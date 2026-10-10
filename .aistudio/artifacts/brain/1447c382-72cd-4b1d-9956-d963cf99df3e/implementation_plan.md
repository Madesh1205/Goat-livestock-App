# Implementation Plan - Fix Pending Farm Admin Display in Super Admin Page

## Overview & Goal
Ensure newly registered pending partner farms are correctly fetched, mapped, and displayed in the Super Admin Farm Management page (`SuperAdminScreen.kt` & `SupabaseFarmRepositoryImpl.kt`), fixing status mapping discrepancies between Supabase (`farm_status` enum) and Android domain models.

## User Review & Critical Decisions
> [!IMPORTANT]
> - **Status Mapping**: Ensure `VerificationStatus` handles `PENDING`, `APPROVED`, `SUSPENDED`, and `REJECTED` robustly from Supabase strings.
> - **Fallback & Cache**: Ensure `SupabaseFarmRepositoryImpl` correctly merges remote pending farms into local cache and display lists without filtering them out incorrectly.

---

## 1. Technical Analysis & Root Cause
1. **Status Mapping in `FarmDto`**:
   - `status` column in Supabase `farms` table can be `"PENDING"`, `"APPROVED"`, etc.
   - `FarmDto.toDomain()` correctly parses `VerificationStatus.valueOf(...)`, but ensure fallback handles case-insensitivity or nulls safely as `VerificationStatus.PENDING` when unverified.
2. **Filtering in `FarmRepository`**:
   - `getAllFarmsForAdmin()` filters: `it.isAmmalOwnFarm || it.ownerId.isNotBlank()`.
   - Ensure pending farms created by farm admins have non-blank `owner_id` and are successfully fetched and returned in `getAllFarmsForAdmin()`.
3. **UI Display & Filter in `SuperAdminScreen`**:
   - Ensure the Super Admin farm tab (`SuperAdminFarmsTab`) and filters correctly show `PENDING` farms with actionable "Approve KYC" and "Reject" buttons.

---

## 2. Proposed Changes
- **`FarmDto` & `FarmRepository`**:
  - Audit `FarmDto.toDomain()` status parsing to ensure robust parsing of `"PENDING"`, `"APPROVED"`, `"SUSPENDED"`, `"REJECTED"`.
  - Ensure `getAllFarmsForAdmin()` and `fetchFarmsFromSupabase()` correctly retrieve and include all pending partner farms.
- **`SuperAdminScreen`**:
  - Verify `SuperAdminFarmsTab` displays `PENDING` status badge (orange/amber) and the approval actions correctly.

---

## 3. Verification Plan
- Compile the app using `compile_applet`.
- Verify no compilation errors and clean state handling.
