# Implementation Plan - Production Polish, Logo Branding & Notification Icon

Integrate the user-uploaded `logo.png` and `ic_notification.png` into the Android application, configure adaptive app icons across all densities, set up production notification iconography, and clean up test placeholders.

## Proposed Changes

### 1. App Launcher Icon (`app-icon-generation`)
- **Process Uploaded Logo**: Use the 1254x1254 `logo.png` from the workspace to create:
  - High-res drawable asset `app/src/main/res/drawable/ic_launcher_foreground_asset.png`.
  - In-app brand logo `app/src/main/res/drawable/img_adu_santhai_logo.png`.
  - Android adaptive icon foreground layer (`res/drawable/ic_launcher_foreground.xml`) centered inside the 66dp safe zone.
  - Android adaptive icon background layer (`res/drawable/ic_launcher_background.xml`) with a matching primary brand background tone (`#1E3A1E` / `#0D2818`).
- **Multi-Density Raster Mipmaps**:
  - Generate crisp PNGs for `mipmap-mdpi` (48px), `mipmap-hdpi` (72px), `mipmap-xhdpi` (96px), `mipmap-xxhdpi` (144px), and `mipmap-xxxhdpi` (192px).
  - Generate true round masked icons (`ic_launcher_round.png`) with transparent corners for all densities.
  - Clean up any obsolete `.webp` template launcher icons to prevent AAPT2 conflicts.

### 2. Custom Notification Icon
- **Process Notification Icon**: Use `/app/applet/ic_notification.png` from the workspace to create:
  - Clean monochrome/silhouette drawable `app/src/main/res/drawable/ic_notification.png` and standard notification densities (`drawable-mdpi`, `drawable-hdpi`, `drawable-xhdpi`, `drawable-xxhdpi`, `drawable-xxxhdpi`).
  - Ensure the notification builder in notification handlers (`AmmalNotificationService`, `NotificationHelper`) references `R.drawable.ic_notification`.

### 3. In-App Brand Polish & Splash Screen (`design-guidelines`)
- **Splash Screen Branding**:
  - Integrate `splash-dark.png` and `Adu Santhai splash screen branding.png` into the splash screen experience.
  - Ensure the splash screen displays the authentic logo with crisp vector/bitmap scaling and smooth transition into the main marketplace.
- **Top App Bar & Auth Screens**:
  - Ensure the top navigation bar and authentication headers (Login, Register, Partner Farm Onboarding) use the new logo.
  - Remove placeholder texts, generic test strings, or unstyled badges.

### 4. Production Hygiene & Test Suite Alignment
- **Goat Model Extensions & Wishlist**:
  - Add standard helper properties/getters on `Goat` (`hasDiscount`, `formattedPrice`, `formattedFinalPrice`, `formattedDiscountBadge`) to satisfy tests and UI formatting.
  - Ensure `WishlistRepository` and `SupabaseWishlistRepositoryImpl` are cleanly defined and referenced.
- **Strings & Platform Identity**:
  - Confirm `app_name` in `strings.xml` and `metadata.json` are consistent as "Adu Santhai" (Ammal Farm Marketplace).
- **Verification**:
  - Run `compile_applet` and `gradle :app:testDebugUnitTest` to guarantee 100% build and test pass rate.

## Verification Plan
1. **Visual & Asset Verification**:
   - Verify all mipmap densities for `ic_launcher.png`, `ic_launcher_round.png`, and `ic_notification.png`.
   - Verify that `ic_launcher_foreground.xml` and `ic_launcher_background.xml` build without resource errors.
2. **App Compilation**:
   - Run `compile_applet` to ensure full build success.
3. **Automated Unit Tests**:
   - Execute `gradle :app:testDebugUnitTest` to guarantee all tests pass cleanly.
