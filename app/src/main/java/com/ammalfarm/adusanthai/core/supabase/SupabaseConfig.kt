package com.ammalfarm.adusanthai.core.supabase

import okhttp3.OkHttpClient

object SupabaseConfig {
    const val SUPABASE_URL = "https://placeholder.supabase.co"
    const val SUPABASE_KEY = "placeholder-key"
    const val supabaseUrl = SUPABASE_URL
    const val supabaseAnonKey = SUPABASE_KEY
    const val BUCKET_GOAT_IMAGES = "goat-images"
    const val TABLE_PROFILES = "profiles"
    const val TABLE_FARMS = "farms"
    const val isConfigured = true
    val isRemoteDatabaseEnabled: Boolean = true

    fun isConfigured(): Boolean = true

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
