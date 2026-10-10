-- Require initial pending partner farms to use the atomic approval + manual payment RPC.
-- Reactivation of previously approved/suspended farms remains supported.


CREATE OR REPLACE FUNCTION public.admin_update_farm_verification(
    p_farm_id UUID,
    p_status TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_actor_id UUID := auth.uid();
    v_actor_role TEXT;
    v_farm public.farms%ROWTYPE;
    v_status public.farm_status;
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to update farm verification.'
            USING ERRCODE = '42501';
    END IF;

    SELECT role INTO v_actor_role
    FROM public.profiles
    WHERE id = v_actor_id;

    IF v_actor_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Only Super Admin can update farm verification status.'
            USING ERRCODE = '42501';
    END IF;

    IF p_farm_id IS NULL THEN
        RAISE EXCEPTION 'Farm ID is required.' USING ERRCODE = '22023';
    END IF;

    IF p_status IS NULL OR p_status NOT IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED') THEN
        RAISE EXCEPTION 'Invalid farm verification status: %', p_status
            USING ERRCODE = '22023';
    END IF;
    v_status := p_status::public.farm_status;

    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = p_farm_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found: %', p_farm_id USING ERRCODE = 'P0002';
    END IF;

    -- Initial partner approval must use the atomic manual-payment RPC.
    -- A farm previously approved and later suspended can still be reactivated.
    IF v_status = 'APPROVED'::public.farm_status
       AND v_farm.status = 'PENDING'::public.farm_status
       AND v_farm.is_ammal_own_farm IS NOT TRUE THEN
        RAISE EXCEPTION 'Initial partner farm approval must be completed through the manual-payment approval flow.'
            USING ERRCODE = '42501';
    END IF;

    UPDATE public.farms
    SET status = v_status,
        verified_at = CASE
            WHEN v_status = 'APPROVED'::public.farm_status THEN NOW()
            ELSE verified_at
        END,
        updated_at = NOW()
    WHERE id = p_farm_id
    RETURNING * INTO v_farm;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm status update did not affect a row: %', p_farm_id
            USING ERRCODE = 'P0002';
    END IF;

    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_id', v_farm.id,
        'status', v_farm.status::text,
        'verified_at', v_farm.verified_at,
        'goat_listing_limit', v_farm.goat_listing_limit
    );
END;
$function$;
