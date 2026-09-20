-- =============================================================================
-- AMMAL FARM HYPERLOCAL LIVESTOCK MARKETPLACE
-- MIGRATION: 20260919080000_stage10c_critical_regression_fixes.sql
-- STAGE 10C: CRITICAL REGRESSION FIXES
-- 1. Farm Admin Account Deletion - Restore trigger bypass for account deletion
-- 2. Storage Path RLS - Allow FARM-XXX / GOAT-YYY paths for authenticated farm admins
-- 3. create_booking_hold RPC - Server-side atomic booking hold returning JSONB (id + booking_id)
-- 4. Sequential IDs & Safe Storage Path Migration
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. RESTORE ACCOUNT DELETION TRIGGER BYPASS ON FARMS & FUNCTION DEFINITION
-- -----------------------------------------------------------------------------
ALTER TABLE public.bookings ADD COLUMN IF NOT EXISTS booking_code TEXT;

CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    -- Permit internal server-authoritative account deletion procedure
    IF current_setting('ammal.account_deletion', true) = 'true' THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- Allow direct DB administrative scripts / SQL Editor (auth.uid() is NULL)
    IF auth.uid() IS NULL THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    -- Non-Super Admins are strictly prohibited from changing listing limits, ownership, or verification status
    IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
        IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
            RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
        END IF;

        IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
            RAISE EXCEPTION 'Farm ownership cannot be modified.';
        END IF;

        IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
            RAISE EXCEPTION 'Ammal Farm designation cannot be modified.';
        END IF;

        IF OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Only Super Admin can modify farm verification status.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Reattach trigger to public.farms
DROP TRIGGER IF EXISTS tr_enforce_farm_metadata_integrity ON public.farms;
CREATE TRIGGER tr_enforce_farm_metadata_integrity
BEFORE UPDATE ON public.farms
FOR EACH ROW EXECUTE FUNCTION public.enforce_farm_metadata_integrity();

-- Drop prior signatures before recreating
DROP FUNCTION IF EXISTS public.delete_user_account(UUID);
DROP FUNCTION IF EXISTS public.delete_user_account();

-- Robust delete_user_account RPC supporting both auth.uid() and explicit p_user_id
CREATE OR REPLACE FUNCTION public.delete_user_account(p_user_id UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID;
    v_user_role public.user_role;
    v_farm_record RECORD;
BEGIN
    v_user_id := COALESCE(auth.uid(), p_user_id);

    -- 1. Authentication check
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required. No active session identified.';
    END IF;

    -- Safety check: if auth.uid() exists and differs from p_user_id, only super admin can proceed
    IF auth.uid() IS NOT NULL AND p_user_id IS NOT NULL AND auth.uid() != p_user_id THEN
        IF NOT public.is_super_admin() THEN
            RAISE EXCEPTION 'Unauthorized account deletion request.';
        END IF;
    END IF;

    -- 2. Fetch user profile
    SELECT role INTO v_user_role
    FROM public.profiles
    WHERE id = v_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'User profile not found.';
    END IF;

    -- 3. Safety Check: Central Ammal Farm Owner / Super Admin check
    IF EXISTS (
        SELECT 1 FROM public.farms
        WHERE owner_id = v_user_id AND is_ammal_own_farm = TRUE
    ) THEN
        RAISE EXCEPTION 'The owner account of the central Ammal Farm cannot be deleted. Transfer ownership before closing this account.';
    END IF;

    -- Set local session config to allow triggers to permit deletion updates
    PERFORM set_config('ammal.account_deletion', 'true', true);

    -- 4. FARM_ADMIN specific cleanup
    IF v_user_role = 'FARM_ADMIN' THEN
        FOR v_farm_record IN SELECT id FROM public.farms WHERE owner_id = v_user_id LOOP
            -- (a) Deactivate active goat listings for this farm so they are not orphaned
            UPDATE public.goats
            SET status = 'INACTIVE',
                updated_at = NOW()
            WHERE farm_id = v_farm_record.id
              AND status != 'SOLD';

            -- (b) Cancel unconfirmed / pending bookings on this farm's goats
            UPDATE public.bookings
            SET status = 'CANCELLED',
                cancelled_at = NOW(),
                admin_notes = COALESCE(admin_notes, '') || ' [Cancelled: Farm account deleted by owner]',
                updated_at = NOW()
            WHERE farm_id = v_farm_record.id
              AND status IN ('PENDING', 'RESERVED');

            -- (c) Clean and anonymize the farm metadata
            UPDATE public.farms
            SET status = 'SUSPENDED',
                contact_phone = 'REDACTED',
                contact_email = NULL,
                description = 'Farm account closed by owner.',
                tagline = NULL,
                logo_url = NULL,
                banner_url = NULL,
                owner_id = NULL,
                updated_at = NOW()
            WHERE id = v_farm_record.id;
        END LOOP;
    END IF;

    -- 5. Bookings made by this user as customer:
    UPDATE public.goats g
    SET status = 'AVAILABLE',
        updated_at = NOW()
    FROM public.bookings b
    WHERE b.customer_id = v_user_id
      AND b.goat_id = g.id
      AND b.status IN ('PENDING', 'RESERVED')
      AND g.status = 'RESERVED';

    UPDATE public.bookings
    SET status = 'CANCELLED',
        cancelled_at = NOW(),
        customer_notes = '[Account deleted by customer]',
        customer_id = NULL,
        updated_at = NOW()
    WHERE customer_id = v_user_id
      AND status IN ('PENDING', 'RESERVED');

    UPDATE public.bookings
    SET customer_notes = NULL,
        customer_id = NULL,
        updated_at = NOW()
    WHERE customer_id = v_user_id;

    -- 6. Wishlist removal
    DELETE FROM public.wishlist WHERE user_id = v_user_id;

    -- 7. Notifications removal
    DELETE FROM public.notifications WHERE user_id = v_user_id;

    -- 8. Reports: delete pending reports filed by user; anonymize resolved reports
    DELETE FROM public.reports WHERE reporter_id = v_user_id AND status = 'PENDING';
    UPDATE public.reports SET reporter_id = NULL WHERE reporter_id = v_user_id;
    UPDATE public.reports SET resolved_by = NULL WHERE resolved_by = v_user_id;

    -- 9. Listing Payments: preserve receipt/amount, anonymize payer_id
    UPDATE public.listing_payments SET payer_id = NULL WHERE payer_id = v_user_id;

    -- 10. Reviews: anonymize customer_id if table exists
    BEGIN
        UPDATE public.reviews SET customer_id = NULL WHERE customer_id = v_user_id;
    EXCEPTION WHEN undefined_table THEN
        NULL;
    END;

    -- 11. Audit logs: anonymize actor_id
    UPDATE public.audit_logs SET actor_id = NULL WHERE actor_id = v_user_id;

    -- 12. Delete from auth.users (cascades to public.profiles)
    DELETE FROM auth.users WHERE id = v_user_id;

    -- Reset session config
    PERFORM set_config('ammal.account_deletion', 'false', true);

    RETURN jsonb_build_object(
        'success', true,
        'message', 'User account, profile, and associated personal data have been permanently deleted.'
    );
END;
$$;

REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated;


-- -----------------------------------------------------------------------------
-- 2. STORAGE RLS HELPERS: SUPPORT SEQUENTIAL FARM & GOAT CODES (FARM-XXX / GOAT-YYY)
-- -----------------------------------------------------------------------------
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

    -- Pattern 1: Sequential Farm Code: 'FARM-XXX/...' (e.g. FARM-001, FARM-002)
    IF v_prefix ~ '^FARM-[0-9]+$' THEN
        RETURN EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.farm_code = v_prefix
              AND (
                  f.owner_id = auth.uid()
                  OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid() AND p.role = 'FARM_ADMIN')
              )
        );
    END IF;

    -- Pattern 2: 'farm/<farm_id>/...'
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

    -- Pattern 3: direct '<farm_uuid>/...' (UUID at root)
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
    v_user_role TEXT;
    v_goat_uuid UUID;
BEGIN
    -- Super Admin has universal access
    IF public.is_super_admin() THEN
        RETURN TRUE;
    END IF;

    -- Unauthenticated callers are strictly denied
    IF auth.uid() IS NULL OR object_name IS NULL OR object_name = '' THEN
        RETURN FALSE;
    END IF;

    -- Check caller role: must be at least FARM_ADMIN or a registered farm owner
    SELECT role::TEXT INTO v_user_role FROM public.profiles WHERE id = auth.uid();
    IF v_user_role != 'FARM_ADMIN' AND v_user_role != 'SUPER_ADMIN' THEN
        IF NOT EXISTS (SELECT 1 FROM public.farms WHERE owner_id = auth.uid()) THEN
            RETURN FALSE;
        END IF;
    END IF;

    v_prefix := split_part(object_name, '/', 1);
    v_target := split_part(object_name, '/', 2);

    -- Pattern 1: Sequential Farm Code: 'FARM-XXX/GOAT-YYY/01.jpg'
    IF v_prefix ~ '^FARM-[0-9]+$' THEN
        RETURN public.can_access_storage_farm_path(object_name);
    END IF;

    -- Pattern 2: 'farm/<farm_id>/...'
    IF v_prefix = 'farm' THEN
        RETURN public.can_access_storage_farm_path(object_name);
    END IF;

    -- Pattern 3: 'goat/<goat_id>/...'
    IF v_prefix = 'goat' AND v_target != '' THEN
        BEGIN
            v_goat_uuid := v_target::UUID;
            IF EXISTS (SELECT 1 FROM public.goats WHERE id = v_goat_uuid) THEN
                RETURN EXISTS (
                    SELECT 1 FROM public.goats g
                    JOIN public.farms f ON f.id = g.farm_id
                    WHERE g.id = v_goat_uuid
                      AND (
                          f.owner_id = auth.uid()
                          OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid())
                      )
                );
            ELSE
                RETURN EXISTS (
                    SELECT 1 FROM public.farms f
                    WHERE (f.owner_id = auth.uid() OR f.id IN (SELECT p.farm_id FROM public.profiles p WHERE p.id = auth.uid()))
                      AND f.status = 'APPROVED'
                );
            END IF;
        EXCEPTION WHEN OTHERS THEN
            RETURN FALSE;
        END;
    END IF;

    -- Pattern 4: direct '<farm_uuid>/...'
    IF public.can_access_storage_farm_path(object_name) THEN
        RETURN TRUE;
    END IF;

    -- Pattern 5: User avatar / profile picture
    IF (v_prefix = 'avatar' OR v_prefix = 'profile') AND v_target = auth.uid()::TEXT THEN
        RETURN TRUE;
    END IF;

    RETURN FALSE;
END;
$$ LANGUAGE plpgsql STABLE SECURITY DEFINER
SET search_path = public, pg_temp;


-- -----------------------------------------------------------------------------
-- 3. ATOMIC BOOKING HOLD RPC (SERVER-SIDE PRICE SNAPSHOT & FARM ADMIN AUTHORIZATION)
-- -----------------------------------------------------------------------------
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID, TEXT, UUID);
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID, TEXT);
DROP FUNCTION IF EXISTS public.create_booking_hold(UUID);
DROP FUNCTION IF EXISTS public.create_booking_hold;

CREATE OR REPLACE FUNCTION public.create_booking_hold(
    p_goat_id UUID,
    p_notes TEXT DEFAULT NULL,
    p_customer_id UUID DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_effective_customer_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_booking RECORD;
    v_booking_code TEXT;
    v_is_caller_super_admin BOOLEAN := FALSE;
BEGIN
    -- 1. Determine effective customer ID
    IF auth.uid() IS NOT NULL THEN
        v_effective_customer_id := auth.uid();
    ELSIF p_customer_id IS NOT NULL THEN
        v_effective_customer_id := p_customer_id;
    ELSE
        RAISE EXCEPTION 'Authentication required to reserve livestock.';
    END IF;

    -- Check if Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_caller_super_admin
    FROM public.profiles
    WHERE id = v_effective_customer_id;

    -- 2. Fetch and lock target goat
    SELECT * INTO v_goat
    FROM public.goats
    WHERE id = p_goat_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 3. Fetch farm record
    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = v_goat.farm_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Associated farm record not found.';
    END IF;

    -- 4. Check OWN FARM restriction:
    -- Farm Admins/Breeders CANNOT book goats belonging to their OWN farm
    IF v_is_caller_super_admin IS NOT TRUE THEN
        IF v_farm.owner_id IS NOT NULL AND v_farm.owner_id = v_effective_customer_id THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;

        IF EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = v_effective_customer_id
              AND p.farm_id = v_farm.id
              AND p.role = 'FARM_ADMIN'
        ) THEN
            RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
        END IF;
    END IF;

    -- 5. Availability & Approval check
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin IS NOT TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval.';
    END IF;

    -- 6. Check for existing active bookings on this goat
    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = p_goat_id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    -- 7. Snapshot authoritative server-side price (applying discount if any)
    IF v_goat.discount_percentage IS NOT NULL AND v_goat.discount_percentage > 0 THEN
        v_effective_price := ROUND(v_goat.price * (1.0 - (v_goat.discount_percentage / 100.0)), 2);
    ELSE
        v_effective_price := v_goat.price;
    END IF;

    v_booking_code := 'AGF-' || UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6));

    -- 8. Insert booking record atomically
    INSERT INTO public.bookings (
        goat_id,
        farm_id,
        customer_id,
        status,
        booking_date,
        hold_expires_at,
        total_price,
        deposit_paid,
        customer_notes,
        booking_code
    ) VALUES (
        p_goat_id,
        v_goat.farm_id,
        v_effective_customer_id,
        'RESERVED',
        NOW(),
        NOW() + INTERVAL '24 hours',
        v_effective_price,
        0.00,
        p_notes,
        v_booking_code
    )
    RETURNING * INTO v_booking;

    -- 9. Transition goat availability status to RESERVED
    UPDATE public.goats
    SET status = 'RESERVED',
        updated_at = NOW()
    WHERE id = p_goat_id;

    -- 10. Return complete JSONB containing both 'id' and 'booking_id'
    RETURN jsonb_build_object(
        'id', v_booking.id,
        'booking_id', v_booking.id,
        'goat_id', v_booking.goat_id,
        'farm_id', v_booking.farm_id,
        'customer_id', v_booking.customer_id,
        'status', v_booking.status,
        'total_price', v_booking.total_price,
        'deposit_paid', v_booking.deposit_paid,
        'customer_notes', v_booking.customer_notes,
        'booking_code', v_booking.booking_code,
        'booking_date', v_booking.booking_date,
        'hold_expires_at', v_booking.hold_expires_at,
        'created_at', v_booking.created_at,
        'updated_at', v_booking.updated_at
    );
END;
$$;

REVOKE ALL ON FUNCTION public.create_booking_hold(UUID, TEXT, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.create_booking_hold(UUID, TEXT, UUID) TO authenticated;


-- -----------------------------------------------------------------------------
-- 4. SEQUENTIAL CODE SEQUENCES & INTEGRITY CHECKS
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS public.farm_code_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE IF NOT EXISTS public.goat_code_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE public.farms ADD COLUMN IF NOT EXISTS farm_code TEXT UNIQUE;
ALTER TABLE public.goats ADD COLUMN IF NOT EXISTS goat_code TEXT UNIQUE;

-- Safely backfill any missing farm codes
DO $$
DECLARE
    r RECORD;
    v_seq INT := 1;
BEGIN
    FOR r IN 
        SELECT id FROM public.farms 
        WHERE farm_code IS NULL OR TRIM(farm_code) = '' 
        ORDER BY created_at ASC, id ASC 
    LOOP
        UPDATE public.farms 
        SET farm_code = 'FARM-' || LPAD(v_seq::text, 3, '0') 
        WHERE id = r.id;
        v_seq := v_seq + 1;
    END LOOP;

    SELECT COALESCE(MAX(NULLIF(regexp_replace(farm_code, '[^0-9]', '', 'g'), '')::INT), 0) + 1 
    INTO v_seq 
    FROM public.farms;

    IF v_seq < 1 THEN v_seq := 1; END IF;
    PERFORM setval('public.farm_code_seq', v_seq, false);
END $$;

-- Safely backfill any missing goat codes
DO $$
DECLARE
    r RECORD;
    v_seq INT := 1;
BEGIN
    FOR r IN 
        SELECT id FROM public.goats 
        WHERE goat_code IS NULL OR TRIM(goat_code) = '' 
        ORDER BY created_at ASC, id ASC 
    LOOP
        UPDATE public.goats 
        SET goat_code = 'GOAT-' || LPAD(v_seq::text, 3, '0') 
        WHERE id = r.id;
        v_seq := v_seq + 1;
    END LOOP;

    SELECT COALESCE(MAX(NULLIF(regexp_replace(goat_code, '[^0-9]', '', 'g'), '')::INT), 0) + 1 
    INTO v_seq 
    FROM public.goats;

    IF v_seq < 1 THEN v_seq := 1; END IF;
    PERFORM setval('public.goat_code_seq', v_seq, false);
END $$;
