package com.ammalfarm.adusanthai.data.repository

import android.content.Context
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.core.util.GoatImageResolver
import com.ammalfarm.adusanthai.core.util.PlatformPricingManager
import com.ammalfarm.adusanthai.data.dto.*
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

interface MarketplaceRepository {
    val goats: StateFlow<List<Goat>>
    val farms: StateFlow<List<Farm>>
    val bookings: StateFlow<List<Booking>>
    val wishlistedGoatIds: StateFlow<Set<String>>
    val notifications: StateFlow<List<AppNotification>>
    val allUsers: StateFlow<List<UserProfile>>
    val reports: StateFlow<List<ListingReport>>
    val farmReviews: StateFlow<Map<String, List<FarmReview>>>
    val isLoading: StateFlow<Boolean>
    val errorMessage: StateFlow<String?>

    suspend fun refreshAll(): Result<Unit>
    suspend fun refreshGoats(): Result<List<Goat>>
    suspend fun refreshFarms(): Result<List<Farm>>
    suspend fun refreshBookings(userId: String? = null, farmId: String? = null, isSuperAdmin: Boolean = false): Result<List<Booking>>
    suspend fun refreshWishlist(userId: String): Result<Set<String>>
    suspend fun refreshNotifications(userId: String): Result<List<AppNotification>>
    suspend fun refreshAllUsers(): Result<List<UserProfile>>
    suspend fun refreshReports(): Result<List<ListingReport>>
    suspend fun loadFarmReviews(farmId: String): Result<List<FarmReview>>

    suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean>

    suspend fun createBooking(
        goat: Goat,
        customer: UserProfile,
        preferredVisitDate: String,
        advanceAmount: Double,
        notes: String
    ): Result<Booking>

    suspend fun updateBookingStatus(
        bookingId: String,
        newStatus: BookingStatus,
        reason: String = ""
    ): Result<Unit>

    suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray> = emptyList(),
        isSuperAdmin: Boolean = false
    ): Result<Goat>

    suspend fun deleteGoat(goatId: String): Result<Unit>

    suspend fun updateGoatApproval(
        goatId: String,
        status: ApprovalStatus,
        rejectionReason: String? = null
    ): Result<Unit>

    suspend fun updateGoatAvailability(
        goatId: String,
        status: AvailabilityStatus
    ): Result<Unit>

    suspend fun updateFarmVerification(
        farmId: String,
        status: VerificationStatus,
        rejectionReason: String = ""
    ): Result<Unit>

    suspend fun updateFarmProfile(farm: Farm): Result<Farm>

    suspend fun increaseFarmListingLimit(farmId: String, additionalSlots: Int): Result<Unit>

    suspend fun markNotificationRead(notificationId: String): Result<Unit>

    suspend fun submitFarmReview(
        farmId: String,
        customer: UserProfile,
        rating: Int,
        comment: String
    ): Result<FarmReview>

    suspend fun submitListingReport(
        goatId: String,
        reporterId: String,
        reason: String
    ): Result<Unit>

    suspend fun resolveReport(reportId: String, status: String = "RESOLVED"): Result<Unit>

    suspend fun toggleUserSuspension(userId: String, suspend: Boolean): Result<Unit>

    suspend fun updatePlatformPricing(
        approvalFeeInr: Double,
        packFeeInr: Double,
        packSize: Int
    ): Result<Unit>
}

open class SupabaseMarketplaceRepositoryImpl(
    private val context: Context? = null
) : MarketplaceRepository {

    private val TAG = "MarketplaceRepo"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _goats = MutableStateFlow<List<Goat>>(emptyList())
    override val goats: StateFlow<List<Goat>> = _goats.asStateFlow()

    private val _farms = MutableStateFlow<List<Farm>>(emptyList())
    override val farms: StateFlow<List<Farm>> = _farms.asStateFlow()

    private val _bookings = MutableStateFlow<List<Booking>>(emptyList())
    override val bookings: StateFlow<List<Booking>> = _bookings.asStateFlow()

    private val _wishlistedGoatIds = MutableStateFlow<Set<String>>(emptySet())
    override val wishlistedGoatIds: StateFlow<Set<String>> = _wishlistedGoatIds.asStateFlow()

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    override val notifications: StateFlow<List<AppNotification>> = _notifications.asStateFlow()

    private val _allUsers = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allUsers: StateFlow<List<UserProfile>> = _allUsers.asStateFlow()

    private val _reports = MutableStateFlow<List<ListingReport>>(emptyList())
    override val reports: StateFlow<List<ListingReport>> = _reports.asStateFlow()

    private val _farmReviews = MutableStateFlow<Map<String, List<FarmReview>>>(emptyMap())
    override val farmReviews: StateFlow<Map<String, List<FarmReview>>> = _farmReviews.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    override val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    override suspend fun refreshAll(): Result<Unit> = withContext(Dispatchers.IO) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val farmsRes = refreshFarms()
            val goatsRes = refreshGoats()
            try {
                val settingsRows = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PLATFORM_SETTINGS]
                    .select()
                    .decodeList<PlatformSettingRowDto>()
                for (row in settingsRows) {
                    when (row.key) {
                        "farm_approval_price_inr" -> if (row.value > 0) PlatformPricingManager.farmApprovalFeeInr = row.value
                        "goat_slot_price_inr" -> if (row.value > 0) PlatformPricingManager.goatListingPackFeeInr = row.value
                        "default_initial_goat_slots" -> if (row.value > 0) PlatformPricingManager.goatListingPackSize = row.value.toInt()
                    }
                }
            } catch (_: Exception) {}

            val currentUserId = try { SupabaseModule.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
            if (!currentUserId.isNullOrBlank()) {
                refreshWishlist(currentUserId)
                refreshNotifications(currentUserId)
                refreshBookings(userId = currentUserId)
            }

            if (goatsRes.isFailure || (farmsRes.isFailure && _farms.value.isEmpty())) {
                val err = goatsRes.exceptionOrNull() ?: farmsRes.exceptionOrNull()
                val friendly = UserFriendlyErrorMapper.toUserMessage(err)
                _errorMessage.value = friendly
                return@withContext Result.failure(Exception(friendly, err))
            }
            _errorMessage.value = null
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            _errorMessage.value = friendly
            Result.failure(Exception(friendly, e))
        } finally {
            _isLoading.value = false
        }
    }

    override suspend fun refreshGoats(): Result<List<Goat>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            val err = IllegalStateException("Backend database is not configured.")
            _errorMessage.value = UserFriendlyErrorMapper.toUserMessage(err)
            return@withContext Result.failure(err)
        }
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<GoatDto>()

            val imagesByGoatId: Map<String, List<String>> = try {
                val imageDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES]
                    .select()
                    .decodeList<GoatImageDto>()
                imageDtos
                    .groupBy { it.goatId }
                    .mapValues { (_, list) ->
                        list.sortedWith(
                            compareByDescending<GoatImageDto> { it.isPrimary }
                                .thenBy { it.displayOrder }
                        ).map { it.imageUrl }
                    }
            } catch (imgErr: Exception) {
                Log.w(TAG, "Could not fetch goat_images: ${imgErr.message}")
                emptyMap()
            }

            if (_farms.value.isEmpty()) {
                try {
                    val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select()
                        .decodeList<FarmDto>()
                    val domainFarms = farmDtos.map { it.toDomain() }
                    domainFarms.forEach { FarmLocalCache.saveFarmProfile(it) }
                    _farms.value = domainFarms
                } catch (farmErr: Exception) {
                    Log.w(TAG, "Could not fetch farms during refreshGoats: ${farmErr.message}")
                }
            }

            val farmsById = _farms.value.associateBy { it.id }

            val domainList = dtos.map { dto ->
                val farm = farmsById[dto.farmId] ?: FarmLocalCache.getCachedFarm(dto.farmId)
                val photos = imagesByGoatId[dto.id].orEmpty()
                dto.toDomain(
                    resolvedPhotos = photos,
                    resolvedFarmName = farm?.name ?: "",
                    resolvedFarmLocation = farm?.location ?: "",
                    resolvedFarmContact = farm?.contactNumber ?: "",
                    resolvedFarmCode = farm?.farmCode ?: ""
                )
            }
            _goats.value = domainList
            _errorMessage.value = null
            Result.success(domainList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading goats: ${e.message}", e)
            _errorMessage.value = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(e)
        }
    }

    override suspend fun refreshFarms(): Result<List<Farm>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Backend database is not configured."))
        }
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select()
                .decodeList<FarmDto>()
            val usersById = _allUsers.value.associateBy { it.id }
            val domainList = dtos.map { dto ->
                val ownerName = usersById[dto.ownerId]?.name ?: dto.ownerName
                val farm = dto.toDomain(resolvedOwnerName = ownerName)
                FarmLocalCache.saveFarmProfile(farm)
                farm
            }
            _farms.value = domainList

            if (_goats.value.isNotEmpty() && domainList.isNotEmpty()) {
                val farmsById = domainList.associateBy { it.id }
                _goats.update { currentGoats ->
                    currentGoats.map { g ->
                        val f = farmsById[g.farmId]
                        if (f != null) {
                            g.copy(
                                farmName = g.farmName.ifBlank { f.name },
                                farmLocation = g.farmLocation.ifBlank { f.location },
                                farmContact = g.farmContact.ifBlank { f.contactNumber }
                            )
                        } else g
                    }
                }
            }
            Result.success(domainList)
        } catch (e: Exception) {
            Log.w(TAG, "Error loading farms: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun refreshBookings(
        userId: String?,
        farmId: String?,
        isSuperAdmin: Boolean
    ): Result<List<Booking>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                .select {
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<BookingDto>()

            val goatsById = _goats.value.associateBy { it.id }
            val farmsById = _farms.value.associateBy { it.id }
            val usersById = _allUsers.value.associateBy { it.id }

            val domainList = dtos.map { dto ->
                val goat = dto.goatId?.let { goatsById[it] }
                val farmIdKey = dto.farmId ?: ""
                val farm = farmsById[farmIdKey] ?: FarmLocalCache.getCachedFarm(farmIdKey)
                val customer = dto.customerId?.let { usersById[it] }
                val resolvedPhoto = GoatImageResolver.resolveBookingGoatPhoto(
                    goatId = dto.goatId,
                    explicitImageUrl = dto.goatImageUrl.takeIf { it.isNotBlank() },
                    goatPrimaryUrl = goat?.imageUrl,
                    goatPhotos = goat?.imageUrls ?: emptyList()
                )
                dto.toDomain(
                    resolvedGoatName = goat?.name ?: dto.goatName,
                    resolvedGoatBreed = goat?.breed ?: dto.goatBreed,
                    resolvedGoatPhoto = resolvedPhoto,
                    resolvedFarmName = farm?.name ?: dto.farmName,
                    resolvedFarmPhone = farm?.contactNumber ?: dto.farmPhone,
                    resolvedCustomerName = customer?.name ?: dto.customerName,
                    resolvedCustomerPhone = customer?.phone ?: dto.customerPhone,
                    resolvedCustomerEmail = customer?.email ?: dto.customerEmail
                )
            }
            val filtered = when {
                isSuperAdmin -> domainList
                !farmId.isNullOrBlank() && !userId.isNullOrBlank() ->
                    domainList.filter { it.farmId == farmId || it.customerId == userId }
                !farmId.isNullOrBlank() ->
                    domainList.filter { it.farmId == farmId }
                !userId.isNullOrBlank() ->
                    domainList.filter { it.customerId == userId }
                else -> domainList
            }
            _bookings.value = filtered
            Result.success(filtered)
        } catch (e: Exception) {
            Log.w(TAG, "Error loading bookings: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun refreshWishlist(userId: String): Result<Set<String>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || userId.isBlank()) return@withContext Result.success(emptySet())
        try {
            val cleanUserId = ensureValidUuid(userId)
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST]
                .select {
                    filter { eq("user_id", cleanUserId) }
                }
                .decodeList<WishlistDto>()
            val ids = dtos.map { it.goatId }.filter { it.isNotBlank() }.toSet()
            _wishlistedGoatIds.value = ids
            Result.success(ids)
        } catch (e: Exception) {
            Log.w(TAG, "Wishlist fetch notice: ${e.message}")
            Result.success(_wishlistedGoatIds.value)
        }
    }

    override suspend fun refreshNotifications(userId: String): Result<List<AppNotification>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || userId.isBlank()) return@withContext Result.success(emptyList())
        try {
            val cleanUserId = ensureValidUuid(userId)
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS]
                .select {
                    filter { eq("user_id", cleanUserId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<NotificationDto>()
            val list = dtos.map { it.toDomain() }
            _notifications.value = list
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Notifications fetch notice: ${e.message}")
            Result.success(_notifications.value)
        }
    }

    override suspend fun refreshAllUsers(): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .select()
                .decodeList<ProfileDto>()
            val list = dtos.map { it.toDomain() }
            _allUsers.value = list
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Profiles fetch notice: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun refreshReports(): Result<List<ListingReport>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(emptyList())
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS]
                .select {
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<ListingReportDto>()
            val list = dtos.map { it.toDomain() }
            _reports.value = list
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Reports fetch notice: ${e.message}")
            Result.success(_reports.value)
        }
    }

    override suspend fun loadFarmReviews(farmId: String): Result<List<FarmReview>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || farmId.isBlank()) return@withContext Result.success(emptyList())
        try {
            val cleanFarmId = ensureValidUuid(farmId)
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REVIEWS]
                .select {
                    filter { eq("farm_id", cleanFarmId) }
                    order("created_at", Order.DESCENDING)
                }
                .decodeList<ReviewDto>()
            val usersById = _allUsers.value.associateBy { it.id }
            val list = dtos.map { dto ->
                val customerName = usersById[dto.customerId]?.name ?: dto.customerName
                dto.toDomain(resolvedCustomerName = customerName)
            }
            _farmReviews.update { current -> current + (farmId to list) }
            Result.success(list)
        } catch (e: Exception) {
            Log.w(TAG, "Reviews fetch notice: ${e.message}")
            Result.success(_farmReviews.value[farmId] ?: emptyList())
        }
    }

    override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || goatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please sign in to save items to your wishlist."))
        }
        val cleanUserId = ensureValidUuid(userId)
        val cleanGoatId = ensureValidUuid(goatId)
        val currentlyWishlisted = _wishlistedGoatIds.value.contains(goatId) || _wishlistedGoatIds.value.contains(cleanGoatId)
        val updatedSet = if (currentlyWishlisted) {
            _wishlistedGoatIds.value - goatId - cleanGoatId
        } else {
            _wishlistedGoatIds.value + goatId
        }
        _wishlistedGoatIds.value = updatedSet

        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(!currentlyWishlisted)
        }

        try {
            if (currentlyWishlisted) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST].delete {
                    filter {
                        eq("user_id", cleanUserId)
                        eq("goat_id", cleanGoatId)
                    }
                }
            } else {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_WISHLIST].insert(
                    buildJsonObject {
                        put("user_id", cleanUserId)
                        put("goat_id", cleanGoatId)
                    }
                )
            }
            Result.success(!currentlyWishlisted)
        } catch (e: Exception) {
            Log.w(TAG, "Wishlist sync warning: ${e.message}")
            Result.success(!currentlyWishlisted)
        }
    }

    override suspend fun createBooking(
        goat: Goat,
        customer: UserProfile,
        preferredVisitDate: String,
        advanceAmount: Double,
        notes: String
    ): Result<Booking> = withContext(Dispatchers.IO) {
        if (goat.approvalStatus != ApprovalStatus.APPROVED) {
            return@withContext Result.failure(IllegalStateException("Only approved goat listings can be booked."))
        }
        if (goat.availabilityStatus != AvailabilityStatus.AVAILABLE || !goat.isActive) {
            return@withContext Result.failure(IllegalStateException("This goat is currently not available for booking."))
        }
        if (preferredVisitDate.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please select or enter a preferred farm visit date."))
        }

        val combinedNotes = buildString {
            if (preferredVisitDate.isNotBlank()) {
                append("Preferred Visit: ").append(preferredVisitDate.trim())
            }
            if (notes.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(notes.trim())
            }
        }

        try {
            var createdBookingDto: BookingDto? = null
            if (SupabaseConfig.isConfigured) {
                createdBookingDto = SupabaseModule.client.postgrest.rpc(
                    "create_booking_hold",
                    buildJsonObject {
                        put("p_goat_id", ensureValidUuid(goat.id))
                        put("p_customer_notes", combinedNotes)
                    }
                ).decodeAs<BookingDto>()
            }

            val newBooking = if (createdBookingDto != null) {
                createdBookingDto.toDomain(
                    resolvedGoatName = goat.name,
                    resolvedGoatBreed = goat.breed,
                    resolvedGoatPhoto = goat.primaryPhoto,
                    resolvedFarmName = goat.farmName,
                    resolvedFarmPhone = goat.farmContact,
                    resolvedCustomerName = customer.name,
                    resolvedCustomerPhone = customer.phone,
                    resolvedCustomerEmail = customer.email
                ).copy(
                    preferredVisitDate = preferredVisitDate.trim()
                )
            } else {
                val bookingId = UUID.randomUUID().toString()
                val bookingCode = "BK-${(1000..9999).random()}"
                Booking(
                    id = bookingId,
                    bookingCode = bookingCode,
                    goatId = goat.id,
                    goatName = goat.name,
                    goatBreed = goat.breed,
                    goatImageUrl = goat.primaryPhoto,
                    goatPrice = goat.effectivePrice,
                    advanceAmount = advanceAmount,
                    customerId = ensureValidUuid(customer.id),
                    customerName = customer.name,
                    customerPhone = customer.phone,
                    customerEmail = customer.email,
                    farmId = ensureValidUuid(goat.farmId),
                    farmName = goat.farmName,
                    farmPhone = goat.farmContact,
                    preferredVisitDate = preferredVisitDate.trim(),
                    notes = combinedNotes,
                    status = BookingStatus.PENDING,
                    paymentStatus = if (advanceAmount > 0) "ADVANCE_PENDING" else "PAY_AT_FARM",
                    createdAt = currentIsoTimestamp()
                )
            }

            _bookings.update { listOf(newBooking) + it }
            _goats.update { list ->
                list.map {
                    if (it.id == goat.id) it.copy(availabilityStatus = AvailabilityStatus.RESERVED) else it
                }
            }
            Result.success(newBooking)
        } catch (e: Exception) {
            Log.w(TAG, "Error creating booking: ${e.message}")
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateBookingStatus(
        bookingId: String,
        newStatus: BookingStatus,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val targetBooking = _bookings.value.find { it.id == bookingId }
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanBookingId = ensureValidUuid(bookingId)
                when (newStatus) {
                    BookingStatus.CONFIRMED, BookingStatus.APPROVED -> {
                        SupabaseModule.client.postgrest.rpc(
                            "confirm_booking",
                            buildJsonObject { put("p_booking_id", cleanBookingId) }
                        )
                    }
                    BookingStatus.COMPLETED -> {
                        SupabaseModule.client.postgrest.rpc(
                            "complete_booking",
                            buildJsonObject { put("p_booking_id", cleanBookingId) }
                        )
                    }
                    BookingStatus.CANCELLED, BookingStatus.REJECTED, BookingStatus.EXPIRED -> {
                        SupabaseModule.client.postgrest.rpc(
                            "cancel_booking",
                            buildJsonObject {
                                put("p_booking_id", cleanBookingId)
                                put("p_reason", reason.ifBlank { "Booking ${newStatus.name.lowercase()}" })
                            }
                        )
                    }
                    BookingStatus.PENDING -> {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].update(
                            buildJsonObject {
                                put("status", "RESERVED")
                                put("updated_at", currentIsoTimestamp())
                            }
                        ) {
                            filter { eq("id", cleanBookingId) }
                        }
                    }
                }
            }

            val nextGoatAvail = when (newStatus) {
                BookingStatus.COMPLETED -> AvailabilityStatus.SOLD
                BookingStatus.CANCELLED, BookingStatus.REJECTED, BookingStatus.EXPIRED -> AvailabilityStatus.AVAILABLE
                BookingStatus.CONFIRMED, BookingStatus.APPROVED, BookingStatus.PENDING -> AvailabilityStatus.RESERVED
            }

            _bookings.update { list ->
                list.map { if (it.id == bookingId) it.copy(status = newStatus) else it }
            }
            if (targetBooking != null && targetBooking.goatId.isNotBlank()) {
                _goats.update { list ->
                    list.map {
                        if (it.id == targetBooking.goatId) it.copy(availabilityStatus = nextGoatAvail) else it
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray>,
        isSuperAdmin: Boolean
    ): Result<Goat> = withContext(Dispatchers.IO) {
        if (goat.name.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter the goat's name or title."))
        }
        if (goat.breed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please select the goat's breed."))
        }
        if (goat.price <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid price greater than ₹0."))
        }
        if (goat.weightKg <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid weight in kg."))
        }

        val isExisting = _goats.value.any { it.id == goat.id }
        val farm = _farms.value.find { it.id == goat.farmId }

        if (!isExisting && !isSuperAdmin && farm != null && !farm.isAmmalOwnFarm) {
            if (!farm.verificationStatus.isApprovedOrVerified) {
                return@withContext Result.failure(
                    IllegalStateException("Your farm must be verified by Super Admin before publishing goat listings.")
                )
            }
            val currentFarmGoatCount = _goats.value.count { it.farmId == farm.id && it.isActive }
            if (currentFarmGoatCount >= farm.goatListingLimit && !farm.hasRemainingListingQuota) {
                return@withContext Result.failure(
                    IllegalStateException("You have reached your goat listing limit (${farm.goatListingLimit}). Request additional slots from your Farm Dashboard.")
                )
            }
        }

        try {
            val uploadedUrls = mutableListOf<String>()
            if (SupabaseConfig.isConfigured && imageBytesList.isNotEmpty()) {
                val bucket = SupabaseModule.storage.from(SupabaseConfig.BUCKET_GOAT_IMAGES)
                imageBytesList.forEachIndexed { index, bytes ->
                    try {
                        val path = "goats/${goat.farmId.ifBlank { "default" }}/${goat.id}_${System.currentTimeMillis()}_$index.jpg"
                        bucket.upload(path, bytes) { upsert = true }
                        uploadedUrls.add(SupabaseConfig.resolveStorageUrl(path, SupabaseConfig.BUCKET_GOAT_IMAGES))
                    } catch (uploadErr: Exception) {
                        Log.w(TAG, "Image upload warning: ${uploadErr.message}")
                    }
                }
            }

            val mergedImages = (uploadedUrls + goat.imageUrls + listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() }))
                .distinct()
                .filter { it.isNotBlank() }

            val primaryImage = mergedImages.firstOrNull() ?: ""
            val autoApprove = isSuperAdmin || (farm?.isAmmalOwnFarm == true)
            val resolvedApproval = if (isExisting) {
                goat.approvalStatus
            } else if (autoApprove) {
                ApprovalStatus.APPROVED
            } else {
                ApprovalStatus.PENDING
            }

            val cleanGoatId = ensureValidUuid(goat.id.ifBlank { UUID.randomUUID().toString() })
            val cleanFarmId = ensureValidUuid(goat.farmId.ifBlank { SEED_AMMAL_FARM_UUID })
            val finalPrice = if (goat.discountPercentage > 0) {
                goat.price * (1.0 - (goat.discountPercentage / 100.0))
            } else {
                goat.price
            }

            val updatedGoat = goat.copy(
                id = cleanGoatId,
                goatCode = goat.goatCode,
                tagId = goat.tagId.ifBlank { "TAG-${(1000..9999).random()}" },
                farmId = cleanFarmId,
                farmName = goat.farmName.ifBlank { farm?.name ?: "Verified Farm" },
                farmLocation = goat.farmLocation.ifBlank { farm?.location ?: "Tamil Nadu" },
                farmContact = goat.farmContact.ifBlank { farm?.contactNumber ?: "" },
                finalPrice = finalPrice,
                imageUrl = primaryImage,
                imageUrls = mergedImages,
                approvalStatus = resolvedApproval,
                isVerified = resolvedApproval == ApprovalStatus.APPROVED
            )

            if (SupabaseConfig.isConfigured) {
                val dbGender = when (updatedGoat.gender.trim().uppercase()) {
                    "FEMALE" -> "FEMALE"
                    "CASTRATED" -> "CASTRATED"
                    else -> "MALE"
                }
                val dbPurpose = when (updatedGoat.purpose.trim().uppercase()) {
                    "MEAT" -> "MEAT"
                    "MILK", "DAIRY" -> "MILK"
                    "SHOW" -> "SHOW"
                    "PET" -> "PET"
                    else -> "BREEDING"
                }
                val dbStatus = when {
                    !updatedGoat.isActive -> "INACTIVE"
                    updatedGoat.availabilityStatus == AvailabilityStatus.UNAVAILABLE -> "INACTIVE"
                    updatedGoat.availabilityStatus == AvailabilityStatus.SOLD -> "SOLD"
                    updatedGoat.availabilityStatus == AvailabilityStatus.RESERVED -> "RESERVED"
                    else -> "AVAILABLE"
                }

                val payload = buildJsonObject {
                    put("id", cleanGoatId)
                    put("farm_id", cleanFarmId)
                    put("name", updatedGoat.name.trim())
                    put("breed_name", updatedGoat.breed.trim())
                    put("gender", dbGender)
                    put("age_months", updatedGoat.ageMonths.coerceAtLeast(1))
                    put("weight_kg", updatedGoat.weightKg)
                    put("purpose", dbPurpose)
                    put("price", updatedGoat.price)
                    put("discount_percentage", updatedGoat.discountPercentage.coerceIn(0.0, 99.0))
                    put("status", dbStatus)
                    put("description", updatedGoat.description.trim())
                    put("vaccination_status", updatedGoat.healthNotes.ifBlank { if (updatedGoat.isVaccinated) "Fully Vaccinated" else "Pending Vaccination" })
                    put("is_approved_by_admin", updatedGoat.approvalStatus == ApprovalStatus.APPROVED)
                }

                val savedGoatDto = if (isExisting) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .update(payload) {
                            filter { eq("id", cleanGoatId) }
                            select()
                        }
                        .decodeSingleOrNull<GoatDto>()
                } else {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .insert(payload) {
                            select()
                        }
                        .decodeSingleOrNull<GoatDto>()
                }

                if (mergedImages.isNotEmpty()) {
                    try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES].delete {
                            filter { eq("goat_id", cleanGoatId) }
                        }
                        mergedImages.forEachIndexed { idx, imgUrl ->
                            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES].insert(
                                buildJsonObject {
                                    put("goat_id", cleanGoatId)
                                    put("image_url", imgUrl)
                                    put("display_order", idx)
                                    put("is_primary", idx == 0)
                                }
                            )
                        }
                    } catch (imgDbErr: Exception) {
                        Log.w(TAG, "goat_images insert notice: ${imgDbErr.message}")
                    }
                }

                val finalGoat = savedGoatDto?.toDomain(
                    resolvedPhotos = mergedImages,
                    resolvedFarmName = updatedGoat.farmName,
                    resolvedFarmLocation = updatedGoat.farmLocation,
                    resolvedFarmContact = updatedGoat.farmContact
                ) ?: updatedGoat.copy(
                    goatCode = updatedGoat.goatCode.ifBlank { "GOAT-${cleanGoatId.take(4).uppercase()}" }
                )

                _goats.update { list ->
                    if (isExisting) {
                        list.map { if (it.id == finalGoat.id) finalGoat else it }
                    } else {
                        listOf(finalGoat) + list
                    }
                }
                return@withContext Result.success(finalGoat)
            }

            _goats.update { list ->
                if (isExisting) {
                    list.map { if (it.id == updatedGoat.id) updatedGoat else it }
                } else {
                    listOf(updatedGoat) + list
                }
            }
            Result.success(updatedGoat)
        } catch (e: Exception) {
            Log.w(TAG, "Error saving goat: ${e.message}")
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun deleteGoat(goatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanGoatId = ensureValidUuid(goatId)
                try {
                    SupabaseModule.client.postgrest.rpc(
                        "delete_goat_listing",
                        buildJsonObject { put("p_goat_id", cleanGoatId) }
                    )
                } catch (_: Exception) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].delete {
                        filter { eq("id", cleanGoatId) }
                    }
                }
            }
            _goats.update { list -> list.filterNot { it.id == goatId } }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateGoatApproval(
        goatId: String,
        status: ApprovalStatus,
        rejectionReason: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanGoatId = ensureValidUuid(goatId)
                try {
                    SupabaseModule.client.postgrest.rpc(
                        "moderate_goat_listing",
                        buildJsonObject {
                            put("p_goat_id", cleanGoatId)
                            put("p_approve", status == ApprovalStatus.APPROVED)
                            put("p_rejection_reason", rejectionReason ?: "")
                        }
                    )
                } catch (_: Exception) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                        buildJsonObject {
                            put("is_approved_by_admin", status == ApprovalStatus.APPROVED)
                            if (status == ApprovalStatus.REJECTED) {
                                put("status", "INACTIVE")
                            } else if (status == ApprovalStatus.APPROVED) {
                                put("status", "AVAILABLE")
                            }
                            put("updated_at", currentIsoTimestamp())
                        }
                    ) {
                        filter { eq("id", cleanGoatId) }
                    }
                }
            }
            _goats.update { list ->
                list.map {
                    if (it.id == goatId) {
                        it.copy(
                            approvalStatus = status,
                            isVerified = status == ApprovalStatus.APPROVED,
                            isActive = status != ApprovalStatus.REJECTED,
                            rejectionReason = rejectionReason
                        )
                    } else it
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateGoatAvailability(
        goatId: String,
        status: AvailabilityStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (SupabaseConfig.isConfigured) {
                val dbStatus = when (status) {
                    AvailabilityStatus.UNAVAILABLE -> "INACTIVE"
                    AvailabilityStatus.AVAILABLE -> "AVAILABLE"
                    AvailabilityStatus.RESERVED -> "RESERVED"
                    AvailabilityStatus.SOLD -> "SOLD"
                }
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                    buildJsonObject {
                        put("status", dbStatus)
                        put("updated_at", currentIsoTimestamp())
                    }
                ) {
                    filter { eq("id", ensureValidUuid(goatId)) }
                }
            }
            _goats.update { list ->
                list.map {
                    if (it.id == goatId) {
                        it.copy(
                            availabilityStatus = status,
                            isActive = status != AvailabilityStatus.UNAVAILABLE
                        )
                    } else it
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateFarmVerification(
        farmId: String,
        status: VerificationStatus,
        rejectionReason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanFarmId = ensureValidUuid(farmId)
                try {
                    if (status.isApprovedOrVerified) {
                        SupabaseModule.client.postgrest.rpc(
                            "approve_partner_farm_with_manual_payment",
                            buildJsonObject {
                                put("p_farm_id", cleanFarmId)
                                put("p_amount", PlatformPricingManager.farmApprovalFeeInr)
                                put("p_payment_reference", "ADMIN-VERIFIED-${System.currentTimeMillis()}")
                                put("p_receipt_number", "RCPT-${(10000..99999).random()}")
                                put("p_notes", "Verified by Super Admin")
                            }
                        )
                    } else {
                        val dbStatus = when (status) {
                            VerificationStatus.REJECTED -> "REJECTED"
                            VerificationStatus.SUSPENDED -> "SUSPENDED"
                            else -> "PENDING"
                        }
                        SupabaseModule.client.postgrest.rpc(
                            "update_farm_verification_status",
                            buildJsonObject {
                                put("p_farm_id", cleanFarmId)
                                put("p_status", dbStatus)
                            }
                        )
                    }
                } catch (_: Exception) {
                    val dbStatus = when {
                        status.isApprovedOrVerified -> "APPROVED"
                        status == VerificationStatus.REJECTED -> "REJECTED"
                        status == VerificationStatus.SUSPENDED -> "SUSPENDED"
                        else -> "PENDING"
                    }
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                        buildJsonObject {
                            put("status", dbStatus)
                            put("updated_at", currentIsoTimestamp())
                        }
                    ) {
                        filter { eq("id", cleanFarmId) }
                    }
                }
            }
            _farms.update { list ->
                list.map {
                    if (it.id == farmId) {
                        val updated = it.copy(
                            verificationStatus = status,
                            approvalStatus = if (status.isApprovedOrVerified) ApprovalStatus.APPROVED else ApprovalStatus.PENDING,
                            approvalFeePaid = status.isApprovedOrVerified || it.approvalFeePaid
                        )
                        FarmLocalCache.saveFarmProfile(updated)
                        updated
                    } else it
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateFarmProfile(farm: Farm): Result<Farm> = withContext(Dispatchers.IO) {
        if (farm.name.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Farm name cannot be empty."))
        }
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanFarmId = ensureValidUuid(farm.id)
                val resolvedDist = farm.district.ifBlank {
                    farm.location.substringBefore(",").trim().ifBlank { "Madurai" }
                }
                val resolvedState = farm.state.ifBlank { "Tamil Nadu" }
                val resolvedAddress = farm.location.ifBlank { "$resolvedDist, $resolvedState" }
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                    buildJsonObject {
                        put("name", farm.name.trim())
                        put("description", farm.description.trim())
                        put("location_district", resolvedDist)
                        put("location_state", resolvedState)
                        put("address", resolvedAddress)
                        put("contact_phone", farm.contactNumber.trim())
                        if (farm.email.isNotBlank()) {
                            put("contact_email", farm.email.trim())
                        }
                        if (farm.logoUrl.isNotBlank()) {
                            put("logo_url", farm.logoUrl)
                        }
                        if (!farm.bannerUrl.isNullOrBlank()) {
                            put("banner_url", farm.bannerUrl)
                        }
                        put("updated_at", currentIsoTimestamp())
                    }
                ) {
                    filter { eq("id", cleanFarmId) }
                }
            }
            FarmLocalCache.saveFarmProfile(farm)
            _farms.update { list ->
                val exists = list.any { it.id == farm.id }
                if (exists) list.map { if (it.id == farm.id) farm else it } else list + farm
            }
            Result.success(farm)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun increaseFarmListingLimit(farmId: String, additionalSlots: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val target = _farms.value.find { it.id == farmId }
            ?: return@withContext Result.failure(IllegalArgumentException("Farm not found."))
        val newLimit = (target.goatListingLimit + additionalSlots).coerceAtLeast(2)
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanFarmId = ensureValidUuid(farmId)
                try {
                    SupabaseModule.client.postgrest.rpc(
                        "increase_farm_goat_quota_with_manual_payment",
                        buildJsonObject {
                            put("p_farm_id", cleanFarmId)
                            put("p_slots_to_add", additionalSlots)
                            put("p_amount", PlatformPricingManager.goatListingPackFeeInr)
                            put("p_payment_reference", "QUOTA-${System.currentTimeMillis()}")
                            put("p_receipt_number", "RCPT-${(10000..99999).random()}")
                            put("p_notes", "Added $additionalSlots listing slots")
                        }
                    )
                } catch (_: Exception) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                        buildJsonObject {
                            put("goat_listing_limit", newLimit)
                            put("updated_at", currentIsoTimestamp())
                        }
                    ) {
                        filter { eq("id", cleanFarmId) }
                    }
                }
            }
            _farms.update { list ->
                list.map {
                    if (it.id == farmId) {
                        val updated = it.copy(goatListingLimit = newLimit)
                        FarmLocalCache.saveFarmProfile(updated)
                        updated
                    } else it
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun markNotificationRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        _notifications.update { list ->
            list.map { if (it.id == notificationId) it.copy(isRead = true) else it }
        }
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(Unit)
        try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].update(
                buildJsonObject { put("is_read", true) }
            ) {
                filter { eq("id", ensureValidUuid(notificationId)) }
            }
            Result.success(Unit)
        } catch (_: Exception) {
            Result.success(Unit)
        }
    }

    override suspend fun submitFarmReview(
        farmId: String,
        customer: UserProfile,
        rating: Int,
        comment: String
    ): Result<FarmReview> = withContext(Dispatchers.IO) {
        if (comment.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter your review comment."))
        }
        val coercedRating = rating.coerceIn(1, 5)
        val review = FarmReview(
            id = UUID.randomUUID().toString(),
            farmId = ensureValidUuid(farmId),
            customerId = ensureValidUuid(customer.id),
            customerName = customer.name.ifBlank { "Verified Customer" },
            rating = coercedRating,
            comment = comment.trim(),
            createdAt = currentIsoTimestamp()
        )
        try {
            if (SupabaseConfig.isConfigured) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REVIEWS].insert(
                    buildJsonObject {
                        put("farm_id", review.farmId)
                        put("customer_id", review.customerId)
                        put("rating", review.rating)
                        put("comment", review.comment)
                        put("is_verified_purchase", true)
                        put("is_approved", true)
                    }
                )
            }
            _farmReviews.update { current ->
                val existing = current[farmId] ?: emptyList()
                current + (farmId to (listOf(review) + existing))
            }
            Result.success(review)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun submitListingReport(
        goatId: String,
        reporterId: String,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (reason.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please describe the reason for reporting this listing."))
        }
        val cleanGoatId = ensureValidUuid(goatId)
        val cleanReporterId = ensureValidUuid(reporterId)
        val report = ListingReport(
            id = UUID.randomUUID().toString(),
            goatId = cleanGoatId,
            reporterId = cleanReporterId,
            reason = reason.trim(),
            status = "OPEN",
            createdAt = currentIsoTimestamp()
        )
        try {
            if (SupabaseConfig.isConfigured) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS].insert(
                    buildJsonObject {
                        put("reporter_id", cleanReporterId)
                        put("target_type", "GOAT")
                        put("target_id", cleanGoatId)
                        put("reason", reason.trim())
                        put("status", "PENDING")
                    }
                )
            }
            _reports.update { listOf(report) + it }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun resolveReport(reportId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        _reports.update { list ->
            list.map { if (it.id == reportId) it.copy(status = status) else it }
        }
        if (!SupabaseConfig.isConfigured) return@withContext Result.success(Unit)
        try {
            val dbStatus = when (status.uppercase()) {
                "RESOLVED", "REVIEWED", "DISMISSED", "PENDING" -> status.uppercase()
                "OPEN" -> "PENDING"
                else -> "RESOLVED"
            }
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS].update(
                buildJsonObject {
                    put("status", dbStatus)
                    put("updated_at", currentIsoTimestamp())
                }
            ) {
                filter { eq("id", ensureValidUuid(reportId)) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun toggleUserSuspension(userId: String, suspend: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanUserId = ensureValidUuid(userId)
                try {
                    SupabaseModule.client.postgrest.rpc(
                        "admin_set_user_suspension",
                        buildJsonObject {
                            put("p_target_user_id", cleanUserId)
                            put("p_suspend", suspend)
                        }
                    )
                } catch (_: Exception) {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].update(
                        buildJsonObject {
                            put("is_suspended", suspend)
                            put("updated_at", currentIsoTimestamp())
                        }
                    ) {
                        filter { eq("id", cleanUserId) }
                    }
                }
            }
            _allUsers.update { list ->
                list.map { if (it.id == userId) it.copy(isSuspended = suspend) else it }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updatePlatformPricing(
        approvalFeeInr: Double,
        packFeeInr: Double,
        packSize: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        PlatformPricingManager.farmApprovalFeeInr = approvalFeeInr.coerceAtLeast(0.0)
        PlatformPricingManager.goatListingPackFeeInr = packFeeInr.coerceAtLeast(0.0)
        PlatformPricingManager.goatListingPackSize = packSize.coerceAtLeast(1)
        if (SupabaseConfig.isConfigured) {
            try {
                SupabaseModule.client.postgrest.rpc(
                    "update_platform_pricing_settings",
                    buildJsonObject {
                        put("p_farm_approval_price", PlatformPricingManager.farmApprovalFeeInr)
                        put("p_goat_slot_price", PlatformPricingManager.goatListingPackFeeInr)
                        put("p_default_initial_slots", PlatformPricingManager.goatListingPackSize)
                    }
                )
            } catch (_: Exception) {}
        }
        Result.success(Unit)
    }
}
