package com.ammalfarm.adusanthai.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.model.PlatformPricing
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class PlatformSettingDto(
    @SerialName("key") val key: String,
    @SerialName("value") val value: Double
)

/**
 * Manages platform pricing configurations dynamically.
 * Allows Super Admin to configure and update:
 * 1. Farm Initial Approval Fee (default ₹500)
 * 2. Additional Goat Listing Slot Price (default ₹150)
 * 3. Default Initial Goat Slots (default 2)
 *
 * Persisted locally and synchronized with Supabase server-side settings.
 */
object PlatformPricingManager {
    private const val TAG = "PlatformPricingManager"
    private const val PREFS_NAME = "ammal_platform_pricing_v2"
    private const val KEY_APPROVAL_PRICE = "pricing_farm_approval"
    private const val KEY_SLOT_PRICE = "pricing_additional_slot"
    private const val KEY_DEFAULT_SLOTS = "pricing_default_slots"
    private const val KEY_UPDATED_AT = "pricing_updated_at"

    const val DEFAULT_APPROVAL_PRICE = 500.0
    const val DEFAULT_SLOT_PRICE = 150.0
    const val DEFAULT_INITIAL_SLOTS = 2

    private val _pricingFlow = MutableStateFlow(loadInitialPricing())
    val pricingFlow: StateFlow<PlatformPricing> = _pricingFlow.asStateFlow()

    private fun getPrefs(context: Context? = null): SharedPreferences? {
        val ctx = context ?: SupabaseModule.getApplicationContext()
        return ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadInitialPricing(): PlatformPricing {
        val prefs = getPrefs() ?: return PlatformPricing(
            farmApprovalPrice = DEFAULT_APPROVAL_PRICE,
            additionalSlotPrice = DEFAULT_SLOT_PRICE,
            defaultInitialSlots = DEFAULT_INITIAL_SLOTS
        )
        val approval = prefs.getFloat(KEY_APPROVAL_PRICE, DEFAULT_APPROVAL_PRICE.toFloat()).toDouble()
        val slot = prefs.getFloat(KEY_SLOT_PRICE, DEFAULT_SLOT_PRICE.toFloat()).toDouble()
        val defaultSlots = prefs.getInt(KEY_DEFAULT_SLOTS, DEFAULT_INITIAL_SLOTS)
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, System.currentTimeMillis())
        return PlatformPricing(
            farmApprovalPrice = approval,
            additionalSlotPrice = slot,
            defaultInitialSlots = defaultSlots,
            updatedAt = updatedAt
        )
    }

    private fun saveLocally(approval: Double, slot: Double, defaultSlots: Int, updatedAt: Long, context: Context? = null) {
        val prefs = getPrefs(context)
        prefs?.edit()
            ?.putFloat(KEY_APPROVAL_PRICE, approval.toFloat())
            ?.putFloat(KEY_SLOT_PRICE, slot.toFloat())
            ?.putInt(KEY_DEFAULT_SLOTS, defaultSlots)
            ?.putLong(KEY_UPDATED_AT, updatedAt)
            ?.apply()
    }

    fun getPricing(context: Context? = null): PlatformPricing {
        val prefs = getPrefs(context) ?: return _pricingFlow.value
        val approval = prefs.getFloat(KEY_APPROVAL_PRICE, _pricingFlow.value.farmApprovalPrice.toFloat()).toDouble()
        val slot = prefs.getFloat(KEY_SLOT_PRICE, _pricingFlow.value.additionalSlotPrice.toFloat()).toDouble()
        val defaultSlots = prefs.getInt(KEY_DEFAULT_SLOTS, _pricingFlow.value.defaultInitialSlots)
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, _pricingFlow.value.updatedAt)
        val updated = PlatformPricing(
            farmApprovalPrice = approval,
            additionalSlotPrice = slot,
            defaultInitialSlots = defaultSlots,
            updatedAt = updatedAt
        )
        _pricingFlow.value = updated
        return updated
    }

    suspend fun fetchPricingRemote(context: Context? = null): Result<PlatformPricing> = withContext(Dispatchers.IO) {
        try {
            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.success(_pricingFlow.value)
            }
            val settings = SupabaseModule.client.postgrest["platform_settings"]
                .select()
                .decodeList<PlatformSettingDto>()

            var approval = _pricingFlow.value.farmApprovalPrice
            var slot = _pricingFlow.value.additionalSlotPrice
            var defaultSlots = _pricingFlow.value.defaultInitialSlots

            for (setting in settings) {
                when (setting.key) {
                    "farm_approval_fee" -> approval = setting.value
                    "additional_slot_price" -> slot = setting.value
                    "default_initial_slots" -> defaultSlots = setting.value.toInt()
                }
            }

            val now = System.currentTimeMillis()
            saveLocally(approval, slot, defaultSlots, now, context)
            val pricing = PlatformPricing(
                farmApprovalPrice = approval,
                additionalSlotPrice = slot,
                defaultInitialSlots = defaultSlots,
                updatedAt = now
            )
            _pricingFlow.value = pricing
            Log.d(TAG, "Successfully fetched remote platform settings from table: Approval=₹$approval, Slot=₹$slot, DefaultSlots=$defaultSlots")
            Result.success(pricing)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            Log.d(TAG, "Notice: remote platform settings fetch using local cache/default safely: $msg")
            Result.success(_pricingFlow.value)
        }
    }

    suspend fun updatePricingRemote(
        approvalPrice: Double,
        slotPrice: Double,
        defaultInitialSlots: Int,
        context: Context? = null
    ): Result<PlatformPricing> = withContext(Dispatchers.IO) {
        try {
            if (approvalPrice < 0 || slotPrice < 0 || defaultInitialSlots < 0) {
                return@withContext Result.failure(IllegalArgumentException("Prices and slots cannot be negative"))
            }
            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.failure(Exception("Supabase is not configured"))
            }

            val params = buildJsonObject {
                put("p_farm_approval_fee", approvalPrice)
                put("p_additional_slot_price", slotPrice)
                put("p_default_initial_slots", defaultInitialSlots)
            }
            val rpcResponse = SupabaseModule.client.postgrest.rpc(
                function = "update_platform_settings",
                parameters = params
            )
            val rawData = rpcResponse.data
            if (!rawData.isNullOrBlank() && rawData != "null") {
                val elem = Json.parseToJsonElement(rawData)
                val obj = when (elem) {
                    is JsonArray -> elem.firstOrNull()?.jsonObject
                    is JsonObject -> elem
                    else -> null
                }
                if (obj != null) {
                    val success = obj["success"]?.jsonPrimitive?.booleanOrNull ?: true
                    if (!success) {
                        val errorMessage = obj["message"]?.jsonPrimitive?.content ?: "Server rejected pricing update"
                        return@withContext Result.failure(Exception(errorMessage))
                    }
                }
            }
            val now = System.currentTimeMillis()
            saveLocally(approvalPrice, slotPrice, defaultInitialSlots, now, context)
            val newPricing = PlatformPricing(
                farmApprovalPrice = approvalPrice,
                additionalSlotPrice = slotPrice,
                defaultInitialSlots = defaultInitialSlots,
                updatedAt = now
            )
            _pricingFlow.value = newPricing
            Log.d(TAG, "Platform pricing updated remotely and saved: Approval=₹$approvalPrice, Slot=₹$slotPrice, DefaultSlots=$defaultInitialSlots")
            Result.success(newPricing)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update remote platform pricing: ${e.message}", e)
            Result.failure(e)
        }
    }
}
