-- =============================================================================
-- AMMAL FARM PLATFORM — STAGE 12B
-- SUPABASE STORAGE SECURITY AUDIT & HARDENING MIGRATION
-- Migration: 20260914020000_stage12b_storage_security_audit.sql
-- =============================================================================
-- Strict Access Control Matrix:
-- 1. goat-images: PUBLIC READ (marketplace catalog & farm branding).
--    INSERT/UPDATE/DELETE: Restricted to Super Admin or owning Farm Admin for their specific farm path.
-- 2. farm-docs: PRIVATE (public = false).
--    SELECT/INSERT/UPDATE/DELETE: Restricted to Super Admin or authorized Farm Admin for that farm.
-- 3. vet-certificates: PRIVATE (public = false).
--    SELECT/INSERT/UPDATE/DELETE: Restricted to Super Admin or authorized Farm Admin for that farm.
--
-- Zero permissive wildcards: All operations are strictly bounded by identity and role.
-- Farm Admin path escalation prevented: Cross-farm tampering blocked server-side.
-- Customers and Anonymous users blocked from uploading/modifying any storage bucket.
-- =============================================================================

-- 1. ENSURE STORAGE BUCKETS EXIST AND CONFIGURE STRICT VISIBILITY
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
-- 2. DROP ALL OBSOLETE AND DANGEROUS PERMISSIVE STORAGE POLICIES
-- =============================================================================

-- Drop overly broad policies from 20260831000000_storage_bucket_policies_fix.sql
DROP POLICY IF EXISTS "Allow all users to view goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to upload goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to update goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to delete goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow anon to upload goat images fallback" ON storage.objects;

-- Drop legacy goat-photos policies
DROP POLICY IF EXISTS "Public View Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Upload Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Update Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Delete Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Public Read Goat Photos" ON storage.objects;
DROP POLICY IF EXISTS "Farm Admin Upload Goat Photos" ON storage.objects;
DROP POLICY IF EXISTS "Farm Admin Modify Goat Photos" ON storage.objects;
DROP POLICY IF EXISTS "Farm Admin Delete Goat Photos" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can upload goat photos" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can update goat photos" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can delete goat photos" ON storage.objects;

-- Drop previous vet/docs policies
DROP POLICY IF EXISTS "Vet Certificates Access" ON storage.objects;
DROP POLICY IF EXISTS "Vet Certificates Upload" ON storage.objects;

-- Drop any previous Stage 12B policies if re-running
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
-- 3. SERVER-SIDE AUTHORIZATION HELPER FUNCTIONS
-- =============================================================================

-- Validates whether current authenticated user owns or administers the farm specified in the storage path.
-- Handles path patterns:
-- - 'farm/<farm_id>/...'
-- - '<farm_id>/...'
CREATE OR REPLACE FUNCTION public.can_access_storage_farm_path(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_farm_uuid UUID;
BEGIN
    -- Super Admin has universal operational access
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    -- Unauthenticated callers are strictly denied
    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern A: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' AND v_target != '' THEN
        BEGIN
            v_farm_uuid := v_target::UUID;
            RETURN EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = v_farm_uuid
                  AND (
                      f.owner_id = auth.uid()
                      OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
                  )
            );
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern B: '<farm_id>/...' (direct farm UUID at root)
    BEGIN
        v_farm_uuid := v_prefix::UUID;
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = v_farm_uuid
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- Validates whether current authenticated user can insert, update, or delete an object in goat-images.
-- Prevents cross-farm path escalation and unauthorized uploads by Customers or Anonymous users.
CREATE OR REPLACE FUNCTION public.can_modify_storage_goat_image(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_user_role TEXT;
    v_goat_uuid UUID;
BEGIN
    -- Super Admin has universal access
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    -- Unauthenticated callers are strictly denied
    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    -- Check caller role: must be at least FARM_ADMIN or a registered farm owner
    SELECT role::TEXT INTO v_user_role FROM public.profiles WHERE id = auth.uid();
    IF v_user_role != 'FARM_ADMIN' AND v_user_role != 'SUPER_ADMIN' THEN
        -- Check if user is farm owner in farms table
        IF NOT EXISTS (SELECT 1 FROM public.farms WHERE owner_id = auth.uid()) THEN
            RETURN FALSE;
        END IF;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern 1: 'farm/<farm_id>/...' (covers farm logos, banners, and farm/<farm_id>/goat/<goat_id>/...)
    IF v_prefix = 'farm' THEN
        RETURN public.can_access_storage_farm_path(object_name);
    END IF;

    -- Pattern 2: 'goat/<goat_id>/...'
    IF v_prefix = 'goat' AND v_target != '' THEN
        BEGIN
            v_goat_uuid := v_target::UUID;
            -- If goat already exists in the database, caller must own that specific goat's farm
            IF EXISTS (SELECT 1 FROM public.goats WHERE id = v_goat_uuid) THEN
                RETURN EXISTS (
                    SELECT 1 FROM public.goats g
                    JOIN public.farms f ON f.id = g.farm_id
                    WHERE g.id = v_goat_uuid
                      AND (
                          f.owner_id = auth.uid()
                          OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid())
                      )
                );
            ELSE
                -- New goat draft upload: verify caller is an approved farm admin with a valid active farm
                RETURN EXISTS (
                    SELECT 1 FROM public.farms f
                    WHERE (f.owner_id = auth.uid() OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid()))
                      AND f.status = 'APPROVED'
                );
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern 3: direct '<farm_id>/...'
    IF public.can_access_storage_farm_path(object_name) THEN
        RETURN TRUE;
    END IF;

    -- Pattern 4: User avatar / profile picture 'avatar/<user_id>/...'
    IF (v_prefix = 'avatar' OR v_prefix = 'profile') AND v_target = auth.uid()::TEXT THEN
        RETURN TRUE;
    END IF;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 4. STORAGE RLS POLICIES FOR 'goat-images'
-- =============================================================================

-- 4.1 Public marketplace read access for goat photos and farm branding
CREATE POLICY "storage_goat_images_public_select"
ON storage.objects FOR SELECT
TO public
USING (bucket_id = 'goat-images');

-- 4.2 Only authorized Farm Admin or Super Admin can upload
CREATE POLICY "storage_goat_images_insert"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'goat-images'
    AND public.can_modify_storage_goat_image(name)
);

-- 4.3 Only authorized Farm Admin or Super Admin can update
CREATE POLICY "storage_goat_images_update"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'goat-images'
    AND public.can_modify_storage_goat_image(name)
)
WITH CHECK (
    bucket_id = 'goat-images'
    AND public.can_modify_storage_goat_image(name)
);

-- 4.4 Only authorized Farm Admin or Super Admin can delete
CREATE POLICY "storage_goat_images_delete"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'goat-images'
    AND public.can_modify_storage_goat_image(name)
);

-- =============================================================================
-- 5. STORAGE RLS POLICIES FOR 'farm-docs' (CONFIDENTIAL / PRIVATE)
-- =============================================================================

-- 5.1 Strictly private: only owning Farm Admin or Super Admin may view
CREATE POLICY "storage_farm_docs_select"
ON storage.objects FOR SELECT
TO authenticated
USING (
    bucket_id = 'farm-docs'
    AND public.can_access_storage_farm_path(name)
);

-- 5.2 Only authorized Farm Admin for that farm or Super Admin may upload
CREATE POLICY "storage_farm_docs_insert"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'farm-docs'
    AND public.can_access_storage_farm_path(name)
);

-- 5.3 Only authorized Farm Admin for that farm or Super Admin may update
CREATE POLICY "storage_farm_docs_update"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'farm-docs'
    AND public.can_access_storage_farm_path(name)
)
WITH CHECK (
    bucket_id = 'farm-docs'
    AND public.can_access_storage_farm_path(name)
);

-- 5.4 Only authorized Farm Admin for that farm or Super Admin may delete
CREATE POLICY "storage_farm_docs_delete"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'farm-docs'
    AND public.can_access_storage_farm_path(name)
);

-- =============================================================================
-- 6. STORAGE RLS POLICIES FOR 'vet-certificates' (CONFIDENTIAL / PRIVATE)
-- =============================================================================

-- 6.1 Strictly private: only owning Farm Admin or Super Admin may view
CREATE POLICY "storage_vet_certificates_select"
ON storage.objects FOR SELECT
TO authenticated
USING (
    bucket_id = 'vet-certificates'
    AND public.can_access_storage_farm_path(name)
);

-- 6.2 Only authorized Farm Admin for that farm or Super Admin may upload
CREATE POLICY "storage_vet_certificates_insert"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'vet-certificates'
    AND public.can_access_storage_farm_path(name)
);

-- 6.3 Only authorized Farm Admin for that farm or Super Admin may update
CREATE POLICY "storage_vet_certificates_update"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'vet-certificates'
    AND public.can_access_storage_farm_path(name)
)
WITH CHECK (
    bucket_id = 'vet-certificates'
    AND public.can_access_storage_farm_path(name)
);

-- 6.4 Only authorized Farm Admin for that farm or Super Admin may delete
CREATE POLICY "storage_vet_certificates_delete"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'vet-certificates'
    AND public.can_access_storage_farm_path(name)
);
