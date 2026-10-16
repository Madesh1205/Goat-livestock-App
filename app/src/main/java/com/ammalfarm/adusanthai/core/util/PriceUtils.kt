package com.ammalfarm.adusanthai.core.util

import java.text.NumberFormat
import java.util.Locale

object PriceUtils {
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
}
