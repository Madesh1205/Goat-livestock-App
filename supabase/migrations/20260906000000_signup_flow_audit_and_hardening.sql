-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 11 / SIGN-UP FLOW AUDIT & HARDENING
-- Comprehensive Fixes for Customer & Farm Admin Registration
-- 1. Database trigger handle_new_auth_user() handles CUSTOMER & FARM_ADMIN metadata
-- 2. Prevents SUPER_ADMIN privilege escalation from client metadata
-- 3. Atomic profile and pending farm creation
-- 4. Foreign key integrity referencing auth.users(id)
-- 5. RLS policies for profiles and farms registration
-- =============================================================================

-- 1. HARDEN TRIGGER ON auth.users
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
        -- Any unknown role, or 'SUPER_ADMIN' request, defaults strictly to 'CUSTOMER'
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
                'PENDING', -- Must remain PENDING until Super Admin verifies
                FALSE,     -- Never Ammal own farm
                10,        -- Partner listing limit
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

-- 2. HARDEN ROW LEVEL SECURITY POLICIES FOR PROFILES
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "profiles_select_all" ON public.profiles;
CREATE POLICY "profiles_select_all" ON public.profiles
    FOR SELECT TO public
    USING (true);

DROP POLICY IF EXISTS "profiles_insert_own" ON public.profiles;
CREATE POLICY "profiles_insert_own" ON public.profiles
    FOR INSERT TO authenticated
    WITH CHECK (
        auth.uid() = id
        AND (
            -- Normal authenticated users can only insert CUSTOMER or FARM_ADMIN, NEVER SUPER_ADMIN
            role IN ('CUSTOMER', 'FARM_ADMIN')
            OR public.is_super_admin()
        )
    );

DROP POLICY IF EXISTS "profiles_update_own" ON public.profiles;
CREATE POLICY "profiles_update_own" ON public.profiles
    FOR UPDATE TO authenticated
    USING (auth.uid() = id OR public.is_super_admin())
    WITH CHECK (
        public.is_super_admin()
        OR (
            auth.uid() = id
            -- Cannot elevate self to SUPER_ADMIN
            AND role = (SELECT p.role FROM public.profiles p WHERE p.id = auth.uid())
            -- Cannot arbitrarily reassign farm_id unless it was null and farm belongs to user
            AND (
                farm_id IS NOT DISTINCT FROM (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid())
                OR (
                    (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid()) IS NULL
                    AND EXISTS (SELECT 1 FROM public.farms f WHERE f.id = profiles.farm_id AND f.owner_id = auth.uid())
                )
            )
        )
    );

-- 3. HARDEN ROW LEVEL SECURITY POLICIES FOR FARMS
ALTER TABLE public.farms ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "farms_insert_authenticated" ON public.farms;
DROP POLICY IF EXISTS "farms_insert_policy" ON public.farms;
CREATE POLICY "farms_insert_policy" ON public.farms
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR (
            owner_id = auth.uid()
            AND status = 'PENDING'
            AND is_ammal_own_farm = FALSE
        )
    );
