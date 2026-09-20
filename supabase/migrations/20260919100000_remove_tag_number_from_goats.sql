-- =============================================================================
-- AMMAL FARM HYPERLOCAL LIVESTOCK MARKETPLACE
-- MIGRATION: 20260919100000_remove_tag_number_from_goats.sql
-- Description: Completely removes tag_number and associated constraints from the
--              goats table while preserving the authoritative sequential goat_code
--              (e.g., GOAT-001, GOAT-002, etc.).
-- =============================================================================

-- 1. Drop the unique constraint on (farm_id, tag_number) if present
ALTER TABLE public.goats DROP CONSTRAINT IF EXISTS uq_farm_goat_tag;

-- 2. Drop tag_number index if exists
DROP INDEX IF EXISTS idx_goats_tag_number;
DROP INDEX IF EXISTS idx_goats_farm_tag;

-- 3. Update verify_listing_payment_atomic to remove references to tag_number and use goat_code
CREATE OR REPLACE FUNCTION public.verify_listing_payment_atomic(
    p_goat_id UUID,
    p_order_id TEXT,
    p_payment_id TEXT,
    p_signature TEXT,
    p_amount NUMERIC DEFAULT 100.00,
    p_verified_by TEXT DEFAULT 'SERVER_GATEWAY'
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_caller_id UUID := auth.uid();
    v_is_super_admin BOOLEAN := FALSE;
    v_existing_payment RECORD;
    v_receipt_no TEXT;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Lock the goat row to prevent concurrent double verifications
    SELECT * INTO v_goat FROM goats WHERE id = p_goat_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    SELECT * INTO v_farm FROM farms WHERE id = v_goat.farm_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found.';
    END IF;

    -- Check caller role
    IF v_caller_id IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM profiles WHERE id = v_caller_id;

        IF v_farm.owner_id != v_caller_id AND v_is_super_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Unauthorized: You do not own this farm.';
        END IF;
    END IF;

    -- 2. Check Idempotency: If this exact payment ID was already verified for this goat
    SELECT * INTO v_existing_payment
    FROM listing_payments
    WHERE razorpay_payment_id = p_payment_id
      AND goat_id = p_goat_id
      AND payment_status = 'PAID';

    IF FOUND THEN
        RETURN jsonb_build_object(
            'success', true,
            'message', 'Payment already verified (Idempotent replay).',
            'goat_id', v_goat.id,
            'receipt_number', v_existing_payment.receipt_number,
            'already_paid', true
        );
    END IF;

    -- 3. Check for replay attack: payment ID already used for ANY other goat/listing
    SELECT * INTO v_existing_payment
    FROM listing_payments
    WHERE razorpay_payment_id = p_payment_id
      AND payment_status = 'PAID';

    IF FOUND THEN
        RAISE EXCEPTION 'Payment ID % has already been used for another listing (%s). Replay rejected.', p_payment_id, v_existing_payment.goat_id;
    END IF;

    -- 4. Verify amount tampering: partner farm expected amount is strictly ₹100.00
    IF v_farm.is_ammal_own_farm IS NOT TRUE AND v_farm.id != '00000000-0000-0000-0000-000000000001'::uuid THEN
        IF p_amount IS NULL OR p_amount != 100.00 THEN
            RAISE EXCEPTION 'Invalid payment amount: %. Expected ₹100.00 for partner farm listing fee.', p_amount;
        END IF;
    END IF;

    -- 5. Check if goat is already marked paid by another payment
    IF v_goat.listing_fee_paid = TRUE THEN
        RETURN jsonb_build_object(
            'success', true,
            'message', 'Listing fee already verified previously.',
            'goat_id', v_goat.id,
            'already_paid', true
        );
    END IF;

    -- Generate receipt number
    v_receipt_no := 'RCPT-' || EXTRACT(EPOCH FROM v_now)::bigint || '-' || SUBSTRING(p_goat_id::text FROM 1 FOR 4);

    -- 6. Update or insert listing_payments record
    UPDATE listing_payments
    SET payment_status = 'PAID',
        payment_gateway_ref = p_order_id,
        razorpay_payment_id = p_payment_id,
        razorpay_signature = p_signature,
        receipt_number = v_receipt_no,
        payment_date = v_now,
        metadata = jsonb_build_object(
            'razorpayPaymentId', p_payment_id,
            'razorpayOrderId', p_order_id,
            'verifiedBy', p_verified_by,
            'verifiedAt', v_now,
            'amount', p_amount
        ),
        updated_at = v_now
    WHERE goat_id = p_goat_id AND payment_status = 'PENDING';

    IF NOT FOUND THEN
        INSERT INTO listing_payments (
            goat_id,
            farm_id,
            payer_id,
            amount,
            currency,
            payment_type,
            payment_status,
            payment_gateway_ref,
            razorpay_payment_id,
            razorpay_signature,
            receipt_number,
            payment_date,
            metadata,
            created_at,
            updated_at
        ) VALUES (
            v_goat.id,
            v_farm.id,
            COALESCE(v_caller_id::text, v_farm.owner_id::text),
            p_amount,
            'INR',
            'LISTING_FEE',
            'PAID',
            p_order_id,
            p_payment_id,
            p_signature,
            v_receipt_no,
            v_now,
            jsonb_build_object(
                'razorpayPaymentId', p_payment_id,
                'razorpayOrderId', p_order_id,
                'verifiedBy', p_verified_by,
                'verifiedAt', v_now
            ),
            v_now,
            v_now
        );
    END IF;

    -- 7. Update goat record: mark fee paid
    PERFORM set_config('ammal.in_payment_verification', 'true', true);

    UPDATE goats
    SET listing_fee_paid = TRUE,
        listing_fee_amount = p_amount,
        status = 'AVAILABLE',
        updated_at = v_now
    WHERE id = v_goat.id;

    -- 8. Deduplicated Notifications:
    -- Farm Admin notification
    IF v_farm.owner_id IS NOT NULL THEN
        PERFORM public.create_system_notification(
            v_farm.owner_id,
            'Listing Payment Verified! 💳',
            '₹100 listing fee verified for ' || v_goat.name || ' (Receipt: ' || v_receipt_no || '). Pending Super Admin approval.',
            'PAYMENT_RECEIPT',
            p_payment_id,
            'payment_verified_farm_' || v_goat.id || '_' || p_payment_id
        );
    END IF;

    -- Super Admin notification
    INSERT INTO public.notifications (user_id, title, body, link_type, link_id, event_key, is_read, created_at)
    SELECT
        p.id,
        'Listing Fee Verified: Moderation Required 🐐',
        '₹100 listing fee verified for ' || v_goat.name || ' (' || COALESCE(v_goat.goat_code, 'GOAT') || '). Ready for Super Admin moderation.',
        'GOAT_APPROVAL',
        v_goat.id::text,
        'payment_verified_admin_' || v_goat.id || '_' || p_payment_id,
        FALSE,
        v_now
    FROM profiles p
    WHERE p.role = 'SUPER_ADMIN'
    ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING;

    -- 9. Audit log
    INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
    VALUES (v_caller_id, 'VERIFY_LISTING_FEE', 'PAYMENT', v_goat.id, 'PENDING', 'PAID', 'Verified ₹100 listing payment. Receipt: ' || v_receipt_no);

    RETURN jsonb_build_object(
        'success', true,
        'goat_id', v_goat.id,
        'payment_id', p_payment_id,
        'receipt_number', v_receipt_no,
        'verified_at', v_now,
        'approval_status', 'PENDING_APPROVAL'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- 4. Drop tag_number column from public.goats
ALTER TABLE public.goats DROP COLUMN IF EXISTS tag_number;
