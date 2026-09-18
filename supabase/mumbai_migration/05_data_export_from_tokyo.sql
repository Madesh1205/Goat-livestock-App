-- =============================================================================
-- AMMAL FARM PLATFORM - TOKYO EXPORT SCRIPT (READ-ONLY AUDIT & EXTRACTION)
-- File: 05_data_export_from_tokyo.sql
-- Run this in Tokyo Supabase SQL Editor (ap-northeast-1) [100% READ ONLY]
-- =============================================================================

-- =============================================================================
-- 1. VERIFY CURRENT RECORD COUNTS IN TOKYO
-- =============================================================================
SELECT 'auth.users' AS table_name, COUNT(*) AS count FROM auth.users
UNION ALL SELECT 'auth.identities', COUNT(*) FROM auth.identities
UNION ALL SELECT 'public.profiles', COUNT(*) FROM public.profiles
UNION ALL SELECT 'public.farms', COUNT(*) FROM public.farms
UNION ALL SELECT 'public.breeds', COUNT(*) FROM public.breeds
UNION ALL SELECT 'public.goats', COUNT(*) FROM public.goats
UNION ALL SELECT 'public.goat_images', COUNT(*) FROM public.goat_images
UNION ALL SELECT 'public.bookings', COUNT(*) FROM public.bookings
UNION ALL SELECT 'public.listing_payments', COUNT(*) FROM public.listing_payments
UNION ALL SELECT 'public.reviews', COUNT(*) FROM public.reviews
UNION ALL SELECT 'public.wishlist', COUNT(*) FROM public.wishlist
UNION ALL SELECT 'public.notifications', COUNT(*) FROM public.notifications
UNION ALL SELECT 'public.reports', COUNT(*) FROM public.reports
UNION ALL SELECT 'public.audit_logs', COUNT(*) FROM public.audit_logs
UNION ALL SELECT 'storage.objects', COUNT(*) FROM storage.objects;

-- =============================================================================
-- 2. EXPORT PUBLIC DATA IN RELATIONAL HIERARCHY (READ ONLY)
-- =============================================================================

-- Profiles
SELECT json_agg(p) AS profiles_export
FROM (
    SELECT * FROM public.profiles ORDER BY created_at ASC
) p;

-- Farms
SELECT json_agg(f) AS farms_export
FROM (
    SELECT * FROM public.farms ORDER BY created_at ASC
) f;

-- Breeds
SELECT json_agg(b) AS breeds_export
FROM (
    SELECT * FROM public.breeds ORDER BY name ASC
) b;

-- Goats
SELECT json_agg(g) AS goats_export
FROM (
    SELECT * FROM public.goats ORDER BY created_at ASC
) g;

-- Goat Images
SELECT json_agg(gi) AS goat_images_export
FROM (
    SELECT * FROM public.goat_images ORDER BY display_order ASC, created_at ASC
) gi;

-- Bookings
SELECT json_agg(bk) AS bookings_export
FROM (
    SELECT * FROM public.bookings ORDER BY created_at ASC
) bk;

-- Listing Payments
SELECT json_agg(lp) AS listing_payments_export
FROM (
    SELECT * FROM public.listing_payments ORDER BY created_at ASC
) lp;

-- Reviews
SELECT json_agg(r) AS reviews_export
FROM (
    SELECT * FROM public.reviews ORDER BY created_at ASC
) r;

-- Wishlist
SELECT json_agg(w) AS wishlist_export
FROM (
    SELECT * FROM public.wishlist ORDER BY created_at ASC
) w;

-- Notifications
SELECT json_agg(n) AS notifications_export
FROM (
    SELECT * FROM public.notifications ORDER BY created_at ASC
) n;

-- Reports
SELECT json_agg(rp) AS reports_export
FROM (
    SELECT * FROM public.reports ORDER BY created_at ASC
) rp;

-- Audit Logs
SELECT json_agg(al) AS audit_logs_export
FROM (
    SELECT * FROM public.audit_logs ORDER BY created_at ASC
) al;

-- Storage Objects Metadata
SELECT json_agg(so) AS storage_objects_export
FROM (
    SELECT id, bucket_id, name, owner, created_at, updated_at, last_accessed_at, metadata
    FROM storage.objects
    ORDER BY created_at ASC
) so;
