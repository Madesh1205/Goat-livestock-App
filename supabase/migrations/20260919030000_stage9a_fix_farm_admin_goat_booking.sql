-- ============================================================================
-- MIGRATION: 20260919030000_stage9a_fix_farm_admin_goat_booking.sql
-- Description: Fix Farm Admin Goat Booking Flow across RPCs, Triggers, and RLS.
-- 
-- Server-Side Rules:
-- 1. FARM_ADMIN + goat.farm_id != current user's farm_id -> ALLOW BOOKING
-- 2. FARM_ADMIN + goat.farm_id == current user's farm_id -> REJECT BOOKING
-- 3. CUSTOMER + eligible goat -> ALLOW BOOKING
-- 4. SUPER_ADMIN -> keep existing behavior
-- ============================================================================

-- 1. UPDATE TRIGGER FUNCTION FOR DIRECT TABLE INSERTS
CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_user_role TEXT;
    v_user_farm_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_auth_uid UUID;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    v_auth_uid := auth.uid();

    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- If authenticated, ensure customer_id is tied to authenticated user
        IF v_auth_uid IS NOT NULL THEN
            NEW.customer_id := v_auth_uid;
        END IF;

        -- Auto-expire overdue holds on this goat
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at <= NOW();

        -- Fetch goat
        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        -- Fetch farm
        SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
        IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
            RAISE EXCEPTION 'Farm is not active or approved.';
        END IF;

        -- Server-side rule check for bookings:
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            SELECT role, farm_id INTO v_user_role, v_user_farm_id 
            FROM public.profiles 
            WHERE id = v_auth_uid;

            IF v_user_farm_id IS NULL AND v_user_role = 'FARM_ADMIN' THEN
                SELECT id INTO v_user_farm_id FROM public.farms WHERE owner_id = v_auth_uid LIMIT 1;
            END IF;

            IF v_user_role = 'FARM_ADMIN' THEN
                IF (v_user_farm_id IS NOT NULL AND v_user_farm_id = v_goat.farm_id)
                   OR (v_farm.owner_id = v_auth_uid) THEN
                    RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
                END IF;
            ELSIF v_user_role = 'CUSTOMER' THEN
                -- Customers can book from any farm
                NULL;
            ELSIF v_farm.owner_id = v_auth_uid THEN
                RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
            END IF;
        END IF;

        -- Validate availability & approval
        IF v_goat.status != 'AVAILABLE' THEN
            RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
        END IF;

        IF v_goat.is_approved_by_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Goat listing is not approved by administrator.';
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

        -- Authoritative price snapshot calculation
        IF v_goat.discount_percentage > 0 THEN
            v_effective_price := ROUND(v_goat.price * (1.0 - (v_goat.discount_percentage / 100.0)), 2);
        ELSE
            v_effective_price := v_goat.price;
        END IF;
        IF v_effective_price < 0 THEN
            v_effective_price := 0.00;
        END IF;

        NEW.total_price := v_effective_price;
        NEW.farm_id := v_goat.farm_id;
        IF NEW.status IS NULL OR NEW.status NOT IN ('PENDING', 'RESERVED') THEN
            NEW.status := 'RESERVED';
        END IF;
        NEW.hold_expires_at := NOW() + INTERVAL '24 hours';
        NEW.booking_date := NOW();
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        IF NEW.booking_code IS NULL OR NEW.booking_code = '' THEN
            NEW.booking_code := 'AMM-' || UPPER(SUBSTRING(REPLACE(gen_random_uuid()::text, '-', '') FROM 1 FOR 6));
        END IF;

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Allow direct DB admin / SQL Editor / cron procedures without restriction
        IF v_auth_uid IS NULL OR v_is_super_admin IS TRUE THEN
            NEW.updated_at := NOW();
            RETURN NEW;
        END IF;

        -- For non-super admins:
        IF NEW.total_price IS DISTINCT FROM OLD.total_price THEN
            RAISE EXCEPTION 'Total price cannot be modified on an existing booking.';
        END IF;
        IF NEW.goat_id IS DISTINCT FROM OLD.goat_id THEN
            RAISE EXCEPTION 'Goat cannot be changed on an existing booking.';
        END IF;
        IF NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN
            RAISE EXCEPTION 'Customer cannot be changed on an existing booking.';
        END IF;
        IF NEW.farm_id IS DISTINCT FROM OLD.farm_id THEN
            RAISE EXCEPTION 'Farm cannot be changed on an existing booking.';
        END IF;
        IF NEW.booking_date IS DISTINCT FROM OLD.booking_date THEN
            NEW.booking_date := OLD.booking_date;
        END IF;
        IF NEW.hold_expires_at > OLD.hold_expires_at THEN
            RAISE EXCEPTION 'Reservation hold expiry cannot be extended.';
        END IF;

        IF OLD.status = 'CANCELLED' AND NEW.status != 'CANCELLED' THEN
            RAISE EXCEPTION 'Cannot re-activate a cancelled booking.';
        END IF;
        IF OLD.status = 'EXPIRED' AND NEW.status != 'EXPIRED' THEN
            RAISE EXCEPTION 'Cannot re-activate an expired booking.';
        END IF;
        IF OLD.status = 'COMPLETED' AND NEW.status != 'COMPLETED' THEN
            RAISE EXCEPTION 'Cannot modify a completed booking.';
        END IF;

        IF NEW.status IN ('CONFIRMED', 'COMPLETED') THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = OLD.farm_id AND f.owner_id = v_auth_uid
            ) THEN
                RAISE EXCEPTION 'Only the listing farm owner or Super Admin can confirm or complete a booking.';
            END IF;
        END IF;

        IF v_auth_uid = OLD.customer_id THEN
            IF NEW.status NOT IN (OLD.status, 'CANCELLED') THEN
                RAISE EXCEPTION 'Customers can only cancel their booking.';
            END IF;
        END IF;

        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;


-- 2. UPDATE ATOMIC BOOKING HOLD RPC
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
    v_user_farm_id UUID;
BEGIN
    -- 1. Determine effective customer ID
    IF auth.uid() IS NOT NULL THEN
        v_effective_customer_id := auth.uid();
    ELSIF p_customer_id IS NOT NULL THEN
        v_effective_customer_id := p_customer_id;
    ELSE
        RAISE EXCEPTION 'Authentication required to reserve livestock.';
    END IF;

    -- 2. Auto-expire any overdue holds on this goat
    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at <= NOW();

    -- 3. Lock the goat row with FOR UPDATE
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

    -- 6. Own-farm booking restriction:
    IF v_customer.role = 'FARM_ADMIN' THEN
        v_user_farm_id := v_customer.farm_id;
        IF v_user_farm_id IS NULL THEN
            SELECT id INTO v_user_farm_id FROM public.farms WHERE owner_id = v_effective_customer_id LIMIT 1;
        END IF;

        IF (v_user_farm_id IS NOT NULL AND v_user_farm_id = v_goat.farm_id)
           OR (v_farm.owner_id = v_effective_customer_id) THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;
    ELSIF v_customer.role = 'CUSTOMER' THEN
        -- Customers can book from any farm
        NULL;
    ELSIF v_farm.owner_id = v_effective_customer_id AND v_customer.role != 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
    END IF;

    -- 7. Validate availability & approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin IS NOT TRUE THEN
        RAISE EXCEPTION 'Goat listing is not approved by administrator.';
    END IF;

    -- 8. Prevent duplicate active booking
    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = v_goat.id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    -- 9. Authoritative price snapshot calculation
    IF v_goat.discount_percentage > 0 THEN
        v_effective_price := ROUND(v_goat.price * (1.0 - (v_goat.discount_percentage / 100.0)), 2);
    ELSE
        v_effective_price := v_goat.price;
    END IF;
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    v_hold_expires := NOW() + INTERVAL '24 hours';
    v_booking_id := gen_random_uuid();

    -- 10. Perform atomic insert
    INSERT INTO public.bookings (
        id,
        goat_id,
        farm_id,
        customer_id,
        status,
        total_price,
        deposit_paid,
        customer_notes,
        booking_date,
        hold_expires_at,
        created_at,
        updated_at
    ) VALUES (
        v_booking_id,
        v_goat.id,
        v_goat.farm_id,
        v_effective_customer_id,
        'RESERVED',
        v_effective_price,
        0.00,
        COALESCE(p_notes, ''),
        NOW(),
        v_hold_expires,
        NOW(),
        NOW()
    );

    -- 11. Update goat status to RESERVED
    UPDATE public.goats
    SET status = 'RESERVED', updated_at = NOW()
    WHERE id = v_goat.id;

    RETURN jsonb_build_object(
        'success', true,
        'booking_id', v_booking_id,
        'goat_id', v_goat.id,
        'farm_id', v_goat.farm_id,
        'customer_id', v_effective_customer_id,
        'status', 'RESERVED',
        'total_price', v_effective_price,
        'hold_expires_at', v_hold_expires
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;


-- 3. UPDATE BOOKINGS INSERT RLS POLICY
DROP POLICY IF EXISTS "bookings_insert_policy" ON public.bookings;
CREATE POLICY "bookings_insert_policy" ON public.bookings
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id = auth.uid()
            AND NOT EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = bookings.farm_id
                  AND (
                      f.owner_id = auth.uid()
                      OR EXISTS (
                          SELECT 1 FROM public.profiles p
                          WHERE p.id = auth.uid()
                            AND p.role = 'FARM_ADMIN'
                            AND p.farm_id = bookings.farm_id
                      )
                  )
            )
        )
    );
