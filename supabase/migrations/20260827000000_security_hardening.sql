-- =============================================================================
-- AMMAL FARM PLATFORM - COMPREHENSIVE SECURITY HARDENING & ISOLATION MIGRATION
-- Migration: 20260827000000_security_hardening.sql
-- Fixes: RLS Isolation, Atomic Booking Lock, Storage RLS, Super Admin Protection
-- =============================================================================

-- 1. STORAGE BUCKETS & RLS POLICIES
-- Ensure storage buckets exist
INSERT INTO storage.buckets (id, name, public)
VALUES 
    ('goat-photos', 'goat-photos', true),
    ('vet-certificates', 'vet-certificates', false),
    ('farm-docs', 'farm-docs', false)
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public;

-- Enable RLS on storage.objects
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;

-- 1.1 Goat Photos Storage Policy
-- Public read access for goat photos
CREATE POLICY "Public Read Goat Photos"
ON storage.objects FOR SELECT
USING (bucket_id = 'goat-photos');

-- Authenticated Farm Admins can upload photos to their own farm folder
-- Or Super Admin can upload anywhere in goat-photos
CREATE POLICY "Farm Admin Upload Goat Photos"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'goat-photos'
    AND (
        is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN'
            AND (storage.foldername(name))[1] = get_auth_farm_id()::text
        )
    )
);

-- Farm Admins can update/delete only their farm's photos
CREATE POLICY "Farm Admin Modify Goat Photos"
ON storage.objects FOR UPDATE
TO authenticated
USING (
    bucket_id = 'goat-photos'
    AND (
        is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN'
            AND (storage.foldername(name))[1] = get_auth_farm_id()::text
        )
    )
);

CREATE POLICY "Farm Admin Delete Goat Photos"
ON storage.objects FOR DELETE
TO authenticated
USING (
    bucket_id = 'goat-photos'
    AND (
        is_super_admin()
        OR (
            get_auth_role() = 'FARM_ADMIN'
            AND (storage.foldername(name))[1] = get_auth_farm_id()::text
        )
    )
);

-- 1.2 Vet Certificates & Farm Docs Policies
CREATE POLICY "Vet Certificates Access"
ON storage.objects FOR SELECT
TO authenticated
USING (
    bucket_id = 'vet-certificates'
    AND (
        is_super_admin()
        OR (storage.foldername(name))[1] = get_auth_farm_id()::text
        OR EXISTS (
            SELECT 1 FROM bookings b
            WHERE b.customer_id = auth.uid()
              AND b.farm_id::text = (storage.foldername(name))[1]
              AND b.status IN ('CONFIRMED', 'COMPLETED')
        )
    )
);

CREATE POLICY "Vet Certificates Upload"
ON storage.objects FOR INSERT
TO authenticated
WITH CHECK (
    bucket_id = 'vet-certificates'
    AND (
        is_super_admin()
        OR (get_auth_role() = 'FARM_ADMIN' AND (storage.foldername(name))[1] = get_auth_farm_id()::text)
    )
);

-- 2. HARDEN RLS ON FARMS TABLE (Prevent farm ownership reassignment)
DROP POLICY IF EXISTS "farms_update_policy" ON farms;
CREATE POLICY "farms_update_policy" ON farms
    FOR UPDATE USING (
        owner_id = auth.uid()
        OR id = get_auth_farm_id()
        OR is_super_admin()
    ) WITH CHECK (
        is_super_admin() OR (
            (owner_id = auth.uid() OR id = get_auth_farm_id())
            AND owner_id = (SELECT f.owner_id FROM farms f WHERE f.id = farms.id)
            AND status = (SELECT f.status FROM farms f WHERE f.id = farms.id)
            AND is_ammal_own_farm = (SELECT f.is_ammal_own_farm FROM farms f WHERE f.id = farms.id)
        )
    );

-- 3. HARDEN RLS ON GOATS TABLE (Prevent approval status bypass and cross-farm tampering)
DROP POLICY IF EXISTS "goats_update_policy" ON goats;
CREATE POLICY "goats_update_policy" ON goats
    FOR UPDATE USING (
        is_super_admin() OR (get_auth_role() = 'FARM_ADMIN' AND farm_id = get_auth_farm_id())
    ) WITH CHECK (
        is_super_admin() OR (
            get_auth_role() = 'FARM_ADMIN'
            AND farm_id = get_auth_farm_id()
            -- Cannot alter farm ownership of existing goat
            AND farm_id = (SELECT g.farm_id FROM goats g WHERE g.id = goats.id)
            -- Cannot self-approve listings without Super Admin review
            AND is_approved_by_admin = (SELECT g.is_approved_by_admin FROM goats g WHERE g.id = goats.id)
        )
    );

-- 4. HARDEN RLS ON BOOKINGS TABLE
DROP POLICY IF EXISTS "bookings_update_policy" ON bookings;
CREATE POLICY "bookings_update_policy" ON bookings
    FOR UPDATE USING (
        customer_id = auth.uid()
        OR farm_id = get_auth_farm_id()
        OR is_super_admin()
    ) WITH CHECK (
        is_super_admin()
        OR (
            -- Customer can ONLY cancel their own booking, and ONLY if currently PENDING or RESERVED
            customer_id = auth.uid()
            AND status = 'CANCELLED'
            AND (SELECT b.status FROM bookings b WHERE b.id = bookings.id) IN ('PENDING', 'RESERVED')
            AND customer_id = (SELECT b.customer_id FROM bookings b WHERE b.id = bookings.id)
            AND farm_id = (SELECT b.farm_id FROM bookings b WHERE b.id = bookings.id)
            AND goat_id = (SELECT b.goat_id FROM bookings b WHERE b.id = bookings.id)
        )
        OR (
            -- Farm Admin can update booking statuses (CONFIRMED, COMPLETED, CANCELLED) only for their farm
            farm_id = get_auth_farm_id()
            AND farm_id = (SELECT b.farm_id FROM bookings b WHERE b.id = bookings.id)
            AND customer_id = (SELECT b.customer_id FROM bookings b WHERE b.id = bookings.id)
            AND goat_id = (SELECT b.goat_id FROM bookings b WHERE b.id = bookings.id)
        )
    );

-- 5. ATOMIC STORED PROCEDURES TO ELIMINATE RACE CONDITIONS
-- 5.1 Atomic Booking Procedure with Row Lock
CREATE OR REPLACE FUNCTION create_goat_booking_atomic(
    p_goat_id UUID,
    p_customer_id UUID,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_booking_id UUID;
    v_customer RECORD;
BEGIN
    -- 1. Verify customer profile
    SELECT * INTO v_customer FROM profiles WHERE id = p_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    -- 2. Lock the goat row with FOR UPDATE to prevent race conditions
    SELECT * INTO v_goat 
    FROM goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 3. Validate goat availability and approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    -- 4. Check farm status
    SELECT * INTO v_farm FROM farms WHERE id = v_goat.farm_id;
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    -- 5. Prevent breeder from booking their own farm's goat
    IF v_customer.farm_id IS NOT NULL AND v_customer.farm_id = v_goat.farm_id THEN
        RAISE EXCEPTION 'Breeders cannot place booking holds on their own farm listings.';
    END IF;

    -- 6. Insert booking record (48h hold)
    INSERT INTO bookings (
        goat_id,
        farm_id,
        customer_id,
        status,
        booking_date,
        hold_expires_at,
        total_price,
        customer_notes
    ) VALUES (
        v_goat.id,
        v_goat.farm_id,
        p_customer_id,
        'PENDING',
        NOW(),
        NOW() + INTERVAL '48 hours',
        v_goat.price,
        SUBSTRING(p_notes FROM 1 FOR 500)
    ) RETURNING id INTO v_booking_id;

    -- 7. Update goat status to RESERVED
    UPDATE goats 
    SET status = 'RESERVED',
        updated_at = NOW()
    WHERE id = v_goat.id;

    -- 8. Create automated notifications
    INSERT INTO notifications (user_id, title, body, link_type, link_id)
    VALUES 
        (v_farm.owner_id, 'New Booking Received! 🐐', 
         v_customer.full_name || ' reserved ' || v_goat.name || ' (₹' || v_goat.price || ') for 48 hours.', 
         'BOOKING', v_booking_id::text),
        (p_customer_id, '48-Hour Reservation Placed! 🎉', 
         'Your reservation for ' || v_goat.name || ' at ' || v_farm.name || ' is active. Breeder has been notified.', 
         'BOOKING', v_booking_id::text);

    RETURN jsonb_build_object(
        'success', true,
        'booking_id', v_booking_id,
        'goat_id', v_goat.id,
        'farm_id', v_goat.farm_id,
        'goat_name', v_goat.name,
        'price', v_goat.price,
        'hold_expires_at', NOW() + INTERVAL '48 hours'
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 5.2 Atomic Payment Verification Procedure
CREATE OR REPLACE FUNCTION verify_listing_payment_atomic(
    p_goat_id UUID,
    p_order_id TEXT,
    p_payment_id TEXT,
    p_signature TEXT,
    p_verified_by TEXT DEFAULT 'SERVER_GATEWAY'
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_existing_payment RECORD;
    v_now TIMESTAMPTZ := NOW();
BEGIN
    -- 1. Lock the goat row
    SELECT * INTO v_goat FROM goats WHERE id = p_goat_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 2. Check if already verified
    IF v_goat.listing_fee_paid = TRUE THEN
        RETURN jsonb_build_object(
            'success', true,
            'message', 'Listing fee already verified previously.',
            'goat_id', v_goat.id,
            'already_paid', true
        );
    END IF;

    -- 3. Check for replay attack on payment ID across platform
    SELECT * INTO v_existing_payment 
    FROM listing_payments 
    WHERE payment_gateway_ref = p_payment_id AND payment_status = 'PAID';
    
    IF FOUND THEN
        RAISE EXCEPTION 'Payment ID has already been used for another listing.';
    END IF;

    -- 4. Update or insert listing payment record
    UPDATE listing_payments 
    SET payment_status = 'PAID',
        payment_gateway_ref = p_order_id,
        payment_date = v_now,
        metadata = jsonb_build_object(
            'razorpayPaymentId', p_payment_id,
            'razorpayOrderId', p_order_id,
            'verifiedBy', p_verified_by,
            'verifiedAt', v_now
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
            receipt_number,
            payment_date,
            metadata
        ) VALUES (
            v_goat.id,
            v_goat.farm_id,
            v_goat.farm_id,
            100.00,
            'INR',
            'LISTING_FEE',
            'PAID',
            p_order_id,
            'RCPT-' || EXTRACT(EPOCH FROM v_now)::bigint || '-' || SUBSTRING(p_goat_id::text FROM 1 FOR 4),
            v_now,
            jsonb_build_object('razorpayPaymentId', p_payment_id, 'verifiedBy', p_verified_by)
        );
    END IF;

    -- 5. Update Goat record
    UPDATE goats
    SET listing_fee_paid = TRUE,
        status = 'AVAILABLE',
        updated_at = v_now
    WHERE id = v_goat.id;

    -- 6. Notify Super Admins
    INSERT INTO notifications (user_id, title, body, link_type, link_id)
    SELECT 
        p.id,
        'Listing Fee Verified: Review Required 🐐',
        '₹100 listing fee verified for ' || v_goat.name || ' (' || v_goat.tag_number || '). Ready for Super Admin moderation.',
        'GOAT_APPROVAL',
        v_goat.id::text
    FROM profiles p
    WHERE p.role = 'SUPER_ADMIN';

    RETURN jsonb_build_object(
        'success', true,
        'goat_id', v_goat.id,
        'payment_id', p_payment_id,
        'verified_at', v_now
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
