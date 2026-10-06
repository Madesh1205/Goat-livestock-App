-- =============================================================================
-- AMMAL FARM PLATFORM - FIX GOAT LISTING FEE TRIGGER RECORD "OLD" FIELD ERROR
-- Fixes enforce_goat_listing_fee_rule(): Removes all references to obsolete
-- OLD.listing_fee_paid / NEW.listing_fee_paid on public.goats.
-- =============================================================================

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

    -- Determine if the target farm is Ammal Farm
    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            -- Ammal Farm: Listings are auto-approved by administrator
            NEW.is_approved_by_admin := TRUE;
        ELSE
            -- Partner Farm: Pending administrator approval
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Prevent untrusted non-admin users from self-approving their listings or changing farm_id
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.farm_id IS DISTINCT FROM NEW.farm_id THEN
                RAISE EXCEPTION 'Goat cannot be transferred to a different farm.';
            END IF;

            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Recreate trigger on goats
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();
