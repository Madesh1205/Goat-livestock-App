-- Atomically approve a partner farm and record its out-of-band manual approval payment.
-- Existing non-zero listing quotas are deliberately preserved; new partners with a zero quota
-- receive two initial slots. Ammal Farm is excluded from partner approval/payment.
-- No payment gateway or checkout is used.

ALTER TYPE public.payment_type ADD VALUE IF NOT EXISTS 'FARM_APPROVAL';
ALTER TYPE public.payment_status ADD VALUE IF NOT EXISTS 'PAID';

CREATE OR REPLACE FUNCTION public.admin_approve_partner_farm(
    p_payment_id UUID,
    p_farm_id UUID,
    p_amount NUMERIC,
    p_payment_ref TEXT DEFAULT NULL,
    p_receipt_number TEXT DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
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
    v_payment_ref TEXT;
    v_receipt_number TEXT;
    v_slots_added INTEGER;
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to approve a partner farm.'
            USING ERRCODE = '42501';
    END IF;

    SELECT role INTO v_actor_role
    FROM public.profiles
    WHERE id = v_actor_id;

    IF v_actor_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Only Super Admin can approve a partner farm.'
            USING ERRCODE = '42501';
    END IF;

    IF p_payment_id IS NULL OR p_farm_id IS NULL THEN
        RAISE EXCEPTION 'Payment ID and farm ID are required.'
            USING ERRCODE = '22023';
    END IF;

    IF p_amount IS NULL OR p_amount <= 0 THEN
        RAISE EXCEPTION 'Manual farm approval payment amount must be greater than zero.'
            USING ERRCODE = '22023';
    END IF;

    SELECT * INTO v_farm
    FROM public.farms
    WHERE id = p_farm_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found: %', p_farm_id
            USING ERRCODE = 'P0002';
    END IF;

    IF v_farm.is_ammal_own_farm IS TRUE THEN
        RAISE EXCEPTION 'Ammal Farm does not require partner-farm approval or payment.'
            USING ERRCODE = '22023';
    END IF;

    IF v_farm.owner_id IS NULL THEN
        RAISE EXCEPTION 'This farm has no owner account and cannot be approved through partner onboarding.'
            USING ERRCODE = '22023';
    END IF;

    IF v_farm.status = 'APPROVED'::public.farm_status THEN
        RAISE EXCEPTION 'Farm is already approved.'
            USING ERRCODE = '22023';
    END IF;

    IF EXISTS (SELECT 1 FROM public.listing_payments WHERE id = p_payment_id) THEN
        RAISE EXCEPTION 'This approval payment request has already been recorded.'
            USING ERRCODE = '23505';
    END IF;

    v_payment_ref := COALESCE(
        NULLIF(BTRIM(p_payment_ref), ''),
        'MANUAL-APPR-' || SUBSTRING(p_farm_id::text FROM 1 FOR 8) || '-' || SUBSTRING(p_payment_id::text FROM 1 FOR 8)
    );

    v_receipt_number := COALESCE(
        NULLIF(BTRIM(p_receipt_number), ''),
        'RCPT-APPR-' || EXTRACT(EPOCH FROM NOW())::BIGINT::text || '-' || SUBSTRING(p_farm_id::text FROM 1 FOR 4)
    );

    -- Keep existing quotas unchanged; only new partner accounts with a zero limit get two slots.
    v_slots_added := CASE WHEN COALESCE(v_farm.goat_listing_limit, 0) <= 0 THEN 2 ELSE 0 END;

    UPDATE public.farms
    SET status = 'APPROVED'::public.farm_status,
        verified_at = NOW(),
        goat_listing_limit = CASE
            WHEN COALESCE(goat_listing_limit, 0) <= 0 THEN 2
            ELSE goat_listing_limit
        END,
        updated_at = NOW()
    WHERE id = p_farm_id
    RETURNING * INTO v_farm;

    IF NOT FOUND OR v_farm.status <> 'APPROVED'::public.farm_status THEN
        RAISE EXCEPTION 'Farm approval did not persist.'
            USING ERRCODE = 'P0002';
    END IF;

    INSERT INTO public.listing_payments (
        id, farm_id, payer_id, amount, currency, payment_type, payment_status,
        payment_gateway_ref, receipt_number, payment_date, metadata, created_at, updated_at
    ) VALUES (
        p_payment_id, v_farm.id, v_farm.owner_id, p_amount, 'INR',
        'FARM_APPROVAL'::public.payment_type, 'PAID'::public.payment_status,
        v_payment_ref, v_receipt_number, NOW(),
        jsonb_build_object(
            'payment_category', 'FARM_APPROVAL',
            'payment_method', 'Manual Payment',
            'notes', LEFT(COALESCE(p_notes, 'Partner farm approval payment recorded manually.'), 1000),
            'slots_added', v_slots_added,
            'recorded_by', v_actor_id,
            'source', 'SUPER_ADMIN_MANUAL'
        ),
        NOW(), NOW()
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_id', v_farm.id,
        'status', v_farm.status::text,
        'goat_listing_limit', v_farm.goat_listing_limit,
        'payment_id', p_payment_id,
        'payment_type', 'FARM_APPROVAL',
        'payment_status', 'PAID',
        'amount', p_amount,
        'receipt_number', v_receipt_number,
        'slots_added', v_slots_added
    );
END;
$function$;

REVOKE ALL ON FUNCTION public.admin_approve_partner_farm(UUID, UUID, NUMERIC, TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.admin_approve_partner_farm(UUID, UUID, NUMERIC, TEXT, TEXT, TEXT) FROM anon;
GRANT EXECUTE ON FUNCTION public.admin_approve_partner_farm(UUID, UUID, NUMERIC, TEXT, TEXT, TEXT) TO authenticated;
