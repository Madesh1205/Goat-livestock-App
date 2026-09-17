-- =============================================================================
-- AMMAL FARM PLATFORM - STAGE 7: BOOKING & RESERVATION SYSTEM HARDENING
-- =============================================================================
-- 1. Ensure unique active reservation constraint (Double booking prevention)
-- 2. Authoritative price snapshot enforcement (Trigger & Atomic RPC)
-- 3. Automatic 48-hour expiration procedure
-- 4. Status transition synchronization (Bookings -> Goats)
-- 5. Tightened RLS policies for Customers, Farm Admins, and Super Admins
-- =============================================================================

-- 1. DOUBLE-BOOKING PREVENTION: Unique Partial Index
CREATE UNIQUE INDEX IF NOT EXISTS idx_single_active_goat_booking 
ON public.bookings (goat_id) 
WHERE status IN ('PENDING', 'RESERVED', 'CONFIRMED');

-- Secondary indexes for high-throughput queries
CREATE INDEX IF NOT EXISTS idx_bookings_customer_id ON public.bookings(customer_id);
CREATE INDEX IF NOT EXISTS idx_bookings_farm_id ON public.bookings(farm_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status ON public.bookings(status);
CREATE INDEX IF NOT EXISTS idx_bookings_hold_expires_at ON public.bookings(hold_expires_at);

-- 2. EXPIRE OVERDUE BOOKINGS PROCEDURE
CREATE OR REPLACE FUNCTION public.expire_overdue_bookings()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    WITH expired_records AS (
        UPDATE public.bookings
        SET status = 'EXPIRED',
            updated_at = NOW()
        WHERE status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW()
        RETURNING id, goat_id
    )
    SELECT COUNT(*) INTO v_count FROM expired_records;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 3. STATUS TRANSITION SYNCHRONIZATION (Booking -> Goat)
CREATE OR REPLACE FUNCTION public.handle_booking_status_change()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        UPDATE public.goats SET status = 'COMPLETED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status = 'CONFIRMED' THEN
        UPDATE public.goats SET status = 'CONFIRMED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status IN ('RESERVED', 'PENDING') THEN
        UPDATE public.goats SET status = 'RESERVED', updated_at = NOW() WHERE id = NEW.goat_id;
    ELSIF NEW.status IN ('CANCELLED', 'EXPIRED') THEN
        -- Only restore goat to AVAILABLE if no other active booking exists
        IF NOT EXISTS (
            SELECT 1 FROM public.bookings 
            WHERE goat_id = NEW.goat_id 
              AND id != NEW.id 
              AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
        ) THEN
            UPDATE public.goats SET status = 'AVAILABLE', updated_at = NOW() WHERE id = NEW.goat_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_booking_status_change ON public.bookings;
CREATE TRIGGER tr_booking_status_change
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.handle_booking_status_change();

-- 4. PRICE SNAPSHOT & IMMUTABILITY ENFORCEMENT TRIGGER
CREATE OR REPLACE FUNCTION public.enforce_booking_price_snapshot()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_effective_price NUMERIC(12, 2);
    v_user_role TEXT;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- First release any expired hold on this goat
        UPDATE public.bookings
        SET status = 'EXPIRED', updated_at = NOW()
        WHERE goat_id = NEW.goat_id
          AND status IN ('PENDING', 'RESERVED')
          AND hold_expires_at < NOW();

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

        -- Check user role for expiry override
        SELECT role INTO v_user_role FROM public.profiles WHERE id = auth.uid();
        IF v_user_role != 'SUPER_ADMIN' THEN
            NEW.hold_expires_at := OLD.hold_expires_at;
        END IF;

        -- Validate status transitions
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

-- 5. ATOMIC RESERVATION FUNCTION WITH DISCOUNTED SNAPSHOT PRICE
CREATE OR REPLACE FUNCTION public.create_goat_booking_atomic(
    p_goat_id UUID,
    p_customer_id UUID,
    p_notes TEXT DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_customer RECORD;
    v_booking_id UUID;
    v_effective_price NUMERIC(12, 2);
    v_hold_expires TIMESTAMPTZ;
BEGIN
    -- 1. Expire any overdue holds for this goat before checking availability
    UPDATE public.bookings
    SET status = 'EXPIRED', updated_at = NOW()
    WHERE goat_id = p_goat_id
      AND status IN ('PENDING', 'RESERVED')
      AND hold_expires_at < NOW();

    -- 2. Lock the goat row with FOR UPDATE to prevent race conditions
    SELECT * INTO v_goat 
    FROM public.goats 
    WHERE id = p_goat_id 
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Goat listing not found.';
    END IF;

    -- 3. Validate customer profile
    SELECT * INTO v_customer FROM public.profiles WHERE id = p_customer_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Customer profile not found.';
    END IF;

    -- 4. Check farm status
    SELECT * INTO v_farm FROM public.farms WHERE id = v_goat.farm_id;
    IF NOT FOUND OR v_farm.status != 'APPROVED' THEN
        RAISE EXCEPTION 'Farm is not active or approved.';
    END IF;

    -- 5. Validate goat availability and approval
    IF v_goat.status != 'AVAILABLE' THEN
        RAISE EXCEPTION 'Goat is no longer available for booking (Status: %).', v_goat.status;
    END IF;

    IF v_goat.is_approved_by_admin != TRUE THEN
        RAISE EXCEPTION 'Goat listing is pending admin approval and cannot be booked.';
    END IF;

    -- 6. Prevent breeder from booking their own farm's goat
    IF v_farm.owner_id = p_customer_id THEN
        RAISE EXCEPTION 'Breeders cannot place booking holds on their own farm listings.';
    END IF;

    -- 7. Calculate authoritative price snapshot with discount
    v_effective_price := ROUND(v_goat.price * (1.0 - COALESCE(v_goat.discount_percentage, 0.0) / 100.0), 2);
    IF v_effective_price < 0 THEN
        v_effective_price := 0.00;
    END IF;

    v_hold_expires := NOW() + INTERVAL '48 hours';

    -- 8. Insert booking record
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
        p_customer_id,
        'PENDING',
        NOW(),
        v_hold_expires,
        v_effective_price,
        0.00,
        SUBSTRING(p_notes FROM 1 FOR 500)
    ) RETURNING id INTO v_booking_id;

    -- 9. Update goat status to RESERVED
    UPDATE public.goats 
    SET status = 'RESERVED', updated_at = NOW() 
    WHERE id = v_goat.id;

    -- 10. Send notification to farm owner
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

    -- 11. Send notification to customer
    INSERT INTO public.notifications (
        user_id,
        title,
        body,
        link_type,
        link_id,
        is_read,
        created_at
    ) VALUES (
        p_customer_id,
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
        'customer_id', p_customer_id,
        'total_price', v_effective_price,
        'status', 'PENDING',
        'hold_expires_at', v_hold_expires
    );
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- 6. REFINED ROW LEVEL SECURITY (RLS) POLICIES
ALTER TABLE public.bookings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "bookings_select_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_insert_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_update_policy" ON public.bookings;
DROP POLICY IF EXISTS "bookings_delete_policy" ON public.bookings;

-- SELECT: Customer sees own, Farm Admin sees farm's, Super Admin sees all
CREATE POLICY "bookings_select_policy" ON public.bookings
    FOR SELECT TO authenticated
    USING (
        customer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

-- INSERT: Customer can only insert for their own user id (or Super Admin)
CREATE POLICY "bookings_insert_policy" ON public.bookings
    FOR INSERT TO authenticated
    WITH CHECK (
        customer_id::text = auth.uid()::text 
        OR public.is_super_admin()
    );

-- UPDATE:
-- Customer can only cancel or update customer notes on their own booking
-- Farm Admin can update status (CONFIRMED, COMPLETED, CANCELLED) or admin notes on their own farm's booking
-- Super Admin has full update capability
CREATE POLICY "bookings_update_policy" ON public.bookings
    FOR UPDATE TO authenticated
    USING (
        customer_id::text = auth.uid()::text
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    )
    WITH CHECK (
        (
            customer_id::text = auth.uid()::text
            AND status IN ('PENDING', 'CANCELLED')
        )
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id::text = bookings.farm_id::text AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

-- DELETE: Super Admin only
CREATE POLICY "bookings_delete_policy" ON public.bookings
    FOR DELETE TO authenticated
    USING (
        public.is_super_admin()
    );
