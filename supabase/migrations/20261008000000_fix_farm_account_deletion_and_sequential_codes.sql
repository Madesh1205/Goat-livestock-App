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

    -- 4. FARM_ADMIN / Owner specific cleanup: Delete farms and all associated resources completely
    FOR v_farm_record IN SELECT id FROM public.farms WHERE owner_id = v_user_id LOOP
        -- Delete goat_images for goats of this farm
        DELETE FROM public.goat_images
        WHERE goat_id IN (SELECT id FROM public.goats WHERE farm_id = v_farm_record.id);

        -- Delete bookings associated with this farm or its goats
        DELETE FROM public.bookings
        WHERE farm_id = v_farm_record.id
           OR goat_id IN (SELECT id FROM public.goats WHERE farm_id = v_farm_record.id);

        -- Delete goats belonging to this farm
        DELETE FROM public.goats
        WHERE farm_id = v_farm_record.id;

        -- Delete payments belonging to this farm
        DELETE FROM public.payments
        WHERE farm_id = v_farm_record.id;

        -- Delete reviews belonging to this farm
        DELETE FROM public.reviews
        WHERE farm_id = v_farm_record.id;

        -- Delete farm quota transactions if any
        DELETE FROM public.farm_quota_transactions
        WHERE farm_id = v_farm_record.id;

        -- Delete the farm record itself completely
        DELETE FROM public.farms
        WHERE id = v_farm_record.id;
    END LOOP;

    -- 5. Delete customer bookings made by this user
    DELETE FROM public.bookings
    WHERE customer_id = v_user_id;

    -- 6. Delete wishlist items
    DELETE FROM public.wishlist
    WHERE user_id = v_user_id;

    -- 7. Delete notifications
    DELETE FROM public.notifications
    WHERE user_id = v_user_id;

    -- 8. Delete user profile
    DELETE FROM public.profiles
    WHERE id = v_user_id;

    -- 9. Delete auth user from auth.users
    DELETE FROM auth.users
    WHERE id = v_user_id;

    RETURN jsonb_build_object(
        'success', true,
        'message', 'User account and associated farm deleted successfully.'
    );
EXCEPTION WHEN OTHERS THEN
    RETURN jsonb_build_object(
        'success', false,
        'message', SQLERRM
    );
END;
$$;

REVOKE ALL ON FUNCTION public.delete_user_account(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.delete_user_account(UUID) TO authenticated;
