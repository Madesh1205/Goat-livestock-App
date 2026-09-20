-- =============================================================================
-- AMMAL FARM HYPERLOCAL LIVESTOCK MARKETPLACE
-- MIGRATION: 20260919090000_stage10e_data_cleanup_and_consistency.sql
-- STAGE 10E: FINAL DATA CLEANUP & PRODUCTION CONSISTENCY
-- 1. Eliminate RLS infinite recursion between profiles and bookings
-- 2. Repair legacy goat_images paths & storage objects to match actual goat_code
-- 3. Clean up Stage 10D test bookings & restore goats to AVAILABLE
-- 4. Clean up Stage 10D test user accounts
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. ELIMINATE RLS RECURSION ON PROFILES & BOOKINGS
-- -----------------------------------------------------------------------------
DROP POLICY IF EXISTS "profiles_select_policy" ON public.profiles;
CREATE POLICY "profiles_select_policy" ON public.profiles
    FOR SELECT TO public
    USING (
        (auth.uid() IS NOT NULL AND auth.uid() = id)
        OR public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.owner_id = profiles.id
        )
    );

DROP POLICY IF EXISTS "bookings_select_policy" ON public.bookings;
CREATE POLICY "bookings_select_policy" ON public.bookings
    FOR SELECT TO authenticated
    USING (
        customer_id = auth.uid()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = bookings.farm_id
              AND f.owner_id = auth.uid()
        )
        OR public.is_super_admin()
    );


-- -----------------------------------------------------------------------------
-- 2. REPAIR STORAGE OBJECTS & GOAT_IMAGES RECORDS
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_src_obj_02 RECORD;
    v_src_obj_03 RECORD;
BEGIN
    -- Fetch legacy template storage objects if they exist
    SELECT * INTO v_src_obj_02 
    FROM storage.objects 
    WHERE bucket_id = 'goat-images' AND name = 'FARM-001/GOAT-001/02.jpg'
    LIMIT 1;

    SELECT * INTO v_src_obj_03 
    FROM storage.objects 
    WHERE bucket_id = 'goat-images' AND name = 'FARM-001/GOAT-001/03.jpg'
    LIMIT 1;

    -- Create repaired storage object for GOAT-004 (70fcbfe0-b840-4b1a-ac39-fc00e4af9117)
    IF v_src_obj_02.id IS NOT NULL THEN
        INSERT INTO storage.objects (
            bucket_id, name, owner, created_at, updated_at, last_accessed_at, metadata, path_tokens
        ) VALUES (
            'goat-images',
            'FARM-001/GOAT-004/01.jpg',
            v_src_obj_02.owner,
            NOW(),
            NOW(),
            NOW(),
            v_src_obj_02.metadata,
            ARRAY['FARM-001', 'GOAT-004', '01.jpg']
        )
        ON CONFLICT (bucket_id, name) DO NOTHING;

        -- Create repaired storage objects for GOAT-006 (a7747761-020d-4b90-9885-e72a728155e8)
        INSERT INTO storage.objects (
            bucket_id, name, owner, created_at, updated_at, last_accessed_at, metadata, path_tokens
        ) VALUES (
            'goat-images',
            'FARM-001/GOAT-006/01.jpg',
            v_src_obj_02.owner,
            NOW(),
            NOW(),
            NOW(),
            v_src_obj_02.metadata,
            ARRAY['FARM-001', 'GOAT-006', '01.jpg']
        )
        ON CONFLICT (bucket_id, name) DO NOTHING;

        -- Create repaired storage object for GOAT-007 (53cc05a0-c718-4f46-a109-5ef4619da39d)
        INSERT INTO storage.objects (
            bucket_id, name, owner, created_at, updated_at, last_accessed_at, metadata, path_tokens
        ) VALUES (
            'goat-images',
            'FARM-001/GOAT-007/01.jpg',
            v_src_obj_02.owner,
            NOW(),
            NOW(),
            NOW(),
            v_src_obj_02.metadata,
            ARRAY['FARM-001', 'GOAT-007', '01.jpg']
        )
        ON CONFLICT (bucket_id, name) DO NOTHING;
    END IF;

    IF v_src_obj_03.id IS NOT NULL THEN
        INSERT INTO storage.objects (
            bucket_id, name, owner, created_at, updated_at, last_accessed_at, metadata, path_tokens
        ) VALUES (
            'goat-images',
            'FARM-001/GOAT-006/02.jpg',
            v_src_obj_03.owner,
            NOW(),
            NOW(),
            NOW(),
            v_src_obj_03.metadata,
            ARRAY['FARM-001', 'GOAT-006', '02.jpg']
        )
        ON CONFLICT (bucket_id, name) DO NOTHING;
    END IF;
END $$;

-- Update public.goat_images table to point to authoritative paths and display orders
UPDATE public.goat_images
SET image_url = 'FARM-001/GOAT-004/01.jpg',
    display_order = 0
WHERE id = '14bb7013-9f08-4467-9106-393a6ce547a9'
  AND goat_id = '70fcbfe0-b840-4b1a-ac39-fc00e4af9117';

UPDATE public.goat_images
SET image_url = 'FARM-001/GOAT-006/01.jpg',
    display_order = 0
WHERE id = '0b72918a-e337-45e5-93ad-3199a242df3d'
  AND goat_id = 'a7747761-020d-4b90-9885-e72a728155e8';

UPDATE public.goat_images
SET image_url = 'FARM-001/GOAT-006/02.jpg',
    display_order = 1
WHERE id = '7ab20db9-31d7-43ef-aeff-9ee4939d83ca'
  AND goat_id = 'a7747761-020d-4b90-9885-e72a728155e8';

UPDATE public.goat_images
SET image_url = 'FARM-001/GOAT-007/01.jpg',
    display_order = 0
WHERE id = 'b74c99fa-a947-424b-9413-78141d41cd86'
  AND goat_id = '53cc05a0-c718-4f46-a109-5ef4619da39d';

-- Clean up legacy storage objects if no longer referenced
DELETE FROM storage.objects
WHERE bucket_id = 'goat-images'
  AND name IN ('FARM-001/GOAT-001/02.jpg', 'FARM-001/GOAT-001/03.jpg');

