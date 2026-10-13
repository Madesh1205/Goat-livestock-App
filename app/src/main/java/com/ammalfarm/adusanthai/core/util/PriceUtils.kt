package com.ammalfarm.adusanthai.core.util

import java.text.NumberFormat
import java.util.Locale

object PriceUtils {
    fun formatINR(amount: Double): String {
        return "₹${amount.toInt()}"
    }
}
