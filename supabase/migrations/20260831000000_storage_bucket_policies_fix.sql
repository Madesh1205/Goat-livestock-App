-- =============================================================================
-- AMMAL FARM PLATFORM - ROBUST STORAGE POLICIES & BUCKET SETUP
-- File: /supabase/migrations/20260831000000_storage_bucket_policies_fix.sql
-- =============================================================================

-- Ensure buckets exist and are public
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES 
    ('goat-images', 'goat-images', true, 15728640, ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/*']),
    ('goat-photos', 'goat-photos', true, 15728640, ARRAY['image/jpeg', 'image/png', 'image/webp', 'image/*']),
    ('farm-docs', 'farm-docs', true, 15728640, NULL),
    ('vet-certificates', 'vet-certificates', true, 15728640, NULL)
ON CONFLICT (id) DO UPDATE SET 
    public = true,
    file_size_limit = 15728640;

-- Drop any conflicting or restrictive storage policies
DROP POLICY IF EXISTS "Public View Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Upload Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Update Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Delete Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can upload goat photos" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can update goat photos" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated users can delete goat photos" ON storage.objects;
DROP POLICY IF EXISTS "Allow all users to view goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to upload goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to update goat images" ON storage.objects;
DROP POLICY IF EXISTS "Allow authenticated to delete goat images" ON storage.objects;

-- Create clean permissive public viewing policy for goat images and photos
CREATE POLICY "Allow all users to view goat images" ON storage.objects
    FOR SELECT
    USING (bucket_id IN ('goat-images', 'goat-photos', 'farm-docs', 'vet-certificates'));

-- Create robust upload policy for storage objects
CREATE POLICY "Allow authenticated to upload goat images" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (bucket_id IN ('goat-images', 'goat-photos', 'farm-docs', 'vet-certificates'));

-- Create robust update policy for storage objects
CREATE POLICY "Allow authenticated to update goat images" ON storage.objects
    FOR UPDATE TO authenticated
    USING (bucket_id IN ('goat-images', 'goat-photos', 'farm-docs', 'vet-certificates'));

-- Create robust delete policy for storage objects
CREATE POLICY "Allow authenticated to delete goat images" ON storage.objects
    FOR DELETE TO authenticated
    USING (bucket_id IN ('goat-images', 'goat-photos', 'farm-docs', 'vet-certificates'));

-- Fallback for anon upload if user session is refreshing
CREATE POLICY "Allow anon to upload goat images fallback" ON storage.objects
    FOR INSERT TO anon
    WITH CHECK (bucket_id IN ('goat-images', 'goat-photos'));
