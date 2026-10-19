package com.ammalfarm.adusanthai.core.util

import java.text.NumberFormat
import java.util.Locale

object PriceUtils {
    sealed class PriceValidationResult {
        data class Valid(val price: Double) : PriceValidationResult()
        data class Error(val message: String) : PriceValidationResult()
    }

    sealed class DiscountValidationResult {
        data class Valid(val discount: Double) : DiscountValidationResult()
        data class Error(val message: String) : DiscountValidationResult()
    }

    fun calculateFinalPrice(price: Double, discountPercentage: Double): Double {
        if (price <= 0.0) return 0.0
        val clampedDiscount = discountPercentage.coerceIn(0.0, 100.0)
        return price * (1.0 - (clampedDiscount / 100.0))
    }

    fun calculateSavings(price: Double, discountPercentage: Double): Double {
        if (price <= 0.0) return 0.0
        return (price - calculateFinalPrice(price, discountPercentage)).coerceAtLeast(0.0)
    }

    fun formatCurrency(amount: Double): String {
        val formatter = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 0
        }
        val formatted = formatter.format(amount)
        return formatted.replace("₹", "₹").replace("INR", "₹").trim()
    }

    fun formatINR(amount: Double): String {
        return formatCurrency(amount)
    }

    fun formatDiscountPercent(discountPercentage: Double): String {
        val clamped = discountPercentage.coerceIn(0.0, 100.0)
        return if (clamped % 1.0 == 0.0) {
            "${clamped.toInt()}%"
        } else {
            "${clamped}%"
        }
    }

    fun formatDiscountBadge(discountPercentage: Double): String {
        return "${formatDiscountPercent(discountPercentage)} OFF"
    }

    fun validatePrice(input: String): PriceValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return PriceValidationResult.Error("Price is required")
        val value = trimmed.toDoubleOrNull() ?: return PriceValidationResult.Error("Invalid price")
        if (value < 0.0) return PriceValidationResult.Error("Price cannot be negative")
        return PriceValidationResult.Valid(value)
    }

    fun validateDiscount(input: String): DiscountValidationResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return DiscountValidationResult.Valid(0.0)
        val value = trimmed.toDoubleOrNull() ?: return DiscountValidationResult.Error("Invalid discount")
        if (value < 0.0 || value > 100.0) return DiscountValidationResult.Error("Discount must be between 0 and 100")
        return DiscountValidationResult.Valid(value)
    }

    fun hasDiscount(discountPercentage: Double): Boolean {
        return discountPercentage > 0.0
    }

    fun hasDiscount(price: Double, discountPercentage: Double): Boolean {
        return price > 0.0 && discountPercentage > 0.0
    }
}
