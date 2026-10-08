-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI MIGRATION (ap-south-1)
-- File: 02_functions_and_triggers.sql
-- Step 2: Functions, Stored Procedures, Business Logic, and Automated Triggers
-- =============================================================================

-- =============================================================================
-- 1. SECURITY DEFINER HELPER FUNCTIONS (WITH SAFE SEARCH_PATH)
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_auth_role()
RETURNS TEXT AS $$
    SELECT role::text FROM public.profiles WHERE id::text = auth.uid()::text;
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.is_super_admin()
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id::text = auth.uid()::text AND role::text = 'SUPER_ADMIN'
    );
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.get_auth_farm_id()
RETURNS UUID AS $$
    SELECT id FROM public.farms WHERE owner_id::text = auth.uid()::text LIMIT 1;
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 2. STORAGE AUTHORIZATION FUNCTIONS
-- =============================================================================

CREATE OR REPLACE FUNCTION public.can_access_storage_farm_path(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_farm_uuid UUID;
BEGIN
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern A: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' AND v_target != '' THEN
        BEGIN
            v_farm_uuid := v_target::UUID;
            RETURN EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = v_farm_uuid
                  AND (
                      f.owner_id = auth.uid()
                      OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
                  )
            );
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern B: '<farm_id>/...' (direct farm UUID at root)
    BEGIN
        v_farm_uuid := v_prefix::UUID;
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = v_farm_uuid
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.can_modify_storage_goat_image(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_farm_uuid UUID;
BEGIN
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    -- Only FARM_ADMIN or SUPER_ADMIN can modify goat images
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id = auth.uid() AND role IN ('FARM_ADMIN', 'SUPER_ADMIN')
    ) THEN
        RETURN FALSE;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern 1: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' AND v_target != '' THEN
        BEGIN
            v_farm_uuid := v_target::UUID;
            RETURN EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = v_farm_uuid
                  AND (
                      f.owner_id = auth.uid()
                      OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
                  )
            );
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern 2: '<farm_id>/...'
    BEGIN
        v_farm_uuid := v_prefix::UUID;
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = v_farm_uuid
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 3. SYSTEM NOTIFICATION CREATION WITH DEDUPLICATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.create_system_notification(
    p_user_id UUID,
    p_title TEXT,
    p_body TEXT,
    p_link_type TEXT DEFAULT 'GENERAL',
    p_link_id TEXT DEFAULT NULL,
    p_event_key TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_notification_id UUID := gen_random_uuid();
BEGIN
    INSERT INTO public.notifications (
        id, user_id, title, body, link_type, link_id, event_key, is_read, created_at
    ) VALUES (
        v_notification_id, p_user_id, p_title, p_body, p_link_type, p_link_id, p_event_key, FALSE, NOW()
    )
    ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL
    DO NOTHING;

    RETURN v_notification_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 4. AUTH SIGNUP & PROFILE / FARM PROVISIONING TRIGGER
-- =============================================================================

CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
DECLARE
    v_raw_role TEXT;
    v_role public.user_role;
    v_existing_role public.user_role;
    v_full_name TEXT;
    v_phone TEXT;
    v_farm_name TEXT;
    v_farm_district TEXT;
    v_farm_description TEXT;
    v_farm_id UUID;
BEGIN
    -- Extract full name and phone safely from metadata or direct fields
    v_full_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''), 'Ammal User');
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Strict Role Assignment: NEVER allow SUPER_ADMIN privilege escalation from client metadata!
    v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
    IF v_raw_role = 'FARM_ADMIN' THEN
        v_role := 'FARM_ADMIN'::public.user_role;
    ELSE
        v_role := 'CUSTOMER'::public.user_role;
    END IF;

    -- profiles.role is the authoritative source of truth.
    -- If profile already exists (e.g. initial SUPER_ADMIN provisioned), preserve its existing role.
    SELECT role INTO v_existing_role FROM public.profiles WHERE id = NEW.id;
    IF v_existing_role IS NOT NULL THEN
        v_role := v_existing_role;
    END IF;

    -- Insert or Update profile for auth.users(id)
    INSERT INTO public.profiles (
        id,
        full_name,
        email,
        phone,
        role,
        created_at,
        updated_at
    )
    VALUES (
        NEW.id,
        v_full_name,
        NEW.email,
        v_phone,
        v_role,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = EXCLUDED.email,
        phone = COALESCE(EXCLUDED.phone, public.profiles.phone),
        updated_at = NOW();

    -- If FARM_ADMIN applicant, create their farm in PENDING status atomically
    IF v_role = 'FARM_ADMIN'::public.user_role THEN
        v_farm_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_name'), ''), v_full_name || ' Farm');
        v_farm_district := COALESCE(
            NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_district'), ''),
            NULLIF(TRIM(NEW.raw_user_meta_data->>'location_district'), ''),
            'Madurai'
        );
        v_farm_description := NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_description'), '');

        -- Check if farm already exists for this owner
        SELECT id INTO v_farm_id FROM public.farms WHERE owner_id = NEW.id LIMIT 1;

        IF v_farm_id IS NULL THEN
            v_farm_id := gen_random_uuid();
            INSERT INTO public.farms (
                id,
                name,
                owner_id,
                description,
                location_district,
                location_state,
                address,
                contact_phone,
                contact_email,
                status,
                is_ammal_own_farm,
                goat_listing_limit,
                rating,
                review_count,
                created_at,
                updated_at
            )
            VALUES (
                v_farm_id,
                v_farm_name,
                NEW.id,
                v_farm_description,
                v_farm_district,
                'Tamil Nadu',
                v_farm_district || ', Tamil Nadu',
                COALESCE(v_phone, 'Not provided'),
                NEW.email,
                'PENDING'::public.farm_status,
                FALSE,
                10,
                5.00,
                0,
                NOW(),
                NOW()
            );
        END IF;

        -- Associate farm_id back to profile
        UPDATE public.profiles
        SET farm_id = v_farm_id, updated_at = NOW()
        WHERE id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Bind trigger to auth.users
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();

-- =============================================================================
-- 5. BOOKING SYSTEM BUSINESS LOGIC & EXPIRATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.expire_overdue_bookings()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Expire any active holds (PENDING or RESERVED) that have reached their hold_expires_at
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

CREATE OR REPLACE FUNCTION public.handle_booking_status_change()
RETURNS TRIGGER AS $$
DECLARE
    v_current_goat_status public.goat_status;
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        -- Completed booking marks the goat as permanently SOLD
        UPDATE public.goats
        SET status = 'SOLD',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status = 'CONFIRMED' THEN
        -- Confirmed booking keeps the goat as CONFIRMED
        UPDATE public.goats
        SET status = 'CONFIRMED',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status IN ('RESERVED', 'PENDING') THEN
        -- Reserved or Pending hold places the goat into RESERVED state
        UPDATE public.goats
        SET status = 'RESERVED',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status IN ('CANCELLED', 'EXPIRED') THEN
        -- Check current goat status before attempting to release to AVAILABLE.
        -- SOLD is permanent and must NEVER be reverted to AVAILABLE upon cancellation or expiration.
        SELECT status INTO v_current_goat_status
        FROM public.goats
        WHERE id = NEW.goat_id;

        IF v_current_goat_status IS DISTINCT FROM 'SOLD' THEN
            -- Only restore goat to AVAILABLE if no other active booking exists
            IF NOT EXISTS (
                SELECT 1 FROM public.bookings 
                WHERE goat_id = NEW.goat_id 
                  AND id != NEW.id 
                  AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
            ) THEN
                UPDATE public.goats
                SET status = 'AVAILABLE',
                    updated_at = NOW()
                WHERE id = NEW.goat_id
                  AND status != 'SOLD';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_booking_status_change ON public.bookings;
CREATE TRIGGER tr_booking_status_change
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.handle_booking_status_change();

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

        -- Auto-expire overdue holds on this goat
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

        -- Server-side rule check for bookings:
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

        -- 5. Status permissions:
        -- Prevent self-confirming and self-completing by customers
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
DROP TRIGGER IF EXISTS tr_enforce_booking_amount ON public.bookings;
CREATE TRIGGER tr_enforce_booking_price_snapshot
    BEFORE INSERT OR UPDATE ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_price_snapshot();

-- Atomic booking hold RPC
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

-- Goat listing quota enforcement
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
-- 6. GOAT LISTING & MODERATION RULES
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            NEW.is_approved_by_admin := TRUE;
        ELSE
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();

-- =============================================================================
-- 7. REVIEW AUTHENTICITY & RATING AGGREGATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_review_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_booking RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF auth.uid() IS NOT NULL AND NOT public.is_super_admin() THEN
            NEW.customer_id := auth.uid();
        END IF;

        IF NEW.booking_id IS NULL THEN
            RAISE EXCEPTION 'A valid booking ID is required to submit a verified review.';
        END IF;

        SELECT * INTO v_booking
        FROM public.bookings
        WHERE id = NEW.booking_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Booking with ID % does not exist.', NEW.booking_id;
        END IF;

        IF v_booking.customer_id != NEW.customer_id AND NOT public.is_super_admin() THEN
            RAISE EXCEPTION 'Unauthorized: Customers can only review their own purchases.';
        END IF;

        IF v_booking.status NOT IN ('COMPLETED', 'CONFIRMED') THEN
            RAISE EXCEPTION 'Reviews are only permitted for confirmed or completed bookings (current: %).', v_booking.status;
        END IF;

        NEW.goat_id := v_booking.goat_id;
        NEW.farm_id := v_booking.farm_id;
        NEW.is_verified_purchase := TRUE;
        NEW.is_approved := TRUE;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NOT public.is_super_admin() THEN
            IF NEW.booking_id != OLD.booking_id OR NEW.goat_id != OLD.goat_id OR
               NEW.farm_id != OLD.farm_id OR NEW.customer_id != OLD.customer_id THEN
                RAISE EXCEPTION 'Relationship fields on a review are immutable.';
            END IF;
            NEW.is_approved := OLD.is_approved;
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_review_authenticity ON public.reviews;
CREATE TRIGGER tr_enforce_review_authenticity
    BEFORE INSERT OR UPDATE ON public.reviews
    FOR EACH ROW EXECUTE FUNCTION public.enforce_review_authenticity();

CREATE OR REPLACE FUNCTION public.recalculate_ratings_on_review()
RETURNS TRIGGER AS $$
DECLARE
    v_goat_id UUID;
    v_farm_id UUID;
    v_avg_rating NUMERIC(3, 2);
    v_count INT;
    v_farm_avg NUMERIC(3, 2);
    v_farm_count INT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_goat_id := OLD.goat_id;
        v_farm_id := OLD.farm_id;
    ELSE
        v_goat_id := NEW.goat_id;
        v_farm_id := NEW.farm_id;
    END IF;

    -- 1. Compute Goat Stats
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_avg_rating, v_count
    FROM public.reviews
    WHERE goat_id = v_goat_id AND is_approved = TRUE;

    UPDATE public.goats
    SET rating = v_avg_rating,
        review_count = v_count,
        updated_at = NOW()
    WHERE id = v_goat_id;

    -- 2. Compute Farm Stats
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_farm_avg, v_farm_count
    FROM public.reviews
    WHERE farm_id = v_farm_id AND is_approved = TRUE;

    UPDATE public.farms
    SET rating = v_farm_avg,
        review_count = v_farm_count,
        updated_at = NOW()
    WHERE id = v_farm_id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_recalculate_ratings ON public.reviews;
CREATE TRIGGER tr_recalculate_ratings
    AFTER INSERT OR UPDATE OF rating, is_approved OR DELETE ON public.reviews
    FOR EACH ROW EXECUTE FUNCTION public.recalculate_ratings_on_review();

-- =============================================================================
-- 8. REPORT AUTHENTICITY
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_report_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := public.is_super_admin();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF auth.uid() IS NOT NULL AND NOT v_is_super_admin THEN
            NEW.reporter_id := auth.uid();
        END IF;
        NEW.status := 'PENDING';
        NEW.resolved_by := NULL;
        NEW.resolution_notes := NULL;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NOT v_is_super_admin THEN
            RAISE EXCEPTION 'Unauthorized: Only Super Admin can update or moderate safety reports.';
        END IF;

        IF NEW.status IN ('RESOLVED', 'DISMISSED') AND OLD.status NOT IN ('RESOLVED', 'DISMISSED') THEN
            NEW.resolved_by := auth.uid();
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_report_authenticity ON public.reports;
CREATE TRIGGER tr_enforce_report_authenticity
    BEFORE INSERT OR UPDATE ON public.reports
    FOR EACH ROW EXECUTE FUNCTION public.enforce_report_authenticity();

-- =============================================================================
-- 9. NOTIFICATION & AUDIT TRIGGERS (MODERATION & BOOKINGS)
-- =============================================================================

CREATE OR REPLACE FUNCTION public.notify_and_audit_farm_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_super_admin_id UUID;
BEGIN
    SELECT id INTO v_super_admin_id FROM public.profiles WHERE role = 'SUPER_ADMIN' LIMIT 1;

    IF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'APPROVED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Approved! 🚜🎉',
                'Your farm "' || NEW.name || '" has been approved by Super Admin. You can now list goats on Ammal Farm Marketplace!',
                'FARM',
                NEW.id::text,
                'farm_approved_' || NEW.id
            );
        ELSIF NEW.status = 'REJECTED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Application Update ⚠️',
                'Your farm application for "' || NEW.name || '" was reviewed and rejected. Contact support for details.',
                'FARM',
                NEW.id::text,
                'farm_rejected_' || NEW.id
            );
        ELSIF NEW.status = 'SUSPENDED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Account Suspended 🛑',
                'Your farm "' || NEW.name || '" has been suspended. Listings are hidden from public view.',
                'FARM',
                NEW.id::text,
                'farm_suspended_' || NEW.id
            );
        END IF;

        -- Record Audit Log
        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (
            COALESCE(auth.uid(), v_super_admin_id),
            'FARM_STATUS_CHANGE',
            'FARM',
            NEW.id,
            OLD.status::text,
            NEW.status::text,
            'Farm status transitioned to ' || NEW.status::text
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_farm_moderation_audit ON public.farms;
CREATE TRIGGER tr_farm_moderation_audit
    AFTER UPDATE OF status ON public.farms
    FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_farm_moderation();

CREATE OR REPLACE FUNCTION public.notify_and_audit_goat_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_farm_owner_id UUID;
    v_super_admin_id UUID;
BEGIN
    SELECT owner_id INTO v_farm_owner_id FROM public.farms WHERE id = NEW.farm_id;
    SELECT id INTO v_super_admin_id FROM public.profiles WHERE role = 'SUPER_ADMIN' LIMIT 1;

    IF TG_OP = 'UPDATE' AND OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
        IF NEW.is_approved_by_admin = TRUE THEN
            IF v_farm_owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm_owner_id,
                    'Listing Approved! 🐐✨',
                    'Goat "' || NEW.name || '" (Tag #' || NEW.tag_number || ') is approved and live on Ammal Farm marketplace.',
                    'GOAT',
                    NEW.id::text,
                    'goat_approved_' || NEW.id
                );
            END IF;
        ELSE
            IF v_farm_owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm_owner_id,
                    'Listing Unapproved ⚠️',
                    'Goat "' || NEW.name || '" listing has been unpublished by admin.',
                    'GOAT',
                    NEW.id::text,
                    'goat_unapproved_' || NEW.id
                );
            END IF;
        END IF;

        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (
            COALESCE(auth.uid(), v_super_admin_id),
            'GOAT_MODERATION',
            'GOAT',
            NEW.id,
            OLD.is_approved_by_admin::text,
            NEW.is_approved_by_admin::text,
            'Goat approval status updated'
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_goat_moderation_audit ON public.goats;
CREATE TRIGGER tr_goat_moderation_audit
    AFTER UPDATE OF is_approved_by_admin ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_goat_moderation();

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
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_notify_booking_lifecycle ON public.bookings;
CREATE TRIGGER tr_notify_booking_lifecycle
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.notify_booking_lifecycle();

-- =============================================================================
-- 19. FARM METADATA INTEGRITY ENFORCEMENT
-- =============================================================================
CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- If an authenticated non-super-admin user inserts a farm, force safe defaults:
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            NEW.is_ammal_own_farm := FALSE;
            NEW.goat_listing_limit := 10;
            NEW.status := 'PENDING'::public.farm_status;
            NEW.owner_id := v_auth_uid;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Non-Super Admins are strictly prohibited from changing listing limits, ownership, status, or Ammal Farm flag
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
                RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
            END IF;

            IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
                RAISE EXCEPTION 'Farm ownership cannot be modified.';
            END IF;

            IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
                RAISE EXCEPTION 'Ammal Farm designation cannot be modified.';
            END IF;

            IF OLD.status IS DISTINCT FROM NEW.status THEN
                RAISE EXCEPTION 'Only Super Admin can modify farm status.';
            END IF;
        END IF;

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
-- 20. PROFILE SECURITY GUARD ENFORCEMENT
-- =============================================================================
CREATE OR REPLACE FUNCTION public.enforce_profile_security_guard()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID := auth.uid();
BEGIN
    -- 1. Direct DB admin / SQL Editor / backend service role (auth.uid() is NULL):
    -- Allow direct administrative operations unconditionally
    IF v_auth_uid IS NULL THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 2. Check if current authenticated caller is Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    -- Super Admin has unrestricted profile update/insert rights
    IF v_is_super_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 3. Non-Super Admins: Enforce strict constraints
    IF TG_OP = 'INSERT' THEN
        -- Non-super admins cannot insert a profile with SUPER_ADMIN role
        IF NEW.role = 'SUPER_ADMIN' THEN
            NEW.role := 'CUSTOMER';
        END IF;
        -- Customers cannot have a farm_id
        IF NEW.role = 'CUSTOMER' THEN
            NEW.farm_id := NULL;
        END IF;
        -- Non-super admins cannot insert suspended status
        NEW.is_suspended := FALSE;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Role is IMMUTABLE for non-super admins
        NEW.role := OLD.role;

        -- Suspension status is IMMUTABLE for non-super admins
        NEW.is_suspended := OLD.is_suspended;

        -- Customers can NEVER be assigned a farm
        IF OLD.role = 'CUSTOMER' THEN
            NEW.farm_id := NULL;
        ELSIF OLD.role = 'FARM_ADMIN' THEN
            -- Farm Admins cannot change their farm_id once set
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


