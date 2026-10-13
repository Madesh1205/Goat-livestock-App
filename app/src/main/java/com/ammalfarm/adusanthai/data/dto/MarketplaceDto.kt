package com.ammalfarm.adusanthai.data.dto

const val SEED_AMMAL_FARM_UUID = "00000000-0000-0000-0000-000000000001"

fun ensureValidUuid(id: String?): String {
    if (id.isNullOrBlank()) return SEED_AMMAL_FARM_UUID
    return try {
        java.util.UUID.fromString(id)
        id
    } catch (e: Exception) {
        SEED_AMMAL_FARM_UUID
    }
}

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED
}

enum class AvailabilityStatus {
    AVAILABLE,
    BOOKED,
    SOLD,
    SUSPENDED
}
