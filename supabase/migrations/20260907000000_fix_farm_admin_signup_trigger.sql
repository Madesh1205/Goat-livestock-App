-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 11A / FIX FARM ADMIN SIGNUP TRIGGER & SCHEMA
-- 1. Adds goat_listing_limit column to public.farms (default 10 for partner farms)
-- 2. Updates handle_new_auth_user() to safely insert PENDING partner farms
-- 3. Atomic profile & farm provisioning:
--    auth.users -> profiles -> role = FARM_ADMIN -> partner farm ->
--    farms.owner_id = auth.uid() -> status = PENDING ->
--    is_ammal_own_farm = false -> profile.farm_id = farm.id
-- 4. Preserves Super Admin security: public signup NEVER creates SUPER_ADMIN
-- =============================================================================

-- 1. Ensure goat_listing_limit column exists on public.farms
ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS goat_listing_limit INT NOT NULL DEFAULT 10;

-- 2. Update trigger function handle_new_auth_user
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
BEGIN
    -- Extract full name and phone safely from metadata or direct fields
    v_full_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''), 'Ammal User');
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Strict Role Assignment: NEVER allow SUPER_ADMIN privilege escalation from client metadata!
    v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
    IF v_raw_role = 'FARM_ADMIN' THEN
        v_role := 'FARM_ADMIN'::public.user_role;
    ELSE
        -- Any unknown role, or 'SUPER_ADMIN' request from client, defaults strictly to 'CUSTOMER'
        v_role := 'CUSTOMER'::public.user_role;
    END IF;

    -- Bootstrap super admin safeguard (by email only)
    IF LOWER(COALESCE(NEW.email, '')) = 'madesh1205@gmail.com' THEN
        v_role := 'SUPER_ADMIN'::public.user_role;
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
                10,                            -- Partner listing limit
                5.0,
                0,
                NOW(),
                NOW()
            );
        END IF;

        -- Link the created farm back to the profile
        UPDATE public.profiles
        SET farm_id = v_farm_id
        WHERE id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger on auth.users
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();
