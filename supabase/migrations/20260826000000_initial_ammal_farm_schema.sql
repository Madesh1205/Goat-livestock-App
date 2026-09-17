-- =============================================================================
-- AMMAL FARM PLATFORM - SUPABASE POSTGRESQL SCHEMA & MIGRATION
-- Production-Ready Database Schema with Strict Constraints & Robust RLS Policies
-- =============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- 1. CUSTOM ENUMS & TYPES
-- =============================================================================

CREATE TYPE user_role AS ENUM (
    'SUPER_ADMIN',
    'FARM_ADMIN',
    'CUSTOMER'
);

CREATE TYPE farm_status AS ENUM (
    'PENDING',
    'APPROVED',
    'REJECTED',
    'SUSPENDED'
);

CREATE TYPE goat_gender AS ENUM (
    'MALE',
    'FEMALE',
    'CASTRATED'
);

CREATE TYPE goat_purpose AS ENUM (
    'BREEDING',
    'MEAT',
    'MILK',
    'SHOW',
    'PET'
);

CREATE TYPE goat_status AS ENUM (
    'AVAILABLE',
    'RESERVED',
    'BOOKING_PENDING',
    'CONFIRMED',
    'SOLD',
    'COMPLETED',
    'INACTIVE'
);

CREATE TYPE booking_status AS ENUM (
    'PENDING',
    'RESERVED',
    'CONFIRMED',
    'CANCELLED',
    'EXPIRED',
    'COMPLETED'
);

CREATE TYPE payment_type AS ENUM (
    'LISTING_FEE',
    'BOOKING_DEPOSIT',
    'FULL_PAYMENT',
    'SUBSCRIPTION'
);

CREATE TYPE payment_status AS ENUM (
    'INITIATED',
    'COMPLETED',
    'FAILED',
    'REFUNDED'
);

CREATE TYPE report_target_type AS ENUM (
    'GOAT',
    'FARM',
    'REVIEW',
    'USER'
);

CREATE TYPE report_status AS ENUM (
    'PENDING',
    'INVESTIGATING',
    'RESOLVED',
    'DISMISSED'
);

-- =============================================================================
-- 2. HELPER FUNCTIONS & TRIGGERS (FOR TIMESTAMPS)
-- =============================================================================

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- 3. CORE TABLES
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 3.1 PROFILES (Linked to Supabase auth.users)
-- -----------------------------------------------------------------------------
CREATE TABLE profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    role user_role NOT NULL DEFAULT 'CUSTOMER',
    full_name TEXT NOT NULL,
    phone TEXT,
    email TEXT,
    avatar_url TEXT,
    farm_id UUID, -- Foreign key added below after farms table creation
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.2 FARMS
-- -----------------------------------------------------------------------------
CREATE TABLE farms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    tagline TEXT,
    description TEXT,
    location_district TEXT NOT NULL,
    location_state TEXT NOT NULL DEFAULT 'Tamil Nadu',
    address TEXT,
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    contact_phone TEXT NOT NULL,
    contact_email TEXT,
    status farm_status NOT NULL DEFAULT 'PENDING',
    is_ammal_own_farm BOOLEAN NOT NULL DEFAULT FALSE,
    verified_at TIMESTAMPTZ,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 5.00 CHECK (rating >= 0 AND rating <= 5),
    review_count INT NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    logo_url TEXT,
    banner_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Add farm_id foreign key constraint back to profiles
ALTER TABLE profiles
    ADD CONSTRAINT fk_profiles_farm
    FOREIGN KEY (farm_id) REFERENCES farms(id) ON DELETE SET NULL;

-- -----------------------------------------------------------------------------
-- 3.3 BREEDS
-- -----------------------------------------------------------------------------
CREATE TABLE breeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    origin TEXT,
    primary_purpose goat_purpose DEFAULT 'BREEDING',
    description TEXT,
    avg_weight_kg NUMERIC(5, 2),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.4 GOATS
-- -----------------------------------------------------------------------------
CREATE TABLE goats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    tag_number TEXT NOT NULL,
    name TEXT NOT NULL,
    breed_id UUID REFERENCES breeds(id) ON DELETE SET NULL,
    breed_name TEXT NOT NULL,
    gender goat_gender NOT NULL,
    age_months INT NOT NULL CHECK (age_months >= 0),
    weight_kg NUMERIC(6, 2) NOT NULL CHECK (weight_kg > 0),
    purpose goat_purpose NOT NULL,
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    status goat_status NOT NULL DEFAULT 'AVAILABLE',
    description TEXT,
    vaccination_status TEXT DEFAULT 'Fully Vaccinated',
    dewormed_date DATE,
    parentage_father_tag TEXT,
    parentage_mother_tag TEXT,
    is_approved_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    is_featured BOOLEAN NOT NULL DEFAULT FALSE,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 5.00 CHECK (rating >= 0 AND rating <= 5),
    review_count INT NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_farm_goat_tag UNIQUE (farm_id, tag_number)
);

-- -----------------------------------------------------------------------------
-- 3.5 GOAT IMAGES
-- -----------------------------------------------------------------------------
CREATE TABLE goat_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL REFERENCES goats(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.6 BOOKINGS
-- -----------------------------------------------------------------------------
CREATE TABLE bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL REFERENCES goats(id) ON DELETE RESTRICT,
    farm_id UUID NOT NULL REFERENCES farms(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    status booking_status NOT NULL DEFAULT 'PENDING',
    booking_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    hold_expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '48 hours'),
    total_price NUMERIC(12, 2) NOT NULL CHECK (total_price >= 0),
    deposit_paid NUMERIC(12, 2) NOT NULL DEFAULT 0.00 CHECK (deposit_paid >= 0),
    customer_notes TEXT,
    admin_notes TEXT,
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- STRICT CONSTRAINT: Only ONE active reservation / booking per goat at any given time
CREATE UNIQUE INDEX idx_single_active_goat_booking 
    ON bookings (goat_id) 
    WHERE status IN ('PENDING', 'RESERVED', 'CONFIRMED');

-- -----------------------------------------------------------------------------
-- 3.7 LISTING PAYMENTS
-- -----------------------------------------------------------------------------
CREATE TABLE listing_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID REFERENCES farms(id) ON DELETE SET NULL,
    goat_id UUID REFERENCES goats(id) ON DELETE SET NULL,
    booking_id UUID REFERENCES bookings(id) ON DELETE SET NULL,
    payer_id UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency TEXT NOT NULL DEFAULT 'INR',
    payment_type payment_type NOT NULL,
    payment_status payment_status NOT NULL DEFAULT 'INITIATED',
    payment_gateway_ref TEXT,
    receipt_number TEXT UNIQUE,
    payment_date TIMESTAMPTZ,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.8 REVIEWS
-- -----------------------------------------------------------------------------
CREATE TABLE reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    goat_id UUID NOT NULL REFERENCES goats(id) ON DELETE CASCADE,
    farm_id UUID NOT NULL REFERENCES farms(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT NOT NULL,
    is_verified_purchase BOOLEAN NOT NULL DEFAULT TRUE,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.9 NOTIFICATIONS
-- -----------------------------------------------------------------------------
CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    link_type TEXT,
    link_id TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.10 REPORTS
-- -----------------------------------------------------------------------------
CREATE TABLE reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES profiles(id) ON DELETE RESTRICT,
    target_type report_target_type NOT NULL,
    target_id UUID NOT NULL,
    reason TEXT NOT NULL,
    description TEXT,
    status report_status NOT NULL DEFAULT 'PENDING',
    resolution_notes TEXT,
    resolved_by UUID REFERENCES profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- 4. PERFORMANCE & LOOKUP INDEXES
-- =============================================================================

CREATE INDEX idx_profiles_role ON profiles(role);
CREATE INDEX idx_profiles_farm_id ON profiles(farm_id);

CREATE INDEX idx_farms_owner_id ON farms(owner_id);
CREATE INDEX idx_farms_status ON farms(status);
CREATE INDEX idx_farms_district ON farms(location_district);

CREATE INDEX idx_goats_farm_id ON goats(farm_id);
CREATE INDEX idx_goats_status ON goats(status);
CREATE INDEX idx_goats_breed_id ON goats(breed_id);
CREATE INDEX idx_goats_approved_status ON goats(is_approved_by_admin, status);
CREATE INDEX idx_goats_featured ON goats(is_featured);

CREATE INDEX idx_goat_images_goat_id ON goat_images(goat_id);

CREATE INDEX idx_bookings_customer_id ON bookings(customer_id);
CREATE INDEX idx_bookings_farm_id ON bookings(farm_id);
CREATE INDEX idx_bookings_goat_id ON bookings(goat_id);
CREATE INDEX idx_bookings_status ON bookings(status);
CREATE INDEX idx_bookings_hold_expires ON bookings(hold_expires_at) WHERE status = 'PENDING';

CREATE INDEX idx_listing_payments_payer ON listing_payments(payer_id);
CREATE INDEX idx_listing_payments_farm ON listing_payments(farm_id);
CREATE INDEX idx_listing_payments_status ON listing_payments(payment_status);

CREATE INDEX idx_reviews_farm_id ON reviews(farm_id);
CREATE INDEX idx_reviews_goat_id ON reviews(goat_id);
CREATE INDEX idx_reviews_customer ON reviews(customer_id);

CREATE INDEX idx_notifications_user_unread ON notifications(user_id, is_read);
CREATE INDEX idx_reports_status ON reports(status);

-- =============================================================================
-- 5. AUTOMATIC TIMESTAMP UPDATE TRIGGERS
-- =============================================================================

CREATE TRIGGER tr_profiles_updated_at BEFORE UPDATE ON profiles FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_farms_updated_at BEFORE UPDATE ON farms FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_breeds_updated_at BEFORE UPDATE ON breeds FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_goats_updated_at BEFORE UPDATE ON goats FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_bookings_updated_at BEFORE UPDATE ON bookings FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_payments_updated_at BEFORE UPDATE ON listing_payments FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_reviews_updated_at BEFORE UPDATE ON reviews FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_reports_updated_at BEFORE UPDATE ON reports FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =============================================================================
-- 6. AUTH SYNC & BUSINESS LOGIC TRIGGERS
-- =============================================================================

-- Auto-create profile when a new user signs up in Supabase Auth
CREATE OR REPLACE FUNCTION handle_new_auth_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, full_name, email, phone, role)
    VALUES (
        NEW.id,
        COALESCE(NEW.raw_user_meta_data->>'full_name', 'Ammal User'),
        NEW.email,
        NEW.phone,
        'CUSTOMER'
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION handle_new_auth_user();

-- Trigger: When a booking becomes completed, update goat status to COMPLETED / SOLD
CREATE OR REPLACE FUNCTION handle_booking_status_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        UPDATE public.goats SET status = 'COMPLETED' WHERE id = NEW.goat_id;
    ELSIF NEW.status = 'CONFIRMED' THEN
        UPDATE public.goats SET status = 'CONFIRMED' WHERE id = NEW.goat_id;
    ELSIF NEW.status = 'RESERVED' OR NEW.status = 'PENDING' THEN
        UPDATE public.goats SET status = 'RESERVED' WHERE id = NEW.goat_id;
    ELSIF (NEW.status = 'CANCELLED' OR NEW.status = 'EXPIRED') AND OLD.status IN ('PENDING', 'RESERVED', 'CONFIRMED') THEN
        UPDATE public.goats SET status = 'AVAILABLE' WHERE id = NEW.goat_id;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER tr_booking_status_change
    AFTER UPDATE OF status ON bookings
    FOR EACH ROW EXECUTE FUNCTION handle_booking_status_change();

-- =============================================================================
-- 7. SECURITY HELPER FUNCTIONS FOR ROW LEVEL SECURITY
-- =============================================================================

-- Fast role lookup from JWT / profiles
CREATE OR REPLACE FUNCTION get_auth_role()
RETURNS user_role AS $$
    SELECT role FROM public.profiles WHERE id = auth.uid();
$$ LANGUAGE sql STABLE SECURITY DEFINER;

-- Fast farm ID lookup for Farm Admin
CREATE OR REPLACE FUNCTION get_auth_farm_id()
RETURNS UUID AS $$
    SELECT farm_id FROM public.profiles WHERE id = auth.uid();
$$ LANGUAGE sql STABLE SECURITY DEFINER;

-- Is current user Super Admin?
CREATE OR REPLACE FUNCTION is_super_admin()
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id = auth.uid() AND role = 'SUPER_ADMIN'
    );
$$ LANGUAGE sql STABLE SECURITY DEFINER;

-- =============================================================================
-- 8. ROW LEVEL SECURITY (RLS) POLICIES
-- =============================================================================

-- Enable RLS on all tables
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE farms ENABLE ROW LEVEL SECURITY;
ALTER TABLE breeds ENABLE ROW LEVEL SECURITY;
ALTER TABLE goats ENABLE ROW LEVEL SECURITY;
ALTER TABLE goat_images ENABLE ROW LEVEL SECURITY;
ALTER TABLE bookings ENABLE ROW LEVEL SECURITY;
ALTER TABLE listing_payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE reports ENABLE ROW LEVEL SECURITY;

-- -----------------------------------------------------------------------------
-- 8.1 PROFILES POLICIES
-- -----------------------------------------------------------------------------
-- View: Super Admin sees all; users see own profile; Farm Admins see profiles of customers who booked at their farm
CREATE POLICY "profiles_select_policy" ON profiles
    FOR SELECT USING (
        auth.uid() = id
        OR is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN' AND EXISTS (
                SELECT 1 FROM bookings b
                WHERE b.customer_id = profiles.id AND b.farm_id = get_auth_farm_id()
            )
        )
    );

-- Update: Users can update their own personal info (cannot change their own role or farm_id unless Super Admin)
CREATE POLICY "profiles_update_policy" ON profiles
    FOR UPDATE USING (
        auth.uid() = id OR is_super_admin()
    ) WITH CHECK (
        is_super_admin() OR (
            auth.uid() = id
            AND role = (SELECT p.role FROM profiles p WHERE p.id = auth.uid())
            AND (farm_id IS NOT DISTINCT FROM (SELECT p.farm_id FROM profiles p WHERE p.id = auth.uid()))
        )
    );

-- -----------------------------------------------------------------------------
-- 8.2 FARMS POLICIES
-- -----------------------------------------------------------------------------
-- View: Public/Customers can see APPROVED farms. Farm Admin can see their own farm. Super Admin sees all.
CREATE POLICY "farms_select_policy" ON farms
    FOR SELECT USING (
        status = 'APPROVED'
        OR owner_id = auth.uid()
        OR id = get_auth_farm_id()
        OR is_super_admin()
    );

-- Insert: Authenticated users can register a farm (defaults to 'PENDING'). Super Admin can create any farm.
CREATE POLICY "farms_insert_policy" ON farms
    FOR INSERT WITH CHECK (
        (auth.uid() = owner_id AND status = 'PENDING')
        OR is_super_admin()
    );

-- Update: Farm Admin/Owner can update their farm profile (except status). Super Admin can update all (including status/approvals).
CREATE POLICY "farms_update_policy" ON farms
    FOR UPDATE USING (
        owner_id = auth.uid()
        OR id = get_auth_farm_id()
        OR is_super_admin()
    ) WITH CHECK (
        is_super_admin() OR (
            (owner_id = auth.uid() OR id = get_auth_farm_id())
            AND status = (SELECT f.status FROM farms f WHERE f.id = farms.id)
            AND is_ammal_own_farm = (SELECT f.is_ammal_own_farm FROM farms f WHERE f.id = farms.id)
        )
    );

-- Delete: Super Admin only
CREATE POLICY "farms_delete_policy" ON farms
    FOR DELETE USING (is_super_admin());

-- -----------------------------------------------------------------------------
-- 8.3 BREEDS POLICIES
-- -----------------------------------------------------------------------------
-- View: Public can view active breeds
CREATE POLICY "breeds_select_policy" ON breeds
    FOR SELECT USING (is_active = TRUE OR is_super_admin());

-- Manage: Super Admin only
CREATE POLICY "breeds_admin_policy" ON breeds
    FOR ALL USING (is_super_admin());

-- -----------------------------------------------------------------------------
-- 8.4 GOATS POLICIES
-- -----------------------------------------------------------------------------
-- View: Public can only view APPROVED goats from APPROVED farms that are active.
-- Farm Admins can view all goats from their farm. Super Admin can view everything.
CREATE POLICY "goats_select_policy" ON goats
    FOR SELECT USING (
        is_super_admin()
        OR farm_id = get_auth_farm_id()
        OR (
            is_approved_by_admin = TRUE
            AND status NOT IN ('INACTIVE')
            AND EXISTS (
                SELECT 1 FROM farms f
                WHERE f.id = goats.farm_id AND f.status = 'APPROVED'
            )
        )
    );

-- Insert: Farm Admin can add goats for their assigned farm. Super Admin can add for any farm.
CREATE POLICY "goats_insert_policy" ON goats
    FOR INSERT WITH CHECK (
        is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN'
            AND farm_id = get_auth_farm_id()
            AND is_approved_by_admin = FALSE -- external farm admins cannot self-approve
        )
    );

-- Update: Farm Admin can update their farm's goats. Super Admin can update any.
CREATE POLICY "goats_update_policy" ON goats
    FOR UPDATE USING (
        is_super_admin() OR (get_auth_role() = 'FARM_ADMIN' AND farm_id = get_auth_farm_id())
    ) WITH CHECK (
        is_super_admin() OR (
            get_auth_role() = 'FARM_ADMIN'
            AND farm_id = get_auth_farm_id()
            -- external farm admins cannot self-approve without super admin review
            AND is_approved_by_admin = (SELECT g.is_approved_by_admin FROM goats g WHERE g.id = goats.id)
        )
    );

-- Delete: Farm Admin can delete unbooked goats from their farm. Super Admin can delete all.
CREATE POLICY "goats_delete_policy" ON goats
    FOR DELETE USING (
        is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN'
            AND farm_id = get_auth_farm_id()
            AND status IN ('AVAILABLE', 'INACTIVE')
        )
    );

-- -----------------------------------------------------------------------------
-- 8.5 GOAT IMAGES POLICIES
-- -----------------------------------------------------------------------------
-- View: Inherit goat visibility
CREATE POLICY "goat_images_select_policy" ON goat_images
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM goats g
            WHERE g.id = goat_images.goat_id
        )
    );

-- Insert/Update/Delete: Farm Admin owning the goat or Super Admin
CREATE POLICY "goat_images_modify_policy" ON goat_images
    FOR ALL USING (
        is_super_admin()
        OR EXISTS (
            SELECT 1 FROM goats g
            WHERE g.id = goat_images.goat_id AND g.farm_id = get_auth_farm_id()
        )
    );

-- -----------------------------------------------------------------------------
-- 8.6 BOOKINGS POLICIES
-- -----------------------------------------------------------------------------
-- View: Customers see their own bookings; Farm Admins see their farm bookings; Super Admin sees all.
CREATE POLICY "bookings_select_policy" ON bookings
    FOR SELECT USING (
        customer_id = auth.uid()
        OR farm_id = get_auth_farm_id()
        OR is_super_admin()
    );

-- Insert: Customers can create booking for AVAILABLE approved goats on approved farms.
CREATE POLICY "bookings_insert_policy" ON bookings
    FOR INSERT WITH CHECK (
        customer_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM goats g
            JOIN farms f ON f.id = g.farm_id
            WHERE g.id = bookings.goat_id
              AND g.farm_id = bookings.farm_id
              AND g.status = 'AVAILABLE'
              AND g.is_approved_by_admin = TRUE
              AND f.status = 'APPROVED'
        )
    );

-- Update: Customers can cancel their own pending booking. Farm Admin can confirm/complete bookings for their farm. Super Admin can update anything.
CREATE POLICY "bookings_update_policy" ON bookings
    FOR UPDATE USING (
        customer_id = auth.uid()
        OR farm_id = get_auth_farm_id()
        OR is_super_admin()
    ) WITH CHECK (
        is_super_admin()
        OR (
            -- Customer can only change status to CANCELLED if currently PENDING/RESERVED
            customer_id = auth.uid()
            AND status = 'CANCELLED'
        )
        OR (
            -- Farm Admin can update booking statuses (CONFIRMED, COMPLETED, CANCELLED) for their farm
            farm_id = get_auth_farm_id()
        )
    );

-- -----------------------------------------------------------------------------
-- 8.7 LISTING PAYMENTS POLICIES
-- -----------------------------------------------------------------------------
CREATE POLICY "payments_select_policy" ON listing_payments
    FOR SELECT USING (
        payer_id = auth.uid()
        OR farm_id = get_auth_farm_id()
        OR is_super_admin()
    );

CREATE POLICY "payments_insert_policy" ON listing_payments
    FOR INSERT WITH CHECK (
        payer_id = auth.uid() OR is_super_admin()
    );

CREATE POLICY "payments_admin_update_policy" ON listing_payments
    FOR UPDATE USING (is_super_admin());

-- -----------------------------------------------------------------------------
-- 8.8 REVIEWS POLICIES
-- -----------------------------------------------------------------------------
-- View: Public can view approved reviews. Author, Farm Admin, and Super Admin can see all.
CREATE POLICY "reviews_select_policy" ON reviews
    FOR SELECT USING (
        is_approved = TRUE
        OR customer_id = auth.uid()
        OR farm_id = get_auth_farm_id()
        OR is_super_admin()
    );

-- Insert: Customer can only review completed bookings they made
CREATE POLICY "reviews_insert_policy" ON reviews
    FOR INSERT WITH CHECK (
        customer_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM bookings b
            WHERE b.id = reviews.booking_id
              AND b.customer_id = auth.uid()
              AND b.goat_id = reviews.goat_id
              AND b.farm_id = reviews.farm_id
              AND b.status = 'COMPLETED'
        )
    );

-- Update: Customer can edit their own review; Super Admin can moderate
CREATE POLICY "reviews_update_policy" ON reviews
    FOR UPDATE USING (
        customer_id = auth.uid() OR is_super_admin()
    ) WITH CHECK (
        is_super_admin() OR (
            customer_id = auth.uid()
            AND is_approved = (SELECT r.is_approved FROM reviews r WHERE r.id = reviews.id)
        )
    );

-- Delete: Review author or Super Admin
CREATE POLICY "reviews_delete_policy" ON reviews
    FOR DELETE USING (customer_id = auth.uid() OR is_super_admin());

-- -----------------------------------------------------------------------------
-- 8.9 NOTIFICATIONS POLICIES
-- -----------------------------------------------------------------------------
CREATE POLICY "notifications_user_policy" ON notifications
    FOR ALL USING (user_id = auth.uid() OR is_super_admin());

-- -----------------------------------------------------------------------------
-- 8.10 REPORTS POLICIES
-- -----------------------------------------------------------------------------
-- View: Reporter can view own submitted reports. Super Admin views all.
CREATE POLICY "reports_select_policy" ON reports
    FOR SELECT USING (reporter_id = auth.uid() OR is_super_admin());

-- Insert: Any authenticated user can submit a report
CREATE POLICY "reports_insert_policy" ON reports
    FOR INSERT WITH CHECK (reporter_id = auth.uid());

-- Update/Manage: Super Admin only
CREATE POLICY "reports_admin_policy" ON reports
    FOR UPDATE USING (is_super_admin());
