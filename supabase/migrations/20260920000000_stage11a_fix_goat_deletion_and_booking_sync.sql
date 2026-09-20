-- =============================================================================
-- STAGE 11A: FIX GOAT DELETION RESTRICTIONS & SYNC RESERVED GOAT BOOKINGS
-- =============================================================================

-- 1. UPDATE SERVER-AUTHORITATIVE GOAT DELETION PROCEDURE
-- Allow farm owner or Super Admin to delete goat listings safely even if
-- reservations or pending booking holds exist. Active holds are automatically
-- cancelled/unlinked, and expired holds are cleaned up without raising errors.

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
    SELECT g.id, g.farm_id, f.owner_id, g.name
    INTO v_goat_id, v_farm_id, v_farm_owner_id, v_goat_name
    FROM public.goats g
    LEFT JOIN public.farms f ON f.id = g.farm_id
    WHERE g.id = p_goat_id;

    IF v_goat_id IS NULL THEN
        -- If goat record is already absent, return success idempotently
        RETURN jsonb_build_object(
            'success', true,
            'goat_id', p_goat_id,
            'message', 'Goat listing already removed or does not exist.'
        );
    END IF;

    -- 4. Enforce strict server-side authorization
    -- Super Admin can delete any goat listing.
    -- Farm Admin can delete goats belonging to their own farm.
    IF v_caller_role != 'SUPER_ADMIN' AND (v_caller_role != 'FARM_ADMIN' OR (v_farm_owner_id IS NOT NULL AND v_farm_owner_id != v_caller_id)) THEN
        RAISE EXCEPTION 'Unauthorized: Only the owning Farm Admin or Super Admin can delete this goat listing.';
    END IF;

    -- 5. Auto-expire overdue holds for this goat
    UPDATE public.bookings
    SET status = 'EXPIRED',
        admin_notes = COALESCE(admin_notes || E'\n', '') || '[Hold expired automatically during listing deletion]'
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at IS NOT NULL
      AND hold_expires_at <= NOW();

    -- 6. For remaining active PENDING or RESERVED bookings:
    -- Cancel them gracefully so the farm owner isn't locked out of managing their inventory
    UPDATE public.bookings
    SET status = 'CANCELLED',
        admin_notes = COALESCE(admin_notes || E'\n', '') || 
                      '[Listing deleted by farm owner. Goat: ' || COALESCE(v_goat_name, 'Unknown') || ' (UUID: ' || p_goat_id || ')]'
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED');

    -- 7. Booking History Preservation & Foreign Key Unlinking:
    -- Record listing snapshot before unlinking goat_id so financial & audit records remain intact
    UPDATE public.bookings
    SET admin_notes = COALESCE(admin_notes || E'\n', '') || 
                      '[Historical Listing: ' || COALESCE(v_goat_name, 'Goat Listing') || ' (UUID: ' || p_goat_id || ')]',
        goat_id = NULL
    WHERE goat_id = p_goat_id;

    -- Unlink listing payments referencing this goat
    UPDATE public.listing_payments
    SET goat_id = NULL
    WHERE goat_id = p_goat_id;

    -- 8. Collect associated image paths BEFORE deleting goat_images
    SELECT ARRAY_AGG(image_url) INTO v_image_urls
    FROM public.goat_images
    WHERE goat_id = p_goat_id;

    IF v_image_urls IS NULL THEN
        v_image_urls := ARRAY[]::TEXT[];
    END IF;

    -- Enqueue each image path for storage cleanup
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

    -- 9. Delete database records in dependency order
    DELETE FROM public.wishlist WHERE goat_id = p_goat_id;
    DELETE FROM public.reports WHERE goat_id = p_goat_id;
    DELETE FROM public.goat_images WHERE goat_id = p_goat_id;
    DELETE FROM public.goats WHERE id = p_goat_id;

    -- 10. Return execution summary
    RETURN jsonb_build_object(
        'success', true,
        'goat_id', p_goat_id,
        'farm_id', v_farm_id,
        'deleted_images', to_jsonb(v_image_urls),
        'historical_bookings_preserved', true
    );
END;
$$;

REVOKE ALL ON FUNCTION public.delete_goat_listing_secure(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_goat_listing_secure(UUID) TO authenticated;
