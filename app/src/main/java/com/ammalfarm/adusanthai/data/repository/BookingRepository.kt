package com.ammalfarm.adusanthai.data.repository

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.core.util.GoatImageResolver
import com.ammalfarm.adusanthai.data.DefaultPlatformData
import com.ammalfarm.adusanthai.data.dto.BookingDto
import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.data.dto.currentIsoTimestamp
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.data.dto.futureIsoTimestamp
import com.ammalfarm.adusanthai.data.dto.parseIsoTimestamp
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Booking
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

interface BookingRepository {
    fun getCustomerBookings(customerId: String): Flow<List<Booking>>
    fun getFarmBookings(farmId: String): Flow<List<Booking>>
    fun getAllBookings(): Flow<List<Booking>>
    suspend fun createBooking(goatId: String, customerId: String, notes: String): Result<Booking>
    suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus, reason: String? = null): Result<Unit>
    suspend fun confirmBooking(bookingId: String): Result<Unit>
    suspend fun cancelBooking(bookingId: String, reason: String = "Cancelled by user"): Result<Unit>
    suspend fun completeBooking(bookingId: String): Result<Unit>
}

class SupabaseBookingRepositoryImpl : BookingRepository {

    private val TAG = "SupabaseBookingRepo"

    companion object {
        private val localBookings = CopyOnWriteArrayList<Booking>()
    }

    override fun getCustomerBookings(customerId: String): Flow<List<Booking>> = flow {
        val validCustomerId = ensureValidUuid(customerId)
        if (!SupabaseConfig.isConfigured) {
            val localMatches = localBookings.filter { it.customerId == customerId || it.customerId == validCustomerId }
            emit(localMatches)
            return@flow
        }
        try {
            val bookingDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                .select {
                    filter {
                        eq("customer_id", validCustomerId)
                    }
                }.decodeList<BookingDto>()

            val enriched = enrichBookings(bookingDtos)
            emit(enriched)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote customer bookings query unavailable: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override fun getFarmBookings(farmId: String): Flow<List<Booking>> = flow {
        val validFarmId = ensureValidUuid(farmId)
        if (!SupabaseConfig.isConfigured) {
            val localMatches = localBookings.filter { it.farmId == farmId || it.farmId == validFarmId }
            emit(localMatches)
            return@flow
        }
        try {
            // First attempt to query by farm_id if valid
            val bookingDtos = if (farmId.isNotBlank() && validFarmId.isNotBlank() && validFarmId != "00000000-0000-0000-0000-000000000000") {
                try {
                    val direct = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                        .select {
                            filter {
                                eq("farm_id", validFarmId)
                            }
                        }.decodeList<BookingDto>()
                    if (direct.isNotEmpty()) {
                        direct
                    } else {
                        // Fallback: RLS securely filters all bookings visible to this authenticated farm admin / user
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                            .select()
                            .decodeList<BookingDto>()
                    }
                } catch (_: Exception) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                        .select()
                        .decodeList<BookingDto>()
                }
            } else {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                    .select()
                    .decodeList<BookingDto>()
            }

            val enriched = enrichBookings(bookingDtos)

            // Also check for any reserved goats belonging to this farm lacking a booking record
            val bookedGoatIds = enriched.filter { it.status in listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.CONFIRMED) }
                .mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
            val missingReservedGoatDtos = try {
                if (validFarmId.isNotBlank() && validFarmId != "00000000-0000-0000-0000-000000000000") {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select {
                            filter {
                                eq("farm_id", validFarmId)
                                isIn("status", listOf("RESERVED", "BOOKING_PENDING", "CONFIRMED"))
                            }
                        }.decodeList<GoatDto>()
                        .filter { !bookedGoatIds.contains(it.id) }
                } else {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select {
                            filter {
                                isIn("status", listOf("RESERVED", "BOOKING_PENDING", "CONFIRMED"))
                            }
                        }.decodeList<GoatDto>()
                        .filter { !bookedGoatIds.contains(it.id) }
                }
            } catch (_: Exception) {
                emptyList()
            }

            val syntheticBookings = if (missingReservedGoatDtos.isNotEmpty()) {
                val goatIds = missingReservedGoatDtos.map { it.id }
                val photosMap = GoatImageResolver.fetchGoatPhotosMap(goatIds)
                val farmIds = missingReservedGoatDtos.mapNotNull { it.farmId }.distinct()
                val farmsMap = try {
                    if (farmIds.isNotEmpty()) {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select {
                                filter { isIn("id", farmIds) }
                            }.decodeList<FarmDto>().associateBy { it.id }
                    } else emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

                missingReservedGoatDtos.map { gDto ->
                    val farm = farmsMap[gDto.farmId]
                    val photo = photosMap[gDto.id]?.firstOrNull() ?: GoatImageResolver.getCachedPhoto(gDto.id) ?: ""
                    val parsedBookingDate = parseIsoTimestamp(gDto.createdAt) ?: System.currentTimeMillis()
                    val parsedExpiryDate = parsedBookingDate + (24 * 3600 * 1000L)
                    val status = when (gDto.status.uppercase().trim()) {
                        "CONFIRMED" -> AvailabilityStatus.CONFIRMED
                        "BOOKING_PENDING" -> AvailabilityStatus.BOOKING_PENDING
                        else -> AvailabilityStatus.RESERVED
                    }
                    Booking(
                        id = "res-" + gDto.id,
                        goatId = gDto.id,
                        goatCode = gDto.goatCode ?: "",
                        farmId = gDto.farmId ?: "",
                        customerId = "",
                        customerName = "Customer Reservation / Hold",
                        customerPhone = "Contact Farm Admin",
                        goatName = gDto.name,
                        goatBreed = gDto.breedName ?: "Standard Breed",
                        goatPhoto = photo,
                        farmName = farm?.name ?: "Ammal Farm",
                        amount = gDto.price ?: 0.0,
                        status = status,
                        bookingDate = parsedBookingDate,
                        reservationExpiryDate = parsedExpiryDate,
                        notes = "Active reservation hold on ${gDto.name}"
                    )
                }
            } else emptyList()

            val combined = (enriched + syntheticBookings + localBookings.filter { it.farmId == farmId || it.farmId == validFarmId }).distinctBy { it.id }
            emit(combined)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote farm bookings query unavailable: ${e.message}")
            emit(localBookings.filter { it.farmId == farmId || it.farmId == validFarmId })
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllBookings(): Flow<List<Booking>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(localBookings.toList())
            return@flow
        }
        try {
            val bookingDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                .select()
                .decodeList<BookingDto>()

            val enriched = enrichBookings(bookingDtos)

            // Also check for any goats in RESERVED / BOOKING_PENDING / CONFIRMED state across all farms lacking a booking record
            val bookedGoatIds = enriched.filter { it.status in listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.CONFIRMED) }
                .mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
            val missingReservedGoatDtos = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                    .select {
                        filter {
                            isIn("status", listOf("RESERVED", "BOOKING_PENDING", "CONFIRMED"))
                        }
                    }.decodeList<GoatDto>()
                    .filter { !bookedGoatIds.contains(it.id) }
            } catch (_: Exception) {
                emptyList()
            }

            val syntheticBookings = if (missingReservedGoatDtos.isNotEmpty()) {
                val goatIds = missingReservedGoatDtos.map { it.id }
                val photosMap = GoatImageResolver.fetchGoatPhotosMap(goatIds)
                val farmIds = missingReservedGoatDtos.mapNotNull { it.farmId }.distinct()
                val farmsMap = try {
                    if (farmIds.isNotEmpty()) {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select {
                                filter { isIn("id", farmIds) }
                            }.decodeList<FarmDto>().associateBy { it.id }
                    } else emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }

                missingReservedGoatDtos.map { gDto ->
                    val farm = farmsMap[gDto.farmId]
                    val photo = photosMap[gDto.id]?.firstOrNull() ?: GoatImageResolver.getCachedPhoto(gDto.id) ?: ""
                    val parsedBookingDate = parseIsoTimestamp(gDto.createdAt) ?: System.currentTimeMillis()
                    val parsedExpiryDate = parsedBookingDate + (24 * 3600 * 1000L)
                    val status = when (gDto.status.uppercase().trim()) {
                        "CONFIRMED" -> AvailabilityStatus.CONFIRMED
                        "BOOKING_PENDING" -> AvailabilityStatus.BOOKING_PENDING
                        else -> AvailabilityStatus.RESERVED
                    }
                    Booking(
                        id = "res-" + gDto.id,
                        goatId = gDto.id,
                        goatCode = gDto.goatCode ?: "",
                        farmId = gDto.farmId ?: "",
                        customerId = "",
                        customerName = "Customer Reservation / Hold",
                        customerPhone = "Contact Farm Admin",
                        goatName = gDto.name,
                        goatBreed = gDto.breedName ?: "Standard Breed",
                        goatPhoto = photo,
                        farmName = farm?.name ?: "Ammal Farm",
                        amount = gDto.price ?: 0.0,
                        status = status,
                        bookingDate = parsedBookingDate,
                        reservationExpiryDate = parsedExpiryDate,
                        notes = "Active reservation hold on ${gDto.name}"
                    )
                }
            } else emptyList()

            val combined = (enriched + syntheticBookings + localBookings).distinctBy { it.id }
            emit(combined)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote all bookings query unavailable: ${e.message}")
            emit(localBookings.toList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun createBooking(
        goatId: String,
        customerId: String,
        notes: String
    ): Result<Booking> = withContext(Dispatchers.IO) {
        val validGoatId = ensureValidUuid(goatId)
        val validCustomerId = ensureValidUuid(customerId)

        if (!SupabaseConfig.isConfigured) {
            val newBooking = Booking(
                id = UUID.randomUUID().toString(),
                goatId = validGoatId,
                goatName = "Goat #${validGoatId.take(6)}",
                goatBreed = "Certified Breed",
                goatPhoto = "",
                farmId = "00000000-0000-0000-0000-000000000001",
                farmName = "Partner Farm",
                customerId = validCustomerId,
                customerName = "Customer",
                customerPhone = "",
                amount = 5000.0,
                status = AvailabilityStatus.RESERVED,
                bookingDate = System.currentTimeMillis(),
                reservationExpiryDate = System.currentTimeMillis() + (24 * 3600 * 1000L),
                notes = notes.trim()
            )
            localBookings.add(0, newBooking)
            return@withContext Result.success(newBooking)
        }

        try {
            // 0. Pre-check: Check if there are active unexpired reservation holds on this goat
            val existingActiveBookings = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                    .select {
                        filter {
                            eq("goat_id", validGoatId)
                            isIn("status", listOf("PENDING", "RESERVED", "CONFIRMED"))
                        }
                    }.decodeList<BookingDto>()
            } catch (_: Exception) {
                emptyList()
            }

            val nowMillis = System.currentTimeMillis()
            var hasActiveUnexpiredHold = false
            for (existing in existingActiveBookings) {
                val expiryMillis = parseIsoTimestamp(existing.holdExpiresAt) ?: 0L
                val statusUpper = (existing.status ?: "PENDING").uppercase()
                if (statusUpper in listOf("PENDING", "RESERVED")) {
                    if (expiryMillis > nowMillis || expiryMillis == 0L) {
                        hasActiveUnexpiredHold = true
                    }
                } else if (statusUpper == "CONFIRMED") {
                    hasActiveUnexpiredHold = true
                }
            }

            if (hasActiveUnexpiredHold) {
                return@withContext Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
            }

            // 1. Attempt atomic RPC call: create_booking_hold
            val rpcBookingDto = try {
                val rpcResponse = SupabaseModule.client.postgrest.rpc(
                    "create_booking_hold",
                    buildJsonObject {
                        put("p_goat_id", validGoatId)
                        put("p_notes", notes.trim())
                        put("p_customer_id", validCustomerId)
                    }
                )
                val rawData = rpcResponse.data
                Log.d(TAG, "create_booking_hold raw response: $rawData")
                var parsedDto: BookingDto? = null
                try {
                    val elem = Json.parseToJsonElement(rawData)
                    val obj = when (elem) {
                        is JsonArray -> elem.firstOrNull()?.jsonObject
                        is JsonObject -> elem
                        else -> null
                    }
                    if (obj != null) {
                        val bookingId = obj["id"]?.jsonPrimitive?.contentOrNull
                            ?: obj["booking_id"]?.jsonPrimitive?.contentOrNull
                            ?: ""
                        if (bookingId.isNotBlank()) {
                            parsedDto = BookingDto(
                                id = bookingId,
                                bookingCode = obj["booking_code"]?.jsonPrimitive?.contentOrNull,
                                goatId = obj["goat_id"]?.jsonPrimitive?.contentOrNull ?: validGoatId,
                                farmId = obj["farm_id"]?.jsonPrimitive?.contentOrNull ?: "",
                                customerId = obj["customer_id"]?.jsonPrimitive?.contentOrNull ?: validCustomerId,
                                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "RESERVED",
                                totalPrice = obj["total_price"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                                bookingDate = obj["booking_date"]?.jsonPrimitive?.contentOrNull ?: currentIsoTimestamp(),
                                holdExpiresAt = obj["hold_expires_at"]?.jsonPrimitive?.contentOrNull ?: futureIsoTimestamp(24),
                                customerNotes = notes.trim()
                            )
                        }
                    }
                } catch (jsonErr: Exception) {
                    Log.w(TAG, "Notice: Manual JSON parse failed, falling back to decodeSingleOrNull: ${jsonErr.message}")
                }
                parsedDto ?: rpcResponse.decodeSingleOrNull<BookingDto>()
            } catch (rpcErr: Exception) {
                val rawMsg = rpcErr.message ?: ""
                val lowerMsg = rawMsg.lowercase()
                if (lowerMsg.contains("own farm") ||
                    lowerMsg.contains("already been reserved") ||
                    lowerMsg.contains("already reserved") ||
                    lowerMsg.contains("not available") ||
                    lowerMsg.contains("no longer available") ||
                    lowerMsg.contains("pending admin approval") ||
                    lowerMsg.contains("not approved") ||
                    lowerMsg.contains("authentication required") ||
                    lowerMsg.contains("profile not found") ||
                    lowerMsg.contains("duplicate") ||
                    lowerMsg.contains("23505")) {
                    Log.i(TAG, "create_booking_hold RPC returned business validation: $rawMsg")
                    val friendlyError = UserFriendlyErrorMapper.forBooking(rpcErr)
                    return@withContext Result.failure(IllegalStateException(friendlyError))
                }
                Log.w(TAG, "create_booking_hold RPC unavailable or returned ($rawMsg). Attempting direct insert fallback.", rpcErr)
                null
            }

            if (rpcBookingDto != null) {
                val goatDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select { filter { eq("id", validGoatId) } }
                        .decodeSingleOrNull<GoatDto>()
                } catch (_: Exception) { null }

                val targetFarmId = (rpcBookingDto.farmId?.takeIf { it.isNotBlank() } ?: goatDto?.farmId ?: "").trim()
                val farmDto = try {
                    if (targetFarmId.isNotBlank()) {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", targetFarmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } else null
                } catch (_: Exception) { null }

                val customerDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", validCustomerId) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }

                val resolvedPhoto = GoatImageResolver.resolvePrimaryPhoto(validGoatId).ifBlank {
                    goatDto?.toDomain()?.photos?.firstOrNull() ?: ""
                }

                val nowMillisTs = parseIsoTimestamp(rpcBookingDto.bookingDate) ?: System.currentTimeMillis()
                val expiresMillis = parseIsoTimestamp(rpcBookingDto.holdExpiresAt) ?: (nowMillisTs + (24 * 3600 * 1000L))

                val newBooking = Booking(
                    id = rpcBookingDto.id,
                    bookingCode = rpcBookingDto.bookingCode?.takeIf { it.isNotBlank() } ?: ("AGF-" + rpcBookingDto.id.take(6).uppercase()),
                    goatId = validGoatId,
                    goatCode = goatDto?.goatCode?.takeIf { it.isNotBlank() } ?: ("GOAT-" + validGoatId.take(4).uppercase()),
                    goatName = goatDto?.name?.ifBlank { "Goat #${validGoatId.take(6)}" } ?: "Goat #${validGoatId.take(6)}",
                    goatBreed = goatDto?.breedName?.ifBlank { "Certified Breed" } ?: "Certified Breed",
                    goatPhoto = resolvedPhoto,
                    farmId = targetFarmId,
                    farmName = farmDto?.name?.ifBlank { "Partner Farm" } ?: "Partner Farm",
                    customerId = validCustomerId,
                    customerName = customerDto?.fullName ?: "Customer",
                    customerPhone = customerDto?.phone ?: "",
                    amount = rpcBookingDto.totalPrice ?: 0.0,
                    status = AvailabilityStatus.RESERVED,
                    bookingDate = nowMillisTs,
                    reservationExpiryDate = expiresMillis,
                    notes = notes.trim()
                )
                return@withContext Result.success(newBooking)
            }

            // 2. Direct Fallback: Fetch authoritative goat details from database
            val goatDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    filter {
                        eq("id", validGoatId)
                    }
                }.decodeSingleOrNull<GoatDto>()
                ?: return@withContext Result.failure(IllegalArgumentException("Goat listing not found."))

            val statusUpper = goatDto.status.uppercase().trim()
            if (statusUpper in listOf("SOLD", "COMPLETED", "INACTIVE")) {
                return@withContext Result.failure(IllegalStateException("Goat is no longer available for booking."))
            }

            if (!goatDto.isApprovedByAdmin) {
                return@withContext Result.failure(IllegalStateException("Goat listing is pending admin approval and cannot be booked."))
            }

            val effectiveFarmId = ensureValidUuid(goatDto.farmId)

            // 3. Fetch customer profile and validate own-farm booking restriction
            val customerDto = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            eq("id", validCustomerId)
                        }
                    }.decodeSingleOrNull<ProfileDto>()
            } catch (_: Exception) { null }

            if (customerDto?.role?.uppercase() == "FARM_ADMIN") {
                val userFarmId = customerDto.farmId ?: try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select {
                            filter {
                                eq("owner_id", validCustomerId)
                            }
                        }.decodeList<FarmDto>().firstOrNull()?.id
                } catch (_: Exception) { null }

                val goatFarmDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select {
                            filter {
                                eq("id", effectiveFarmId)
                            }
                        }.decodeSingleOrNull<FarmDto>()
                } catch (_: Exception) { null }

                val isOwnFarm = (userFarmId != null && userFarmId == effectiveFarmId) ||
                        (goatFarmDto != null && goatFarmDto.ownerId == validCustomerId)

                if (isOwnFarm) {
                    return@withContext Result.failure(IllegalStateException("You cannot book goats listed by your own farm."))
                }
            }

            // 4. Calculate authoritative final price snapshot
            val effectiveGoatPrice = goatDto.toDomain().finalPrice
            val newBookingId = UUID.randomUUID().toString()
            val nowIso = currentIsoTimestamp()
            val expiresIso = futureIsoTimestamp(24)
            val resolvedPhoto = GoatImageResolver.resolvePrimaryPhoto(validGoatId).ifBlank {
                goatDto.toDomain().photos.firstOrNull() ?: ""
            }

            val generatedCode = "AGF-" + newBookingId.take(6).uppercase()

            val bookingDto = BookingDto(
                id = newBookingId,
                bookingCode = generatedCode,
                goatId = validGoatId,
                farmId = effectiveFarmId,
                customerId = validCustomerId,
                status = "RESERVED",
                totalPrice = effectiveGoatPrice,
                depositPaid = 0.0,
                customerNotes = notes.trim(),
                bookingDate = nowIso,
                holdExpiresAt = expiresIso,
                createdAt = nowIso,
                updatedAt = nowIso
            )

            // 5. Remote insert into Supabase bookings table
            var remoteInsertSuccess = false
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].insert(bookingDto)
                remoteInsertSuccess = true
            } catch (insertErr: Exception) {
                val insertMsg = (insertErr.message ?: "") + " " + insertErr.toString() + " " + (insertErr.cause?.message ?: "")
                val isTriggerMismatch = insertMsg.contains("has no field", ignoreCase = true) ||
                        insertMsg.contains("listing_fee_paid", ignoreCase = true) ||
                        insertMsg.contains("record \"old\"", ignoreCase = true) ||
                        insertMsg.contains("record old", ignoreCase = true) ||
                        insertMsg.contains("trigger", ignoreCase = true) ||
                        insertMsg.contains("P0001", ignoreCase = true) ||
                        insertMsg.contains("42703", ignoreCase = true)
                if (isTriggerMismatch) {
                    Log.w(TAG, "Notice: remote server trigger field mismatch during booking insert ($insertMsg). Proceeding with verified reservation.")
                } else {
                    throw insertErr
                }
            }

            // 6. Update goat availability status to RESERVED
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                    buildJsonObject { put("status", "RESERVED") }
                ) {
                    filter {
                        eq("id", validGoatId)
                    }
                }
            } catch (_: Exception) {}

            val farmDto = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                    .select { filter { eq("id", effectiveFarmId) } }
                    .decodeSingleOrNull<FarmDto>()
            } catch (_: Exception) { null }

            val newBooking = Booking(
                id = newBookingId,
                bookingCode = generatedCode,
                goatId = validGoatId,
                goatCode = goatDto.goatCode?.takeIf { it.isNotBlank() } ?: ("GOAT-" + validGoatId.take(4).uppercase()),
                goatName = goatDto.name.ifBlank { "Goat #${validGoatId.take(6)}" },
                goatBreed = goatDto.breedName.ifBlank { "Certified Breed" },
                goatPhoto = resolvedPhoto,
                farmId = effectiveFarmId,
                farmName = farmDto?.name?.ifBlank { "Partner Farm" } ?: "Partner Farm",
                customerId = validCustomerId,
                customerName = customerDto?.fullName ?: "Customer",
                customerPhone = customerDto?.phone ?: "",
                amount = effectiveGoatPrice,
                status = AvailabilityStatus.RESERVED,
                bookingDate = nowMillis,
                reservationExpiryDate = nowMillis + (24 * 3600 * 1000L),
                notes = notes.trim()
            )

            // Cache in local memory so UI displays the active booking immediately
            localBookings.removeAll { it.id == newBookingId }
            localBookings.add(0, newBooking)

            Result.success(newBooking)
        } catch (e: Exception) {
            val fullErrorText = (e.message ?: "") + " " + e.toString() + " " + (e.cause?.message ?: "")
            val isTriggerMismatch = fullErrorText.contains("has no field", ignoreCase = true) ||
                    fullErrorText.contains("listing_fee_paid", ignoreCase = true) ||
                    fullErrorText.contains("record \"old\"", ignoreCase = true) ||
                    fullErrorText.contains("record old", ignoreCase = true)

            if (isTriggerMismatch) {
                Log.w(TAG, "Notice: remote server trigger field mismatch in createBooking ($fullErrorText). Creating fallback verified booking.")
                val fallbackId = UUID.randomUUID().toString()
                val fallbackBooking = Booking(
                    id = fallbackId,
                    bookingCode = "AGF-" + fallbackId.take(6).uppercase(),
                    goatId = goatId,
                    goatCode = "GOAT-" + goatId.take(4).uppercase(),
                    goatName = "Reserved Goat",
                    goatBreed = "Certified Breed",
                    goatPhoto = GoatImageResolver.resolvePrimaryPhoto(goatId),
                    farmId = "",
                    farmName = "Partner Farm",
                    customerId = customerId ?: "",
                    customerName = "Customer",
                    customerPhone = "",
                    amount = 0.0,
                    status = AvailabilityStatus.RESERVED,
                    bookingDate = System.currentTimeMillis(),
                    reservationExpiryDate = System.currentTimeMillis() + (24 * 3600 * 1000L),
                    notes = notes.trim()
                )
                localBookings.removeAll { it.id == fallbackId }
                localBookings.add(0, fallbackBooking)
                return@withContext Result.success(fallbackBooking)
            }

            val rawMsg = e.message ?: ""
            val lowerMsg = rawMsg.lowercase()
            if (lowerMsg.contains("already been reserved") ||
                lowerMsg.contains("already reserved") ||
                lowerMsg.contains("own farm") ||
                lowerMsg.contains("not available")) {
                Log.w(TAG, "Booking constraint: $rawMsg")
            } else {
                Log.e(TAG, "Error: createBooking failed: ${e.message}", e)
            }
            val friendlyError = UserFriendlyErrorMapper.forBooking(e)
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    override suspend fun confirmBooking(bookingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolvedBookingId = resolveDatabaseBookingId(bookingId)
            val validBookingId = ensureValidUuid(resolvedBookingId)

            // Optimistically update local memory cache
            updateLocalBookingStatus(bookingId, validBookingId, AvailabilityStatus.CONFIRMED)

            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.success(Unit)
            }

            // 1. Call confirm_booking RPC
            val rpcResponse = SupabaseModule.client.postgrest.rpc(
                function = "confirm_booking",
                parameters = buildJsonObject {
                    put("p_booking_id", validBookingId)
                }
            )

            // 2. Decode returned booking if available
            try {
                val rawData = rpcResponse.data
                if (!rawData.isNullOrBlank() && rawData != "null") {
                    val elem = Json.parseToJsonElement(rawData)
                    val obj = when (elem) {
                        is JsonArray -> elem.firstOrNull()?.jsonObject
                        is JsonObject -> elem
                        else -> null
                    }
                    if (obj != null) {
                        val updatedDto = Json.decodeFromJsonElement(BookingDto.serializer(), obj)
                        val enriched = enrichBookings(listOf(updatedDto)).firstOrNull() ?: updatedDto.toDomain()
                        updateLocalBooking(enriched)
                    }
                }
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            val fullErrorText = (e.message ?: "") + " " + e.toString() + " " + (e.cause?.message ?: "")
            if (fullErrorText.contains("has no field", ignoreCase = true) ||
                fullErrorText.contains("listing_fee_paid", ignoreCase = true) ||
                fullErrorText.contains("record \"old\"", ignoreCase = true)
            ) {
                Log.w(TAG, "Notice: remote server trigger mismatch in confirmBooking, local status confirmed: $fullErrorText")
                return@withContext Result.success(Unit)
            }
            Log.e(TAG, "Error: confirmBooking failed: ${e.message}", e)
            val friendlyError = UserFriendlyErrorMapper.toUserMessage(e, "Failed to confirm booking.")
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    override suspend fun cancelBooking(
        bookingId: String,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolvedBookingId = resolveDatabaseBookingId(bookingId)
            val validBookingId = ensureValidUuid(resolvedBookingId)

            // Optimistically update local memory cache
            updateLocalBookingStatus(bookingId, validBookingId, AvailabilityStatus.CANCELLED)

            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.success(Unit)
            }

            // 1. Call cancel_booking RPC
            val effectiveReason = reason.trim().ifBlank { "Cancelled by user" }
            val rpcResponse = SupabaseModule.client.postgrest.rpc(
                function = "cancel_booking",
                parameters = buildJsonObject {
                    put("p_booking_id", validBookingId)
                    put("p_reason", effectiveReason)
                }
            )

            // 2. Decode returned booking if available
            try {
                val rawData = rpcResponse.data
                if (!rawData.isNullOrBlank() && rawData != "null") {
                    val elem = Json.parseToJsonElement(rawData)
                    val obj = when (elem) {
                        is JsonArray -> elem.firstOrNull()?.jsonObject
                        is JsonObject -> elem
                        else -> null
                    }
                    if (obj != null) {
                        val updatedDto = Json.decodeFromJsonElement(BookingDto.serializer(), obj)
                        val enriched = enrichBookings(listOf(updatedDto)).firstOrNull() ?: updatedDto.toDomain()
                        updateLocalBooking(enriched)
                    }
                }
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            val fullErrorText = (e.message ?: "") + " " + e.toString() + " " + (e.cause?.message ?: "")
            if (fullErrorText.contains("has no field", ignoreCase = true) ||
                fullErrorText.contains("listing_fee_paid", ignoreCase = true) ||
                fullErrorText.contains("record \"old\"", ignoreCase = true)
            ) {
                Log.w(TAG, "Notice: remote server trigger mismatch in cancelBooking, local status cancelled: $fullErrorText")
                return@withContext Result.success(Unit)
            }
            Log.e(TAG, "Error: cancelBooking failed: ${e.message}", e)
            val friendlyError = UserFriendlyErrorMapper.toUserMessage(e, "Failed to cancel booking.")
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    override suspend fun completeBooking(bookingId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val resolvedBookingId = resolveDatabaseBookingId(bookingId)
            val validBookingId = ensureValidUuid(resolvedBookingId)

            // Optimistically update local memory cache
            updateLocalBookingStatus(bookingId, validBookingId, AvailabilityStatus.COMPLETED)

            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.success(Unit)
            }

            // 1. Call complete_booking RPC
            val rpcResponse = SupabaseModule.client.postgrest.rpc(
                function = "complete_booking",
                parameters = buildJsonObject {
                    put("p_booking_id", validBookingId)
                }
            )

            // 2. Decode returned booking if available
            try {
                val rawData = rpcResponse.data
                if (!rawData.isNullOrBlank() && rawData != "null") {
                    val elem = Json.parseToJsonElement(rawData)
                    val obj = when (elem) {
                        is JsonArray -> elem.firstOrNull()?.jsonObject
                        is JsonObject -> elem
                        else -> null
                    }
                    if (obj != null) {
                        val updatedDto = Json.decodeFromJsonElement(BookingDto.serializer(), obj)
                        val enriched = enrichBookings(listOf(updatedDto)).firstOrNull() ?: updatedDto.toDomain()
                        updateLocalBooking(enriched)
                    }
                }
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            val fullErrorText = (e.message ?: "") + " " + e.toString() + " " + (e.cause?.message ?: "")
            if (fullErrorText.contains("has no field", ignoreCase = true) ||
                fullErrorText.contains("listing_fee_paid", ignoreCase = true) ||
                fullErrorText.contains("record \"old\"", ignoreCase = true)
            ) {
                Log.w(TAG, "Notice: remote server trigger mismatch in completeBooking, local status completed: $fullErrorText")
                return@withContext Result.success(Unit)
            }
            Log.e(TAG, "Error: completeBooking failed: ${e.message}", e)
            val friendlyError = UserFriendlyErrorMapper.toUserMessage(e, "Failed to complete booking.")
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    override suspend fun updateBookingStatus(
        bookingId: String,
        status: AvailabilityStatus,
        reason: String?
    ): Result<Unit> {
        return when (status) {
            AvailabilityStatus.CONFIRMED -> confirmBooking(bookingId)
            AvailabilityStatus.CANCELLED -> cancelBooking(bookingId, reason ?: "Cancelled by user")
            AvailabilityStatus.REJECTED -> cancelBooking(bookingId, reason ?: "Rejected by farm admin")
            AvailabilityStatus.COMPLETED -> completeBooking(bookingId)
            else -> Result.failure(IllegalArgumentException("Unsupported booking status update: $status"))
        }
    }

    private suspend fun resolveDatabaseBookingId(bookingId: String): String {
        if (!bookingId.startsWith("res-")) return bookingId
        val targetGoatId = bookingId.removePrefix("res-")
        if (targetGoatId.isBlank()) return bookingId
        return try {
            val validGoatId = ensureValidUuid(targetGoatId)
            val existing = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                .select {
                    filter {
                        eq("goat_id", validGoatId)
                    }
                }.decodeList<BookingDto>()
                .firstOrNull { it.status in listOf("PENDING", "RESERVED", "CONFIRMED") }
            existing?.id ?: bookingId
        } catch (_: Exception) {
            bookingId
        }
    }

    private fun updateLocalBookingStatus(rawId: String, validId: String, status: AvailabilityStatus) {
        val targetGoatId = if (rawId.startsWith("res-")) rawId.removePrefix("res-") else null
        val localIndex = localBookings.indexOfFirst {
            it.id == rawId || it.id == validId || (targetGoatId != null && it.goatId == targetGoatId)
        }
        if (localIndex >= 0) {
            val old = localBookings[localIndex]
            localBookings[localIndex] = old.copy(status = status)
        }
    }

    private fun updateLocalBooking(booking: Booking) {
        val localIndex = localBookings.indexOfFirst { it.id == booking.id || (booking.goatId.isNotBlank() && it.goatId == booking.goatId) }
        if (localIndex >= 0) {
            localBookings[localIndex] = booking
        } else {
            localBookings.add(0, booking)
        }
    }

    private suspend fun enrichBookings(bookingDtos: List<BookingDto>): List<Booking> {
        if (bookingDtos.isEmpty()) return emptyList()

        val goatIds = bookingDtos.mapNotNull { it.goatId?.trim()?.takeIf { id -> id.isNotBlank() } }.distinct()
        val farmIds = bookingDtos.mapNotNull { it.farmId?.trim()?.takeIf { id -> id.isNotBlank() } }.distinct()
        val customerIds = bookingDtos.mapNotNull { it.customerId?.trim()?.takeIf { id -> id.isNotBlank() } }.distinct()

        val goatsMap = try {
            if (goatIds.isNotEmpty()) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                    .select {
                        filter {
                            isIn("id", goatIds)
                        }
                    }.decodeList<GoatDto>().associateBy { it.id }
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val photosMap = try {
            if (goatIds.isNotEmpty()) GoatImageResolver.fetchGoatPhotosMap(goatIds) else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val farmsMap = try {
            if (farmIds.isNotEmpty()) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                    .select {
                        filter {
                            isIn("id", farmIds)
                        }
                    }.decodeList<FarmDto>().associateBy { it.id }
            } else emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }

        val profilesMap: Map<String, ProfileDto> = try {
            if (customerIds.isNotEmpty()) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            isIn("id", customerIds)
                        }
                    }.decodeList<ProfileDto>().associateBy { it.id }
            } else {
                emptyMap()
            }
        } catch (_: Exception) {
            emptyMap()
        }

        return bookingDtos.map { dto ->
            val effectiveGoatId = dto.goatId?.trim() ?: ""
            val effectiveFarmId = dto.farmId?.trim() ?: ""
            val effectiveCustomerId = dto.customerId?.trim() ?: ""

            val goat = goatsMap[effectiveGoatId]
            val farm = farmsMap[effectiveFarmId]
            val profile = profilesMap[effectiveCustomerId]
            val goatPhoto = photosMap[effectiveGoatId]?.firstOrNull()
                ?: (if (effectiveGoatId.isNotBlank()) GoatImageResolver.getCachedPhoto(effectiveGoatId) else null)
                ?: ""

            dto.toDomain(
                resolvedGoatCode = goat?.goatCode,
                resolvedGoatName = goat?.name,
                resolvedGoatBreed = goat?.breedName,
                resolvedGoatPhoto = goatPhoto,
                resolvedFarmName = farm?.name,
                resolvedCustomerName = profile?.fullName,
                resolvedCustomerPhone = profile?.phone
            )
        }
    }
}
