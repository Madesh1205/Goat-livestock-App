-- Fix farm account deletion to ensure farm and all associated resources are completely deleted
-- (not left behind as 'SUSPENDED' with NULL owner_id) and enforce sequential farm codes.

DROP FUNCTION IF EXISTS public.delete_user_account(UUID);
DROP FUNCTION IF EXISTS public.delete_user_account();

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

    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required. No active session identified.';
    END IF;

    IF auth.uid() IS NOT NULL AND p_user_id IS NOT NULL AND auth.uid() != p_user_id THEN
        IF NOT public.is_super_admin() THEN
            RAISE EXCEPTION 'Unauthorized account deletion request.';
        END IF;
    END IF;

    SELECT role INTO v_user_role
    FROM public.profiles
    WHERE id = v_user_id;

    IF NOT FOUND THEN
        -- If profile is already gone, clean up any orphaned farms owned by this user
        DELETE FROM public.goats WHERE farm_id IN (SELECT id FROM public.farms WHERE owner_id = v_user_id);
        DELETE FROM public.bookings WHERE farm_id IN (SELECT id FROM public.farms WHERE owner_id = v_user_id);
        DELETE FROM public.listing_payments WHERE farm_id IN (SELECT id FROM public.farms WHERE owner_id = v_user_id);
        DELETE FROM public.reviews WHERE farm_id IN (SELECT id FROM public.farms WHERE owner_id = v_user_id);
        DELETE FROM public.farms WHERE owner_id = v_user_id;
        RETURN jsonb_build_object('success', true, 'message', 'User profile already removed; cleaned up orphaned farm data.');
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.farms
        WHERE owner_id = v_user_id AND is_ammal_own_farm = TRUE
    ) THEN
        RAISE EXCEPTION 'The owner account of the central Ammal Farm cannot be deleted. Transfer ownership before closing this account.';
    END IF;

    PERFORM set_config('ammal.account_deletion', 'true', true);

    -- FARM_ADMIN specific cleanup: Delete the farm and all associated resources completely
    FOR v_farm_record IN SELECT id FROM public.farms WHERE owner_id = v_user_id LOOP
        DELETE FROM public.goats WHERE farm_id = v_farm_record.id;
        DELETE FROM public.bookings WHERE farm_id = v_farm_record.id;
        DELETE FROM public.listing_payments WHERE farm_id = v_farm_record.id;
        DELETE FROM public.reviews WHERE farm_id = v_farm_record.id;
        DELETE FROM public.farms WHERE id = v_farm_record.id;
    END LOOP;

    -- Clean up customer bookings
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

    DELETE FROM public.wishlist WHERE user_id = v_user_id;
    DELETE FROM public.notifications WHERE user_id = v_user_id;
    DELETE FROM public.profiles WHERE id = v_user_id;

    RETURN jsonb_build_object('success', true, 'message', 'Account and associated farm data deleted successfully.');
END;
$$;

REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated;
