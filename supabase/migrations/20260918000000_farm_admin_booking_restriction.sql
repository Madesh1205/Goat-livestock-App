-- ============================================================================
-- MIGRATION: 20260918000000_farm_admin_booking_restriction.sql
-- Description: Enforce server-side restriction that FARM_ADMIN accounts CAN book goats,
-- but MUST NOT book goats belonging to their OWN farm.
-- CUSTOMER -> can book from any eligible farm
-- FARM_ADMIN -> can book from OTHER farms, rejected for OWN farm
-- SUPER_ADMIN -> keep existing behavior
-- ============================================================================

-- 1. Update create_booking_hold RPC Function
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
      AND hold_expires_at < NOW();

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

    v_hold_expires := NOW() + INTERVAL '48 hours';

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
        'message', 'Goat hold reserved successfully for 48 hours.'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 2. Update enforce_booking_price_snapshot Trigger Function
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
            SELECT role, farm_id INTO v_user_role, v_user_farm_id FROM public.profiles WHERE id = v_auth_uid;
            NEW.customer_id := v_auth_uid;
        END IF;

        -- First release any expired hold on this goat
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW();

        -- Fetch authoritative goat listing from database (do not trust client-supplied farm_id)
        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        -- Fetch authoritative farm details
        SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;

        -- Check own-farm booking restriction for FARM_ADMIN or farm owners
        IF v_auth_uid IS NOT NULL THEN
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
        NEW.hold_expires_at := COALESCE(NEW.hold_expires_at, NOW() + INTERVAL '48 hours');
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. Update RLS Policy for public.bookings INSERT to allow FARM_ADMIN for other farms
DROP POLICY IF EXISTS "bookings_insert_policy" ON public.bookings;
CREATE POLICY "bookings_insert_policy" ON public.bookings
FOR INSERT WITH CHECK (
    auth.uid() = customer_id AND
    EXISTS (
        SELECT 1 FROM public.profiles p
        WHERE p.id = auth.uid() AND (
            p.role = 'SUPER_ADMIN' OR
            p.role = 'CUSTOMER' OR
            (p.role = 'FARM_ADMIN' AND NOT EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = bookings.farm_id AND f.owner_id = auth.uid()
            ) AND (p.farm_id IS NULL OR p.farm_id != bookings.farm_id))
        )
    )
);
