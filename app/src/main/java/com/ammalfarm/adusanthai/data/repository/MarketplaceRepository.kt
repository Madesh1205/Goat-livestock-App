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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray> = emptyList(),
        isSuperAdmin: Boolean = false,
        actorUserId: String? = null,
        actorFarmId: String? = null
    ): Result<Goat> = addOrUpdateGoat(goat, imageBytesList, isSuperAdmin)

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

    suspend fun updateFarmProfile(
        farm: Farm,
        logoBytes: ByteArray? = null,
        bannerBytes: ByteArray? = null
    ): Result<Farm>

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
    private val context: Context? = null,
    initialFarms: List<Farm> = emptyList(),
    initialGoats: List<Goat> = emptyList()
) : MarketplaceRepository {

    private val TAG = "MarketplaceRepo"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val rpcJson = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val _goats = MutableStateFlow<List<Goat>>(initialGoats)
    override val goats: StateFlow<List<Goat>> = _goats.asStateFlow()

    private val _farms = MutableStateFlow<List<Farm>>(initialFarms)
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

            val cleanUserId = userId?.takeIf { it.isNotBlank() }?.let { ensureValidUuid(it) }
            val cleanFarmId = farmId?.takeIf { it.isNotBlank() }?.let { ensureValidUuid(it) }

            val domainList = dtos.map { dto ->
                val goat = dto.goatId?.let { goatsById[it] }
                val farmIdKey = dto.farmId?.takeIf { it.isNotBlank() } ?: goat?.farmId ?: ""
                val farm = farmsById[farmIdKey] ?: FarmLocalCache.getCachedFarm(farmIdKey)
                val resolvedCustId = dto.customerId?.takeIf { it.isNotBlank() } ?: ""
                val customer = resolvedCustId.let { usersById[it] }
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
                ).let { b ->
                    if (b.goatPrice <= 0.0 && goat != null) b.copy(goatPrice = goat.effectivePrice) else b
                }
            }
            val filtered = when {
                isSuperAdmin -> domainList
                !farmId.isNullOrBlank() && !userId.isNullOrBlank() ->
                    domainList.filter {
                        it.farmId.equals(farmId, ignoreCase = true) ||
                        (cleanFarmId != null && it.farmId.equals(cleanFarmId, ignoreCase = true)) ||
                        it.customerId.equals(userId, ignoreCase = true) ||
                        (cleanUserId != null && it.customerId.equals(cleanUserId, ignoreCase = true))
                    }
                !farmId.isNullOrBlank() ->
                    domainList.filter {
                        it.farmId.equals(farmId, ignoreCase = true) ||
                        (cleanFarmId != null && it.farmId.equals(cleanFarmId, ignoreCase = true))
                    }
                !userId.isNullOrBlank() ->
                    domainList.filter {
                        it.customerId.equals(userId, ignoreCase = true) ||
                        (cleanUserId != null && it.customerId.equals(cleanUserId, ignoreCase = true))
                    }
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
        if (customer.id.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Your session has expired. Please sign in again."))
        }
        if (goat.approvalStatus != ApprovalStatus.APPROVED) {
            return@withContext Result.failure(IllegalStateException("Only approved goat listings can be booked."))
        }
        if (goat.availabilityStatus != AvailabilityStatus.AVAILABLE || !goat.isActive) {
            return@withContext Result.failure(IllegalStateException("This goat is no longer available. Please choose another goat."))
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

        val cleanGoatId = ensureValidUuid(goat.id)
        val cleanCustomerId = ensureValidUuid(customer.id)
        val cleanFarmId = ensureValidUuid(goat.farmId.ifBlank { SEED_AMMAL_FARM_UUID })

        try {
            var createdBookingDto: BookingDto? = null
            if (SupabaseConfig.isConfigured) {
                try {
                    createdBookingDto = SupabaseModule.client.postgrest.rpc(
                        "create_booking_hold",
                        buildJsonObject {
                            put("p_goat_id", cleanGoatId)
                            put("p_customer_notes", combinedNotes)
                        }
                    ).decodeAs<BookingDto>()
                } catch (rpcErr: Exception) {
                    val msg = (rpcErr.message ?: "").lowercase()
                    val isMissingRpc = msg.contains("pgrst202") ||
                        (msg.contains("function") && msg.contains("create_booking_hold") && msg.contains("does not exist"))
                    if (!isMissingRpc) {
                        throw rpcErr
                    }
                    // Server-side verification before fallback insert if RPC is not deployed
                    val remoteGoat = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select { filter { eq("id", cleanGoatId) } }
                        .decodeSingleOrNull<GoatDto>()
                        ?: throw IllegalStateException("Goat listing not found.")
                    val remoteStatus = (remoteGoat.status ?: "AVAILABLE").uppercase()
                    if (remoteStatus != "AVAILABLE" || !remoteGoat.isApprovedByAdmin) {
                        throw IllegalStateException("This goat has already been reserved by another customer.")
                    }
                    val existingActiveBookings = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                        .select {
                            filter {
                                eq("goat_id", cleanGoatId)
                            }
                        }
                        .decodeList<BookingDto>()
                        .any {
                            val st = (it.status ?: "").uppercase()
                            st == "RESERVED" || st == "PENDING" || st == "CONFIRMED" || st == "APPROVED"
                        }
                    if (existingActiveBookings) {
                        throw IllegalStateException("This goat has already been reserved by another customer.")
                    }
                    createdBookingDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                        .insert(
                            buildJsonObject {
                                put("goat_id", cleanGoatId)
                                put("farm_id", cleanFarmId)
                                put("customer_id", cleanCustomerId)
                                put("status", "RESERVED")
                                put("total_price", goat.effectivePrice)
                                put("deposit_paid", advanceAmount)
                                put("customer_notes", combinedNotes)
                            }
                        ) {
                            select()
                        }
                        .decodeSingleOrNull<BookingDto>()

                    try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(
                            buildJsonObject {
                                put("status", "RESERVED")
                                put("updated_at", currentIsoTimestamp())
                            }
                        ) {
                            filter { eq("id", cleanGoatId) }
                        }
                    } catch (_: Exception) {}
                }
            }

            val newBooking = if (createdBookingDto != null) {
                val domainBooking = createdBookingDto.toDomain(
                    resolvedGoatName = goat.name,
                    resolvedGoatBreed = goat.breed,
                    resolvedGoatPhoto = goat.primaryPhoto,
                    resolvedFarmName = goat.farmName,
                    resolvedFarmPhone = goat.farmContact,
                    resolvedCustomerName = customer.name,
                    resolvedCustomerPhone = customer.phone,
                    resolvedCustomerEmail = customer.email
                )
                domainBooking.copy(
                    goatId = domainBooking.goatId.ifBlank { cleanGoatId },
                    farmId = domainBooking.farmId.ifBlank { cleanFarmId },
                    customerId = domainBooking.customerId.ifBlank { customer.id },
                    goatPrice = if (domainBooking.goatPrice > 0.0) domainBooking.goatPrice else goat.effectivePrice,
                    advanceAmount = if (domainBooking.advanceAmount > 0.0) domainBooking.advanceAmount else advanceAmount,
                    preferredVisitDate = preferredVisitDate.trim(),
                    notes = domainBooking.notes.ifBlank { combinedNotes },
                    createdAt = domainBooking.createdAt.ifBlank { currentIsoTimestamp() }
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
                    customerId = customer.id,
                    customerName = customer.name,
                    customerPhone = customer.phone,
                    customerEmail = customer.email,
                    farmId = goat.farmId.ifBlank { cleanFarmId },
                    farmName = goat.farmName,
                    farmPhone = goat.farmContact,
                    preferredVisitDate = preferredVisitDate.trim(),
                    notes = combinedNotes,
                    status = BookingStatus.PENDING,
                    paymentStatus = if (advanceAmount > 0) "ADVANCE_PENDING" else "PAY_AT_FARM",
                    createdAt = currentIsoTimestamp()
                )
            }

            _bookings.update { current ->
                listOf(newBooking) + current.filterNot { it.id == newBooking.id }
            }
            _goats.update { list ->
                list.map {
                    if (it.id == goat.id || it.id == cleanGoatId) {
                        it.copy(availabilityStatus = AvailabilityStatus.RESERVED)
                    } else it
                }
            }

            if (SupabaseConfig.isConfigured) {
                try {
                    refreshGoats()
                    val refreshed = refreshBookings(
                        userId = customer.id,
                        farmId = customer.farmId,
                        isSuperAdmin = customer.role == UserRole.SUPER_ADMIN
                    ).getOrNull()
                    if (refreshed != null && refreshed.none { it.id == newBooking.id }) {
                        _bookings.update { current ->
                            listOf(newBooking) + current.filterNot { it.id == newBooking.id }
                        }
                    }
                } catch (_: Exception) {}
            }

            Result.success(newBooking)
        } catch (e: Exception) {
            Log.w(TAG, "Error creating booking: ${e.message}")
            val friendly = UserFriendlyErrorMapper.forBooking(e)
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
                    else -> {
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
                BookingStatus.COMPLETED, AvailabilityStatus.SOLD -> AvailabilityStatus.SOLD
                BookingStatus.CANCELLED, BookingStatus.REJECTED, BookingStatus.EXPIRED, AvailabilityStatus.AVAILABLE -> AvailabilityStatus.AVAILABLE
                else -> AvailabilityStatus.RESERVED
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
            if (SupabaseConfig.isConfigured) {
                try {
                    refreshGoats()
                } catch (_: Exception) {}
            }
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.forBooking(e)
            Result.failure(Exception(friendly, e))
        }
    }

    protected open val isRemoteBackendEnabled: Boolean
        get() = SupabaseConfig.isConfigured

    protected open suspend fun resolveCurrentAuthUserId(): String? {
        if (!isRemoteBackendEnabled) return null
        return try {
            SupabaseModule.auth.currentUserOrNull()?.id
        } catch (_: Exception) {
            null
        }
    }

    protected open suspend fun fetchRemoteFarmById(farmId: String): FarmDto? {
        if (!isRemoteBackendEnabled || farmId.isBlank()) return null
        return SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
            .select { filter { eq("id", ensureValidUuid(farmId)) } }
            .decodeList<FarmDto>()
            .firstOrNull()
    }

    protected open suspend fun fetchRemoteFarmByOwnerId(ownerId: String): FarmDto? {
        if (!isRemoteBackendEnabled || ownerId.isBlank()) return null
        return SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
            .select { filter { eq("owner_id", ensureValidUuid(ownerId)) } }
            .decodeList<FarmDto>()
            .firstOrNull()
    }

    protected open suspend fun executeCreateGoatListingSecureRpc(
        goatPayload: JsonObject,
        fallbackGoatId: String,
        fallbackFarmId: String
    ): GoatDto? {
        val rpcResult = SupabaseModule.client.postgrest.rpc(
            "create_goat_listing_secure",
            buildJsonObject {
                put("p_goat", goatPayload)
            }
        )
        return parseGoatDtoFromRpcResponse(rpcResult.data, fallbackGoatId, fallbackFarmId)
    }

    protected open suspend fun executeUpdateGoatListingRemote(
        goatId: String,
        farmId: String,
        payloadWithoutTag: JsonObject,
        payloadWithTag: JsonObject
    ): GoatDto? {
        return try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .update(payloadWithoutTag) {
                    filter {
                        eq("id", goatId)
                        if (farmId.isNotBlank()) {
                            eq("farm_id", farmId)
                        }
                    }
                    select()
                }
                .decodeSingleOrNull<GoatDto>()
        } catch (firstDbErr: Exception) {
            val errText = (firstDbErr.message ?: "").lowercase()
            if (errText.contains("tag_number") && (errText.contains("null value") || errText.contains("not-null"))) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                    .update(payloadWithTag) {
                        filter {
                            eq("id", goatId)
                            if (farmId.isNotBlank()) {
                                eq("farm_id", farmId)
                            }
                        }
                        select()
                    }
                    .decodeSingleOrNull<GoatDto>()
            } else {
                throw firstDbErr
            }
        }
    }

    protected open suspend fun syncGoatImagesRemote(
        goatId: String,
        mergedImages: List<String>,
        isExisting: Boolean
    ) {
        if (!isRemoteBackendEnabled) return
        if (mergedImages.isNotEmpty()) {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES].delete {
                filter { eq("goat_id", goatId) }
            }
            mergedImages.forEachIndexed { idx, imgUrl ->
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES].insert(
                    buildJsonObject {
                        put("goat_id", goatId)
                        put("image_url", imgUrl)
                        put("display_order", idx)
                        put("is_primary", idx == 0)
                    }
                )
            }
        } else if (isExisting) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOAT_IMAGES].delete {
                    filter { eq("goat_id", goatId) }
                }
            } catch (_: Exception) {}
        }
    }

    internal fun parseGoatDtoFromRpcResponse(
        rawData: String?,
        fallbackGoatId: String,
        fallbackFarmId: String
    ): GoatDto? {
        val trimmed = rawData?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        return try {
            val element = rpcJson.parseToJsonElement(trimmed)
            when (element) {
                is JsonArray -> {
                    val firstObj = element.firstOrNull()?.jsonObject ?: return null
                    rpcJson.decodeFromJsonElement<GoatDto>(firstObj)
                }
                is JsonObject -> {
                    val candidateObj = when {
                        element["goat"] is JsonObject -> element["goat"]!!.jsonObject
                        element["data"] is JsonObject -> element["data"]!!.jsonObject
                        else -> element
                    }
                    if (candidateObj.containsKey("name") || candidateObj.containsKey("breed_name")) {
                        rpcJson.decodeFromJsonElement<GoatDto>(candidateObj)
                    } else {
                        val returnedId = candidateObj["id"]?.jsonPrimitive?.contentOrNull
                            ?: candidateObj["goat_id"]?.jsonPrimitive?.contentOrNull
                            ?: fallbackGoatId
                        val returnedCode = candidateObj["goat_code"]?.jsonPrimitive?.contentOrNull ?: ""
                        val returnedFarmId = candidateObj["farm_id"]?.jsonPrimitive?.contentOrNull ?: fallbackFarmId
                        GoatDto(
                            id = returnedId,
                            goatCode = returnedCode,
                            farmId = returnedFarmId
                        )
                    }
                }
                is JsonPrimitive -> {
                    val idStr = element.contentOrNull?.trim().orEmpty()
                    if (idStr.isNotBlank() && !idStr.equals("true", ignoreCase = true) && !idStr.equals("false", ignoreCase = true)) {
                        GoatDto(id = idStr, farmId = fallbackFarmId)
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice decoding create_goat_listing_secure response: ${e.message}")
            null
        }
    }

    override suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray>,
        isSuperAdmin: Boolean
    ): Result<Goat> = addOrUpdateGoat(
        goat = goat,
        imageBytesList = imageBytesList,
        isSuperAdmin = isSuperAdmin,
        actorUserId = null,
        actorFarmId = null
    )

    override suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray>,
        isSuperAdmin: Boolean,
        actorUserId: String?,
        actorFarmId: String?
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
        if (goat.ageMonths <= 0) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid age in months greater than 0."))
        }

        val existingGoat = goat.id.takeIf { it.isNotBlank() }?.let { id ->
            _goats.value.find { it.id.equals(id, ignoreCase = true) }
        }
        val isExisting = existingGoat != null

        val authUserId = actorUserId?.takeIf { it.isNotBlank() } ?: resolveCurrentAuthUserId()

        // If editing an existing goat, a Farm Admin cannot reassign it to a different farm
        if (isExisting && !isSuperAdmin) {
            val existingFarmId = existingGoat!!.farmId
            if (goat.farmId.isNotBlank() && existingFarmId.isNotBlank() && !goat.farmId.equals(existingFarmId, ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalAccessException("You can only create or manage goat listings for your own farm.")
                )
            }
            if (!actorFarmId.isNullOrBlank() && existingFarmId.isNotBlank() && !existingFarmId.equals(actorFarmId, ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalAccessException("You can only create or manage goat listings for your own farm.")
                )
            }
        }

        // Prevent Farm Admin from submitting a listing for another farm ID
        val explicitRequestedFarmId = if (isExisting) {
            existingGoat!!.farmId.ifBlank { goat.farmId }
        } else {
            goat.farmId
        }

        if (!isSuperAdmin && !actorFarmId.isNullOrBlank() && explicitRequestedFarmId.isNotBlank() &&
            !explicitRequestedFarmId.equals(actorFarmId, ignoreCase = true)
        ) {
            return@withContext Result.failure(
                IllegalAccessException("You can only create or manage goat listings for your own farm.")
            )
        }

        // Resolve the farm accurately from state, cache, and remote database without substituting a different farm ID
        var farm: Farm? = if (explicitRequestedFarmId.isNotBlank()) {
            _farms.value.find { it.id.equals(explicitRequestedFarmId, ignoreCase = true) }
                ?: FarmLocalCache.getCachedFarm(explicitRequestedFarmId)
        } else if (!actorFarmId.isNullOrBlank()) {
            _farms.value.find { it.id.equals(actorFarmId, ignoreCase = true) }
                ?: FarmLocalCache.getCachedFarm(actorFarmId)
        } else if (!authUserId.isNullOrBlank() && !isSuperAdmin) {
            _farms.value.find { it.ownerId.equals(authUserId, ignoreCase = true) }
        } else null

        if (isRemoteBackendEnabled) {
            try {
                // Always fetch fresh farm record from Supabase so approval status, goat_listing_limit, and consumed_listing_slots are up-to-date
                val fetchedFarmDto = if (explicitRequestedFarmId.isNotBlank()) {
                    fetchRemoteFarmById(explicitRequestedFarmId)
                } else if (!actorFarmId.isNullOrBlank()) {
                    fetchRemoteFarmById(actorFarmId)
                } else if (!authUserId.isNullOrBlank() && !isSuperAdmin) {
                    fetchRemoteFarmByOwnerId(authUserId)
                } else null

                if (fetchedFarmDto != null) {
                    val domainFarm = fetchedFarmDto.toDomain()
                    FarmLocalCache.saveFarmProfile(domainFarm)
                    _farms.update { list ->
                        val exists = list.any { it.id.equals(domainFarm.id, ignoreCase = true) }
                        if (exists) {
                            list.map { if (it.id.equals(domainFarm.id, ignoreCase = true)) domainFarm else it }
                        } else {
                            list + domainFarm
                        }
                    }
                    farm = domainFarm
                }
            } catch (e: Exception) {
                Log.w(TAG, "Farm lookup before addOrUpdateGoat notice: ${e.message}", e)
            }
        }

        val effectiveFarmId = when {
            explicitRequestedFarmId.isNotBlank() -> explicitRequestedFarmId
            farm != null && farm.id.isNotBlank() -> farm.id
            !actorFarmId.isNullOrBlank() -> actorFarmId
            isSuperAdmin -> SEED_AMMAL_FARM_UUID
            else -> ""
        }

        if (effectiveFarmId.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Your farm profile could not be loaded. Please refresh or save your Farm Info first.")
            )
        }

        // Strict ownership enforcement for non-Super-Admin (FARM_ADMIN)
        if (!isSuperAdmin) {
            if (farm == null) {
                val ownedFarmInState = if (!authUserId.isNullOrBlank()) {
                    _farms.value.find { it.ownerId.equals(authUserId, ignoreCase = true) }
                } else null
                return@withContext if (ownedFarmInState != null && !ownedFarmInState.id.equals(effectiveFarmId, ignoreCase = true)) {
                    Result.failure(IllegalAccessException("You can only create or manage goat listings for your own farm."))
                } else {
                    Result.failure(IllegalStateException("Your farm profile could not be loaded. Please refresh or save your Farm Info first."))
                }
            }

            if (farm.isAmmalOwnFarm || farm.id.equals(SEED_AMMAL_FARM_UUID, ignoreCase = true)) {
                val isAmmalOwner = !authUserId.isNullOrBlank() && farm.ownerId.equals(authUserId, ignoreCase = true)
                if (!isAmmalOwner) {
                    return@withContext Result.failure(
                        IllegalAccessException("Only Super Admin can manage Ammal Farm listings.")
                    )
                }
            }

            if (!authUserId.isNullOrBlank() && farm.ownerId.isNotBlank() && !farm.ownerId.equals(authUserId, ignoreCase = true)) {
                return@withContext Result.failure(
                    IllegalAccessException("You can only create or manage goat listings for your own farm.")
                )
            }

            if (!authUserId.isNullOrBlank()) {
                val ownedFarm = _farms.value.find { it.ownerId.equals(authUserId, ignoreCase = true) }
                if (ownedFarm != null && !ownedFarm.id.equals(farm.id, ignoreCase = true)) {
                    return@withContext Result.failure(
                        IllegalAccessException("You can only create or manage goat listings for your own farm.")
                    )
                }
            }
        }

        // Approval & quota check for new listings on non-Ammal partner farms
        if (!isExisting && !isSuperAdmin && farm != null && !farm.isAmmalOwnFarm) {
            if (!farm.verificationStatus.isApprovedOrVerified) {
                return@withContext Result.failure(
                    IllegalStateException("Your partner farm must be approved by Super Admin before you can add goats.")
                )
            }
            val currentFarmGoatCount = _goats.value.count {
                it.farmId.equals(farm.id, ignoreCase = true) &&
                    it.isActive &&
                    it.availabilityStatus != AvailabilityStatus.UNAVAILABLE
            }
            val effectiveConsumedSlots = maxOf(farm.consumedListingSlots, currentFarmGoatCount)
            if (!farm.hasRemainingListingQuota || effectiveConsumedSlots >= farm.goatListingLimit) {
                return@withContext Result.failure(
                    IllegalStateException("Your goat listing quota has been fully consumed. Contact Super Admin (+91 63808 98358) to purchase additional listing slots.")
                )
            }
        }

        try {
            val cleanGoatId = ensureValidUuid(goat.id.ifBlank { UUID.randomUUID().toString() })
            val cleanFarmId = ensureValidUuid(effectiveFarmId)

            val uploadedUrls = mutableListOf<String>()
            if (isRemoteBackendEnabled && imageBytesList.isNotEmpty()) {
                val bucket = SupabaseModule.storage.from(SupabaseConfig.BUCKET_GOAT_IMAGES)
                imageBytesList.forEachIndexed { index, bytes ->
                    if (bytes.isEmpty()) {
                        throw IllegalArgumentException("Selected image #${index + 1} is empty or unreadable.")
                    }
                    // Use <farm_id>/goats/... as primary path so (storage.foldername(name))[1] = <farm_id> satisfies Storage RLS
                    val primaryPath = "$cleanFarmId/goats/${cleanGoatId}_${System.currentTimeMillis()}_$index.jpg"
                    try {
                        bucket.upload(primaryPath, bytes) { upsert = true }
                        val resolvedUrl = SupabaseConfig.resolveStorageUrl(primaryPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        if (resolvedUrl.isBlank()) {
                            throw IllegalStateException("Image upload returned an invalid image URL.")
                        }
                        uploadedUrls.add(resolvedUrl)
                    } catch (primaryErr: Exception) {
                        val fallbackPath = "goats/$cleanFarmId/${cleanGoatId}_${System.currentTimeMillis()}_$index.jpg"
                        try {
                            bucket.upload(fallbackPath, bytes) { upsert = true }
                            val resolvedUrl = SupabaseConfig.resolveStorageUrl(fallbackPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                            if (resolvedUrl.isBlank()) {
                                throw IllegalStateException("Image upload returned an invalid image URL.")
                            }
                            uploadedUrls.add(resolvedUrl)
                        } catch (uploadErr: Exception) {
                            Log.e(TAG, "Goat image upload failed: ${primaryErr.message}", primaryErr)
                            throw IllegalStateException("Failed to upload goat image: ${primaryErr.message ?: uploadErr.message}", primaryErr)
                        }
                    }
                }
            }

            val mergedImages = (uploadedUrls + goat.imageUrls + listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() }))
                .map { if (isRemoteBackendEnabled) SupabaseConfig.resolveStorageUrl(it, SupabaseConfig.BUCKET_GOAT_IMAGES) else it }
                .distinct()
                .filter { it.isNotBlank() }

            val primaryImage = mergedImages.firstOrNull() ?: ""
            val autoApprove = isSuperAdmin || (farm?.isAmmalOwnFarm == true)
            val resolvedApproval = if (isExisting) {
                if (isSuperAdmin) goat.approvalStatus else existingGoat!!.approvalStatus
            } else if (autoApprove) {
                ApprovalStatus.APPROVED
            } else {
                ApprovalStatus.PENDING
            }

            val finalPrice = if (goat.discountPercentage > 0) {
                goat.price * (1.0 - (goat.discountPercentage / 100.0))
            } else {
                goat.price
            }

            val autoTagId = goat.tagId.ifBlank {
                goat.goatCode.ifBlank { "TAG-${cleanGoatId.take(6).uppercase()}" }
            }

            val updatedGoat = goat.copy(
                id = cleanGoatId,
                goatCode = goat.goatCode,
                tagId = autoTagId,
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

            if (isRemoteBackendEnabled) {
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

                fun buildUpdateGoatPayload(includeTagNumber: Boolean) = buildJsonObject {
                    if (includeTagNumber) {
                        put("tag_number", autoTagId)
                    }
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
                    put("vaccination_status", updatedGoat.healthNotes.ifBlank { "Healthy" })
                    if (isSuperAdmin) {
                        put("is_approved_by_admin", updatedGoat.approvalStatus == ApprovalStatus.APPROVED)
                    }
                }

                val createRpcPayload = buildJsonObject {
                    put("id", cleanGoatId)
                    put("farm_id", cleanFarmId)
                    put("tag_number", autoTagId)
                    put("name", updatedGoat.name.trim())
                    put("breed_name", updatedGoat.breed.trim())
                    put("gender", dbGender)
                    put("age_months", updatedGoat.ageMonths.coerceAtLeast(1))
                    put("weight_kg", updatedGoat.weightKg)
                    put("purpose", dbPurpose)
                    put("price", updatedGoat.price)
                    put("discount_percentage", updatedGoat.discountPercentage.coerceIn(0.0, 99.0))
                    put("status", if (!isSuperAdmin) "AVAILABLE" else dbStatus)
                    put("description", updatedGoat.description.trim())
                    put("vaccination_status", updatedGoat.healthNotes.ifBlank { "Healthy" })
                    put("is_approved_by_admin", autoApprove && updatedGoat.approvalStatus == ApprovalStatus.APPROVED)
                    if (primaryImage.isNotBlank()) {
                        put("image_url", primaryImage)
                    }
                    if (mergedImages.isNotEmpty()) {
                        put("image_urls", JsonArray(mergedImages.map { JsonPrimitive(it) }))
                    }
                }

                val savedGoatDto = if (isExisting) {
                    executeUpdateGoatListingRemote(
                        goatId = cleanGoatId,
                        farmId = cleanFarmId,
                        payloadWithoutTag = buildUpdateGoatPayload(includeTagNumber = false),
                        payloadWithTag = buildUpdateGoatPayload(includeTagNumber = true)
                    )
                } else {
                    executeCreateGoatListingSecureRpc(
                        goatPayload = createRpcPayload,
                        fallbackGoatId = cleanGoatId,
                        fallbackFarmId = cleanFarmId
                    )
                }

                val persistedGoatId = savedGoatDto?.id?.takeIf { it.isNotBlank() } ?: cleanGoatId

                syncGoatImagesRemote(
                    goatId = persistedGoatId,
                    mergedImages = mergedImages,
                    isExisting = isExisting
                )

                val finalGoat = if (savedGoatDto != null && savedGoatDto.name.isNotBlank()) {
                    savedGoatDto.toDomain(
                        resolvedPhotos = mergedImages,
                        resolvedFarmName = updatedGoat.farmName,
                        resolvedFarmLocation = updatedGoat.farmLocation,
                        resolvedFarmContact = updatedGoat.farmContact
                    )
                } else {
                    updatedGoat.copy(
                        id = persistedGoatId,
                        goatCode = savedGoatDto?.goatCode?.takeIf { it.isNotBlank() }
                            ?: updatedGoat.goatCode.ifBlank { "GOAT-${persistedGoatId.take(4).uppercase()}" }
                    )
                }

                _goats.update { list ->
                    if (isExisting) {
                        list.map { if (it.id.equals(cleanGoatId, ignoreCase = true) || it.id.equals(finalGoat.id, ignoreCase = true)) finalGoat else it }
                    } else {
                        listOf(finalGoat) + list.filterNot { it.id.equals(finalGoat.id, ignoreCase = true) }
                    }
                }

                if (!isExisting) {
                    _farms.update { farmsList ->
                        farmsList.map { f ->
                            if (f.id.equals(cleanFarmId, ignoreCase = true) && !f.isAmmalOwnFarm) {
                                val nextFarm = f.copy(consumedListingSlots = f.consumedListingSlots + 1)
                                FarmLocalCache.saveFarmProfile(nextFarm)
                                nextFarm
                            } else f
                        }
                    }
                }

                try {
                    refreshFarms()
                    refreshGoats()
                } catch (refreshErr: Exception) {
                    Log.w(TAG, "Post-save refresh notice: ${refreshErr.message}", refreshErr)
                }

                val refreshedGoat = _goats.value.find { it.id.equals(finalGoat.id, ignoreCase = true) } ?: finalGoat
                return@withContext Result.success(refreshedGoat)
            }

            _goats.update { list ->
                if (isExisting) {
                    list.map { if (it.id.equals(updatedGoat.id, ignoreCase = true)) updatedGoat else it }
                } else {
                    listOf(updatedGoat) + list.filterNot { it.id.equals(updatedGoat.id, ignoreCase = true) }
                }
            }
            if (!isExisting) {
                _farms.update { farmsList ->
                    farmsList.map { f ->
                        if (f.id.equals(cleanFarmId, ignoreCase = true) && !f.isAmmalOwnFarm) {
                            val nextFarm = f.copy(consumedListingSlots = f.consumedListingSlots + 1)
                            FarmLocalCache.saveFarmProfile(nextFarm)
                            nextFarm
                        } else f
                    }
                }
            }
            Result.success(updatedGoat)
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to save goat listing (isExisting=$isExisting, farmId=$effectiveFarmId, goatId=${goat.id}): ${e.message}",
                e
            )
            val friendly = UserFriendlyErrorMapper.forGoatListing(e)
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
                    AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED, AvailabilityStatus.EXPIRED -> "INACTIVE"
                    AvailabilityStatus.AVAILABLE -> "AVAILABLE"
                    AvailabilityStatus.SOLD, AvailabilityStatus.COMPLETED -> "SOLD"
                    else -> "RESERVED"
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
            val friendly = UserFriendlyErrorMapper.forFarmModeration(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateFarmProfile(
        farm: Farm,
        logoBytes: ByteArray?,
        bannerBytes: ByteArray?
    ): Result<Farm> = withContext(Dispatchers.IO) {
        if (farm.name.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Farm name cannot be empty."))
        }
        try {
            val cleanFarmId = ensureValidUuid(farm.id.ifBlank { UUID.randomUUID().toString() })
            var finalLogoUrl = farm.logoUrl.ifBlank { farm.imageUrl }
            var finalBannerUrl = farm.bannerUrl

            if (SupabaseConfig.isConfigured) {
                val bucket = SupabaseModule.storage.from(SupabaseConfig.BUCKET_GOAT_IMAGES)
                if (logoBytes != null && logoBytes.isNotEmpty()) {
                    val primaryLogoPath = "$cleanFarmId/branding/logo_${System.currentTimeMillis()}.jpg"
                    try {
                        bucket.upload(primaryLogoPath, logoBytes) { upsert = true }
                        finalLogoUrl = SupabaseConfig.resolveStorageUrl(primaryLogoPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                    } catch (primaryErr: Exception) {
                        val fallbackLogoPath = "goats/$cleanFarmId/logo_${System.currentTimeMillis()}.jpg"
                        try {
                            bucket.upload(fallbackLogoPath, logoBytes) { upsert = true }
                            finalLogoUrl = SupabaseConfig.resolveStorageUrl(fallbackLogoPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        } catch (fallbackErr: Exception) {
                            throw IllegalStateException("Failed to upload farm logo: ${primaryErr.message ?: fallbackErr.message}", primaryErr)
                        }
                    }
                }

                if (bannerBytes != null && bannerBytes.isNotEmpty()) {
                    val primaryBannerPath = "$cleanFarmId/branding/banner_${System.currentTimeMillis()}.jpg"
                    try {
                        bucket.upload(primaryBannerPath, bannerBytes) { upsert = true }
                        finalBannerUrl = SupabaseConfig.resolveStorageUrl(primaryBannerPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                    } catch (primaryErr: Exception) {
                        val fallbackBannerPath = "goats/$cleanFarmId/banner_${System.currentTimeMillis()}.jpg"
                        try {
                            bucket.upload(fallbackBannerPath, bannerBytes) { upsert = true }
                            finalBannerUrl = SupabaseConfig.resolveStorageUrl(fallbackBannerPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        } catch (fallbackErr: Exception) {
                            throw IllegalStateException("Failed to upload farm banner: ${primaryErr.message ?: fallbackErr.message}", primaryErr)
                        }
                    }
                }

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
                        if (finalLogoUrl.isNotBlank()) {
                            put("logo_url", finalLogoUrl)
                        }
                        if (!finalBannerUrl.isNullOrBlank()) {
                            put("banner_url", finalBannerUrl)
                        }
                        put("updated_at", currentIsoTimestamp())
                    }
                ) {
                    filter { eq("id", cleanFarmId) }
                }
            }

            val updatedFarm = farm.copy(
                id = cleanFarmId,
                imageUrl = finalLogoUrl,
                logoUrl = finalLogoUrl,
                bannerUrl = finalBannerUrl
            )
            FarmLocalCache.saveFarmProfile(updatedFarm)
            _farms.update { list ->
                val exists = list.any { it.id == updatedFarm.id || it.id == farm.id }
                if (exists) {
                    list.map { if (it.id == updatedFarm.id || it.id == farm.id) updatedFarm else it }
                } else {
                    list + updatedFarm
                }
            }
            _goats.update { currentGoats ->
                currentGoats.map { g ->
                    if (g.farmId == updatedFarm.id || g.farmId == farm.id) {
                        g.copy(
                            farmName = updatedFarm.name,
                            farmLocation = updatedFarm.location,
                            farmContact = updatedFarm.contactNumber
                        )
                    } else g
                }
            }
            if (SupabaseConfig.isConfigured) {
                try { refreshFarms() } catch (_: Exception) {}
            }
            Result.success(updatedFarm)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.toUserMessage(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun increaseFarmListingLimit(farmId: String, additionalSlots: Int): Result<Unit> = withContext(Dispatchers.IO) {
        if (additionalSlots <= 0 || additionalSlots > 500) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid number of additional slots (1 to 500)."))
        }
        val target = _farms.value.find { it.id == farmId }
            ?: FarmLocalCache.getCachedFarm(farmId)
            ?: return@withContext Result.failure(IllegalArgumentException("Farm not found."))
        val newLimit = (target.goatListingLimit + additionalSlots).coerceAtLeast(2)
        try {
            if (SupabaseConfig.isConfigured) {
                val cleanFarmId = ensureValidUuid(farmId)
                try {
                    // Use the dedicated Super Admin quota update RPC which checks Super Admin authorization
                    // server-side without resetting consumed_listing_slots or confusing custom slots with slot-pack purchases
                    SupabaseModule.client.postgrest.rpc(
                        "admin_update_farm_listing_limit",
                        buildJsonObject {
                            put("p_farm_id", cleanFarmId)
                            put("p_new_limit", newLimit)
                        }
                    )
                } catch (rpcErr: Exception) {
                    val msg = (rpcErr.message ?: "").lowercase()
                    if (msg.contains("only super admin") || msg.contains("permission denied") || msg.contains("not authorized")) {
                        throw rpcErr
                    }
                    try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                            buildJsonObject {
                                put("goat_listing_limit", newLimit)
                                put("updated_at", currentIsoTimestamp())
                            }
                        ) {
                            filter { eq("id", cleanFarmId) }
                        }
                    } catch (_: Exception) {
                        SupabaseModule.client.postgrest.rpc(
                            "increase_farm_goat_quota_with_manual_payment",
                            buildJsonObject {
                                put("p_farm_id", cleanFarmId)
                                put("p_slots_to_add", additionalSlots)
                                put("p_amount", PlatformPricingManager.goatListingPackFeeInr)
                                put("p_payment_reference", "ADMIN-QUOTA-${System.currentTimeMillis()}")
                                put("p_receipt_number", "RCPT-${(10000..99999).random()}")
                                put("p_notes", "Super Admin increased quota by $additionalSlots slots (New total: $newLimit)")
                            }
                        )
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
            if (SupabaseConfig.isConfigured) {
                try { refreshFarms() } catch (_: Exception) {}
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
