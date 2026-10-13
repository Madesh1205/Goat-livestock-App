-- =============================================================================
-- AMMAL FARM / ADU SANTHAI - PRODUCTION SECURITY AUDIT & HARDENING MIGRATION
-- Migration: 20261011000000_production_security_audit_and_hardening.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. PREVENT PRIVILEGE ESCALATION DURING SIGNUP
-- -----------------------------------------------------------------------------

-- A. Handle New Auth User Signup Trigger
CREATE OR REPLACE FUNCTION public.handle_new_auth_user()
RETURNS TRIGGER AS $$
DECLARE
    v_raw_role TEXT;
    v_role public.user_role;
    v_existing_role public.user_role;
    v_full_name TEXT;
    v_phone TEXT;
    v_farm_name TEXT;
    v_farm_district TEXT;
    v_farm_description TEXT;
    v_farm_id UUID;
    v_default_slots INT := 2;
BEGIN
    -- Extract full name and phone safely from metadata or direct fields
    v_full_name := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'full_name'), ''), 'Ammal User');
    v_phone := COALESCE(NULLIF(TRIM(NEW.raw_user_meta_data->>'phone'), ''), NEW.phone);

    -- Strict Role Assignment: NEVER allow SUPER_ADMIN privilege escalation from client metadata!
    v_raw_role := UPPER(COALESCE(TRIM(NEW.raw_user_meta_data->>'role'), 'CUSTOMER'));
    IF v_raw_role = 'FARM_ADMIN' OR v_raw_role = 'GOAT_BREEDER' OR v_raw_role = 'BREEDER' THEN
        v_role := 'FARM_ADMIN'::public.user_role;
    ELSE
        v_role := 'CUSTOMER'::public.user_role;
    END IF;

    -- Read configured default initial listing slots from platform_settings
    BEGIN
        SELECT COALESCE(value::INT, 2) INTO v_default_slots
        FROM public.platform_settings
        WHERE key = 'default_initial_slots';
        IF v_default_slots IS NULL OR v_default_slots <= 0 THEN
            v_default_slots := 2;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        v_default_slots := 2;
    END;

    -- profiles.role is the authoritative source of truth.
    -- If profile already exists (e.g. initial SUPER_ADMIN provisioned), preserve its existing role.
    SELECT role INTO v_existing_role FROM public.profiles WHERE id = NEW.id;
    IF v_existing_role IS NOT NULL THEN
        v_role := v_existing_role;
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
                v_default_slots,
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
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- Alias handle_new_user_signup for compatibility
CREATE OR REPLACE FUNCTION public.handle_new_user_signup()
RETURNS TRIGGER AS $$
BEGIN
    RETURN public.handle_new_auth_user();
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_auth_user();


-- B. Enforce Profile Security Guard Trigger
CREATE OR REPLACE FUNCTION public.enforce_profile_security_guard()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID := auth.uid();
    v_session_role TEXT := COALESCE(current_setting('role', true), '');
BEGIN
    -- 1. Direct DB admin / SQL Editor / backend service role / auth trigger execution (auth.uid() is NULL):
    IF v_auth_uid IS NULL THEN
        -- Even when auth.uid() is NULL, reject SUPER_ADMIN escalation unless session role is service_role or superuser
        IF TG_OP = 'INSERT' AND NEW.role = 'SUPER_ADMIN' THEN
            IF v_session_role NOT IN ('service_role', 'postgres', 'supabase_admin') AND current_user NOT IN ('postgres', 'supabase_admin') THEN
                NEW.role := 'CUSTOMER'::public.user_role;
            END IF;
        ELSIF TG_OP = 'UPDATE' AND NEW.role IS DISTINCT FROM OLD.role THEN
            IF v_session_role NOT IN ('service_role', 'postgres', 'supabase_admin') AND current_user NOT IN ('postgres', 'supabase_admin') THEN
                NEW.role := OLD.role;
            END IF;
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 2. Check if current authenticated caller is Super Admin (profiles.role is source of truth)
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    IF v_is_super_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- 3. Non-Super Admins: Enforce strict constraints
    IF TG_OP = 'INSERT' THEN
        IF NEW.role = 'SUPER_ADMIN' THEN
            NEW.role := 'CUSTOMER'::public.user_role;
        END IF;
        IF NEW.role NOT IN ('CUSTOMER'::public.user_role, 'FARM_ADMIN'::public.user_role) THEN
            NEW.role := 'CUSTOMER'::public.user_role;
        END IF;
        IF NEW.role = 'CUSTOMER'::public.user_role THEN
            NEW.farm_id := NULL;
        END IF;
        NEW.is_suspended := FALSE;

    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.role = 'SUPER_ADMIN'::public.user_role THEN
            NEW.role := OLD.role;
        END IF;
        IF NEW.role NOT IN ('CUSTOMER'::public.user_role, 'FARM_ADMIN'::public.user_role) THEN
            NEW.role := OLD.role;
        END IF;
        NEW.is_suspended := OLD.is_suspended;
        IF NEW.role = 'CUSTOMER'::public.user_role THEN
            NEW.farm_id := NULL;
        END IF;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_profile_security_guard ON public.profiles;
CREATE TRIGGER tr_enforce_profile_security_guard
    BEFORE INSERT OR UPDATE ON public.profiles
    FOR EACH ROW EXECUTE FUNCTION public.enforce_profile_security_guard();


-- -----------------------------------------------------------------------------
-- 2. REMOVE HARDCODED TOKEN FROM NOTIFICATION TRIGGER & HARDEN FCM DISPATCH
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.send_fcm_on_notification_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_payload JSONB;
    v_url TEXT;
    v_secret TEXT;
BEGIN
    BEGIN
        v_url := COALESCE(
            NULLIF(current_setting('app.settings.supabase_url', true), ''),
            'https://wphgctwmjcvrblpybktd.supabase.co'
        ) || '/functions/v1/send-fcm-notification';

        v_secret := COALESCE(
            NULLIF(current_setting('app.settings.fcm_webhook_secret', true), ''),
            'fcm-webhook-secret-v1'
        );

        v_payload := jsonb_build_object(
            'type', 'INSERT',
            'table', 'notifications',
            'schema', 'public',
            'record', jsonb_build_object(
                'id', NEW.id,
                'user_id', NEW.user_id,
                'title', NEW.title,
                'body', NEW.body,
                'link_type', NEW.link_type,
                'link_id', NEW.link_id,
                'event_key', NEW.event_key
            )
        );

        IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_net') THEN
            PERFORM net.http_post(
                url := v_url,
                headers := jsonb_build_object(
                    'Content-Type', 'application/json',
                    'X-Webhook-Secret', v_secret
                ),
                body := v_payload
            );
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'FCM notification trigger dispatch notice: %', SQLERRM;
    END;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_send_fcm_on_notification_insert ON public.notifications;
CREATE TRIGGER tr_send_fcm_on_notification_insert
    AFTER INSERT ON public.notifications
    FOR EACH ROW EXECUTE FUNCTION public.send_fcm_on_notification_insert();


-- -----------------------------------------------------------------------------
-- 3. TIGHTEN RLS & AUTHORIZATION (audit_logs, listing_payments, cleanup_queue)
-- -----------------------------------------------------------------------------

-- A. audit_logs
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "audit_logs_insert_policy" ON public.audit_logs;
DROP POLICY IF EXISTS "Anyone can insert audit logs" ON public.audit_logs;
DROP POLICY IF EXISTS "Authenticated users can insert audit logs" ON public.audit_logs;
DROP POLICY IF EXISTS "Super admin can view audit logs" ON public.audit_logs;

CREATE POLICY "Super admin can view audit logs"
    ON public.audit_logs
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );

CREATE POLICY "Super admin can insert audit logs"
    ON public.audit_logs
    FOR INSERT
    TO authenticated
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );


-- B. listing_payments
ALTER TABLE public.listing_payments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "listing_payments_insert_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "Anyone can insert listing payments" ON public.listing_payments;
DROP POLICY IF EXISTS "Authenticated users can insert payments" ON public.listing_payments;
DROP POLICY IF EXISTS "Farm owners can view listing payments" ON public.listing_payments;
DROP POLICY IF EXISTS "Super admin can manage listing payments" ON public.listing_payments;

CREATE POLICY "Farm owners can view listing payments"
    ON public.listing_payments
    FOR SELECT
    TO authenticated
    USING (
        farm_id IN (
            SELECT farm_id FROM public.profiles WHERE id = (SELECT auth.uid())
        )
        OR EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );

CREATE POLICY "Super admin can manage listing payments"
    ON public.listing_payments
    FOR ALL
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );


-- C. goat_storage_cleanup_queue
ALTER TABLE public.goat_storage_cleanup_queue ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "cleanup_queue_authenticated_policy" ON public.goat_storage_cleanup_queue;
DROP POLICY IF EXISTS "Farm owners can view cleanup queue" ON public.goat_storage_cleanup_queue;
DROP POLICY IF EXISTS "Farm owners can manage cleanup queue" ON public.goat_storage_cleanup_queue;

CREATE POLICY "Farm owners can view cleanup queue"
    ON public.goat_storage_cleanup_queue
    FOR SELECT
    TO authenticated
    USING (
        farm_id IN (
            SELECT farm_id FROM public.profiles WHERE id = (SELECT auth.uid())
        )
        OR EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );

CREATE POLICY "Farm owners can manage cleanup queue"
    ON public.goat_storage_cleanup_queue
    FOR ALL
    TO authenticated
    USING (
        farm_id IN (
            SELECT farm_id FROM public.profiles WHERE id = (SELECT auth.uid())
        )
        OR EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    )
    WITH CHECK (
        farm_id IN (
            SELECT farm_id FROM public.profiles WHERE id = (SELECT auth.uid())
        )
        OR EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = (SELECT auth.uid()) AND role = 'SUPER_ADMIN'
        )
    );


-- -----------------------------------------------------------------------------
-- 4. HARDEN SECURITY DEFINER FUNCTIONS & SUSPENDED ACCOUNT GUARDS
-- -----------------------------------------------------------------------------

-- A. Enforce Goat Listing Limit
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_farm RECORD;
    v_current_count INT;
    v_limit INT;
    v_is_super_admin BOOLEAN := FALSE;
    v_is_suspended BOOLEAN := FALSE;
    v_caller_id UUID := auth.uid();
BEGIN
    IF v_caller_id IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN'), is_suspended
        INTO v_is_super_admin, v_is_suspended
        FROM public.profiles
        WHERE id = v_caller_id;

        IF v_is_suspended IS TRUE THEN
            RAISE EXCEPTION 'Suspended user account cannot create or modify goat listings.' USING ERRCODE = '42501';
        END IF;

        IF v_is_super_admin IS TRUE THEN
            RETURN NEW;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
        IF v_farm.id IS NULL THEN
            RAISE EXCEPTION 'Associated farm does not exist.';
        END IF;

        -- Ammal Farm is exempt from listing limits
        IF v_farm.is_ammal_own_farm IS TRUE THEN
            RETURN NEW;
        END IF;

        -- Check farm verification status
        IF v_farm.status IS DISTINCT FROM 'APPROVED'::public.farm_status THEN
            RAISE EXCEPTION 'Farm must be approved by Super Admin before listing goats.';
        END IF;

        v_limit := COALESCE(v_farm.goat_listing_limit, 2);
        SELECT COUNT(*)::INT INTO v_current_count
        FROM public.goats
        WHERE farm_id = NEW.farm_id AND is_deleted = FALSE;

        IF v_current_count >= v_limit THEN
            RAISE EXCEPTION 'Goat listing quota reached for farm (%) (% / % slots used). Contact Super Admin to increase limit.', v_farm.name, v_current_count, v_limit;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
    BEFORE INSERT ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();


-- B. Booking Hold Creation with Suspended Account Guard
CREATE OR REPLACE FUNCTION public.create_booking_hold(
    p_goat_id UUID,
    p_notes TEXT DEFAULT ''
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_customer_id UUID := auth.uid();
    v_goat public.goats%ROWTYPE;
    v_farm public.farms%ROWTYPE;
    v_existing_active_booking public.bookings%ROWTYPE;
    v_booking_id UUID;
    v_is_suspended BOOLEAN := FALSE;
BEGIN
    IF v_customer_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to create a booking hold.' USING ERRCODE = '42501';
    END IF;

    SELECT is_suspended INTO v_is_suspended
    FROM public.profiles WHERE id = v_customer_id;

    IF v_is_suspended IS TRUE THEN
        RAISE EXCEPTION 'Suspended user account cannot create bookings.' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_goat FROM public.goats WHERE id = p_goat_id AND is_deleted = FALSE;
    IF v_goat.id IS NULL THEN
        RAISE EXCEPTION 'Goat listing not found or has been removed.' USING ERRCODE = '42703';
    END IF;

    IF v_goat.is_approved_by_admin IS NOT TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval.' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF v_farm.id IS NULL OR v_farm.status IS DISTINCT FROM 'APPROVED'::public.farm_status THEN
        RAISE EXCEPTION 'Farm is not active or approved.' USING ERRCODE = '42501';
    END IF;

    IF v_farm.owner_id = v_customer_id THEN
        RAISE EXCEPTION 'Farm owners cannot book goats from their own farm.' USING ERRCODE = '42501';
    END IF;

    IF v_goat.availability_status IS DISTINCT FROM 'AVAILABLE'::public.availability_status THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.availability_status USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_existing_active_booking
    FROM public.bookings
    WHERE goat_id = p_goat_id
      AND status IN ('RESERVED'::public.booking_status, 'CONFIRMED'::public.booking_status)
    LIMIT 1;

    IF v_existing_active_booking.id IS NOT NULL THEN
        RAISE EXCEPTION 'Goat is currently reserved or booked by another customer.' USING ERRCODE = '42501';
    END IF;

    v_booking_id := gen_random_uuid();

    INSERT INTO public.bookings (
        id,
        goat_id,
        farm_id,
        customer_id,
        status,
        total_price,
        notes,
        hold_expires_at,
        created_at,
        updated_at
    )
    VALUES (
        v_booking_id,
        p_goat_id,
        v_goat.farm_id,
        v_customer_id,
        'RESERVED'::public.booking_status,
        v_goat.price,
        COALESCE(p_notes, ''),
        NOW() + INTERVAL '24 hours',
        NOW(),
        NOW()
    );

    UPDATE public.goats
    SET availability_status = 'RESERVED'::public.availability_status,
        updated_at = NOW()
    WHERE id = p_goat_id;

    RETURN jsonb_build_object(
        'success', TRUE,
        'booking_id', v_booking_id,
        'goat_id', p_goat_id,
        'status', 'RESERVED',
        'hold_expires_at', NOW() + INTERVAL '24 hours'
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.create_booking_hold(UUID, TEXT) TO authenticated;


-- C. Make Partner Farm Approval Use Dynamic Configured Default Slots
CREATE OR REPLACE FUNCTION public.approve_partner_farm_with_manual_payment(
    p_farm_id UUID,
    p_amount NUMERIC DEFAULT 500,
    p_payment_ref TEXT DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_actor_id UUID := auth.uid();
    v_actor_role TEXT;
    v_farm public.farms%ROWTYPE;
    v_payment_id UUID;
    v_receipt_number TEXT;
    v_payment_ref TEXT;
    v_existing_limit INT;
    v_new_limit INT;
    v_default_slots INT := 2;
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required.' USING ERRCODE = '42501';
    END IF;

    SELECT role INTO v_actor_role FROM public.profiles WHERE id = v_actor_id;
    IF v_actor_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Only Super Admin can approve partner farms.' USING ERRCODE = '42501';
    END IF;

    IF p_amount IS NULL OR p_amount < 0 THEN
        RAISE EXCEPTION 'Approval payment amount must be non-negative.' USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_farm FROM public.farms WHERE id = p_farm_id;
    IF v_farm.id IS NULL THEN
        RAISE EXCEPTION 'Farm not found: %', p_farm_id USING ERRCODE = '42703';
    END IF;

    IF v_farm.is_ammal_own_farm IS TRUE THEN
        RAISE EXCEPTION 'Ammal Farm is exempt from partner approval charges.' USING ERRCODE = '42501';
    END IF;

    -- Read configured default initial listing slots from platform_settings
    SELECT COALESCE(value::INT, 2) INTO v_default_slots
    FROM public.platform_settings
    WHERE key = 'default_initial_slots';

    IF v_default_slots IS NULL OR v_default_slots <= 0 THEN
        v_default_slots := 2;
    END IF;

    v_existing_limit := COALESCE(v_farm.goat_listing_limit, 0);
    IF v_existing_limit <= 0 THEN
        v_new_limit := v_default_slots;
    ELSE
        v_new_limit := v_existing_limit;
    END IF;

    v_payment_id := gen_random_uuid();
    v_receipt_number := 'RCPT-APPR-' || RIGHT(EXTRACT(EPOCH FROM NOW())::BIGINT::TEXT, 6);
    v_payment_ref := COALESCE(NULLIF(TRIM(p_payment_ref), ''), 'MANUAL-APPR-' || SUBSTRING(p_farm_id::TEXT FROM 1 FOR 6) || '-' || SUBSTRING(v_payment_id::TEXT FROM 1 FOR 6));

    INSERT INTO public.listing_payments (
        id,
        farm_id,
        farm_name,
        amount,
        currency,
        payment_type,
        slots_added,
        status,
        receipt_number,
        payment_method,
        notes,
        created_at
    )
    VALUES (
        v_payment_id,
        p_farm_id,
        v_farm.name,
        p_amount,
        'INR',
        'FARM_APPROVAL',
        CASE WHEN v_existing_limit <= 0 THEN v_default_slots ELSE 0 END,
        'PAID'::public.payment_status,
        v_receipt_number,
        'Manual Payment (Super Admin Approved)',
        COALESCE(p_notes, 'Initial partner farm registration fee paid.'),
        NOW()
    );

    UPDATE public.farms
    SET status = 'APPROVED'::public.farm_status,
        goat_listing_limit = v_new_limit,
        updated_at = NOW()
    WHERE id = p_farm_id;

    UPDATE public.profiles
    SET role = 'FARM_ADMIN'::public.user_role,
        farm_id = p_farm_id,
        updated_at = NOW()
    WHERE id = v_farm.owner_id;

    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_id', p_farm_id,
        'status', 'APPROVED',
        'goat_listing_limit', v_new_limit,
        'payment_id', v_payment_id,
        'receipt_number', v_receipt_number,
        'payment_reference', v_payment_ref
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.approve_partner_farm_with_manual_payment(UUID, NUMERIC, TEXT, TEXT) TO authenticated;


-- Alias admin_approve_partner_farm for backwards compatibility
CREATE OR REPLACE FUNCTION public.admin_approve_partner_farm(
    p_farm_id UUID,
    p_amount NUMERIC DEFAULT 500,
    p_payment_ref TEXT DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    RETURN public.approve_partner_farm_with_manual_payment(p_farm_id, p_amount, p_payment_ref, p_notes);
END;
$$;

GRANT EXECUTE ON FUNCTION public.admin_approve_partner_farm(UUID, NUMERIC, TEXT, TEXT) TO authenticated;


-- -----------------------------------------------------------------------------
-- 5. MISSING FOREIGN KEY INDEXES & RLS OPTIMIZATION
-- -----------------------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_listing_payments_booking_id ON public.listing_payments(booking_id);
CREATE INDEX IF NOT EXISTS idx_listing_payments_goat_id ON public.listing_payments(goat_id);
CREATE INDEX IF NOT EXISTS idx_platform_settings_updated_by ON public.platform_settings(updated_by);
CREATE INDEX IF NOT EXISTS idx_reports_resolved_by ON public.reports(resolved_by);

-- Ensure platform_settings RLS policies allow public read
ALTER TABLE public.platform_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Anyone can read platform settings" ON public.platform_settings;
DROP POLICY IF EXISTS "Authenticated users can read platform settings" ON public.platform_settings;

CREATE POLICY "Anyone can read platform settings"
    ON public.platform_settings
    FOR SELECT
    TO public, authenticated, anon
    USING (TRUE);

GRANT SELECT ON public.platform_settings TO PUBLIC, authenticated, anon;
