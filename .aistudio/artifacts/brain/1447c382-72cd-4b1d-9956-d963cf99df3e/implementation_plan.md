# Implementation Plan - District Location Cleanup & Deduplication

Fix the location selector and filter options to display **only clean active district names** (e.g. "Vellore", "Coimbatore", "Salem") without state repetitions ("Tamil Nadu", "Vellore, Tamil Nadu"). Apply location cleaning across the entire application including goat cards and detail screens.

---

## User Preferences & Requirements
1. **Active Districts Only**: The location selector and filter options will list only active districts that have available goats or farms.
2. **Clean District Display Everywhere**: Remove "Tamil Nadu", "TN", "India", and redundant comma-separated state suffixes (e.g., "Vellore, Tamil Nadu") across all screens, dropdowns, cards, and detail views.

---

## Planned Changes

### 1. Location Parsing & Extraction Utility (`FarmLocations.kt`)
- Implement `FarmLocations.cleanDistrictName(rawLocation: String): String`:
  - Strips state descriptors ("Tamil Nadu", "TamilNadu", "TN", "India").
  - Extracts the primary district name from composite strings (e.g., "Vellore, Tamil Nadu" -> "Vellore").
- Implement `FarmLocations.extractActiveDistricts(farms: List<Farm>, goats: List<GoatListing>): List<String>`:
  - Collects locations from active farms and goats.
  - Cleans each location, removes state names and blank strings, deduplicates, and returns a sorted list of distinct district names.

### 2. Location Selector & Dropdown (`MarketplaceHomeScreen.kt`)
- Update `availableLocations` computation to use `FarmLocations.extractActiveDistricts()`.
- Ensure the dropdown menu contains "All Locations" at the top followed strictly by clean district names.
- Ensure active filter chips display clean district names.

### 3. Advanced Filter Sheet (`GoatFilterBottomSheet.kt`)
- Update `districtOptions` in `GoatFilterBottomSheet.kt` to use the unified active district list.
- Verify filtering logic matches clean district names seamlessly against goat and farm locations.

### 4. Cards and Detail Screens
- Update `GoatCard.kt`: Format farm location display as `${goat.farmName} (${FarmLocations.cleanDistrictName(goat.farmLocation)})`.
- Update `GoatDetailScreen.kt`, `FarmDetailScreen.kt`, and `BookingsScreen.kt` to display clean district names consistently.

### 5. Verification & Build
- Verify build with `compile_applet`.
- Build fresh APK to `./AduSanthai.apk` in workspace root.

---

## Verification Plan
1. Open Location Dropdown on Home Screen: Verify list starts with "All Locations" followed by clean, unique district names (no "Tamil Nadu" or "Vellore, Tamil Nadu").
2. Select a District (e.g., "Vellore"): Verify matching goats are displayed, active chip shows "Vellore", and filter bottom sheet reflects selection.
3. Check Goat Cards & Detail Views: Confirm card and detail screen display clean district names.
