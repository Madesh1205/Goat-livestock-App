-- =============================================================================
-- AMMAL FARM PLATFORM - REMOVE OBSOLETE GOAT LISTING FEE TRIGGER
-- Fixes production booking failure ("record 'old' has no field 'listing_fee_paid'")
-- by dropping the obsolete trigger and trigger function from public.goats.
-- =============================================================================

-- Step 1: Drop the obsolete trigger from public.goats
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;

-- Step 2: Drop the obsolete trigger function
DROP FUNCTION IF EXISTS public.enforce_goat_listing_fee_rule();
