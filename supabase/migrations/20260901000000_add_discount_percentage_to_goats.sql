-- File: /supabase/migrations/20260901000000_add_discount_percentage_to_goats.sql
-- Add discount_percentage to goats table (numeric 0 to 100 with default 0.0)

ALTER TABLE public.goats
ADD COLUMN IF NOT EXISTS discount_percentage NUMERIC(5, 2) NOT NULL DEFAULT 0.00
CHECK (discount_percentage >= 0 AND discount_percentage <= 100);

COMMENT ON COLUMN public.goats.discount_percentage IS 'Discount percentage from 0 to 100 offered on goat price';
