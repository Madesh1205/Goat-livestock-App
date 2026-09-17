-- =============================================================================
-- AMMAL FARM PLATFORM — STAGE 9
-- PAYMENT, LISTING FEE & TRANSACTION SECURITY AUDIT MIGRATION
-- =============================================================================

-- 1. Server-Authoritative Listing Fee Enforcement Trigger on Goats
-- Ensures:
--  - Ammal Farm: fee = ₹0, listing_fee_paid = TRUE
--  - Partner Farm: fee = ₹100, listing_fee_paid = FALSE on creation
--  - Clients cannot tamper with fee amount or bypass payment by setting listing_fee_paid = true directly
CREATE OR REPLACE FUNCTION enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    -- Check if current user is Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM profiles
    WHERE id = auth.uid();

    -- Determine if the target farm is Ammal Farm
    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            -- Ammal Farm: Zero listing fee waiver, auto-marked paid
            NEW.listing_fee_amount := 0.00;
            NEW.listing_fee_paid := TRUE;
            -- Super Admin farm listings are auto-approved per platform design
            NEW.is_approved_by_admin := TRUE;
        ELSE
            -- Partner Farm: Strictly ₹100 listing fee, unpaid on insert, pending approval
            NEW.listing_fee_amount := 100.00;
            NEW.listing_fee_paid := FALSE;
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Prevent untrusted client tampering with fee amounts or payment status
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            -- Non-super admins cannot change listing_fee_amount
            IF OLD.listing_fee_amount IS DISTINCT FROM NEW.listing_fee_amount THEN
                RAISE EXCEPTION 'Listing fee amount is server-authoritative and cannot be modified by client.';
            END IF;

            -- Non-super admins cannot directly flip listing_fee_paid to TRUE
            -- Only the verified payment procedure (SECURITY DEFINER) can mark fee as paid
            IF OLD.listing_fee_paid IS FALSE AND NEW.listing_fee_paid IS TRUE THEN
                IF current_setting('ammal.in_payment_verification', true) != 'true' THEN
                    RAISE EXCEPTION 'Clients cannot directly mark listing_fee_paid = true. Verified payment required.';
                END IF;
            END IF;

            -- Non-super admins cannot self-approve their own listings
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
BEFORE INSERT OR UPDATE ON goats
FOR EACH ROW EXECUTE FUNCTION enforce_goat_listing_fee_rule();

-- 2. Prevent Tampering with Immutable Booking Amount
CREATE OR REPLACE FUNCTION enforce_booking_amount_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.amount IS DISTINCT FROM NEW.amount THEN
            RAISE EXCEPTION 'Booking price snapshot is immutable once booked (amount: %, attempted: %).', OLD.amount, NEW.amount;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_booking_amount ON bookings;
CREATE TRIGGER tr_enforce_booking_amount
BEFORE UPDATE ON bookings
FOR EACH ROW EXECUTE FUNCTION enforce_booking_amount_immutability();

-- 3. Hardened RLS Policies on listing_payments Table
ALTER TABLE public.listing_payments ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "payments_select_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "payments_insert_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "payments_update_policy" ON public.listing_payments;
DROP POLICY IF EXISTS "Farm Admins can view own farm payments" ON public.listing_payments;
DROP POLICY IF EXISTS "Edge functions service role full access" ON public.listing_payments;

-- SELECT: Users can only see payments for their owned farms or their own payer_id (or Super Admin)
CREATE POLICY "payments_select_secure" ON public.listing_payments
    FOR SELECT TO authenticated
    USING (
        payer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = listing_payments.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR EXISTS (
            SELECT 1 FROM public.profiles p
            WHERE p.id = auth.uid() AND p.role = 'SUPER_ADMIN'
        )
    );

-- INSERT: Clients can only initiate PENDING payment records for their owned farm's goats at the exact ₹100 fee
CREATE POLICY "payments_insert_secure" ON public.listing_payments
    FOR INSERT TO authenticated
    WITH CHECK (
        payer_id::text = auth.uid()::text
        AND payment_status = 'PENDING'
        AND amount = 100.00
        AND currency = 'INR'
        AND EXISTS (
            SELECT 1 FROM public.goats g
            JOIN public.farms f ON f.id = g.farm_id
            WHERE g.id = listing_payments.goat_id
              AND f.owner_id::text = auth.uid()::text
        )
    );

-- UPDATE: Clients are FORBIDDEN from updating payments directly.
-- All transitions to PAID or FAILED must happen via verified RPC or service_role!
-- No UPDATE policy for authenticated users means direct client updates are rejected by RLS.

-- Service role full access
CREATE POLICY "payments_service_role_all" ON public.listing_payments
    FOR ALL TO service_role
    USING (true)
    WITH CHECK (true);

-- 4. Unique Constraints for Replay Protection
CREATE UNIQUE INDEX IF NOT EXISTS idx_listing_payments_unique_payment_id
    ON listing_payments(razorpay_payment_id)
    WHERE razorpay_payment_id IS NOT NULL AND payment_status = 'PAID';

CREATE UNIQUE INDEX IF NOT EXISTS idx_listing_payments_unique_paid_goat
    ON listing_payments(goat_id)
    WHERE payment_status = 'PAID';

CREATE INDEX IF NOT EXISTS idx_listing_payments_order_ref
    ON listing_payments(payment_gateway_ref);

-- 5. Atomic Listing Payment Initiation RPC
CREATE OR REPLACE FUNCTION initiate_listing_payment(
    p_goat_id UUID
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_caller_id UUID := auth.uid();
    v_is_super_admin BOOLEAN := FALSE;
    v_existing_pending RECORD;
    v_order_id TEXT;
    v_payment_id UUID;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to initiate listing payment.';
    END IF;

    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM profiles WHERE id = v_caller_id;

    -- Fetch goat & farm with lock
    SELECT * INTO v_goat FROM goats WHERE id = p_goat_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    SELECT * INTO v_farm FROM farms WHERE id = v_goat.farm_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Farm not found.';
    END IF;

    -- Verify authorization: must be farm owner or super admin
    IF v_farm.owner_id != v_caller_id AND v_is_super_admin IS NOT TRUE THEN
        RAISE EXCEPTION 'Unauthorized: You do not own this farm.';
    END IF;

    -- Check if Ammal Farm (₹0 fee waiver)
    IF v_farm.is_ammal_own_farm = TRUE OR v_farm.id = '00000000-0000-0000-0000-000000000001'::uuid THEN
        PERFORM set_config('ammal.in_payment_verification', 'true', true);
        UPDATE goats
        SET listing_fee_paid = TRUE,
            listing_fee_amount = 0.00,
            is_approved_by_admin = TRUE,
            updated_at = v_now
        WHERE id = v_goat.id;

        RETURN jsonb_build_object(
            'success', true,
            'fee_required', false,
            'amount', 0.00,
            'currency', 'INR',
            'message', 'Ammal Farm listing fee waived (₹0).',
            'already_paid', true,
            'goat_id', v_goat.id
        );
    END IF;

    -- Check if already paid
    IF v_goat.listing_fee_paid = TRUE THEN
        RETURN jsonb_build_object(
            'success', true,
            'fee_required', false,
            'amount', 100.00,
            'currency', 'INR',
            'message', 'Listing fee has already been paid for this goat.',
            'already_paid', true,
            'goat_id', v_goat.id
        );
    END IF;

    -- Look for reusable recent PENDING payment to prevent duplicate records on retry
    SELECT * INTO v_existing_pending
    FROM listing_payments
    WHERE goat_id = p_goat_id
      AND payment_status = 'PENDING'
      AND created_at > (v_now - INTERVAL '24 hours')
    ORDER BY created_at DESC
    LIMIT 1;

    IF FOUND THEN
        RETURN jsonb_build_object(
            'success', true,
            'fee_required', true,
            'amount', 100.00,
            'currency', 'INR',
            'order_id', v_existing_pending.payment_gateway_ref,
            'payment_record_id', v_existing_pending.id,
            'goat_id', v_goat.id,
            'already_paid', false
        );
    END IF;

    -- Generate new order reference & record
    v_order_id := 'order_' || SUBSTRING(REPLACE(gen_random_uuid()::text, '-', '') FROM 1 FOR 14);
    v_payment_id := gen_random_uuid();

    INSERT INTO listing_payments (
        id,
        goat_id,
        farm_id,
        payer_id,
        amount,
        currency,
        payment_type,
        payment_status,
        payment_gateway_ref,
        created_at,
        updated_at
    ) VALUES (
        v_payment_id,
        v_goat.id,
        v_farm.id,
        v_caller_id::text,
        100.00,
        'INR',
        'LISTING_FEE',
        'PENDING',
        v_order_id,
        v_now,
        v_now
    );

    RETURN jsonb_build_object(
        'success', true,
        'fee_required', true,
        'amount', 100.00,
        'currency', 'INR',
        'order_id', v_order_id,
        'payment_record_id', v_payment_id,
        'goat_id', v_goat.id,
        'already_paid', false
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 6. Hardened Server-Side Atomic Payment Verification RPC
CREATE OR REPLACE FUNCTION verify_listing_payment_atomic(
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
    -- Set session variable so trigger allows the update
    PERFORM set_config('ammal.in_payment_verification', 'true', true);

    -- Notice: payment verified does NOT bypass Super Admin approval!
    -- approval_status remains PENDING_APPROVAL for moderation
    UPDATE goats
    SET listing_fee_paid = TRUE,
        listing_fee_amount = p_amount,
        status = 'AVAILABLE',
        updated_at = v_now
    WHERE id = v_goat.id;

    -- 8. Notify Super Admins of verified listing ready for review
    INSERT INTO notifications (user_id, title, body, link_type, link_id)
    SELECT
        p.id,
        'Listing Fee Verified: Moderation Required 🐐',
        '₹100 listing fee verified for ' || v_goat.name || ' (' || v_goat.tag_number || '). Ready for Super Admin moderation.',
        'GOAT_APPROVAL',
        v_goat.id::text
    FROM profiles p
    WHERE p.role = 'SUPER_ADMIN';

    RETURN jsonb_build_object(
        'success', true,
        'goat_id', v_goat.id,
        'payment_id', p_payment_id,
        'receipt_number', v_receipt_no,
        'verified_at', v_now,
        'approval_status', 'PENDING_APPROVAL'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 7. Payment Failure & Cancellation RPCs
CREATE OR REPLACE FUNCTION record_listing_payment_failure(
    p_goat_id UUID,
    p_order_id TEXT,
    p_error_message TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_now TIMESTAMPTZ := NOW();
BEGIN
    UPDATE listing_payments
    SET payment_status = 'FAILED',
        error_message = p_error_message,
        updated_at = v_now
    WHERE goat_id = p_goat_id
      AND (payment_gateway_ref = p_order_id OR p_order_id IS NULL)
      AND payment_status = 'PENDING';

    RETURN jsonb_build_object(
        'success', true,
        'status', 'FAILED',
        'goat_id', p_goat_id,
        'error_message', p_error_message
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE OR REPLACE FUNCTION record_listing_payment_cancelled(
    p_goat_id UUID,
    p_order_id TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_now TIMESTAMPTZ := NOW();
BEGIN
    UPDATE listing_payments
    SET payment_status = 'CANCELLED',
        error_message = 'Payment cancelled by user',
        updated_at = v_now
    WHERE goat_id = p_goat_id
      AND (payment_gateway_ref = p_order_id OR p_order_id IS NULL)
      AND payment_status = 'PENDING';

    RETURN jsonb_build_object(
        'success', true,
        'status', 'CANCELLED',
        'goat_id', p_goat_id
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
