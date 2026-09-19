-- ============================================================================
-- MIGRATION: 20260918010000_stage3_booking_hold_24h_expiration.sql
-- Description: Update goat reservation hold duration from 48 hours to exactly 24 hours.
-- Server/Database authoritative expiration logic:
-- 1. Updates create_booking_hold RPC function (hold_expires_at = NOW() + INTERVAL '24 hours')
-- 2. Updates create_goat_booking_atomic function (hold_expires_at = NOW() + INTERVAL '24 hours')
-- 3. Updates enforce_booking_price_snapshot trigger (NEW.hold_expires_at = NOW() + INTERVAL '24 hours')
-- 4. Updates expire_overdue_bookings procedure to cleanly expire overdue 24h holds and release goats
-- 5. Updates notify_booking_lifecycle to reflect 24-hour hold lifecycle notifications
-- 6. Sets column default on bookings.hold_expires_at to NOW() + INTERVAL '24 hours'
-- Preserves:
-- - FARM_ADMIN own-farm booking restriction (CAN book other farms, CANNOT book own farm)
-- - Row-level locking on goats (FOR UPDATE)
-- - Automatic goat release to AVAILABLE on EXPIRED / CANCELLED
-- - CONFIRMED and COMPLETED bookings are NEVER expired
-- ============================================================================

-- 1. Alter Column Default for hold_expires_at
ALTER TABLE public.bookings 
ALTER COLUMN hold_expires_at SET DEFAULT (NOW() + INTERVAL '24 hours');

-- 2. Update create_booking_hold RPC Function
CREATE OR REPLACE FUNCTION public.create_booking_hold(
    p_goat_id UUID,
    p_notes TEXT DEFAULT NULL,
    p_customer_id UUID DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_customer RECORD;
    v_booking_id UUID;
    v_effective_customer_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_hold_expires TIMESTAMPTZ;
BEGIN
    -- 1. Determine effective customer ID (prefer auth.uid())
    IF auth.uid() IS NOT NULL THEN
        v_effective_customer_id := auth.uid();
    ELSIF p_customer_id IS NOT NULL THEN
        v_effective_customer_id := p_customer_id;
    ELSE
        RAISE EXCEPTION 'Authentication required to reserve livestock.';
    END IF;

    -- 2. Expire any overdue holds for this goat before evaluating availability
    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at <= NOW();

    -- 3. Lock the goat row with FOR UPDATE to prevent race conditions & double-booking
    SELECT * INTO v_goat 
    FROM public.goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 4. Validate customer profile and role
    SELECT * INTO v_customer FROM public.profiles WHERE id = v_effective_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    -- 5. Fetch authoritative farm details from database
    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    -- 6. Own-farm booking restriction for FARM_ADMIN or farm owners
    IF v_customer.role = 'FARM_ADMIN' THEN
        IF (v_customer.farm_id IS NOT NULL AND v_customer.farm_id = v_goat.farm_id)
           OR (v_farm.owner_id = v_effective_customer_id) THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;
    ELSIF v_farm.owner_id = v_effective_customer_id THEN
        RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
    END IF;

    -- 7. Validate goat availability and approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    -- 8. Prevent duplicate active booking for the same goat
    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = v_goat.id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    -- 9. Server calculates authoritative price snapshot with discount
    v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    -- Authoritative 24-hour hold expiration
    v_hold_expires := NOW() + INTERVAL '24 hours';

    -- 10. Insert booking record
    INSERT INTO public.bookings (
        goat_id,
        farm_id,
        customer_id,
        status,
        booking_date,
        hold_expires_at,
        total_price,
        notes,
        created_at,
        updated_at
    ) VALUES (
        v_goat.id,
        v_goat.farm_id,
        v_effective_customer_id,
        'RESERVED',
        NOW(),
        v_hold_expires,
        v_effective_price,
        p_notes,
        NOW(),
        NOW()
    )
    RETURNING id INTO v_booking_id;

    -- 11. Update goat status to RESERVED atomically
    UPDATE public.goats
    SET status = 'RESERVED', updated_at = NOW()
    WHERE id = v_goat.id;

    RETURN jsonb_build_object(
        'success', true,
        'booking_id', v_booking_id,
        'goat_id', v_goat.id,
        'farm_id', v_goat.farm_id,
        'customer_id', v_effective_customer_id,
        'total_price', v_effective_price,
        'status', 'RESERVED',
        'hold_expires_at', v_hold_expires,
        'message', 'Goat hold reserved successfully for 24 hours.'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Update create_goat_booking_atomic Function
CREATE OR REPLACE FUNCTION public.create_goat_booking_atomic(
    p_goat_id UUID,
    p_customer_id UUID DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_auth_uid UUID;
    v_effective_customer_id UUID;
    v_goat RECORD;
    v_farm RECORD;
    v_customer RECORD;
    v_booking_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_hold_expires TIMESTAMPTZ;
BEGIN
    -- 1. Resolve authenticated caller
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        v_effective_customer_id := v_auth_uid;
    ELSE
        IF p_customer_id IS NULL THEN
            RAISE EXCEPTION 'Authentication required to create a booking.';
        END IF;
        v_effective_customer_id := p_customer_id;
    END IF;

    -- 2. Expire any overdue holds for this goat before evaluating availability
    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at <= NOW();

    -- 3. Lock the goat row with FOR UPDATE to prevent race conditions & double-booking
    SELECT * INTO v_goat 
    FROM public.goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 4. Validate customer profile and role
    SELECT * INTO v_customer FROM public.profiles WHERE id = v_effective_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    -- Customer or Farm Admin can book (Farm Admin cannot book own farm)
    IF v_customer.role NOT IN ('CUSTOMER', 'FARM_ADMIN', 'SUPER_ADMIN') THEN
        RAISE EXCEPTION 'Account role is not eligible to place bookings.';
    END IF;

    -- 5. Check farm status
    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    -- 6. Validate goat availability and approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    -- 7. Own-farm booking restriction
    IF v_customer.role = 'FARM_ADMIN' THEN
        IF (v_customer.farm_id IS NOT NULL AND v_customer.farm_id = v_goat.farm_id)
           OR (v_farm.owner_id = v_effective_customer_id) THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;
    ELSIF v_farm.owner_id = v_effective_customer_id THEN
        RAISE EXCEPTION 'Breeders cannot place booking holds on their own farm listings.';
    END IF;

    -- 8. Prevent duplicate active booking for the same goat
    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = v_goat.id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    -- 9. Server calculates authoritative price snapshot with discount
    v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    -- Authoritative 24-hour hold expiration
    v_hold_expires := NOW() + INTERVAL '24 hours';

    -- 10. Insert booking record
    INSERT INTO public.bookings (
        goat_id,
        farm_id,
        customer_id,
        status,
        booking_date,
        hold_expires_at,
        total_price,
        deposit_paid,
        customer_notes,
        created_at,
        updated_at
    ) VALUES (
        v_goat.id,
        v_goat.farm_id,
        v_effective_customer_id,
        'RESERVED',
        NOW(),
        v_hold_expires,
        v_effective_price,
        0.00,
        SUBSTRING(p_notes FROM 1 FOR 500),
        NOW(),
        NOW()
    ) RETURNING id INTO v_booking_id;

    -- 11. Atomically transition goat availability to RESERVED
    UPDATE public.goats
    SET status = 'RESERVED', updated_at = NOW()
    WHERE id = v_goat.id;

    -- 12. Create lifecycle notifications
    IF v_farm.owner_id IS NOT NULL THEN
        INSERT INTO public.notifications (
            user_id,
            title,
            message,
            notification_type,
            related_entity_id,
            event_key
        ) VALUES (
            v_farm.owner_id,
            'New Booking Received 📋',
            format('A customer placed a 24h hold on %s (₹%s). Contact customer to coordinate pickup.', v_goat.name, v_effective_price::text),
            'BOOKING',
            v_booking_id::text,
            'booking_created_farm_' || v_booking_id::text
        ) ON CONFLICT (event_key) DO NOTHING;
    END IF;

    INSERT INTO public.notifications (
        user_id,
        title,
        message,
        notification_type,
        related_entity_id,
        event_key
    ) VALUES (
        v_effective_customer_id,
        'Reservation Active (24 Hours) 🐐',
        format('You reserved %s for ₹%s. 24-hour hold is active. Breeder will contact you.', v_goat.name, v_effective_price::text),
        'BOOKING',
        v_booking_id::text,
        'booking_created_cust_' || v_booking_id::text
    ) ON CONFLICT (event_key) DO NOTHING;

    RETURN jsonb_build_object(
        'success', true,
        'booking_id', v_booking_id,
        'goat_id', v_goat.id,
        'farm_id', v_goat.farm_id,
        'customer_id', v_effective_customer_id,
        'amount', v_effective_price,
        'status', 'RESERVED',
        'hold_expires_at', v_hold_expires,
        'message', 'Goat hold reserved successfully for 24 hours.'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 4. Update enforce_booking_price_snapshot Trigger Function
CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_effective_price NUMERIC(12, 2);
    v_user_role TEXT;
    v_user_farm_id UUID;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();

    IF TG_OP = 'INSERT' THEN
        -- If auth.uid() is available, force customer_id to auth.uid()
        IF v_auth_uid IS NOT NULL THEN
            NEW.customer_id := v_auth_uid;
        END IF;

        -- Release any expired holds on this goat before checking availability
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at <= NOW();

        -- Fetch authoritative goat listing
        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        -- Fetch farm details
        SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
        IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
            RAISE EXCEPTION 'Farm is not active or approved.';
        END IF;

        -- Validate caller profile & own-farm booking restriction
        IF v_auth_uid IS NOT NULL THEN
            SELECT role, farm_id INTO v_user_role, v_user_farm_id 
            FROM public.profiles 
            WHERE id = v_auth_uid;

            IF v_user_role = 'FARM_ADMIN' THEN
                IF (v_user_farm_id IS NOT NULL AND v_user_farm_id = v_goat.farm_id)
                   OR (v_farm.owner_id = v_auth_uid) THEN
                    RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
                END IF;
            ELSIF v_farm.owner_id = v_auth_uid THEN
                RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
            END IF;
        END IF;

        -- Prevent duplicate active booking
        IF EXISTS (
            SELECT 1 FROM public.bookings
            WHERE goat_id = NEW.goat_id
              AND id != COALESCE(NEW.id, '00000000-0000-0000-0000-000000000000'::uuid)
              AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
              AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
        ) THEN
            RAISE EXCEPTION 'This goat has already been reserved by another customer.';
        END IF;

        IF v_goat.status != 'AVAILABLE' THEN
            RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
        END IF;

        IF v_goat.is_approved_by_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Goat listing is not approved by administrator.';
        END IF;

        -- Authoritative price calculation (base price minus discount)
        v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
        IF v_effective_price < 0 THEN
            v_effective_price := 0.00;
        END IF;

        -- Overwrite client-supplied values with server-authoritative values
        NEW.total_price := v_effective_price;
        NEW.farm_id := v_goat.farm_id;
        IF NEW.status IS NULL OR NEW.status NOT IN ('PENDING', 'RESERVED') THEN
            NEW.status := 'RESERVED';
        END IF;
        -- Enforce authoritative 24-hour expiration window
        NEW.hold_expires_at := NOW() + INTERVAL '24 hours';
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5. Update expire_overdue_bookings Procedure
CREATE OR REPLACE FUNCTION public.expire_overdue_bookings()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Expire any active holds (PENDING or RESERVED) that have passed their hold_expires_at
    -- Explicitly leaves CONFIRMED and COMPLETED bookings untouched
    WITH expired_records AS (
        UPDATE public.bookings
        SET status = 'EXPIRED',
            updated_at = NOW()
        WHERE status IN ('PENDING', 'RESERVED')
          AND hold_expires_at <= NOW()
        RETURNING id, goat_id
    ),
    released_goats AS (
        UPDATE public.goats g
        SET status = 'AVAILABLE',
            updated_at = NOW()
        FROM expired_records er
        WHERE g.id = er.goat_id
          AND NOT EXISTS (
              SELECT 1 FROM public.bookings b
              WHERE b.goat_id = er.goat_id
                AND b.status IN ('PENDING', 'RESERVED', 'CONFIRMED')
                AND (b.hold_expires_at IS NULL OR b.hold_expires_at > NOW())
          )
        RETURNING g.id
    )
    SELECT COUNT(*) INTO v_count FROM expired_records;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- 6. Update notify_booking_lifecycle Trigger Function
CREATE OR REPLACE FUNCTION public.notify_booking_lifecycle()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_booking_ref TEXT;
BEGIN
    SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
    SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
    v_booking_ref := COALESCE(NEW.booking_code, SUBSTRING(NEW.id::text FROM 1 FOR 6));

    IF TG_OP = 'INSERT' THEN
        -- Customer notification: 24h hold active
        PERFORM public.create_system_notification(
            NEW.customer_id,
            'Reservation Active (24 Hours) 🐐',
            'Your reservation for ' || COALESCE(v_goat.name, 'Goat') || ' is active. Complete farm confirmation within 24 hours.',
            'BOOKING',
            NEW.id::text,
            'booking_created_' || NEW.id
        );

        -- Farm Admin notification: new reservation received
        IF v_farm.owner_id IS NOT NULL THEN
            PERFORM public.create_system_notification(
                v_farm.owner_id,
                'New Booking Received 📋',
                'New booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' (₹' || NEW.total_price || ').',
                'FARM_BOOKINGS',
                NEW.id::text,
                'booking_farm_notify_' || NEW.id
            );
        END IF;

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'CONFIRMED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Confirmed! ✅',
                'Your booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' has been confirmed by ' || COALESCE(v_farm.name, 'the farm') || '.',
                'BOOKING',
                NEW.id::text,
                'booking_confirmed_' || NEW.id
            );
        ELSIF NEW.status = 'COMPLETED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Purchase Completed 🎉',
                'Congratulations on your purchase of ' || COALESCE(v_goat.name, 'Goat') || '!',
                'BOOKING',
                NEW.id::text,
                'booking_completed_' || NEW.id
            );
        ELSIF NEW.status = 'CANCELLED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Cancelled ❌',
                'Your booking #' || v_booking_ref || ' has been cancelled.',
                'BOOKING',
                NEW.id::text,
                'booking_cancelled_' || NEW.id
            );
        ELSIF NEW.status = 'EXPIRED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                '24-Hour Hold Expired ⏳',
                'Your 24-hour reservation hold on ' || COALESCE(v_goat.name, 'Goat') || ' has expired and was released.',
                'BOOKING',
                NEW.id::text,
                'booking_expired_' || NEW.id
            );
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
