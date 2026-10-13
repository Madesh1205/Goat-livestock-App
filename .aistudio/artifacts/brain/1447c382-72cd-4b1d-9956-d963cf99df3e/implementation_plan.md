# Implementation Plan - Fix Farm Admin Account Creation

## Problem
When a user registers as a Farm Partner (Farm Admin), the account is created as a Customer or fails to persist the `FARM_ADMIN` role and associated farm profile due to database trigger security guard constraints (`enforce_profile_security_guard`) overriding or preventing role updates from `CUSTOMER` to `FARM_ADMIN`.

## Proposed Changes

### 1. Database & Security Trigger Adjustment (`supabase/migrations/`)
- Update `enforce_profile_security_guard()` in database functions and migrations to allow the initial role assignment or allow setting `FARM_ADMIN` during profile creation/upsert when transitioning from unassigned or during signup, while still protecting against unauthorized privilege escalation to `SUPER_ADMIN`.
- Ensure `handle_new_auth_user()` and profile upsert logic in `AuthRepository` correctly handle `FARM_ADMIN` role persistence without being blocked by `NEW.role := OLD.role;`.

### 2. Authentication Repository & Local Fallback (`AuthRepository.kt`)
- Ensure `registerFarmAdmin` correctly passes and retains `FARM_ADMIN` role and provisions the farm record atomically.
- In local mock mode (`!SupabaseConfig.isConfigured`), ensure `registerFarmAdmin` correctly saves `FARM_ADMIN` role and farm association to persistent local cache and state.

### 3. Verification & Testing
- Test farm admin registration flow locally and via unit/Robolectric tests to confirm successful creation of Farm Admin accounts and farm profiles.

## Verification Plan
- Run tests and compile applet to verify clean build.
- Verify through code audit that farm admin registration successfully creates both user profile (`FARM_ADMIN`) and farm record (`VERIFICATION_STATUS = PENDING`).
