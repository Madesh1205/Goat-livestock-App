package com.example.data.repository

import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.data.DefaultPlatformData
import com.example.data.dto.*
import com.example.model.*
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Order
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class SupabaseMarketplaceRepositoryImpl(
    private val authRepository: AuthRepository = AuthRepositoryImpl(),
    private val goatRepository: GoatRepository = SupabaseGoatRepositoryImpl(),
    private val farmRepository: FarmRepository = SupabaseFarmRepositoryImpl(),
    private val bookingRepository: BookingRepository = SupabaseBookingRepositoryImpl(),
    private val wishlistRepository: WishlistRepository = SupabaseWishlistRepositoryImpl(goatRepository)
) : MarketplaceRepository {

    private val TAG = "MarketplaceRepo"

    // ==========================================
    // 1. Auth & Session
    // ==========================================
    override fun getCurrentUser(): Flow<UserProfile?> = authRepository.currentUser

    override suspend fun registerUser(
        name: String,
        email: String,
        phone: String,
        role: UserRole,
        farmName: String?
    ): Result<UserProfile> {
        return if (role == UserRole.FARM_ADMIN && !farmName.isNullOrBlank()) {
            authRepository.registerFarmAdmin(
                name = name,
                email = email,
                password = "Password@123",
                phone = phone,
                farmName = farmName,
                farmDistrict = "Trichy",
                farmDescription = "Breeding farm registered via Ammal Farm Marketplace"
            )
        } else {
            authRepository.registerCustomer(
                name = name,
                email = email,
                password = "Password@123",
                phone = phone
            )
        }
    }

    override suspend fun login(email: String): Result<UserProfile> {
        return authRepository.login(email, "Password@123")
    }

    override suspend fun logout() {
        authRepository.logout()
    }

    // ==========================================
    // 2. Goats (Marketplace)
    // ==========================================
    override fun getApprovedGoats(): Flow<List<Goat>> = goatRepository.getApprovedGoats()
    override fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> = goatRepository.searchAndFilterGoats(criteria)
    override fun getGoatById(id: String): Flow<Goat?> = goatRepository.getGoatById(id)
    override fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = goatRepository.getGoatsByFarm(farmId)
    override fun getAllGoatsForAdmin(): Flow<List<Goat>> = goatRepository.getAllGoatsForAdmin()
    override fun getAvailableBreeds(): Flow<List<String>> = goatRepository.getAvailableBreeds()
    override suspend fun addGoatListing(goat: Goat): Result<Goat> = goatRepository.addGoatListing(goat)
    override suspend fun updateGoatListing(goat: Goat): Result<Goat> = goatRepository.updateGoatListing(goat)
    override suspend fun deleteGoatListing(goatId: String): Result<Unit> = goatRepository.deleteGoatListing(goatId)
    override suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit> =
        goatRepository.updateGoatApprovalStatus(goatId, status)
    override suspend fun suspendGoatListing(goatId: String): Result<Unit> =
        goatRepository.updateGoatApprovalStatus(goatId, ApprovalStatus.SUSPENDED)
    override suspend fun restoreGoatListing(goatId: String): Result<Unit> =
        goatRepository.updateGoatApprovalStatus(goatId, ApprovalStatus.APPROVED)
    override fun invalidateCache() = goatRepository.invalidateCache()

    // ==========================================
    // 3. Bookings
    // ==========================================
    override fun getCustomerBookings(customerId: String): Flow<List<Booking>> =
        bookingRepository.getCustomerBookings(customerId)

    override fun getFarmBookings(farmId: String): Flow<List<Booking>> =
        bookingRepository.getFarmBookings(farmId)

    override fun getAllBookings(): Flow<List<Booking>> = bookingRepository.getAllBookings()

    override suspend fun createBooking(goatId: String, notes: String): Result<Booking> {
        val currentUser = authRepository.currentUser.value
        val customerId = currentUser?.id ?: "usr-customer"
        return bookingRepository.createBooking(goatId, customerId, notes)
    }

    override suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus): Result<Unit> =
        bookingRepository.updateBookingStatus(bookingId, status)

    // ==========================================
    // 5. Farms
    // ==========================================
    override fun getAllFarms(): Flow<List<Farm>> = farmRepository.getAllFarmsForAdmin()
    override fun getFarmById(farmId: String): Flow<Farm?> = farmRepository.getFarmById(farmId)
    override suspend fun registerFarm(farm: Farm): Result<Farm> = farmRepository.registerFarm(farm)
    override suspend fun updateFarmProfile(farm: Farm): Result<Farm> = farmRepository.updateFarmProfile(farm)
    override suspend fun updateFarmLogo(farmId: String, logoUrl: String): Result<String> = farmRepository.updateFarmLogo(farmId, logoUrl)
    override suspend fun updateFarmVerification(farmId: String, status: VerificationStatus): Result<Unit> =
        farmRepository.updateFarmVerification(farmId, status)
    override suspend fun updateFarmListingLimit(farmId: String, limit: Int): Result<Unit> =
        farmRepository.updateFarmListingLimit(farmId, limit)

    // ==========================================
    // 6. Reports & Moderation
    // ==========================================
    override fun getAllReports(): Flow<List<PlatformReport>> = flow {
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS]
                .select()
                .decodeList<ReportDto>()
            emit(dtos.map { it.toDomain() })
        } catch (e: Exception) {
            Log.w(TAG, "Reports table query notice: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun submitReport(report: PlatformReport): Result<PlatformReport> = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.currentUser.value
                ?: return@withContext Result.failure(IllegalStateException("Must be logged in to submit a report."))

            val cleanReport = report.copy(
                id = if (report.id.isBlank() || report.id.startsWith("rep-")) UUID.randomUUID().toString() else ensureValidUuid(report.id),
                reporterId = ensureValidUuid(currentUser.id),
                reporterName = currentUser.name.ifBlank { "Customer" },
                reporterEmail = currentUser.email,
                targetId = ensureValidUuid(report.targetId),
                status = ReportStatus.NEW
            )
            if (SupabaseConfig.isConfigured) {
                val dto = ReportDto.fromDomain(cleanReport)
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS].insert(dto)
            }
            Result.success(cleanReport)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit report: ${e.message}", e)
            val isDuplicate = e.message?.contains("duplicate", ignoreCase = true) == true ||
                    e.message?.contains("idx_reports_user_target_pending", ignoreCase = true) == true ||
                    e.message?.contains("23505", ignoreCase = true) == true
            if (isDuplicate) {
                Result.failure(IllegalStateException("You have already submitted a pending report for this item."))
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun updateReportStatus(
        reportId: String,
        status: ReportStatus,
        resolutionNotes: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUser = authRepository.currentUser.value
            if (currentUser?.role != UserRole.SUPER_ADMIN) {
                return@withContext Result.failure(SecurityException("Only Super Admin can update or moderate safety reports."))
            }
            val validReportId = ensureValidUuid(reportId)
            if (SupabaseConfig.isConfigured) {
                val updatePayload = buildJsonObject {
                    put("status", status.name)
                    put("resolution_notes", resolutionNotes ?: "")
                    put("resolved_by", ensureValidUuid(currentUser.id))
                }
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS].update(updatePayload) {
                    filter {
                        eq("id", validReportId)
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update report status: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun resolveReportWithAction(
        reportId: String,
        removeListingId: String?,
        suspendFarmId: String?,
        resolutionNotes: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val currentUser = authRepository.currentUser.value
        if (currentUser?.role != UserRole.SUPER_ADMIN) {
            return@withContext Result.failure(SecurityException("Only Super Admin can resolve safety reports."))
        }
        if (!removeListingId.isNullOrBlank()) {
            suspendGoatListing(removeListingId)
        }
        if (!suspendFarmId.isNullOrBlank()) {
            updateFarmVerification(suspendFarmId, VerificationStatus.SUSPENDED)
        }
        updateReportStatus(reportId, ReportStatus.RESOLVED, resolutionNotes)
    }

    // ==========================================
    // 8. Customers / Users Management
    // ==========================================
    override fun getAllCustomers(): Flow<List<UserProfile>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(emptyList())
            return@flow
        }
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .select()
                .decodeList<ProfileDto>()

            val profiles = dtos.map { it.toDomain() }
            emit(profiles)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: profiles remote query unavailable: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateUserSuspension(userId: String, isSuspended: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(Unit)
        }
        try {
            val validUserId = ensureValidUuid(userId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].update(
                buildJsonObject { put("is_suspended", isSuspended) }
            ) {
                filter {
                    eq("id", validUserId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: user suspension update deferred: ${e.message}")
            Result.success(Unit)
        }
    }

    // ==========================================
    // 9. Notifications
    // ==========================================
    override fun getNotificationsForUser(userId: String): Flow<List<AppNotification>> = callbackFlow {
        if (!SupabaseConfig.isConfigured) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val validUserId = try {
            ensureValidUuid(userId)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: invalid user ID for notifications: ${e.message}")
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val notificationMap = ConcurrentHashMap<String, AppNotification>()

        // 1. Initial snapshot from database
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS]
                .select {
                    filter {
                        eq("user_id", validUserId)
                    }
                }.decodeList<NotificationDto>()
            dtos.forEach { dto ->
                val notif = dto.toDomain()
                notificationMap[notif.id] = notif
            }
            trySend(notificationMap.values.sortedByDescending { it.timestamp })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: initial notifications query notice: ${e.message}")
            trySend(emptyList())
        }

        // 2. Realtime listener for live INSERT/UPDATE/DELETE events
        var channel: RealtimeChannel? = null
        var realtimeJob: Job? = null
        try {
            val channelName = "realtime_notifications_$validUserId"
            channel = SupabaseModule.client.realtime.channel(channelName)
            val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = SupabaseConfig.TABLE_NOTIFICATIONS
                filter("user_id", FilterOperator.EQ, validUserId)
            }

            realtimeJob = launch {
                changeFlow.collect { action ->
                    try {
                        when (action) {
                            is PostgresAction.Insert -> {
                                val dto = action.decodeRecord<NotificationDto>()
                                val newNotif = dto.toDomain()
                                // Deduplicate by database ID
                                if (!notificationMap.containsKey(newNotif.id)) {
                                    notificationMap[newNotif.id] = newNotif
                                    trySend(notificationMap.values.sortedByDescending { it.timestamp })
                                }
                            }
                            is PostgresAction.Update -> {
                                val dto = action.decodeRecord<NotificationDto>()
                                val updatedNotif = dto.toDomain()
                                notificationMap[updatedNotif.id] = updatedNotif
                                trySend(notificationMap.values.sortedByDescending { it.timestamp })
                            }
                            is PostgresAction.Delete -> {
                                val deletedId = action.oldRecord["id"]?.jsonPrimitive?.content
                                if (deletedId != null && notificationMap.containsKey(deletedId)) {
                                    notificationMap.remove(deletedId)
                                    trySend(notificationMap.values.sortedByDescending { it.timestamp })
                                }
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.w(TAG, "Notice: realtime notification processing notice: ${e.message}")
                    }
                }
            }

            channel.subscribe()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: failed to subscribe to realtime notifications: ${e.message}")
        }

        awaitClose {
            realtimeJob?.cancel()
            channel?.let { ch ->
                launch(NonCancellable) {
                    try {
                        ch.unsubscribe()
                        SupabaseModule.client.realtime.removeChannel(ch)
                    } catch (e: Exception) {
                        Log.w(TAG, "Notice: error removing realtime notification channel: ${e.message}")
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun getNotificationsForRole(role: UserRole): Flow<List<AppNotification>> = flow<List<AppNotification>> {
        emit(emptyList())
    }.flowOn(Dispatchers.IO)

    override fun getAllNotifications(): Flow<List<AppNotification>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(emptyList())
            return@flow
        }
        try {
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS]
                .select()
                .decodeList<NotificationDto>()
            val remote = dtos.map { it.toDomain() }
            emit(remote)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: all notifications query notice: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun sendNotification(notification: AppNotification): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val cleanNotification = notification.copy(
                id = ensureValidUuid(notification.id),
                recipientUserId = ensureValidUuid(notification.recipientUserId)
            )
            val dto = NotificationDto.fromDomain(cleanNotification)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].insert(dto)

            // Trigger server-side push notification dispatch securely
            try {
                SupabaseModule.client.postgrest.rpc(
                    "send_push_notification",
                    buildJsonObject {
                        put("p_notification_id", cleanNotification.id)
                        put("p_recipient_user_id", cleanNotification.recipientUserId)
                        put("p_title", cleanNotification.title)
                        put("p_message", cleanNotification.message)
                        put("p_type", cleanNotification.type.name)
                        put("p_reference_id", cleanNotification.referenceId ?: "")
                    }
                )
            } catch (e: Exception) {
                Log.d(TAG, "Notice: push notification RPC or DB trigger notice: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validId = ensureValidUuid(notificationId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].update(
                buildJsonObject { put("is_read", true) }
            ) {
                filter {
                    eq("id", validId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark notification read: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun markAllNotificationsAsRead(userId: String, role: UserRole): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validUserId = ensureValidUuid(userId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].update(
                buildJsonObject { put("is_read", true) }
            ) {
                filter {
                    eq("user_id", validUserId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark all read: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteNotification(notificationId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validId = ensureValidUuid(notificationId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].delete {
                filter {
                    eq("id", validId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete notification: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun clearAllNotifications(userId: String, role: UserRole): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validUserId = ensureValidUuid(userId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS].delete {
                filter {
                    eq("user_id", validUserId)
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear notifications: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun triggerSampleNotification(type: NotificationType): Result<Unit> = withContext(Dispatchers.IO) {
        Result.success(Unit)
    }

    // ==========================================
    // 9. Platform Stats
    // ==========================================
    override fun getPlatformStats(): Flow<PlatformStats> = flow {
        val emptyStats = PlatformStats(
            totalGoats = 0,
            activeFarms = 0,
            pendingFarms = 0,
            suspendedFarms = 0,
            pendingListings = 0,
            totalBookings = 0,
            activeBookings = 0,
            completedBookings = 0,
            totalCustomers = 0,
            totalRevenue = 0.0,
            totalListingFeesCollected = 0.0,
            pendingReports = 0
        )
        if (!SupabaseConfig.isConfigured) {
            emit(emptyStats)
            return@flow
        }
        try {
            val allGoats = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                    .select(Columns.list("id", "is_approved_by_admin", "status", "price"))
                    .decodeList<GoatDto>()
            } catch (_: Exception) { emptyList() }

            val allFarms = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                    .select(Columns.list("id", "status"))
                    .decodeList<FarmDto>()
            } catch (_: Exception) { emptyList() }

            val allBookingsList = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                    .select(Columns.list("id", "status", "total_price"))
                    .decodeList<BookingDto>()
            } catch (_: Exception) { emptyList() }

            val customersCount = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select(Columns.list("id"))
                    .decodeList<ProfileDto>().size
            } catch (_: Exception) { 0 }

            val allReportsList = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REPORTS]
                    .select(Columns.list("id", "status"))
                    .decodeList<ReportDto>()
            } catch (_: Exception) { emptyList() }

            val totalGoats = allGoats.size
            val pendingGoats = allGoats.count { !it.isApprovedByAdmin && it.status.uppercase() != "REJECTED" }
            val activeFarms = allFarms.count { it.status.equals("APPROVED", ignoreCase = true) }
            val pendingFarms = allFarms.count { it.status.equals("PENDING", ignoreCase = true) }
            val suspendedFarms = allFarms.count { it.status.equals("SUSPENDED", ignoreCase = true) }
            val totalBookings = allBookingsList.size
            val activeBookings = allBookingsList.count { it.status.uppercase() in listOf("PENDING", "CONFIRMED") }
            val completedBookings = allBookingsList.count { it.status.uppercase() == "COMPLETED" }
            val pendingReports = allReportsList.count { it.status.uppercase() in listOf("NEW", "PENDING", "UNDER_REVIEW") }
            val totalRevenue = allBookingsList.sumOf { it.totalPrice }
            val totalListingFees = try {
                val paidListingPayments = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS]
                    .select(Columns.list("amount", "payment_status")) {
                        filter {
                            eq("payment_status", "PAID")
                        }
                    }.decodeList<ListingPaymentDto>()
                paidListingPayments.sumOf { it.amount }
            } catch (_: Exception) { 0.0 }

            emit(
                PlatformStats(
                    totalGoats = totalGoats,
                    activeFarms = activeFarms,
                    pendingFarms = pendingFarms,
                    suspendedFarms = suspendedFarms,
                    pendingListings = pendingGoats,
                    totalBookings = totalBookings,
                    activeBookings = activeBookings,
                    completedBookings = completedBookings,
                    totalCustomers = customersCount,
                    totalRevenue = totalRevenue,
                    totalListingFeesCollected = totalListingFees,
                    pendingReports = pendingReports
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emit(emptyStats)
        }
    }.flowOn(Dispatchers.IO)

    // ==========================================
    // 11. Wishlist & Saved Listings
    // ==========================================
    override fun getWishlistForUser(userId: String): Flow<List<WishlistItem>> =
        wishlistRepository.getWishlistForUser(userId)

    override fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean> =
        wishlistRepository.isGoatInWishlist(userId, goatId)

    override suspend fun addToWishlist(userId: String, goatId: String): Result<WishlistItem> =
        wishlistRepository.addToWishlist(userId, goatId)

    override suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit> =
        wishlistRepository.removeFromWishlist(userId, goatId)

    override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> =
        wishlistRepository.toggleWishlist(userId, goatId)

    // ==========================================
    // 12. Listing Payments & Transaction Verification
    // ==========================================
    override fun getListingPaymentsForFarm(farmId: String): Flow<List<ListingPayment>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(emptyList())
            return@flow
        }
        try {
            val payments = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS]
                .select {
                    filter {
                        eq("farm_id", farmId)
                    }
                    order("created_at", Order.DESCENDING)
                }.decodeList<ListingPaymentDto>()
            emit(payments.map { it.toDomain() })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching farm listing payments: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllListingPayments(): Flow<List<ListingPayment>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(emptyList())
            return@flow
        }
        try {
            val payments = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS]
                .select {
                    order("created_at", Order.DESCENDING)
                }.decodeList<ListingPaymentDto>()
            emit(payments.map { it.toDomain() })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching all listing payments: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun initiateListingPayment(goatId: String): Result<ListingPaymentInitiation> = withContext(Dispatchers.IO) {
        // Stage 11K: No online payment. Listing limits are managed manually by Super Admin.
        Result.failure(UnsupportedOperationException("Online payment is disabled. Partner listing limits are managed directly by Super Admin."))
    }

    override suspend fun verifyListingPayment(
        goatId: String,
        orderId: String,
        paymentId: String,
        signature: String,
        amount: Double
    ): Result<PaymentVerificationResult> = withContext(Dispatchers.IO) {
        // Stage 11K: No online payment verification.
        Result.failure(UnsupportedOperationException("Online payment is disabled. Partner listing limits are managed directly by Super Admin."))
    }

    override suspend fun recordPaymentFailure(goatId: String, orderId: String?, error: String): Result<Unit> = withContext(Dispatchers.IO) {
        Result.success(Unit)
    }

    override suspend fun recordPaymentCancellation(goatId: String, orderId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        Result.success(Unit)
    }
}
