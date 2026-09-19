-- =============================================================================
-- AMMAL FARM APP - STAGE 11K
-- Server-Side Listing Limit Enforcement & Reversion of Razorpay Payments
--
-- 1. Partner Farm Admin gets a default listing limit of 10 goats.
-- 2. When limit is reached, database triggers enforce:
--    'Listing limit reached. Contact Super Admin to increase your listing limit.'
-- 3. Farm Admin cannot directly modify listing_limit or owner_id.
-- 4. Super Admin manually updates partner farm listing_limit after offline arrangement.
-- 5. Changing the limit does not modify farm ownership, verification status, goats, bookings, or reviews.
-- 6. Super Admin can approve/reject goats without online payment gates.
-- 7. Ammal Farm remains owned by Super Admin, listing fee ₹0, exempt from partner limits.
-- =============================================================================

-- 1. Ensure goat_listing_limit exists on public.farms with default 10
ALTER TABLE public.farms 
ADD COLUMN IF NOT EXISTS goat_listing_limit INT NOT NULL DEFAULT 10;

-- Ensure Ammal Farm has unlimited / high limit
UPDATE public.farms
SET goat_listing_limit = 9999
WHERE id = '00000000-0000-0000-0000-000000000001'::uuid OR is_ammal_own_farm = TRUE;

-- 2. Trigger function to enforce farm listing limits on goat insertion
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 10;
    v_current_count INT := 0;
BEGIN
    -- Determine if the farm is Ammal Farm
    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid),
           COALESCE(f.goat_listing_limit, 10)
    INTO v_is_ammal, v_limit
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    -- Ammal Farm is exempt from partner listing limit checks
    IF v_is_ammal = TRUE THEN
        RETURN NEW;
    END IF;

    -- Count active/listed goats for this partner farm
    SELECT COUNT(*) INTO v_current_count
    FROM public.goats
    WHERE farm_id = NEW.farm_id
      AND status != 'INACTIVE';

    IF v_current_count >= v_limit THEN
        RAISE EXCEPTION 'Listing limit reached. Contact Super Admin to increase your listing limit.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Attach trigger to public.goats
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
BEFORE INSERT ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();

-- 3. Trigger function to protect farm listing limit & critical metadata modifications
CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    -- Non-Super Admins are strictly prohibited from changing listing limits, ownership, or verification status
    IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
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
            RAISE EXCEPTION 'Only Super Admin can modify farm verification status.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Attach trigger to public.farms
DROP TRIGGER IF EXISTS tr_enforce_farm_metadata_integrity ON public.farms;
CREATE TRIGGER tr_enforce_farm_metadata_integrity
BEFORE UPDATE ON public.farms
FOR EACH ROW EXECUTE FUNCTION public.enforce_farm_metadata_integrity();

-- 4. Clean and restore enforce_goat_listing_fee_rule without Stage 11J Razorpay blocks
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
            -- Ammal Farm listings are auto-approved with ₹0 listing fee
            NEW.is_approved_by_admin := TRUE;
        ELSE
            -- Partner Farm listings: default pending approval or auto-approved within quota
            NEW.is_approved_by_admin := TRUE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Only Super Admin or server triggers can update approval status
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Re-attach trigger on goats
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
BEFORE INSERT OR UPDATE ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();
