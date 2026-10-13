-- Migration: Fix Stale Listing Fee Trigger and Booking Schema
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
BEGIN
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION public.enforce_booking_amount_immutability()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.total_price IS DISTINCT FROM OLD.total_price THEN
        RAISE EXCEPTION 'Booking total price cannot be modified.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
