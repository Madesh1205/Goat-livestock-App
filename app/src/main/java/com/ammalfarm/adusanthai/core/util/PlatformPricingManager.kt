package com.ammalfarm.adusanthai.core.util

object PlatformPricingManager {
    var farmApprovalFeeInr: Double = 500.0
    var goatListingPackFeeInr: Double = 200.0
    var goatListingPackSize: Int = 5

    fun isConfigured(): Boolean = true
}
