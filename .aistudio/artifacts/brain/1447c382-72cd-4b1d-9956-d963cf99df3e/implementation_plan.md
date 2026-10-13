# Installable APK Generation & Firebase App Distribution Plan

Generate and position a fresh, standalone debug APK (`AduSanthai.apk` and `app-debug.apk`) directly in the workspace root directory for immediate testing and smooth upload to Firebase App Distribution, bypassing the Google Play linking required for `.aab` bundles.

### User Review & Critical Decisions

> [!IMPORTANT]
> The issues and solutions confirmed during clarification:
> - **Firebase App Distribution AAB Requirement**: Firebase App Distribution shows *"App bundle setup is incomplete"* when uploading an `.aab` because Google Play App Signing / Play Console account linking is required to unpack AAB bundles on Firebase's backend.
> - **Target Build Output**: Providing a direct `.apk` eliminates this constraint completely—Firebase App Distribution accepts standalone `.apk` files directly without needing Google Play account linking or App Bundle setup.
> - **Workspace Placement**: The `.apk` will be placed in the project root directory (`./AduSanthai.apk` and `./app-debug.apk`) as well as in `.build-outputs/` for visibility and download.

- **Confirmed Decision 1**: Target Firebase App Distribution with an installable `.apk` file instead of `.aab`.
- **Confirmed Decision 2**: Place `AduSanthai.apk` and `app-debug.apk` directly into the workspace root folder (`./`).

---

### 1. Overview & Core Concept

- **What It Does**: Builds the latest debug APK with all notification icon updates and Supabase WebSocket connection fixes, placing the resulting binary directly into the workspace root (`./AduSanthai.apk`).
- **Target Audience / Persona**: Testers, QA, and developers distributing builds via Firebase App Distribution and sideloading directly onto Android test devices.
- **Key Value**: Immediate testing without needing to configure Google Play Console app signing or link Play accounts inside Firebase.

---

### 2. User Experience & Visual Design

- **Delivery Flow**:
  1. Build task compiles the Android app and generates the output APK.
  2. The APK is copied into both the workspace root (`./AduSanthai.apk`) and `.build-outputs/AduSanthai.apk`.
  3. The tester uploads `AduSanthai.apk` directly to Firebase App Distribution or downloads it to sideload onto devices.
- **Notification & Visual Updates**:
  - Contains the unified `ic_notification.png` header badge.
  - No redundant secondary logo on the right side of notifications.
  - Includes the persistent Supabase WebSocket connection fix (0ms read timeout, 15s ping interval).

---

### 3. Key Product Decisions & Trade-Offs

- **APK vs. AAB for Firebase App Distribution**:
  - *Chosen Approach*: Deliver `AduSanthai.apk` in the root folder.
  - *Why*: Firebase App Distribution requires Google Play Developer Console linking to process AABs into device APKs. Uploading an APK requires zero external service linking, letting testers install builds immediately.
  - *Alternative Considered*: Requiring the user to configure Google Play App Signing in Google Play Console and link it to Firebase. Passed over because testing can proceed immediately with APK.
- **Duplicate Naming for Accessibility**:
  - *Chosen Approach*: Copy the APK to both `./AduSanthai.apk` (descriptive brand name) and `./app-debug.apk` (standard build name), plus `.build-outputs/`.
  - *Why*: Ensures whatever tool, script, or file browser the user uses to inspect or export the file, the APK is plainly visible.

---

### 4. Technical Architecture & Delivery Pipeline

```
┌─────────────────────────────────────────────────────────────┐
│                       Gradle Build                          │
│                  :app:assembleDebug                         │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│            Generated APK: app-debug.apk                     │
│         (app/build/outputs/apk/debug/app-debug.apk)         │
└──────────────────────────────┬──────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌──────────────────────────────┐    ┌──────────────────────────────┐
│       Workspace Root         │    │       .build-outputs/        │
│   ./AduSanthai.apk           │    │  .build-outputs/             │
│   ./app-debug.apk            │    │    AduSanthai.apk            │
└──────────────┬───────────────┘    └──────────────────────────────┘
               │
               ▼
┌──────────────────────────────┐
│  Firebase App Distribution   │
│  (Upload & Direct Install)   │
└──────────────────────────────┘
```

- **Output Files to be Verified**:
  - `./AduSanthai.apk`: 38 MB installable binary
  - `./app-debug.apk`: Standard binary alias
  - `.build-outputs/AduSanthai.apk`: Backup location
