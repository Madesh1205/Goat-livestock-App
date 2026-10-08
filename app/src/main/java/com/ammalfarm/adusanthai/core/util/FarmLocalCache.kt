package com.ammalfarm.adusanthai.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.VerificationStatus
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
        // Orphaned non-Ammal farm whose owner was removed/deleted is invalid
        if (farm.ownerId.isBlank() && !farm.isAmmalOwnFarm) return false
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
                goatListingLimit = obj.optInt("goatListingLimit", 2)
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

    fun deleteFarm(farmId: String?, context: Context? = null) {
        removeCachedFarm(farmId, context)
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
                    goatListingLimit = obj.optInt("goatListingLimit", 2)
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

    fun getConsumedListingSlots(farmId: String, currentGoatsCount: Int = 0, context: Context? = null): Int {
        if (!isValidUuid(farmId)) return currentGoatsCount
        val prefs = getPrefs(context) ?: return currentGoatsCount
        val stored = prefs.getInt("consumed_slots_$farmId", 0)
        val maxConsumed = maxOf(stored, currentGoatsCount)
        if (maxConsumed > stored) {
            prefs.edit().putInt("consumed_slots_$farmId", maxConsumed).apply()
        }
        return maxConsumed
    }

    fun recordListingSlotConsumed(farmId: String, context: Context? = null): Int {
        if (!isValidUuid(farmId)) return 1
        val prefs = getPrefs(context) ?: return 1
        val current = prefs.getInt("consumed_slots_$farmId", 0)
        val next = current + 1
        prefs.edit().putInt("consumed_slots_$farmId", next).apply()
        Log.d(TAG, "Slot permanently consumed for farm $farmId. New consumed count: $next")
        return next
    }

    fun saveListingPayment(payment: com.ammalfarm.adusanthai.model.ListingPayment, context: Context? = null) {
        val prefs = getPrefs(context) ?: return
        try {
            val allPayments = getAllCachedPayments(context).toMutableList()
            val existingIndex = allPayments.indexOfFirst { it.id == payment.id }
            if (existingIndex >= 0) {
                allPayments[existingIndex] = payment
            } else {
                allPayments.add(0, payment)
            }
            val array = JSONArray()
            for (p in allPayments) {
                val obj = JSONObject().apply {
                    put("id", p.id)
                    put("goatId", p.goatId)
                    put("goatName", p.goatName)
                    put("goatCode", p.goatCode)
                    put("farmId", p.farmId)
                    put("farmName", p.farmName)
                    put("amount", p.amount)
                    put("currency", p.currency)
                    put("paymentType", p.paymentType)
                    put("slotsAdded", p.slotsAdded)
                    put("status", p.status.name)
                    put("orderId", p.orderId ?: "")
                    put("razorpayPaymentId", p.razorpayPaymentId ?: "")
                    put("receiptNumber", p.receiptNumber ?: "")
                    put("paymentMethod", p.paymentMethod)
                    put("notes", p.notes)
                    put("createdAt", p.createdAt)
                }
                array.put(obj)
            }
            prefs.edit().putString("all_payments_json", array.toString()).apply()
            Log.d(TAG, "Cached listing payment receipt: ${payment.receiptNumber} (${payment.paymentType})")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache listing payment: ${e.message}")
        }
    }

    fun getAllCachedPayments(context: Context? = null): List<com.ammalfarm.adusanthai.model.ListingPayment> {
        val prefs = getPrefs(context) ?: return emptyList()
        val jsonStr = prefs.getString("all_payments_json", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<com.ammalfarm.adusanthai.model.ListingPayment>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val payment = com.ammalfarm.adusanthai.model.ListingPayment(
                    id = obj.getString("id"),
                    goatId = obj.optString("goatId", ""),
                    goatName = obj.optString("goatName", ""),
                    goatCode = obj.optString("goatCode", ""),
                    farmId = obj.optString("farmId", ""),
                    farmName = obj.optString("farmName", ""),
                    amount = obj.optDouble("amount", 0.0),
                    currency = obj.optString("currency", "INR"),
                    paymentType = obj.optString("paymentType", "FARM_APPROVAL"),
                    slotsAdded = obj.optInt("slotsAdded", 0),
                    status = try {
                        com.ammalfarm.adusanthai.model.PaymentStatus.valueOf(obj.optString("status", "PAID"))
                    } catch (_: Exception) { com.ammalfarm.adusanthai.model.PaymentStatus.PAID },
                    orderId = obj.optString("orderId").takeIf { it.isNotBlank() },
                    razorpayPaymentId = obj.optString("razorpayPaymentId").takeIf { it.isNotBlank() },
                    receiptNumber = obj.optString("receiptNumber").takeIf { it.isNotBlank() },
                    paymentMethod = obj.optString("paymentMethod", "Manual Payment"),
                    notes = obj.optString("notes", ""),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                )
                list.add(payment)
            }
            list
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached payments: ${e.message}")
            emptyList()
        }
    }

    fun getCachedPaymentsForFarm(farmId: String, context: Context? = null): List<com.ammalfarm.adusanthai.model.ListingPayment> {
        if (!isValidUuid(farmId)) return emptyList()
        return getAllCachedPayments(context).filter { it.farmId == farmId }
    }

    fun clear(context: Context? = null) {
        try {
            getPrefs(context)?.edit()?.clear()?.apply()
        } catch (_: Exception) {}
    }
}
