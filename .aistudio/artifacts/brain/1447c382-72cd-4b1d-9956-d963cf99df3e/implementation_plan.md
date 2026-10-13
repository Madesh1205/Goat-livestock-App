# Implementation Plan - Comprehensive End-to-End Code Audit

Audit all primary user journeys (Customer, Farm Partner, and Super Admin) and verify complete functionality from signup/authentication through booking, listing management, admin approval, and database policies.

## User Review Required

> [!IMPORTANT]
> Please review the audit plan below covering the 3 user roles and backend architecture. Click **Proceed** to execute the audit and generate the comprehensive report.

---

## 1. Scope of Audit

### User Journey 1: Customer Flow
- **Sign Up & Login**: Validate registration, profile sync, session persistence, and default `CUSTOMER` role assignment.
- **Marketplace Browsing**: Validate goat listing display, search/filter logic, breed filtering, district location filtering, and farm seller details.
- **Booking / Ordering**: Validate booking modal, mandatory field validation, booking submission, and order history tracking (`OrdersScreen`).
- **Profile & Settings**: Validate profile updates, theme switching, and terms/privacy web views.

### User Journey 2: Farm Partner Flow
- **Registration**: Validate farm partner registration, automatic creation of farm record (`status = PENDING`, `goatListingLimit = 0`), and profile-to-farm linking.
- **Farm Portal Access Guard**: Verify `isFarmPartnerOrAdmin()` role guard prevents demotion to `CUSTOMER` upon navigating to the Farm Dashboard.
- **Goat Listing Management**: Verify adding, editing, and deleting goat listings, including strict enforcement of allocated listing quota slots.
- **Order Fulfillment**: Verify booking status transitions (CONFIRMED, REJECTED, COMPLETED) by farm owners.

### User Journey 3: Super Admin Flow
- **Admin Console Access**: Verify strict access control restricting `SuperAdminDashboard` to `SUPER_ADMIN` users.
- **Farm Approval Workflow**: Verify reviewing pending farm applications, approving/rejecting farms, and adjusting allocated listing quotas (`goat_listing_limit`).
- **Platform Analytics & Oversight**: Verify global listing views, order tracking, and platform status monitoring.

### Backend, Database & Security Audit
- **Supabase Integration & RLS**: Verify SQL tables (`profiles`, `farms`, `goats`, `bookings`), foreign keys, and Row-Level Security policies.
- **Repository Data Layer**: Verify error handling, coroutine flows, offline cache fallbacks (`FarmLocalCache`), and network resilience in `AuthRepository`, `GoatRepository`, and `MarketplaceViewModel`.
- **Compilation Check**: Execute `compile_applet` to confirm zero syntax or build errors across all files.

---

## Proposed Changes

1. **Perform Code Inspection Across Key Source Files**:
   - `MainActivity.kt` (routing, role guards, tab bar)
   - `AuthRepository.kt` & `AuthViewModel.kt` (authentication, profile/farm resolution)
   - `GoatRepository.kt` & `MarketplaceViewModel.kt` (data fetching, listing management, booking handlers)
   - `RegisterScreen.kt`, `ProfileScreen.kt`, `MarketplaceHomeScreen.kt`, `FarmAdminScreen.kt`, `SuperAdminDashboardScreen.kt` (UI views)
   - `supabase/migrations/` (RLS policies, triggers, and functions)

2. **Run Verification**:
   - Run `compile_applet` to verify compilation.
   - Summarize code audit results, highlighting tested components, verified guarantees, and current system health.

---

## Verification Plan

### Automated Build Verification
- Execute `compile_applet` to confirm complete applet compilation with zero errors.
