# AMMAL FARM - TOKYO TO MUMBAI SUPABASE MIGRATION RUNBOOK

**Source Project**: Tokyo (`ap-northeast-1`) — *STRICTLY READ-ONLY (ZERO MUTATIONS)*  
**Target Project**: Mumbai (`ap-south-1`) — *Ammal Farm App Mumbai*  
**Migration Package Version**: 3.0 (Production Pre-Migration Audited)

---

## 📋 Executive Overview & Migration Baseline

| Entity | Tokyo Source Count | Mumbai Target Baseline | Migration Method & Guarantees |
| :--- | :--- | :--- | :--- |
| **Auth Users** | 3 | 3 | `pg_dump` via `sync_tokyo_to_mumbai.sh` preserving UUIDs, bcrypt password hashes, and confirmed status |
| **Auth Identities** | 3 | 3 | `pg_dump` via `sync_tokyo_to_mumbai.sh` preserving provider identities and auth linkages |
| **Profiles** | 3 | 3 | 1:1 foreign key match to `auth.users(id)` |
| **Farms** | 2 | 2 | Primary & Partner Farm (`00000000-0000-0000-0000-000000000001` preserved) |
| **Breeds** | 10 | 10 | Standard Breed Catalog |
| **Goats** | 3 | 3 | Full attributes, discounts & pricing |
| **Goat Images** | 7 | 7 | Relational pointers to Storage URLs |
| **Bookings** | 4 | 4 | Historic reservations & hold states |
| **Reviews** | 0 | 0 | Authenticity constraints pre-configured |
| **Wishlist** | 1 | 1 | User-goat relational link |
| **Notifications** | 17 | 17 | Deduplication event keys preserved |
| **Reports** | 0 | 0 | Safety moderation queue initialized |
| **Audit Logs** | 1 | 1 | Historic audit trail preserved |
| **Storage Binary Files** | 18 | 18 | Complete binary object sync across all buckets (`goat-images`, `farm-docs`, `vet-certificates`) |
| **Edge Functions** | 2 | 2 | `expire-bookings`, `send-fcm-notification` with **JWT verification enabled** (*NO Razorpay*) |

---

## 🔒 Security & Safety Principles

1. **Tokyo is 100% Read-Only**: No `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, or configuration change will ever be executed against the Tokyo database.
2. **No Raw Manual Auth SQL**: Users and identities are migrated strictly via direct database dump/restore (`pg_dump` of `auth.users` and `auth.identities`).
3. **No Insecure Edge Functions**: All Edge Functions are deployed with standard JWT verification (`verify_jwt = true`).
4. **No Plaintext Secrets in Code**: All service-role keys, database passwords, and Firebase keys are provided via ephemeral shell environment variables.
5. **No Automatic Client Cutover**: The Android application is **never** cut over automatically. It requires explicit verification and a manual update gate.

---

## 🛠️ Step-by-Step Execution Plan

### Phase 1: Target Database Infrastructure Setup (Mumbai)

Open the **Supabase SQL Editor** in your new Mumbai project (`ap-south-1`) and execute the SQL files in this exact order:

1. **Step 1 — Schema, Types & Tables**:
   - Execute `/supabase/mumbai_migration/01_schema_and_types.sql`
   - *Installs extensions, 10 PostgreSQL enums, 12 core tables, primary/foreign keys, and indexes.*

2. **Step 2 — Functions, Business Logic & Triggers**:
   - Execute `/supabase/mumbai_migration/02_functions_and_triggers.sql`
   - *Installs helper functions (`get_auth_role()`, `is_super_admin()`), booking expiry procedures, review validators, and storage path authorizers with safe `search_path`.*

3. **Step 3 — Row Level Security (RLS) Hardening**:
   - Execute `/supabase/mumbai_migration/03_rls_policies.sql`
   - *Enables RLS on all 12 tables and applies Stage 12A hardened access control policies.*

4. **Step 4 — Storage Buckets & Storage Security**:
   - Execute `/supabase/mumbai_migration/04_storage_setup.sql`
   - *Provisions `goat-images` (public), `farm-docs` (private), and `vet-certificates` (private) with strict path isolation.*

---

### Phase 2: Auth, Data & Storage Synchronization

Set your environment variables in your secure terminal (do not write them to files):

```bash
export TOKYO_DB_URI="postgresql://postgres.[REF]:[PW]@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres"
export MUMBAI_DB_URI="postgresql://postgres.[REF]:[PW]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres"
export TOKYO_SUPABASE_URL="https://<tokyo_ref>.supabase.co"
export TOKYO_SERVICE_KEY="<tokyo_service_role_key>"
export MUMBAI_SUPABASE_URL="https://<mumbai_ref>.supabase.co"
export MUMBAI_SERVICE_KEY="<mumbai_service_role_key>"
```

Execute the full automated synchronization script:

```bash
chmod +x /supabase/mumbai_migration/sync_tokyo_to_mumbai.sh
./supabase/mumbai_migration/sync_tokyo_to_mumbai.sh
```

What this does:
1. Performs a read-only `pg_dump` of `auth.users` and `auth.identities` from Tokyo and loads them into Mumbai (preserving UUIDs, bcrypt hashes, and email confirmations).
2. Performs a read-only `pg_dump` of the `public` schema from Tokyo and loads it into Mumbai using a transactional `SET LOCAL session_replication_role = 'replica'`.
3. Recursively downloads all 18 binary files across Tokyo buckets and uploads them to Mumbai storage buckets preserving exact relative paths and MIME types.

---

### Phase 3: Edge Functions & Secrets Deployment

1. **Step 5 — Deploy Protected Edge Functions**:
   ```bash
   supabase login
   supabase link --project-ref <MUMBAI_PROJECT_REF>

   # Deploy with JWT verification ENABLED (verify_jwt=true by omitting --no-verify-jwt)
   supabase functions deploy expire-bookings --project-ref <MUMBAI_PROJECT_REF>
   supabase functions deploy send-fcm-notification --project-ref <MUMBAI_PROJECT_REF>
   ```

2. **Step 6 — Configure Edge Function Secrets**:
   ```bash
   supabase secrets set \
       FIREBASE_PROJECT_ID="your-firebase-project-id" \
       FIREBASE_CLIENT_EMAIL="firebase-adminsdk@your-project.iam.gserviceaccount.com" \
       FIREBASE_PRIVATE_KEY="-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----\n" \
       --project-ref <MUMBAI_PROJECT_REF>
   ```

3. **Step 7 — Configure Authentication Settings in Supabase Dashboard**:
   - Navigate to **Authentication -> URL Configuration** in Mumbai project.
   - Set Redirect URLs: `com.aistudio.ammalfarm://auth-callback` and `ammalfarm://reset-password`.
   - Ensure Email provider is enabled.

---

### Phase 4: Post-Migration Validation Suite

1. **Step 8 — Run SQL Validation Suite in Mumbai SQL Editor**:
   - Execute `/supabase/mumbai_migration/09_verification_and_validation.sql` in the Mumbai Supabase SQL Editor.
   - Verify that:
     - All 14 entity row counts show `✅ MATCH`.
     - `auth.users`, `public.profiles`, and `auth.identities` show `✅ AUTH + PROFILE + IDENTITY SYNCED`.
     - All tables and `storage.objects` show `✅ RLS ACTIVE`.
     - All 10 orphan check queries return `0`.
     - Super Admin user (`madesh1205@gmail.com`) shows `✅ SUPER ADMIN CONFIGURED`.
     - Storage buckets show `✅ PUBLIC ACCESS OK` and `✅ PRIVATE SECURITY OK`.

---

### Phase 5: Manual Cutover Gate (Android App)

> 🛑 **MIGRATION GATE**: Do NOT proceed with this phase until Phase 4 validation produces 100% green checks.

1. **Step 9 — Update `.env`**:
   Update the Android project's `.env` configuration with the Mumbai credentials:
   ```properties
   SUPABASE_URL=https://<MUMBAI_PROJECT_REF>.supabase.co
   SUPABASE_ANON_KEY=<MUMBAI_ANON_KEY>
   ```

2. **Step 10 — Verify Android Application**:
   - Recompile applet (`compile_applet`).
   - Log in with existing credentials (`madesh1205@gmail.com`).
   - Verify goat listings, images, and booking history render properly.
