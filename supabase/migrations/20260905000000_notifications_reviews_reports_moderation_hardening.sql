-- =============================================================================
-- AMMAL FARM PLATFORM — STAGE 10
-- NOTIFICATIONS, REVIEWS, REPORTS & SUPER ADMIN MODERATION AUDIT MIGRATION
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. NOTIFICATIONS TABLE HARDENING & IDEMPOTENCY
-- -----------------------------------------------------------------------------

-- Add event_key column for idempotency & deduplication
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS event_key TEXT;

-- Deduplication index: Prevents duplicate notifications for the same user and event
CREATE UNIQUE INDEX IF NOT EXISTS idx_notifications_user_event_key
ON public.notifications (user_id, event_key)
WHERE event_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notifications_user_id ON public.notifications(user_id);
CREATE INDEX IF NOT EXISTS idx_notifications_is_read ON public.notifications(is_read);
CREATE INDEX IF NOT EXISTS idx_notifications_created_at ON public.notifications(created_at DESC);

-- Helper procedure: create_system_notification with automatic deduplication
CREATE OR REPLACE FUNCTION public.create_system_notification(
    p_user_id UUID,
    p_title TEXT,
    p_body TEXT,
    p_link_type TEXT DEFAULT 'GENERAL',
    p_link_id TEXT DEFAULT NULL,
    p_event_key TEXT DEFAULT NULL
)
RETURNS UUID AS $$
DECLARE
    v_notification_id UUID := gen_random_uuid();
BEGIN
    INSERT INTO public.notifications (
        id, user_id, title, body, link_type, link_id, event_key, is_read, created_at
    ) VALUES (
        v_notification_id, p_user_id, p_title, p_body, p_link_type, p_link_id, p_event_key, FALSE, NOW()
    )
    ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL
    DO NOTHING;

    RETURN v_notification_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- -----------------------------------------------------------------------------
-- 2. AUDIT LOGS TABLE & TRIGGER AUTOMATION
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS public.audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES public.profiles(id) ON DELETE SET NULL,
    action TEXT NOT NULL,
    target_type TEXT NOT NULL,
    target_id UUID NOT NULL,
    previous_state TEXT,
    new_state TEXT,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_actor_id ON public.audit_logs(actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_target ON public.audit_logs(target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON public.audit_logs(created_at DESC);

-- -----------------------------------------------------------------------------
-- 3. REVIEWS TABLE CONSTRAINTS, AUTHENTICITY & RATING AGGREGATION
-- -----------------------------------------------------------------------------

-- Unique review per booking (Duplicate review prevention)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_reviews_booking_id'
    ) THEN
        ALTER TABLE public.reviews
        ADD CONSTRAINT uq_reviews_booking_id UNIQUE (booking_id);
    END IF;
END $$;

-- Rating constraint between 1 and 5
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_reviews_rating_range'
    ) THEN
        ALTER TABLE public.reviews
        ADD CONSTRAINT chk_reviews_rating_range CHECK (rating >= 1 AND rating <= 5);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_reviews_goat_approved ON public.reviews(goat_id, is_approved);
CREATE INDEX IF NOT EXISTS idx_reviews_farm_approved ON public.reviews(farm_id, is_approved);
CREATE INDEX IF NOT EXISTS idx_reviews_customer_id ON public.reviews(customer_id);

-- Enforce review authenticity: customer must own the booking, booking must be completed or confirmed
CREATE OR REPLACE FUNCTION public.enforce_review_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_booking RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Force customer_id to authenticated user unless super admin
        IF auth.uid() IS NOT NULL AND NOT public.is_super_admin() THEN
            NEW.customer_id := auth.uid();
        END IF;

        IF NEW.booking_id IS NULL THEN
            RAISE EXCEPTION 'A valid booking ID is required to submit a verified review.';
        END IF;

        -- Authoritative booking lookup
        SELECT * INTO v_booking
        FROM public.bookings
        WHERE id = NEW.booking_id;

        IF NOT FOUND THEN
            RAISE EXCEPTION 'Booking with ID % does not exist.', NEW.booking_id;
        END IF;

        -- Verify customer ownership
        IF v_booking.customer_id != NEW.customer_id AND NOT public.is_super_admin() THEN
            RAISE EXCEPTION 'Unauthorized: Customers can only review their own purchases.';
        END IF;

        -- Verify booking eligibility (CONFIRMED or COMPLETED)
        IF v_booking.status NOT IN ('COMPLETED', 'CONFIRMED') THEN
            RAISE EXCEPTION 'Reviews are only permitted for confirmed or completed bookings (current: %).', v_booking.status;
        END IF;

        -- Authoritative association: force goat_id and farm_id from booking
        NEW.goat_id := v_booking.goat_id;
        NEW.farm_id := v_booking.farm_id;
        NEW.is_verified_purchase := TRUE;
        NEW.is_approved := TRUE;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();

        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NOT public.is_super_admin() THEN
            -- Non-super admins cannot alter relationship fields
            IF NEW.booking_id != OLD.booking_id OR NEW.goat_id != OLD.goat_id OR
               NEW.farm_id != OLD.farm_id OR NEW.customer_id != OLD.customer_id THEN
                RAISE EXCEPTION 'Relationship fields on a review are immutable.';
            END IF;
            -- Customers cannot unhide their review if hidden by moderation
            NEW.is_approved := OLD.is_approved;
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_review_authenticity ON public.reviews;
CREATE TRIGGER tr_enforce_review_authenticity
BEFORE INSERT OR UPDATE ON public.reviews
FOR EACH ROW EXECUTE FUNCTION public.enforce_review_authenticity();

-- Recalculate goat & farm ratings on review insert/update/delete (Only approved reviews)
CREATE OR REPLACE FUNCTION public.recalculate_ratings_on_review()
RETURNS TRIGGER AS $$
DECLARE
    v_goat_id UUID;
    v_farm_id UUID;
    v_avg_rating NUMERIC(3, 2);
    v_count INT;
    v_farm_avg NUMERIC(3, 2);
    v_farm_count INT;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_goat_id := OLD.goat_id;
        v_farm_id := OLD.farm_id;
    ELSE
        v_goat_id := NEW.goat_id;
        v_farm_id := NEW.farm_id;
    END IF;

    -- 1. Compute Goat Stats (only where is_approved = true)
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_avg_rating, v_count
    FROM public.reviews
    WHERE goat_id = v_goat_id AND is_approved = TRUE;

    UPDATE public.goats
    SET rating = v_avg_rating,
        review_count = v_count,
        updated_at = NOW()
    WHERE id = v_goat_id;

    -- 2. Compute Farm Stats (only where is_approved = true)
    SELECT
        COALESCE(ROUND(AVG(rating)::numeric, 2), 5.00),
        COUNT(*)
    INTO v_farm_avg, v_farm_count
    FROM public.reviews
    WHERE farm_id = v_farm_id AND is_approved = TRUE;

    UPDATE public.farms
    SET rating = v_farm_avg,
        review_count = v_farm_count,
        updated_at = NOW()
    WHERE id = v_farm_id;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_recalculate_ratings ON public.reviews;
CREATE TRIGGER tr_recalculate_ratings
AFTER INSERT OR UPDATE OF rating, is_approved OR DELETE ON public.reviews
FOR EACH ROW EXECUTE FUNCTION public.recalculate_ratings_on_review();

-- -----------------------------------------------------------------------------
-- 4. REPORTS TABLE CONSTRAINTS & AUTHENTICITY
-- -----------------------------------------------------------------------------

-- Prevent duplicate pending reports from the same user against the same target
CREATE UNIQUE INDEX IF NOT EXISTS idx_reports_user_target_pending
ON public.reports (reporter_id, target_type, target_id)
WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_reports_status ON public.reports(status);
CREATE INDEX IF NOT EXISTS idx_reports_target ON public.reports(target_type, target_id);

CREATE OR REPLACE FUNCTION public.enforce_report_authenticity()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := public.is_super_admin();
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Force authentic reporter_id
        IF auth.uid() IS NOT NULL AND NOT v_is_super_admin THEN
            NEW.reporter_id := auth.uid();
        END IF;
        NEW.status := 'PENDING';
        NEW.resolved_by := NULL;
        NEW.resolution_notes := NULL;
        NEW.created_at := NOW();
        NEW.updated_at := NOW();
        RETURN NEW;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Only Super Admin can resolve, dismiss, or update reports
        IF NOT v_is_super_admin THEN
            RAISE EXCEPTION 'Unauthorized: Only Super Admin can update or moderate safety reports.';
        END IF;

        IF NEW.status IN ('RESOLVED', 'DISMISSED') AND OLD.status NOT IN ('RESOLVED', 'DISMISSED') THEN
            NEW.resolved_by := auth.uid();
        END IF;
        NEW.updated_at := NOW();
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_enforce_report_authenticity ON public.reports;
CREATE TRIGGER tr_enforce_report_authenticity
BEFORE INSERT OR UPDATE ON public.reports
FOR EACH ROW EXECUTE FUNCTION public.enforce_report_authenticity();

-- -----------------------------------------------------------------------------
-- 5. AUTOMATED NOTIFICATION TRIGGERS (BOOKINGS, LISTINGS, FARMS, REPORTS)
-- -----------------------------------------------------------------------------

-- 5.1 Booking Lifecycle Notification Trigger
CREATE OR REPLACE FUNCTION public.notify_booking_lifecycle()
RETURNS TRIGGER AS $$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_booking_ref TEXT;
BEGIN
    SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
    SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
    v_booking_ref := SUBSTRING(NEW.id::text FROM 1 FOR 6);

    IF TG_OP = 'INSERT' THEN
        -- Customer notification: 48h hold active
        PERFORM public.create_system_notification(
            NEW.customer_id,
            'Reservation Active (48 Hours) 🐐',
            'Your reservation for ' || COALESCE(v_goat.name, 'Goat') || ' is active. Complete farm confirmation within 48 hours.',
            'BOOKING',
            NEW.id::text,
            'booking_created_' || NEW.id
        );

        -- Farm Admin notification: new reservation received
        IF v_farm.owner_id IS NOT NULL THEN
            PERFORM public.create_system_notification(
                v_farm.owner_id,
                'New Booking Received 📋',
                'New booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' (₹' || NEW.total_price || ').',
                'FARM_BOOKINGS',
                NEW.id::text,
                'farm_booking_created_' || NEW.id
            );
        END IF;

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'CONFIRMED' THEN
            -- Customer notification: Booking confirmed
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Confirmed! 🎉',
                'Your reservation for ' || COALESCE(v_goat.name, 'Goat') || ' has been confirmed by ' || COALESCE(v_farm.name, 'the farm') || '.',
                'BOOKING',
                NEW.id::text,
                'booking_confirmed_' || NEW.id
            );
        ELSIF NEW.status = 'CANCELLED' THEN
            -- Customer notification
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Cancelled ⚠️',
                'Your booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' has been cancelled.',
                'BOOKING',
                NEW.id::text,
                'booking_cancelled_cust_' || NEW.id
            );
            -- Farm Admin notification
            IF v_farm.owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Booking Cancelled ⚠️',
                    'Booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' was cancelled.',
                    'FARM_BOOKINGS',
                    NEW.id::text,
                    'booking_cancelled_farm_' || NEW.id
                );
            END IF;
        ELSIF NEW.status = 'EXPIRED' THEN
            -- Customer notification: 48h hold expired
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Hold Expired ⏳',
                'Your 48-hour reservation hold for ' || COALESCE(v_goat.name, 'Goat') || ' has expired.',
                'BOOKING',
                NEW.id::text,
                'booking_expired_' || NEW.id
            );
        ELSIF NEW.status = 'COMPLETED' THEN
            -- Customer notification with review prompt
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Order Completed! Leave a Review ⭐',
                'You completed your purchase of ' || COALESCE(v_goat.name, 'Goat') || '. Share your verified buyer review!',
                'REVIEW_PROMPT',
                NEW.id::text,
                'booking_completed_review_' || NEW.id
            );
            -- Farm Admin notification
            IF v_farm.owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Booking Completed 🤝',
                    'Booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' is completed.',
                    'FARM_BOOKINGS',
                    NEW.id::text,
                    'booking_completed_farm_' || NEW.id
                );
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_notify_booking_lifecycle ON public.bookings;
CREATE TRIGGER tr_notify_booking_lifecycle
AFTER INSERT OR UPDATE OF status ON public.bookings
FOR EACH ROW EXECUTE FUNCTION public.notify_booking_lifecycle();

-- 5.2 Goat Listing Moderation Notification & Audit Trigger
CREATE OR REPLACE FUNCTION public.notify_and_audit_goat_moderation()
RETURNS TRIGGER AS $$
DECLARE
    v_farm RECORD;
    v_super_admin_id UUID := auth.uid();
BEGIN
    SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;

    IF TG_OP = 'UPDATE' THEN
        -- Listing approved
        IF OLD.is_approved_by_admin IS NOT TRUE AND NEW.is_approved_by_admin IS TRUE THEN
            IF v_farm.owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Listing Approved! 🐐',
                    'Your goat listing "' || NEW.name || '" has been approved by Super Admin and is now live.',
                    'GOAT_DETAIL',
                    NEW.id::text,
                    'goat_approved_' || NEW.id
                );
            END IF;

            -- Audit log
            INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
            VALUES (v_super_admin_id, 'APPROVE_LISTING', 'GOAT', NEW.id, 'PENDING_APPROVAL', 'APPROVED', 'Super Admin approved goat listing');

        -- Listing rejected
        ELSIF NEW.status = 'REJECTED' AND OLD.status != 'REJECTED' THEN
            IF v_farm.owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Listing Rejected ❌',
                    'Your goat listing "' || NEW.name || '" was rejected during moderation.',
                    'GOAT_DETAIL',
                    NEW.id::text,
                    'goat_rejected_' || NEW.id
                );
            END IF;

            -- Audit log
            INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
            VALUES (v_super_admin_id, 'REJECT_LISTING', 'GOAT', NEW.id, OLD.status, 'REJECTED', 'Super Admin rejected goat listing');

        -- Listing suspended
        ELSIF NEW.status = 'SUSPENDED' AND OLD.status != 'SUSPENDED' THEN
            IF v_farm.owner_id IS NOT NULL THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Listing Suspended ⚠️',
                    'Your goat listing "' || NEW.name || '" has been suspended by marketplace moderation.',
                    'GOAT_DETAIL',
                    NEW.id::text,
                    'goat_suspended_' || NEW.id
                );
            END IF;

            -- Audit log
            INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
            VALUES (v_super_admin_id, 'SUSPEND_LISTING', 'GOAT', NEW.id, OLD.status, 'SUSPENDED', 'Super Admin suspended goat listing');
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_notify_goat_moderation ON public.goats;
CREATE TRIGGER tr_notify_goat_moderation
AFTER UPDATE OF is_approved_by_admin, status ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_goat_moderation();

-- 5.3 Farm Verification Notification & Audit Trigger
CREATE OR REPLACE FUNCTION public.notify_and_audit_farm_verification()
RETURNS TRIGGER AS $$
DECLARE
    v_super_admin_id UUID := auth.uid();
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- Notify Super Admins of new farm registration
        INSERT INTO public.notifications (user_id, title, body, link_type, link_id, event_key, is_read, created_at)
        SELECT
            p.id,
            'New Farm Awaiting Review 🏡',
            'Farm "' || NEW.name || '" has registered and is pending verification.',
            'FARM_VERIFICATION',
            NEW.id::text,
            'farm_registered_super_' || NEW.id,
            FALSE,
            NOW()
        FROM public.profiles p
        WHERE p.role = 'SUPER_ADMIN'
        ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING;

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        -- Notify Farm Owner of verification status change
        IF NEW.owner_id IS NOT NULL THEN
            PERFORM public.create_system_notification(
                NEW.owner_id,
                'Farm Status Updated: ' || NEW.status || ' 🛡️',
                'Your farm "' || NEW.name || '" verification status is now ' || NEW.status || '.',
                'FARM_PROFILE',
                NEW.id::text,
                'farm_status_' || NEW.id || '_' || NEW.status
            );
        END IF;

        -- Audit log
        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (v_super_admin_id, 'UPDATE_FARM_VERIFICATION', 'FARM', NEW.id, OLD.status::text, NEW.status::text, 'Farm verification updated');
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_notify_farm_verification ON public.farms;
CREATE TRIGGER tr_notify_farm_verification
AFTER INSERT OR UPDATE OF status ON public.farms
FOR EACH ROW EXECUTE FUNCTION public.notify_and_audit_farm_verification();

-- 5.4 Report Submission Notification Trigger
CREATE OR REPLACE FUNCTION public.notify_report_submission()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- 1. Notify Super Admins
        INSERT INTO public.notifications (user_id, title, body, link_type, link_id, event_key, is_read, created_at)
        SELECT
            p.id,
            'New Safety Report Submitted 🚨',
            'A safety report regarding ' || NEW.target_type || ' requires moderation.',
            'REPORT_MODERATION',
            NEW.id::text,
            'report_created_super_' || NEW.id,
            FALSE,
            NOW()
        FROM public.profiles p
        WHERE p.role = 'SUPER_ADMIN'
        ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING;

        -- 2. Confirm to Reporter
        PERFORM public.create_system_notification(
            NEW.reporter_id,
            'Report Received 🛡️',
            'Your report regarding ' || NEW.target_type || ' has been received by our moderation team.',
            'REPORTS',
            NEW.id::text,
            'report_ack_' || NEW.id
        );

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        -- Audit log for report resolution
        INSERT INTO public.audit_logs (actor_id, action, target_type, target_id, previous_state, new_state, notes)
        VALUES (auth.uid(), 'RESOLVE_REPORT', 'REPORT', NEW.id, OLD.status, NEW.status, COALESCE(NEW.resolution_notes, 'Report status updated'));
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS tr_notify_report_submission ON public.reports;
CREATE TRIGGER tr_notify_report_submission
AFTER INSERT OR UPDATE OF status ON public.reports
FOR EACH ROW EXECUTE FUNCTION public.notify_report_submission();

-- -----------------------------------------------------------------------------
-- 6. UPDATE PAYMENT VERIFICATION RPC TO NOTIFY FARM ADMIN & SUPER ADMIN WITH DEDUP
-- -----------------------------------------------------------------------------

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
        '₹100 listing fee verified for ' || v_goat.name || ' (' || v_goat.tag_number || '). Ready for Super Admin moderation.',
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
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- -----------------------------------------------------------------------------
-- 7. ROW LEVEL SECURITY (RLS) POLICIES AUDIT & REINFORCEMENT
-- -----------------------------------------------------------------------------

-- 7.1 NOTIFICATIONS RLS
ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "notifications_select_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_insert_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_update_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_delete_policy" ON public.notifications;
DROP POLICY IF EXISTS "notifications_select_own" ON public.notifications;
DROP POLICY IF EXISTS "notifications_insert_authenticated" ON public.notifications;
DROP POLICY IF EXISTS "notifications_update_own" ON public.notifications;
DROP POLICY IF EXISTS "notifications_delete_own" ON public.notifications;
DROP POLICY IF EXISTS "notifications_user_policy" ON public.notifications;

-- SELECT: Users can only select their own notifications. Super Admin can view all.
CREATE POLICY "notifications_select_policy" ON public.notifications
    FOR SELECT TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

-- INSERT: Authenticated users can only insert for themselves. Super Admin can insert for any.
CREATE POLICY "notifications_insert_policy" ON public.notifications
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

-- UPDATE: Users can only update their own notifications (e.g., mark as read).
CREATE POLICY "notifications_update_policy" ON public.notifications
    FOR UPDATE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin())
    WITH CHECK (auth.uid()::text = user_id::text OR public.is_super_admin());

-- DELETE: Users can only delete their own notifications.
CREATE POLICY "notifications_delete_policy" ON public.notifications
    FOR DELETE TO authenticated
    USING (auth.uid()::text = user_id::text OR public.is_super_admin());

-- 7.2 REVIEWS RLS
ALTER TABLE public.reviews ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "reviews_select_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_insert_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_update_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_delete_policy" ON public.reviews;
DROP POLICY IF EXISTS "reviews_select_all" ON public.reviews;
DROP POLICY IF EXISTS "reviews_insert_authenticated" ON public.reviews;
DROP POLICY IF EXISTS "reviews_update_authenticated" ON public.reviews;
DROP POLICY IF EXISTS "reviews_delete_authenticated" ON public.reviews;

-- SELECT: Public can see approved reviews. Author, farm owner, Super Admin can see unapproved.
CREATE POLICY "reviews_select_policy" ON public.reviews
    FOR SELECT TO public
    USING (
        is_approved = TRUE
        OR (auth.uid() IS NOT NULL AND customer_id::text = auth.uid()::text)
        OR EXISTS (
            SELECT 1 FROM public.farms f
            WHERE f.id = reviews.farm_id AND f.owner_id::text = auth.uid()::text
        )
        OR public.is_super_admin()
    );

-- INSERT: Only authenticated customer who completed/confirmed the booking.
CREATE POLICY "reviews_insert_policy" ON public.reviews
    FOR INSERT TO authenticated
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id::text = auth.uid()::text
            AND EXISTS (
                SELECT 1 FROM public.bookings b
                WHERE b.id = reviews.booking_id
                  AND b.customer_id::text = auth.uid()::text
                  AND b.status IN ('COMPLETED', 'CONFIRMED')
            )
        )
    );

-- UPDATE: Author can update comment/rating (is_approved preserved), Super Admin full update.
CREATE POLICY "reviews_update_policy" ON public.reviews
    FOR UPDATE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin())
    WITH CHECK (
        public.is_super_admin()
        OR (
            customer_id::text = auth.uid()::text
            AND is_approved = (SELECT r.is_approved FROM public.reviews r WHERE r.id = reviews.id)
        )
    );

-- DELETE: Author or Super Admin.
CREATE POLICY "reviews_delete_policy" ON public.reviews
    FOR DELETE TO authenticated
    USING (customer_id::text = auth.uid()::text OR public.is_super_admin());

-- 7.3 REPORTS RLS
ALTER TABLE public.reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "reports_select_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_insert_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_update_policy" ON public.reports;
DROP POLICY IF EXISTS "reports_delete_policy" ON public.reports;

-- SELECT: Reporter can view own reports. Super Admin can view all reports.
CREATE POLICY "reports_select_policy" ON public.reports
    FOR SELECT TO authenticated
    USING (reporter_id::text = auth.uid()::text OR public.is_super_admin());

-- INSERT: Authenticated users can insert reports with their own reporter_id.
CREATE POLICY "reports_insert_policy" ON public.reports
    FOR INSERT TO authenticated
    WITH CHECK (reporter_id::text = auth.uid()::text OR public.is_super_admin());

-- UPDATE: Only Super Admin can resolve or update report status.
CREATE POLICY "reports_update_policy" ON public.reports
    FOR UPDATE TO authenticated
    USING (public.is_super_admin())
    WITH CHECK (public.is_super_admin());

-- DELETE: Only Super Admin can delete reports.
CREATE POLICY "reports_delete_policy" ON public.reports
    FOR DELETE TO authenticated
    USING (public.is_super_admin());

-- 7.4 AUDIT LOGS RLS
ALTER TABLE public.audit_logs ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "audit_logs_select_policy" ON public.audit_logs;
DROP POLICY IF EXISTS "audit_logs_insert_policy" ON public.audit_logs;

CREATE POLICY "audit_logs_select_policy" ON public.audit_logs
    FOR SELECT TO authenticated
    USING (public.is_super_admin());

CREATE POLICY "audit_logs_insert_policy" ON public.audit_logs
    FOR INSERT TO authenticated
    WITH CHECK (public.is_super_admin() OR auth.uid() IS NOT NULL);
