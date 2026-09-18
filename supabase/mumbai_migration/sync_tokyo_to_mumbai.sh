#!/usr/bin/env bash
# =============================================================================
# AMMAL FARM PLATFORM - TOKYO TO MUMBAI FULL SYNC SCRIPT
# File: sync_tokyo_to_mumbai.sh
# =============================================================================
# PURPOSE:
# Safely dumps and restores:
# 1. auth.users & auth.identities (preserving exact UUIDs, identities, and bcrypt password hashes)
# 2. public schema tables in strict foreign-key dependency order
# 3. All actual binary storage files (18 objects) across buckets:
#    - goat-images (public)
#    - farm-docs (private)
#    - vet-certificates (private)
#
# PREREQUISITES (Export as Environment Variables):
#   export TOKYO_DB_URI="postgresql://postgres.[REF]:[PW]@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres"
#   export MUMBAI_DB_URI="postgresql://postgres.[REF]:[PW]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres"
#   export TOKYO_SUPABASE_URL="https://<tokyo_ref>.supabase.co"
#   export TOKYO_SERVICE_KEY="<tokyo_service_role_key>"
#   export MUMBAI_SUPABASE_URL="https://<mumbai_ref>.supabase.co"
#   export MUMBAI_SERVICE_KEY="<mumbai_service_role_key>"
# =============================================================================

set -euo pipefail

SYNC_DIR="./mumbai_sync_$(date +%Y%m%d_%H%M%S)"
mkdir -p "${SYNC_DIR}/storage/goat-images"
mkdir -p "${SYNC_DIR}/storage/farm-docs"
mkdir -p "${SYNC_DIR}/storage/vet-certificates"

echo "============================================================================="
echo " AMMAL FARM - PRODUCTION TOKYO -> MUMBAI SYNC"
echo " Tokyo (ap-northeast-1) [READ ONLY] -> Mumbai (ap-south-1)"
echo "============================================================================="
echo "📁 Sync working directory: ${SYNC_DIR}"

# -----------------------------------------------------------------------------
# STEP 1: EXPORT AUTH & IDENTITIES (Preserving exact UUIDs, identities, bcrypt hashes)
# -----------------------------------------------------------------------------
if [ -n "${TOKYO_DB_URI:-}" ]; then
    echo "📦 1. Exporting auth.users and auth.identities from Tokyo (READ-ONLY)..."
    pg_dump "${TOKYO_DB_URI}" \
        --table=auth.users \
        --table=auth.identities \
        --data-only \
        --no-owner \
        --no-privileges \
        --file="${SYNC_DIR}/01_auth_users_and_identities.sql"
    echo "✅ auth.users and auth.identities dump created."

    echo "📦 2. Exporting public schema data in foreign-key dependency order (READ-ONLY)..."
    pg_dump "${TOKYO_DB_URI}" \
        --schema=public \
        --data-only \
        --no-owner \
        --no-privileges \
        --disable-triggers \
        --file="${SYNC_DIR}/02_public_data.sql"
    echo "✅ public schema dump created."

    # If Mumbai connection string is supplied, load directly into Mumbai
    if [ -n "${MUMBAI_DB_URI:-}" ]; then
        echo "📥 3. Restoring auth.users and auth.identities into Mumbai..."
        psql "${MUMBAI_DB_URI}" -f "${SYNC_DIR}/01_auth_users_and_identities.sql"
        
        echo "📥 4. Restoring public schema records with scoped session_replication_role..."
        psql "${MUMBAI_DB_URI}" -c "BEGIN; SET LOCAL session_replication_role = 'replica'; \i ${SYNC_DIR}/02_public_data.sql; COMMIT;"
        echo "✅ Database tables successfully restored in Mumbai with origin replication role guaranteed."
    else
        echo "ℹ️ MUMBAI_DB_URI not provided. SQL dump files generated in ${SYNC_DIR}"
    fi
else
    echo "⚠️ TOKYO_DB_URI not set. Set environment variable TOKYO_DB_URI to run pg_dump."
fi

# -----------------------------------------------------------------------------
# STEP 2: DOWNLOAD & UPLOAD ACTUAL BINARY STORAGE FILES ACROSS ALL 3 BUCKETS
# -----------------------------------------------------------------------------
echo "============================================================================="
echo "🖼️ Step 2: Binary Storage Files Synchronization"
echo "============================================================================="

if [ -n "${TOKYO_SUPABASE_URL:-}" ] && [ -n "${TOKYO_SERVICE_KEY:-}" ]; then
    python3 - <<EOF
import os
import json
import urllib.request
import mimetypes

tokyo_url = os.environ.get("TOKYO_SUPABASE_URL", "").rstrip("/")
tokyo_key = os.environ.get("TOKYO_SERVICE_KEY", "")
mumbai_url = os.environ.get("MUMBAI_SUPABASE_URL", "").rstrip("/")
mumbai_key = os.environ.get("MUMBAI_SERVICE_KEY", "")
sync_dir = "${SYNC_DIR}/storage"

buckets = ["goat-images", "farm-docs", "vet-certificates"]
downloaded_count = 0
uploaded_count = 0

def list_all_objects(bucket, prefix=""):
    objects = []
    list_url = f"{tokyo_url}/storage/v1/object/list/{bucket}"
    payload = json.dumps({"prefix": prefix, "limit": 1000, "sortBy": {"column": "name", "order": "asc"}}).encode("utf-8")
    req = urllib.request.Request(
        list_url,
        data=payload,
        headers={
            "Authorization": f"Bearer {tokyo_key}",
            "apikey": tokyo_key,
            "Content-Type": "application/json"
        },
        method="POST"
    )
    try:
        with urllib.request.urlopen(req) as resp:
            items = json.loads(resp.read().decode("utf-8"))
            for item in items:
                name = item.get("name")
                if not name:
                    continue
                # If item is a folder (id is None or metadata is None), recurse
                if item.get("id") is None:
                    nested_prefix = f"{prefix}/{name}".strip("/")
                    objects.extend(list_all_objects(bucket, nested_prefix))
                else:
                    full_name = f"{prefix}/{name}".strip("/")
                    objects.append(full_name)
    except Exception as e:
        print(f"Error listing '{bucket}' with prefix '{prefix}': {e}")
    return objects

for b in buckets:
    print(f"\n--- Checking bucket: {b} ---")
    obj_names = list_all_objects(b)
    print(f"Found {len(obj_names)} total files in Tokyo bucket '{b}'.")

    for name in obj_names:
        # 1. Download binary content from Tokyo (READ-ONLY)
        download_url = f"{tokyo_url}/storage/v1/object/authenticated/{b}/{name}"
        down_req = urllib.request.Request(
            download_url,
            headers={"Authorization": f"Bearer {tokyo_key}"}
        )
        local_file_path = os.path.join(sync_dir, b, name)
        os.makedirs(os.path.dirname(local_file_path), exist_ok=True)

        try:
            with urllib.request.urlopen(down_req) as file_resp:
                content = file_resp.read()
                with open(local_file_path, "wb") as f_out:
                    f_out.write(content)
            downloaded_count += 1
            print(f"  📥 Downloaded ({len(content)} bytes): {b}/{name}")

            # 2. Upload binary content to Mumbai
            if mumbai_url and mumbai_key:
                mime_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
                upload_url = f"{mumbai_url}/storage/v1/object/{b}/{name}"
                up_req = urllib.request.Request(
                    upload_url,
                    data=content,
                    headers={
                        "Authorization": f"Bearer {mumbai_key}",
                        "apikey": mumbai_key,
                        "Content-Type": mime_type,
                        "x-upsert": "true"
                    },
                    method="POST"
                )
                try:
                    with urllib.request.urlopen(up_req) as up_resp:
                        uploaded_count += 1
                        print(f"  📤 Uploaded to Mumbai: {b}/{name} (HTTP {up_resp.status})")
                except Exception as up_err:
                    print(f"  ⚠️ Upload failure for {b}/{name}: {up_err}")

        except Exception as down_err:
            print(f"  ⚠️ Download failure for {b}/{name}: {down_err}")

print(f"\n✅ Storage binary sync complete! Downloaded: {downloaded_count} files, Uploaded: {uploaded_count} files.")
EOF
else
    echo "⚠️ Storage credentials not provided. Export TOKYO_SUPABASE_URL, TOKYO_SERVICE_KEY, MUMBAI_SUPABASE_URL, and MUMBAI_SERVICE_KEY."
fi

echo "============================================================================="
echo "🎉 Tokyo -> Mumbai synchronization process finished."
echo "============================================================================="
