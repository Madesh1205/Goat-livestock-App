package com.ammalfarm.adusanthai.core.util

import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import java.util.concurrent.ConcurrentHashMap

object GoatImageResolver {
    private val photoCache = ConcurrentHashMap<String, String>()

    fun resolve(url: String?): String = url ?: ""

    fun getCachedPhoto(goatId: String): String? = photoCache[goatId]

    fun cachePhoto(goatId: String, photoUrl: String) {
        if (goatId.isNotBlank() && photoUrl.isNotBlank()) {
            photoCache[goatId] = photoUrl
        }
    }

    fun cachePhotos(map: Map<String, List<String>>) {
        for ((goatId, urls) in map) {
            val firstUrl = urls.firstOrNull { it.isNotBlank() }
            if (goatId.isNotBlank() && !firstUrl.isNullOrBlank()) {
                photoCache[goatId] = firstUrl
            }
        }
    }

    fun resolveBookingGoatPhoto(
        goatId: String?,
        explicitImageUrl: String?,
        goatPrimaryUrl: String?,
        goatPhotos: List<String> = emptyList()
    ): String {
        val candidate = explicitImageUrl?.takeIf { it.isNotBlank() }
            ?: goatPrimaryUrl?.takeIf { it.isNotBlank() }
            ?: goatPhotos.firstOrNull { it.isNotBlank() }
            ?: goatId?.let { photoCache[it] }?.takeIf { it.isNotBlank() }
            ?: ""
        return if (candidate.isNotBlank()) {
            SupabaseConfig.resolveStorageUrl(candidate, SupabaseConfig.BUCKET_GOAT_IMAGES)
        } else {
            ""
        }
    }
}
