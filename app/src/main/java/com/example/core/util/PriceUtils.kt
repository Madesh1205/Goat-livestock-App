package com.example.core.util

import java.util.Locale
import kotlin.math.round

/**
 * Authoritative, centralized pricing, discount calculation, validation, and currency formatting
 * for the Ammal Farm marketplace.
 */
object PriceUtils {

    /**
     * Calculates the final customer price after discount.
     * Formula: finalPrice = price * (1 - discountPercentage / 100)
     *
     * Handles edge cases: NaN, Infinity, negative values, and clamps discount to 0..100.
     * Uses standard currency rounding (round to nearest integer).
     */
    fun calculateFinalPrice(price: Double, discountPercentage: Double): Double {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return 0.0
        if (discountPercentage.isNaN() || discountPercentage.isInfinite() || discountPercentage <= 0.0) {
            return round(price)
        }
        val clampedDiscount = discountPercentage.coerceIn(0.0, 100.0)
        val discounted = price * (1.0 - (clampedDiscount / 100.0))
        return round(discounted).coerceAtLeast(0.0)
    }

    /**
     * Calculates customer savings: price - finalPrice.
     */
    fun calculateSavings(price: Double, discountPercentage: Double): Double {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return 0.0
        val final = calculateFinalPrice(price, discountPercentage)
        return (price - final).coerceAtLeast(0.0)
    }

    /**
     * Determines whether a goat has an active, displayable discount.
     * Returns true ONLY if price > 0, discountPercentage > 0, and finalPrice < price.
     */
    fun hasDiscount(price: Double, discountPercentage: Double): Boolean {
        if (price <= 0.0 || discountPercentage <= 0.0 || price.isNaN() || discountPercentage.isNaN()) return false
        val final = calculateFinalPrice(price, discountPercentage)
        return final < price
    }

    /**
     * Formats positive integer numbers with standard Indian grouping (e.g. 28,000; 1,00,000).
     */
    fun formatIndianNumber(number: Long): String {
        if (number < 0) return "-${formatIndianNumber(-number)}"
        val s = number.toString()
        if (s.length <= 3) return s
        val last3 = s.substring(s.length - 3)
        val remaining = s.substring(0, s.length - 3)
        val sb = StringBuilder()
        var count = 0
        for (i in remaining.length - 1 downTo 0) {
            sb.append(remaining[i])
            count++
            if (count == 2 && i > 0) {
                sb.append(',')
                count = 0
            }
        }
        return sb.reverse().toString() + "," + last3
    }

    /**
     * Formats an amount to Indian currency standard (e.g., ₹28,000, ₹22,400, ₹8,500, ₹0).
     */
    fun formatCurrency(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite() || amount < 0.0) return "₹0"
        val rounded = round(amount).toLong()
        return "₹" + formatIndianNumber(rounded)
    }

    /**
     * Formats the discount badge string (e.g. "20% OFF", "12.5% OFF").
     */
    fun formatDiscountBadge(discountPercentage: Double): String {
        return "${formatDiscountPercent(discountPercentage)} OFF"
    }

    /**
     * Formats the percentage portion (e.g. "20%", "12.5%").
     */
    fun formatDiscountPercent(discountPercentage: Double): String {
        val clamped = discountPercentage.coerceIn(0.0, 100.0)
        return if (clamped % 1.0 == 0.0) {
            "${clamped.toInt()}%"
        } else {
            "%.1f%%".format(Locale.US, clamped)
        }
    }

    /**
     * Validates raw price string input from Admin Add/Edit forms.
     */
    fun validatePrice(priceText: String): PriceValidationResult {
        val trimmed = priceText.trim()
        if (trimmed.isBlank()) {
            return PriceValidationResult.Error("Price is required")
        }
        val value = trimmed.toDoubleOrNull()
            ?: return PriceValidationResult.Error("Invalid price format")
        if (value.isNaN() || value.isInfinite()) {
            return PriceValidationResult.Error("Invalid price value")
        }
        if (value < 0.0) {
            return PriceValidationResult.Error("Price cannot be negative")
        }
        return PriceValidationResult.Valid(value)
    }

    /**
     * Validates raw discount string input from Admin Add/Edit forms.
     */
    fun validateDiscount(discountText: String): DiscountValidationResult {
        val trimmed = discountText.trim()
        if (trimmed.isBlank()) {
            return DiscountValidationResult.Valid(0.0)
        }
        val value = trimmed.toDoubleOrNull()
            ?: return DiscountValidationResult.Error("Invalid discount format")
        if (value.isNaN() || value.isInfinite()) {
            return DiscountValidationResult.Error("Invalid discount value")
        }
        if (value < 0.0) {
            return DiscountValidationResult.Error("Discount cannot be negative")
        }
        if (value > 100.0) {
            return DiscountValidationResult.Error("Discount cannot exceed 100%")
        }
        return DiscountValidationResult.Valid(value)
    }

    sealed class PriceValidationResult {
        data class Valid(val price: Double) : PriceValidationResult()
        data class Error(val message: String) : PriceValidationResult()
    }

    sealed class DiscountValidationResult {
        data class Valid(val discount: Double) : DiscountValidationResult()
        data class Error(val message: String) : DiscountValidationResult()
    }
}
