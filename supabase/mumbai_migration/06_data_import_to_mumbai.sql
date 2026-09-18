-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI DATA IMPORT TEMPLATE
-- File: 06_data_import_to_mumbai.sql
-- Run this in Mumbai Supabase SQL Editor (ap-south-1) for public tables
-- =============================================================================
-- CRITICAL PRE-REQUISITE:
-- Auth users and identities must be loaded first via pg_dump / sync_tokyo_to_mumbai.sh
-- so that foreign keys to auth.users(id) resolve cleanly.
--
-- EXECUTION SAFETY:
-- 1. All statements run inside an explicit transaction block.
-- 2. SET LOCAL session_replication_role = 'replica' ensures triggers/event hooks
--    do not fire during migration.
-- 3. Because it uses SET LOCAL, replication role is strictly guaranteed to revert
--    to 'origin' when the transaction completes.
-- 4. Inserts records in strict Foreign Key dependency order:
--    profiles -> farms -> breeds -> goats -> goat_images -> bookings -> listing_payments
--    -> reviews -> wishlist -> notifications -> reports -> audit_logs.
-- =============================================================================

BEGIN;

-- Temporarily bypass triggers only for this local transaction
SET LOCAL session_replication_role = 'replica';

-- =============================================================================
-- 1. PROFILES (3 Records - Aligned to auth.users UUIDs)
-- =============================================================================
/*
INSERT INTO public.profiles (
    id, role, full_name, phone, email, avatar_url, farm_id, is_suspended, created_at, updated_at
) VALUES
-- ('<UUID>', 'SUPER_ADMIN', 'Madesh', '+919876543210', 'madesh1205@gmail.com', null, '<FARM_UUID>', false, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    role = EXCLUDED.role,
    full_name = EXCLUDED.full_name,
    phone = EXCLUDED.phone,
    email = EXCLUDED.email,
    farm_id = EXCLUDED.farm_id,
    is_suspended = EXCLUDED.is_suspended;
*/

-- =============================================================================
-- 2. FARMS (2 Records)
-- =============================================================================
/*
INSERT INTO public.farms (
    id, name, owner_id, tagline, description, location_district, location_state,
    address, latitude, longitude, contact_phone, contact_email, status,
    is_ammal_own_farm, goat_listing_limit, verified_at, rating, review_count, logo_url, banner_url, created_at, updated_at
) VALUES
-- ('00000000-0000-0000-0000-000000000001', 'Ammal Farm Central Hub', '<OWNER_UUID>', 'Premier Stud & Breeding Farm', 'Main stud farm in Vellore', 'Vellore', 'Tamil Nadu', 'Vellore, Tamil Nadu', 12.9165, 79.1325, '+919876543210', 'madesh1205@gmail.com', 'APPROVED', true, 100, NOW(), 5.00, 0, null, null, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    owner_id = EXCLUDED.owner_id,
    status = EXCLUDED.status,
    is_ammal_own_farm = EXCLUDED.is_ammal_own_farm,
    location_district = EXCLUDED.location_district;
*/

-- =============================================================================
-- 3. BREEDS (10 Standard Breeds)
-- =============================================================================
INSERT INTO public.breeds (id, name, origin, primary_purpose, description, avg_weight_kg, is_active)
VALUES
    (gen_random_uuid(), 'Jamunapari', 'Uttar Pradesh, India', 'BREEDING', 'Tall, majestic dairy & breeding goat with long pendulous ears and convex Roman nose.', 65.0, TRUE),
    (gen_random_uuid(), 'Boer', 'South Africa', 'MEAT', 'Premier meat goat breed known for fast growth rates, muscular build, and high fertility.', 85.0, TRUE),
    (gen_random_uuid(), 'Sirohi', 'Rajasthan, India', 'BREEDING', 'Hardy dual-purpose breed, compact build with brown patches, excellent for intensive farming.', 50.0, TRUE),
    (gen_random_uuid(), 'Barbari', 'Delhi / Uttar Pradesh, India', 'BREEDING', 'Small, alert, triple-purpose goat with erect ears, prolific breeder suitable for stall feeding.', 38.0, TRUE),
    (gen_random_uuid(), 'Tellicherry (Malabari)', 'Kerala, India', 'BREEDING', 'High twinning rate, excellent milk yield, calm temperament, and adaptable to humid climates.', 42.0, TRUE),
    (gen_random_uuid(), 'Beetal', 'Punjab, India', 'BREEDING', 'Large dairy breed resembling Jamunapari with Roman nose and high lactation capacity.', 60.0, TRUE),
    (gen_random_uuid(), 'Black Bengal', 'West Bengal, India', 'MEAT', 'World-renowned for premium chevon quality, prolific kidding, and fine leather hide.', 28.0, TRUE),
    (gen_random_uuid(), 'Kanni Aadu', 'Tamil Nadu, India', 'MEAT', 'Native drought-hardy meat breed from Southern Tamil Nadu with distinct black-and-tan coat.', 36.0, TRUE),
    (gen_random_uuid(), 'Salem Black', 'Tamil Nadu, India', 'MEAT', 'Tall, completely jet-black native breed from Salem, well adapted to dry tropical weather.', 40.0, TRUE),
    (gen_random_uuid(), 'Kodi Aadu', 'Tamil Nadu, India', 'MEAT', 'Long-legged, agile native breed known for endurance in scrub jungles and rocky terrain.', 35.0, TRUE)
ON CONFLICT (name) DO UPDATE SET
    origin = EXCLUDED.origin,
    primary_purpose = EXCLUDED.primary_purpose,
    description = EXCLUDED.description,
    avg_weight_kg = EXCLUDED.avg_weight_kg,
    is_active = EXCLUDED.is_active;

-- =============================================================================
-- 4. GOATS (3 Records)
-- =============================================================================
/*
INSERT INTO public.goats (
    id, farm_id, tag_number, name, breed_id, breed_name, gender, age_months,
    weight_kg, purpose, price, discount_percentage, status, description,
    vaccination_status, dewormed_date, parentage_father_tag, parentage_mother_tag,
    is_approved_by_admin, is_featured, rating, review_count, created_at, updated_at
) VALUES
-- ('<GOAT_UUID>', '<FARM_UUID>', 'AMM-001', 'Raja Jamunapari', '<BREED_UUID>', 'Jamunapari', 'MALE', 24, 72.50, 'BREEDING', 45000.00, 0.00, 'AVAILABLE', 'Champion breeding buck', 'Fully Vaccinated', '2026-08-01', null, null, true, true, 5.00, 0, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    is_approved_by_admin = EXCLUDED.is_approved_by_admin,
    price = EXCLUDED.price;
*/

-- =============================================================================
-- 5. GOAT IMAGES (7 Records)
-- =============================================================================
/*
INSERT INTO public.goat_images (
    id, goat_id, image_url, display_order, is_primary, created_at
) VALUES
-- ('<IMG_UUID>', '<GOAT_UUID>', 'https://...', 0, true, NOW())
ON CONFLICT (id) DO NOTHING;
*/

-- =============================================================================
-- 6. BOOKINGS (4 Records)
-- =============================================================================
/*
INSERT INTO public.bookings (
    id, booking_code, goat_id, farm_id, customer_id, status, booking_date,
    hold_expires_at, total_price, deposit_paid, customer_notes, admin_notes,
    confirmed_at, completed_at, cancelled_at, created_at, updated_at
) VALUES
-- ('<BOOKING_UUID>', 'AMM-789012', '<GOAT_UUID>', '<FARM_UUID>', '<CUSTOMER_UUID>', 'CONFIRMED', NOW() - INTERVAL '3 days', NOW() + INTERVAL '45 hours', 45000.00, 0.00, null, null, NOW() - INTERVAL '2 days', null, null, NOW() - INTERVAL '3 days', NOW())
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    total_price = EXCLUDED.total_price;
*/

-- =============================================================================
-- 7. LISTING PAYMENTS
-- =============================================================================
/*
INSERT INTO public.listing_payments (
    id, farm_id, goat_id, booking_id, payer_id, amount, currency, payment_type,
    payment_status, payment_gateway_ref, receipt_number, payment_date, metadata, created_at, updated_at
) VALUES
-- ('<PAYMENT_UUID>', '<FARM_UUID>', '<GOAT_UUID>', null, '<PAYER_UUID>', 100.00, 'INR', 'LISTING_FEE', 'COMPLETED', null, 'REC-001', NOW(), null, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;
*/

-- =============================================================================
-- 8. REVIEWS (0 Records)
-- =============================================================================
-- Currently 0 reviews in production.

-- =============================================================================
-- 9. WISHLIST (1 Record)
-- =============================================================================
/*
INSERT INTO public.wishlist (id, user_id, goat_id, created_at)
VALUES
-- ('<WISHLIST_UUID>', '<USER_UUID>', '<GOAT_UUID>', NOW())
ON CONFLICT (user_id, goat_id) DO NOTHING;
*/

-- =============================================================================
-- 10. NOTIFICATIONS (17 Records)
-- =============================================================================
/*
INSERT INTO public.notifications (
    id, user_id, title, body, link_type, link_id, event_key, is_read, created_at
) VALUES
-- ('<NOTIF_UUID>', '<USER_UUID>', 'Welcome to Ammal Farm', 'Your account is ready!', 'GENERAL', null, 'welcome_01', false, NOW())
ON CONFLICT (id) DO NOTHING;
*/

-- =============================================================================
-- 11. REPORTS (0 Records)
-- =============================================================================
-- Currently 0 reports in production.

-- =============================================================================
-- 12. AUDIT LOGS (1 Record)
-- =============================================================================
/*
INSERT INTO public.audit_logs (
    id, actor_id, action, target_type, target_id, previous_state, new_state, notes, created_at
) VALUES
-- ('<AUDIT_UUID>', '<ACTOR_UUID>', 'FARM_STATUS_CHANGE', 'FARM', '<FARM_UUID>', 'PENDING', 'APPROVED', 'Initial farm approval', NOW())
ON CONFLICT (id) DO NOTHING;
*/

COMMIT;
