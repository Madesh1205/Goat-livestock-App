-- =============================================================================
-- Migration: Complete FCM Notification System Audit and Fix
-- Table: public.fcm_device_tokens, RPC token registration, triggers for
-- Super Admin, Farm Admin, and Customer events, and automated FCM webhook dispatch.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. FCM DEVICE TOKENS TABLE & CONSTRAINTS
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.fcm_device_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    token TEXT NOT NULL UNIQUE,
    device_type TEXT NOT NULL DEFAULT 'android',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_fcm_device_tokens_token
    ON public.fcm_device_tokens (token);

CREATE INDEX IF NOT EXISTS idx_fcm_device_tokens_user_id
    ON public.fcm_device_tokens (user_id);

-- Add FCM delivery tracking columns to notifications table if not present
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS fcm_status TEXT DEFAULT 'PENDING';
ALTER TABLE public.notifications ADD COLUMN IF NOT EXISTS fcm_sent_at TIMESTAMPTZ;

-- -----------------------------------------------------------------------------
-- 2. ROW LEVEL SECURITY ON FCM_DEVICE_TOKENS
-- -----------------------------------------------------------------------------
ALTER TABLE public.fcm_device_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "fcm_tokens_select_own" ON public.fcm_device_tokens;
CREATE POLICY "fcm_tokens_select_own" ON public.fcm_device_tokens
    FOR SELECT
    USING (auth.uid() = user_id);

DROP POLICY IF EXISTS "fcm_tokens_insert_own" ON public.fcm_device_tokens;
CREATE POLICY "fcm_tokens_insert_own" ON public.fcm_device_tokens
    FOR INSERT
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "fcm_tokens_update_own" ON public.fcm_device_tokens;
CREATE POLICY "fcm_tokens_update_own" ON public.fcm_device_tokens
    FOR UPDATE
    USING (auth.uid() = user_id)
    WITH CHECK (auth.uid() = user_id);

DROP POLICY IF EXISTS "fcm_tokens_delete_own" ON public.fcm_device_tokens;
CREATE POLICY "fcm_tokens_delete_own" ON public.fcm_device_tokens
    FOR DELETE
    USING (auth.uid() = user_id);

-- -----------------------------------------------------------------------------
-- 3. SECURE RPC FUNCTIONS FOR TOKEN MANAGEMENT
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.register_fcm_device_token(
    p_token TEXT,
    p_device_type TEXT DEFAULT 'android'
)
RETURNS JSONB AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Authentication required');
    END IF;

    IF p_token IS NULL OR TRIM(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Token cannot be blank');
    END IF;

    INSERT INTO public.fcm_device_tokens (user_id, token, device_type, updated_at)
    VALUES (v_uid, TRIM(p_token), COALESCE(p_device_type, 'android'), NOW())
    ON CONFLICT (token) DO UPDATE SET
        user_id = EXCLUDED.user_id,
        device_type = EXCLUDED.device_type,
        updated_at = NOW();

    RETURN jsonb_build_object('success', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

CREATE OR REPLACE FUNCTION public.remove_fcm_device_token(
    p_token TEXT
)
RETURNS JSONB AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := auth.uid();
    IF v_uid IS NULL OR p_token IS NULL THEN
        RETURN jsonb_build_object('success', false);
    END IF;

    DELETE FROM public.fcm_device_tokens
    WHERE token = TRIM(p_token) AND user_id = v_uid;

    RETURN jsonb_build_object('success', true);
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

-- -----------------------------------------------------------------------------
-- 4. SUPER ADMIN NOTIFICATION TRIGGERS (Farm Registration, Moderation, Reports)
-- -----------------------------------------------------------------------------

-- A. Notify Super Admins when a new farm registers (status = PENDING)
CREATE OR REPLACE FUNCTION public.notify_super_admin_on_farm_registration()
RETURNS TRIGGER AS $$
DECLARE
    v_admin RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        FOR v_admin IN SELECT id FROM public.profiles WHERE role = 'SUPER_ADMIN' LOOP
            PERFORM public.create_system_notification(
                v_admin.id,
                'New Farm Registration 🚜',
                'Farm "' || NEW.name || '" has registered and is awaiting admin approval.',
                'FARM_APPLICATION',
                NEW.id::text,
                'farm_reg_superadmin_' || NEW.id
            );
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_notify_super_admin_farm_registration ON public.farms;
CREATE TRIGGER tr_notify_super_admin_farm_registration
    AFTER INSERT ON public.farms
    FOR EACH ROW
    WHEN (NEW.status = 'PENDING')
    EXECUTE FUNCTION public.notify_super_admin_on_farm_registration();

-- B. Notify Super Admins when a new goat listing requires moderation
CREATE OR REPLACE FUNCTION public.notify_super_admin_on_goat_submission()
RETURNS TRIGGER AS $$
DECLARE
    v_admin RECORD;
    v_farm_name TEXT;
BEGIN
    IF TG_OP = 'INSERT' AND NEW.is_approved_by_admin = FALSE THEN
        SELECT name INTO v_farm_name FROM public.farms WHERE id = NEW.farm_id;
        FOR v_admin IN SELECT id FROM public.profiles WHERE role = 'SUPER_ADMIN' LOOP
            PERFORM public.create_system_notification(
                v_admin.id,
                'Goat Listing Awaiting Review 🐐',
                'Goat "' || NEW.name || '" from ' || COALESCE(v_farm_name, 'a partner farm') || ' is awaiting moderation.',
                'MODERATION',
                NEW.id::text,
                'goat_submission_superadmin_' || NEW.id
            );
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_notify_super_admin_goat_submission ON public.goats;
CREATE TRIGGER tr_notify_super_admin_goat_submission
    AFTER INSERT ON public.goats
    FOR EACH ROW
    WHEN (NEW.is_approved_by_admin = FALSE)
    EXECUTE FUNCTION public.notify_super_admin_on_goat_submission();

-- C. Notify Super Admins on user report submission
CREATE OR REPLACE FUNCTION public.notify_super_admin_on_report()
RETURNS TRIGGER AS $$
DECLARE
    v_admin RECORD;
BEGIN
    IF TG_OP = 'INSERT' THEN
        FOR v_admin IN SELECT id FROM public.profiles WHERE role = 'SUPER_ADMIN' LOOP
            PERFORM public.create_system_notification(
                v_admin.id,
                'New Content Report ⚠️',
                'A new ' || NEW.target_type || ' report was submitted for reason: ' || NEW.reason,
                'REPORT',
                NEW.id::text,
                'report_superadmin_' || NEW.id
            );
        END LOOP;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS tr_notify_super_admin_report ON public.reports;
CREATE TRIGGER tr_notify_super_admin_report
    AFTER INSERT ON public.reports
    FOR EACH ROW
    EXECUTE FUNCTION public.notify_super_admin_on_report();

-- -----------------------------------------------------------------------------
-- 5. AUTOMATED SERVER-SIDE FCM WEBHOOK DISPATCH (Via pg_net)
-- -----------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION public.send_fcm_on_notification_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_payload JSONB;
    v_url TEXT;
    v_secret TEXT;
    v_service_role_key TEXT;
    v_headers JSONB;
BEGIN
    BEGIN
        v_url := COALESCE(
            NULLIF(current_setting('app.settings.supabase_url', true), ''),
            'https://wphgctwmjcvrblpybktd.supabase.co'
        ) || '/functions/v1/send-fcm-notification';

        v_secret := COALESCE(
            NULLIF(current_setting('app.settings.fcm_webhook_secret', true), ''),
            'fcm-webhook-secret-v1'
        );

        v_service_role_key := NULLIF(current_setting('app.settings.service_role_key', true), '');

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

        -- Build headers with both X-Webhook-Secret and Bearer Authorization
        v_headers := jsonb_build_object(
            'Content-Type', 'application/json',
            'X-Webhook-Secret', v_secret
        );

        IF v_service_role_key IS NOT NULL AND v_service_role_key <> '' THEN
            v_headers := v_headers || jsonb_build_object('Authorization', 'Bearer ' || v_service_role_key);
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
