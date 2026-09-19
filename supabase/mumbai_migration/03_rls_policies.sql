-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI MIGRATION (ap-south-1)
-- File: 03_rls_policies.sql
-- Step 3: Enable Row Level Security & Apply Hardened Stage 12A Policies
-- =============================================================================

-- =============================================================================
-- 1. ACTIVATE ROW LEVEL SECURITY (RLS) ON ALL 12 TABLES
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
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

-- =============================================================================
-- 2. PROFILES RLS POLICIES (Privacy Hardened: Zero Unauthenticated PII Leakage)
-- =============================================================================

DROP POLICY IF EXISTS "profiles_select_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_insert_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_update_policy" ON public.profiles;
DROP POLICY IF EXISTS "profiles_delete_policy" ON public.profiles;

-- SELECT:
-- 1. Users can read their own profile.
-- 2. Super Admin can read all profiles.
-- 3. Farm Admins can read profile details of customers who booked with their farm.
-- 4. Public can read profiles of approved farm owners (for farm owner attribution).
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

CREATE POLICY "profiles_insert_policy" ON public.profiles
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

CREATE POLICY "profiles_update_policy" ON public.profiles
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = id::text OR public.is_super_admin());

CREATE POLICY "profiles_delete_policy" ON public.profiles
    FOR DELETE TO authenticated
    USING (auth.uid()::text = id::text OR public.is_super_admin());

-- =============================================================================
-- 3. FARMS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "farms_select_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_insert_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_update_policy" ON public.farms;
DROP POLICY IF EXISTS "farms_delete_policy" ON public.farms;

-- SELECT: Approved farms are public; pending/unapproved farms visible only to owner or Super Admin.
CREATE POLICY "farms_select_policy" ON public.farms
    FOR SELECT TO public
    USING (
        status = 'APPROVED'
        OR (auth.uid() IS NOT NULL AND owner_id::text = auth.uid()::text)
        OR public.is_super_admin()
    );

CREATE POLICY "farms_insert_policy" ON public.farms
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

CREATE POLICY "farms_update_policy" ON public.farms
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = owner_id::text OR public.is_super_admin());

CREATE POLICY "farms_delete_policy" ON public.farms
    FOR DELETE TO authenticated
    USING (auth.uid()::text = owner_id::text OR public.is_super_admin());

-- =============================================================================
-- 4. BREEDS RLS POLICIES (Modification Strictly Restricted to Super Admin)
-- =============================================================================

DROP POLICY IF EXISTS "breeds_select_policy" ON public.breeds;
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
-- 5. GOATS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "goats_select_policy" ON public.goats;
DROP POLICY IF EXISTS "goats_insert_policy" ON public.goats;
DROP POLICY IF EXISTS "goats_update_policy" ON public.goats;
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
-- 6. GOAT IMAGES RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "goat_images_select_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_insert_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_update_policy" ON public.goat_images;
DROP POLICY IF EXISTS "goat_images_delete_policy" ON public.goat_images;

CREATE POLICY "goat_images_select_policy" ON public.goat_images
    FOR SELECT TO public
    USING (
        EXISTS (
            SELECT 1 FROM public.goats g
            WHERE g.id = goat_images.goat_id
              AND (
                  (g.status != 'INACTIVE' AND g.is_approved_by_admin = TRUE)
                  OR (auth.uid() IS NOT NULL AND EXISTS (
                      SELECT 1 FROM public.farms f
                      WHERE f.id = g.farm_id
                        AND f.owner_id = auth.uid()
                  ))
                  OR public.is_super_admin()
              )
        )
    );

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
-- 7. BOOKINGS RLS POLICIES
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
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id::text = auth.uid()::text
            AND NOT EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = bookings.farm_id
                  AND f.owner_id::text = auth.uid()::text
            )
        )
    );

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
-- 8. LISTING PAYMENTS RLS POLICIES
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
-- 9. REVIEWS RLS POLICIES
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
-- 10. WISHLIST RLS POLICIES
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
-- 11. NOTIFICATIONS RLS POLICIES
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
-- 12. REPORTS RLS POLICIES
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
-- 13. AUDIT LOGS RLS POLICIES
-- =============================================================================

DROP POLICY IF EXISTS "audit_logs_select_policy" ON public.audit_logs;
DROP POLICY IF EXISTS "audit_logs_insert_policy" ON public.audit_logs;

CREATE POLICY "audit_logs_select_policy" ON public.audit_logs
    FOR SELECT TO authenticated
    USING (public.is_super_admin());

CREATE POLICY "audit_logs_insert_policy" ON public.audit_logs
    FOR INSERT TO authenticated
    WITH CHECK (public.is_super_admin() OR auth.uid() IS NOT NULL);
