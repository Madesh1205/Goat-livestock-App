-- =============================================================================
-- AMMAL FARM PLATFORM - MUMBAI MIGRATION (ap-south-1)
-- File: 02_functions_and_triggers.sql
-- Step 2: Functions, Stored Procedures, Business Logic, and Automated Triggers
-- =============================================================================

-- =============================================================================
-- 1. SECURITY DEFINER HELPER FUNCTIONS (WITH SAFE SEARCH_PATH)
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

CREATE OR REPLACE FUNCTION public.get_auth_farm_id()
RETURNS UUID AS $$
    SELECT id FROM public.farms WHERE owner_id::text = auth.uid()::text LIMIT 1;
$$ LANGUAGE sql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 2. STORAGE AUTHORIZATION FUNCTIONS
-- =============================================================================

CREATE OR REPLACE FUNCTION public.can_access_storage_farm_path(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_farm_uuid UUID;
BEGIN
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern A: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' AND v_target != '' THEN
        BEGIN
            v_farm_uuid := v_target::UUID;
            RETURN EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = v_farm_uuid
                  AND (
                      f.owner_id = auth.uid()
                      OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
                  )
            );
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern B: '<farm_id>/...' (direct farm UUID at root)
    BEGIN
        v_farm_uuid := v_prefix::UUID;
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = v_farm_uuid
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.can_modify_storage_goat_image(object_name TEXT)
RETURNS BOOLEAN AS $$
DECLARE
    v_prefix TEXT;
    v_target TEXT;
    v_farm_uuid UUID;
BEGIN
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    -- Only FARM_ADMIN or SUPER_ADMIN can modify goat images
    IF NOT EXISTS (
        SELECT 1 FROM public.profiles
        WHERE id = auth.uid() AND role IN ('FARM_ADMIN', 'SUPER_ADMIN')
    ) THEN
        RETURN FALSE;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern 1: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' AND v_target != '' THEN
        BEGIN
            v_farm_uuid := v_target::UUID;
            RETURN EXISTS (
                SELECT 1 FROM public.farms f
                WHERE f.id = v_farm_uuid
                  AND (
                      f.owner_id = auth.uid()
                      OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
                  )
            );
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern 2: '<farm_id>/...'
    BEGIN
        v_farm_uuid := v_prefix::UUID;
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = v_farm_uuid
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 3. SYSTEM NOTIFICATION CREATION WITH DEDUPLICATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.create_system_notification(
    p_user_id UUID,
    p_title TEXT,
    p_body TEXT,
    p_link_type TEXT DEFAULT 'GENERAL',
    p_link_id TEXT DEFAULT NULL,
    p_event_key TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_notification_id UUID := gen_random_uuid();
BEGIN
    INSERT INTO public.notifications (
        id, user_id, title, body, link_type, link_id, event_key, is_read, created_at
    ) VALUES (
        v_notification_id, p_user_id, p_title, p_body, p_link_type, p_link_id, p_event_key, FALSE, NOW()
    )
    ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL
    DO NOTHING;

    RETURN v_notification_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- =============================================================================
-- 4. AUTH SIGNUP & PROFILE / FARM PROVISIONING TRIGGER
-- =============================================================================

CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
DECLARE
    v_raw_role TEXT;
    v_role public.user_role;
    v_full_name TEXT;
    v_phone TEXT;
    v_farm_name TEXT;
    v_farm_district TEXT;
    v_farm_description TEXT;
    v_farm_id UUID;
BEGIN
    -- Extract full name and phone safely from metadata or direct fields
    v_full_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''), 'Ammal User');
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Strict Role Assignment: NEVER allow SUPER_ADMIN privilege escalation from client metadata!
    v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
    IF v_raw_role = 'FARM_ADMIN' THEN
        v_role := 'FARM_ADMIN'::public.user_role;
    ELSE
        v_role := 'CUSTOMER'::public.user_role;
    END IF;

    -- Bootstrap super admin safeguard (by email only)
    IF LOWER(COALESCE(NEW.email, '')) = 'madesh1205@gmail.com' THEN
        v_role := 'SUPER_ADMIN'::public.user_role;
    END IF;

    -- Insert or Update profile for auth.users(id)
    INSERT INTO public.profiles (
        id,
        full_name,
        email,
        phone,
        role,
        created_at,
        updated_at
    )
    VALUES (
        NEW.id,
        v_full_name,
        NEW.email,
        v_phone,
        v_role,
        NOW(),
        NOW()
    )
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = EXCLUDED.email,
        phone = COALESCE(EXCLUDED.phone, public.profiles.phone),
        updated_at = NOW();

    -- If FARM_ADMIN applicant, create their farm in PENDING status atomically
    IF v_role = 'FARM_ADMIN'::public.user_role THEN
        v_farm_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_name'), ''), v_full_name || ' Farm');
        v_farm_district := COALESCE(
            NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_district'), ''),
            NULLIF(TRIM(NEW.raw_user_meta_data->>'location_district'), ''),
            'Madurai'
        );
        v_farm_description := NULLIF(TRIM(NEW.raw_user_meta_data->>'farm_description'), '');

        -- Check if farm already exists for this owner
        SELECT id INTO v_farm_id FROM public.farms WHERE owner_id = NEW.id LIMIT 1;

        IF v_farm_id IS NULL THEN
            v_farm_id := gen_random_uuid();
            INSERT INTO public.farms (
                id,
                name,
                owner_id,
                description,
                location_district,
                location_state,
                address,
                contact_phone,
                contact_email,
                status,
                is_ammal_own_farm,
                goat_listing_limit,
                rating,
                review_count,
                created_at,
                updated_at
            )
            VALUES (
                v_farm_id,
                v_farm_name,
                NEW.id,
                v_farm_description,
                v_farm_district,
                'Tamil Nadu',
                v_farm_district || ', Tamil Nadu',
                COALESCE(v_phone, 'Not provided'),
                NEW.email,
                'PENDING'::public.farm_status,
                FALSE,
                10,
                5.00,
                0,
                NOW(),
                NOW()
            );
        END IF;

        -- Associate farm_id back to profile
        UPDATE public.profiles
        SET farm_id = v_farm_id, updated_at = NOW()
        WHERE id = NEW.id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Bind trigger to auth.users
DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();

-- =============================================================================
-- 5. BOOKING SYSTEM BUSINESS LOGIC & EXPIRATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.expire_overdue_bookings()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH expired_records AS (
        UPDATE public.bookings
        SET status = 'EXPIRED',
            updated_at = NOW()
        WHERE status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW()
        RETURNING id, goat_id
    )
    SELECT COUNT(*) INTO v_count FROM expired_records;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.handle_booking_status_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        UPDATE public.goats SET status = 'COMPLETED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status = 'CONFIRMED' THEN
        UPDATE public.goats SET status = 'CONFIRMED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status IN ('RESERVED', 'PENDING') THEN
        UPDATE public.goats SET status = 'RESERVED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status IN ('CANCELLED', 'EXPIRED') THEN
        IF NOT EXISTS (
            SELECT 1 FROM public.bookings 
            WHERE goat_id = NEW.goat_id 
              AND id != NEW.id 
              AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
        ) THEN
            UPDATE public.goats SET status = 'AVAILABLE', updated_at = NOW() WHERE id = NEW.goat_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_booking_status_change ON public.bookings;
CREATE TRIGGER tr_booking_status_change
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.handle_booking_status_change();

CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_effective_price NUMERIC(12, 2);
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Auto-expire overdue holds on this goat
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW();

        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        IF v_goat.status != 'AVAILABLE' THEN
            RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
        END IF;

        IF v_goat.is_approved_by_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Goat listing is not approved by administrator.';
        END IF;

        -- Compute authoritative price
        IF v_goat.discount_percentage > 0 THEN
            v_effective_price := ROUND(v_goat.price * (1 - (v_goat.discount_percentage / 100.0)), 2);
        ELSE
            v_effective_price := v_goat.price;
        END IF;

        NEW.total_price := v_effective_price;
        NEW.farm_id := v_goat.farm_id;
        NEW.booking_date := NOW();
        NEW.hold_expires_at := NOW() + INTERVAL '48 hours';
        NEW.status := 'PENDING';
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        IF NEW.booking_code IS NULL OR NEW.booking_code = '' THEN
            NEW.booking_code := 'AMM-' || UPPER(SUBSTRING(REPLACE(gen_random_uuid()::text, '-', '') FROM 1 FOR 6));
        END IF;

        RETURN NEW;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_booking_price_snapshot ON public.bookings;
CREATE TRIGGER tr_enforce_booking_price_snapshot
    BEFORE INSERT ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_price_snapshot();

CREATE OR REPLACE FUNCTION public.enforce_booking_amount_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.total_price IS DISTINCT FROM NEW.total_price THEN
            RAISE EXCEPTION 'Booking price snapshot is immutable once booked (total_price: %, attempted: %).', OLD.total_price, NEW.total_price;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_booking_amount ON public.bookings;
CREATE TRIGGER tr_enforce_booking_amount
    BEFORE UPDATE ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_amount_immutability();

-- =============================================================================
-- 6. GOAT LISTING & MODERATION RULES
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            NEW.is_approved_by_admin := TRUE;
        ELSE
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();

-- =============================================================================
-- 7. REVIEW AUTHENTICITY & RATING AGGREGATION
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_review_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_booking RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF auth.uid() IS NOT NULL AND NOT public.is_super_admin() THEN
            NEW.customer_id := auth.uid();
        END IF;

        IF NEW.booking_id IS NULL THEN
            RAISE EXCEPTION 'A valid booking ID is required to submit a verified review.';
        END IF;

        SELECT * INTO v_booking
        FROM public.bookings
        WHERE id = NEW.booking_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Booking with ID % does not exist.', NEW.booking_id;
        END IF;

        IF v_booking.customer_id != NEW.customer_id AND NOT public.is_super_admin() THEN
            RAISE EXCEPTION 'Unauthorized: Customers can only review their own purchases.';
        END IF;

        IF v_booking.status NOT IN ('COMPLETED', 'CONFIRMED') THEN
            RAISE EXCEPTION 'Reviews are only permitted for confirmed or completed bookings (current: %).', v_booking.status;
        END IF;

        NEW.goat_id := v_booking.goat_id;
        NEW.farm_id := v_booking.farm_id;
        NEW.is_verified_purchase := TRUE;
        NEW.is_approved := TRUE;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NOT public.is_super_admin() THEN
            IF NEW.booking_id != OLD.booking_id OR NEW.goat_id != OLD.goat_id OR
               NEW.farm_id != OLD.farm_id OR NEW.customer_id != OLD.customer_id THEN
                RAISE EXCEPTION 'Relationship fields on a review are immutable.';
            END IF;
            NEW.is_approved := OLD.is_approved;
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_review_authenticity ON public.reviews;
CREATE TRIGGER tr_enforce_review_authenticity
    BEFORE INSERT OR UPDATE ON public.reviews
    FOR EACH ROW EXECUTE FUNCTION public.enforce_review_authenticity();

CREATE OR REPLACE FUNCTION public.recalculate_ratings_on_review()
RETURNS TRIGGER AS $$
DECLARE
    v_goat_id UUID;
    v_farm_id UUID;
    v_avg_rating NUMERIC(3, 2);
    v_count INT;
    v_farm_avg NUMERIC(3, 2);
    v_farm_count INT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_goat_id := OLD.goat_id;
        v_farm_id := OLD.farm_id;
    ELSE
        v_goat_id := NEW.goat_id;
        v_farm_id := NEW.farm_id;
    END IF;

    -- 1. Compute Goat Stats
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_avg_rating, v_count
    FROM public.reviews
    WHERE goat_id = v_goat_id AND is_approved = TRUE;

    UPDATE public.goats
    SET rating = v_avg_rating,
        review_count = v_count,
        updated_at = NOW()
    WHERE id = v_goat_id;

    -- 2. Compute Farm Stats
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_farm_avg, v_farm_count
    FROM public.reviews
    WHERE farm_id = v_farm_id AND is_approved = TRUE;

    UPDATE public.farms
    SET rating = v_farm_avg,
        review_count = v_farm_count,
        updated_at = NOW()
    WHERE id = v_farm_id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_recalculate_ratings ON public.reviews;
CREATE TRIGGER tr_recalculate_ratings
    AFTER INSERT OR UPDATE OF rating, is_approved OR DELETE ON public.reviews
    FOR EACH ROW EXECUTE FUNCTION public.recalculate_ratings_on_review();

-- =============================================================================
-- 8. REPORT AUTHENTICITY
-- =============================================================================

CREATE OR REPLACE FUNCTION public.enforce_report_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := public.is_super_admin();
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF auth.uid() IS NOT NULL AND NOT v_is_super_admin THEN
            NEW.reporter_id := auth.uid();
        END IF;
        NEW.status := 'PENDING';
        NEW.resolved_by := NULL;
        NEW.resolution_notes := NULL;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NOT v_is_super_admin THEN
            RAISE EXCEPTION 'Unauthorized: Only Super Admin can update or moderate safety reports.';
        END IF;

        IF NEW.status IN ('RESOLVED', 'DISMISSED') AND OLD.status NOT IN ('RESOLVED', 'DISMISSED') THEN
            NEW.resolved_by := auth.uid();
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_report_authenticity ON public.reports;
CREATE TRIGGER tr_enforce_report_authenticity
    BEFORE INSERT OR UPDATE ON public.reports
    FOR EACH ROW EXECUTE FUNCTION public.enforce_report_authenticity();

-- =============================================================================
-- 9. NOTIFICATION & AUDIT TRIGGERS (MODERATION & BOOKINGS)
-- =============================================================================

CREATE OR REPLACE FUNCTION public.notify_and_audit_farm_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_super_admin_id UUID;
BEGIN
    SELECT id INTO v_super_admin_id FROM public.profiles WHERE role = 'SUPER_ADMIN' LIMIT 1;

    IF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'APPROVED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Approved! 🚜🎉',
                'Your farm "' || NEW.name || '" has been approved by Super Admin. You can now list goats on Ammal Farm Marketplace!',
                'FARM',
                NEW.id::text,
                'farm_approved_' || NEW.id
            );
        ELSIF NEW.status = 'REJECTED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Application Update ⚠️',
                'Your farm application for "' || NEW.name || '" was reviewed and rejected. Contact support for details.',
                'FARM',
                NEW.id::text,
                'farm_rejected_' || NEW.id
            );
        ELSIF NEW.status = 'SUSPENDED' THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Account Suspended 🛑',
                'Your farm "' || NEW.name || '" has been suspended. Listings are hidden from public view.',
                'FARM',
                NEW.id::text,
                'farm_suspended_' || NEW.id
            );
        END IF;

        -- Record Audit Log
        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (
            COALESCE(auth.uid(), v_super_admin_id),
            'FARM_STATUS_CHANGE',
            'FARM',
            NEW.id,
            OLD.status::text,
            NEW.status::text,
            'Farm status transitioned to ' || NEW.status::text
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_farm_moderation_audit ON public.farms;
CREATE TRIGGER tr_farm_moderation_audit
    AFTER UPDATE OF status ON public.farms
    FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_farm_moderation();

CREATE OR REPLACE FUNCTION public.notify_and_audit_goat_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_farm_owner_id UUID;
    v_super_admin_id UUID;
BEGIN
    SELECT owner_id INTO v_farm_owner_id FROM public.farms WHERE id = NEW.farm_id;
    SELECT id INTO v_super_admin_id FROM public.profiles WHERE role = 'SUPER_ADMIN' LIMIT 1;

    IF TG_OP = 'UPDATE' AND OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
        IF NEW.is_approved_by_admin = TRUE THEN
            IF v_farm_owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm_owner_id,
                    'Listing Approved! 🐐✨',
                    'Goat "' || NEW.name || '" (Tag #' || NEW.tag_number || ') is approved and live on Ammal Farm marketplace.',
                    'GOAT',
                    NEW.id::text,
                    'goat_approved_' || NEW.id
                );
            END IF;
        ELSE
            IF v_farm_owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm_owner_id,
                    'Listing Unapproved ⚠️',
                    'Goat "' || NEW.name || '" listing has been unpublished by admin.',
                    'GOAT',
                    NEW.id::text,
                    'goat_unapproved_' || NEW.id
                );
            END IF;
        END IF;

        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (
            COALESCE(auth.uid(), v_super_admin_id),
            'GOAT_MODERATION',
            'GOAT',
            NEW.id,
            OLD.is_approved_by_admin::text,
            NEW.is_approved_by_admin::text,
            'Goat approval status updated'
        );
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_goat_moderation_audit ON public.goats;
CREATE TRIGGER tr_goat_moderation_audit
    AFTER UPDATE OF is_approved_by_admin ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_goat_moderation();

CREATE OR REPLACE FUNCTION public.notify_booking_lifecycle()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_booking_ref TEXT;
BEGIN
    SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
    SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
    v_booking_ref := COALESCE(NEW.booking_code, SUBSTRING(NEW.id::text FROM 1 FOR 6));

    IF TG_OP = 'INSERT' THEN
        -- Customer notification: 48h hold active
        PERFORM public.create_system_notification(
            NEW.customer_id,
            'Reservation Active (48 Hours) 🐐',
            'Your reservation for ' || COALESCE(v_goat.name, 'Goat') || ' is active. Complete farm confirmation within 48 hours.',
            'BOOKING',
            NEW.id::text,
            'booking_created_' || NEW.id
        );

        -- Farm Admin notification: new reservation received
        IF v_farm.owner_id IS NOT NULL THEN
            PERFORM public.create_system_notification(
                v_farm.owner_id,
                'New Booking Received 📋',
                'New booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' (₹' || NEW.total_price || ').',
                'FARM_BOOKINGS',
                NEW.id::text,
                'booking_farm_notify_' || NEW.id
            );
        END IF;

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'CONFIRMED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Confirmed! ✅',
                'Your booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' has been confirmed by ' || COALESCE(v_farm.name, 'the farm') || '.',
                'BOOKING',
                NEW.id::text,
                'booking_confirmed_' || NEW.id
            );
        ELSIF NEW.status = 'COMPLETED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Purchase Completed 🎉',
                'Congratulations on your purchase of ' || COALESCE(v_goat.name, 'Goat') || '! Please leave a verified review.',
                'BOOKING',
                NEW.id::text,
                'booking_completed_' || NEW.id
            );
        ELSIF NEW.status = 'CANCELLED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Cancelled ❌',
                'Your booking #' || v_booking_ref || ' has been cancelled.',
                'BOOKING',
                NEW.id::text,
                'booking_cancelled_' || NEW.id
            );
        ELSIF NEW.status = 'EXPIRED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                '48-Hour Hold Expired ⏳',
                'Your 48-hour reservation hold on ' || COALESCE(v_goat.name, 'Goat') || ' has expired and was released.',
                'BOOKING',
                NEW.id::text,
                'booking_expired_' || NEW.id
            );
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_notify_booking_lifecycle ON public.bookings;
CREATE TRIGGER tr_notify_booking_lifecycle
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.notify_booking_lifecycle();
