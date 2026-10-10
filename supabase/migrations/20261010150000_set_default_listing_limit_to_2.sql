-- Set default goat listing limit to 2 for new partner farms across table defaults, triggers, and functions

ALTER TABLE public.farms ALTER COLUMN goat_listing_limit SET DEFAULT 2;

-- Update enforce_goat_listing_limit function to use default limit of 2 instead of 10
CREATE OR REPLACE FUNCTION public.enforce_goat_listing_limit()
RETURNS TRIGGER AS $$
DECLARE
    v_farm RECORD;
    v_current_count INT;
    v_limit INT;
    v_is_super_admin BOOLEAN := FALSE;
BEGIN
    IF auth.uid() IS NOT NULL THEN
        SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
        FROM public.profiles
        WHERE id = auth.uid();
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT * INTO v_farm FROM public.farms WHERE id = NEW.farm_id;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Associated farm not found.';
        END IF;

        IF v_farm.status != 'APPROVED' AND v_is_super_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Farm is not approved. Unapproved farms cannot list goats.';
        END IF;

        -- Count current approved/active goats for this farm
        SELECT COUNT(*) INTO v_current_count
        FROM public.goats
        WHERE farm_id = NEW.farm_id
          AND status != 'INACTIVE';

        v_limit := COALESCE(v_farm.goat_listing_limit, 2);

        IF v_current_count >= v_limit AND v_is_super_admin IS NOT TRUE THEN
            RAISE EXCEPTION 'Farm has reached its goat listing limit of % slots. Contact Super Admin for more slots.', v_limit;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
