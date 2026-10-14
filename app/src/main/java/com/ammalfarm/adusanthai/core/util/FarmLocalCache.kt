package com.ammalfarm.adusanthai.core.util

import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.model.Farm
import java.util.concurrent.ConcurrentHashMap

object FarmLocalCache {
    private val cachedFarms = ConcurrentHashMap<String, Farm>()

    fun isValidUuid(uuid: String?): Boolean {
        if (uuid.isNullOrBlank()) return false
        return try {
            java.util.UUID.fromString(uuid)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun getCachedFarm(farmId: String?): Farm? {
        if (farmId.isNullOrBlank()) return null
        return cachedFarms[farmId]
    }

    fun isValidFarm(farm: Farm?): Boolean {
        return farm != null && farm.id.isNotBlank() && farm.name.isNotBlank()
    }

    fun saveFarmProfile(farm: Farm) {
        if (farm.id.isNotBlank()) {
            cachedFarms[farm.id] = farm
        }
    }

    fun getAllCachedFarms(): List<Farm> {
        return cachedFarms.values.toList()
    }

    fun invalidate() {
        cachedFarms.clear()
    }
}
