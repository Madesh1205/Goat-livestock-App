package com.ammalfarm.adusanthai.data.repository

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.DefaultPlatformData
import com.ammalfarm.adusanthai.data.dto.*
import com.ammalfarm.adusanthai.model.*
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.firstOrNull
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
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
import kotlinx.serialization.json.JsonNull
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
        val currentUser = authRepository.currentUser.value ?: authRepository.checkExistingSession().getOrNull()
        val customerId = currentUser?.id ?: try {
            SupabaseModule.auth.currentUserOrNull()?.id
        } catch (_: Exception) { null }

        if (customerId.isNullOrBlank()) {
            return Result.failure(IllegalStateException("User not authenticated."))
        }
        return bookingRepository.createBooking(goatId, customerId, notes)
    }

    override suspend fun confirmBooking(bookingId: String): Result<Unit> =
        bookingRepository.confirmBooking(bookingId)

    override suspend fun cancelBooking(bookingId: String, reason: String): Result<Unit> =
        bookingRepository.cancelBooking(bookingId, reason)

    override suspend fun completeBooking(bookingId: String): Result<Unit> =
        bookingRepository.completeBooking(bookingId)

    override suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus, reason: String?): Result<Unit> =
        bookingRepository.updateBookingStatus(bookingId, status, reason)

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
    override suspend fun deleteFarm(farmId: String): Result<Unit> =
        farmRepository.deleteFarm(farmId)

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

            // Check for recent unread notifications that have not been delivered locally yet
            val appContext = SupabaseModule.getApplicationContext()
            if (appContext != null) {
                val now = System.currentTimeMillis()
                val recentWindowMillis = 24 * 60 * 60 * 1000L // last 24 hours
                notificationMap.values
                    .filter { !it.isRead && (now - it.timestamp) < recentWindowMillis }
                    .forEach { unreadNotif ->
                        if (!com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.isDelivered(appContext, validUserId, unreadNotif.id)) {
                            if (com.ammalfarm.adusanthai.core.notification.NotificationHelper.showSystemNotification(appContext, unreadNotif)) {
                                com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.markDelivered(appContext, validUserId, unreadNotif.id)
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: initial notifications query notice: ${e.message}")
            trySend(emptyList())
        }

        // 2. Realtime listener for live INSERT/UPDATE/DELETE events
        var channel: RealtimeChannel? = null
        var realtimeJob: Job? = null
        var pollingJob: Job? = null

        try {
            val channelName = "realtime_notifications_$validUserId"
            channel = SupabaseModule.client.realtime.channel(channelName)
            val changeFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = SupabaseConfig.TABLE_NOTIFICATIONS
                filter("user_id", FilterOperator.EQ, validUserId)
            }

            realtimeJob = launch {
                try {
                    changeFlow.collect { action ->
                        try {
                            when (action) {
                                is PostgresAction.Insert -> {
                                    val dto = action.decodeRecord<NotificationDto>()
                                    val newNotif = dto.toDomain()
                                    // Verify user ownership
                                    if (newNotif.recipientUserId.isBlank() || newNotif.recipientUserId == validUserId) {
                                        val appContext = SupabaseModule.getApplicationContext()
                                        if (appContext != null && !com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.isDelivered(appContext, validUserId, newNotif.id)) {
                                            if (com.ammalfarm.adusanthai.core.notification.NotificationHelper.showSystemNotification(appContext, newNotif)) {
                                                com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.markDelivered(appContext, validUserId, newNotif.id)
                                            }
                                        }
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
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Notice: realtime notification changeFlow ended gracefully: ${e.message}")
                }
            }

            try {
                channel.subscribe()
            } catch (subErr: Throwable) {
                if (subErr is CancellationException) throw subErr
                Log.w(TAG, "Notice: Realtime websocket unavailable, falling back to REST polling: ${subErr.message}")
                try {
                    channel.unsubscribe()
                    SupabaseModule.client.realtime.removeChannel(channel)
                } catch (_: Exception) {}
                channel = null
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: realtime notifications not active: ${e.message}")
            try {
                channel?.let {
                    SupabaseModule.client.realtime.removeChannel(it)
                }
            } catch (_: Exception) {}
            channel = null
        }

        // 3. Fallback periodic refresh (every 20s) to keep notifications updated
        pollingJob = launch {
            var pollingIntervalMs = 60_000L
            while (isActive) {
                delay(pollingIntervalMs)
                if (!isActive) break
                if (!com.ammalfarm.adusanthai.core.util.NetworkConnectivityObserver.isCurrentlyOnline()) {
                    continue
                }
                try {
                    val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS]
                        .select {
                            filter {
                                eq("user_id", validUserId)
                            }
                        }.decodeList<NotificationDto>()

                    if (!isActive) break

                    val appContext = SupabaseModule.getApplicationContext()
                    val now = System.currentTimeMillis()
                    val recentWindowMillis = 24 * 60 * 60 * 1000L

                    dtos.forEach { dto ->
                        val notif = dto.toDomain()
                        if (!notificationMap.containsKey(notif.id)) {
                            // Newly discovered notification from polling
                            if (appContext != null && !notif.isRead && (now - notif.timestamp) < recentWindowMillis) {
                                if (!com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.isDelivered(appContext, validUserId, notif.id)) {
                                    if (com.ammalfarm.adusanthai.core.notification.NotificationHelper.showSystemNotification(appContext, notif)) {
                                        com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker.markDelivered(appContext, validUserId, notif.id)
                                    }
                                }
                            }
                        }
                        notificationMap[notif.id] = notif
                    }
                    trySend(notificationMap.values.sortedByDescending { it.timestamp })
                    pollingIntervalMs = 60_000L // Reset backoff on success
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    val msg = e.message ?: ""
                    val isNetworkError = msg.contains("resolve host", ignoreCase = true) ||
                            msg.contains("SocketException", ignoreCase = true) ||
                            msg.contains("Software caused connection abort", ignoreCase = true) ||
                            msg.contains("timeout", ignoreCase = true) ||
                            msg.contains("ConnectException", ignoreCase = true)
                    if (isNetworkError) {
                        Log.d(TAG, "Notice: offline/network retry for notifications: $msg")
                        pollingIntervalMs = (pollingIntervalMs * 2).coerceAtMost(120_000L)
                    } else {
                        Log.w(TAG, "Notice: notification polling check notice: $msg")
                    }
                }
            }
        }

        awaitClose {
            realtimeJob?.cancel()
            pollingJob?.cancel()
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

    override suspend fun updateFcmToken(token: String): Result<Unit> {
        return registerFcmDeviceToken(token, null)
    }

    override suspend fun registerFcmDeviceToken(token: String, deviceId: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (token.isBlank() || !SupabaseConfig.isConfigured) {
                return@withContext Result.success(Unit)
            }
            val authUser = SupabaseModule.auth.currentUserOrNull()
            if (authUser == null) {
                Log.d(TAG, "No authenticated Supabase session; skipping FCM device token registration")
                return@withContext Result.success(Unit)
            }
            val parameters = buildJsonObject {
                put("p_token", token)
                if (!deviceId.isNullOrBlank()) {
                    put("p_device_id", deviceId)
                } else {
                    put("p_device_id", JsonNull)
                }
                put("p_platform", "ANDROID")
            }
            SupabaseModule.client.postgrest.rpc(
                function = "register_fcm_device_token",
                parameters = parameters
            )
            Log.d(TAG, "Successfully registered FCM device token via RPC (deviceId: ${deviceId ?: "null"})")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: register_fcm_device_token RPC failed safely: ${e.message}")
            Result.success(Unit)
        }
    }

    override suspend fun unregisterFcmDeviceToken(token: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (token.isBlank() || !SupabaseConfig.isConfigured) {
                return@withContext Result.success(Unit)
            }
            val parameters = buildJsonObject {
                put("p_token", token)
            }
            SupabaseModule.client.postgrest.rpc(
                function = "unregister_fcm_device_token",
                parameters = parameters
            )
            Log.d(TAG, "Successfully unregistered FCM device token via RPC")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Notice: unregister_fcm_device_token RPC failed safely: ${e.message}")
            Result.success(Unit)
        }
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
            val activeBookings = allBookingsList.count { (it.status ?: "").uppercase() in listOf("PENDING", "CONFIRMED", "RESERVED") }
            val completedBookings = allBookingsList.count { (it.status ?: "").uppercase() in listOf("COMPLETED", "SOLD") }
            val pendingReports = allReportsList.count { it.status.uppercase() in listOf("NEW", "PENDING", "UNDER_REVIEW") }
            val totalRevenue = allBookingsList.sumOf { it.totalPrice ?: 0.0 }
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
    // 12. Listing Payments & Transaction Verification & Receipts
    // ==========================================
    override fun getListingPaymentsForFarm(farmId: String): Flow<List<ListingPayment>> = flow<List<ListingPayment>> {
        val cached = FarmLocalCache.getCachedPaymentsForFarm(farmId)
        if (!SupabaseConfig.isConfigured) {
            emit(cached)
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

            val farm = getFarmById(farmId).firstOrNull()
            val domainPayments = payments.map { dto: ListingPaymentDto ->
                dto.toDomain(farmName = farm?.name ?: "")
            }
            val merged = (domainPayments + cached).distinctBy { it.id }.sortedByDescending { it.createdAt }
            emit(merged)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching farm listing payments: ${e.message}")
            emit(cached)
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllListingPayments(): Flow<List<ListingPayment>> = flow<List<ListingPayment>> {
        val cached = FarmLocalCache.getAllCachedPayments()
        if (!SupabaseConfig.isConfigured) {
            emit(cached)
            return@flow
        }
        try {
            val payments = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS]
                .select {
                    order("created_at", Order.DESCENDING)
                }.decodeList<ListingPaymentDto>()

            val farms = getAllFarms().firstOrNull() ?: emptyList()
            val farmMap = farms.associateBy { it.id }
            val domainPayments = payments.map { dto: ListingPaymentDto ->
                val fName = dto.farmId?.let { farmMap[it]?.name } ?: ""
                dto.toDomain(farmName = fName)
            }
            val merged = (domainPayments + cached).distinctBy { it.id }.sortedByDescending { it.createdAt }
            emit(merged)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Error fetching all listing payments: ${e.message}")
            emit(cached)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun recordFarmApprovalPayment(
        farmId: String,
        amount: Double,
        paymentRef: String?,
        receiptNumber: String?,
        notes: String?
    ): Result<ListingPayment> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farmId)
            val farm = getFarmById(validFarmId).firstOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Farm not found: $farmId"))

            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.failure(
                    IllegalStateException("Supabase is not configured; farm approval was not saved.")
                )
            }
            if (SupabaseModule.auth.currentUserOrNull() == null) {
                return@withContext Result.failure(IllegalStateException("Authentication required"))
            }
            if (amount <= 0.0) {
                return@withContext Result.failure(IllegalArgumentException("Approval payment amount must be greater than zero."))
            }

            val paymentId = UUID.randomUUID().toString()
            val generatedReceipt = receiptNumber?.takeIf { it.isNotBlank() }
                ?: "RCPT-APPR-${System.currentTimeMillis().toString().takeLast(6)}"
            val paymentReference = paymentRef?.trim()?.takeIf { it.isNotBlank() }
                ?: "MANUAL-APPR-${validFarmId.take(6)}-${paymentId.take(6)}"
            val slotsAdded = if (farm.goatListingLimit <= 0) 2 else 0

            val payment = ListingPayment(
                id = paymentId,
                farmId = validFarmId,
                farmName = farm.name,
                amount = amount,
                currency = "INR",
                paymentType = "FARM_APPROVAL",
                slotsAdded = slotsAdded,
                status = PaymentStatus.PAID,
                orderId = paymentReference,
                razorpayPaymentId = null,
                receiptNumber = generatedReceipt,
                paymentMethod = "Manual Payment",
                notes = notes?.takeIf { it.isNotBlank() }
                    ?: "Super Admin partner farm approval payment recorded manually.",
                createdAt = System.currentTimeMillis()
            )

            // One server-side transaction authorizes the Super Admin, approves the farm,
            // preserves any existing non-zero listing quota, and stores the manual receipt.
            val parameters = buildJsonObject {
                put("p_payment_id", paymentId)
                put("p_farm_id", validFarmId)
                put("p_amount", amount)
                put("p_payment_ref", paymentReference)
                put("p_receipt_number", generatedReceipt)
                put("p_notes", payment.notes)
            }
            SupabaseModule.client.postgrest.rpc(
                function = "admin_approve_partner_farm",
                parameters = parameters
            )

            // Cache only after the database transaction succeeds.
            FarmLocalCache.saveListingPayment(payment)
            Log.i(TAG, "Farm ${farm.name} approved and manual receipt ${payment.receiptNumber} persisted.")
            Result.success(payment)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to approve farm and record manual payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun recordQuotaIncreasePayment(
        farmId: String,
        slotsToAdd: Int,
        amount: Double,
        paymentRef: String?,
        receiptNumber: String?,
        notes: String?
    ): Result<ListingPayment> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farmId)
            val farm = getFarmById(validFarmId).firstOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Farm not found: $farmId"))

            if (slotsToAdd <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Slots to add must be greater than 0"))
            }

            val currentLimit = farm.goatListingLimit
            val newLimit = currentLimit + slotsToAdd

            val generatedRcpt = receiptNumber?.takeIf { it.isNotBlank() }
                ?: "RCPT-QUOTA-${System.currentTimeMillis().toString().takeLast(6)}"
            val paymentId = UUID.randomUUID().toString()

            val payment = ListingPayment(
                id = paymentId,
                farmId = validFarmId,
                farmName = farm.name,
                amount = amount,
                currency = "INR",
                paymentType = "ADDITIONAL_QUOTA",
                slotsAdded = slotsToAdd,
                status = PaymentStatus.PAID,
                orderId = paymentRef?.takeIf { it.isNotBlank() } ?: "MANUAL-QUOTA-${validFarmId.take(6)}",
                razorpayPaymentId = paymentRef,
                receiptNumber = generatedRcpt,
                paymentMethod = "Manual Payment",
                notes = notes ?: "Additional $slotsToAdd goat listing slots purchased (Limit: $currentLimit -> $newLimit)",
                createdAt = System.currentTimeMillis()
            )

            // 1. Update listing limit
            updateFarmListingLimit(validFarmId, newLimit)

            // 2. Persist in local cache
            FarmLocalCache.saveListingPayment(payment)

            // 3. Persist in Supabase if configured
            if (SupabaseConfig.isConfigured) {
                try {
                    val authUser = SupabaseModule.auth.currentUserOrNull()
                    val payerId = farm.ownerId.ifBlank { authUser?.id ?: "" }
                    val dto = ListingPaymentDto.fromDomain(payment, payerId = payerId)
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS].insert<ListingPaymentDto>(dto)
                } catch (postgrestErr: Exception) {
                    Log.w(TAG, "Notice: remote listing_payments insert deferred: ${postgrestErr.message}")
                }
            }

            Log.d(TAG, "Recorded quota payment receipt $generatedRcpt for farm ${farm.name} (+$slotsToAdd slots)")
            Result.success(payment)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record quota increase payment: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getPlatformPricing(): Flow<PlatformPricing> =
        com.ammalfarm.adusanthai.core.util.PlatformPricingManager.pricingFlow

    override suspend fun updatePlatformPricing(
        approvalPrice: Double,
        slotPrice: Double
    ): Result<PlatformPricing> = withContext(Dispatchers.IO) {
        com.ammalfarm.adusanthai.core.util.PlatformPricingManager.updatePricing(approvalPrice, slotPrice)
    }

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
