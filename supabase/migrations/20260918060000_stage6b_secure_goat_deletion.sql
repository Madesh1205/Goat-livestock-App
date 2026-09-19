-- =============================================================================
-- STAGE 6B: SECURE GOAT DELETION & STORAGE IMAGE CLEANUP
-- =============================================================================
-- 1. Foreign Key Safety: Ensure bookings.goat_id is nullable ON DELETE SET NULL
--    so historical financial, audit, and tax records are never destroyed.
-- 2. Storage Cleanup Queue: Guarantees atomicity and retryability for object storage files.
-- 3. delete_goat_listing_secure(p_goat_id UUID): Server-authoritative deletion RPC.
-- =============================================================================

-- 1. FOREIGN KEY SAFETY & AUDIT INTEGRITY ON BOOKINGS
ALTER TABLE public.bookings ALTER COLUMN goat_id DROP NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints 
        WHERE constraint_name = 'bookings_goat_id_fkey' 
          AND table_name = 'bookings'
    ) THEN
        ALTER TABLE public.bookings DROP CONSTRAINT bookings_goat_id_fkey;
    END IF;
    ALTER TABLE public.bookings 
        ADD CONSTRAINT bookings_goat_id_fkey 
        FOREIGN KEY (goat_id) REFERENCES public.goats(id) ON DELETE SET NULL;
END $$;

-- 2. STORAGE CLEANUP QUEUE TABLE
CREATE TABLE IF NOT EXISTS public.goat_storage_cleanup_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL,
    farm_id UUID,
    storage_path TEXT NOT NULL,
    bucket_name TEXT NOT NULL DEFAULT 'goat-images',
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')),
    retry_count INT NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_goat_storage_cleanup_status 
    ON public.goat_storage_cleanup_queue (status);

CREATE INDEX IF NOT EXISTS idx_goat_storage_cleanup_goat_id 
    ON public.goat_storage_cleanup_queue (goat_id);

ALTER TABLE public.goat_storage_cleanup_queue ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "cleanup_queue_authenticated_policy" ON public.goat_storage_cleanup_queue;
CREATE POLICY "cleanup_queue_authenticated_policy" ON public.goat_storage_cleanup_queue
    FOR ALL TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND p.role IN ('SUPER_ADMIN', 'FARM_ADMIN')
        )
    );

-- 3. SERVER-AUTHORITATIVE GOAT DELETION PROCEDURE
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
    SELECT g.id, g.farm_id, f.owner_id, g.name
    INTO v_goat_id, v_farm_id, v_farm_owner_id, v_goat_name
    FROM public.goats g
    JOIN public.farms f ON f.id = g.farm_id
    WHERE g.id = p_goat_id;

    IF v_goat_id IS NULL THEN
        RAISE EXCEPTION 'Goat listing % not found.', p_goat_id;
    END IF;

    -- 4. Enforce strict server-side authorization
    -- Super Admin can delete any goat listing.
    -- Farm Admin can only delete goats belonging to their own farm.
    -- Customers cannot delete goat listings.
    IF v_caller_role != 'SUPER_ADMIN' AND (v_caller_role != 'FARM_ADMIN' OR v_farm_owner_id != v_caller_id) THEN
        RAISE EXCEPTION 'Unauthorized: Only the owning Farm Admin or Super Admin can delete this goat listing.';
    END IF;

    -- 5. Booking Safety: Check for active reservations/bookings
    SELECT COUNT(*) INTO v_active_bookings_count
    FROM public.bookings
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED', 'CONFIRMED');

    IF v_active_bookings_count > 0 THEN
        RAISE EXCEPTION 'Cannot delete goat listing with active reservations or bookings (% active). Complete or cancel active reservations first.', v_active_bookings_count;
    END IF;

    -- 6. Booking History Preservation:
    -- Check historical bookings (COMPLETED, CANCELLED, EXPIRED)
    SELECT COUNT(*) INTO v_historical_bookings_count
    FROM public.bookings
    WHERE goat_id = p_goat_id;

    IF v_historical_bookings_count > 0 THEN
        -- Preserve historical context in admin notes before unlinking foreign key
        UPDATE public.bookings
        SET admin_notes = COALESCE(admin_notes || E'\n', '') || 
                          '[Historical Listing: ' || COALESCE(v_goat_name, 'Goat Listing') || ' (UUID: ' || p_goat_id || ')]'
        WHERE goat_id = p_goat_id;

        -- Safely unlink foreign key so booking rows remain intact for ledger/tax audits
        UPDATE public.bookings
        SET goat_id = NULL
        WHERE goat_id = p_goat_id;
    END IF;

    -- Unlink listing payments pointing to this goat if any
    UPDATE public.listing_payments
    SET goat_id = NULL
    WHERE goat_id = p_goat_id;

    -- 7. Collect all associated image paths BEFORE deleting goat_images
    SELECT ARRAY_AGG(image_url) INTO v_image_urls
    FROM public.goat_images
    WHERE goat_id = p_goat_id;

    IF v_image_urls IS NULL THEN
        v_image_urls := ARRAY[]::TEXT[];
    END IF;

    -- Enqueue each image path for storage cleanup to ensure atomicity and retryability
    FOREACH v_img_url IN ARRAY v_image_urls LOOP
        -- Remove full URL prefix if present to store clean path
        v_cleaned_path := regexp_replace(v_img_url, '^.*/storage/v1/object/public/goat-images/', '');
        v_cleaned_path := ltrim(v_cleaned_path, '/');

        -- Safety guard: Do not enqueue farm branding assets
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

    -- 8. Delete database records in foreign-key dependency order
    -- Remove wishlist entries
    DELETE FROM public.wishlist WHERE goat_id = p_goat_id;

    -- Remove pending reports on this goat
    DELETE FROM public.reports WHERE goat_id = p_goat_id AND status = 'PENDING';

    -- Remove goat_images records
    DELETE FROM public.goat_images WHERE goat_id = p_goat_id;

    -- Remove goat listing record
    DELETE FROM public.goats WHERE id = p_goat_id;

    -- 9. Return execution summary to client
    RETURN jsonb_build_object(
        'success', true,
        'goat_id', p_goat_id,
        'farm_id', v_farm_id,
        'deleted_images', to_jsonb(v_image_urls),
        'historical_bookings_preserved', v_historical_bookings_count
    );
END;
$$;

-- 4. STORAGE CLEANUP COMPLETION CALLBACK PROCEDURE
CREATE OR REPLACE FUNCTION public.complete_storage_cleanup(
    p_goat_id UUID,
    p_completed_paths TEXT[],
    p_failed_paths TEXT[] DEFAULT ARRAY[]::TEXT[],
    p_error TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_path TEXT;
BEGIN
    -- Mark successfully deleted storage files as COMPLETED
    IF p_completed_paths IS NOT NULL AND array_length(p_completed_paths, 1) > 0 THEN
        FOREACH v_path IN ARRAY p_completed_paths LOOP
            UPDATE public.goat_storage_cleanup_queue
            SET status = 'COMPLETED',
                updated_at = NOW()
            WHERE goat_id = p_goat_id
              AND (storage_path = v_path OR storage_path LIKE '%' || v_path);
        END LOOP;
    END IF;

    -- Mark failed storage files as FAILED with error message
    IF p_failed_paths IS NOT NULL AND array_length(p_failed_paths, 1) > 0 THEN
        FOREACH v_path IN ARRAY p_failed_paths LOOP
            UPDATE public.goat_storage_cleanup_queue
            SET status = 'FAILED',
                retry_count = retry_count + 1,
                error_message = p_error,
                updated_at = NOW()
            WHERE goat_id = p_goat_id
              AND (storage_path = v_path OR storage_path LIKE '%' || v_path);
        END LOOP;
    END IF;

    RETURN jsonb_build_object('success', true);
END;
$$;
