-- Migration: Stage 9C - Sequential Farm and Goat IDs + Storage Path Standardization
-- Introduces farm_code (FARM-001...) and goat_code (GOAT-001...)
-- Server-side generated sequences while keeping existing UUID primary and foreign keys intact.

-- -----------------------------------------------------------------------------
-- 1. SEQUENTIAL FARM CODE SEQUENCE AND TRIGGER
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS public.farm_code_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE public.farms 
    ADD COLUMN IF NOT EXISTS farm_code TEXT UNIQUE;

-- Backfill existing farms ordered by created_at, id
DO $$
DECLARE
    r RECORD;
    v_seq INT := 1;
BEGIN
    FOR r IN 
        SELECT id 
        FROM public.farms 
        WHERE farm_code IS NULL OR TRIM(farm_code) = '' 
        ORDER BY created_at ASC, id ASC 
    LOOP
        UPDATE public.farms 
        SET farm_code = 'FARM-' || LPAD(v_seq::text, 3, '0') 
        WHERE id = r.id;
        v_seq := v_seq + 1;
    END LOOP;

    -- Adjust sequence nextval to start higher than current max
    SELECT COALESCE(MAX(NULLIF(regexp_replace(farm_code, '[^0-9]', '', 'g'), '')::INT), 0) + 1 
    INTO v_seq 
    FROM public.farms;

    IF v_seq < 1 THEN v_seq := 1; END IF;
    PERFORM setval('public.farm_code_seq', v_seq, false);
END $$;

-- Enforce NOT NULL on farm_code
ALTER TABLE public.farms 
    ALTER COLUMN farm_code SET NOT NULL;

-- Trigger Function for Farm Code Assignment
CREATE OR REPLACE FUNCTION public.assign_farm_code()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.farm_code IS NULL OR TRIM(NEW.farm_code) = '' THEN
            NEW.farm_code := 'FARM-' || LPAD(nextval('public.farm_code_seq')::text, 3, '0');
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.farm_code IS NOT NULL AND NEW.farm_code IS DISTINCT FROM OLD.farm_code THEN
            NEW.farm_code := OLD.farm_code; -- Prevent non-system mutation
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_assign_farm_code ON public.farms;
CREATE TRIGGER trg_assign_farm_code
    BEFORE INSERT OR UPDATE ON public.farms
    FOR EACH ROW EXECUTE FUNCTION public.assign_farm_code();


-- -----------------------------------------------------------------------------
-- 2. SEQUENTIAL GOAT CODE SEQUENCE AND TRIGGER
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS public.goat_code_seq START WITH 1 INCREMENT BY 1;

ALTER TABLE public.goats 
    ADD COLUMN IF NOT EXISTS goat_code TEXT UNIQUE;

-- Backfill existing goats ordered by created_at, id
DO $$
DECLARE
    r RECORD;
    v_seq INT := 1;
BEGIN
    FOR r IN 
        SELECT id 
        FROM public.goats 
        WHERE goat_code IS NULL OR TRIM(goat_code) = '' 
        ORDER BY created_at ASC, id ASC 
    LOOP
        UPDATE public.goats 
        SET goat_code = 'GOAT-' || LPAD(v_seq::text, 3, '0') 
        WHERE id = r.id;
        v_seq := v_seq + 1;
    END LOOP;

    SELECT COALESCE(MAX(NULLIF(regexp_replace(goat_code, '[^0-9]', '', 'g'), '')::INT), 0) + 1 
    INTO v_seq 
    FROM public.goats;

    IF v_seq < 1 THEN v_seq := 1; END IF;
    PERFORM setval('public.goat_code_seq', v_seq, false);
END $$;

-- Enforce NOT NULL on goat_code
ALTER TABLE public.goats 
    ALTER COLUMN goat_code SET NOT NULL;

-- Trigger Function for Goat Code Assignment
CREATE OR REPLACE FUNCTION public.assign_goat_code()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.goat_code IS NULL OR TRIM(NEW.goat_code) = '' THEN
            NEW.goat_code := 'GOAT-' || LPAD(nextval('public.goat_code_seq')::text, 3, '0');
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        IF OLD.goat_code IS NOT NULL AND NEW.goat_code IS DISTINCT FROM OLD.goat_code THEN
            NEW.goat_code := OLD.goat_code; -- Prevent non-system mutation
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER
SET search_path = public, pg_temp;

DROP TRIGGER IF EXISTS trg_assign_goat_code ON public.goats;
CREATE TRIGGER trg_assign_goat_code
    BEFORE INSERT OR UPDATE ON public.goats
    FOR EACH ROW EXECUTE FUNCTION public.assign_goat_code();


-- -----------------------------------------------------------------------------
-- 3. STORAGE PATH AND GOAT IMAGES STANDARDIZATION MIGRATION
-- -----------------------------------------------------------------------------
-- Standardize existing goat_images records to: FARM-XXX/GOAT-YYY/01.ext
DO $$
DECLARE
    r RECORD;
    v_farm_code TEXT;
    v_goat_code TEXT;
    v_old_path TEXT;
    v_clean_old_path TEXT;
    v_new_path TEXT;
    v_ext TEXT;
    v_pos TEXT;
BEGIN
    FOR r IN 
        SELECT gi.id, gi.goat_id, gi.image_url, gi.display_order, g.farm_id, f.farm_code, g.goat_code
        FROM public.goat_images gi
        JOIN public.goats g ON g.id = gi.goat_id
        JOIN public.farms f ON f.id = g.farm_id
        ORDER BY gi.goat_id, gi.display_order, gi.created_at
    LOOP
        v_farm_code := r.farm_code;
        v_goat_code := r.goat_code;
        v_old_path := r.image_url;
        v_clean_old_path := replace(v_old_path, 'goat-images/', '');
        
        -- Extract extension safely
        IF v_old_path LIKE '%.png' THEN v_ext := '.png';
        ELSIF v_old_path LIKE '%.webp' THEN v_ext := '.webp';
        ELSIF v_old_path LIKE '%.jpeg' THEN v_ext := '.jpeg';
        ELSE v_ext := '.jpg';
        END IF;

        v_pos := LPAD((COALESCE(r.display_order, 0) + 1)::text, 2, '0');
        v_new_path := v_farm_code || '/' || v_goat_code || '/' || v_pos || v_ext;
        
        -- Update public.goat_images
        UPDATE public.goat_images 
        SET image_url = v_new_path 
        WHERE id = r.id;

        -- Update storage.objects safely matching exact image path and preventing duplicate key collisions
        IF v_clean_old_path != v_new_path AND NOT EXISTS (
            SELECT 1 FROM storage.objects 
            WHERE bucket_id = 'goat-images' AND name = v_new_path
        ) THEN
            UPDATE storage.objects
            SET name = v_new_path
            WHERE bucket_id = 'goat-images'
              AND (name = v_old_path OR name = v_clean_old_path OR name = 'goat-images/' || v_clean_old_path);
        END IF;
    END LOOP;
END $$;
