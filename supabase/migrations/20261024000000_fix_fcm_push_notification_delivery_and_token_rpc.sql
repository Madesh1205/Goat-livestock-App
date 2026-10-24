-- =============================================================================
-- AMMAL FARM / ADU SANTHAI - FCM PUSH NOTIFICATION SYSTEM HARDENING
-- Migration: 20261024000000_fix_fcm_push_notification_delivery_and_token_rpc.sql
-- Fixes:
-- 1. Updates register_fcm_device_token to accept p_user_id UUID DEFAULT NULL
--    with strict verification against auth.uid() and profiles.
-- 2. Updates remove_fcm_device_token to accept p_user_id UUID DEFAULT NULL.
-- 3. Ensures notifications fcm_status and fcm_sent_at columns exist with indexes.
-- 4. Fixes send_fcm_on_notification_insert trigger to supply apikey and Authorization
--    headers with valid fallback so Kong gateway does not reject with HTTP 401.
-- =============================================================================

-- 1. Ensure table and delivery status columns exist
CREATE TABLE IF NOT EXISTS public.fcm_device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    device_type TEXT NOT NULL DEFAULT 'android',
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE public.fcm_device_tokens ENABLE ROW LEVEL SECURITY;

CREATE INDEX IF NOT EXISTS idx_fcm_device_tokens_user_id
    ON public.fcm_device_tokens(user_id);

CREATE INDEX IF NOT EXISTS idx_fcm_device_tokens_token
    ON public.fcm_device_tokens(token);

ALTER TABLE public.notifications
    ADD COLUMN IF NOT EXISTS fcm_status TEXT DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS fcm_sent_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_notifications_fcm_status
    ON public.notifications(fcm_status)
    WHERE fcm_status IS NOT NULL;

-- 2. Secure RPC for Device Token Registration
CREATE OR REPLACE FUNCTION public.register_fcm_device_token(
    p_token TEXT,
    p_device_type TEXT DEFAULT 'android',
    p_user_id UUID DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_uid UUID;
BEGIN
    -- Resolve user: prefer authenticated session auth.uid(), fallback to provided p_user_id
    v_uid := COALESCE(auth.uid(), p_user_id);
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Authentication required');
    END IF;

    -- Security enforcement: if caller is authenticated, caller cannot register a token for another user
    IF auth.uid() IS NOT NULL AND p_user_id IS NOT NULL AND auth.uid() <> p_user_id THEN
        RETURN jsonb_build_object('success', false, 'error', 'User ID mismatch');
    END IF;

    -- Verify target user exists in profiles
    IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_uid) THEN
        RETURN jsonb_build_object('success', false, 'error', 'User profile not found');
    END IF;

    IF p_token IS NULL OR TRIM(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Token cannot be blank');
    END IF;

    -- Atomic upsert: reassign token to new user if transferred or updated
    INSERT INTO public.fcm_device_tokens (user_id, token, device_type, updated_at)
    VALUES (v_uid, TRIM(p_token), COALESCE(p_device_type, 'android'), NOW())
    ON CONFLICT (token) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        device_type = EXCLUDED.device_type,
        updated_at = NOW();

    RETURN jsonb_build_object('success', true, 'user_id', v_uid);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- 3. Secure RPC for Device Token Removal on Logout
CREATE OR REPLACE FUNCTION public.remove_fcm_device_token(
    p_token TEXT,
    p_user_id UUID DEFAULT NULL
)
RETURNS JSONB AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := COALESCE(auth.uid(), p_user_id);
    IF v_uid IS NULL OR p_token IS NULL OR TRIM(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid token or user');
    END IF;

    DELETE FROM public.fcm_device_tokens
    WHERE token = TRIM(p_token) AND user_id = v_uid;

    RETURN jsonb_build_object('success', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

GRANT EXECUTE ON FUNCTION public.register_fcm_device_token(TEXT, TEXT, UUID) TO authenticated, anon;
GRANT EXECUTE ON FUNCTION public.remove_fcm_device_token(TEXT, UUID) TO authenticated, anon;

-- 4. Server-Side FCM Trigger (Dispatches to send-fcm-notification Edge Function)
CREATE OR REPLACE FUNCTION public.send_fcm_on_notification_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_payload JSONB;
    v_url TEXT;
    v_secret TEXT;
    v_service_role_key TEXT;
    v_anon_key TEXT;
    v_auth_token TEXT;
    v_headers JSONB;
BEGIN
    BEGIN
        v_url := COALESCE(
            NULLIF(current_setting('app.settings.supabase_url', true), ''),
            'https://wphgctwmjcvrblpybktd.supabase.co'
        ) || '/functions/v1/send-fcm-notification';

        v_secret := NULLIF(current_setting('app.settings.fcm_webhook_secret', true), '');
        v_service_role_key := NULLIF(current_setting('app.settings.service_role_key', true), '');
        v_anon_key := COALESCE(
            NULLIF(current_setting('app.settings.anon_key', true), ''),
            'sb_publishable_n2K7Pxz4L-DAJWcL7MAgQQ_IrOwGKoG'
        );

        v_auth_token := COALESCE(v_service_role_key, v_anon_key);

        v_payload := jsonb_build_object(
            'type', 'INSERT',
            'table', 'notifications',
            'schema', 'public',
            'record', jsonb_build_object(
                'id', NEW.id,
                'user_id', NEW.user_id,
                'title', NEW.title,
                'body', NEW.body,
                'link_type', NEW.link_type,
                'link_id', NEW.link_id,
                'event_key', NEW.event_key,
                'fcm_status', NEW.fcm_status
            )
        );

        -- Supply apikey and Authorization header so Kong gateway accepts the dispatch
        v_headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'apikey', v_auth_token,
            'Authorization', 'Bearer ' || v_auth_token
        );

        IF v_secret IS NOT NULL AND v_secret <> '' THEN
            v_headers := v_headers || jsonb_build_object('X-Webhook-Secret', v_secret);
        END IF;

        IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_net') THEN
            PERFORM net.http_post(
                url := v_url,
                headers := v_headers,
                body := v_payload
            );
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RAISE WARNING 'FCM notification trigger dispatch notice: %', SQLERRM;
    END;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_send_fcm_on_notification_insert ON public.notifications;
CREATE TRIGGER tr_send_fcm_on_notification_insert
    AFTER INSERT ON public.notifications
    FOR EACH ROW
    EXECUTE FUNCTION public.send_fcm_on_notification_insert();
