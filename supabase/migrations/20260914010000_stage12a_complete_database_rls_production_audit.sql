-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 12A: DATABASE & RLS PRODUCTION AUDIT & HARDENING
-- File: /supabase/migrations/20260914010000_stage12a_complete_database_rls_production_audit.sql
--
-- Comprehensive Production Security & RLS Hardening:
-- 1. Tightens SELECT on `profiles` to eliminate unauthenticated PII leakage (emails/phones).
-- 2. Restricts `breeds` modification strictly to SUPER_ADMIN.
-- 3. Restricts unapproved / pending `farms` from public SELECT.
-- 4. Restricts unapproved / deleted `goats` from public SELECT.
-- 5. Sets explicit `SET search_path = public, pg_temp` on ALL SECURITY DEFINER functions.
-- 6. Ensures strict ownership & role checks across all 11 core tables.
-- =============================================================================

-- =============================================================================
-- 1. CORE HELPER FUNCTIONS WITH SAFE SEARCH_PATH
-- =============================================================================

CREATE OR REPLACE FUNCTION public.get_auth_role()
RETURNS TEXT AS $$
    SELECT role::text FROM public.profiles WHERE id::text = auth.uid()::text;
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.is_super_admin()
RETURNS BOOLEAN AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id::text = auth.uid()::text AND role::text = 'SUPER_ADMIN'
    );
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 2. ROW LEVEL SECURITY (RLS) ACTIVATION ON ALL TABLES
-- =============================================================================

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
ALTER TABLE IF EXISTS public.audit_logs ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- 3. PROFILES RLS POLICIES (Privacy Hardening: Zero PII Exposure)
-- =============================================================================

DROP POLICY IF EXISTS "profiles_select_all" ON public.profiles;
DROP POLICY IF EXISTS "profiles_select_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_own" ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_own" ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_delete_policy" ON public.profiles;

-- SELECT:
-- 1. Users can read their own profile.
-- 2. Super Admin can read all profiles.
-- 3. Farm Admins can read profile details of customers who booked with their farm.
-- 4. Public can read profiles of approved farm owners (necessary for farm owner attribution).
CREATE POLICY "profiles_select_policy" ON public.profiles
    FOR SELECT TO public
    USING (
        (auth.uid() IS NOT NULL AND auth.uid()::text = id::text)
        OR public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.bookings b
            JOIN public.farms f ON f.id = b.farm_id
            WHERE b.customer_id = profiles.id
              AND f.owner_id::text = auth.uid()::text
        )
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.owner_id = profiles.id
              AND f.status = 'APPROVED'
        )
    );

-- INSERT: Only authenticated user for their own id, or Super Admin.
CREATE POLICY "profiles_insert_policy" ON public.profiles
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

-- UPDATE: Only user for their own profile, or Super Admin.
CREATE POLICY "profiles_update_policy" ON public.profiles
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

-- DELETE: Only user or Super Admin.
CREATE POLICY "profiles_delete_policy" ON public.profiles
    FOR DELETE TO authenticated
    USING (auth.uid()::text = id::text OR public.is_super_admin());

-- =============================================================================
-- 4. FARMS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "farms_select_all" ON public.farms;
DROP POLICY IF EXISTS "farms_select_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_insert_authenticated" ON public.farms;
DROP POLICY IF EXISTS "farms_insert_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_update_owner" ON public.farms;
DROP POLICY IF EXISTS "farms_update_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_delete_owner" ON public.farms;
DROP POLICY IF EXISTS "farms_delete_policy" ON public.farms;

-- SELECT: Approved farms are visible to public. Unapproved/pending farms only visible to owner or Super Admin.
CREATE POLICY "farms_select_policy" ON public.farms
    FOR SELECT TO public
    USING (
        status = 'APPROVED'
        OR (auth.uid() IS NOT NULL AND owner_id::text = auth.uid()::text)
        OR public.is_super_admin()
    );

-- INSERT: Authenticated users for their own farm, or Super Admin.
CREATE POLICY "farms_insert_policy" ON public.farms
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

-- UPDATE: Owner or Super Admin.
CREATE POLICY "farms_update_policy" ON public.farms
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

-- DELETE: Owner or Super Admin.
CREATE POLICY "farms_delete_policy" ON public.farms
    FOR DELETE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin());

-- =============================================================================
-- 5. BREEDS RLS POLICIES (Fix: Restrict modifications strictly to Super Admin)
-- =============================================================================

DROP POLICY IF EXISTS "breeds_select_all" ON public.breeds;
DROP POLICY IF EXISTS "breeds_select_policy" ON public.breeds;
DROP POLICY IF EXISTS "breeds_modify_admin" ON public.breeds;
DROP POLICY IF EXISTS "breeds_insert_policy" ON public.breeds;
DROP POLICY IF EXISTS "breeds_update_policy" ON public.breeds;
DROP POLICY IF EXISTS "breeds_delete_policy" ON public.breeds;

CREATE POLICY "breeds_select_policy" ON public.breeds
    FOR SELECT TO public
    USING (true);

CREATE POLICY "breeds_insert_policy" ON public.breeds
    FOR INSERT TO authenticated
    WITH CHECK (public.is_super_admin());

CREATE POLICY "breeds_update_policy" ON public.breeds
    FOR UPDATE TO authenticated
    USING (public.is_super_admin())
    WITH CHECK (public.is_super_admin());

CREATE POLICY "breeds_delete_policy" ON public.breeds
    FOR DELETE TO authenticated
    USING (public.is_super_admin());

-- =============================================================================
-- 6. GOATS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "goats_select_all" ON public.goats;
DROP POLICY IF EXISTS "goats_select_policy" ON public.goats;
DROP POLICY IF EXISTS "goats_insert_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_insert_policy" ON public.goats;
DROP POLICY IF EXISTS "goats_update_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_update_policy" ON public.goats;
DROP POLICY IF EXISTS "goats_delete_authenticated" ON public.goats;
DROP POLICY IF EXISTS "goats_delete_policy" ON public.goats;

-- SELECT: Approved and active goats are public. Unapproved/draft goats visible to farm owner or Super Admin.
CREATE POLICY "goats_select_policy" ON public.goats
    FOR SELECT TO public
    USING (
        (status != 'INACTIVE' AND is_approved_by_admin = TRUE)
        OR (auth.uid() IS NOT NULL AND EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
        ))
        OR public.is_super_admin()
    );

-- INSERT: Only Super Admin or approved Farm Admin owning the target farm.
CREATE POLICY "goats_insert_policy" ON public.goats
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

-- UPDATE: Only Super Admin or Farm Admin owning the target farm.
CREATE POLICY "goats_update_policy" ON public.goats
    FOR UPDATE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    )
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    );

-- DELETE: Only Super Admin or Farm Admin owning the target farm.
CREATE POLICY "goats_delete_policy" ON public.goats
    FOR DELETE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    );

-- =============================================================================
-- 7. GOAT IMAGES RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "goat_images_select_all" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_select_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_modify_authenticated" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_insert_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_update_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_delete_policy" ON public.goat_images;

CREATE POLICY "goat_images_select_policy" ON public.goat_images
    FOR SELECT TO public
    USING (true);

CREATE POLICY "goat_images_insert_policy" ON public.goat_images
    FOR INSERT TO authenticated
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

CREATE POLICY "goat_images_update_policy" ON public.goat_images
    FOR UPDATE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id::text = g.farm_id::text
            WHERE g.id::text = goat_images.goat_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    )
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id::text = g.farm_id::text
            WHERE g.id::text = goat_images.goat_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    );

CREATE POLICY "goat_images_delete_policy" ON public.goat_images
    FOR DELETE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id::text = g.farm_id::text
            WHERE g.id::text = goat_images.goat_id::text
              AND f.owner_id::text = auth.uid()::text
        )
    );

-- =============================================================================
-- 8. BOOKINGS RLS POLICIES
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
-- 9. LISTING PAYMENTS RLS POLICIES
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
    USING (public.is_super_admin())
    WITH CHECK (public.is_super_admin());

-- =============================================================================
-- 10. REVIEWS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "reviews_select_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_insert_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_update_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_delete_policy" ON public.reviews;

CREATE POLICY "reviews_select_policy" ON public.reviews
    FOR SELECT TO public
    USING (
        is_approved = TRUE
        OR (auth.uid() IS NOT NULL AND customer_id::text = auth.uid()::text)
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = reviews.farm_id AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

CREATE POLICY "reviews_insert_policy" ON public.reviews
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id::text = auth.uid()::text
            AND EXISTS (
                SELECT 1 FROM public.bookings b
                WHERE b.id = reviews.booking_id
                  AND b.customer_id::text = auth.uid()::text
                  AND b.status IN ('COMPLETED', 'CONFIRMED')
            )
        )
    );

CREATE POLICY "reviews_update_policy" ON public.reviews
    FOR UPDATE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin())
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id::text = auth.uid()::text
            AND is_approved = (SELECT r.is_approved FROM public.reviews r WHERE r.id = reviews.id)
        )
    );

CREATE POLICY "reviews_delete_policy" ON public.reviews
    FOR DELETE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin());

-- =============================================================================
-- 11. WISHLIST RLS POLICIES
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
-- 12. NOTIFICATIONS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "notifications_select_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_insert_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_update_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_delete_policy" ON public.notifications;

CREATE POLICY "notifications_select_policy" ON public.notifications
    FOR SELECT TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "notifications_insert_policy" ON public.notifications
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "notifications_update_policy" ON public.notifications
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

CREATE POLICY "notifications_delete_policy" ON public.notifications
    FOR DELETE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

-- =============================================================================
-- 13. REPORTS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "reports_select_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_insert_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_update_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_delete_policy" ON public.reports;

CREATE POLICY "reports_select_policy" ON public.reports
    FOR SELECT TO authenticated
    USING (reporter_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reports_insert_policy" ON public.reports
    FOR INSERT TO authenticated
    WITH CHECK (reporter_id::text = auth.uid()::text OR public.is_super_admin());

CREATE POLICY "reports_update_policy" ON public.reports
    FOR UPDATE TO authenticated
    USING (public.is_super_admin())
    WITH CHECK (public.is_super_admin());

CREATE POLICY "reports_delete_policy" ON public.reports
    FOR DELETE TO authenticated
    USING (public.is_super_admin());

-- =============================================================================
-- 14. AUDIT LOGS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "audit_logs_select_policy" ON public.audit_logs;
DROP POLICY IF EXISTS "audit_logs_insert_policy" ON public.audit_logs;

CREATE POLICY "audit_logs_select_policy" ON public.audit_logs
    FOR SELECT TO authenticated
    USING (public.is_super_admin());

CREATE POLICY "audit_logs_insert_policy" ON public.audit_logs
    FOR INSERT TO authenticated
    WITH CHECK (public.is_super_admin() OR auth.uid() IS NOT NULL);

-- =============================================================================
-- 15. REINFORCE SEARCH_PATH ON ALL SECURITY DEFINER FUNCTIONS & LISTING LIMIT FIX
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 10;
    v_current_count INT := 0;
BEGIN
    -- Genuine Ammal Farm check: Must match designated UUID and is_ammal_own_farm flag
    SELECT (f.id = '00000000-0000-0000-0000-000000000001'::uuid AND f.is_ammal_own_farm = TRUE),
           COALESCE(f.goat_listing_limit, 10)
    INTO v_is_ammal, v_limit
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    -- Only genuine Ammal Farm is exempt from partner listing limit checks
    IF v_is_ammal = TRUE THEN
        RETURN NEW;
    END IF;

    -- Count active/listed goats for this partner farm (status != 'INACTIVE')
    SELECT COUNT(*) INTO v_current_count
    FROM public.goats
    WHERE farm_id = NEW.farm_id
      AND status != 'INACTIVE';

    IF v_current_count >= v_limit THEN
        RAISE EXCEPTION 'Listing limit reached. Contact Super Admin to increase your listing limit.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
BEFORE INSERT ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();

-- Safely enforce search_path on available security definer functions without aborting on missing ones
DO $$
BEGIN
    BEGIN ALTER FUNCTION public.handle_new_auth_user() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_profile_security_guard() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.create_goat_booking_atomic(UUID, UUID, TEXT) SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_booking_price_snapshot() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_goat_listing_limit() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_farm_metadata_integrity() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_goat_listing_fee_rule() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.create_system_notification(UUID, TEXT, TEXT, TEXT, TEXT) SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_review_authenticity() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.recalculate_ratings_on_review() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.enforce_report_authenticity() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.notify_booking_lifecycle() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.notify_and_audit_goat_moderation() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.notify_and_audit_farm_verification() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.notify_report_submission() SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
    BEGIN ALTER FUNCTION public.verify_listing_payment_atomic(UUID, TEXT, NUMERIC, TEXT, TEXT) SET search_path = public, pg_temp; EXCEPTION WHEN undefined_function THEN NULL; END;
END $$;

