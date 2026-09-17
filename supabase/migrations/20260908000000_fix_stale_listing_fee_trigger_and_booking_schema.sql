-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 11F / FIX STALE LISTING FEE TRIGGER & BOOKING SCHEMA
-- 1. Fixes enforce_goat_listing_fee_rule(): removes obsolete references to
--    OLD.listing_fee_amount and NEW.listing_fee_amount on public.goats.
--    The goats table stores livestock catalog details; listing fee payments are
--    tracked authoritatively in public.listing_payments.
-- 2. Fixes enforce_booking_amount_immutability(): uses authoritative column
--    total_price on public.bookings instead of obsolete 'amount'.
-- 3. Fixes notify_and_audit_goat_moderation(): removes invalid enum cast for
--    'REJECTED' against goat_status enum.
-- =============================================================================

-- 1. Fix trigger function enforce_goat_listing_fee_rule on public.goats
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    -- Check if current user is Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    -- Determine if the target farm is Ammal Farm
    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            -- Ammal Farm: Listings are auto-approved by administrator
            NEW.is_approved_by_admin := TRUE;
        ELSE
            -- Partner Farm: Pending administrator approval
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Prevent untrusted non-admin users from self-approving their listings
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger on goats
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
BEFORE INSERT OR UPDATE ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();

-- 2. Fix enforce_booking_amount_immutability on public.bookings (use total_price, not amount)
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger on bookings
DROP TRIGGER IF EXISTS tr_enforce_booking_amount ON public.bookings;
CREATE TRIGGER tr_enforce_booking_amount
BEFORE UPDATE ON public.bookings
FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_amount_immutability();

-- 3. Fix notify_and_audit_goat_moderation to prevent invalid enum comparisons
CREATE OR REPLACE FUNCTION public.notify_and_audit_goat_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_farm_owner_id UUID;
    v_farm_name TEXT;
    v_super_admin_id UUID;
BEGIN
    -- Fetch farm owner and farm name
    SELECT owner_id, name INTO v_farm_owner_id, v_farm_name
    FROM public.farms
    WHERE id = NEW.farm_id;

    -- Fetch a super admin ID for auditing
    SELECT id INTO v_super_admin_id
    FROM public.profiles
    WHERE role = 'SUPER_ADMIN'
    LIMIT 1;

    -- Listing approved
    IF NEW.is_approved_by_admin = TRUE AND (OLD.is_approved_by_admin IS DISTINCT FROM TRUE) THEN
        IF v_farm_owner_id IS NOT NULL THEN
            INSERT INTO public.notifications (user_id, title, body, link_type, link_id, is_read)
            VALUES (
                v_farm_owner_id,
                'Goat Listing Approved! 🎉',
                format('Your goat listing "%s" has been approved by Super Admin and is now live on the marketplace.', NEW.name),
                'FARM_LISTINGS',
                NEW.id::text,
                FALSE
            );
        END IF;

        IF v_super_admin_id IS NOT NULL THEN
            INSERT INTO public.audit_logs (user_id, action, resource_type, resource_id, old_values, new_values, notes)
            VALUES (v_super_admin_id, 'APPROVE_LISTING', 'GOAT', NEW.id, '{"is_approved": false}'::jsonb, '{"is_approved": true}'::jsonb, 'Super Admin approved goat listing');
        END IF;

    -- Listing unapproved / rejected
    ELSIF NEW.is_approved_by_admin = FALSE AND OLD.is_approved_by_admin = TRUE THEN
        IF v_farm_owner_id IS NOT NULL THEN
            INSERT INTO public.notifications (user_id, title, body, link_type, link_id, is_read)
            VALUES (
                v_farm_owner_id,
                'Goat Listing Update ⚠️',
                format('Your goat listing "%s" was set to unapproved by Super Admin.', NEW.name),
                'FARM_LISTINGS',
                NEW.id::text,
                FALSE
            );
        END IF;

        IF v_super_admin_id IS NOT NULL THEN
            INSERT INTO public.audit_logs (user_id, action, resource_type, resource_id, old_values, new_values, notes)
            VALUES (v_super_admin_id, 'UNAPPROVE_LISTING', 'GOAT', NEW.id, '{"is_approved": true}'::jsonb, '{"is_approved": false}'::jsonb, 'Super Admin unapproved goat listing');
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Recreate trigger on goats
DROP TRIGGER IF EXISTS tr_notify_goat_moderation ON public.goats;
CREATE TRIGGER tr_notify_goat_moderation
AFTER UPDATE OF is_approved_by_admin, status ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_goat_moderation();
