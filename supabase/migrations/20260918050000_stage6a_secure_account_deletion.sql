-- =============================================================================
-- AMMAL FARM HYPERLOCAL LIVESTOCK MARKETPLACE - MUMBAI PRODUCTION SETUP
-- MIGRATION: 20260918050000_stage6a_secure_account_deletion.sql
-- STAGE 6A: SECURE SERVER-AUTHORITATIVE ACCOUNT DELETION
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. FOREIGN KEY HARDENING & RELAXATION FOR ANONYMIZED RETENTION
-- -----------------------------------------------------------------------------

-- 1.1 BOOKINGS: Allow customer_id to be NULL upon account deletion (ON DELETE SET NULL)
ALTER TABLE public.bookings ALTER COLUMN customer_id DROP NOT NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'bookings_customer_id_fkey') THEN
        ALTER TABLE public.bookings DROP CONSTRAINT bookings_customer_id_fkey;
    END IF;
    ALTER TABLE public.bookings
        ADD CONSTRAINT bookings_customer_id_fkey
        FOREIGN KEY (customer_id) REFERENCES public.profiles(id) ON DELETE SET NULL;
END $$;

-- 1.2 FARMS: Allow owner_id to be NULL upon account deletion (ON DELETE SET NULL)
ALTER TABLE public.farms ALTER COLUMN owner_id DROP NOT NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'farms_owner_id_fkey') THEN
        ALTER TABLE public.farms DROP CONSTRAINT farms_owner_id_fkey;
    END IF;
    ALTER TABLE public.farms
        ADD CONSTRAINT farms_owner_id_fkey
        FOREIGN KEY (owner_id) REFERENCES public.profiles(id) ON DELETE SET NULL;
END $$;

-- 1.3 REPORTS: Allow reporter_id to be NULL upon account deletion (ON DELETE SET NULL)
ALTER TABLE public.reports ALTER COLUMN reporter_id DROP NOT NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reports_reporter_id_fkey') THEN
        ALTER TABLE public.reports DROP CONSTRAINT reports_reporter_id_fkey;
    END IF;
    ALTER TABLE public.reports
        ADD CONSTRAINT reports_reporter_id_fkey
        FOREIGN KEY (reporter_id) REFERENCES public.profiles(id) ON DELETE SET NULL;
END $$;

-- 1.4 LISTING PAYMENTS: Allow payer_id to be NULL upon account deletion (ON DELETE SET NULL)
ALTER TABLE public.listing_payments ALTER COLUMN payer_id DROP NOT NULL;
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'listing_payments_payer_id_fkey') THEN
        ALTER TABLE public.listing_payments DROP CONSTRAINT listing_payments_payer_id_fkey;
    END IF;
    ALTER TABLE public.listing_payments
        ADD CONSTRAINT listing_payments_payer_id_fkey
        FOREIGN KEY (payer_id) REFERENCES public.profiles(id) ON DELETE SET NULL;
END $$;

-- 1.5 REVIEWS: Allow customer_id to be NULL upon account deletion (ON DELETE SET NULL)
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'reviews' AND column_name = 'customer_id') THEN
        ALTER TABLE public.reviews ALTER COLUMN customer_id DROP NOT NULL;
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'reviews_customer_id_fkey') THEN
            ALTER TABLE public.reviews DROP CONSTRAINT reviews_customer_id_fkey;
        END IF;
        ALTER TABLE public.reviews
            ADD CONSTRAINT reviews_customer_id_fkey
            FOREIGN KEY (customer_id) REFERENCES public.profiles(id) ON DELETE SET NULL;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 2. TRIGGER UPDATES: ALLOW ACCOUNT DELETION TRANSACTION BYPASS
-- -----------------------------------------------------------------------------

-- 2.1 Update enforce_farm_metadata_integrity to permit account deletion deactivation
CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_auth_uid UUID := auth.uid();
    v_is_admin BOOLEAN := FALSE;
BEGIN
    -- Permit internal server-authoritative account deletion procedure
    IF current_setting('ammal.account_deletion', true) = 'true' THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- Allow direct DB administrative scripts / SQL Editor (auth.uid() is NULL)
    IF v_auth_uid IS NULL THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- Verify caller role directly via profiles
    SELECT (role = 'SUPER_ADMIN') INTO v_is_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    IF v_is_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    -- Non-Super Admins (FARM_ADMIN or CUSTOMER) cannot alter critical metadata
    IF TG_OP = 'INSERT' THEN
        NEW.status := 'PENDING';
        NEW.is_ammal_own_farm := FALSE;
        NEW.goat_listing_limit := 10;
        NEW.owner_id := v_auth_uid;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Only Super Admin can modify farm status.';
        END IF;

        IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
            RAISE EXCEPTION 'is_ammal_own_farm cannot be altered by partner farms.';
        END IF;

        IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
            RAISE EXCEPTION 'Listing limit can only be adjusted by Super Admin.';
        END IF;

        IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
            RAISE EXCEPTION 'Farm ownership cannot be transferred directly.';
        END IF;
    END IF;

    NEW.updated_at := NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- 2.2 Update enforce_booking_price_snapshot to permit customer anonymization on deletion
CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_current_goat_price NUMERIC(12, 2);
    v_goat_farm_id UUID;
    v_auth_uid UUID := auth.uid();
    v_is_super_admin BOOLEAN := FALSE;
    v_is_farm_owner BOOLEAN := FALSE;
BEGIN
    -- Permit internal server-authoritative account deletion procedure
    IF current_setting('ammal.account_deletion', true) = 'true' THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    ELSE
        v_is_super_admin := TRUE;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT price, farm_id INTO v_current_goat_price, v_goat_farm_id
        FROM public.goats
        WHERE id = NEW.goat_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat with id % not found.', NEW.goat_id;
        END IF;

        IF v_auth_uid IS NOT NULL AND NOT v_is_super_admin THEN
            SELECT EXISTS (
                SELECT 1 FROM public.farms
                WHERE id = v_goat_farm_id AND owner_id = v_auth_uid
            ) INTO v_is_farm_owner;

            IF v_is_farm_owner THEN
                RAISE EXCEPTION 'Farm administrators are not permitted to reserve or book goats listed by their own farm.';
            END IF;
        END IF;

        NEW.total_price := v_current_goat_price;
        NEW.farm_id := v_goat_farm_id;
        IF NEW.hold_expires_at IS NULL THEN
            NEW.hold_expires_at := NOW() + INTERVAL '24 hours';
        END IF;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        IF v_is_super_admin THEN
            NEW.updated_at := NOW();
            RETURN NEW;
        END IF;

        IF NEW.total_price IS DISTINCT FROM OLD.total_price THEN
            RAISE EXCEPTION 'total_price cannot be modified once booking is placed.';
        END IF;

        IF NEW.goat_id IS DISTINCT FROM OLD.goat_id THEN
            RAISE EXCEPTION 'Goat cannot be changed on an existing booking.';
        END IF;

        IF NEW.customer_id IS DISTINCT FROM OLD.customer_id THEN
            RAISE EXCEPTION 'Customer cannot be changed on an existing booking.';
        END IF;

        IF NEW.farm_id IS DISTINCT FROM OLD.farm_id THEN
            RAISE EXCEPTION 'Farm cannot be changed on an existing booking.';
        END IF;

        IF NEW.hold_expires_at > OLD.hold_expires_at THEN
            RAISE EXCEPTION 'Booking hold expiration cannot be extended.';
        END IF;

        IF OLD.status IN ('CANCELLED', 'EXPIRED', 'COMPLETED') AND NEW.status != OLD.status THEN
            RAISE EXCEPTION 'Terminal booking status (%) cannot be altered.', OLD.status;
        END IF;

        IF v_auth_uid IS NOT NULL AND OLD.customer_id = v_auth_uid THEN
            IF NEW.status NOT IN (OLD.status, 'CANCELLED') THEN
                RAISE EXCEPTION 'Customers may only transition active bookings to CANCELLED.';
            END IF;
        END IF;

        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- -----------------------------------------------------------------------------
-- 3. SERVER-AUTHORITATIVE ACCOUNT DELETION FUNCTION
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.delete_user_account()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_user_role public.user_role;
    v_farm_record RECORD;
BEGIN
    -- 1. Authentication check
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required. No active session identified.';
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
    -- (a) For active pending/reserved bookings, release goat hold back to AVAILABLE
    UPDATE public.goats g
    SET status = 'AVAILABLE',
        updated_at = NOW()
    FROM public.bookings b
    WHERE b.customer_id = v_user_id
      AND b.goat_id = g.id
      AND b.status IN ('PENDING', 'RESERVED')
      AND g.status = 'RESERVED';

    -- (b) Cancel those active pending/reserved bookings
    UPDATE public.bookings
    SET status = 'CANCELLED',
        cancelled_at = NOW(),
        customer_notes = '[Account deleted by customer]',
        customer_id = NULL,
        updated_at = NOW()
    WHERE customer_id = v_user_id
      AND status IN ('PENDING', 'RESERVED');

    -- (c) Anonymize completed, confirmed, or expired past bookings (preserve financial/audit history)
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

    -- 12. Storage cleanup (avatars) if applicable
    BEGIN
        DELETE FROM storage.objects
        WHERE bucket_id = 'avatars'
          AND (owner = v_user_id OR name LIKE (v_user_id::text || '/%') OR name LIKE (v_user_id::text || '.%'));
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    -- 13. Delete from auth.users (cascades to public.profiles)
    DELETE FROM auth.users WHERE id = v_user_id;

    -- Reset session config
    PERFORM set_config('ammal.account_deletion', 'false', true);

    RETURN jsonb_build_object(
        'success', true,
        'message', 'User account, profile, and associated personal data have been permanently deleted.'
    );
END;
$$;

-- Revoke execute from public and grant execute strictly to authenticated users
REVOKE ALL ON FUNCTION public.delete_user_account() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_user_account() TO authenticated;
