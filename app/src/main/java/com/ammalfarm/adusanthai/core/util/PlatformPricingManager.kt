package com.ammalfarm.adusanthai.core.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.model.PlatformPricing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages platform pricing configurations dynamically.
 * Allows Super Admin to configure and update:
 * 1. Farm Initial Approval Fee (replaces hardcoded ₹100)
 * 2. Additional Goat Listing Slot Price (replaces hardcoded ₹100)
 *
 * Persisted locally and synchronized reactively across all screens.
 */
object PlatformPricingManager {

    private const val TAG = "PlatformPricingManager"
    private const val PREFS_NAME = "ammal_platform_pricing_v1"
    private const val KEY_APPROVAL_PRICE = "pricing_farm_approval"
    private const val KEY_SLOT_PRICE = "pricing_additional_slot"
    private const val KEY_UPDATED_AT = "pricing_updated_at"

    const val DEFAULT_APPROVAL_PRICE = 500.0
    const val DEFAULT_SLOT_PRICE = 150.0

    private val _pricingFlow = MutableStateFlow(loadInitialPricing())
    val pricingFlow: StateFlow<PlatformPricing> = _pricingFlow.asStateFlow()

    private fun getPrefs(context: Context? = null): SharedPreferences? {
        val ctx = context ?: SupabaseModule.getApplicationContext()
        return ctx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadInitialPricing(): PlatformPricing {
        val prefs = getPrefs() ?: return PlatformPricing(
            farmApprovalPrice = DEFAULT_APPROVAL_PRICE,
            additionalSlotPrice = DEFAULT_SLOT_PRICE
        )
        val approval = prefs.getFloat(KEY_APPROVAL_PRICE, DEFAULT_APPROVAL_PRICE.toFloat()).toDouble()
        val slot = prefs.getFloat(KEY_SLOT_PRICE, DEFAULT_SLOT_PRICE.toFloat()).toDouble()
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, System.currentTimeMillis())
        return PlatformPricing(
            farmApprovalPrice = approval,
            additionalSlotPrice = slot,
            updatedAt = updatedAt
        )
    }

    fun getPricing(context: Context? = null): PlatformPricing {
        val prefs = getPrefs(context) ?: return _pricingFlow.value
        val approval = prefs.getFloat(KEY_APPROVAL_PRICE, _pricingFlow.value.farmApprovalPrice.toFloat()).toDouble()
        val slot = prefs.getFloat(KEY_SLOT_PRICE, _pricingFlow.value.additionalSlotPrice.toFloat()).toDouble()
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, _pricingFlow.value.updatedAt)
        val updated = PlatformPricing(
            farmApprovalPrice = approval,
            additionalSlotPrice = slot,
            updatedAt = updatedAt
        )
        _pricingFlow.value = updated
        return updated
    }

    fun updatePricing(
        approvalPrice: Double,
        slotPrice: Double,
        context: Context? = null
    ): Result<PlatformPricing> {
        return try {
            if (approvalPrice <= 0 || slotPrice <= 0) {
                return Result.failure(IllegalArgumentException("Prices must be greater than 0"))
            }
            val now = System.currentTimeMillis()
            val prefs = getPrefs(context)
            prefs?.edit()
                ?.putFloat(KEY_APPROVAL_PRICE, approvalPrice.toFloat())
                ?.putFloat(KEY_SLOT_PRICE, slotPrice.toFloat())
                ?.putLong(KEY_UPDATED_AT, now)
                ?.apply()

            val newPricing = PlatformPricing(
                farmApprovalPrice = approvalPrice,
                additionalSlotPrice = slotPrice,
                updatedAt = now
            )
            _pricingFlow.value = newPricing
            Log.d(TAG, "Platform pricing updated: Approval=₹$approvalPrice, Slot=₹$slotPrice")
            Result.success(newPricing)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update platform pricing: ${e.message}", e)
            Result.failure(e)
        }
    }
}
