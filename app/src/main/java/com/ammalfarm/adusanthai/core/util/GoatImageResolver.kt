package com.ammalfarm.adusanthai.core.util

import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig

object GoatImageResolver {
    fun resolve(url: String?): String = url ?: ""

    fun resolveBookingGoatPhoto(
        goatId: String?,
        explicitImageUrl: String?,
        goatPrimaryUrl: String?,
        goatPhotos: List<String> = emptyList()
    ): String {
        val candidate = explicitImageUrl?.takeIf { it.isNotBlank() }
            ?: goatPrimaryUrl?.takeIf { it.isNotBlank() }
            ?: goatPhotos.firstOrNull { it.isNotBlank() }
            ?: ""
        return if (candidate.isNotBlank()) {
            SupabaseConfig.resolveStorageUrl(candidate, SupabaseConfig.BUCKET_GOAT_IMAGES)
        } else {
            ""
        }
    }
}
