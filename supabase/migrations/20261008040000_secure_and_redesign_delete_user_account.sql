-- =============================================================================
-- AMMAL FARM PLATFORM - SECURE AND REDESIGN public.delete_user_account()
-- Migration: 20261008040000_secure_and_redesign_delete_user_account.sql
--
-- Requirements:
-- 1. Strictly requires authenticated session (auth.uid() IS NOT NULL).
-- 2. Non-super-admin users can ONLY delete their own account (target_user_id = auth.uid()).
--    If p_user_id is supplied and differs from auth.uid(), require Super Admin.
-- 3. Reject deletion if the user owns the central Ammal Farm (is_ammal_own_farm = TRUE).
-- 4. PRESERVE TRANSACTION AND BUSINESS HISTORY:
--    - Never delete public.bookings, public.goats, or public.farms.
--    - For customer's active bookings (PENDING, RESERVED), cancel them and release
--      the goat hold back to AVAILABLE (only if not SOLD and no other active booking).
--    - Past bookings (CONFIRMED, COMPLETED, CANCELLED, EXPIRED) are preserved;
--      customer_id is set to NULL, customer_notes anonymized.
--    - For Farm Admin's farm:
--      - If farm has active bookings (PENDING, RESERVED, CONFIRMED), reject deletion
--        with clear message: "Cannot delete account while farm has active bookings. Resolve or complete pending/confirmed reservations first."
--      - Otherwise, retain the farm and historical goats/sales.
--      - Active available goats are deactivated (status = 'INACTIVE') so they are not orphaned.
--      - SOLD goats remain permanently SOLD.
--      - Farm metadata is safely anonymized/suspended: status = 'SUSPENDED', owner_id = NULL,
--        contact_phone = 'REDACTED', contact_email = NULL, description = 'Farm account closed by owner.'
-- 5. Anonymize/Clean personal records:
--    - Delete wishlist items and notifications belonging to the user.
--    - Delete pending reports filed by the user; anonymize resolved reports.
--    - Anonymize payer_id on listing_payments (retain amount, receipt, etc.).
--    - Anonymize actor_id on audit_logs.
-- 6. Relax FK constraints to ON DELETE SET NULL where needed so profile/auth deletion succeeds.
-- 7. Delete auth.users (cascades to public.profiles).
-- 8. REVOKE EXECUTE FROM anon, PUBLIC; GRANT EXECUTE TO authenticated.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Step 1: Ensure foreign key constraints permit NULL / SET NULL on user deletion
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

-- -----------------------------------------------------------------------------
-- Step 2: Ensure enforce_farm_metadata_integrity bypasses during account deletion
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_auth_uid UUID := auth.uid();
    v_is_super_admin BOOLEAN := FALSE;
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

    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = v_auth_uid;

    IF v_is_super_admin IS TRUE THEN
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- If an authenticated non-super-admin user registers a farm, force safe defaults:
        NEW.is_ammal_own_farm := FALSE;
        NEW.goat_listing_limit := 0;
        NEW.consumed_listing_slots := 0;
        NEW.status := 'PENDING'::public.farm_status;
        NEW.owner_id := v_auth_uid;
        NEW.updated_at := NOW();
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Non-Super Admins are strictly prohibited from changing listing limits, consumed slots, ownership, status, or Ammal Farm flag
        IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
            RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
        END IF;

        IF OLD.consumed_listing_slots IS DISTINCT FROM NEW.consumed_listing_slots THEN
            RAISE EXCEPTION 'Consumed listing slots cannot be manually modified.';
        END IF;

        IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
            RAISE EXCEPTION 'Farm ownership cannot be modified.';
        END IF;

        IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
            RAISE EXCEPTION 'Ammal Farm designation cannot be modified.';
        END IF;

        IF OLD.status IS DISTINCT FROM NEW.status THEN
            RAISE EXCEPTION 'Only Super Admin can modify farm status.';
        END IF;

        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- -----------------------------------------------------------------------------
-- Step 3: Recreate public.delete_user_account()
-- -----------------------------------------------------------------------------

DROP FUNCTION IF EXISTS public.delete_user_account(UUID);
DROP FUNCTION IF EXISTS public.delete_user_account();

CREATE OR REPLACE FUNCTION public.delete_user_account(p_user_id UUID DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_caller_id UUID := auth.uid();
    v_target_user_id UUID;
    v_target_role public.user_role;
    v_is_caller_super_admin BOOLEAN := FALSE;
    v_farm RECORD;
    v_active_farm_bookings_count INT := 0;
    v_booking RECORD;
    v_other_active_count INT;
BEGIN
    -- 1. Authentication check: caller MUST have an authenticated session
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required.';
    END IF;

    -- Determine caller's super admin status
    SELECT (role = 'SUPER_ADMIN') INTO v_is_caller_super_admin
    FROM public.profiles
    WHERE id = v_caller_id;

    -- 2. Target user resolution & authorization
    IF p_user_id IS NULL OR p_user_id = v_caller_id THEN
        v_target_user_id := v_caller_id;
    ELSE
        -- Calling on behalf of another user is ONLY permitted for Super Admins
        IF NOT COALESCE(v_is_caller_super_admin, FALSE) THEN
            RAISE EXCEPTION 'Unauthorized account deletion request. Only Super Admins can delete other accounts.';
        END IF;
        v_target_user_id := p_user_id;
    END IF;

    -- 3. Verify target user exists
    SELECT role INTO v_target_role
    FROM public.profiles
    WHERE id = v_target_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'User profile not found.';
    END IF;

    -- 4. Protected Central Ammal Farm account check
    IF EXISTS (
        SELECT 1 FROM public.farms
        WHERE owner_id = v_target_user_id AND is_ammal_own_farm = TRUE
    ) THEN
        RAISE EXCEPTION 'The owner account of the central Ammal Farm cannot be deleted. Transfer ownership before closing this account.';
    END IF;

    -- Set local session config to allow triggers to permit deletion updates
    PERFORM set_config('ammal.account_deletion', 'true', true);

    -- 5. Farm Admin specific processing
    FOR v_farm IN SELECT id, name FROM public.farms WHERE owner_id = v_target_user_id LOOP
        -- Check for active bookings on this farm or any of its goats
        SELECT COUNT(*) INTO v_active_farm_bookings_count
        FROM public.bookings
        WHERE (farm_id = v_farm.id OR goat_id IN (SELECT id FROM public.goats WHERE farm_id = v_farm.id))
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED');

        IF v_active_farm_bookings_count > 0 THEN
            RAISE EXCEPTION 'Cannot delete account while farm has active bookings (% active). Resolve or complete pending/confirmed reservations first.', v_active_farm_bookings_count;
        END IF;

        -- Deactivate remaining active listings so they are not orphaned in the marketplace.
        -- SOLD goats remain permanently SOLD.
        UPDATE public.goats
        SET status = 'INACTIVE',
            updated_at = NOW()
        WHERE farm_id = v_farm.id
          AND status != 'SOLD';

        -- Unlink user profile references to this farm
        UPDATE public.profiles
        SET farm_id = NULL
        WHERE farm_id = v_farm.id;

        -- Preserve farm record for historical/transaction integrity:
        -- Suspend the farm and anonymize owner/contact data.
        UPDATE public.farms
        SET status = 'SUSPENDED',
            owner_id = NULL,
            contact_phone = 'REDACTED',
            contact_email = NULL,
            description = 'Farm account closed by owner.',
            tagline = NULL,
            logo_url = NULL,
            banner_url = NULL,
            updated_at = NOW()
        WHERE id = v_farm.id;
    END LOOP;

    -- 6. Customer bookings processing (Preserve booking records & transaction history)
    -- 6a. For active bookings placed by this user, cancel them and release goat holds
    FOR v_booking IN
        SELECT b.id AS booking_id, b.goat_id, g.status AS goat_status
        FROM public.bookings b
        JOIN public.goats g ON g.id = b.goat_id
        WHERE b.customer_id = v_target_user_id
          AND b.status IN ('PENDING', 'RESERVED')
    LOOP
        -- Cancel the customer's active booking
        UPDATE public.bookings
        SET status = 'CANCELLED',
            cancelled_at = NOW(),
            customer_notes = '[Account deleted by customer]',
            customer_id = NULL,
            updated_at = NOW()
        WHERE id = v_booking.booking_id;

        -- If goat was RESERVED and is not SOLD, check if any other active booking holds it
        IF v_booking.goat_status IS DISTINCT FROM 'SOLD' THEN
            SELECT COUNT(*) INTO v_other_active_count
            FROM public.bookings
            WHERE goat_id = v_booking.goat_id
              AND id != v_booking.booking_id
              AND status IN ('PENDING', 'RESERVED', 'CONFIRMED');

            IF v_other_active_count = 0 THEN
                UPDATE public.goats
                SET status = 'AVAILABLE',
                    updated_at = NOW()
                WHERE id = v_booking.goat_id
                  AND status != 'SOLD';
            END IF;
        END IF;
    END LOOP;

    -- 6b. Anonymize past completed, confirmed, expired, or cancelled bookings
    UPDATE public.bookings
    SET customer_notes = NULL,
        customer_id = NULL,
        updated_at = NOW()
    WHERE customer_id = v_target_user_id;

    -- 7. Wishlist removal
    DELETE FROM public.wishlist
    WHERE user_id = v_target_user_id;

    -- 8. Notifications removal
    DELETE FROM public.notifications
    WHERE user_id = v_target_user_id;

    -- 9. Reports: delete pending reports submitted by user; anonymize resolved reports
    DELETE FROM public.reports
    WHERE reporter_id = v_target_user_id AND status = 'PENDING';

    UPDATE public.reports
    SET reporter_id = NULL
    WHERE reporter_id = v_target_user_id;

    UPDATE public.reports
    SET resolved_by = NULL
    WHERE resolved_by = v_target_user_id;

    -- 10. Listing Payments: preserve receipts and financial amounts, anonymize payer_id
    UPDATE public.listing_payments
    SET payer_id = NULL
    WHERE payer_id = v_target_user_id;

    -- 11. Audit logs: anonymize actor_id
    UPDATE public.audit_logs
    SET actor_id = NULL
    WHERE actor_id = v_target_user_id;

    -- 12. Storage cleanup (avatars) if applicable
    BEGIN
        DELETE FROM storage.objects
        WHERE bucket_id = 'avatars'
          AND (owner = v_target_user_id OR name LIKE (v_target_user_id::text || '/%') OR name LIKE (v_target_user_id::text || '.%'));
    EXCEPTION WHEN OTHERS THEN
        NULL;
    END;

    -- 13. Delete from auth.users (cascades to public.profiles)
    -- Explicitly delete public.profiles first in case cascade is not immediate
    DELETE FROM public.profiles
    WHERE id = v_target_user_id;

    DELETE FROM auth.users
    WHERE id = v_target_user_id;

    -- Reset session config
    PERFORM set_config('ammal.account_deletion', 'false', true);

    RETURN jsonb_build_object(
        'success', true,
        'message', 'User account, profile, and personal data have been deleted. Transaction and business history preserved.'
    );
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config('ammal.account_deletion', 'false', true);
    RETURN jsonb_build_object(
        'success', false,
        'message', SQLERRM
    );
END;
$$;

-- -----------------------------------------------------------------------------
-- Step 4: Strict permissions on delete_user_account
-- -----------------------------------------------------------------------------
REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM anon;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated;
