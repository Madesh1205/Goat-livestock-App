# Supabase Secrets & Configuration Checklist (Mumbai: ap-south-1)

This checklist covers all environment variables, secrets, and dashboard settings required for the target Mumbai project (`Ammal Farm App Mumbai`).

---

## 1. Edge Function Secrets (Supabase CLI / Dashboard)

Configure these secrets in **Supabase Dashboard -> Edge Functions -> Secrets** or via CLI:

```bash
# Set secrets via Supabase CLI
supabase secrets set \
    FIREBASE_PROJECT_ID="your-firebase-project-id" \
    FIREBASE_CLIENT_EMAIL="firebase-adminsdk@your-project.iam.gserviceaccount.com" \
    FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n" \
    --project-ref <MUMBAI_PROJECT_REF>
```

| Secret Name | Required By | Description | Example / Notes |
| :--- | :--- | :--- | :--- |
| `SUPABASE_URL` | Edge Functions | Target project base URL | `https://<mumbai-ref>.supabase.co` (automatically set by Supabase runtime) |
| `SUPABASE_ANON_KEY` | Edge Functions | Target project public anon key | Automatically set by Supabase runtime |
| `SUPABASE_SERVICE_ROLE_KEY` | Edge Functions | Target project service_role key | Automatically set by Supabase runtime |
| `FIREBASE_PROJECT_ID` | `send-fcm-notification` | Google Firebase Project ID for push notifications | e.g. `ammal-farm-app` |
| `FIREBASE_CLIENT_EMAIL` | `send-fcm-notification` | Firebase Admin SDK service account email | `firebase-adminsdk-xyz@ammal-farm-app.iam.gserviceaccount.com` |
| `FIREBASE_PRIVATE_KEY` | `send-fcm-notification` | Firebase Admin private RSA key for FCM HTTP v1 auth | Formatted string with `\n` newlines |

---

## 2. Authentication Configuration (Supabase Dashboard -> Authentication)

1. **Email Auth Provider**:
   - Status: **Enabled**
   - Confirm email: Keep matching Tokyo setting (typically enabled in production).
   - Secure password policies: Min 6 characters.

2. **Redirect URLs / Deep Links**:
   - Add app scheme URI: `com.aistudio.ammalfarm://auth-callback` and `ammalfarm://reset-password`
   - Add production web URLs if any.

3. **Rate Limits**:
   - Verify token and email sending rate limits in **Authentication -> Rate Limits**.

---

## 3. Storage Configuration (Supabase Dashboard -> Storage)

Ensure the 3 buckets are initialized (handled automatically by `04_storage_setup.sql`):
1. `goat-images`:
   - Public: **Yes**
   - Max file size: **15 MB**
   - Allowed MIME types: `image/jpeg`, `image/png`, `image/webp`, `image/gif`
2. `farm-docs`:
   - Public: **No (Private)**
   - Max file size: **20 MB**
   - Allowed MIME types: `application/pdf`, `image/jpeg`, `image/png`
3. `vet-certificates`:
   - Public: **No (Private)**
   - Max file size: **20 MB**
   - Allowed MIME types: `application/pdf`, `image/jpeg`, `image/png`

---

## 4. Android App Preparation Note (When ready to cut over)

When the Mumbai database is completely migrated and verified, update the app's `.env` configuration:
```properties
SUPABASE_URL=https://<MUMBAI_PROJECT_REF>.supabase.co
SUPABASE_ANON_KEY=<MUMBAI_ANON_KEY>
```
*(Do not update `.env` until migration and verification are fully validated.)*
