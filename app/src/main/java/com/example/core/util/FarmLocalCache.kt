package com.example.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.core.supabase.SupabaseModule
import com.example.model.Farm
import com.example.model.VerificationStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object FarmLocalCache {
    private const val TAG = "FarmLocalCache"
    private const val PREFS_NAME = "ammal_farm_local_cache_v2"

    private fun getPrefs(context: Context? = null): SharedPreferences? {
        val ctx = context ?: SupabaseModule.getApplicationContext()
        return ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Validates whether a given string is a real, well-formed UUID.
     * Rejects legacy placeholder formats like "farm-1", "usr-admin", or blank values.
     */
    fun isValidUuid(str: String?): Boolean {
        if (str.isNullOrBlank()) return false
        val trimmed = str.trim()
        if (trimmed.startsWith("farm-") || trimmed.startsWith("user-") || trimmed.startsWith("usr-") || trimmed.startsWith("goat-")) {
            return false
        }
        return try {
            val uuid = UUID.fromString(trimmed)
            uuid.toString().equals(trimmed, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifies that a Farm entity represents verified real data rather than a legacy/fake placeholder.
     */
    fun isValidFarm(farm: Farm?): Boolean {
        if (farm == null) return false
        if (!isValidUuid(farm.id)) return false
        if (farm.name.isBlank()) return false
        if (farm.ownerId.isNotBlank() && !isValidUuid(farm.ownerId)) return false
        return true
    }

    fun saveFarmLogo(farmId: String, logoUrl: String, context: Context? = null) {
        if (logoUrl.isBlank() || !isValidUuid(farmId)) return
        val prefs = getPrefs(context) ?: return
        try {
            prefs.edit()
                .putString("logo_$farmId", logoUrl)
                .apply()
            Log.d(TAG, "Saved farm logo to local cache for $farmId: $logoUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache farm logo: ${e.message}")
        }
    }

    fun getFarmLogo(farmId: String?, context: Context? = null): String? {
        val prefs = getPrefs(context) ?: return null
        if (!farmId.isNullOrBlank() && isValidUuid(farmId)) {
            val specific = prefs.getString("logo_$farmId", null)
            if (!specific.isNullOrBlank()) return specific
        }
        return null
    }

    fun saveFarmBanner(farmId: String, bannerUrl: String, context: Context? = null) {
        if (bannerUrl.isBlank() || !isValidUuid(farmId)) return
        val prefs = getPrefs(context) ?: return
        try {
            prefs.edit()
                .putString("banner_$farmId", bannerUrl)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache farm banner: ${e.message}")
        }
    }

    fun getFarmBanner(farmId: String?, context: Context? = null): String? {
        val prefs = getPrefs(context) ?: return null
        if (!farmId.isNullOrBlank() && isValidUuid(farmId)) {
            val specific = prefs.getString("banner_$farmId", null)
            if (!specific.isNullOrBlank()) return specific
        }
        return null
    }

    fun saveFarmProfile(farm: Farm, context: Context? = null) {
        if (!isValidFarm(farm)) {
            Log.w(TAG, "Refusing to cache invalid/placeholder farm: ${farm.id} (${farm.name})")
            return
        }
        val prefs = getPrefs(context) ?: return
        try {
            val json = JSONObject().apply {
                put("id", farm.id)
                put("name", farm.name)
                put("ownerId", farm.ownerId)
                put("ownerName", farm.ownerName)
                put("location", farm.location)
                put("state", farm.state)
                put("contactNumber", farm.contactNumber)
                put("email", farm.email)
                put("description", farm.description)
                put("logoUrl", farm.logoUrl ?: "")
                put("bannerUrl", farm.bannerUrl ?: "")
                put("verificationStatus", farm.verificationStatus.name)
                put("isAmmalOwnFarm", farm.isAmmalOwnFarm)
                put("rating", farm.rating)
                put("totalReviews", farm.totalReviews)
                put("totalGoatsListed", farm.totalGoatsListed)
                put("goatListingLimit", farm.goatListingLimit)
            }
            prefs.edit()
                .putString("farm_${farm.id}", json.toString())
                .putString("last_managed_farm_id", farm.id)
                .apply()

            if (!farm.logoUrl.isNullOrBlank()) {
                saveFarmLogo(farm.id, farm.logoUrl, context)
            }
            if (!farm.bannerUrl.isNullOrBlank()) {
                saveFarmBanner(farm.id, farm.bannerUrl, context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save farm profile to cache: ${e.message}")
        }
    }

    fun getCachedFarm(farmId: String?, context: Context? = null): Farm? {
        val prefs = getPrefs(context) ?: return null
        val targetId = farmId ?: prefs.getString("last_managed_farm_id", null) ?: return null
        if (!isValidUuid(targetId)) {
            // Invalid or legacy identifier found: purge and return null
            removeCachedFarm(targetId, context)
            return null
        }

        val jsonStr = prefs.getString("farm_$targetId", null) ?: return null
        return try {
            val obj = JSONObject(jsonStr)
            val parsedId = obj.getString("id")
            if (!isValidUuid(parsedId)) {
                removeCachedFarm(targetId, context)
                return null
            }
            val ownerId = obj.optString("ownerId", "")
            if (ownerId.isNotBlank() && !isValidUuid(ownerId)) {
                removeCachedFarm(targetId, context)
                return null
            }
            val farmName = obj.optString("name", "")
            if (farmName.isBlank()) {
                removeCachedFarm(targetId, context)
                return null
            }

            val logo = obj.optString("logoUrl").ifBlank { getFarmLogo(targetId, context) ?: "" }
            val banner = obj.optString("bannerUrl").ifBlank { getFarmBanner(targetId, context) ?: "" }
            val farm = Farm(
                id = parsedId,
                name = farmName,
                ownerId = ownerId,
                ownerName = obj.optString("ownerName", ""),
                location = obj.optString("location", ""),
                state = obj.optString("state", ""),
                contactNumber = obj.optString("contactNumber", ""),
                email = obj.optString("email", ""),
                description = obj.optString("description", ""),
                logoUrl = logo,
                bannerUrl = banner,
                verificationStatus = try {
                    VerificationStatus.valueOf(obj.optString("verificationStatus", "APPROVED"))
                } catch (_: Exception) { VerificationStatus.APPROVED },
                isAmmalOwnFarm = obj.optBoolean("isAmmalOwnFarm", false),
                rating = obj.optDouble("rating", 5.0),
                totalReviews = obj.optInt("totalReviews", 0),
                totalGoatsListed = obj.optInt("totalGoatsListed", 0),
                goatListingLimit = obj.optInt("goatListingLimit", 100)
            )
            if (!isValidFarm(farm)) {
                removeCachedFarm(targetId, context)
                null
            } else {
                farm
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached farm: ${e.message}")
            removeCachedFarm(targetId, context)
            null
        }
    }

    fun removeCachedFarm(farmId: String?, context: Context? = null) {
        if (farmId.isNullOrBlank()) return
        val prefs = getPrefs(context) ?: return
        try {
            val editor = prefs.edit()
                .remove("farm_$farmId")
                .remove("logo_$farmId")
                .remove("banner_$farmId")
            val lastManaged = prefs.getString("last_managed_farm_id", null)
            if (lastManaged == farmId) {
                editor.remove("last_managed_farm_id")
            }
            editor.apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove cached farm $farmId: ${e.message}")
        }
    }

    fun clearAllCachedFarms(context: Context? = null) {
        val prefs = getPrefs(context) ?: return
        try {
            prefs.edit()
                .remove("all_farms_json")
                .remove("last_managed_farm_id")
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear all cached farms: ${e.message}")
        }
    }

    fun saveAllFarms(farms: List<Farm>, context: Context? = null) {
        val prefs = getPrefs(context) ?: return
        try {
            val validFarms = farms.filter { isValidFarm(it) }
            val array = JSONArray()
            validFarms.forEach { farm ->
                val obj = JSONObject().apply {
                    put("id", farm.id)
                    put("name", farm.name)
                    put("ownerId", farm.ownerId)
                    put("ownerName", farm.ownerName)
                    put("location", farm.location)
                    put("state", farm.state)
                    put("contactNumber", farm.contactNumber)
                    put("email", farm.email)
                    put("description", farm.description)
                    put("logoUrl", farm.logoUrl ?: "")
                    put("bannerUrl", farm.bannerUrl ?: "")
                    put("verificationStatus", farm.verificationStatus.name)
                    put("isAmmalOwnFarm", farm.isAmmalOwnFarm)
                    put("rating", farm.rating)
                    put("totalReviews", farm.totalReviews)
                    put("totalGoatsListed", farm.totalGoatsListed)
                    put("goatListingLimit", farm.goatListingLimit)
                }
                array.put(obj)
                if (!farm.logoUrl.isNullOrBlank()) {
                    prefs.edit().putString("logo_${farm.id}", farm.logoUrl).apply()
                }
            }
            prefs.edit().putString("all_farms_json", array.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache all farms: ${e.message}")
        }
    }

    fun getAllCachedFarms(context: Context? = null): List<Farm> {
        val prefs = getPrefs(context) ?: return emptyList()
        val jsonStr = prefs.getString("all_farms_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<Farm>()
            var hadInvalid = false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                if (!isValidUuid(id)) {
                    hadInvalid = true
                    continue
                }
                val ownerId = obj.optString("ownerId", "")
                if (ownerId.isNotBlank() && !isValidUuid(ownerId)) {
                    hadInvalid = true
                    continue
                }
                val name = obj.getString("name")
                if (name.isBlank()) {
                    hadInvalid = true
                    continue
                }

                val logo = obj.optString("logoUrl").ifBlank { getFarmLogo(id, context) ?: "" }
                val banner = obj.optString("bannerUrl").ifBlank { getFarmBanner(id, context) ?: "" }
                val farm = Farm(
                    id = id,
                    name = name,
                    ownerId = ownerId,
                    ownerName = obj.optString("ownerName", ""),
                    location = obj.optString("location", ""),
                    state = obj.optString("state", ""),
                    contactNumber = obj.optString("contactNumber", ""),
                    email = obj.optString("email", ""),
                    description = obj.optString("description", ""),
                    logoUrl = logo,
                    bannerUrl = banner,
                    verificationStatus = try {
                        VerificationStatus.valueOf(obj.optString("verificationStatus", "APPROVED"))
                    } catch (_: Exception) { VerificationStatus.APPROVED },
                    isAmmalOwnFarm = obj.optBoolean("isAmmalOwnFarm", false),
                    rating = obj.optDouble("rating", 5.0),
                    totalReviews = obj.optInt("totalReviews", 0),
                    totalGoatsListed = obj.optInt("totalGoatsListed", 0),
                    goatListingLimit = obj.optInt("goatListingLimit", 100)
                )
                if (isValidFarm(farm)) {
                    list.add(farm)
                } else {
                    hadInvalid = true
                }
            }
            if (hadInvalid) {
                saveAllFarms(list, context)
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached farms list: ${e.message}")
            emptyList()
        }
    }

    fun clear(context: Context? = null) {
        try {
            getPrefs(context)?.edit()?.clear()?.apply()
        } catch (_: Exception) {}
    }
}
