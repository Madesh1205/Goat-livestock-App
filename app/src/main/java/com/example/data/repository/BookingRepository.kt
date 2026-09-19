package com.example.data.repository

import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.core.util.GoatImageResolver
import com.example.data.DefaultPlatformData
import com.example.data.dto.BookingDto
import com.example.data.dto.FarmDto
import com.example.data.dto.GoatDto
import com.example.data.dto.ProfileDto
import com.example.data.dto.currentIsoTimestamp
import com.example.data.dto.ensureValidUuid
import com.example.data.dto.futureIsoTimestamp
import com.example.data.dto.parseIsoTimestamp
import com.example.model.AvailabilityStatus
import com.example.model.Booking
import com.example.util.UserFriendlyErrorMapper
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
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
    suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus): Result<Unit>
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
            val bookingDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                .select {
                    filter {
                        eq("farm_id", validFarmId)
                    }
                }.decodeList<BookingDto>()

            val enriched = enrichBookings(bookingDtos)
            emit(enriched)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote farm bookings query unavailable: ${e.message}")
            emit(emptyList())
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
            emit(enriched)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote all bookings query unavailable: ${e.message}")
            emit(emptyList())
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
            return@withContext Result.failure(IllegalStateException("Database not connected."))
        }

        try {
            // 1. Fetch authoritative goat details from database
            val goatDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    filter {
                        eq("id", validGoatId)
                    }
                }.decodeSingleOrNull<GoatDto>()
                ?: return@withContext Result.failure(IllegalArgumentException("Goat listing not found."))

            if (goatDto.status.uppercase() != "AVAILABLE") {
                return@withContext Result.failure(IllegalStateException("Goat is no longer available for booking."))
            }

            if (!goatDto.isApprovedByAdmin && goatDto.status.uppercase() != "APPROVED") {
                return@withContext Result.failure(IllegalStateException("Goat listing is pending admin approval and cannot be booked."))
            }

            // 2. Check for active reservation holds and expire overdue ones
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
                if (existing.status.uppercase() in listOf("PENDING", "RESERVED") && expiryMillis in 1..nowMillis) {
                    try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].update(
                            buildJsonObject { put("status", "EXPIRED") }
                        ) {
                            filter {
                                eq("id", existing.id)
                            }
                        }
                    } catch (_: Exception) {}
                } else if (existing.status.uppercase() in listOf("PENDING", "RESERVED", "CONFIRMED")) {
                    hasActiveUnexpiredHold = true
                }
            }

            if (hasActiveUnexpiredHold) {
                return@withContext Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
            }

            val effectiveFarmId = ensureValidUuid(goatDto.farmId)

            // 3. Fetch customer profile
            val customerDto = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            eq("id", validCustomerId)
                        }
                    }.decodeSingleOrNull<ProfileDto>()
            } catch (_: Exception) { null }

            if (customerDto?.role?.uppercase() == "FARM_ADMIN") {
                val userFarmId = customerDto.farmId
                val farmDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select {
                            filter {
                                eq("id", effectiveFarmId)
                            }
                        }.decodeSingleOrNull<FarmDto>()
                } catch (_: Exception) { null }

                if ((userFarmId != null && userFarmId == effectiveFarmId) || farmDto?.ownerId == validCustomerId) {
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

            val bookingDto = BookingDto(
                id = newBookingId,
                goatId = validGoatId,
                farmId = effectiveFarmId,
                customerId = validCustomerId,
                status = "PENDING",
                totalPrice = effectiveGoatPrice,
                depositPaid = 0.0,
                customerNotes = notes.trim(),
                bookingDate = nowIso,
                holdExpiresAt = expiresIso,
                createdAt = nowIso,
                updatedAt = nowIso
            )

            // 5. Remote insert into Supabase bookings table
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].insert(bookingDto)

            // 6. Update goat status to RESERVED
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                    buildJsonObject { put("status", "RESERVED") }
                ) {
                    filter {
                        eq("id", validGoatId)
                    }
                }
            } catch (_: Exception) {}

            val newBooking = Booking(
                id = newBookingId,
                goatId = validGoatId,
                goatName = goatDto.name.ifBlank { "Goat #${validGoatId.take(6)}" },
                goatBreed = goatDto.breedName.ifBlank { "Certified Breed" },
                goatPhoto = resolvedPhoto,
                farmId = effectiveFarmId,
                farmName = "Partner Farm",
                customerId = validCustomerId,
                customerName = customerDto?.fullName ?: "Customer",
                customerPhone = customerDto?.phone ?: "",
                amount = effectiveGoatPrice,
                status = AvailabilityStatus.BOOKING_PENDING,
                bookingDate = nowMillis,
                reservationExpiryDate = nowMillis + (24 * 3600 * 1000L),
                notes = notes.trim()
            )

            Result.success(newBooking)
        } catch (e: Exception) {
            Log.e(TAG, "Error: createBooking failed: ${e.message}", e)
            val friendlyError = UserFriendlyErrorMapper.forBooking(e)
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    override suspend fun updateBookingStatus(
        bookingId: String,
        status: AvailabilityStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val validBookingId = ensureValidUuid(bookingId)

        if (!SupabaseConfig.isConfigured) {
            val index = localBookings.indexOfFirst { it.id == bookingId || it.id == validBookingId }
            if (index >= 0) {
                val old = localBookings[index]
                localBookings[index] = old.copy(status = status)
            }
            return@withContext Result.success(Unit)
        }

        try {
            val dbStatus = when (status) {
                AvailabilityStatus.CONFIRMED -> "CONFIRMED"
                AvailabilityStatus.COMPLETED -> "COMPLETED"
                AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED -> "CANCELLED"
                AvailabilityStatus.RESERVED -> "RESERVED"
                else -> "PENDING"
            }

            val nowStr = currentIsoTimestamp()
            val updatePayload = buildJsonObject {
                put("status", dbStatus)
                when (dbStatus) {
                    "CONFIRMED" -> put("confirmed_at", nowStr)
                    "COMPLETED" -> put("completed_at", nowStr)
                    "CANCELLED" -> put("cancelled_at", nowStr)
                }
            }

            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].update(updatePayload) {
                filter {
                    eq("id", validBookingId)
                }
            }

            // Sync corresponding goat status
            try {
                val bookingDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                    .select {
                        filter {
                            eq("id", validBookingId)
                        }
                    }.decodeSingleOrNull<BookingDto>()

                if (bookingDto != null && bookingDto.goatId.isNotBlank()) {
                    val goatStatus = when (dbStatus) {
                        "COMPLETED" -> "COMPLETED"
                        "CONFIRMED" -> "CONFIRMED"
                        "RESERVED", "PENDING" -> "RESERVED"
                        "CANCELLED" -> "AVAILABLE"
                        else -> "AVAILABLE"
                    }
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                        buildJsonObject { put("status", goatStatus) }
                    ) {
                        filter {
                            eq("id", bookingDto.goatId)
                        }
                    }
                }
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Error: updateBookingStatus failed: ${e.message}", e)
            val friendlyError = UserFriendlyErrorMapper.toUserMessage(e, "Failed to update booking status.")
            Result.failure(IllegalStateException(friendlyError))
        }
    }

    private suspend fun enrichBookings(bookingDtos: List<BookingDto>): List<Booking> {
        if (bookingDtos.isEmpty()) return emptyList()

        val goatIds = bookingDtos.map { it.goatId }.distinct()
        val farmIds = bookingDtos.map { it.farmId }.distinct()
        val customerIds = bookingDtos.map { it.customerId }.distinct()

        val goatsMap = try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    filter {
                        isIn("id", goatIds)
                    }
                }.decodeList<GoatDto>().associateBy { it.id }
        } catch (_: Exception) {
            emptyMap()
        }

        val photosMap = GoatImageResolver.fetchGoatPhotosMap(goatIds)

        val farmsMap = try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select {
                    filter {
                        isIn("id", farmIds)
                    }
                }.decodeList<FarmDto>().associateBy { it.id }
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
            val goat = goatsMap[dto.goatId]
            val farm = farmsMap[dto.farmId]
            val profile = profilesMap[dto.customerId]
            val goatPhoto = photosMap[dto.goatId]?.firstOrNull()
                ?: GoatImageResolver.getCachedPhoto(dto.goatId)
                ?: ""

            dto.toDomain(
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
