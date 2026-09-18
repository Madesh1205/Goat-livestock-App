-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI MIGRATION (ap-south-1)
-- File: 01_schema_and_types.sql
-- Step 1: PostgreSQL Extensions, Custom Types, Core Tables, Constraints & Indexes
-- =============================================================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- =============================================================================
-- 1. POSTGRESQL ENUMS / TYPES
-- =============================================================================

DO $$ BEGIN
    CREATE TYPE public.user_role AS ENUM (
        'SUPER_ADMIN',
        'FARM_ADMIN',
        'CUSTOMER'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.farm_status AS ENUM (
        'PENDING',
        'APPROVED',
        'REJECTED',
        'SUSPENDED'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.goat_gender AS ENUM (
        'MALE',
        'FEMALE',
        'CASTRATED'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.goat_purpose AS ENUM (
        'BREEDING',
        'MEAT',
        'MILK',
        'SHOW',
        'PET'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.goat_status AS ENUM (
        'AVAILABLE',
        'RESERVED',
        'BOOKING_PENDING',
        'CONFIRMED',
        'SOLD',
        'COMPLETED',
        'INACTIVE'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.booking_status AS ENUM (
        'PENDING',
        'RESERVED',
        'CONFIRMED',
        'CANCELLED',
        'EXPIRED',
        'COMPLETED'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.payment_type AS ENUM (
        'LISTING_FEE',
        'BOOKING_DEPOSIT',
        'FULL_PAYMENT',
        'SUBSCRIPTION'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.payment_status AS ENUM (
        'INITIATED',
        'COMPLETED',
        'FAILED',
        'REFUNDED'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.report_target_type AS ENUM (
        'GOAT',
        'FARM',
        'REVIEW',
        'USER'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE public.report_status AS ENUM (
        'PENDING',
        'INVESTIGATING',
        'RESOLVED',
        'DISMISSED'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- =============================================================================
-- 2. TIMESTAMP TRIGGER FUNCTION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =============================================================================
-- 3. CORE PUBLIC TABLES
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 3.1 PROFILES (1-to-1 with auth.users)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    role public.user_role NOT NULL DEFAULT 'CUSTOMER',
    full_name TEXT NOT NULL,
    phone TEXT,
    email TEXT,
    avatar_url TEXT,
    farm_id UUID, -- Foreign key constraint attached below after farms table creation
    is_suspended BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.2 FARMS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.farms (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    owner_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    tagline TEXT,
    description TEXT,
    location_district TEXT NOT NULL,
    location_state TEXT NOT NULL DEFAULT 'Tamil Nadu',
    address TEXT,
    latitude NUMERIC(10, 7),
    longitude NUMERIC(10, 7),
    contact_phone TEXT NOT NULL,
    contact_email TEXT,
    status public.farm_status NOT NULL DEFAULT 'PENDING',
    is_ammal_own_farm BOOLEAN NOT NULL DEFAULT FALSE,
    goat_listing_limit INT NOT NULL DEFAULT 10,
    verified_at TIMESTAMPTZ,
    rating NUMERIC(3, 2) NOT NULL DEFAULT 5.00 CHECK (rating >= 0 AND rating <= 5),
    review_count INT NOT NULL DEFAULT 0 CHECK (review_count >= 0),
    logo_url TEXT,
    banner_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Bind farm_id constraint on profiles
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_profiles_farm'
    ) THEN
        ALTER TABLE public.profiles
            ADD CONSTRAINT fk_profiles_farm
            FOREIGN KEY (farm_id) REFERENCES public.farms(id) ON DELETE SET NULL;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 3.3 BREEDS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.breeds (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL UNIQUE,
    origin TEXT,
    primary_purpose public.goat_purpose DEFAULT 'BREEDING',
    description TEXT,
    avg_weight_kg NUMERIC(5, 2),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.4 GOATS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.goats (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID NOT NULL REFERENCES public.farms(id) ON DELETE CASCADE,
    tag_number TEXT NOT NULL,
    name TEXT NOT NULL,
    breed_id UUID REFERENCES public.breeds(id) ON DELETE SET NULL,
    breed_name TEXT NOT NULL,
    gender public.goat_gender NOT NULL,
    age_months INT NOT NULL CHECK (age_months >= 0),
    weight_kg NUMERIC(6, 2) NOT NULL CHECK (weight_kg > 0),
    purpose public.goat_purpose NOT NULL,
    price NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    discount_percentage NUMERIC(5, 2) NOT NULL DEFAULT 0.00 CHECK (discount_percentage >= 0 AND discount_percentage <= 100),
    status public.goat_status NOT NULL DEFAULT 'AVAILABLE',
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
CREATE TABLE IF NOT EXISTS public.goat_images (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID NOT NULL REFERENCES public.goats(id) ON DELETE CASCADE,
    image_url TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.6 BOOKINGS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.bookings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_code TEXT,
    goat_id UUID NOT NULL REFERENCES public.goats(id) ON DELETE RESTRICT,
    farm_id UUID NOT NULL REFERENCES public.farms(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    status public.booking_status NOT NULL DEFAULT 'PENDING',
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

-- Strict Double Booking Prevention: Only ONE active reservation / booking per goat
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_active_goat_booking 
    ON public.bookings (goat_id) 
    WHERE status IN ('PENDING', 'RESERVED', 'CONFIRMED');

-- -----------------------------------------------------------------------------
-- 3.7 LISTING PAYMENTS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.listing_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    farm_id UUID REFERENCES public.farms(id) ON DELETE SET NULL,
    goat_id UUID REFERENCES public.goats(id) ON DELETE SET NULL,
    booking_id UUID REFERENCES public.bookings(id) ON DELETE SET NULL,
    payer_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    amount NUMERIC(12, 2) NOT NULL CHECK (amount >= 0),
    currency TEXT NOT NULL DEFAULT 'INR',
    payment_type public.payment_type NOT NULL,
    payment_status public.payment_status NOT NULL DEFAULT 'INITIATED',
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
CREATE TABLE IF NOT EXISTS public.reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id UUID NOT NULL UNIQUE REFERENCES public.bookings(id) ON DELETE CASCADE,
    goat_id UUID NOT NULL REFERENCES public.goats(id) ON DELETE CASCADE,
    farm_id UUID NOT NULL REFERENCES public.farms(id) ON DELETE CASCADE,
    customer_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    rating INT NOT NULL CHECK (rating >= 1 AND rating <= 5),
    comment TEXT NOT NULL,
    is_verified_purchase BOOLEAN NOT NULL DEFAULT TRUE,
    is_approved BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 3.9 WISHLIST
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.wishlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    goat_id UUID NOT NULL REFERENCES public.goats(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_goat_wishlist UNIQUE (user_id, goat_id)
);

-- -----------------------------------------------------------------------------
-- 3.10 NOTIFICATIONS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    link_type TEXT,
    link_id TEXT,
    event_key TEXT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Notification deduplication index
CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_event_key
    ON public.notifications (user_id, event_key)
    WHERE event_key IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 3.11 REPORTS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE RESTRICT,
    target_type public.report_target_type NOT NULL,
    target_id UUID NOT NULL,
    reason TEXT NOT NULL,
    description TEXT,
    status public.report_status NOT NULL DEFAULT 'PENDING',
    resolution_notes TEXT,
    resolved_by UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_reports_user_target_pending
    ON public.reports (reporter_id, target_type, target_id)
    WHERE status = 'PENDING';

-- -----------------------------------------------------------------------------
-- 3.12 AUDIT LOGS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    previous_state TEXT,
    new_state TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =============================================================================
-- 4. PERFORMANCE & OPERATIONAL INDEXES
-- =============================================================================

CREATE INDEX IF NOT EXISTS idx_profiles_role ON public.profiles(role);
CREATE INDEX IF NOT EXISTS idx_profiles_farm_id ON public.profiles(farm_id);
CREATE INDEX IF NOT EXISTS idx_profiles_email ON public.profiles(email);

CREATE INDEX IF NOT EXISTS idx_farms_owner_id ON public.farms(owner_id);
CREATE INDEX IF NOT EXISTS idx_farms_status ON public.farms(status);
CREATE INDEX IF NOT EXISTS idx_farms_district ON public.farms(location_district);
CREATE INDEX IF NOT EXISTS idx_farms_is_ammal ON public.farms(is_ammal_own_farm);

CREATE INDEX IF NOT EXISTS idx_goats_farm_id ON public.goats(farm_id);
CREATE INDEX IF NOT EXISTS idx_goats_status ON public.goats(status);
CREATE INDEX IF NOT EXISTS idx_goats_breed_id ON public.goats(breed_id);
CREATE INDEX IF NOT EXISTS idx_goats_approved_status ON public.goats(is_approved_by_admin, status);
CREATE INDEX IF NOT EXISTS idx_goats_featured ON public.goats(is_featured);
CREATE INDEX IF NOT EXISTS idx_goats_price ON public.goats(price);

CREATE INDEX IF NOT EXISTS idx_goat_images_goat_id ON public.goat_images(goat_id);

CREATE INDEX IF NOT EXISTS idx_bookings_customer_id ON public.bookings(customer_id);
CREATE INDEX IF NOT EXISTS idx_bookings_farm_id ON public.bookings(farm_id);
CREATE INDEX IF NOT EXISTS idx_bookings_goat_id ON public.bookings(goat_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status ON public.bookings(status);
CREATE INDEX IF NOT EXISTS idx_bookings_hold_expires ON public.bookings(hold_expires_at) WHERE status IN ('PENDING', 'RESERVED');

CREATE INDEX IF NOT EXISTS idx_listing_payments_payer ON public.listing_payments(payer_id);
CREATE INDEX IF NOT EXISTS idx_listing_payments_farm ON public.listing_payments(farm_id);
CREATE INDEX IF NOT EXISTS idx_listing_payments_status ON public.listing_payments(payment_status);

CREATE INDEX IF NOT EXISTS idx_reviews_farm_id ON public.reviews(farm_id);
CREATE INDEX IF NOT EXISTS idx_reviews_goat_id ON public.reviews(goat_id);
CREATE INDEX IF NOT EXISTS idx_reviews_customer ON public.reviews(customer_id);
CREATE INDEX IF NOT EXISTS idx_reviews_approved ON public.reviews(is_approved);

CREATE INDEX IF NOT EXISTS idx_wishlist_user_id ON public.wishlist(user_id);
CREATE INDEX IF NOT EXISTS idx_wishlist_goat_id ON public.wishlist(goat_id);

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON public.notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread ON public.notifications(user_id, is_read);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON public.notifications(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_reports_status ON public.reports(status);
CREATE INDEX IF NOT EXISTS idx_reports_target ON public.reports(target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_id ON public.audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target ON public.audit_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON public.audit_logs(created_at DESC);

-- =============================================================================
-- 5. AUTOMATIC TIMESTAMP TRIGGERS
-- =============================================================================

DROP TRIGGER IF EXISTS tr_profiles_updated_at ON public.profiles;
CREATE TRIGGER tr_profiles_updated_at BEFORE UPDATE ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_farms_updated_at ON public.farms;
CREATE TRIGGER tr_farms_updated_at BEFORE UPDATE ON public.farms FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_breeds_updated_at ON public.breeds;
CREATE TRIGGER tr_breeds_updated_at BEFORE UPDATE ON public.breeds FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_goats_updated_at ON public.goats;
CREATE TRIGGER tr_goats_updated_at BEFORE UPDATE ON public.goats FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_bookings_updated_at ON public.bookings;
CREATE TRIGGER tr_bookings_updated_at BEFORE UPDATE ON public.bookings FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_payments_updated_at ON public.listing_payments;
CREATE TRIGGER tr_payments_updated_at BEFORE UPDATE ON public.listing_payments FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_reviews_updated_at ON public.reviews;
CREATE TRIGGER tr_reviews_updated_at BEFORE UPDATE ON public.reviews FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

DROP TRIGGER IF EXISTS tr_reports_updated_at ON public.reports;
CREATE TRIGGER tr_reports_updated_at BEFORE UPDATE ON public.reports FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
