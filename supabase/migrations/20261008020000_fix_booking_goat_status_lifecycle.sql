-- =============================================================================
-- AMMAL FARM PLATFORM - FIX BOOKING-TO-GOAT STATUS LIFECYCLE
-- Updates public.handle_booking_status_change() so that:
-- 1. COMPLETED bookings transition the goat to 'SOLD' (not 'COMPLETED').
-- 2. CANCELLED or EXPIRED bookings can never revert a 'SOLD' goat to 'AVAILABLE'.
-- 3. Preserves existing logic for RESERVED, CONFIRMED, active bookings,
--    and updated_at handling.
-- =============================================================================

CREATE OR REPLACE FUNCTION public.handle_booking_status_change()
RETURNS TRIGGER AS $$
DECLARE
    v_current_goat_status public.goat_status;
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        -- Completed booking marks the goat as permanently SOLD
        UPDATE public.goats
        SET status = 'SOLD',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status = 'CONFIRMED' THEN
        -- Confirmed booking keeps the goat as CONFIRMED
        UPDATE public.goats
        SET status = 'CONFIRMED',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status IN ('RESERVED', 'PENDING') THEN
        -- Reserved or Pending hold places the goat into RESERVED state
        UPDATE public.goats
        SET status = 'RESERVED',
            updated_at = NOW()
        WHERE id = NEW.goat_id;

    ELSIF NEW.status IN ('CANCELLED', 'EXPIRED') THEN
        -- Check current goat status before attempting to release to AVAILABLE.
        -- SOLD is permanent and must NEVER be reverted to AVAILABLE upon cancellation or expiration.
        SELECT status INTO v_current_goat_status
        FROM public.goats
        WHERE id = NEW.goat_id;

        IF v_current_goat_status IS DISTINCT FROM 'SOLD' THEN
            -- Only restore goat to AVAILABLE if no other active booking exists
            IF NOT EXISTS (
                SELECT 1 FROM public.bookings 
                WHERE goat_id = NEW.goat_id 
                  AND id != NEW.id 
                  AND status IN ('PENDING', 'RESERVED', 'CONFIRMED')
            ) THEN
                UPDATE public.goats
                SET status = 'AVAILABLE',
                    updated_at = NOW()
                WHERE id = NEW.goat_id
                  AND status != 'SOLD';
            END IF;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Ensure the trigger is active and attached on public.bookings
DROP TRIGGER IF EXISTS tr_booking_status_change ON public.bookings;
CREATE TRIGGER tr_booking_status_change
    AFTER INSERT OR UPDATE OF status ON public.bookings
    FOR EACH ROW EXECUTE FUNCTION public.handle_booking_status_change();
