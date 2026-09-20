-- ============================================================================
-- Stage 12B Migration — Enable Supabase Realtime for Notifications Table
-- ============================================================================
-- Fixes: E/Supabase-Realtime: Received message without event error
-- Ensures that the public.notifications table is published in the supabase_realtime
-- publication and that REPLICA IDENTITY IS FULL for realtime postgres_changes listeners.
-- ============================================================================

DO $$
BEGIN
    -- 1. Ensure publication supabase_realtime exists
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication WHERE pubname = 'supabase_realtime'
    ) THEN
        CREATE PUBLICATION supabase_realtime;
    END IF;

    -- 2. Add public.notifications to publication if not already added
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'notifications'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
    END IF;

    -- 3. Add public.bookings to publication if not already added
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'bookings'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.bookings;
    END IF;

    -- 4. Add public.goats to publication if not already added
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables 
        WHERE pubname = 'supabase_realtime' 
          AND schemaname = 'public' 
          AND tablename = 'goats'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.goats;
    END IF;
END $$;

-- Set replica identity to FULL for notifications, bookings, and goats
ALTER TABLE public.notifications REPLICA IDENTITY FULL;
ALTER TABLE public.bookings REPLICA IDENTITY FULL;
ALTER TABLE public.goats REPLICA IDENTITY FULL;
