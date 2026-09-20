-- ============================================================================
-- MIGRATION: 20260919040000_stage9b_partner_farm_initial_goat_limit.sql
-- Description: Partner Farm Manual Approval & Initial Listing Limit Enforcement (Limit = 2)
-- ============================================================================

-- 1. Update default column value on public.farms to 2
ALTER TABLE public.farms ALTER COLUMN goat_listing_limit SET DEFAULT 2;

-- 2. Update farm metadata integrity trigger to set initial limit to 2 for non-super-admins
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
            NEW.goat_listing_limit := 2;
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


-- 3. Update goat listing quota enforcement trigger (partner limit = 2, Ammal Farm exempt)
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 2;
    v_current_count INT := 0;
BEGIN
    -- Genuine Ammal Farm check: designated canonical UUID or is_ammal_own_farm flag
    SELECT (f.id = '00000000-0000-0000-0000-000000000001'::uuid OR f.is_ammal_own_farm = TRUE),
           COALESCE(f.goat_listing_limit, 2)
    INTO v_is_ammal, v_limit
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    -- Ammal Farm is exempt from partner listing limit restrictions
    IF v_is_ammal = TRUE THEN
        RETURN NEW;
    END IF;

    -- On UPDATE:
    IF TG_OP = 'UPDATE' THEN
        -- If farm_id didn't change and goat was already active, quota consumption is unchanged
        IF OLD.farm_id = NEW.farm_id AND OLD.status != 'INACTIVE' AND NEW.status != 'INACTIVE' THEN
            RETURN NEW;
        END IF;
    END IF;

    -- If goat is/will be INACTIVE, it does not consume an active listing slot
    IF NEW.status = 'INACTIVE' THEN
        RETURN NEW;
    END IF;

    -- Count active/listed goats for this partner farm
    SELECT COUNT(*) INTO v_current_count
    FROM public.goats
    WHERE farm_id = NEW.farm_id
      AND status != 'INACTIVE'
      AND (TG_OP = 'INSERT' OR id != NEW.id);

    IF v_current_count >= v_limit THEN
        RAISE EXCEPTION 'Your goat listing limit has been reached. Contact +91 63808 98358 for approval to add more goats.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();
