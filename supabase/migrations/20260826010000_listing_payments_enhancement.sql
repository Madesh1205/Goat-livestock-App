-- =============================================================================
-- AMMAL FARM PLATFORM - LISTING FEE PAYMENT SCHEMA & ATOMICITY POLICIES
-- =============================================================================

CREATE TABLE IF NOT EXISTS listing_payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    goat_id UUID REFERENCES goats(id) ON DELETE CASCADE,
    farm_id UUID REFERENCES farms(id) ON DELETE SET NULL,
    payer_id TEXT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL DEFAULT 100.00 CHECK (amount >= 0),
    currency TEXT NOT NULL DEFAULT 'INR',
    payment_type TEXT NOT NULL DEFAULT 'LISTING_FEE',
    payment_status TEXT NOT NULL DEFAULT 'PENDING',
    payment_gateway_ref TEXT,
    razorpay_payment_id TEXT,
    razorpay_signature TEXT,
    receipt_number TEXT UNIQUE,
    payment_date TIMESTAMPTZ,
    error_message TEXT,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Unique index to prevent duplicate successful payments on the same goat
CREATE UNIQUE INDEX IF NOT EXISTS idx_listing_payments_unique_paid_goat 
ON listing_payments(goat_id) 
WHERE payment_status = 'PAID';

-- Lookup Indexes
CREATE INDEX IF NOT EXISTS idx_listing_payments_goat_id ON listing_payments(goat_id);
CREATE INDEX IF NOT EXISTS idx_listing_payments_farm_id ON listing_payments(farm_id);
CREATE INDEX IF NOT EXISTS idx_listing_payments_order_id ON listing_payments(payment_gateway_ref);
CREATE INDEX IF NOT EXISTS idx_listing_payments_status ON listing_payments(payment_status);

-- Enable RLS
ALTER TABLE listing_payments ENABLE ROW LEVEL SECURITY;

-- Policies:
-- 1. Anyone authenticated can read payments for their farm or as Super Admin
CREATE POLICY "Farm Admins can view own farm payments"
ON listing_payments FOR SELECT
TO authenticated
USING (
    farm_id::text = auth.uid()::text 
    OR payer_id = auth.uid()::text
    OR EXISTS (SELECT 1 FROM profiles WHERE profiles.id = auth.uid() AND profiles.role = 'SUPER_ADMIN')
);

-- 2. System and Edge Functions with Service Role have full insert/update access
CREATE POLICY "Edge functions service role full access"
ON listing_payments FOR ALL
TO service_role
USING (true)
WITH CHECK (true);
