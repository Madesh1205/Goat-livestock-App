-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI MIGRATION (ap-south-1)
-- File: 04_storage_setup.sql
-- Step 4: Storage Buckets, MIME Restrictions, Size Limits & RLS Hardening (Stage 12B)
-- =============================================================================

-- =============================================================================
-- 1. PROVISION STORAGE BUCKETS
-- =============================================================================

INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES 
    ('goat-images', 'goat-images', true, 15728640, ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/gif']),
    ('farm-docs', 'farm-docs', false, 20971520, ARRAY['application/pdf', 'image/jpeg', 'image/png']),
    ('vet-certificates', 'vet-certificates', false, 20971520, ARRAY['application/pdf', 'image/jpeg', 'image/png'])
ON CONFLICT (id) DO UPDATE SET 
    public = EXCLUDED.public,
    file_size_limit = EXCLUDED.file_size_limit,
    allowed_mime_types = EXCLUDED.allowed_mime_types;

-- Explicitly enforce public/private visibility flags
UPDATE storage.buckets SET public = true WHERE id = 'goat-images';
UPDATE storage.buckets SET public = false WHERE id IN ('farm-docs', 'vet-certificates');

-- Enable RLS on storage.objects
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- 2. DROP OBSOLETE & PERMISSIVE STORAGE POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "Allow all users to view goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to upload goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to update goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to delete goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow anon to upload goat images fallback" ON storage.objects;
DROP POLICY IF EXISTS "storage_goat_images_public_select" ON storage.objects;
DROP POLICY IF EXISTS "storage_goat_images_insert" ON storage.objects;
DROP POLICY IF EXISTS "storage_goat_images_update" ON storage.objects;
DROP POLICY IF EXISTS "storage_goat_images_delete" ON storage.objects;
DROP POLICY IF EXISTS "storage_farm_docs_select" ON storage.objects;
DROP POLICY IF EXISTS "storage_farm_docs_insert" ON storage.objects;
DROP POLICY IF EXISTS "storage_farm_docs_update" ON storage.objects;
DROP POLICY IF EXISTS "storage_farm_docs_delete" ON storage.objects;
DROP POLICY IF EXISTS "storage_vet_certificates_select" ON storage.objects;
DROP POLICY IF EXISTS "storage_vet_certificates_insert" ON storage.objects;
DROP POLICY IF EXISTS "storage_vet_certificates_update" ON storage.objects;
DROP POLICY IF EXISTS "storage_vet_certificates_delete" ON storage.objects;

-- =============================================================================
-- 3. GOAT-IMAGES POLICIES (Public Read, Protected Farm-Path Write)
-- =============================================================================

-- SELECT: Publicly readable for marketplace catalog & farm branding
CREATE POLICY "storage_goat_images_public_select" ON storage.objects
    FOR SELECT TO public
    USING (bucket_id = 'goat-images');

-- INSERT: Authenticated Super Admin or Farm Admin for their designated farm folder
CREATE POLICY "storage_goat_images_insert" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (
        bucket_id = 'goat-images'
        AND public.can_modify_storage_goat_image(name)
    );

-- UPDATE: Authenticated Super Admin or Farm Admin for their designated farm folder
CREATE POLICY "storage_goat_images_update" ON storage.objects
    FOR UPDATE TO authenticated
    USING (
        bucket_id = 'goat-images'
        AND public.can_modify_storage_goat_image(name)
    )
    WITH CHECK (
        bucket_id = 'goat-images'
        AND public.can_modify_storage_goat_image(name)
    );

-- DELETE: Authenticated Super Admin or Farm Admin for their designated farm folder
CREATE POLICY "storage_goat_images_delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (
        bucket_id = 'goat-images'
        AND public.can_modify_storage_goat_image(name)
    );

-- =============================================================================
-- 4. FARM-DOCS POLICIES (Private: Super Admin & Authorized Farm Admin Only)
-- =============================================================================

CREATE POLICY "storage_farm_docs_select" ON storage.objects
    FOR SELECT TO authenticated
    USING (
        bucket_id = 'farm-docs'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_farm_docs_insert" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (
        bucket_id = 'farm-docs'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_farm_docs_update" ON storage.objects
    FOR UPDATE TO authenticated
    USING (
        bucket_id = 'farm-docs'
        AND public.can_access_storage_farm_path(name)
    )
    WITH CHECK (
        bucket_id = 'farm-docs'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_farm_docs_delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (
        bucket_id = 'farm-docs'
        AND public.can_access_storage_farm_path(name)
    );

-- =============================================================================
-- 5. VET-CERTIFICATES POLICIES (Private: Super Admin & Authorized Farm Admin Only)
-- =============================================================================

CREATE POLICY "storage_vet_certificates_select" ON storage.objects
    FOR SELECT TO authenticated
    USING (
        bucket_id = 'vet-certificates'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_vet_certificates_insert" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (
        bucket_id = 'vet-certificates'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_vet_certificates_update" ON storage.objects
    FOR UPDATE TO authenticated
    USING (
        bucket_id = 'vet-certificates'
        AND public.can_access_storage_farm_path(name)
    )
    WITH CHECK (
        bucket_id = 'vet-certificates'
        AND public.can_access_storage_farm_path(name)
    );

CREATE POLICY "storage_vet_certificates_delete" ON storage.objects
    FOR DELETE TO authenticated
    USING (
        bucket_id = 'vet-certificates'
        AND public.can_access_storage_farm_path(name)
    );
