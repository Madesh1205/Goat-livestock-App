-- =============================================================================
-- AMMAL FARM PLATFORM - ADMIN SETUP, SUPER ADMIN ROLES & SEED DATA
-- File: /supabase/migrations/20260830010000_admin_setup_and_seed_data.sql
-- Run this in your Supabase SQL Editor (SQL Editor -> New Query -> Run)
-- =============================================================================

-- =============================================================================
-- 1. MAKE USER SUPER ADMIN & FARM ADMIN
-- (Replace madesh1205@gmail.com with your email if different)
-- =============================================================================
UPDATE public.profiles
SET role = 'SUPER_ADMIN'::user_role
WHERE email = 'madesh1205@gmail.com';

-- Also ensure profile exists for any existing auth user in auth.users
INSERT INTO public.profiles (id, full_name, email, phone, role)
SELECT 
    au.id,
    COALESCE(au.raw_user_meta_data->>'full_name', split_part(au.email, '@', 1)),
    au.email,
    au.phone,
    (CASE WHEN au.email = 'madesh1205@gmail.com' THEN 'SUPER_ADMIN' ELSE 'CUSTOMER' END)::user_role
FROM auth.users au
WHERE NOT EXISTS (SELECT 1 FROM public.profiles p WHERE p.id = au.id)
ON CONFLICT (id) DO UPDATE
SET role = EXCLUDED.role, email = EXCLUDED.email;

-- =============================================================================
-- 2. SEED STANDARD GOAT BREEDS (If not already present)
-- =============================================================================
INSERT INTO public.breeds (id, name, origin, primary_purpose, description, avg_weight_kg, is_active)
VALUES
    (gen_random_uuid(), 'Jamunapari', 'Uttar Pradesh, India', 'BREEDING'::goat_purpose, 'Tall, majestic dairy & breeding goat with long pendulous ears and convex Roman nose.', 65.0, TRUE),
    (gen_random_uuid(), 'Boer', 'South Africa', 'MEAT'::goat_purpose, 'Premier meat goat breed known for fast growth rates, muscular build, and high fertility.', 85.0, TRUE),
    (gen_random_uuid(), 'Sirohi', 'Rajasthan, India', 'BREEDING'::goat_purpose, 'Hardy dual-purpose breed, compact build with brown patches, excellent for intensive farming.', 50.0, TRUE),
    (gen_random_uuid(), 'Barbari', 'Delhi / Uttar Pradesh, India', 'BREEDING'::goat_purpose, 'Small, alert, triple-purpose goat with erect ears, prolific breeder suitable for stall feeding.', 38.0, TRUE),
    (gen_random_uuid(), 'Tellicherry (Malabari)', 'Kerala, India', 'BREEDING'::goat_purpose, 'High twinning rate, excellent milk yield, calm temperament, and adaptable to humid climates.', 42.0, TRUE),
    (gen_random_uuid(), 'Beetal', 'Punjab, India', 'BREEDING'::goat_purpose, 'Large dairy breed resembling Jamunapari with Roman nose and high lactation capacity.', 60.0, TRUE),
    (gen_random_uuid(), 'Black Bengal', 'West Bengal, India', 'MEAT'::goat_purpose, 'World-renowned for premium chevon quality, prolific kidding, and fine leather hide.', 28.0, TRUE),
    (gen_random_uuid(), 'Kanni Aadu', 'Tamil Nadu, India', 'MEAT'::goat_purpose, 'Native drought-hardy meat breed from Southern Tamil Nadu with distinct black-and-tan coat.', 36.0, TRUE),
    (gen_random_uuid(), 'Salem Black', 'Tamil Nadu, India', 'MEAT'::goat_purpose, 'Tall, completely jet-black native breed from Salem, well adapted to dry tropical weather.', 40.0, TRUE),
    (gen_random_uuid(), 'Kodi Aadu', 'Tamil Nadu, India', 'MEAT'::goat_purpose, 'Long-legged, agile native breed known for endurance in scrub jungles and rocky terrain.', 35.0, TRUE)
ON CONFLICT (name) DO NOTHING;

-- =============================================================================
-- 3. SEED MAIN CENTRAL FARM IN VELLORE
-- =============================================================================
DO $$
DECLARE
    admin_uid UUID;
    main_farm_id UUID := '00000000-0000-0000-0000-000000000001'::UUID;
BEGIN
    SELECT id INTO admin_uid FROM auth.users WHERE email = 'madesh1205@gmail.com' LIMIT 1;
    IF admin_uid IS NULL THEN
        SELECT id INTO admin_uid FROM auth.users LIMIT 1;
    END IF;

    IF admin_uid IS NOT NULL THEN
        INSERT INTO public.farms (id, name, owner_id, location_district, contact_phone, contact_email, description, status)
        VALUES (
            main_farm_id,
            'Ammal Farm Central Hub',
            admin_uid,
            'Vellore',
            '+91 98765 43210',
            'madesh1205@gmail.com',
            'Main stud farm and breeding excellence center in Vellore, Tamil Nadu.',
            'APPROVED'
        )
        ON CONFLICT (id) DO UPDATE 
        SET owner_id = EXCLUDED.owner_id,
            location_district = 'Vellore',
            description = 'Main stud farm and breeding excellence center in Vellore, Tamil Nadu.',
            contact_email = EXCLUDED.contact_email,
            status = 'APPROVED';

        -- Update any existing farm records for Ammal Farm / admin to Vellore
        UPDATE public.farms
        SET location_district = 'Vellore'
        WHERE name ILIKE '%Ammal%' OR owner_id = admin_uid;
    END IF;
END $$;

-- =============================================================================
-- 4. AUTO-CREATE PROFILE TRIGGER ON NEW AUTH SIGNUP
-- =============================================================================
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, full_name, email, phone, role)
    VALUES (
        NEW.id,
        COALESCE(NEW.raw_user_meta_data->>'full_name', split_part(NEW.email, '@', 1)),
        NEW.email,
        NEW.phone,
        (CASE WHEN NEW.email = 'madesh1205@gmail.com' THEN 'SUPER_ADMIN' ELSE 'CUSTOMER' END)::user_role
    )
    ON CONFLICT (id) DO UPDATE
    SET email = EXCLUDED.email;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();

-- =============================================================================
-- 5. STORAGE BUCKET CONFIGURATION FOR GOAT IMAGES
-- =============================================================================
INSERT INTO storage.buckets (id, name, public)
VALUES ('goat-images', 'goat-images', TRUE)
ON CONFLICT (id) DO UPDATE SET public = TRUE;

-- Storage Policies for 'goat-images'
DROP POLICY IF EXISTS "Public View Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Upload Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Update Goat Images" ON storage.objects;
DROP POLICY IF EXISTS "Authenticated Delete Goat Images" ON storage.objects;

CREATE POLICY "Public View Goat Images" ON storage.objects
    FOR SELECT TO public
    USING (bucket_id = 'goat-images');

CREATE POLICY "Authenticated Upload Goat Images" ON storage.objects
    FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'goat-images');

CREATE POLICY "Authenticated Update Goat Images" ON storage.objects
    FOR UPDATE TO authenticated
    USING (bucket_id = 'goat-images');

CREATE POLICY "Authenticated Delete Goat Images" ON storage.objects
    FOR DELETE TO authenticated
    USING (bucket_id = 'goat-images');
