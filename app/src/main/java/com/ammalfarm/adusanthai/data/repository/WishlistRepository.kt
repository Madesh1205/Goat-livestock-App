package com.ammalfarm.adusanthai.data.repository

import com.ammalfarm.adusanthai.model.WishlistItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

interface WishlistRepository {
    fun getWishlistForUser(userId: String): Flow<List<WishlistItem>>
    suspend fun addToWishlist(userId: String, goatId: String): Result<Unit>
    suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit>
    suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean>
    fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean>
    fun clearCacheForUser(userId: String)
}

class SupabaseWishlistRepositoryImpl(
    private val goatRepository: GoatRepository = SupabaseGoatRepositoryImpl()
) : WishlistRepository {

    private val userWishlists = mutableMapOf<String, MutableSet<String>>()
    private val _wishlistUpdates = MutableStateFlow(0L)

    private fun getOrCreateUserWishlist(userId: String): MutableSet<String> {
        return userWishlists.getOrPut(userId) { mutableSetOf() }
    }

    override fun getWishlistForUser(userId: String): Flow<List<WishlistItem>> {
        if (userId.isBlank()) {
            return MutableStateFlow(emptyList())
        }
        return combine(goatRepository.getApprovedGoats(), _wishlistUpdates) { goats, _ ->
            val set = userWishlists[userId] ?: emptySet()
            set.map { goatId ->
                val goat = goats.find { it.id == goatId }
                WishlistItem(
                    id = "wish-$userId-$goatId",
                    userId = userId,
                    goatId = goatId,
                    goat = goat
                )
            }
        }
    }

    override suspend fun addToWishlist(userId: String, goatId: String): Result<Unit> {
        if (userId.isNotBlank()) {
            getOrCreateUserWishlist(userId).add(goatId)
            _wishlistUpdates.value = System.currentTimeMillis()
        }
        return Result.success(Unit)
    }

    override suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit> {
        if (userId.isNotBlank()) {
            userWishlists[userId]?.remove(goatId)
            _wishlistUpdates.value = System.currentTimeMillis()
        }
        return Result.success(Unit)
    }

    override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> {
        if (userId.isBlank()) return Result.success(false)
        val set = getOrCreateUserWishlist(userId)
        val newSavedState = if (set.contains(goatId)) {
            set.remove(goatId)
            false
        } else {
            set.add(goatId)
            true
        }
        _wishlistUpdates.value = System.currentTimeMillis()
        return Result.success(newSavedState)
    }

    override fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean> {
        return _wishlistUpdates.map {
            userWishlists[userId]?.contains(goatId) == true
        }
    }

    override fun clearCacheForUser(userId: String) {
        userWishlists.remove(userId)
        _wishlistUpdates.value = System.currentTimeMillis()
    }
}
