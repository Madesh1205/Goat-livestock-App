-- =============================================================================
-- AMMAL FARM PLATFORM - COMPLETE DATABASE SCHEMA & RLS POLICIES SETUP
-- File: /supabase/migrations/20260830000000_complete_rls_policies.sql
-- Run this in your Supabase SQL Editor (SQL Editor -> New Query -> Run)
-- =============================================================================

-- 1. ENSURE ALL TABLES EXIST (Creates any missing tables safely)
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY,
    email TEXT UNIQUE,
    full_name TEXT,
    phone TEXT,
    role TEXT NOT NULL DEFAULT 'CUSTOMER',
    avatar_url TEXT,
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.farms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    owner_id UUID NOT NULL,
    location_district TEXT NOT NULL DEFAULT 'Vellore',
    contact_phone TEXT,
    contact_email TEXT,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'APPROVED',
    logo_url TEXT,
    banner_url TEXT,
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.breeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    origin TEXT,
    primary_purpose TEXT DEFAULT 'BREEDING',
    description TEXT,
    avg_weight_kg NUMERIC(5, 2),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.goats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID NOT NULL,
    tag_number TEXT NOT NULL,
    name TEXT NOT NULL,
    breed_id UUID,
    breed_name TEXT NOT NULL,
    gender TEXT NOT NULL,
    age_months INT NOT NULL DEFAULT 12,
    weight_kg NUMERIC(6, 2) NOT NULL DEFAULT 35.0,
    purpose TEXT NOT NULL DEFAULT 'BREEDING',
    price NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    status TEXT NOT NULL DEFAULT 'AVAILABLE',
    description TEXT,
    vaccination_status TEXT DEFAULT 'Fully Vaccinated',
    dewormed_date DATE,
    parentage_father_tag TEXT,
    parentage_mother_tag TEXT,
    is_approved_by_admin BOOLEAN NOT NULL DEFAULT TRUE,
    is_featured BOOLEAN NOT NULL DEFAULT FALSE,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 5.00,
    review_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.goat_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL,
    image_url TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL,
    farm_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    booking_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    hold_expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '48 hours'),
    total_price NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    deposit_paid NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    customer_notes TEXT,
    admin_notes TEXT,
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.listing_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID,
    goat_id UUID,
    booking_id UUID,
    payer_id UUID NOT NULL,
    amount NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    currency TEXT NOT NULL DEFAULT 'INR',
    payment_type TEXT NOT NULL DEFAULT 'LISTING_FEE',
    payment_status TEXT NOT NULL DEFAULT 'COMPLETED',
    payment_gateway_ref TEXT,
    receipt_number TEXT UNIQUE,
    payment_date TIMESTAMPTZ DEFAULT NOW(),
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID,
    goat_id UUID,
    farm_id UUID,
    customer_id UUID NOT NULL,
    rating INT NOT NULL DEFAULT 5,
    comment TEXT NOT NULL,
    is_verified_purchase BOOLEAN NOT NULL DEFAULT TRUE,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.wishlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    goat_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    link_type TEXT,
    link_id TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS public.reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    reason TEXT NOT NULL,
    description TEXT,
    status TEXT NOT NULL DEFAULT 'PENDING',
    resolution_notes TEXT,
    resolved_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Ensure all optional columns exist
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN DEFAULT FALSE;
ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;
ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS logo_url TEXT;
ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS banner_url TEXT;
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS vaccination_status TEXT DEFAULT 'Fully Vaccinated';
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS dewormed_date DATE;
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS parentage_father_tag TEXT;
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS parentage_mother_tag TEXT;
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS is_featured BOOLEAN DEFAULT FALSE;
ALTER TABLE public.reviews ADD COLUMN IF NOT EXISTS is_approved BOOLEAN DEFAULT TRUE;

-- 2. Helper functions (SECURITY DEFINER to avoid recursive policy checks)
CREATE OR REPLACE FUNCTION public.get_auth_role()
RETURNS TEXT AS $$
    SELECT role::text FROM public.profiles WHERE id::text = auth.uid()::text;
$$ LANGUAGE sql STABLE SECURITY DEFINER;

CREATE OR REPLACE FUNCTION public.is_super_admin()
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id::text = auth.uid()::text AND role::text = 'SUPER_ADMIN'
    );
$$ LANGUAGE sql STABLE SECURITY DEFINER;

-- 3. ENABLE ROW LEVEL SECURITY (RLS) ON ALL TABLES
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.farms ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.breeds ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.goats ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.goat_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.bookings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.listing_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.wishlist ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- 4. PROFILES POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "profiles_select_all" ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_own" ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_own" ON public.profiles;

CREATE POLICY "profiles_select_all" ON public.profiles
    FOR SELECT TO public
    USING (true);

CREATE POLICY "profiles_insert_own" ON public.profiles
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

CREATE POLICY "profiles_update_own" ON public.profiles
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

-- =============================================================================
-- 5. FARMS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "farms_select_all" ON public.farms;
DROP POLICY IF EXISTS "farms_insert_authenticated" ON public.farms;
DROP POLICY IF EXISTS "farms_update_owner" ON public.farms;
DROP POLICY IF EXISTS "farms_delete_owner" ON public.farms;

CREATE POLICY "farms_select_all" ON public.farms
    FOR SELECT TO public
    USING (true);

CREATE POLICY "farms_insert_authenticated" ON public.farms
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

CREATE POLICY "farms_update_owner" ON public.farms
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

CREATE POLICY "farms_delete_owner" ON public.farms
    FOR DELETE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin());

-- =============================================================================
-- 6. BREEDS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "breeds_select_all" ON public.breeds;
DROP POLICY IF EXISTS "breeds_modify_admin" ON public.breeds;

CREATE POLICY "breeds_select_all" ON public.breeds
    FOR SELECT TO public
    USING (true);

CREATE POLICY "breeds_modify_admin" ON public.breeds
    FOR ALL TO authenticated
    USING (true)
    WITH CHECK (true);

-- =============================================================================
-- 7. GOATS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "goats_select_all" ON public.goats;
DROP POLICY IF EXISTS "goats_insert_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_update_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_delete_authenticated" ON public.goats;

CREATE POLICY "goats_select_all" ON public.goats
    FOR SELECT TO public
    USING (true);

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

-- =============================================================================
-- 8. GOAT IMAGES POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "goat_images_select_all" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_modify_authenticated" ON public.goat_images;

CREATE POLICY "goat_images_select_all" ON public.goat_images
    FOR SELECT TO public
    USING (true);

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

-- =============================================================================
-- 9. BOOKINGS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "bookings_select_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_insert_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_update_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_delete_policy" ON public.bookings;

CREATE POLICY "bookings_select_policy" ON public.bookings
    FOR SELECT TO authenticated
    USING (
        customer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

CREATE POLICY "bookings_insert_policy" ON public.bookings
    FOR INSERT TO authenticated
    WITH CHECK (customer_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "bookings_update_policy" ON public.bookings
    FOR UPDATE TO authenticated
    USING (
        customer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    )
    WITH CHECK (
        customer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

CREATE POLICY "bookings_delete_policy" ON public.bookings
    FOR DELETE TO authenticated
    USING (
        customer_id::text = auth.uid()::text
        OR public.is_super_admin()
    );

-- =============================================================================
-- 10. LISTING PAYMENTS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "payments_select_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "payments_insert_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "payments_update_policy" ON public.listing_payments;

CREATE POLICY "payments_select_policy" ON public.listing_payments
    FOR SELECT TO authenticated
    USING (
        payer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = listing_payments.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

CREATE POLICY "payments_insert_policy" ON public.listing_payments
    FOR INSERT TO authenticated
    WITH CHECK (payer_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "payments_update_policy" ON public.listing_payments
    FOR UPDATE TO authenticated
    USING (payer_id::text = auth.uid()::text OR public.is_super_admin())
    WITH CHECK (payer_id::text = auth.uid()::text OR public.is_super_admin());

-- =============================================================================
-- 11. REVIEWS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "reviews_select_all" ON public.reviews;
DROP POLICY IF EXISTS "reviews_insert_authenticated" ON public.reviews;
DROP POLICY IF EXISTS "reviews_update_authenticated" ON public.reviews;
DROP POLICY IF EXISTS "reviews_delete_authenticated" ON public.reviews;

CREATE POLICY "reviews_select_all" ON public.reviews
    FOR SELECT TO public
    USING (true);

CREATE POLICY "reviews_insert_authenticated" ON public.reviews
    FOR INSERT TO authenticated
    WITH CHECK (customer_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reviews_update_authenticated" ON public.reviews
    FOR UPDATE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin())
    WITH CHECK (customer_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reviews_delete_authenticated" ON public.reviews
    FOR DELETE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin());

-- =============================================================================
-- 12. WISHLIST POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "wishlist_select_own" ON public.wishlist;
DROP POLICY IF EXISTS "wishlist_insert_own" ON public.wishlist;
DROP POLICY IF EXISTS "wishlist_delete_own" ON public.wishlist;

CREATE POLICY "wishlist_select_own" ON public.wishlist
    FOR SELECT TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "wishlist_insert_own" ON public.wishlist
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "wishlist_delete_own" ON public.wishlist
    FOR DELETE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

-- =============================================================================
-- 13. NOTIFICATIONS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "notifications_select_own" ON public.notifications;
DROP POLICY IF EXISTS "notifications_insert_authenticated" ON public.notifications;
DROP POLICY IF EXISTS "notifications_update_own" ON public.notifications;
DROP POLICY IF EXISTS "notifications_delete_own" ON public.notifications;

CREATE POLICY "notifications_select_own" ON public.notifications
    FOR SELECT TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "notifications_insert_authenticated" ON public.notifications
    FOR INSERT TO authenticated
    WITH CHECK (true);

CREATE POLICY "notifications_update_own" ON public.notifications
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "notifications_delete_own" ON public.notifications
    FOR DELETE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

-- =============================================================================
-- 14. REPORTS POLICIES
-- =============================================================================
DROP POLICY IF EXISTS "reports_select_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_insert_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_update_policy" ON public.reports;

CREATE POLICY "reports_select_policy" ON public.reports
    FOR SELECT TO authenticated
    USING (reporter_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reports_insert_policy" ON public.reports
    FOR INSERT TO authenticated
    WITH CHECK (reporter_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reports_update_policy" ON public.reports
    FOR UPDATE TO authenticated
    USING (public.is_super_admin());
