-- ============================================================================
-- MIGRATION: 20260918020000_stage3a_supabase_cron_24h_expiration.sql
-- Description: Stage 3A — Configure 24-hour booking expiration with Supabase Cron.
-- 
-- Invariants & Guarantees:
-- 1. Uses existing server-authoritative function: public.expire_overdue_bookings()
--    - Finds active bookings where hold_expires_at <= NOW()
--    - Changes booking status to EXPIRED
--    - Releases associated goat to AVAILABLE (only if no other valid active booking)
--    - Safe / idempotent if executed repeatedly
--    - Operates entirely server-side without depending on the Android app being open
-- 2. Supabase Cron (pg_cron):
--    - Configures recurring cron job: 'expire-overdue-bookings-every-5-min'
--    - Interval: '*/5 * * * *' (Every 5 minutes)
--    - Exact SQL executed: SELECT public.expire_overdue_bookings();
--    - 24-hour duration is strictly determined by hold_expires_at, NOT by cron interval
-- 3. Concurrency & Integrity:
--    - Row-level locks (FOR UPDATE) prevent concurrent double-booking
--    - Customer manual cancellation transitions booking to CANCELLED and immediately releases goat
--    - Expired bookings cannot continue blocking goats
-- ============================================================================

-- Step 1: Ensure public.expire_overdue_bookings() is authoritative and idempotent
CREATE OR REPLACE FUNCTION public.expire_overdue_bookings()
RETURNS INTEGER AS $$
DECLARE
    v_count INTEGER;
BEGIN
    -- Expire any active holds (PENDING or RESERVED) that have reached their hold_expires_at
    -- Explicitly leaves CONFIRMED and COMPLETED bookings untouched
    WITH expired_records AS (
        UPDATE public.bookings
        SET status = 'EXPIRED',
            updated_at = NOW()
        WHERE status IN ('PENDING', 'RESERVED')
          AND hold_expires_at <= NOW()
        RETURNING id, goat_id
    ),
    released_goats AS (
        UPDATE public.goats g
        SET status = 'AVAILABLE',
            updated_at = NOW()
        FROM expired_records er
        WHERE g.id = er.goat_id
          AND NOT EXISTS (
              SELECT 1 FROM public.bookings b
              WHERE b.goat_id = er.goat_id
                AND b.status IN ('PENDING', 'RESERVED', 'CONFIRMED')
                AND (b.hold_expires_at IS NULL OR b.hold_expires_at > NOW())
          )
        RETURNING g.id
    )
    SELECT COUNT(*) INTO v_count FROM expired_records;

    RETURN v_count;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

-- Step 2: Grant execute permissions to database administrator and service role
GRANT EXECUTE ON FUNCTION public.expire_overdue_bookings() TO postgres, service_role;

-- Step 3: Enable pg_cron extension if not already enabled
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- Step 4: Schedule recurring 5-minute Supabase Cron job
-- Safely unschedule any legacy or duplicate jobs before registering the 5-minute cron
DO $$
BEGIN
    -- Check if pg_cron extension and cron schema exist
    IF EXISTS (
        SELECT 1 FROM pg_proc p 
        JOIN pg_namespace n ON p.pronamespace = n.oid 
        WHERE n.nspname = 'cron' AND p.proname = 'schedule'
    ) THEN
        -- Remove legacy hourly job if present
        BEGIN
            PERFORM cron.unschedule('hourly-booking-expiration');
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;

        -- Remove existing 5-minute job if present to avoid duplicates
        BEGIN
            PERFORM cron.unschedule('expire-overdue-bookings-every-5-min');
        EXCEPTION WHEN OTHERS THEN
            NULL;
        END;

        -- Schedule the recurring job every 5 minutes
        PERFORM cron.schedule(
            'expire-overdue-bookings-every-5-min',
            '*/5 * * * *',
            'SELECT public.expire_overdue_bookings();'
        );
    END IF;
END $$;
