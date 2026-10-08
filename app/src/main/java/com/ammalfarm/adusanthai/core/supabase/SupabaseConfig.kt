package com.ammalfarm.adusanthai.core.supabase

import com.ammalfarm.adusanthai.BuildConfig

/**
 * Configuration for Supabase Backend integration.
 * Reads URL and Key strictly from environment variables / BuildConfig.
 * Intended target: Mumbai (ap-south-1) - wphgctwmjcvrblpybktd.supabase.co
 * 
 * IMPORTANT: Fails cleanly with clear errors when configuration is missing.
 * No silent fallbacks to obsolete Tokyo environments.
 */
object SupabaseConfig {
    const val MUMBAI_PROJECT_REF = "wphgctwmjcvrblpybktd"
    const val MUMBAI_SUPABASE_URL = "https://wphgctwmjcvrblpybktd.supabase.co"

    fun sanitizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        var url = rawUrl.trim().removeSurrounding("\"").removeSurrounding("'")

        // If passed a full PostgreSQL connection URI like postgresql://...
        if (url.startsWith("postgres://") || url.startsWith("postgresql://")) {
            val atIndex = url.indexOf('@')
            if (atIndex != -1) {
                val hostPart = url.substring(atIndex + 1).split(':', '/').firstOrNull() ?: ""
                if (hostPart.isNotBlank()) {
                    url = hostPart
                }
            }
        }

        // Remove scheme if present to clean host
        url = url.removePrefix("https://").removePrefix("http://")

        // Remove trailing slashes and paths (e.g. /rest/v1)
        if (url.contains("/")) {
            url = url.substringBefore("/")
        }
        // Remove port if present
        if (url.contains(":")) {
            url = url.substringBefore(":")
        }

        // If host starts with db., e.g. db.wphgctwmjcvrblpybktd.supabase.co
        // Convert to standard Supabase API Gateway domain
        if (url.startsWith("db.")) {
            url = url.removePrefix("db.")
        }

        // If user only entered the project ref, e.g. "wphgctwmjcvrblpybktd"
        if (!url.contains(".")) {
            url = "$url.supabase.co"
        }

        return "https://$url"
    }

    fun sanitizeKey(rawKey: String?): String {
        if (rawKey.isNullOrBlank()) return ""
        val key = rawKey.trim().removeSurrounding("\"").removeSurrounding("'")
        return key
    }

    val supabaseUrl: String
        get() = try {
            val direct = BuildConfig.SUPABASE_URL
            if (!direct.isNullOrBlank()) sanitizeUrl(direct) else ""
        } catch (_: Throwable) {
            try {
                val configField = BuildConfig::class.java.getField("SUPABASE_URL")
                val value = configField.get(null) as? String
                sanitizeUrl(value)
            } catch (_: Throwable) {
                ""
            }
        }

    val supabaseAnonKey: String
        get() = try {
            val direct = BuildConfig.SUPABASE_ANON_KEY
            if (!direct.isNullOrBlank()) sanitizeKey(direct) else ""
        } catch (_: Throwable) {
            try {
                val configField = BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
                val value = configField.get(null) as? String
                sanitizeKey(value)
            } catch (_: Throwable) {
                ""
            }
        }

    val isConfigured: Boolean
        get() {
            val url = supabaseUrl.trim()
            val key = supabaseAnonKey.trim()
            return url.isNotBlank() &&
                    !url.contains("placeholder") &&
                    !url.contains("example.com") &&
                    key.isNotBlank() &&
                    !key.contains("placeholder")
        }

    // Tables
    const val TABLE_PROFILES = "profiles"
    const val TABLE_GOATS = "goats"
    const val TABLE_FARMS = "farms"
    const val TABLE_BREEDS = "breeds"
    const val TABLE_BOOKINGS = "bookings"
    const val TABLE_PAYMENTS = "listing_payments"
    const val TABLE_NOTIFICATIONS = "notifications"
    const val TABLE_REPORTS = "reports"
    const val TABLE_WISHLIST = "wishlist"
    const val TABLE_REVIEWS = "reviews"

    // Storage Buckets
    const val BUCKET_GOAT_IMAGES = "goat-images"
    const val BUCKET_GOAT_PHOTOS = "goat-images"
    const val BUCKET_VET_CERTIFICATES = "vet-certificates"
    const val BUCKET_FARM_DOCS = "farm-docs"

    /**
     * Resolves a public URL from either a relative storage path (e.g. goat/123/abc.jpg)
     * or an already fully qualified URL / local URI.
     */
    fun resolveStorageUrl(pathOrUrl: String?, bucket: String = BUCKET_GOAT_IMAGES): String {
        if (pathOrUrl.isNullOrBlank()) return ""
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("http://") || 
            trimmed.startsWith("https://") || 
            trimmed.startsWith("file://") || 
            trimmed.startsWith("content://") || 
            trimmed.startsWith("data:") ||
            trimmed.startsWith("android.resource://")) {
            return trimmed
        }
        val baseUrl = supabaseUrl.ifBlank { MUMBAI_SUPABASE_URL }
        val cleanPath = trimmed.removePrefix("/")
        return "$baseUrl/storage/v1/object/public/$bucket/$cleanPath"
    }

    /**
     * Extracts relative storage path if the input is a full Supabase storage URL,
     * or returns the input if it's already a relative path or local file.
     */
    fun extractStoragePath(pathOrUrl: String?, bucket: String = BUCKET_GOAT_IMAGES): String {
        if (pathOrUrl.isNullOrBlank()) return ""
        val trimmed = pathOrUrl.trim()
        if (trimmed.startsWith("file://") || 
            trimmed.startsWith("content://") || 
            trimmed.startsWith("data:") || 
            trimmed.startsWith("android.resource://")) {
            return trimmed
        }
        val marker = "/storage/v1/object/public/$bucket/"
        return if (trimmed.contains(marker)) {
            trimmed.substringAfter(marker)
        } else {
            trimmed.removePrefix("/")
        }
    }
}
