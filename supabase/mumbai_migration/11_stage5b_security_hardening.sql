-- =============================================================================
-- AMMAL FARM PLATFORM — MUMBAI MIGRATION (ap-south-1)
-- File: 11_stage5b_security_hardening.sql
-- Step 11: Production Security Hardening (Stage 5B)
--
-- Enforces:
-- 1. Profiles Privilege Escalation Defense (Role & Farm ID lockdown)
-- 2. Farm Metadata Integrity Protection (Blocks unapproved status/quota/ammal tampering)
-- 3. Goat Listing Quota Enforcement (INSERT & UPDATE, Ammal Farm exempt)
-- 4. Booking Update Security (Locks price, IDs, expiry; blocks self-confirm/complete)
-- 5. Farm Admin Booking Rules (Customer any farm; Farm Admin other farms only)
-- 6. SECURITY DEFINER Search Path Enforcement (SET search_path = public, pg_temp)
-- 7. Goat Images RLS Hardening (Removes USING (true))
-- =============================================================================

SET search_path = public, pg_temp;

-- =============================================================================
-- 1. PROFILES PRIVILEGE ESCALATION HARDENING
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_profile_security_guard()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID := auth.uid();
BEGIN
    -- 1. Direct DB admin / SQL Editor / backend service role (auth.uid() is NULL):
    IF v_auth_uid IS NULL THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 2. Check if current authenticated caller is Super Admin (profiles.role is source of truth)
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    IF v_is_super_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 3. Non-Super Admins: Enforce strict constraints
    IF TG_OP = 'INSERT' THEN
        IF NEW.role = 'SUPER_ADMIN' THEN
            NEW.role := 'CUSTOMER';
        END IF;
        IF NEW.role = 'CUSTOMER' THEN
            NEW.farm_id := NULL;
        END IF;
        NEW.is_suspended := FALSE;

    ELSIF TG_OP = 'UPDATE' THEN
        NEW.role := OLD.role;
        NEW.is_suspended := OLD.is_suspended;

        IF OLD.role = 'CUSTOMER' THEN
            NEW.farm_id := NULL;
        ELSIF OLD.role = 'FARM_ADMIN' THEN
            IF OLD.farm_id IS NOT NULL AND NEW.farm_id IS DISTINCT FROM OLD.farm_id THEN
                NEW.farm_id := OLD.farm_id;
            END IF;
        END IF;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_profile_security_guard ON public.profiles;
CREATE TRIGGER tr_enforce_profile_security_guard
    BEFORE INSERT OR UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.enforce_profile_security_guard();


-- =============================================================================
-- 2. FARM METADATA INTEGRITY PROTECTION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID := auth.uid();
BEGIN
    IF v_auth_uid IS NULL THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    IF v_is_super_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        NEW.is_ammal_own_farm := FALSE;
        NEW.goat_listing_limit := 10;
        NEW.status := 'PENDING'::public.farm_status;
        NEW.owner_id := v_auth_uid;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Only Super Admin can modify farm status.';
        END IF;

        IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
            RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
        END IF;

        IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
            RAISE EXCEPTION 'Ammal Farm designation cannot be modified.';
        END IF;

        IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
            RAISE EXCEPTION 'Farm ownership cannot be modified.';
        END IF;

        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_farm_metadata_integrity ON public.farms;
CREATE TRIGGER tr_enforce_farm_metadata_integrity
    BEFORE INSERT OR UPDATE ON public.farms
    FOR EACH ROW EXECUTE FUNCTION public.enforce_farm_metadata_integrity();


-- =============================================================================
-- 3. GOAT LISTING QUOTA ENFORCEMENT (INSERT & UPDATE)
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 10;
    v_current_count INT := 0;
BEGIN
    SELECT (f.id = '00000000-0000-0000-0000-000000000001'::uuid OR f.is_ammal_own_farm = TRUE),
           COALESCE(f.goat_listing_limit, 10)
    INTO v_is_ammal, v_limit
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF v_is_ammal = TRUE THEN
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF OLD.farm_id = NEW.farm_id AND OLD.status != 'INACTIVE' AND NEW.status != 'INACTIVE' THEN
            RETURN NEW;
        END IF;
    END IF;

    IF NEW.status = 'INACTIVE' THEN
        RETURN NEW;
    END IF;

    SELECT COUNT(*) INTO v_current_count
    FROM public.goats
    WHERE farm_id = NEW.farm_id
      AND status != 'INACTIVE'
      AND (TG_OP = 'INSERT' OR id != NEW.id);

    IF v_current_count >= v_limit THEN
        RAISE EXCEPTION 'Listing limit of % reached for partner farm. Contact Super Admin to increase your listing limit.', v_limit;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();


-- =============================================================================
-- 4. BOOKING UPDATE SECURITY & FARM ADMIN BOOKING RULES
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_effective_price NUMERIC(12, 2);
    v_user_role TEXT;
    v_user_farm_id UUID;
    v_auth_uid UUID := auth.uid();
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF v_auth_uid IS NOT NULL THEN
            NEW.customer_id := v_auth_uid;
        END IF;

        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at <= NOW();

        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
        IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
            RAISE EXCEPTION 'Farm is not active or approved.';
        END IF;

        -- Farm Admin booking rules:
        -- CUSTOMER: Can book goats from any farm.
        -- FARM_ADMIN: Can book goats from other farms, cannot book goats from their own farm.
        -- SUPER_ADMIN: Preserve existing behavior.
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            SELECT role, farm_id INTO v_user_role, v_user_farm_id 
            FROM public.profiles 
            WHERE id = v_auth_uid;

            IF v_user_role = 'FARM_ADMIN' THEN
                IF (v_user_farm_id IS NOT NULL AND v_user_farm_id = v_goat.farm_id)
                   OR (v_farm.owner_id = v_auth_uid) THEN
                    RAISE EXCEPTION 'Farm Admins cannot book goats listed by their own farm.';
                END IF;
            ELSIF v_user_role = 'CUSTOMER' THEN
                NULL;
            ELSIF v_farm.owner_id = v_auth_uid THEN
                RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
            END IF;
        END IF;

        IF v_goat.status != 'AVAILABLE' THEN
            RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
        END IF;

        IF v_goat.is_approved_by_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Goat listing is not approved by administrator.';
        END IF;

        IF EXISTS (
            SELECT 1 FROM public.bookings
            WHERE goat_id = NEW.goat_id
              AND id != COALESCE(NEW.id, '00000000-0000-0000-0000-000000000000'::uuid)
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
        IF v_auth_uid IS NULL OR v_is_super_admin IS TRUE THEN
            NEW.updated_at := NOW();
            RETURN NEW;
        END IF;

        -- 1. Prevent changing total_price
        IF NEW.total_price IS DISTINCT FROM OLD.total_price THEN
            RAISE EXCEPTION 'Total price cannot be modified on an existing booking.';
        END IF;

        -- 2. Prevent changing goat_id / customer_id / farm_id / booking_date
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

        -- 3. Prevent extending hold_expires_at
        IF NEW.hold_expires_at > OLD.hold_expires_at THEN
            RAISE EXCEPTION 'Reservation hold expiry cannot be extended.';
        END IF;

        -- 4. Terminal status transitions
        IF OLD.status = 'CANCELLED' AND NEW.status != 'CANCELLED' THEN
            RAISE EXCEPTION 'Cannot re-activate a cancelled booking.';
        END IF;
        IF OLD.status = 'EXPIRED' AND NEW.status != 'EXPIRED' THEN
            RAISE EXCEPTION 'Cannot re-activate an expired booking.';
        END IF;
        IF OLD.status = 'COMPLETED' AND NEW.status != 'COMPLETED' THEN
            RAISE EXCEPTION 'Cannot modify a completed booking.';
        END IF;

        -- 5. Prevent self-confirming and self-completing
        IF NEW.status IN ('CONFIRMED', 'COMPLETED') THEN
            IF NOT EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = OLD.farm_id AND f.owner_id = v_auth_uid
            ) THEN
                RAISE EXCEPTION 'Only the listing farm owner or Super Admin can confirm or complete a booking.';
            END IF;
        END IF;

        -- Allow legitimate cancellation
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

DROP TRIGGER IF EXISTS tr_enforce_booking_price_snapshot ON public.bookings;
CREATE TRIGGER tr_enforce_booking_price_snapshot
    BEFORE INSERT OR UPDATE ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_price_snapshot();


-- =============================================================================
-- 5. ATOMIC BOOKING HOLD RPC (SAFE SEARCH PATH & SERVER-SIDE RULES)
-- =============================================================================

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
    IF auth.uid() IS NOT NULL THEN
        v_effective_customer_id := auth.uid();
    ELSIF p_customer_id IS NOT NULL THEN
        v_effective_customer_id := p_customer_id;
    ELSE
        RAISE EXCEPTION 'Authentication required to reserve livestock.';
    END IF;

    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at <= NOW();

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
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    IF v_customer.role = 'FARM_ADMIN' THEN
        IF (v_customer.farm_id IS NOT NULL AND v_customer.farm_id = v_goat.farm_id)
           OR (v_farm.owner_id = v_effective_customer_id) THEN
            RAISE EXCEPTION 'Farm Admins cannot book goats listed by their own farm.';
        END IF;
    ELSIF v_customer.role = 'CUSTOMER' THEN
        NULL;
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
        WHERE goat_id = v_goat.id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    v_hold_expires := NOW() + INTERVAL '24 hours';

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
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.create_goat_booking_atomic(
    p_goat_id UUID,
    p_customer_id UUID DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
BEGIN
    RETURN public.create_booking_hold(
        p_goat_id => p_goat_id,
        p_notes => p_notes,
        p_customer_id => p_customer_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;


-- =============================================================================
-- 6. GOAT IMAGES RLS HARDENING (REMOVE USING (true))
-- =============================================================================

DROP POLICY IF EXISTS "goat_images_select_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_select_all" ON public.goat_images;

CREATE POLICY "goat_images_select_policy" ON public.goat_images
    FOR SELECT TO public
    USING (
        EXISTS (
            SELECT 1 FROM public.goats g
            WHERE g.id = goat_images.goat_id
              AND (
                  (g.status != 'INACTIVE' AND g.is_approved_by_admin = TRUE)
                  OR (auth.uid() IS NOT NULL AND EXISTS (
                      SELECT 1 FROM public.farms f
                      WHERE f.id = g.farm_id
                        AND f.owner_id = auth.uid()
                  ))
                  OR public.is_super_admin()
              )
        )
    );

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

DROP POLICY IF EXISTS "bookings_update_policy" ON public.bookings;
CREATE POLICY "bookings_update_policy" ON public.bookings
    FOR UPDATE TO authenticated
    USING (
        customer_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = bookings.farm_id AND f.owner_id = auth.uid()
        )
        OR public.is_super_admin()
    )
    WITH CHECK (
        customer_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = bookings.farm_id AND f.owner_id = auth.uid()
        )
        OR public.is_super_admin()
    );


-- =============================================================================
-- 7. REINFORCE SAFE SEARCH_PATH ON ALL SECURITY DEFINER FUNCTIONS
-- =============================================================================

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args
        FROM pg_proc p
        JOIN pg_namespace n ON n.oid = p.pronamespace
        WHERE n.nspname = 'public'
          AND p.prosecdef = TRUE
    ) LOOP
        EXECUTE format('ALTER FUNCTION public.%I(%s) SET search_path = public, pg_temp;', r.proname, r.args);
    END LOOP;
END $$;


-- =============================================================================
-- 8. COMPREHENSIVE POST-FIX SECURITY AUDIT QUERY
-- =============================================================================

DO $$
DECLARE
    v_unprotected_tables INT;
    v_broad_image_policies INT;
    v_missing_search_path_funcs INT;
BEGIN
    SELECT COUNT(*) INTO v_unprotected_tables
    FROM pg_tables t
    JOIN pg_class c ON c.relname = t.tablename
    JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.schemaname
    WHERE t.schemaname = 'public'
      AND t.tablename IN (
          'profiles', 'farms', 'breeds', 'goats', 'goat_images',
          'bookings', 'listing_payments', 'reviews', 'wishlist',
          'notifications', 'reports', 'audit_logs'
      )
      AND c.relrowsecurity = FALSE;

    IF v_unprotected_tables > 0 THEN
        RAISE WARNING 'Audit: % table(s) do not have RLS enabled!', v_unprotected_tables;
    ELSE
        RAISE NOTICE 'Audit: ✅ All 12 public tables have RLS enabled.';
    END IF;

    SELECT COUNT(*) INTO v_broad_image_policies
    FROM pg_policies
    WHERE schemaname = 'public'
      AND tablename = 'goat_images'
      AND cmd = 'SELECT'
      AND (qual = 'true' OR qual = '(true)');

    IF v_broad_image_policies > 0 THEN
        RAISE EXCEPTION 'Audit: ❌ goat_images still contains open USING (true) SELECT policy!';
    ELSE
        RAISE NOTICE 'Audit: ✅ goat_images RLS successfully restricted to authorized goat visibility.';
    END IF;

    SELECT COUNT(*) INTO v_missing_search_path_funcs
    FROM pg_proc p
    JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.prosecdef = TRUE
      AND (p.proconfig IS NULL OR NOT ARRAY['search_path=public, pg_temp']::text[] <@ p.proconfig);

    IF v_missing_search_path_funcs > 0 THEN
        RAISE WARNING 'Audit: % SECURITY DEFINER function(s) missing search_path setting.', v_missing_search_path_funcs;
    ELSE
        RAISE NOTICE 'Audit: ✅ All SECURITY DEFINER functions configured with safe search_path.';
    END IF;
END $$;
