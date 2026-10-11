-- =============================================================================
-- AMMAL FARM / ADU SANTHAI - CORRECT GOAT LISTING SLOT ACCOUNTING
-- Migration: 20261011010000_correct_goat_listing_slot_accounting.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. AUTHORITATIVE CONSUMED SLOTS COLUMN AND HELPER FUNCTION
-- -----------------------------------------------------------------------------

ALTER TABLE public.farms 
    ADD COLUMN IF NOT EXISTS consumed_listing_slots INT NOT NULL DEFAULT 0;

-- Function to calculate consumed listing slots for a given farm
CREATE OR REPLACE FUNCTION public.get_farm_consumed_slots(p_farm_id UUID)
RETURNS INT
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_consumed INT := 0;
BEGIN
    IF p_farm_id IS NULL THEN
        RETURN 0;
    END IF;

    -- Count total goats currently belonging to the farm in public.goats
    -- (Unsold goats currently listed + SOLD goats permanently retained)
    SELECT COUNT(*)::INT INTO v_consumed
    FROM public.goats
    WHERE farm_id = p_farm_id;

    RETURN COALESCE(v_consumed, 0);
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_farm_consumed_slots(UUID) TO authenticated, anon;

-- -----------------------------------------------------------------------------
-- 2. AUTOMATIC CONSUMED SLOTS SYNCHRONIZATION TRIGGER
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.sync_farm_consumed_slots()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_farm_id UUID;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_farm_id := OLD.farm_id;
    ELSE
        v_farm_id := NEW.farm_id;
    END IF;

    IF v_farm_id IS NOT NULL THEN
        UPDATE public.farms
        SET consumed_listing_slots = public.get_farm_consumed_slots(v_farm_id),
            updated_at = NOW()
        WHERE id = v_farm_id;
    END IF;

    IF TG_OP = 'UPDATE' AND OLD.farm_id IS DISTINCT FROM NEW.farm_id AND OLD.farm_id IS NOT NULL THEN
        UPDATE public.farms
        SET consumed_listing_slots = public.get_farm_consumed_slots(OLD.farm_id),
            updated_at = NOW()
        WHERE id = OLD.farm_id;
    END IF;

    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS tr_sync_farm_consumed_slots ON public.goats;
CREATE TRIGGER tr_sync_farm_consumed_slots
    AFTER INSERT OR UPDATE OF farm_id, status OR DELETE ON public.goats
    FOR EACH ROW
    EXECUTE FUNCTION public.sync_farm_consumed_slots();

-- Initialize consumed_listing_slots for all existing farms in database
UPDATE public.farms f
SET consumed_listing_slots = public.get_farm_consumed_slots(f.id);

-- -----------------------------------------------------------------------------
-- 3. PREVENT DELETION OF SOLD GOAT RECORDS
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.prevent_sold_goat_deletion()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    -- Allow deletion during full account deletion/cleanup if configured
    IF current_setting('ammal.account_deletion', TRUE) = 'true' THEN
        RETURN OLD;
    END IF;

    -- Block deletion of sold / completed listings to preserve sales history & slot accounting
    IF OLD.status IN ('SOLD'::public.goat_status, 'COMPLETED'::public.goat_status) THEN
        RAISE EXCEPTION 'Cannot delete a sold goat listing. Sold records are permanently retained for sales history and slot accounting.'
            USING ERRCODE = '42501';
    END IF;

    RETURN OLD;
END;
$$;

DROP TRIGGER IF EXISTS tr_prevent_sold_goat_deletion ON public.goats;
CREATE TRIGGER tr_prevent_sold_goat_deletion
    BEFORE DELETE ON public.goats
    FOR EACH ROW
    EXECUTE FUNCTION public.prevent_sold_goat_deletion();

-- -----------------------------------------------------------------------------
-- 4. HARDEN LISTING LIMIT ENFORCEMENT ON INSERT
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_farm RECORD;
    v_current_count INT;
    v_limit INT;
    v_is_super_admin BOOLEAN := FALSE;
    v_is_suspended BOOLEAN := FALSE;
    v_caller_id UUID := auth.uid();
BEGIN
    IF v_caller_id IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN'), is_suspended
        INTO v_is_super_admin, v_is_suspended
        FROM public.profiles
        WHERE id = v_caller_id;

        IF v_is_suspended IS TRUE THEN
            RAISE EXCEPTION 'Suspended user account cannot create or modify goat listings.' USING ERRCODE = '42501';
        END IF;

        IF v_is_super_admin IS TRUE THEN
            RETURN NEW;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- Lock farm record FOR UPDATE to prevent race conditions during concurrent listing creation
        SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id FOR UPDATE;
        IF v_farm.id IS NULL THEN
            RAISE EXCEPTION 'Associated farm does not exist.';
        END IF;

        -- Ammal Farm is exempt from listing limits
        IF v_farm.is_ammal_own_farm IS TRUE THEN
            RETURN NEW;
        END IF;

        -- Check farm verification status
        IF v_farm.status IS DISTINCT FROM 'APPROVED'::public.farm_status THEN
            RAISE EXCEPTION 'Farm must be approved by Super Admin before listing goats.';
        END IF;

        v_limit := COALESCE(v_farm.goat_listing_limit, 2);
        v_current_count := public.get_farm_consumed_slots(NEW.farm_id);

        IF v_current_count >= v_limit THEN
            RAISE EXCEPTION 'Goat listing quota reached for farm (%) (% / % slots used). Contact Super Admin to increase limit.', v_farm.name, v_current_count, v_limit;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
    BEFORE INSERT ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();

-- -----------------------------------------------------------------------------
-- 5. HARDEN SECURE GOAT DELETION PROCEDURE
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.delete_goat_listing_secure(
    p_goat_id UUID
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller_id UUID;
    v_caller_role TEXT;
    v_goat_id UUID;
    v_farm_id UUID;
    v_farm_owner_id UUID;
    v_goat_name TEXT;
    v_goat_status public.goat_status;
    v_active_bookings_count INT := 0;
    v_historical_bookings_count INT := 0;
    v_image_urls TEXT[];
    v_img_url TEXT;
    v_cleaned_path TEXT;
BEGIN
    -- 1. Validate caller authentication
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to delete goat listing.';
    END IF;

    -- 2. Fetch caller role
    SELECT role INTO v_caller_role 
    FROM public.profiles 
    WHERE id = v_caller_id;

    IF v_caller_role IS NULL THEN
        RAISE EXCEPTION 'Caller profile not found.';
    END IF;

    -- 3. Fetch goat and farm details
    SELECT g.id, g.farm_id, f.owner_id, g.name, g.status
    INTO v_goat_id, v_farm_id, v_farm_owner_id, v_goat_name, v_goat_status
    FROM public.goats g
    JOIN public.farms f ON f.id = g.farm_id
    WHERE g.id = p_goat_id;

    IF v_goat_id IS NULL THEN
        RAISE EXCEPTION 'Goat listing % not found.', p_goat_id;
    END IF;

    -- 4. Enforce strict server-side authorization
    IF v_caller_role != 'SUPER_ADMIN' AND (v_caller_role != 'FARM_ADMIN' OR v_farm_owner_id != v_caller_id) THEN
        RAISE EXCEPTION 'Unauthorized: Only the owning Farm Admin or Super Admin can delete this goat listing.';
    END IF;

    -- 5. Disallow deletion of sold goat records
    IF v_goat_status IN ('SOLD'::public.goat_status, 'COMPLETED'::public.goat_status) THEN
        RAISE EXCEPTION 'Cannot delete a sold goat listing. Sold records are permanently retained for sales history and slot accounting.' USING ERRCODE = '42501';
    END IF;

    -- 6. Booking Safety: Check for active reservations/bookings
    SELECT COUNT(*) INTO v_active_bookings_count
    FROM public.bookings
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED', 'CONFIRMED');

    IF v_active_bookings_count > 0 THEN
        RAISE EXCEPTION 'Cannot delete goat listing with active reservations or bookings (% active). Complete or cancel active reservations first.', v_active_bookings_count;
    END IF;

    -- 7. Booking History Preservation:
    SELECT COUNT(*) INTO v_historical_bookings_count
    FROM public.bookings
    WHERE goat_id = p_goat_id;

    IF v_historical_bookings_count > 0 THEN
        UPDATE public.bookings
        SET admin_notes = COALESCE(admin_notes || E'\n', '') || 
                          '[Historical Listing: ' || COALESCE(v_goat_name, 'Goat Listing') || ' (UUID: ' || p_goat_id || ')]'
        WHERE goat_id = p_goat_id;

        UPDATE public.bookings
        SET goat_id = NULL
        WHERE goat_id = p_goat_id;
    END IF;

    UPDATE public.listing_payments
    SET goat_id = NULL
    WHERE goat_id = p_goat_id;

    -- 8. Collect all associated image paths BEFORE deleting goat_images
    SELECT ARRAY_AGG(image_url) INTO v_image_urls
    FROM public.goat_images
    WHERE goat_id = p_goat_id;

    IF v_image_urls IS NULL THEN
        v_image_urls := ARRAY[]::TEXT[];
    END IF;

    FOREACH v_img_url IN ARRAY v_image_urls LOOP
        v_cleaned_path := regexp_replace(v_img_url, '^.*/storage/v1/object/public/goat-images/', '');
        v_cleaned_path := ltrim(v_cleaned_path, '/');

        IF v_cleaned_path NOT LIKE '%farm_logo%' AND v_cleaned_path NOT LIKE '%farm_banner%' THEN
            INSERT INTO public.goat_storage_cleanup_queue (
                goat_id,
                farm_id,
                storage_path,
                bucket_name,
                status
            ) VALUES (
                p_goat_id,
                v_farm_id,
                v_cleaned_path,
                'goat-images',
                'PENDING'
            );
        END IF;
    END LOOP;

    -- 9. Delete database records in foreign-key dependency order
    DELETE FROM public.wishlist WHERE goat_id = p_goat_id;
    DELETE FROM public.reports WHERE goat_id = p_goat_id AND status = 'PENDING';
    DELETE FROM public.goat_images WHERE goat_id = p_goat_id;
    DELETE FROM public.goats WHERE id = p_goat_id;

    RETURN jsonb_build_object(
        'success', true,
        'goat_id', p_goat_id,
        'farm_id', v_farm_id,
        'deleted_images', to_jsonb(v_image_urls),
        'historical_bookings_preserved', v_historical_bookings_count
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.delete_goat_listing_secure(UUID) TO authenticated;

-- -----------------------------------------------------------------------------
-- 6. CONVENIENCE QUERY FUNCTION FOR FARM SLOT STATUS
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.get_farm_slot_info(p_farm_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_farm RECORD;
    v_consumed INT;
    v_limit INT;
    v_remaining INT;
BEGIN
    SELECT * INTO v_farm FROM public.farms WHERE id = p_farm_id;
    IF v_farm.id IS NULL THEN
        RETURN jsonb_build_object('error', 'Farm not found');
    END IF;

    v_consumed := public.get_farm_consumed_slots(p_farm_id);
    v_limit := COALESCE(v_farm.goat_listing_limit, 2);

    IF v_farm.is_ammal_own_farm IS TRUE THEN
        v_remaining := 999999;
    ELSE
        v_remaining := GREATEST(0, v_limit - v_consumed);
    END IF;

    RETURN jsonb_build_object(
        'farm_id', v_farm.id,
        'goat_listing_limit', v_limit,
        'consumed_listing_slots', v_consumed,
        'remaining_slots', v_remaining,
        'is_ammal_own_farm', v_farm.is_ammal_own_farm
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_farm_slot_info(UUID) TO authenticated, anon;
