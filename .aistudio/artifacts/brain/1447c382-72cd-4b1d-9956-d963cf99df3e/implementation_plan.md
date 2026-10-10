# Implementation Plan - Fix Pending Farm Status Mapping

## Problem
In `MarketplaceDto.kt` (`FarmDto.fromDomain`), non-Ammal farms with `VerificationStatus.PENDING` were being forced to save as `"APPROVED"` in Supabase due to a conditional check (`if (domain.verificationStatus == VerificationStatus.PENDING) "APPROVED" else domain.verificationStatus.name`). Consequently, pending farms were stored as APPROVED and failed to appear under the PENDING filter in the Super Admin farms console.

## Proposed Changes
1. **Fix FarmDto Status Mapping (`MarketplaceDto.kt`)**:
   - Change `status = if (isAmmal) "APPROVED" else (if (domain.verificationStatus == VerificationStatus.PENDING) "APPROVED" else domain.verificationStatus.name)` to:
     `status = if (isAmmal) "APPROVED" else domain.verificationStatus.name`
   - This ensures `PENDING`, `APPROVED`, `SUSPENDED`, and `REJECTED` statuses map correctly between domain models and Supabase DTOs.

2. **Verification & Testing**:
   - Compile the applet to verify correctness.
