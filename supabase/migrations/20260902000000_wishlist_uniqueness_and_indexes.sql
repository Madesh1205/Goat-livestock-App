-- =============================================================================
-- AMMAL FARM PLATFORM - WISHLIST DATABASE ENHANCEMENT & UNIQUENESS ENFORCEMENT
-- Migration: 20260902000000_wishlist_uniqueness_and_indexes.sql
-- Enforces UNIQUE(user_id, goat_id) constraint and ensures indexes exist
-- =============================================================================

DO $$
BEGIN
    -- Ensure unique constraint exists on (user_id, goat_id)
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_user_goat_wishlist'
    ) THEN
        -- Remove duplicate wishlist entries if any exist before applying constraint
        DELETE FROM public.wishlist a USING public.wishlist b
        WHERE a.id < b.id AND a.user_id = b.user_id AND a.goat_id = b.goat_id;

        ALTER TABLE public.wishlist ADD CONSTRAINT uq_user_goat_wishlist UNIQUE (user_id, goat_id);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_wishlist_user_id ON public.wishlist(user_id);
CREATE INDEX IF NOT EXISTS idx_wishlist_goat_id ON public.wishlist(goat_id);
