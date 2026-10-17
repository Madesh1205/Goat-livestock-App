package com.ammalfarm.adusanthai.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.core.util.PlatformPricingManager
import com.ammalfarm.adusanthai.data.dto.*
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
                val pricing = SupabaseModule.client.postgrest["platform_pricing_settings"]
                    .select()
                    .decodeList<PlatformPricingSettingsDto>()
                    .firstOrNull()
                if (pricing != null) {
                    PlatformPricingManager.farmApprovalFeeInr = pricing.farmApprovalFeeInr
                    PlatformPricingManager.goatListingPackFeeInr = pricing.goatListingPackFeeInr
                    PlatformPricingManager.goatListingPackSize = pricing.goatListingPackSize
                }
            } catch (_: Exception) {}

            val currentUserId = try { SupabaseModule.auth.currentUserOrNull()?.id } catch (_: Exception) { null }
            if (!currentUserId.isNullOrBlank()) {
                refreshWishlist(currentUserId)
                refreshNotifications(currentUserId)
                refreshBookings(userId = currentUserId)
            }

            if (goatsRes.isFailure && farmsRes.isFailure) {
                val err = goatsRes.exceptionOrNull() ?: farmsRes.exceptionOrNull()
                val friendly = UserFriendlyErrorMapper.toUserMessage(err)
                _errorMessage.value = friendly
                return@withContext Result.failure(Exception(friendly, err))
            }
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
            return@withContext Result.failure(IllegalStateException("Backend database is not configured."))
        }
        try {
            val dtos = SupabaseModule.client.postgrest["goats"]
                .select()
                .decodeList<GoatDto>()
            val farmMap = _farms.value.associateBy { it.id }
            val domainList = dtos.map { dto ->
                val domain = dto.toDomain()
                val associatedFarm = farmMap[domain.farmId]
                if (associatedFarm != null) {
                    domain.copy(
                        farmName = domain.farmName.ifBlank { associatedFarm.name },
                        farmLocation = domain.farmLocation.ifBlank { associatedFarm.location },
                        farmContact = domain.farmContact.ifBlank { associatedFarm.contactNumber }
                    )
                } else {
                    domain
                }
            }
            _goats.value = domainList
            Result.success(domainList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading goats: ${e.message}", e)
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
            val domainList = dtos.map { it.toDomain() }
            _farms.value = domainList
            domainList.forEach { FarmLocalCache.saveFarmProfile(it) }
            Result.success(domainList)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading farms: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshBookings(
        userId: String?,
        farmId: String?,
        isSuperAdmin: Boolean
    ): Result<List<Booking>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.failure(IllegalStateException("Backend database is not configured."))
        }
        try {
            val dtos = SupabaseModule.client.postgrest["bookings"]
                .select()
                .decodeList<BookingDto>()
            val goatMap = _goats.value.associateBy { it.id }
            val farmMap = _farms.value.associateBy { it.id }
            val enriched = dtos.map { dto ->
                val b = dto.toDomain()
                val g = goatMap[b.goatId]
                val f = farmMap[b.farmId]
                b.copy(
                    goatName = b.goatName.ifBlank { g?.name ?: "Goat Listing" },
                    goatBreed = b.goatBreed.ifBlank { g?.breed ?: "" },
                    goatImageUrl = b.goatImageUrl.ifBlank { g?.imageUrl ?: "" },
                    goatPrice = if (b.goatPrice > 0.0) b.goatPrice else (g?.effectivePrice ?: 0.0),
                    farmName = b.farmName.ifBlank { f?.name ?: g?.farmName ?: "" },
                    farmPhone = b.farmPhone.ifBlank { f?.contactNumber ?: g?.farmContact ?: "" }
                )
            }
            _bookings.value = enriched
            Result.success(enriched)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bookings: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun refreshWishlist(userId: String): Result<Set<String>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || userId.isBlank()) {
            return@withContext Result.success(_wishlistedGoatIds.value)
        }
        try {
            val dtos = SupabaseModule.client.postgrest["wishlists"]
                .select { filter { eq("user_id", userId) } }
                .decodeList<WishlistDto>()
            val ids = dtos.map { it.goatId }.filter { it.isNotBlank() }.toSet()
            _wishlistedGoatIds.value = ids
            Result.success(ids)
        } catch (e: Exception) {
            Log.w(TAG, "Notice loading wishlists: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (userId.isBlank() || goatId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please sign in to save goats to your wishlist."))
        }
        val currentIds = _wishlistedGoatIds.value
        val isCurrentlyWishlisted = currentIds.contains(goatId)
        try {
            if (isCurrentlyWishlisted) {
                SupabaseModule.client.postgrest["wishlists"].delete {
                    filter {
                        eq("user_id", userId)
                        eq("goat_id", goatId)
                    }
                }
                _wishlistedGoatIds.value = currentIds - goatId
                Result.success(false)
            } else {
                SupabaseModule.client.postgrest["wishlists"].insert(
                    buildJsonObject {
                        put("user_id", userId)
                        put("goat_id", goatId)
                    }
                )
                _wishlistedGoatIds.value = currentIds + goatId
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle wishlist: ${e.message}", e)
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
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
            return@withContext Result.failure(IllegalStateException("Please sign in to book a goat."))
        }
        if (goat.farmId.isNotBlank() && customer.farmId == goat.farmId) {
            return@withContext Result.failure(IllegalStateException("You cannot book goats listed by your own farm."))
        }
        if (goat.availabilityStatus != AvailabilityStatus.AVAILABLE || !goat.isActive) {
            return@withContext Result.failure(IllegalStateException("This goat is no longer available. Please choose another goat."))
        }

        try {
            // Try atomic RPC first if deployed, fallback to standard insert + status update
            try {
                SupabaseModule.client.postgrest.rpc(
                    "create_goat_booking_atomic",
                    buildJsonObject {
                        put("p_goat_id", goat.id)
                        put("p_customer_id", customer.id)
                        put("p_customer_name", customer.name)
                        put("p_customer_phone", customer.phone)
                        put("p_preferred_visit_date", preferredVisitDate)
                        put("p_advance_amount", advanceAmount)
                        put("p_notes", notes)
                    }
                )
            } catch (rpcErr: Exception) {
                val msg = rpcErr.message ?: ""
                if (msg.contains("does not exist", ignoreCase = true) || msg.contains("PGRST202", ignoreCase = true)) {
                    val bookingId = UUID.randomUUID().toString()
                    val code = "BK-${System.currentTimeMillis().toString().takeLast(6)}"
                    SupabaseModule.client.postgrest["bookings"].insert(
                        buildJsonObject {
                            put("id", bookingId)
                            put("booking_code", code)
                            put("goat_id", goat.id)
                            put("goat_name", goat.name)
                            put("goat_breed", goat.breed)
                            put("goat_image_url", goat.imageUrl)
                            put("goat_price", goat.effectivePrice)
                            put("advance_amount", advanceAmount)
                            put("customer_id", customer.id)
                            put("customer_name", customer.name)
                            put("customer_phone", customer.phone)
                            put("customer_email", customer.email)
                            put("farm_id", goat.farmId)
                            put("farm_name", goat.farmName)
                            put("farm_phone", goat.farmContact)
                            put("preferred_visit_date", preferredVisitDate)
                            put("notes", notes)
                            put("status", BookingStatus.PENDING.name)
                        }
                    )
                    try {
                        SupabaseModule.client.postgrest["goats"].update(
                            { set("availability_status", AvailabilityStatus.RESERVED.name) }
                        ) { filter { eq("id", goat.id) } }
                    } catch (_: Exception) {}
                } else {
                    throw rpcErr
                }
            }

            refreshGoats()
            val updatedBookings = refreshBookings(userId = customer.id).getOrNull().orEmpty()
            val created = updatedBookings.firstOrNull { it.goatId == goat.id && it.customerId == customer.id }
                ?: Booking(
                    id = UUID.randomUUID().toString(),
                    goatId = goat.id,
                    goatName = goat.name,
                    goatBreed = goat.breed,
                    goatImageUrl = goat.imageUrl,
                    goatPrice = goat.effectivePrice,
                    advanceAmount = advanceAmount,
                    customerId = customer.id,
                    customerName = customer.name,
                    customerPhone = customer.phone,
                    customerEmail = customer.email,
                    farmId = goat.farmId,
                    farmName = goat.farmName,
                    farmPhone = goat.farmContact,
                    preferredVisitDate = preferredVisitDate,
                    notes = notes,
                    status = BookingStatus.PENDING
                )
            Result.success(created)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.forBooking(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateBookingStatus(
        bookingId: String,
        newStatus: BookingStatus,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetBooking = _bookings.value.find { it.id == bookingId }
            try {
                SupabaseModule.client.postgrest.rpc(
                    "transition_booking_status_atomic",
                    buildJsonObject {
                        put("p_booking_id", bookingId)
                        put("p_new_status", newStatus.name)
                        put("p_reason", reason)
                    }
                )
            } catch (rpcErr: Exception) {
                SupabaseModule.client.postgrest["bookings"].update(
                    {
                        set("status", newStatus.name)
                        set("updated_at", currentIsoTimestamp())
                    }
                ) {
                    filter { eq("id", bookingId) }
                }
                if (targetBooking != null && targetBooking.goatId.isNotBlank()) {
                    val newGoatAvailability = when (newStatus) {
                        BookingStatus.COMPLETED -> AvailabilityStatus.SOLD
                        BookingStatus.CANCELLED, BookingStatus.REJECTED, BookingStatus.EXPIRED -> AvailabilityStatus.AVAILABLE
                        BookingStatus.CONFIRMED, BookingStatus.APPROVED, BookingStatus.PENDING -> AvailabilityStatus.RESERVED
                    }
                    try {
                        SupabaseModule.client.postgrest["goats"].update(
                            { set("availability_status", newGoatAvailability.name) }
                        ) { filter { eq("id", targetBooking.goatId) } }
                    } catch (_: Exception) {}
                }
            }
            refreshGoats()
            refreshBookings()
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.forBooking(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun addOrUpdateGoat(
        goat: Goat,
        imageBytesList: List<ByteArray>,
        isSuperAdmin: Boolean
    ): Result<Goat> = withContext(Dispatchers.IO) {
        try {
            val uploadedUrls = mutableListOf<String>()
            for ((index, bytes) in imageBytesList.withIndex()) {
                val fileName = "${goat.farmId.ifBlank { "farm" }}/${UUID.randomUUID()}_$index.jpg"
                try {
                    val bucket = SupabaseModule.client.storage[SupabaseConfig.BUCKET_GOAT_IMAGES]
                    bucket.upload(fileName, bytes) { upsert = true }
                    val publicUrl = SupabaseConfig.resolveStorageUrl(fileName, SupabaseConfig.BUCKET_GOAT_IMAGES)
                    uploadedUrls.add(publicUrl)
                } catch (uploadErr: Exception) {
                    Log.w(TAG, "Image upload notice: ${uploadErr.message}")
                }
            }

            val mergedImageUrls = (goat.imageUrls + uploadedUrls).filter { it.isNotBlank() }.distinct()
            val primaryImage = mergedImageUrls.firstOrNull() ?: goat.imageUrl

            val finalId = goat.id.ifBlank { UUID.randomUUID().toString() }
            val effectiveApproval = if (isSuperAdmin) ApprovalStatus.APPROVED else goat.approvalStatus
            val computedFinalPrice = if (goat.discountPercentage > 0.0) {
                goat.price * (1.0 - goat.discountPercentage / 100.0)
            } else {
                goat.price
            }

            val payload = buildJsonObject {
                put("id", finalId)
                if (goat.goatCode.isNotBlank()) put("goat_code", goat.goatCode)
                put("tag_id", goat.tagId.ifBlank { "GT-${finalId.take(6).uppercase()}" })
                put("name", goat.name.trim())
                put("breed", goat.breed.trim())
                put("gender", goat.gender.trim())
                put("age_months", goat.ageMonths)
                put("weight_kg", goat.weightKg)
                put("price", goat.price)
                put("discount_percentage", goat.discountPercentage)
                put("final_price", computedFinalPrice)
                put("color", goat.color.trim())
                put("purpose", goat.purpose.trim())
                put("description", goat.description.trim())
                put("health_notes", goat.healthNotes.trim())
                put("is_vaccinated", goat.isVaccinated)
                put("is_verified", isSuperAdmin || goat.isVerified)
                put("image_url", primaryImage)
                put("farm_id", goat.farmId)
                put("farm_name", goat.farmName)
                put("farm_location", goat.farmLocation)
                put("farm_contact", goat.farmContact)
                put("approval_status", effectiveApproval.name)
                put("availability_status", goat.availabilityStatus.name)
                put("is_active", goat.isActive)
            }

            SupabaseModule.client.postgrest["goats"].upsert(payload)
            refreshGoats()
            val saved = _goats.value.find { it.id == finalId } ?: goat.copy(
                id = finalId,
                imageUrl = primaryImage,
                imageUrls = mergedImageUrls,
                finalPrice = computedFinalPrice,
                approvalStatus = effectiveApproval
            )
            Result.success(saved)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.forGoatListing(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun deleteGoat(goatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["goats"].delete {
                filter { eq("id", goatId) }
            }
            refreshGoats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun updateGoatApproval(
        goatId: String,
        status: ApprovalStatus,
        rejectionReason: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["goats"].update(
                {
                    set("approval_status", status.name)
                    set("is_verified", status == ApprovalStatus.APPROVED)
                    if (rejectionReason != null) {
                        set("rejection_reason", rejectionReason)
                    }
                    set("updated_at", currentIsoTimestamp())
                }
            ) {
                filter { eq("id", goatId) }
            }
            refreshGoats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun updateGoatAvailability(
        goatId: String,
        status: AvailabilityStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["goats"].update(
                {
                    set("availability_status", status.name)
                    set("updated_at", currentIsoTimestamp())
                }
            ) {
                filter { eq("id", goatId) }
            }
            refreshGoats()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun updateFarmVerification(
        farmId: String,
        status: VerificationStatus,
        rejectionReason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            try {
                if (status == VerificationStatus.VERIFIED || status == VerificationStatus.APPROVED) {
                    SupabaseModule.client.postgrest.rpc(
                        "approve_partner_farm_manual_payment",
                        buildJsonObject {
                            put("p_farm_id", farmId)
                            put("p_payment_reference", "SUPER_ADMIN_APPROVAL")
                        }
                    )
                } else {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                        {
                            set("verification_status", status.name)
                            set("updated_at", currentIsoTimestamp())
                        }
                    ) { filter { eq("id", farmId) } }
                }
            } catch (_: Exception) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                    {
                        set("verification_status", status.name)
                        if (status == VerificationStatus.VERIFIED || status == VerificationStatus.APPROVED) {
                            set("approval_fee_paid", true)
                        }
                        set("updated_at", currentIsoTimestamp())
                    }
                ) { filter { eq("id", farmId) } }
            }
            refreshFarms()
            Result.success(Unit)
        } catch (e: Exception) {
            val friendly = UserFriendlyErrorMapper.forFarmModeration(e)
            Result.failure(Exception(friendly, e))
        }
    }

    override suspend fun updateFarmProfile(farm: Farm): Result<Farm> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                {
                    set("name", farm.name.trim())
                    set("owner_name", farm.ownerName.trim())
                    set("location", farm.location.trim())
                    set("district", farm.district.trim())
                    set("contact_number", farm.contactNumber.trim())
                    set("description", farm.description.trim())
                    set("updated_at", currentIsoTimestamp())
                }
            ) {
                filter { eq("id", farm.id) }
            }
            FarmLocalCache.saveFarmProfile(farm)
            refreshFarms()
            Result.success(farm)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun increaseFarmListingLimit(farmId: String, additionalSlots: Int): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentFarm = _farms.value.find { it.id == farmId }
            val newLimit = (currentFarm?.goatListingLimit ?: 2) + additionalSlots
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(
                {
                    set("goat_listing_limit", newLimit)
                    set("updated_at", currentIsoTimestamp())
                }
            ) {
                filter { eq("id", farmId) }
            }
            refreshFarms()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun refreshNotifications(userId: String): Result<List<AppNotification>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || userId.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        try {
            val dtos = SupabaseModule.client.postgrest["notifications"]
                .select { filter { eq("user_id", userId) } }
                .decodeList<NotificationDto>()
            val list = dtos.map { it.toDomain() }
            _notifications.value = list
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markNotificationRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["notifications"].update(
                { set("is_read", true) }
            ) { filter { eq("id", notificationId) } }
            _notifications.update { list ->
                list.map { if (it.id == notificationId) it.copy(isRead = true) else it }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshAllUsers(): Result<List<UserProfile>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(emptyList())
        }
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .select()
                .decodeList<ProfileDto>()
            val users = dtos.map { it.toDomain() }
            _allUsers.value = users
            Result.success(users)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshReports(): Result<List<ListingReport>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(emptyList())
        }
        try {
            val dtos = SupabaseModule.client.postgrest["listing_reports"]
                .select()
                .decodeList<ListingReportDto>()
            val list = dtos.map { it.toDomain() }
            _reports.value = list
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun loadFarmReviews(farmId: String): Result<List<FarmReview>> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || farmId.isBlank()) {
            return@withContext Result.success(emptyList())
        }
        try {
            val dtos = SupabaseModule.client.postgrest["farm_reviews"]
                .select { filter { eq("farm_id", farmId) } }
                .decodeList<ReviewDto>()
            val list = dtos.map { it.toDomain() }
            _farmReviews.update { it + (farmId to list) }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun submitFarmReview(
        farmId: String,
        customer: UserProfile,
        rating: Int,
        comment: String
    ): Result<FarmReview> = withContext(Dispatchers.IO) {
        try {
            val reviewId = UUID.randomUUID().toString()
            SupabaseModule.client.postgrest["farm_reviews"].insert(
                buildJsonObject {
                    put("id", reviewId)
                    put("farm_id", farmId)
                    put("customer_id", customer.id)
                    put("customer_name", customer.name)
                    put("rating", rating.coerceIn(1, 5))
                    put("comment", comment.trim())
                }
            )
            loadFarmReviews(farmId)
            val created = FarmReview(
                id = reviewId,
                farmId = farmId,
                customerId = customer.id,
                customerName = customer.name,
                rating = rating,
                comment = comment.trim()
            )
            Result.success(created)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun submitListingReport(
        goatId: String,
        reporterId: String,
        reason: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["listing_reports"].insert(
                buildJsonObject {
                    put("id", UUID.randomUUID().toString())
                    put("goat_id", goatId)
                    put("reporter_id", reporterId)
                    put("reason", reason.trim())
                    put("status", "OPEN")
                }
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun resolveReport(reportId: String, status: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest["listing_reports"].update(
                { set("status", status) }
            ) { filter { eq("id", reportId) } }
            refreshReports()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun toggleUserSuspension(userId: String, suspend: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].update(
                {
                    set("is_suspended", suspend)
                    set("updated_at", currentIsoTimestamp())
                }
            ) { filter { eq("id", userId) } }
            refreshAllUsers()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }

    override suspend fun updatePlatformPricing(
        approvalFeeInr: Double,
        packFeeInr: Double,
        packSize: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            PlatformPricingManager.farmApprovalFeeInr = approvalFeeInr
            PlatformPricingManager.goatListingPackFeeInr = packFeeInr
            PlatformPricingManager.goatListingPackSize = packSize
            SupabaseModule.client.postgrest["platform_pricing_settings"].upsert(
                buildJsonObject {
                    put("id", 1)
                    put("farm_approval_fee_inr", approvalFeeInr)
                    put("goat_listing_pack_fee_inr", packFeeInr)
                    put("goat_listing_pack_size", packSize)
                }
            )
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
        }
    }
}
