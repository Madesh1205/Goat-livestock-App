-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 11N: AUTHENTICATION & ACCOUNT HARDENING
-- 1. Updates handle_new_auth_user() to eliminate hardcoded admin emails
-- 2. Strictly enforces CUSTOMER and FARM_ADMIN registration lifecycle
-- 3. Adds enforce_profile_security_guard trigger to protect role, farm_id, and is_suspended
-- 4. Guarantees public signups cannot escalate to SUPER_ADMIN
-- =============================================================================

-- 1. Update trigger function handle_new_auth_user to eliminate email-based role elevation
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
DECLARE
    v_raw_role TEXT;
    v_role public.user_role;
    v_full_name TEXT;
    v_phone TEXT;
    v_farm_name TEXT;
    v_farm_district TEXT;
    v_farm_description TEXT;
    v_farm_id UUID;
    v_existing_profile_role public.user_role;
BEGIN
    -- Extract full name and phone safely from metadata or direct fields
    v_full_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''), 'Ammal User');
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Check if profile already exists to preserve existing role
    SELECT role INTO v_existing_profile_role FROM public.profiles WHERE id = NEW.id;

    IF v_existing_profile_role IS NOT NULL THEN
        -- Preserve existing role on profile upsert
        v_role := v_existing_profile_role;
    ELSE
        -- Strict Role Assignment for new users:
        -- Only FARM_ADMIN is accepted if explicit; otherwise strictly CUSTOMER.
        -- SUPER_ADMIN is NEVER granted through public registration or client metadata!
        v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
        IF v_raw_role = 'FARM_ADMIN' THEN
            v_role := 'FARM_ADMIN'::public.user_role;
        ELSE
            v_role := 'CUSTOMER'::public.user_role;
        END IF;
    END IF;

    -- Insert or Update profile for auth.users(id)
    INSERT INTO public.profiles (
        id,
        full_name,
        email,
        phone,
        role,
        is_suspended,
        created_at,
        updated_at
    )
    VALUES (
        NEW.id,
        v_full_name,
        NEW.email,
        v_phone,
        v_role,
        FALSE,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = COALESCE(EXCLUDED.email, public.profiles.email),
        phone = COALESCE(EXCLUDED.phone, public.profiles.phone),
        updated_at = NOW();

    -- If new FARM_ADMIN applicant, provision partner farm atomically in PENDING status
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
            ) VALUES (
                v_farm_id,
                v_farm_name,
                NEW.id,
                v_farm_description,
                v_farm_district,
                'Tamil Nadu',
                v_farm_district || ', Tamil Nadu',
                COALESCE(v_phone, ''),
                NEW.email,
                'PENDING'::public.farm_status, -- Must remain PENDING until Super Admin verifies
                FALSE,                         -- Never Ammal own farm
                10,                            -- Standard partner listing limit
                5.0,
                0,
                NOW(),
                NOW()
            );
        END IF;

        -- Link the created farm back to the profile
        UPDATE public.profiles
        SET farm_id = v_farm_id
        WHERE id = NEW.id AND (farm_id IS NULL OR farm_id = v_farm_id);
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger on auth.users
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();

-- 2. Trigger function to enforce profile security guard on direct inserts/updates
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

    -- Check if current authenticated caller is Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    -- Super Admin has unrestricted profile update/insert rights
    IF v_is_super_admin IS TRUE THEN
        RETURN NEW;
    END IF;

    -- Non-Super Admins: Enforce strict constraints
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Bind enforce_profile_security_guard to public.profiles
DROP TRIGGER IF EXISTS tr_enforce_profile_security_guard ON public.profiles;
CREATE TRIGGER tr_enforce_profile_security_guard
    BEFORE INSERT OR UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.enforce_profile_security_guard();
