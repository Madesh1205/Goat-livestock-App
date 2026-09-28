package com.ammalfarm.adusanthai.data.repository

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.dto.WishlistItemDto
import com.ammalfarm.adusanthai.data.dto.currentIsoTimestamp
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.WishlistItem
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface WishlistRepository {
    fun getWishlistForUser(userId: String): Flow<List<WishlistItem>>
    fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean>
    suspend fun addToWishlist(userId: String, goatId: String): Result<WishlistItem>
    suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit>
    suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean>
    fun clearCacheForUser(userId: String) {}
    fun clearAllCache() {}
}

class SupabaseWishlistRepositoryImpl(
    private val goatRepository: GoatRepository = SupabaseGoatRepositoryImpl(),
    private val useRemoteDatabase: Boolean = isRemoteDatabaseEnabled
) : WishlistRepository {

    companion object {
        var isRemoteDatabaseEnabled: Boolean = SupabaseConfig.isConfigured
    }

    private val TAG = "SupabaseWishlistRepo"

    // Thread-safe per-user isolated cache
    private val localWishlistCache = ConcurrentHashMap<String, MutableSet<String>>()

    override fun clearCacheForUser(userId: String) {
        if (userId.isNotBlank()) {
            localWishlistCache.remove(userId)
        }
    }

    override fun clearAllCache() {
        localWishlistCache.clear()
    }

    override fun getWishlistForUser(userId: String): Flow<List<WishlistItem>> = flow {
        if (userId.isBlank()) {
            emit(emptyList())
            return@flow
        }

        if (!SupabaseConfig.isConfigured || !useRemoteDatabase) {
            val cachedGoatIds = localWishlistCache[userId] ?: emptySet()
            val allGoatsResult = try {
                goatRepository.getApprovedGoats().firstOrNull() ?: emptyList()
            } catch (ce: Exception) {
                if (ce is CancellationException) throw ce
                emptyList()
            }
            val goatsMap = allGoatsResult.associateBy { it.id }.toMutableMap()
            val fallbackItems = cachedGoatIds.map { goatId ->
                var resolved = goatsMap[goatId]
                if (resolved == null) {
                    try {
                        resolved = goatRepository.getGoatById(goatId).firstOrNull()
                        if (resolved != null) {
                            goatsMap[goatId] = resolved
                        }
                    } catch (_: Exception) {}
                }
                WishlistItem(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    goatId = goatId,
                    goat = resolved
                )
            }
            emit(fallbackItems)
            return@flow
        }

        val validUserId = try {
            ensureValidUuid(userId)
        } catch (e: Exception) {
            emit(emptyList())
            return@flow
        }

        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST]
                .select {
                    filter {
                        eq("user_id", validUserId)
                    }
                }.decodeList<WishlistItemDto>()

            // Update user cache strictly for this authenticated user
            val userSet = ConcurrentHashMap.newKeySet<String>()
            dtos.forEach { userSet.add(it.goatId) }
            localWishlistCache[userId] = userSet

            val allGoatsResult = try {
                goatRepository.getApprovedGoats().firstOrNull() ?: emptyList()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                emptyList()
            }

            val goatsMap = allGoatsResult.associateBy { it.id }.toMutableMap()

            val items = dtos.map { dto ->
                var resolvedGoat = goatsMap[dto.goatId]
                if (resolvedGoat == null) {
                    try {
                        resolvedGoat = goatRepository.getGoatById(dto.goatId).firstOrNull()
                        if (resolvedGoat != null) {
                            goatsMap[dto.goatId] = resolvedGoat
                        }
                    } catch (_: Exception) {}
                }
                dto.toDomain(resolvedGoat)
            }

            emit(items)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error querying Supabase wishlist table for user $userId: ${e.message}")
            val cachedGoatIds = localWishlistCache[userId] ?: emptySet()
            val allGoatsResult = try {
                goatRepository.getApprovedGoats().firstOrNull() ?: emptyList()
            } catch (ce: Exception) {
                if (ce is CancellationException) throw ce
                emptyList()
            }

            val goatsMap = allGoatsResult.associateBy { it.id }.toMutableMap()
            val fallbackItems = cachedGoatIds.map { goatId ->
                var resolved = goatsMap[goatId]
                if (resolved == null) {
                    try {
                        resolved = goatRepository.getGoatById(goatId).firstOrNull()
                        if (resolved != null) {
                            goatsMap[goatId] = resolved
                        }
                    } catch (_: Exception) {}
                }
                WishlistItem(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    goatId = goatId,
                    goat = resolved
                )
            }
            emit(fallbackItems)
        }
    }.flowOn(Dispatchers.IO)

    override fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean> = flow {
        if (userId.isBlank() || goatId.isBlank()) {
            emit(false)
            return@flow
        }

        val isCached = localWishlistCache[userId]?.contains(goatId) == true
        emit(isCached)
        if (!SupabaseConfig.isConfigured) {
            return@flow
        }

        val validUserId = try {
            ensureValidUuid(userId)
        } catch (e: Exception) {
            emit(false)
            return@flow
        }
        val validGoatId = try {
            ensureValidUuid(goatId)
        } catch (e: Exception) {
            emit(false)
            return@flow
        }

        try {
            val count = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST]
                .select {
                    filter {
                        eq("user_id", validUserId)
                        eq("goat_id", validGoatId)
                    }
                }.decodeList<WishlistItemDto>().size

            val isInWishlist = count > 0
            val userCache = localWishlistCache.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
            if (isInWishlist) {
                userCache.add(goatId)
            } else {
                userCache.remove(goatId)
            }
            emit(isInWishlist)
        } catch (_: Exception) {}
    }.flowOn(Dispatchers.IO)

    override suspend fun addToWishlist(userId: String, goatId: String): Result<WishlistItem> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || goatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID and Goat ID are required"))
        }

        val validUserId = try {
            ensureValidUuid(userId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID format: $userId", e))
        }

        val validGoatId = try {
            ensureValidUuid(goatId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Goat ID format: $goatId", e))
        }

        val userCache = localWishlistCache.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        val wasAlreadyCached = userCache.contains(goatId)
        userCache.add(goatId)

        val itemId = UUID.randomUUID().toString()
        val dto = WishlistItemDto(
            id = itemId,
            userId = validUserId,
            goatId = validGoatId,
            createdAt = currentIsoTimestamp()
        )

        if (SupabaseConfig.isConfigured && useRemoteDatabase) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST].insert(dto)
                Log.d(TAG, "Successfully added goat $goatId to Supabase wishlist for user $userId")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                val errorMsg = e.message ?: ""
                val isDuplicate = errorMsg.contains("23505", ignoreCase = true) ||
                                  errorMsg.contains("duplicate key", ignoreCase = true) ||
                                  errorMsg.contains("uq_user_goat_wishlist", ignoreCase = true)
                if (isDuplicate) {
                    Log.d(TAG, "Goat $goatId already present in Supabase wishlist for user $userId (duplicate prevented)")
                    return@withContext Result.success(dto.toDomain())
                } else {
                    // REAL FAILURE: rollback optimistic cache!
                    if (!wasAlreadyCached) {
                        userCache.remove(goatId)
                    }
                    Log.e(TAG, "Failed to insert goat $goatId into Supabase wishlist for user $userId: ${e.message}")
                    return@withContext Result.failure(e)
                }
            }
        }

        Result.success(dto.toDomain())
    }

    override suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || goatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID and Goat ID are required"))
        }

        val validUserId = try {
            ensureValidUuid(userId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid User ID format: $userId", e))
        }

        val validGoatId = try {
            ensureValidUuid(goatId)
        } catch (e: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Goat ID format: $goatId", e))
        }

        val userCache = localWishlistCache.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        val wasInCache = userCache.contains(goatId)
        userCache.remove(goatId)

        if (SupabaseConfig.isConfigured && useRemoteDatabase) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST].delete {
                    filter {
                        eq("user_id", validUserId)
                        eq("goat_id", validGoatId)
                    }
                }
                Log.d(TAG, "Successfully removed goat $goatId from Supabase wishlist for user $userId")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // REAL FAILURE: rollback optimistic cache!
                if (wasInCache) {
                    userCache.add(goatId)
                }
                Log.e(TAG, "Failed to delete goat $goatId from Supabase wishlist for user $userId: ${e.message}")
                return@withContext Result.failure(e)
            }
        }

        Result.success(Unit)
    }

    override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || goatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("User ID and Goat ID are required"))
        }

        val userCache = localWishlistCache.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }
        val currentlyWishlisted = userCache.contains(goatId)

        return@withContext if (currentlyWishlisted) {
            val removeRes = removeFromWishlist(userId, goatId)
            if (removeRes.isSuccess) {
                Result.success(false)
            } else {
                Result.failure(removeRes.exceptionOrNull() ?: Exception("Failed to remove from wishlist"))
            }
        } else {
            val addRes = addToWishlist(userId, goatId)
            if (addRes.isSuccess) {
                Result.success(true)
            } else {
                Result.failure(addRes.exceptionOrNull() ?: Exception("Failed to add to wishlist"))
            }
        }
    }
}
