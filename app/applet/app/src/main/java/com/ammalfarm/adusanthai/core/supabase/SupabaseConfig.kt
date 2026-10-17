package com.ammalfarm.adusanthai.core.supabase

import com.ammalfarm.adusanthai.BuildConfig
import okhttp3.OkHttpClient

object SupabaseConfig {
    // Official Mumbai Production Supabase Endpoint & Public Anon Key
    private const val DEFAULT_PROD_URL = "https://wphgctwmjcvrblpybktd.supabase.co"
    private const val DEFAULT_PROD_KEY = "sb_publishable_n2K7Pxz4L-DAJWcL7MAgQQ_IrOwGKoG"

    val SUPABASE_URL: String = try {
        BuildConfig.SUPABASE_URL.takeIf { it.isNotBlank() && !it.contains("placeholder") } ?: DEFAULT_PROD_URL
    } catch (_: Throwable) {
        DEFAULT_PROD_URL
    }

    val SUPABASE_KEY: String = try {
        BuildConfig.SUPABASE_ANON_KEY.takeIf { it.isNotBlank() && !it.contains("placeholder") } ?: DEFAULT_PROD_KEY
    } catch (_: Throwable) {
        DEFAULT_PROD_KEY
    }

    val supabaseUrl: String get() = SUPABASE_URL
    val supabaseAnonKey: String get() = SUPABASE_KEY

    const val BUCKET_GOAT_IMAGES = "goat-images"
    const val TABLE_PROFILES = "profiles"
    const val TABLE_FARMS = "farms"

    val isConfigured: Boolean
        get() = SUPABASE_URL.isNotBlank() &&
                !SUPABASE_URL.contains("placeholder") &&
                SUPABASE_KEY.isNotBlank() &&
                !SUPABASE_KEY.contains("placeholder")

    val isRemoteDatabaseEnabled: Boolean
        get() = isConfigured

    fun isConfigured(): Boolean = isConfigured

    fun createCustomOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder().build()
    }

    fun resolveStorageUrl(path: String, bucket: String): String {
        return "$SUPABASE_URL/storage/v1/object/public/$bucket/$path"
    }

    fun extractStoragePath(url: String, bucketName: String = BUCKET_GOAT_IMAGES): String {
        if (!url.contains(bucketName)) return url
        val parts = url.split("$bucketName/")
        return if (parts.size > 1) parts[1] else url
    }
}
