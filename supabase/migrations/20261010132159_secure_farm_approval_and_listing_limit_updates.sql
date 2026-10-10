-- Enforce Super Admin permissions for farm verification and listing limit updates.
-- All changes are checked against the live farm row; no existing quota is recalculated.

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

REVOKE ALL ON FUNCTION public.admin_update_farm_verification(UUID, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_update_farm_verification(UUID, TEXT) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_update_farm_verification(UUID, TEXT) TO authenticated;


CREATE OR REPLACE FUNCTION public.admin_update_farm_listing_limit(
    p_farm_id UUID,
    p_limit INTEGER
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
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to update farm listing limit.'
            USING ERRCODE = '42501';
    END IF;

    SELECT role INTO v_actor_role
    FROM public.profiles
    WHERE id = v_actor_id;

    IF v_actor_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Only Super Admin can update farm listing limit.'
            USING ERRCODE = '42501';
    END IF;

    IF p_farm_id IS NULL OR p_limit IS NULL OR p_limit < 0 THEN
        RAISE EXCEPTION 'A farm ID and a non-negative listing limit are required.'
            USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = p_farm_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found: %', p_farm_id USING ERRCODE = 'P0002';
    END IF;

    IF p_limit < v_farm.consumed_listing_slots THEN
        RAISE EXCEPTION 'Listing limit cannot be lower than consumed slots (%).',
            v_farm.consumed_listing_slots
            USING ERRCODE = '22023';
    END IF;

    UPDATE public.farms
    SET goat_listing_limit = p_limit,
        updated_at = NOW()
    WHERE id = p_farm_id
    RETURNING * INTO v_farm;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm listing limit update did not affect a row: %', p_farm_id
            USING ERRCODE = 'P0002';
    END IF;

    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_id', v_farm.id,
        'goat_listing_limit', v_farm.goat_listing_limit,
        'consumed_listing_slots', v_farm.consumed_listing_slots
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) TO authenticated;
