-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI VERIFICATION & VALIDATION SUITE
-- File: 09_verification_and_validation.sql
-- Run this in Mumbai Supabase SQL Editor (ap-south-1) after migration
-- =============================================================================

-- =============================================================================
-- 1. RECORD COUNT VERIFICATION MATRIX
-- =============================================================================
-- Expected Production Baseline:
-- Auth users: 3 | auth.identities: 3 | profiles: 3 | farms: 2 | breeds: 10
-- goats: 3 | goat_images: 7 | bookings: 4 | reviews: 0 | wishlist: 1
-- notifications: 17 | reports: 0 | audit_logs: 1 | storage objects: 18

WITH actual_counts AS (
    SELECT 'auth.users' AS entity, COUNT(*) AS count FROM auth.users
    UNION ALL SELECT 'auth.identities', COUNT(*) FROM auth.identities
    UNION ALL SELECT 'public.profiles', COUNT(*) FROM public.profiles
    UNION ALL SELECT 'public.farms', COUNT(*) FROM public.farms
    UNION ALL SELECT 'public.breeds', COUNT(*) FROM public.breeds
    UNION ALL SELECT 'public.goats', COUNT(*) FROM public.goats
    UNION ALL SELECT 'public.goat_images', COUNT(*) FROM public.goat_images
    UNION ALL SELECT 'public.bookings', COUNT(*) FROM public.bookings
    UNION ALL SELECT 'public.reviews', COUNT(*) FROM public.reviews
    UNION ALL SELECT 'public.wishlist', COUNT(*) FROM public.wishlist
    UNION ALL SELECT 'public.notifications', COUNT(*) FROM public.notifications
    UNION ALL SELECT 'public.reports', COUNT(*) FROM public.reports
    UNION ALL SELECT 'public.audit_logs', COUNT(*) FROM public.audit_logs
    UNION ALL SELECT 'storage.objects', COUNT(*) FROM storage.objects
),
expected_counts AS (
    SELECT 'auth.users' AS entity, 3 AS expected
    UNION ALL SELECT 'auth.identities', 3
    UNION ALL SELECT 'public.profiles', 3
    UNION ALL SELECT 'public.farms', 2
    UNION ALL SELECT 'public.breeds', 10
    UNION ALL SELECT 'public.goats', 3
    UNION ALL SELECT 'public.goat_images', 7
    UNION ALL SELECT 'public.bookings', 4
    UNION ALL SELECT 'public.reviews', 0
    UNION ALL SELECT 'public.wishlist', 1
    UNION ALL SELECT 'public.notifications', 17
    UNION ALL SELECT 'public.reports', 0
    UNION ALL SELECT 'public.audit_logs', 1
    UNION ALL SELECT 'storage.objects', 18
)
SELECT 
    e.entity,
    e.expected AS target_expected_count,
    COALESCE(a.count, 0) AS actual_count,
    CASE 
        WHEN COALESCE(a.count, 0) = e.expected THEN '✅ MATCH'
        ELSE '⚠️ MISMATCH'
    END AS status
FROM expected_counts e
LEFT JOIN actual_counts a ON a.entity = e.entity
ORDER BY e.entity;

-- =============================================================================
-- 2. AUTH & PROFILES 1:1 UUID INTEGRITY AUDIT
-- =============================================================================
SELECT 
    u.id AS auth_user_id,
    u.email AS auth_email,
    p.id AS profile_id,
    p.email AS profile_email,
    p.role AS user_role,
    i.id AS identity_id,
    i.provider AS identity_provider,
    CASE 
        WHEN p.id IS NOT NULL AND i.id IS NOT NULL THEN '✅ AUTH + PROFILE + IDENTITY SYNCED'
        ELSE '❌ INTEGRITY ERROR'
    END AS sync_status
FROM auth.users u
LEFT JOIN public.profiles p ON p.id = u.id
LEFT JOIN auth.identities i ON i.user_id = u.id;

-- =============================================================================
-- 3. ROW LEVEL SECURITY (RLS) STATUS AUDIT
-- =============================================================================
SELECT 
    schemaname,
    tablename,
    rowsecurity AS rls_enabled,
    CASE WHEN rowsecurity THEN '✅ RLS ACTIVE' ELSE '❌ INSECURE' END AS status
FROM pg_tables
WHERE schemaname = 'public'
  AND tablename IN (
    'profiles', 'farms', 'breeds', 'goats', 'goat_images', 'bookings',
    'listing_payments', 'reviews', 'wishlist', 'notifications', 'reports', 'audit_logs'
  )
ORDER BY tablename;

-- Check storage.objects RLS
SELECT 
    schemaname,
    tablename,
    rowsecurity AS rls_enabled,
    CASE WHEN rowsecurity THEN '✅ STORAGE RLS ACTIVE' ELSE '❌ INSECURE' END AS status
FROM pg_tables
WHERE schemaname = 'storage' AND tablename = 'objects';

-- =============================================================================
-- 4. RELATIONAL INTEGRITY CHECKS (ZERO ORPHANS AUDIT)
-- =============================================================================
SELECT 'Orphaned Profiles (auth.users missing)' AS check_name, COUNT(*) AS orphan_count
FROM public.profiles p LEFT JOIN auth.users u ON u.id = p.id WHERE u.id IS NULL
UNION ALL
SELECT 'Orphaned Farms (owner_id missing)', COUNT(*)
FROM public.farms f LEFT JOIN public.profiles p ON p.id = f.owner_id WHERE p.id IS NULL
UNION ALL
SELECT 'Orphaned Goats (farm_id missing)', COUNT(*)
FROM public.goats g LEFT JOIN public.farms f ON f.id = g.farm_id WHERE f.id IS NULL
UNION ALL
SELECT 'Orphaned Goat Images (goat_id missing)', COUNT(*)
FROM public.goat_images gi LEFT JOIN public.goats g ON g.id = gi.goat_id WHERE g.id IS NULL
UNION ALL
SELECT 'Orphaned Bookings (goat_id missing)', COUNT(*)
FROM public.bookings b LEFT JOIN public.goats g ON g.id = b.goat_id WHERE g.id IS NULL
UNION ALL
SELECT 'Orphaned Bookings (customer_id missing)', COUNT(*)
FROM public.bookings b LEFT JOIN public.profiles p ON p.id = b.customer_id WHERE p.id IS NULL
UNION ALL
SELECT 'Orphaned Bookings (farm_id missing)', COUNT(*)
FROM public.bookings b LEFT JOIN public.farms f ON f.id = b.farm_id WHERE f.id IS NULL
UNION ALL
SELECT 'Orphaned Wishlist (user_id missing)', COUNT(*)
FROM public.wishlist w LEFT JOIN public.profiles p ON p.id = w.user_id WHERE p.id IS NULL
UNION ALL
SELECT 'Orphaned Wishlist (goat_id missing)', COUNT(*)
FROM public.wishlist w LEFT JOIN public.goats g ON g.id = w.goat_id WHERE g.id IS NULL
UNION ALL
SELECT 'Orphaned Notifications (user_id missing)', COUNT(*)
FROM public.notifications n LEFT JOIN public.profiles p ON p.id = n.user_id WHERE p.id IS NULL;

-- =============================================================================
-- 5. STORAGE BUCKETS & OBJECTS VERIFICATION
-- =============================================================================
SELECT 
    id AS bucket_name,
    public,
    file_size_limit / 1048576 AS max_mb,
    allowed_mime_types,
    CASE 
        WHEN id = 'goat-images' AND public = true THEN '✅ PUBLIC ACCESS OK'
        WHEN id IN ('farm-docs', 'vet-certificates') AND public = false THEN '✅ PRIVATE SECURITY OK'
        ELSE '⚠️ CHECK BUCKET CONFIG'
    END AS status
FROM storage.buckets
WHERE id IN ('goat-images', 'farm-docs', 'vet-certificates')
ORDER BY id;

-- Check distribution of objects across storage buckets
SELECT 
    bucket_id,
    COUNT(*) AS total_objects,
    SUM((metadata->>'size')::bigint) / 1024 AS total_kb
FROM storage.objects
GROUP BY bucket_id;

-- =============================================================================
-- 6. SUPER ADMIN VERIFICATION & HARDENED FUNCTIONS AUDIT
-- =============================================================================
SELECT 
    p.id,
    p.full_name,
    p.email,
    p.role,
    p.farm_id,
    CASE WHEN p.role = 'SUPER_ADMIN' THEN '✅ SUPER ADMIN CONFIGURED' ELSE '⚠️ ROLE NOT SET' END AS status
FROM public.profiles p
WHERE p.email = 'madesh1205@gmail.com';
