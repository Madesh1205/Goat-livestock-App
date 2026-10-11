# Amazon-Style Filter UI Restructuring Plan

Consolidate and streamline the marketplace filter categories in the Adu Santhai Android app to simplify search and discovery while strictly adhering to user category combination and removal preferences.

## User Review & Critical Decisions

> [!IMPORTANT]
> The category consolidation rules have been explicitly confirmed by the user in Phase 1:

- **Confirmed Choice 1 (Physical Attributes)**: Combine **Gender**, **Age**, and **Weight** into a single unified category: **Goat Specs**.
- **Confirmed Choice 2 (Removals)**: Completely remove **Farm Verification**, **Health & Docs**, and **Horn Status** categories from the filter UI and filtering logic.
- **Confirmed Choice 3 (Sorting & Discovery)**: Combine **New Arrivals** and **Sort By** into a single category: **Sorting & New Arrivals**.

---

## 1. Overview & Core Concept

- **What It Does**: Streamlines the 14-category Amazon filter sidebar down to 8 distinct, well-organized categories.
- **Target Audience / Persona**: Goat buyers and farm owners browsing the Adu Santhai marketplace looking for fast, intuitive filter controls without redundant options.
- **Key Value**: Reduces clutter, eliminates unused filter criteria, and groups related options into comprehensive control panels.

---

## 2. User Experience & Visual Design

### Unified Filter Category Hierarchy

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Marketplace Filters                           │
├───────────────────────────────────┬────────────────────────────────────┤
│ Left Panel: Categories (~32%)     │ Right Panel: Options (~68%)        │
├───────────────────────────────────┼────────────────────────────────────┤
│ ✦ Suggested Filters               │  [Selected Category Controls]      │
│ 📍 Farm Location                   │                                    │
│ 🐐 Breed                          │  - Goat Specs:                     │
│ ⚙️ Goat Specs (Gender/Age/Weight)  │     • Gender (All/Male/Female)     │
│ 💰 Price and Deals                │     • Age Slider / Input (Months)  │
│ 📦 Availability                   │     • Weight Slider / Input (kg)   │
│ ⭐ Customer Reviews               │  - Sorting & New Arrivals:         │
│ ↕️ Sorting & New Arrivals         │     • New Arrivals Switch (30 days)│
│                                   │     • Sort Radio Buttons           │
└───────────────────────────────────┴────────────────────────────────────┘
```

- **Visual Identity**: Material 3 two-panel split screen with a light grey category navigation rail on the left and white detail panel on the right.
- **Selected Category Highlight**: Active category features a blue accent indicator line on the left, bold primary-colored label, and clean white tile background.

---

## 3. Key Technical Changes

### 1. Enum Definition (`AmazonFilterCategory`)
Update `AmazonFilterCategory` in `GoatFilterBottomSheet.kt`:
- **`SUGGESTED`** ("Suggested Filters")
- **`LOCATION`** ("Farm Location")
- **`BREED`** ("Breed")
- **`GOAT_SPECS`** ("Goat Specs") — *Combines Gender, Age, and Weight*
- **`PRICE`** ("Price and Deals")
- **`AVAILABILITY`** ("Availability")
- **`REVIEWS`** ("Customer Reviews")
- **`SORTING_NEW`** ("Sorting & New Arrivals") — *Combines Sort By and New Arrivals toggle*
- *Removed*: `HORN_STATUS`, `FARM_VERIFICATION`, `HEALTH_DOCUMENTS`

### 2. UI Control Merging in `GoatFilterBottomSheet.kt`
- **Goat Specs Panel**: Render Gender toggle buttons, Age range controls, and Weight range controls together in one cohesive scrollable panel.
- **Sorting & New Arrivals Panel**: Render a "New Arrivals (Past 30 Days)" switch/chip at the top followed by standard sorting options (Newest First, Price Low-High, Price High-Low, Top Rated, Heaviest).
- **Cleanup**: Remove all rendering and state handling logic for `HORN_STATUS`, `FARM_VERIFICATION`, and `HEALTH_DOCUMENTS`.

### 3. Data Model & Search Filter Matching (`MarketplaceModels.kt` / `MarketplaceHomeScreen.kt`)
- Ensure `GoatFilterCriteria` default values and matching logic gracefully ignore removed criteria or treat them as neutral.
- Update category search in `GoatFilterBottomSheet` so typing "gender", "age", or "weight" directs the user to "Goat Specs", and typing "new" or "sort" directs them to "Sorting & New Arrivals".

---

## 4. Verification Strategy

1. Execute `compile_applet` to verify Kotlin compilation and compose layout safety.
2. Confirm that resetting or clearing filters works seamlessly across combined categories.
3. Ensure no unresolved references remain for removed filter categories (`hornStatus`, `isFarmVerified`, `hasHealthCert`).
