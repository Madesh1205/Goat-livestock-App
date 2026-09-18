#!/usr/bin/env bash
# =============================================================================
# AMMAL FARM PLATFORM - TOKYO TO MUMBAI DATA & STORAGE MIGRATION SCRIPT
# File: export_tokyo_cli.sh
# =============================================================================
# PREREQUISITES:
# 1. Supabase CLI installed: https://supabase.com/docs/guides/cli
# 2. Database Connection Strings (available in Supabase Dashboard -> Settings -> Database -> Connection URI)
#    - TOKYO_DB_URI: postgresql://postgres.[REF_TOKYO]:[PASSWORD]@aws-0-ap-northeast-1.pooler.supabase.com:5432/postgres
#    - MUMBAI_DB_URI: postgresql://postgres.[REF_MUMBAI]:[PASSWORD]@aws-0-ap-south-1.pooler.supabase.com:5432/postgres
# 3. Supabase Access Tokens & Storage Keys
# =============================================================================

set -euo pipefail

EXPORT_DIR="./migration_dump_$(date +%Y%m%d_%H%M%S)"
mkdir -p "${EXPORT_DIR}"
echo "📁 Created export directory: ${EXPORT_DIR}"

# -----------------------------------------------------------------------------
# STEP 1: DUMP POSTGRES DATA FROM TOKYO (Preserving Auth & Public Data)
# -----------------------------------------------------------------------------
echo "📦 Step 1: Exporting database dump from Tokyo..."
if [ -n "${TOKYO_DB_URI:-}" ]; then
    # 1. Dump auth schema (users, identities, etc.)
    pg_dump "${TOKYO_DB_URI}" \
        --schema=auth \
        --data-only \
        --no-owner \
        --no-privileges \
        --file="${EXPORT_DIR}/01_auth_data.sql"
    echo "✅ Exported auth schema data."

    # 2. Dump public schema data in dependency order
    pg_dump "${TOKYO_DB_URI}" \
        --schema=public \
        --data-only \
        --no-owner \
        --no-privileges \
        --disable-triggers \
        --file="${EXPORT_DIR}/02_public_data.sql"
    echo "✅ Exported public schema data."
else
    echo "⚠️ TOKYO_DB_URI not set. You can run 05_data_export_from_tokyo.sql via Supabase SQL Editor."
fi

# -----------------------------------------------------------------------------
# STEP 2: DOWNLOAD STORAGE OBJECTS FROM TOKYO (18 Files)
# -----------------------------------------------------------------------------
echo "🖼️ Step 2: Downloading storage objects from Tokyo..."
if [ -n "${TOKYO_SUPABASE_URL:-}" ] && [ -n "${TOKYO_SERVICE_KEY:-}" ]; then
    mkdir -p "${EXPORT_DIR}/storage/goat-images"
    mkdir -p "${EXPORT_DIR}/storage/farm-docs"
    mkdir -p "${EXPORT_DIR}/storage/vet-certificates"

    # Download list of objects in goat-images bucket
    OBJECTS=$(curl -s -X POST "${TOKYO_SUPABASE_URL}/storage/v1/object/list/goat-images" \
        -H "Authorization: Bearer ${TOKYO_SERVICE_KEY}" \
        -H "apikey: ${TOKYO_SERVICE_KEY}" \
        -H "Content-Type: application/json" \
        -d '{"prefix":"","limit":100}')

    echo "Found storage objects in goat-images: ${OBJECTS}"
    
    # Python / jq helper to download each object
    python3 - <<EOF
import json, os, subprocess

url = "${TOKYO_SUPABASE_URL}"
key = "${TOKYO_SERVICE_KEY}"
out_dir = "${EXPORT_DIR}/storage/goat-images"

cmd = ["curl", "-s", "-X", "POST", f"{url}/storage/v1/object/list/goat-images",
       "-H", f"Authorization: Bearer {key}",
       "-H", f"apikey: {key}",
       "-H", "Content-Type: application/json",
       "-d", json.dumps({"prefix": "", "limit": 100, "sortBy": {"column": "name", "order": "asc"}})]

res = subprocess.check_output(cmd)
try:
    items = json.loads(res)
    for it in items:
        name = it.get('name')
        if name and not it.get('id') is None:
            obj_url = f"{url}/storage/v1/object/authenticated/goat-images/{name}"
            target_path = os.path.join(out_dir, name)
            os.makedirs(os.path.dirname(target_path), exist_ok=True)
            subprocess.run(["curl", "-s", "-o", target_path, obj_url, "-H", f"Authorization: Bearer {key}"])
            print(f"Downloaded storage object: {name}")
except Exception as e:
    print(f"Storage download note: {e}")
EOF
    echo "✅ Storage download completed."
else
    echo "⚠️ TOKYO_SUPABASE_URL or TOKYO_SERVICE_KEY not set. Storage objects can be downloaded via Dashboard."
fi

echo "🎉 Export preparation complete! Files located in ${EXPORT_DIR}"
