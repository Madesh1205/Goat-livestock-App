# Firebase Cloud Messaging (FCM) Integration Plan (Revised)

Implement Firebase Cloud Messaging (FCM) push notifications in the **Adu Santhai** Android application while seamlessly integrating with the app's existing notification pipeline and maintaining Supabase as the single source of truth.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following choices were confirmed based on user input during Phase 1 & Phase 2 analysis:

- **Notification Permission Timing**: Request Android 13+ (`POST_NOTIFICATIONS`) permission automatically on first app start, gracefully falling back if permission is denied.
- **FCM Registration Token Storage**: Upsert token into the existing `profiles` table in Supabase under `fcm_token` when user is authenticated, without creating or altering database tables.
- **Notification Drawer & Deduplication Guard**: Reuse the existing `NotificationHelper` and `NotificationDeliveryTracker` to render system drawer alerts. `AmmalFirebaseMessagingService` checks `NotificationDeliveryTracker.isDelivered()` before posting to eliminate duplicate notifications across FCM, WorkManager sync, and Supabase Realtime.
- **Service Coexistence**: Keep the existing `NotificationSyncWorker` as a fallback background polling worker when FCM push messages are delayed or unavailable on devices lacking Google Play Services.

---

## 1. Overview & Core Concept

- **Goal**: Enable real-time push notification delivery via FCM for livestock marketplace activity, booking status updates, and administrative alerts.
- **Scope**:
  1. Add `firebase-messaging` SDK using the existing Firebase BoM in `gradle/libs.versions.toml` and `app/build.gradle.kts`.
  2. Implement `AmmalFirebaseMessagingService` extending `FirebaseMessagingService` to receive background messages and handle token refresh events.
  3. Wire FCM token retrieval and upsert logic into `SupabaseMarketplaceRepositoryImpl` / `SupabaseModule` to update `profiles.fcm_token` when logged in.
  4. Ensure system notifications are posted using `NotificationHelper` with appropriate deep link route extras (`extra_deep_link_route`, `extra_notification_id`, etc.) and checked against `NotificationDeliveryTracker`.
  5. Provide non-intrusive `POST_NOTIFICATIONS` permission handling on launch for Android 13+.

---

## 2. User Experience & Visual Design

- **App Launch & Permission Flow**:
  - On first cold launch (Android 13+ / API 33+), request system notification permission automatically.
  - If granted, retrieve current FCM token and sync to `profiles.fcm_token`.
  - If denied or dismissed, the app remains 100% functional; in-app notification center and `NotificationSyncWorker` continue to operate.
- **Notification Presentation**:
  - System drawer display using standard icons and channel titles ("Marketplace & Updates", "Bookings & Holds", "Farm & Super Admin Alerts").
  - Auto-cancel on tap, navigating directly to the targeted screen via deep link routing (e.g., `orders`, `marketplace_detail/{id}`).
  - No launcher badges or popup alert dialogs.

---

## 3. Key Product Decisions & Trade-Offs

- **Deduplication via `NotificationDeliveryTracker`**:
  - *Chosen Approach*: `AmmalFirebaseMessagingService` passes the notification ID through `NotificationDeliveryTracker.isDelivered()` before displaying. If already rendered via Realtime or `NotificationSyncWorker`, FCM skips rendering. If rendered by FCM first, `NotificationSyncWorker` skips it later.
  - *Why*: Guarantees zero duplicate notification popups or drawer entries.
- **Supabase as User Source of Truth**:
  - *Chosen Approach*: FCM token is associated with the Supabase `auth.uid()` by updating `profiles.fcm_token`.
  - *Why*: Avoids mixing authentication providers or duplicating account state in Firestore.
- **FCM Service Credentials**:
  - *Chosen Approach*: No service account keys or FCM server keys are stored in the Android APK. Push triggers must originate from secure backend webhooks or Supabase Edge Functions using Google OAuth2.

---

## 4. Technical Architecture & Data Strategy

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Application                             │
│                                                                        │
│  ┌────────────────────────┐         ┌───────────────────────────────┐  │
│  │AmmalFirebaseMessaging  │         │  NotificationDeliveryTracker  │  │
│  │Service                 ├────────>│  (Check & Mark Delivered)     │  │
│  └───────────┬────────────┘         └───────────────┬───────────────┘  │
│              │                                      │                  │
│              │ (if not delivered)                   v                  │
│              │                      ┌───────────────────────────────┐  │
│              └─────────────────────>│  NotificationHelper           │  │
│                                     │  (Build System Drawer Notif)  │  │
│                                     └───────────────────────────────┘  │
│                                                                        │
│  ┌────────────────────────┐         ┌───────────────────────────────┐  │
│  │ Supabase Repository    ├────────>│ Supabase REST API             │  │
│  │ (upsert Profile token) │         │ (public.profiles.fcm_token)   │  │
│  └────────────────────────┘         └───────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────┘
```

### Component Breakdown
1. **`gradle/libs.versions.toml`**: Add `firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" }`.
2. **`app/build.gradle.kts`**: Add `implementation(libs.firebase.messaging)` under `dependencies`.
3. **`AndroidManifest.xml`**: Declare `AmmalFirebaseMessagingService` with `<intent-filter>` for `com.google.firebase.MESSAGING_EVENT`.
4. **`AmmalFirebaseMessagingService.kt`**:
   - `onNewToken(token: String)`: Log and send token to `SupabaseMarketplaceRepositoryImpl`.
   - `onMessageReceived(remoteMessage: RemoteMessage)`: Parse notification/data payload, verify `NotificationDeliveryTracker.isDelivered(...)`, and invoke `NotificationHelper.showSystemNotification(...)`.
5. **`MainActivity.kt`**: Add system launcher for `POST_NOTIFICATIONS` on startup.
