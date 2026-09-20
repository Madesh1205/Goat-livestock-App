-- Migration: Stage 9C - Allow Farm Admin to Book Goats from Other Farms
-- Ensures Farm Admins can reserve goats listed by other partner farms or central Ammal Farm,
-- while strictly blocking them from booking goats listed by their own farm.

-- -----------------------------------------------------------------------------
-- 1. UPDATE TRIGGER FUNCTION: enforce_booking_price_snapshot()
-- -----------------------------------------------------------------------------
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
        -- If authenticated, tie customer_id to current authenticated user
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
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Farm does not exist.';
        END IF;

        -- Check caller role and own-farm restriction
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
            ELSIF v_farm.owner_id = v_auth_uid THEN
                RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
            END IF;
        END IF;

        -- Validate goat availability & approval
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

        -- Calculate price snapshot
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
            NEW.booking_code := 'AGF-' || UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6));
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Re-attach trigger
DROP TRIGGER IF EXISTS trg_enforce_booking_price_snapshot ON public.bookings;
CREATE TRIGGER trg_enforce_booking_price_snapshot
    BEFORE INSERT ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_price_snapshot();


-- -----------------------------------------------------------------------------
-- 2. UPDATE RPC FUNCTION: create_booking_hold()
-- -----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID, TEXT, UUID);
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID, TEXT);
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID);
DROP FUNCTION IF EXISTS public.create_booking_hold;

CREATE OR REPLACE FUNCTION public.create_booking_hold(
    p_goat_id UUID,
    p_notes TEXT DEFAULT NULL,
    p_customer_id UUID DEFAULT NULL
)
RETURNS public.bookings
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_effective_customer_id UUID;
    v_goat RECORD;
    v_farm RECORD;
    v_customer RECORD;
    v_user_farm_id UUID;
    v_booking public.bookings;
    v_effective_price NUMERIC(12, 2);
    v_booking_code TEXT;
BEGIN
    v_effective_customer_id := COALESCE(auth.uid(), p_customer_id);
    IF v_effective_customer_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to create a booking hold.';
    END IF;

    SELECT * INTO v_goat 
    FROM public.goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    SELECT * INTO v_customer FROM public.profiles WHERE id = v_effective_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found.';
    END IF;

    -- Own-farm restriction check
    IF v_customer.role = 'FARM_ADMIN' THEN
        v_user_farm_id := v_customer.farm_id;
        IF v_user_farm_id IS NULL THEN
            SELECT id INTO v_user_farm_id FROM public.farms WHERE owner_id = v_effective_customer_id LIMIT 1;
        END IF;

        IF (v_user_farm_id IS NOT NULL AND v_user_farm_id = v_goat.farm_id)
           OR (v_farm.owner_id = v_effective_customer_id) THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;
    ELSIF v_farm.owner_id = v_effective_customer_id AND v_customer.role != 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
    END IF;

    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = p_goat_id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    IF v_goat.discount_percentage > 0 THEN
        v_effective_price := ROUND(v_goat.price * (1.0 - (v_goat.discount_percentage / 100.0)), 2);
    ELSE
        v_effective_price := v_goat.price;
    END IF;

    v_booking_code := 'AGF-' || UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6));

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
        booking_code
    ) VALUES (
        p_goat_id,
        v_goat.farm_id,
        v_effective_customer_id,
        'RESERVED',
        NOW(),
        NOW() + INTERVAL '24 hours',
        v_effective_price,
        0.00,
        p_notes,
        v_booking_code
    )
    RETURNING * INTO v_booking;

    UPDATE public.goats
    SET status = 'RESERVED',
        updated_at = NOW()
    WHERE id = p_goat_id;

    RETURN v_booking;
END;
$$;


-- -----------------------------------------------------------------------------
-- 3. UPDATE RLS POLICIES FOR public.bookings
-- -----------------------------------------------------------------------------
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
                  AND f.owner_id = auth.uid()
            )
        )
    );

DROP POLICY IF EXISTS "bookings_select_policy" ON public.bookings;
CREATE POLICY "bookings_select_policy" ON public.bookings
    FOR SELECT TO authenticated
    USING (
        customer_id = auth.uid()
        OR EXISTS (
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
        OR public.is_super_admin()
    );
