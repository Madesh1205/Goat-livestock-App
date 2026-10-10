-- Platform Pricing & Settings Table and RPC functions for Super Admin dynamic configuration

CREATE TABLE IF NOT EXISTS public.platform_settings (
    key TEXT PRIMARY KEY,
    value NUMERIC NOT NULL,
    updated_at TIMESTAMPTZ DEFAULT NOW(),
    updated_by UUID REFERENCES auth.users(id)
);

-- Insert default authoritative pricing and slot settings if not present
INSERT INTO public.platform_settings (key, value) VALUES
    ('farm_approval_fee', 500),
    ('additional_slot_price', 150),
    ('default_initial_slots', 2)
ON CONFLICT (key) DO NOTHING;

-- Enable RLS
ALTER TABLE public.platform_settings ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Authenticated users can read platform settings" ON public.platform_settings;
CREATE POLICY "Authenticated users can read platform settings"
    ON public.platform_settings
    FOR SELECT
    TO authenticated
    USING (TRUE);

DROP POLICY IF EXISTS "Super admin can modify platform settings" ON public.platform_settings;
CREATE POLICY "Super admin can modify platform settings"
    ON public.platform_settings
    FOR ALL
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = auth.uid() AND role = 'SUPER_ADMIN'
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM public.profiles
            WHERE id = auth.uid() AND role = 'SUPER_ADMIN'
        )
    );

-- RPC: Get Platform Settings
CREATE OR REPLACE FUNCTION public.get_platform_settings()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $$
DECLARE
    v_approval NUMERIC;
    v_slot NUMERIC;
    v_initial_slots NUMERIC;
BEGIN
    SELECT value INTO v_approval FROM public.platform_settings WHERE key = 'farm_approval_fee';
    SELECT value INTO v_slot FROM public.platform_settings WHERE key = 'additional_slot_price';
    SELECT value INTO v_initial_slots FROM public.platform_settings WHERE key = 'default_initial_slots';

    RETURN jsonb_build_object(
        'farm_approval_fee', COALESCE(v_approval, 500),
        'additional_slot_price', COALESCE(v_slot, 150),
        'default_initial_slots', COALESCE(v_initial_slots, 2)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.get_platform_settings() FROM PUBLIC;
REVOKE ALL ON FUNCTION public.get_platform_settings() FROM anon;
GRANT EXECUTE ON FUNCTION public.get_platform_settings() TO authenticated;

-- RPC: Update Platform Settings (Super Admin only)
CREATE OR REPLACE FUNCTION public.update_platform_settings(
    p_farm_approval_fee NUMERIC,
    p_additional_slot_price NUMERIC,
    p_default_initial_slots INTEGER DEFAULT 2
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public', 'pg_temp'
AS $$
DECLARE
    v_actor_id UUID := auth.uid();
    v_actor_role TEXT;
BEGIN
    IF v_actor_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required to update platform pricing.' USING ERRCODE = '42501';
    END IF;

    SELECT role INTO v_actor_role FROM public.profiles WHERE id = v_actor_id;
    IF v_actor_role IS DISTINCT FROM 'SUPER_ADMIN' THEN
        RAISE EXCEPTION 'Only Super Admin can update platform pricing.' USING ERRCODE = '42501';
    END IF;

    IF p_farm_approval_fee IS NULL OR p_farm_approval_fee < 0 OR
       p_additional_slot_price IS NULL OR p_additional_slot_price < 0 OR
       p_default_initial_slots IS NULL OR p_default_initial_slots < 0 THEN
        RAISE EXCEPTION 'Invalid pricing or slot values. Must be non-negative.' USING ERRCODE = '22023';
    END IF;

    INSERT INTO public.platform_settings (key, value, updated_at, updated_by)
    VALUES ('farm_approval_fee', p_farm_approval_fee, NOW(), v_actor_id)
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW(), updated_by = v_actor_id;

    INSERT INTO public.platform_settings (key, value, updated_at, updated_by)
    VALUES ('additional_slot_price', p_additional_slot_price, NOW(), v_actor_id)
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW(), updated_by = v_actor_id;

    INSERT INTO public.platform_settings (key, value, updated_at, updated_by)
    VALUES ('default_initial_slots', p_default_initial_slots, NOW(), v_actor_id)
    ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW(), updated_by = v_actor_id;

    RETURN jsonb_build_object(
        'success', TRUE,
        'farm_approval_fee', p_farm_approval_fee,
        'additional_slot_price', p_additional_slot_price,
        'default_initial_slots', p_default_initial_slots,
        'updated_at', NOW()
    );
END;
$$;

REVOKE ALL ON FUNCTION public.update_platform_settings(NUMERIC, NUMERIC, INTEGER) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.update_platform_settings(NUMERIC, NUMERIC, INTEGER) FROM anon;
GRANT EXECUTE ON FUNCTION public.update_platform_settings(NUMERIC, NUMERIC, INTEGER) TO authenticated;
