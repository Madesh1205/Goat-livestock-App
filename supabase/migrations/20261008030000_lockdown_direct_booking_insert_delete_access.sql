-- =============================================================================
-- AMMAL FARM PLATFORM - LOCK DOWN DIRECT BOOKING INSERT AND DELETE ACCESS
-- 
-- Requirements:
-- 1. Drop direct INSERT policy on public.bookings (bookings_insert_policy).
-- 2. Drop direct DELETE policy on public.bookings (bookings_delete_policy).
-- 3. Keep bookings_select_policy unchanged.
-- 4. Do NOT create replacement INSERT or DELETE policies.
-- 5. Do NOT create a direct UPDATE policy.
--
-- Server-authoritative booking management is enforced exclusively through
-- SECURITY DEFINER RPCs:
-- - public.create_booking_hold(...)
-- - public.cancel_booking(...)
-- - public.confirm_booking(...)
-- - public.complete_booking(...)
-- - public.expire_overdue_bookings(...)
-- =============================================================================

-- Step 1: Drop bookings_insert_policy if it exists
DROP POLICY IF EXISTS "bookings_insert_policy" ON public.bookings;
DROP POLICY IF EXISTS bookings_insert_policy ON public.bookings;

-- Step 2: Drop bookings_delete_policy if it exists
DROP POLICY IF EXISTS "bookings_delete_policy" ON public.bookings;
DROP POLICY IF EXISTS bookings_delete_policy ON public.bookings;

-- Step 3: Ensure any legacy or lingering bookings_update_policy is also dropped so no direct UPDATE is permitted
DROP POLICY IF EXISTS "bookings_update_policy" ON public.bookings;
DROP POLICY IF EXISTS bookings_update_policy ON public.bookings;
