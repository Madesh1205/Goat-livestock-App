-- Migration: 20260909000000_enforce_unpaid_partner_listing_approval_restriction.sql
-- Hardens enforce_goat_listing_fee_rule trigger:
-- 1. Unpaid partner listings (listing_fee_paid IS NOT TRUE) CANNOT be approved by Super Admin.
-- 2. Ammal Farm listings (listing_fee = ₹0, is_ammal_own_farm = TRUE) remain auto-approved / waiver eligible.
-- 3. Verified paid partner listings can proceed through approval workflow normally.

CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()
RETURNS TRIGGER AS $$
DECLARE
    v_is_super_admin BOOLEAN := FALSE;
    v_is_ammal BOOLEAN := FALSE;
BEGIN
    -- Check if current user is Super Admin
    SELECT (role = 'SUPER_ADMIN') INTO v_is_super_admin
    FROM public.profiles
    WHERE id = auth.uid();

    -- Determine if the target farm is Ammal Farm
    SELECT (f.is_ammal_own_farm = TRUE OR f.id = '00000000-0000-0000-0000-000000000001'::uuid)
    INTO v_is_ammal
    FROM public.farms f
    WHERE f.id = NEW.farm_id;

    IF TG_OP = 'INSERT' THEN
        IF v_is_ammal = TRUE THEN
            -- Ammal Farm: Listings are auto-approved by administrator and zero fee
            NEW.listing_fee_amount := 0.00;
            NEW.listing_fee_paid := TRUE;
            NEW.is_approved_by_admin := TRUE;
        ELSE
            -- Partner Farm: Pending administrator approval and unpaid on creation
            NEW.listing_fee_amount := 100.00;
            NEW.listing_fee_paid := FALSE;
            NEW.is_approved_by_admin := FALSE;
        END IF;
    ELSIF TG_OP = 'UPDATE' THEN
        -- Prevent untrusted non-admin users from self-approving their listings
        IF auth.uid() IS NOT NULL AND v_is_super_admin IS NOT TRUE THEN
            IF OLD.is_approved_by_admin IS DISTINCT FROM NEW.is_approved_by_admin THEN
                RAISE EXCEPTION 'Only Super Admin can modify listing approval status.';
            END IF;

            -- Non-super admins cannot directly set listing_fee_paid to TRUE unless in secure verification RPC
            IF OLD.listing_fee_paid IS FALSE AND NEW.listing_fee_paid IS TRUE THEN
                IF current_setting('ammal.in_payment_verification', true) != 'true' THEN
                    RAISE EXCEPTION 'Clients cannot directly mark listing_fee_paid = true. Verified payment required.';
                END IF;
            END IF;

            -- Non-super admins cannot alter listing_fee_amount
            IF OLD.listing_fee_amount IS DISTINCT FROM NEW.listing_fee_amount THEN
                RAISE EXCEPTION 'Listing fee amount is server-authoritative and cannot be modified by client.';
            END IF;
        END IF;

        -- Hardened Rule: Unpaid partner listings cannot be approved even by Super Admin until ₹100 listing fee is paid and verified
        IF NEW.is_approved_by_admin = TRUE AND v_is_ammal IS NOT TRUE AND NEW.listing_fee_paid IS NOT TRUE THEN
            RAISE EXCEPTION 'Unpaid partner listing cannot be approved: ₹100 listing fee has not been paid and verified.';
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

-- Re-attach trigger
DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats;
CREATE TRIGGER tr_enforce_goat_listing_fee
BEFORE INSERT OR UPDATE ON public.goats
FOR EACH ROW EXECUTE FUNCTION public.enforce_goat_listing_fee_rule();
