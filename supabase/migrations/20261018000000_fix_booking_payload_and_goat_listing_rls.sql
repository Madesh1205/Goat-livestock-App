-- =============================================================================
-- Migration: 20261018000000_fix_booking_payload_and_goat_listing_rls.sql
-- Purpose:
--   1. Ensure create_booking_hold returns complete booking fields (including
--      customer_id, total_price, deposit_paid, customer_notes, created_at)
--      while enforcing atomic server-side reservation locks and conflict checks.
--   2. Allow Super Admin custom listing slot increases via admin_update_farm_listing_limit
--      without resetting consumed_listing_slots or requiring slot-pack limits.
--   3. Permit authorized Farm Admins and Super Admin to upload goat and farm
--      branding images to the goat-images bucket under <farm_id>/... or goats/<farm_id>/...
-- =============================================================================

CREATE OR REPLACE FUNCTION public.create_booking_hold(
    p_goat_id UUID,
    p_customer_notes TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public
AS $$
DECLARE
    v_user_id UUID := auth.uid();
    v_goat public.goats%ROWTYPE;
    v_farm public.farms%ROWTYPE;
    v_booking public.bookings%ROWTYPE;
    v_expires_at TIMESTAMPTZ := NOW() + INTERVAL '24 hours';
    v_booking_code TEXT;
BEGIN
    IF v_user_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to book a goat.';
    END IF;

    PERFORM public.expire_stale_booking_holds();

    SELECT * INTO v_goat
    FROM public.goats
    WHERE id = p_goat_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = v_goat.farm_id;

    IF NOT FOUND OR (v_farm.status <> 'APPROVED' AND COALESCE(v_farm.is_ammal_own_farm, FALSE) = FALSE) THEN
        RAISE EXCEPTION 'This goat belongs to a farm that is not currently verified.';
    END IF;

    IF v_farm.owner_id = v_user_id THEN
        RAISE EXCEPTION 'You cannot book goats listed by your own farm.';
    END IF;

    IF v_goat.is_approved_by_admin IS NOT TRUE THEN
        RAISE EXCEPTION 'This goat listing is not approved for public booking.';
    END IF;

    IF v_goat.status <> 'AVAILABLE' THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = p_goat_id
          AND status IN ('RESERVED', 'CONFIRMED')
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    v_booking_code := public.generate_Read_booking_code();

    INSERT INTO public.bookings (
        booking_code,
        goat_id,
        farm_id,
        customer_id,
        status,
        booking_date,
        hold_expires_at,
        total_price,
        deposit_paid,
        customer_notes
    ) VALUES (
        v_booking_code,
        v_goat.id,
        v_goat.farm_id,
        v_user_id,
        'RESERVED',
        NOW(),
        v_expires_at,
        COALESCE(NULLIF(v_goat.final_price, 0), v_goat.price),
        ROUND(GREATEST(COALESCE(NULLIF(v_goat.final_price, 0), v_goat.price) * 0.10, 500.0), 2),
        NULLIF(BTRIM(COALESCE(p_customer_notes, '')), '')
    )
    RETURNING * INTO v_booking;

    UPDATE public.goats
    SET status = 'RESERVED',
        updated_at = NOW()
    WHERE id = v_goat.id;

    RETURN jsonb_build_object(
        'id', v_booking.id,
        'booking_code', v_booking.booking_code,
        'goat_id', v_booking.goat_id,
        'farm_id', v_booking.farm_id,
        'customer_id', v_booking.customer_id,
        'status', v_booking.status,
        'booking_date', v_booking.booking_date,
        'hold_expires_at', v_booking.hold_expires_at,
        'total_price', v_booking.total_price,
        'deposit_paid', v_booking.deposit_paid,
        'customer_notes', v_booking.customer_notes,
        'created_at', v_booking.created_at,
        'updated_at', v_booking.updated_at
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.create_booking_hold(UUID, TEXT) TO authenticated;

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
    v_updated_farm RECORD;
BEGIN
    SELECT role INTO v_caller_role
    FROM public.profiles
    WHERE id = auth.uid();

    IF v_caller_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Security Exception: Only Super Admin can modify farm goat listing limits.';
    END IF;

    IF p_new_limit IS NULL OR p_new_limit < 2 OR p_new_limit > 1000 THEN
        RAISE EXCEPTION 'Invalid listing limit (%). Value must be between 2 and 1000.', p_new_limit;
    END IF;

    UPDATE public.farms
    SET
        goat_listing_limit = p_new_limit,
        updated_at = NOW()
    WHERE id = p_farm_id
    RETURNING id, name, status, goat_listing_limit, consumed_listing_slots, updated_at
    INTO v_updated_farm;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm with ID % was not found.', p_farm_id;
    END IF;

    RETURN jsonb_build_object(
        'id', v_updated_farm.id,
        'name', v_updated_farm.name,
        'status', v_updated_farm.status,
        'goat_listing_limit', v_updated_farm.goat_listing_limit,
        'consumed_listing_slots', v_updated_farm.consumed_listing_slots,
        'updated_at', v_updated_farm.updated_at
    );
END;
$$;

GRANT EXECUTE ON FUNCTION public.admin_update_farm_listing_limit(UUID, INTEGER) TO authenticated;

DROP POLICY IF EXISTS "goat_images_owner_insert" ON storage.objects;
CREATE POLICY "goat_images_owner_insert"
    ON storage.objects FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'goat-images'
        AND (
            public.is_super_admin()
            OR (storage.foldername(name))[1] IN (
                SELECT id::text FROM public.farms WHERE owner_id = auth.uid()
            )
            OR (
                (storage.foldername(name))[1] = 'goats'
                AND (storage.foldername(name))[2] IN (
                    SELECT id::text FROM public.farms WHERE owner_id = auth.uid()
                )
            )
        )
    );

DROP POLICY IF EXISTS "goat_images_owner_update" ON storage.objects;
CREATE POLICY "goat_images_owner_update"
    ON storage.objects FOR UPDATE
    TO authenticated
    USING (
        bucket_id = 'goat-images'
        AND (
            public.is_super_admin()
            OR (storage.foldername(name))[1] IN (
                SELECT id::text FROM public.farms WHERE owner_id = auth.uid()
            )
            OR (
                (storage.foldername(name))[1] = 'goats'
                AND (storage.foldername(name))[2] IN (
                    SELECT id::text FROM public.farms WHERE owner_id = auth.uid()
                )
            )
        )
    );
