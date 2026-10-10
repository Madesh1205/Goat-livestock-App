-- Notify both the customer and farm owner when a booking is cancelled.
-- This mirrors the migration already applied to the live Mumbai Supabase project.
-- Other booking lifecycle notifications and event keys are preserved.

CREATE OR REPLACE FUNCTION public.notify_booking_lifecycle()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    v_goat RECORD;
    v_farm RECORD;
    v_booking_ref TEXT;
BEGIN
    SELECT * INTO v_goat FROM public.goats WHERE id = NEW.goat_id;
    SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
    v_booking_ref := COALESCE(NEW.booking_code, SUBSTRING(NEW.id::text FROM 1 FOR 6));

    IF TG_OP = 'INSERT' THEN
        PERFORM public.create_system_notification(
            NEW.customer_id,
            'Reservation Active (24 Hours) 🐐',
            'Your reservation for ' || COALESCE(v_goat.name, 'Goat') || ' is active. Complete farm confirmation within 24 hours.',
            'BOOKING',
            NEW.id::text,
            'booking_created_' || NEW.id
        );

        IF v_farm.owner_id IS NOT NULL THEN
            PERFORM public.create_system_notification(
                v_farm.owner_id,
                'New Booking Received 📋',
                'New booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' (₹' || NEW.total_price || ').',
                'FARM_BOOKINGS',
                NEW.id::text,
                'booking_farm_notify_' || NEW.id
            );
        END IF;

    ELSIF TG_OP = 'UPDATE' AND OLD.status IS DISTINCT FROM NEW.status THEN
        IF NEW.status = 'CONFIRMED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Confirmed! ✅',
                'Your booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' has been confirmed by ' || COALESCE(v_farm.name, 'the farm') || '.',
                'BOOKING',
                NEW.id::text,
                'booking_confirmed_' || NEW.id
            );
        ELSIF NEW.status = 'COMPLETED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Purchase Completed 🎉',
                'Congratulations on your purchase of ' || COALESCE(v_goat.name, 'Goat') || '!',
                'BOOKING',
                NEW.id::text,
                'booking_completed_' || NEW.id
            );
        ELSIF NEW.status = 'CANCELLED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                'Booking Cancelled ❌',
                'Your booking #' || v_booking_ref || ' has been cancelled.',
                'BOOKING',
                NEW.id::text,
                'booking_cancelled_' || NEW.id
            );

            IF v_farm.owner_id IS NOT NULL
               AND v_farm.owner_id IS DISTINCT FROM NEW.customer_id THEN
                PERFORM public.create_system_notification(
                    v_farm.owner_id,
                    'Booking Cancelled ❌',
                    'Booking #' || v_booking_ref || ' for ' || COALESCE(v_goat.name, 'Goat') || ' has been cancelled.',
                    'FARM_BOOKINGS',
                    NEW.id::text,
                    'booking_cancelled_farm_' || NEW.id
                );
            END IF;
        ELSIF NEW.status = 'EXPIRED' THEN
            PERFORM public.create_system_notification(
                NEW.customer_id,
                '24-Hour Hold Expired ⏳',
                'Your 24-hour reservation hold on ' || COALESCE(v_goat.name, 'Goat') || ' has expired and was released.',
                'BOOKING',
                NEW.id::text,
                'booking_expired_' || NEW.id
            );
        END IF;
    END IF;

    RETURN NEW;
END;
$function$;
