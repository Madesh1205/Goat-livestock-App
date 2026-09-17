-- =============================================================================
-- AMMAL FARM APP - STAGE 11M
-- Booking & Partner Listing Quota Hardening
--
-- 1. BOOKING ATOMIC FUNCTION & TRIGGERS (auth.uid() authoritative security):
--    - Authenticated customer verification via auth.uid()
--    - Server-authoritative discounted price snapshot calculation
--    - Double-booking prevention with row-level locking (FOR UPDATE)
--    - 48-hour reservation hold expiration management
--    - Direct status & price manipulation prevention
--
-- 2. PARTNER LISTING QUOTA HARDENING:
--    - Partner farm default listing quota = 10
--    - Server-side quota trigger on goat insertions
--    - Protection of is_ammal_own_farm from fake exemption claims
--    - Super Admin exclusive control over listing quotas and verification
--    - Farm ownership and metadata immutability for non-admins
-- =============================================================================

-- 1. UNIQUE ACTIVE RESERVATION PARTIAL INDEX
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_active_goat_booking 
ON public.bookings (goat_id) 
WHERE status IN ('PENDING', 'RESERVED', 'CONFIRMED');

-- 2. ATOMIC RESERVATION FUNCTION WITH AUTH.UID() AND SERVER PRICE CALCULATION
CREATE OR REPLACE FUNCTION public.create_goat_booking_atomic(
    p_goat_id UUID,
    p_customer_id UUID DEFAULT NULL,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_auth_uid UUID;
    v_effective_customer_id UUID;
    v_goat RECORD;
    v_farm RECORD;
    v_customer RECORD;
    v_booking_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_hold_expires TIMESTAMPTZ;
BEGIN
    -- 1. Resolve authenticated caller (never trust client-supplied customer ID over auth.uid)
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        v_effective_customer_id := v_auth_uid;
    ELSE
        IF p_customer_id IS NULL THEN
            RAISE EXCEPTION 'Authentication required to create a booking.';
        END IF;
        v_effective_customer_id := p_customer_id;
    END IF;

    -- 2. Expire any overdue holds for this goat before evaluating availability
    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at < NOW();

    -- 3. Lock the goat row with FOR UPDATE to prevent race conditions & double-booking
    SELECT * INTO v_goat 
    FROM public.goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 4. Validate customer profile and role
    SELECT * INTO v_customer FROM public.profiles WHERE id = v_effective_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    -- Only customers or super admin can place booking holds
    IF v_customer.role NOT IN ('CUSTOMER', 'SUPER_ADMIN') THEN
        RAISE EXCEPTION 'Only customer accounts can place bookings.';
    END IF;

    -- 5. Check farm status
    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    -- 6. Validate goat availability and approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    -- 7. Prevent breeder from booking their own farm's goat
    IF v_farm.owner_id = v_effective_customer_id THEN
        RAISE EXCEPTION 'Breeders cannot place booking holds on their own farm listings.';
    END IF;

    -- 8. Prevent duplicate active booking for the same goat
    IF EXISTS (
        SELECT 1 FROM public.bookings
        WHERE goat_id = v_goat.id
          AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
          AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
    ) THEN
        RAISE EXCEPTION 'This goat has already been reserved by another customer.';
    END IF;

    -- 9. Server calculates authoritative price snapshot with discount (client cannot set price)
    v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    v_hold_expires := NOW() + INTERVAL '48 hours';

    -- 10. Insert booking record
    INSERT INTO public.bookings (
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
        v_goat.id,
        v_goat.farm_id,
        v_effective_customer_id,
        'PENDING',
        NOW(),
        v_hold_expires,
        v_effective_price,
        0.00,
        SUBSTRING(p_notes FROM 1 FOR 500)
    ) RETURNING id INTO v_booking_id;

    -- 11. Update goat status to RESERVED
    UPDATE public.goats 
    SET status = 'RESERVED', updated_at = NOW() 
    WHERE id = v_goat.id;

    -- 12. Send notification to farm owner
    IF v_farm.owner_id IS NOT NULL THEN
        INSERT INTO public.notifications (
            user_id,
            title,
            body,
            link_type,
            link_id,
            is_read,
            created_at
        ) VALUES (
            v_farm.owner_id,
            'New Goat Reservation Hold 🐐',
            format('A customer placed a 48h hold on %s (₹%s). Contact customer to coordinate pickup.', v_goat.name, v_effective_price::text),
            'FARM_BOOKINGS',
            v_booking_id::text,
            FALSE,
            NOW()
        );
    END IF;

    -- 13. Send notification to customer
    INSERT INTO public.notifications (
        user_id,
        title,
        body,
        link_type,
        link_id,
        is_read,
        created_at
    ) VALUES (
        v_effective_customer_id,
        'Reservation Hold Confirmed ⏳',
        format('You reserved %s for ₹%s. 48-hour hold is active. Breeder will contact you.', v_goat.name, v_effective_price::text),
        'MY_BOOKINGS',
        v_booking_id::text,
        FALSE,
        NOW()
    );

    RETURN jsonb_build_object(
        'success', TRUE,
        'booking_id', v_booking_id,
        'goat_id', v_goat.id,
        'farm_id', v_goat.farm_id,
        'customer_id', v_effective_customer_id,
        'total_price', v_effective_price,
        'status', 'PENDING',
        'hold_expires_at', v_hold_expires
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. BOOKING PRICE SNAPSHOT & STATUS TRANSITION TRIGGER
CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_effective_price NUMERIC(12, 2);
    v_user_role TEXT;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();

    IF TG_OP = 'INSERT' THEN
        -- If auth.uid() is available, force customer_id to auth.uid()
        IF v_auth_uid IS NOT NULL THEN
            SELECT role INTO v_user_role FROM public.profiles WHERE id = v_auth_uid;
            IF v_user_role NOT IN ('CUSTOMER', 'SUPER_ADMIN') THEN
                RAISE EXCEPTION 'Only customer accounts can create bookings.';
            END IF;
            NEW.customer_id := v_auth_uid;
        END IF;

        -- First release any expired hold on this goat
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW();

        -- Prevent duplicate active booking
        IF EXISTS (
            SELECT 1 FROM public.bookings
            WHERE goat_id = NEW.goat_id
              AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
              AND (hold_expires_at IS NULL OR hold_expires_at > NOW())
        ) THEN
            RAISE EXCEPTION 'This goat has already been reserved by another customer.';
        END IF;

        -- Fetch authoritative goat listing
        SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Goat listing with ID % does not exist.', NEW.goat_id;
        END IF;

        IF v_goat.status != 'AVAILABLE' THEN
            RAISE EXCEPTION 'Goat is not available for reservation (current status: %).', v_goat.status;
        END IF;

        IF v_goat.is_approved_by_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Goat listing is not approved by administrator.';
        END IF;

        -- Authoritative price calculation (base price minus discount)
        v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
        IF v_effective_price < 0 THEN
            v_effective_price := 0.00;
        END IF;

        -- Overwrite client-supplied values with server-authoritative values
        NEW.total_price := v_effective_price;
        NEW.farm_id := v_goat.farm_id;
        NEW.deposit_paid := 0.00;
        IF NEW.booking_date IS NULL THEN
            NEW.booking_date := NOW();
        END IF;
        NEW.hold_expires_at := NEW.booking_date + INTERVAL '48 hours';
        NEW.status := 'PENDING';
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Lock historical price snapshot and key relational fields from tampering
        NEW.total_price := OLD.total_price;
        NEW.goat_id := OLD.goat_id;
        NEW.farm_id := OLD.farm_id;
        NEW.customer_id := OLD.customer_id;
        NEW.booking_date := OLD.booking_date;
        NEW.created_at := OLD.created_at;
        NEW.updated_at := NOW();

        -- Check caller role
        SELECT role INTO v_user_role FROM public.profiles WHERE id = v_auth_uid;

        -- Customers can only cancel their own pending booking
        IF v_auth_uid IS NOT NULL AND v_user_role = 'CUSTOMER' THEN
            IF NEW.status NOT IN ('PENDING', 'CANCELLED') THEN
                RAISE EXCEPTION 'Customers cannot change booking status to %.', NEW.status;
            END IF;
            NEW.hold_expires_at := OLD.hold_expires_at;
        END IF;

        IF v_user_role != 'SUPER_ADMIN' THEN
            NEW.hold_expires_at := OLD.hold_expires_at;
        END IF;

        -- Validate terminal status transitions
        IF OLD.status = 'CANCELLED' AND NEW.status != 'CANCELLED' AND v_user_role != 'SUPER_ADMIN' THEN
            RAISE EXCEPTION 'Cannot re-activate a cancelled booking.';
        END IF;

        IF OLD.status = 'EXPIRED' AND NEW.status != 'EXPIRED' AND v_user_role != 'SUPER_ADMIN' THEN
            RAISE EXCEPTION 'Cannot re-activate an expired booking.';
        END IF;

        IF OLD.status = 'COMPLETED' AND NEW.status != 'COMPLETED' AND v_user_role != 'SUPER_ADMIN' THEN
            RAISE EXCEPTION 'Cannot modify a completed booking.';
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS trg_enforce_booking_price_snapshot ON public.bookings;
CREATE TRIGGER trg_enforce_booking_price_snapshot
    BEFORE INSERT OR UPDATE ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.enforce_booking_price_snapshot();

-- 4. PARTNER LISTING LIMIT TRIGGER (Exemption strictly for genuine Ammal Farm)
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_is_ammal BOOLEAN := FALSE;
    v_limit INT := 10;
    v_current_count INT := 0;
BEGIN
    -- Genuine Ammal Farm check: Must match designated UUID and is_ammal_own_farm flag
    SELECT (f.id = '00000000-0000-0000-0000-000000000001'::uuid AND f.is_ammal_own_farm = TRUE),
           COALESCE(f.goat_listing_limit, 10)
    INTO v_is_ammal, v_limit
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    -- Only genuine Ammal Farm is exempt from partner listing limit checks
    IF v_is_ammal = TRUE THEN
        RETURN NEW;
    END IF;

    -- Count active/listed goats for this partner farm
    SELECT COUNT(*) INTO v_current_count
    FROM public.goats
    WHERE farm_id = NEW.farm_id
      AND status != 'INACTIVE';

    IF v_current_count >= v_limit THEN
        RAISE EXCEPTION 'Listing limit reached. Contact Super Admin to increase your listing limit.';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_goat_listing_limit ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_limit
BEFORE INSERT ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_limit();

-- 5. FARM METADATA INTEGRITY TRIGGER (Enforce server-side defaults on INSERT and immutability on UPDATE)
CREATE OR REPLACE FUNCTION public.enforce_farm_metadata_integrity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_auth_uid UUID;
BEGIN
    v_auth_uid := auth.uid();
    IF v_auth_uid IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = v_auth_uid;
    END IF;

    IF TG_OP = 'INSERT' THEN
        -- If non-super-admin inserts a farm, force defaults:
        IF v_is_super_admin IS NOT TRUE THEN
            -- Only Super Admin can create an Ammal own farm or set higher quota
            NEW.is_ammal_own_farm := FALSE;
            NEW.goat_listing_limit := 10;
            NEW.verification_status := 'PENDING';
            IF v_auth_uid IS NOT NULL THEN
                NEW.owner_id := v_auth_uid;
            END IF;
        END IF;
        RETURN NEW;

    ELSIF TG_OP = 'UPDATE' THEN
        -- Non-Super Admins are strictly prohibited from changing listing limits, ownership, or verification status
        IF v_auth_uid IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.goat_listing_limit IS DISTINCT FROM NEW.goat_listing_limit THEN
                RAISE EXCEPTION 'Only Super Admin can update the farm listing limit.';
            END IF;

            IF OLD.owner_id IS DISTINCT FROM NEW.owner_id THEN
                RAISE EXCEPTION 'Farm ownership cannot be modified.';
            END IF;

            IF OLD.is_ammal_own_farm IS DISTINCT FROM NEW.is_ammal_own_farm THEN
                RAISE EXCEPTION 'Ammal Farm designation cannot be modified.';
            END IF;

            IF OLD.verification_status IS DISTINCT FROM NEW.verification_status THEN
                RAISE EXCEPTION 'Only Super Admin can modify farm verification status.';
            END IF;
        END IF;

        RETURN NEW;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_farm_metadata_integrity ON public.farms;
CREATE TRIGGER tr_enforce_farm_metadata_integrity
BEFORE INSERT OR UPDATE ON public.farms
FOR EACH ROW EXECUTE FUNCTION public.enforce_farm_metadata_integrity();
