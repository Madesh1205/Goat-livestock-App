# Supabase Edge Functions Deployment (Mumbai: ap-south-1)

This guide details how to deploy the required Edge Functions to the target Mumbai project (`Ammal Farm App Mumbai`).

> ⚠️ **IMPORTANT**: Per project requirements:
> 1. The obsolete Razorpay Edge Function is **strictly excluded**.
> 2. JWT Verification is **ENABLED** (`verify_jwt = true`, default standard behavior) for all deployed functions to ensure that anonymous/unauthenticated callers without valid JWT or service-role bearer credentials cannot invoke them.

---

## 1. Required Functions Overview

| Function Name | Location | Purpose | JWT Verification Policy | Invocation |
| :--- | :--- | :--- | :--- | :--- |
| `expire-bookings` | `/supabase/functions/expire-bookings` | Evaluates active bookings with 48h hold expiry; marks them `EXPIRED` and restores goat `AVAILABLE` status | **JWT Verification Enabled** (Standard/Protected) | Invoked by pg_net (with Service Role key) or authenticated Super Admin |
| `send-fcm-notification` | `/supabase/functions/send-fcm-notification` | Dispatches Firebase Cloud Messaging (FCM) push notifications on booking/farm events | **JWT Verification Enabled** (Standard/Protected) | Invoked by authenticated app users / Service Role |

---

## 2. Prerequisites

1. Install Supabase CLI:
   ```bash
   npm install -g supabase
   # or
   brew install supabase/tap/supabase
   ```

2. Login to Supabase CLI:
   ```bash
   supabase login
   ```

3. Link CLI to the new Mumbai project:
   ```bash
   supabase link --project-ref <MUMBAI_PROJECT_REF>
   ```

---

## 3. Function Deployment Commands (Protected / JWT Verified)

Deploy each required function with JWT verification active:

```bash
# Deploy expire-bookings with standard JWT verification enabled
supabase functions deploy expire-bookings --project-ref <MUMBAI_PROJECT_REF>

# Deploy send-fcm-notification with standard JWT verification enabled
supabase functions deploy send-fcm-notification --project-ref <MUMBAI_PROJECT_REF>
```

*(Note: Do NOT use `--no-verify-jwt`. By omitting `--no-verify-jwt`, Supabase Gateway enforces JWT signature validation on the API gateway before executing function code).*

---

## 4. Scheduling `expire_overdue_bookings()` via pg_cron (Database Level)

In Mumbai Supabase SQL Editor:

```sql
-- 1. Enable pg_cron extension
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- 2. Schedule 5-minute execution of server-authoritative stored procedure
-- Removes previous hourly schedule if present
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_proc p 
        JOIN pg_namespace n ON p.pronamespace = n.oid 
        WHERE n.nspname = 'cron' AND p.proname = 'schedule'
    ) THEN
        BEGIN
            PERFORM cron.unschedule('hourly-booking-expiration');
        EXCEPTION WHEN OTHERS THEN NULL;
        END;

        BEGIN
            PERFORM cron.unschedule('expire-overdue-bookings-every-5-min');
        EXCEPTION WHEN OTHERS THEN NULL;
        END;

        PERFORM cron.schedule(
            'expire-overdue-bookings-every-5-min',
            '*/5 * * * *', -- Every 5 minutes
            'SELECT public.expire_overdue_bookings();'
        );
    END IF;
END $$;
```
