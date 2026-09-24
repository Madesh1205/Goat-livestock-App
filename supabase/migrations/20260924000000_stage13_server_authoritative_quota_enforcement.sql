-- ============================================================================
-- MIGRATION: 20260924000000_stage13_server_authoritative_quota_enforcement.sql
-- Description: Server-Authoritative Atomic Partner Farm Quota Enforcement
-- ============================================================================

-- 1. Ensure consumed_listing_slots exists on public.farms and default limit is 0
ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS consumed_listing_slots INT NOT NULL DEFAULT 0;
ALTER TABLE public.farms ALTER COLUMN goat_listing_limit SET DEFAULT 0;

-- 2. Farm metadata integrity trigger: ensures new partner farms start with PENDING & limit 0
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
        -- If an authenticated non-super-admin user registers a farm, force safe defaults:
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            NEW.is_ammal_own_farm := FALSE;
            NEW.goat_listing_limit := 0;
            NEW.consumed_listing_slots := 0;
            NEW.status := 'PENDING'::public.farm_status;
            NEW.owner_id := v_auth_uid;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Non-Super Admins are strictly prohibited from changing listing limits, consumed slots, ownership, status, or Ammal Farm flag
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
                RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
            END IF;

            IF OLD.consumed_listing_slots IS DISTINCT FROM NEW.consumed_listing_slots THEN
                RAISE EXCEPTION 'Consumed listing slots cannot be manually modified.';
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


-- 3. Database-Authoritative Quota Enforcement Trigger (with Row-Level Exclusive Lock to eliminate race conditions)
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 0;
    v_consumed INT := 0;
    v_farm_status public.farm_status;
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    -- Row-level exclusive lock on the target farm row (FOR UPDATE)
    SELECT (f.id = '00000000-0000-0000-0000-000000000001'::uuid OR f.is_ammal_own_farm = TRUE),
           COALESCE(f.goat_listing_limit, 0),
           COALESCE(f.consumed_listing_slots, 0),
           f.status
    INTO v_is_ammal, v_limit, v_consumed, v_farm_status
    FROM public.farms f
    WHERE f.id = NEW.farm_id
    FOR UPDATE;

    -- Ammal Farm is exempt from partner quota and approval rules
    IF v_is_ammal = TRUE THEN
        NEW.is_approved_by_admin := TRUE;
        NEW.listing_fee_paid := TRUE;
        NEW.listing_fee_amount := 0.0;
        RETURN NEW;
    END IF;

    -- Partner farm must be APPROVED before adding goats
    IF v_farm_status != 'APPROVED' AND NOT v_is_super_admin THEN
        RAISE EXCEPTION 'Your partner farm must be approved by Super Admin before you can add goats. Please complete the initial approval fee payment.';
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Quota check (Super Admin can bypass limit)
        IF NOT v_is_super_admin AND v_consumed >= v_limit THEN
            RAISE EXCEPTION 'Your goat listing quota has been fully consumed (% / % slots). Contact Super Admin to purchase additional listing slots.', v_consumed, v_limit;
        END IF;

        -- Auto-approve listing since farm is approved and quota slot is consumed (Rule 13)
        NEW.is_approved_by_admin := TRUE;
        NEW.status := 'AVAILABLE';
        NEW.listing_fee_paid := TRUE;
        NEW.listing_fee_amount := 0.0;

        -- Atomically increment consumed slots on the farm
        IF NOT v_is_super_admin THEN
            UPDATE public.farms
            SET consumed_listing_slots = v_consumed + 1,
                updated_at = NOW()
            WHERE id = NEW.farm_id;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
    BEFORE INSERT ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();


-- 4. Server-Authoritative Atomic RPC: create_goat_listing_secure
CREATE OR REPLACE FUNCTION public.create_goat_listing_secure(
    p_goat JSONB
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller_id UUID;
    v_caller_role TEXT;
    v_farm_id UUID;
    v_farm RECORD;
    v_is_super_admin BOOLEAN := FALSE;
    v_is_ammal BOOLEAN := FALSE;
    v_goat_id UUID;
    v_goat_code TEXT;
    v_name TEXT;
    v_breed_name TEXT;
    v_gender TEXT;
    v_age_months INT;
    v_weight_kg NUMERIC(6, 2);
    v_purpose TEXT;
    v_price NUMERIC(12, 2);
    v_discount_percentage NUMERIC(5, 2);
    v_description TEXT;
    v_new_goat RECORD;
BEGIN
    -- 1. Validate caller authentication
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to create goat listing.';
    END IF;

    -- 2. Fetch caller role
    SELECT role INTO v_caller_role 
    FROM public.profiles 
    WHERE id = v_caller_id;

    IF v_caller_role IS NULL THEN
        RAISE EXCEPTION 'Caller profile not found.';
    END IF;

    v_is_super_admin := (v_caller_role = 'SUPER_ADMIN');

    -- 3. Extract farm_id
    v_farm_id := (p_goat->>'farm_id')::UUID;
    IF v_farm_id IS NULL THEN
        RAISE EXCEPTION 'Invalid farm_id provided.';
    END IF;

    -- 4. Lock farm row FOR UPDATE to guarantee atomic slot check and consumption
    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = v_farm_id
    FOR UPDATE;

    IF v_farm.id IS NULL THEN
        RAISE EXCEPTION 'Target farm not found.';
    END IF;

    -- 5. Authorization check
    IF NOT v_is_super_admin THEN
        IF v_caller_role != 'FARM_ADMIN' OR v_farm.owner_id != v_caller_id THEN
            RAISE EXCEPTION 'Unauthorized: You can only list goats for your own farm.';
        END IF;
    END IF;

    v_is_ammal := (v_farm.id = '00000000-0000-0000-0000-000000000001'::UUID OR v_farm.is_ammal_own_farm = TRUE);

    -- 6. Quota and status validation
    IF NOT v_is_ammal AND NOT v_is_super_admin THEN
        IF v_farm.status != 'APPROVED' THEN
            RAISE EXCEPTION 'Your partner farm must be approved by Super Admin before you can add goats. Please complete the initial approval fee payment.';
        END IF;

        IF COALESCE(v_farm.consumed_listing_slots, 0) >= COALESCE(v_farm.goat_listing_limit, 0) THEN
            RAISE EXCEPTION 'Your goat listing quota has been fully consumed (% / % slots). Contact Super Admin to purchase additional listing slots.',
                COALESCE(v_farm.consumed_listing_slots, 0), COALESCE(v_farm.goat_listing_limit, 0);
        END IF;
    END IF;

    -- 7. Extract goat attributes
    v_goat_id := COALESCE(NULLIF(p_goat->>'id', '')::UUID, gen_random_uuid());
    v_name := COALESCE(NULLIF(p_goat->>'name', ''), 'Goat');
    v_breed_name := COALESCE(NULLIF(p_goat->>'breed_name', ''), 'Boer');
    v_gender := COALESCE(NULLIF(p_goat->>'gender', ''), 'MALE');
    v_age_months := COALESCE((p_goat->>'age_months')::INT, 12);
    v_weight_kg := COALESCE((p_goat->>'weight_kg')::NUMERIC, 35.0);
    v_purpose := COALESCE(NULLIF(p_goat->>'purpose', ''), 'BREEDING');
    v_price := COALESCE((p_goat->>'price')::NUMERIC, 0.0);
    v_discount_percentage := COALESCE((p_goat->>'discount_percentage')::NUMERIC, 0.0);
    v_description := COALESCE(p_goat->>'description', 'High quality breed livestock.');
    v_goat_code := NULLIF(p_goat->>'goat_code', '');

    -- 8. Insert Goat record (Auto-approved as per Rule 13; triggers tr_enforce_goat_listing_limit to atomically consume 1 slot)
    INSERT INTO public.goats (
        id,
        farm_id,
        name,
        breed_name,
        gender,
        age_months,
        weight_kg,
        purpose,
        price,
        discount_percentage,
        description,
        status,
        is_approved_by_admin,
        listing_fee_paid,
        listing_fee_amount,
        goat_code,
        created_at,
        updated_at
    ) VALUES (
        v_goat_id,
        v_farm_id,
        v_name,
        v_breed_name,
        v_gender,
        v_age_months,
        v_weight_kg,
        v_purpose,
        v_price,
        v_discount_percentage,
        v_description,
        'AVAILABLE',
        TRUE,
        TRUE,
        0.0,
        v_goat_code,
        NOW(),
        NOW()
    )
    RETURNING * INTO v_new_goat;

    -- 9. Return inserted goat
    RETURN to_jsonb(v_new_goat);
END;
$$;

REVOKE ALL ON FUNCTION public.create_goat_listing_secure(JSONB) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_goat_listing_secure(JSONB) TO authenticated;

-- 5. Harden Goat Update Trigger: prevents farm_id tampering, approval tampering, or fee status modification
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            NEW.is_approved_by_admin := TRUE;
            NEW.listing_fee_paid := TRUE;
            NEW.listing_fee_amount := 0.0;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Only Super Admin can modify farm_id, approval status, or listing fee paid flags
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.farm_id IS DISTINCT FROM NEW.farm_id THEN
                RAISE EXCEPTION 'Goat cannot be transferred to a different farm.';
            END IF;

            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;

            IF OLD.listing_fee_paid IS DISTINCT FROM NEW.listing_fee_paid THEN
                RAISE EXCEPTION 'Listing fee status cannot be manually modified.';
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

