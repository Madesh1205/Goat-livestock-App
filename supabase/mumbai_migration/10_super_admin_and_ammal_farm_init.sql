-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 5 MUMBAI INITIALIZATION
-- File: /supabase/mumbai_migration/10_super_admin_and_ammal_farm_init.sql
-- Description: Initializes the initial SUPER_ADMIN profile and canonical Ammal Farm
--              record in the Mumbai Supabase database.
-- =============================================================================

-- 0. PREREQUISITE TRIGGER FIXES:
--    A. Ensure enforce_profile_security_guard allows direct DB admin / SQL Editor execution
--       (where auth.uid() is NULL), preventing silent reset to 'CUSTOMER'.
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

--    B. Ensure enforce_farm_metadata_integrity references 'status' (not 'verification_status')
--       and allows direct DB admin / SQL Editor execution.
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

-- 1. MAIN INITIALIZATION BLOCK
DO $$
DECLARE
    v_admin_uuid UUID;
    v_ammal_farm_id UUID := '00000000-0000-0000-0000-000000000001'::UUID;
    v_ammal_phone TEXT := '+91 63808 98358';
    v_farm_exists BOOLEAN;
    v_profile_exists BOOLEAN;
    v_admin_name TEXT;
    v_admin_phone TEXT;
    v_admin_email TEXT;
BEGIN
    -- 1. DYNAMICALLY RESOLVE SUPER ADMIN AUTHENTICATED UUID
    IF auth.uid() IS NOT NULL THEN
        v_admin_uuid := auth.uid();
    ELSE
        -- Select existing Super Admin profile if previously set
        SELECT id INTO v_admin_uuid FROM public.profiles WHERE role = 'SUPER_ADMIN' LIMIT 1;
        -- If none, select the project's authenticated user from auth.users
        IF v_admin_uuid IS NULL THEN
            SELECT id INTO v_admin_uuid FROM auth.users ORDER BY created_at ASC LIMIT 1;
        END IF;
    END IF;

    IF v_admin_uuid IS NULL THEN
        RAISE EXCEPTION 'Initialization halted: No authenticated user record found in auth.users or profiles.';
    END IF;

    -- 2. FETCH AUTH USER ATTRIBUTES
    SELECT 
        COALESCE(NULLIF(TRIM(raw_user_meta_data->>'full_name'), ''), 'Ammal Super Admin'),
        phone,
        email
    INTO v_admin_name, v_admin_phone, v_admin_email
    FROM auth.users
    WHERE id = v_admin_uuid;

    v_admin_phone := COALESCE(NULLIF(TRIM(v_admin_phone), ''), v_ammal_phone);

    -- Also align auth.users user metadata
    UPDATE auth.users
    SET raw_user_meta_data = jsonb_set(
        jsonb_set(COALESCE(raw_user_meta_data, '{}'::jsonb), '{role}', '"SUPER_ADMIN"'),
        '{phone}',
        to_jsonb(v_ammal_phone)
    )
    WHERE id = v_admin_uuid;

    -- 3. EXISTING DATA SAFETY: SET PROFILE AS SUPER_ADMIN
    SELECT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_admin_uuid) INTO v_profile_exists;

    IF v_profile_exists THEN
        -- Update existing profile: role = SUPER_ADMIN, farm_id = NULL
        UPDATE public.profiles
        SET role = 'SUPER_ADMIN'::public.user_role,
            phone = COALESCE(phone, v_ammal_phone),
            farm_id = NULL,
            updated_at = NOW()
        WHERE id = v_admin_uuid;
    ELSE
        -- Insert new profile
        INSERT INTO public.profiles (
            id,
            full_name,
            email,
            phone,
            role,
            farm_id,
            is_suspended,
            created_at,
            updated_at
        ) VALUES (
            v_admin_uuid,
            COALESCE(v_admin_name, 'Ammal Super Admin'),
            v_admin_email,
            v_ammal_phone,
            'SUPER_ADMIN'::public.user_role,
            NULL,
            FALSE,
            NOW(),
            NOW()
        );
    END IF;

    -- 4. EXISTING DATA SAFETY: CHECK AMMAL FARM RECORD
    SELECT EXISTS (SELECT 1 FROM public.farms WHERE id = v_ammal_farm_id) INTO v_farm_exists;

    IF v_farm_exists THEN
        -- Update existing canonical record to ensure real UUID owner and approved status
        UPDATE public.farms
        SET owner_id = v_admin_uuid,
            is_ammal_own_farm = TRUE,
            status = 'APPROVED'::public.farm_status,
            goat_listing_limit = 10000,
            contact_phone = v_ammal_phone,
            contact_email = COALESCE(v_admin_email, contact_email),
            updated_at = NOW()
        WHERE id = v_ammal_farm_id;
    ELSE
        -- Insert official canonical Ammal Farm record
        INSERT INTO public.farms (
            id,
            name,
            owner_id,
            tagline,
            description,
            location_district,
            location_state,
            address,
            latitude,
            longitude,
            contact_phone,
            contact_email,
            status,
            is_ammal_own_farm,
            goat_listing_limit,
            verified_at,
            rating,
            review_count,
            logo_url,
            banner_url,
            created_at,
            updated_at
        ) VALUES (
            v_ammal_farm_id,
            'Ammal Farm Central Hub',
            v_admin_uuid,
            'Premier Stud & Breeding Farm',
            'Main stud farm and breeding excellence center in Vellore, Tamil Nadu.',
            'Vellore',
            'Tamil Nadu',
            'Vellore, Tamil Nadu',
            12.9165,
            79.1325,
            v_ammal_phone,
            v_admin_email,
            'APPROVED'::public.farm_status,
            TRUE,
            10000, -- Unrestricted capacity for Ammal Farm
            NOW(),
            5.00,
            0,
            NULL, -- no partner-farm default logo, remains NULL
            NULL, -- no preset banner, remains NULL
            NOW(),
            NOW()
        );
    END IF;

    -- 5. VERIFICATION LOG
    RAISE NOTICE 'SUCCESS: Initialized Super Admin (UUID: %) and Ammal Farm (UUID: %, Owner: %, Phone: %)',
        v_admin_uuid, v_ammal_farm_id, v_admin_uuid, v_ammal_phone;
END;
$$ LANGUAGE plpgsql;

-- Verification Query to execute immediately after script:
SELECT 
    p.id AS super_admin_profile_uuid,
    p.role AS super_admin_role,
    p.phone AS super_admin_phone,
    p.farm_id AS super_admin_farm_id,
    f.id AS ammal_farm_uuid,
    f.owner_id AS ammal_farm_owner_uuid,
    f.contact_phone AS ammal_farm_phone,
    f.is_ammal_own_farm,
    f.status AS farm_status,
    f.goat_listing_limit
FROM public.profiles p
JOIN public.farms f ON f.owner_id = p.id
WHERE p.role = 'SUPER_ADMIN' AND f.id = '00000000-0000-0000-0000-000000000001'::UUID;
