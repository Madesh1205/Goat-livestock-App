-- =============================================================================
-- AMMAL FARM PLATFORM - ENFORCE ONLY APPROVED FARMS CAN UPLOAD GOAT DETAILS
-- File: /supabase/migrations/20260830020000_approved_farms_only_upload_policy.sql
-- =============================================================================

-- 1. DROP EXISTING POLICIES FOR GOATS & GOAT IMAGES
DROP POLICY IF EXISTS "goats_insert_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_update_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_delete_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goat_images_modify_authenticated" ON public.goat_images;

-- 2. ONLY APPROVED FARMS (OR SUPER ADMIN) CAN UPLOAD / INSERT GOAT DETAILS
CREATE POLICY "goats_insert_authenticated" ON public.goats
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text 
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    );

-- 3. ONLY APPROVED FARMS (OR SUPER ADMIN) CAN UPDATE GOAT DETAILS
CREATE POLICY "goats_update_authenticated" ON public.goats
    FOR UPDATE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text 
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    )
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text 
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    );

-- 4. ONLY APPROVED FARMS (OR SUPER ADMIN) CAN DELETE GOAT DETAILS
CREATE POLICY "goats_delete_authenticated" ON public.goats
    FOR DELETE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text 
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    );

-- 5. ONLY APPROVED FARMS (OR SUPER ADMIN) CAN UPLOAD / MANAGE GOAT PHOTOS
CREATE POLICY "goat_images_modify_authenticated" ON public.goat_images
    FOR ALL TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id::text = g.farm_id::text
            WHERE g.id::text = goat_images.goat_id::text
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    )
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id::text = g.farm_id::text
            WHERE g.id::text = goat_images.goat_id::text
              AND f.owner_id::text = auth.uid()::text
              AND f.status = 'APPROVED'
        )
    );
