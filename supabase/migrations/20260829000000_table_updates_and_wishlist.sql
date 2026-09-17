-- =============================================================================
-- AMMAL FARM PLATFORM - DATABASE ENHANCEMENT & SCHEMA RECONCILIATION
-- Migration: 20260829000000_table_updates_and_wishlist.sql
-- Ensures all columns, wishlist table, and indexes are present
-- =============================================================================

-- 1. PROFILES ENHANCEMENT
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS is_suspended BOOLEAN DEFAULT FALSE;

-- 2. FARMS ENHANCEMENT
ALTER TABLE farms ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;
ALTER TABLE farms ADD COLUMN IF NOT EXISTS logo_url TEXT;
ALTER TABLE farms ADD COLUMN IF NOT EXISTS banner_url TEXT;

-- 3. GOATS ENHANCEMENT
ALTER TABLE goats ADD COLUMN IF NOT EXISTS vaccination_status TEXT DEFAULT 'Fully Vaccinated';
ALTER TABLE goats ADD COLUMN IF NOT EXISTS dewormed_date DATE;
ALTER TABLE goats ADD COLUMN IF NOT EXISTS parentage_father_tag TEXT;
ALTER TABLE goats ADD COLUMN IF NOT EXISTS parentage_mother_tag TEXT;
ALTER TABLE goats ADD COLUMN IF NOT EXISTS is_featured BOOLEAN DEFAULT FALSE;

-- 4. WISHLIST TABLE
CREATE TABLE IF NOT EXISTS wishlist (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
    goat_id UUID NOT NULL REFERENCES goats(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_goat_wishlist UNIQUE (user_id, goat_id)
);

CREATE INDEX IF NOT EXISTS idx_wishlist_user_id ON wishlist(user_id);
CREATE INDEX IF NOT EXISTS idx_wishlist_goat_id ON wishlist(goat_id);

ALTER TABLE wishlist ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view own wishlist" ON wishlist;
CREATE POLICY "Users can view own wishlist" ON wishlist
    FOR SELECT TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can insert into own wishlist" ON wishlist;
CREATE POLICY "Users can insert into own wishlist" ON wishlist
    FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete from own wishlist" ON wishlist;
CREATE POLICY "Users can delete from own wishlist" ON wishlist
    FOR DELETE TO authenticated
    USING (auth.uid() = user_id);

-- 5. REVIEWS ENHANCEMENT
ALTER TABLE reviews ADD COLUMN IF NOT EXISTS is_approved BOOLEAN DEFAULT TRUE;

-- 6. NOTIFICATIONS RLS POLICIES
DROP POLICY IF EXISTS "Users can view own notifications" ON notifications;
CREATE POLICY "Users can view own notifications" ON notifications
    FOR SELECT TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can update own notifications" ON notifications;
CREATE POLICY "Users can update own notifications" ON notifications
    FOR UPDATE TO authenticated
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "Users can delete own notifications" ON notifications;
CREATE POLICY "Users can delete own notifications" ON notifications
    FOR DELETE TO authenticated
    USING (auth.uid() = user_id);
