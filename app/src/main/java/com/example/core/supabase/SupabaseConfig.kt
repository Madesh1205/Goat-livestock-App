package com.example.core.supabase

import com.example.BuildConfig

/**
 * Configuration for Supabase Backend integration.
 * Reads URL and Key from environment variables (injected via BuildConfig / Secrets).
 * Automatically normalizes API URLs (e.g. converting database hostnames to PostgREST API endpoints).
 */
object SupabaseConfig {
    private const val FALLBACK_SUPABASE_URL = "https://xrkhmfedwsbbbfordrrz.supabase.co"
    private const val FALLBACK_SUPABASE_ANON_KEY = "sb_publishable_8xl5qoICatxfzFAz9HUqxQ_RxePkq8X"

    fun sanitizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return FALLBACK_SUPABASE_URL
        var url = rawUrl.trim().removeSurrounding("\"").removeSurrounding("'")

        // If user passed a full PostgreSQL connection URI like postgresql://...
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

        // If host starts with db., e.g. db.xrkhmfedwsbbbfordrrz.supabase.co
        // Convert to standard Supabase API Gateway domain
        if (url.startsWith("db.")) {
            url = url.removePrefix("db.")
        }

        // If user only entered the project ref, e.g. "xrkhmfedwsbbbfordrrz"
        if (!url.contains(".")) {
            url = "$url.supabase.co"
        }

        return "https://$url"
    }

    fun sanitizeKey(rawKey: String?): String {
        if (rawKey.isNullOrBlank()) return FALLBACK_SUPABASE_ANON_KEY
        val key = rawKey.trim().removeSurrounding("\"").removeSurrounding("'")
        return if (key.isNotBlank()) key else FALLBACK_SUPABASE_ANON_KEY
    }

    val supabaseUrl: String
        get() = try {
            val direct = BuildConfig.SUPABASE_URL
            if (!direct.isNullOrBlank()) sanitizeUrl(direct) else FALLBACK_SUPABASE_URL
        } catch (_: Throwable) {
            try {
                val configField = BuildConfig::class.java.getField("SUPABASE_URL")
                val value = configField.get(null) as? String
                sanitizeUrl(value)
            } catch (_: Throwable) {
                FALLBACK_SUPABASE_URL
            }
        }

    val supabaseAnonKey: String
        get() = try {
            val direct = BuildConfig.SUPABASE_ANON_KEY
            if (!direct.isNullOrBlank()) sanitizeKey(direct) else FALLBACK_SUPABASE_ANON_KEY
        } catch (_: Throwable) {
            try {
                val configField = BuildConfig::class.java.getField("SUPABASE_ANON_KEY")
                val value = configField.get(null) as? String
                sanitizeKey(value)
            } catch (_: Throwable) {
                FALLBACK_SUPABASE_ANON_KEY
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
    const val TABLE_REVIEWS = "reviews"
    const val TABLE_NOTIFICATIONS = "notifications"
    const val TABLE_REPORTS = "reports"
    const val TABLE_WISHLIST = "wishlist"

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
        val cleanPath = trimmed.removePrefix("/")
        return "$supabaseUrl/storage/v1/object/public/$bucket/$cleanPath"
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


