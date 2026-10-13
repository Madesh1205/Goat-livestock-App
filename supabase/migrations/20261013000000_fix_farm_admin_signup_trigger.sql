CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
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
    v_default_slots INT := 2;
BEGIN
    v_full_name := COALESCE(
        NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''),
        NULLIF(TRIM(NEW.raw_user_meta_data->>'name'), ''),
        'Ammal User'
    );
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Never allow client metadata to grant SUPER_ADMIN.
    v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
    IF v_raw_role IN ('FARM_ADMIN', 'GOAT_BREEDER', 'BREEDER') THEN
        v_role := 'FARM_ADMIN'::public.user_role;
    ELSE
        v_role := 'CUSTOMER'::public.user_role;
    END IF;

    BEGIN
        SELECT COALESCE(value::INT, 2) INTO v_default_slots
        FROM public.platform_settings
        WHERE key = 'default_initial_slots';
        IF v_default_slots IS NULL OR v_default_slots <= 0 THEN
            v_default_slots := 2;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_default_slots := 2;
    END;

    SELECT role INTO v_existing_role FROM public.profiles WHERE id = NEW.id;
    IF v_existing_role IS NOT NULL THEN
        v_role := v_existing_role;
    END IF;

    INSERT INTO public.profiles (
        id, full_name, email, phone, role, created_at, updated_at
    )
    VALUES (
        NEW.id, v_full_name, NEW.email, v_phone, v_role, NOW(), NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = EXCLUDED.email,
        phone = COALESCE(EXCLUDED.phone, public.profiles.phone),
        updated_at = NOW();

    -- Create a pending farm application atomically for farm-admin signups.
    IF v_role = 'FARM_ADMIN'::public.user_role THEN
        v_farm_name := COALESCE(
            NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_name'), ''),
            v_full_name || ' Farm'
        );
        v_farm_district := COALESCE(
            NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_district'), ''),
            NULLIF(TRIM(NEW.raw_user_meta_data->>'location_district'), ''),
            'Madurai'
        );
        v_farm_description := NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_description'), '');

        SELECT id INTO v_farm_id
        FROM public.farms
        WHERE owner_id = NEW.id
        LIMIT 1;

        IF v_farm_id IS NULL THEN
            INSERT INTO public.farms (
                name, owner_id, description, location_district, location_state,
                address, contact_phone, contact_email, status, is_ammal_own_farm,
                goat_listing_limit, created_at, updated_at
            )
            VALUES (
                v_farm_name, NEW.id, v_farm_description, v_farm_district, 'Tamil Nadu',
                v_farm_district || ', Tamil Nadu', COALESCE(v_phone, 'Not provided'),
                NEW.email, 'PENDING'::public.farm_status, FALSE,
                v_default_slots, NOW(), NOW()
            )
            RETURNING id INTO v_farm_id;
        END IF;

        UPDATE public.profiles
        SET farm_id = v_farm_id, updated_at = NOW()
        WHERE id = NEW.id;
    END IF;

    RETURN NEW;
END;
$function$;