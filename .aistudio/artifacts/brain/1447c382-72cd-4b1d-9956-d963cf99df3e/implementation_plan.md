# Replace 'Goat breeder' with 'Farm owner' Terminology

## Summary
Update all user-facing strings, UI labels, dialogs, headers, and references in the Adu Santhai Android application from "Goat breeder" to "Farm owner" to match the user's branding and terminology preferences.

## 1. Overview & Scope
- **Objective**: Ensure professional and consistent terminology ("Farm owner") across all screens, navigation items, role badges, dialog titles, and notification messages.
- **Affected Components**: 
  - `strings.xml` (string resources)
  - Jetpack Compose UI screens (`SuperAdminScreen.kt`, `MarketplaceScreen.kt`, `ProfileScreen.kt`, authentication screens, dialogs, etc.)
  - ViewModel messages or toast notifications referencing "goat breeder".

## 2. Implementation Steps
1. **Search & Locate**: Find all occurrences of "Goat Breeder", "goat breeder", "GoatBreeder", etc., in `app/src/main/`.
2. **String Resources**: Update `res/values/strings.xml` labels and descriptions.
3. **Composable UI & Composables**: Update screen titles, button texts, cards, status chips, and helper text.
4. **Verification**: Compile the app using `compile_applet` to ensure zero compilation errors.
