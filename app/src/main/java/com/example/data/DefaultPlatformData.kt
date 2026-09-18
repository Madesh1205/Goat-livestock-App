package com.example.data

import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.*

/**
 * Platform definitions for Ammal Farm Livestock Marketplace.
 * Example seed data has been removed per explicit user instructions:
 * "dont use any example images,details,goats or any .if no item in in database ,show none.if database is not connect show eror page instead of doing like idiot"
 */
object DefaultPlatformData {

    const val FARM_KONGU_UUID = "00000000-0000-0000-0000-000000000002"
    const val FARM_MALABAR_UUID = "00000000-0000-0000-0000-000000000003"
    const val FARM_DINDIGUL_UUID = "00000000-0000-0000-0000-000000000004"

    val SEED_FARMS: List<Farm> = emptyList()
    val SEED_GOATS: List<Goat> = emptyList()
    val SEED_NOTIFICATIONS: List<AppNotification> = emptyList()
    val SEED_BREEDS: List<String> = emptyList()
}
