-- =============================================================================
-- AMMAL FARM / ADU SANTHAI - BATCH 3 MIGRATION
-- Farm Management, Listing Slot Editing & Safe Account Deletion Hardening
-- Migration: 20261020050000_batch3_farm_management_and_slot_rules.sql
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. HARDEN admin_update_farm_listing_limit FUNCTION
-- -----------------------------------------------------------------------------
-- Enables Super Admin to set a farm's total goat listing limit with strict validation.
-- Never reduces total slots below the number of slots already consumed by active listings.

CREATE OR REPLACE FUNCTION public.admin_update_farm_listing_limit(
    p_farm_id UUID,
    p_new_limit INTEGER
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_caller_role public.user_role;
    v_stored_consumed INT := 0;
    v_active_goats INT := 0;
    v_min_consumed INT := 0;
    v_updated_farm RECORD;
BEGIN
    -- Authorization check: Caller MUST be SUPER_ADMIN
    SELECT role INTO v_caller_role
    FROM public.profiles
    WHERE id = auth.uid();

    IF v_caller_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Security Exception: Only Super Admin can modify farm listing limits.';
    END IF;

    -- Validate input bounds
    IF p_new_limit IS NULL OR p_new_limit < 0 OR p_new_limit > 10000 THEN
        RAISE EXCEPTION 'Invalid listing limit (%). Value must be between 0 and 10000.', p_new_limit;
    END IF;

    -- Retrieve current stored consumed slots and count active listings
    SELECT COALESCE(consumed_listing_slots, 0) INTO v_stored_consumed
    FROM public.farms
    WHERE id = p_farm_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm with ID % was not found.', p_farm_id;
    END IF;

    SELECT COUNT(*) INTO v_active_goats
    FROM public.goats
    WHERE farm_id = p_farm_id
      AND is_active = TRUE
      AND availability_status NOT IN ('SOLD', 'UNAVAILABLE')
      AND approval_status != 'REJECTED';

    v_min_consumed := GREATEST(v_stored_consumed, v_active_goats);

    -- ENFORCE RULE: Total slots can NEVER be reduced below consumed slots
    IF p_new_limit < v_min_consumed THEN
        RAISE EXCEPTION 'Cannot reduce total slots (% slots) below consumed slots (% slots already used).', p_new_limit, v_min_consumed;
    END IF;

    -- Perform total slot update
    UPDATE public.farms
    SET
        goat_listing_limit = p_new_limit,
        updated_at = NOW()
    WHERE id = p_farm_id
    RETURNING id, name, status, goat_listing_limit, consumed_listing_slots, updated_at
    INTO v_updated_farm;

    RETURN jsonb_build_object(
        'success', true,
        'id', v_updated_farm.id,
        'name', v_updated_farm.name,
        'status', v_updated_farm.status,
        'goat_listing_limit', v_updated_farm.goat_listing_limit,
        'consumed_listing_slots', v_updated_farm.consumed_listing_slots,
        'updated_at', v_updated_farm.updated_at
    );
END;
$$;

REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) TO authenticated;

-- -----------------------------------------------------------------------------
-- 2. HARDEN delete_user_account WITH SAFE FARM SUSPENSION & PRESERVATION
-- -----------------------------------------------------------------------------
-- When a Farm Admin account is deleted:
-- - Suspend the farm (status = 'SUSPENDED').
-- - Unlink owner_id = NULL safely.
-- - Prevent new listings or farm-management actions.
-- - Preserve farm, goat, booking, listing payment, and historical data intact.

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
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required.';
    END IF;

    SELECT (role = 'SUPER_ADMIN') INTO v_is_caller_super_admin
    FROM public.profiles
    WHERE id = v_caller_id;

    IF p_user_id IS NULL OR p_user_id = v_caller_id THEN
        v_target_user_id := v_caller_id;
    ELSE
        IF NOT COALESCE(v_is_caller_super_admin, FALSE) THEN
            RAISE EXCEPTION 'Unauthorized account deletion. Only Super Admins can delete other user accounts.';
        END IF;
        v_target_user_id := p_user_id;
    END IF;

    SELECT role INTO v_target_role
    FROM public.profiles
    WHERE id = v_target_user_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'User profile not found.';
    END IF;

    -- Protect central Ammal Farm owner
    IF EXISTS (
        SELECT 1 FROM public.farms
        WHERE owner_id = v_target_user_id AND is_ammal_own_farm = TRUE
    ) THEN
        RAISE EXCEPTION 'The owner account of the central Ammal Farm cannot be deleted.';
    END IF;

    PERFORM set_config('ammal.account_deletion', 'true', true);

    -- Farm Admin processing
    FOR v_farm IN SELECT id, name FROM public.farms WHERE owner_id = v_target_user_id LOOP
        SELECT COUNT(*) INTO v_active_farm_bookings_count
        FROM public.bookings
        WHERE (farm_id = v_farm.id OR goat_id IN (SELECT id FROM public.goats WHERE farm_id = v_farm.id))
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED');

        IF v_active_farm_bookings_count > 0 THEN
            RAISE EXCEPTION 'Cannot delete account while farm has active reservations (% active).', v_active_farm_bookings_count;
        END IF;

        -- Deactivate active listings
        UPDATE public.goats
        SET status = 'INACTIVE',
            updated_at = NOW()
        WHERE farm_id = v_farm.id
          AND status != 'SOLD';

        -- Unlink user profile references
        UPDATE public.profiles
        SET farm_id = NULL
        WHERE farm_id = v_farm.id;

        -- Suspend farm and unlink owner to preserve all historical records safely
        UPDATE public.farms
        SET status = 'SUSPENDED',
            owner_id = NULL,
            updated_at = NOW()
        WHERE id = v_farm.id;
    END LOOP;

    -- Customer active bookings cancellation & release holds
    FOR v_booking IN
        SELECT b.id AS booking_id, b.goat_id, g.status AS goat_status
        FROM public.bookings b
        JOIN public.goats g ON g.id = b.goat_id
        WHERE b.customer_id = v_target_user_id
          AND b.status IN ('PENDING', 'RESERVED')
    LOOP
        UPDATE public.bookings
        SET status = 'CANCELLED',
            cancelled_at = NOW(),
            customer_notes = '[Account closed by user]',
            customer_id = NULL,
            updated_at = NOW()
        WHERE id = v_booking.booking_id;

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

    -- Anonymize historical customer bookings
    UPDATE public.bookings
    SET customer_notes = NULL,
        customer_id = NULL,
        updated_at = NOW()
    WHERE customer_id = v_target_user_id;

    -- Wishlist & Notifications
    DELETE FROM public.wishlist WHERE user_id = v_target_user_id;
    DELETE FROM public.notifications WHERE user_id = v_target_user_id;

    -- Reports & Listing Payments & Audit Logs
    DELETE FROM public.reports WHERE reporter_id = v_target_user_id AND status = 'PENDING';
    UPDATE public.reports SET reporter_id = NULL WHERE reporter_id = v_target_user_id;
    UPDATE public.reports SET resolved_by = NULL WHERE resolved_by = v_target_user_id;
    UPDATE public.listing_payments SET payer_id = NULL WHERE payer_id = v_target_user_id;
    UPDATE public.audit_logs SET actor_id = NULL WHERE actor_id = v_target_user_id;

    -- Delete Profile and Auth user
    DELETE FROM public.profiles WHERE id = v_target_user_id;
    DELETE FROM auth.users WHERE id = v_target_user_id;

    PERFORM set_config('ammal.account_deletion', 'false', true);

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Account deleted successfully. Farm and transaction history preserved and farm suspended.'
    );
EXCEPTION WHEN OTHERS THEN
    PERFORM set_config('ammal.account_deletion', 'false', true);
    RETURN jsonb_build_object(
        'success', false,
        'message', SQLERRM
    );
END;
$$;

REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM anon;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated;

-- -----------------------------------------------------------------------------
-- 3. ENFORCE FARM SUSPENSION ON LISTING CREATION AT DATABASE / RLS LEVEL
-- -----------------------------------------------------------------------------

DROP POLICY IF EXISTS "goats_insert_policy" ON public.goats;
CREATE POLICY "goats_insert_policy" ON public.goats
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
              AND f.status IN ('APPROVED', 'VERIFIED')
              AND f.status != 'SUSPENDED'
        )
    );

DROP POLICY IF EXISTS "goats_update_policy" ON public.goats;
CREATE POLICY "goats_update_policy" ON public.goats
    FOR UPDATE TO authenticated
    USING (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
              AND f.status != 'SUSPENDED'
        )
    )
    WITH CHECK (
        public.is_super_admin()
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = goats.farm_id::text
              AND f.owner_id::text = auth.uid()::text
              AND f.status != 'SUSPENDED'
        )
    );
