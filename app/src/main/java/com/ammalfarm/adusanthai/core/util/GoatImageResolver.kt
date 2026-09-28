package com.ammalfarm.adusanthai.core.util

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.dto.GoatImageDto
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared resolver for goat images reusing the existing Marketplace image loading logic.
 * Queries `goat_images` table in Supabase PostgREST, resolves paths to Supabase Storage public URLs,
 * and maintains an in-memory cache to avoid duplicate network calls.
 */
object GoatImageResolver {
    private const val TAG = "GoatImageResolver"
    private val memoryCache = ConcurrentHashMap<String, String>()

    fun getCachedPhoto(goatId: String?): String? {
        if (goatId.isNullOrBlank()) return null
        return memoryCache[goatId]
    }

    fun cachePhoto(goatId: String?, photoUrl: String?) {
        if (!goatId.isNullOrBlank() && !photoUrl.isNullOrBlank()) {
            memoryCache[goatId] = photoUrl
        }
    }

    fun cachePhotos(photosMap: Map<String, List<String>>) {
        for ((goatId, photos) in photosMap) {
            val first = photos.firstOrNull { it.isNotBlank() }
            if (first != null) {
                memoryCache[goatId] = first
            }
        }
    }

    /**
     * Resolves the primary photo for a single goat ID from `goat_images` and Supabase Storage.
     * Returns an empty string if no image exists or if network fails.
     */
    suspend fun resolvePrimaryPhoto(goatId: String?): String {
        if (goatId.isNullOrBlank()) return ""
        val cached = memoryCache[goatId]
        if (!cached.isNullOrBlank()) return cached

        if (!SupabaseConfig.isConfigured) return ""

        val validGoatId = ensureValidUuid(goatId)
        return try {
            val imageDtos = SupabaseModule.client.postgrest["goat_images"]
                .select {
                    filter {
                        eq("goat_id", validGoatId)
                    }
                }.decodeList<GoatImageDto>()

            val sortedImages = imageDtos.sortedBy { it.displayOrder }
            val firstValid = sortedImages.firstOrNull { it.imageUrl.isNotBlank() }
            val rawPathOrUrl = firstValid?.imageUrl?.trim() ?: ""
            if (rawPathOrUrl.isNotBlank()) {
                val fullUrl = SupabaseConfig.resolveStorageUrl(rawPathOrUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                if (fullUrl.isNotBlank()) {
                    memoryCache[goatId] = fullUrl
                    fullUrl
                } else {
                    ""
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to resolve photo for goat $goatId: ${e.message}")
            ""
        }
    }

    /**
     * Batch resolves goat photos for a list of goat IDs reusing Marketplace logic.
     */
    suspend fun fetchGoatPhotosMap(goatIds: List<String>): Map<String, List<String>> {
        val cleanIds = goatIds.filter { it.isNotBlank() }.distinct()
        if (cleanIds.isEmpty()) return emptyMap()

        val validGoatIds = cleanIds.map { ensureValidUuid(it) }

        if (!SupabaseConfig.isConfigured) return emptyMap()

        return try {
            val imageDtos = SupabaseModule.client.postgrest["goat_images"]
                .select {
                    filter {
                        isIn("goat_id", validGoatIds)
                    }
                }.decodeList<GoatImageDto>()

            val sortedImages = imageDtos.sortedBy { it.displayOrder }
            val grouped = mutableMapOf<String, MutableList<String>>()
            for (img in sortedImages) {
                val gId = img.goatId.takeIf { it.isNotBlank() } ?: continue
                val rawPathOrUrl = img.imageUrl.takeIf { it.isNotBlank() } ?: continue
                val fullUrl = SupabaseConfig.resolveStorageUrl(rawPathOrUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                if (fullUrl.isNotBlank()) {
                    grouped.getOrPut(gId) { mutableListOf() }.add(fullUrl)
                    if (!memoryCache.containsKey(gId)) {
                        memoryCache[gId] = fullUrl
                    }
                }
            }
            // Also map any original IDs that were converted to valid UUIDs
            for (originalId in cleanIds) {
                val mappedUuid = ensureValidUuid(originalId)
                if (mappedUuid != originalId && grouped.containsKey(mappedUuid)) {
                    val photos = grouped[mappedUuid]
                    if (photos != null) {
                        grouped[originalId] = photos
                        photos.firstOrNull()?.let { memoryCache[originalId] = it }
                    }
                }
            }
            grouped
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch photos map for goats: ${e.message}")
            emptyMap()
        }
    }
}
