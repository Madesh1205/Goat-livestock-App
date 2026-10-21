-- =============================================================================
-- AMMAL FARM / ADU SANTHAI - REALTIME NOTIFICATIONS PUBLICATION
-- Enable Supabase Realtime postgres_changes broadcast for public.notifications
-- =============================================================================

DO $$
BEGIN
    -- Ensure replica identity is set to FULL so update and delete events contain full row data
    ALTER TABLE public.notifications REPLICA IDENTITY FULL;

    -- Add public.notifications to supabase_realtime publication if not already present
    IF NOT EXISTS (
        SELECT 1
        FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime'
          AND schemaname = 'public'
          AND tablename = 'notifications'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.notifications;
    END IF;
END $$;
