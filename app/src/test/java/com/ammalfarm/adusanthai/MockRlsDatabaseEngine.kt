package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import java.util.UUID

/**
 * MOCK DATABASE ENGINE SIMULATING THE POSTGRESQL RLS & TRIGGER POLICIES
 * Shared across Stage 12A and Stage 5B test suites.
 */
class MockRlsDatabaseEngine {
    private val profiles = mutableMapOf<String, ProfileDto>()
    private val farms = mutableMapOf<String, Farm>()
    private val goats = mutableMapOf<String, GoatDto>()
    private val bookings = mutableMapOf<String, BookingDto>()
    private val reports = mutableMapOf<String, ReportDto>()
    private val wishlist = mutableMapOf<String, MutableSet<String>>() // userId -> Set<goatId>
    private val notifications = mutableMapOf<String, MutableList<String>>() // userId -> List<message>
    private val goatImages = mutableMapOf<String, MutableList<String>>() // goatId -> List<imageUrl>
    private val storageObjects = mutableMapOf<String, MutableSet<String>>() // bucket -> Set<storagePath>
    private val storageCleanupQueue = mutableListOf<StorageQueueItem>()

    data class StorageQueueItem(
        val id: String = UUID.randomUUID().toString(),
        val goatId: String,
        val farmId: String?,
        val storagePath: String,
        val bucketName: String = "goat-images",
        var status: String = "PENDING",
        var retryCount: Int = 0,
        var errorMessage: String? = null
    )

    data class GoatDeletionResult(
        val success: Boolean,
        val goatId: String,
        val farmId: String,
        val deletedImages: List<String>,
        val historicalBookingsPreserved: Int
    )

    data class BookingDto(
        val id: String,
        val goatId: String,
        val farmId: String,
        val customerId: String?,
        val totalPrice: Double,
        val status: String,
        val customerNotes: String? = null,
        val bookingDate: Long = System.currentTimeMillis(),
        val holdExpiresAt: Long = System.currentTimeMillis() + 24 * 60 * 60 * 1000L
    )

    data class ReportDto(
        val id: String,
        val reporterId: String,
        val reason: String,
        var status: String
    )

    fun createUser(name: String, email: String, role: UserRole, phone: String, id: String = UUID.randomUUID().toString()): UserProfile {
        val dto = ProfileDto(
            id = id,
            email = email,
            fullName = name,
            phone = phone,
            role = role.name,
            farmId = null,
            isSuspended = false
        )
        profiles[id] = dto
        return UserProfile(id = id, email = email, name = name, phone = phone, role = role)
    }

    fun createFarm(
        farmId: String,
        ownerId: String,
        name: String,
        status: VerificationStatus,
        isAmmalOwnFarm: Boolean,
        quota: Int
    ): Farm {
        val farm = Farm(
            id = farmId,
            name = name,
            ownerId = ownerId,
            ownerName = "Breeder Name",
            location = "Vellore, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9876543210",
            email = "farm@example.com",
            description = "Healthy farm",
            verificationStatus = status,
            isAmmalOwnFarm = isAmmalOwnFarm,
            goatListingLimit = quota
        )
        farms[farmId] = farm
        val p = profiles[ownerId]
        if (p != null && p.role == "FARM_ADMIN") {
            profiles[ownerId] = p.copy(farmId = farmId)
        }
        return farm
    }

    fun createGoat(
        id: String,
        farmId: String,
        name: String,
        price: Double,
        isApproved: Boolean,
        status: String = "AVAILABLE"
    ): Boolean {
        goats[id] = GoatDto(
            id = id,
            farmId = farmId,
            name = name,
            price = price,
            isApprovedByAdmin = isApproved,
            status = status
        )
        return true
    }

    fun createBooking(goatId: String, farmId: String, customerId: String, price: Double): String {
        val id = UUID.randomUUID().toString()
        bookings[id] = BookingDto(id, goatId, farmId, customerId, price, "PENDING")
        return id
    }

    fun getBookingById(bookingId: String): BookingDto? = bookings[bookingId]

    fun updateGoatStatus(goatId: String, status: String) {
        val goat = goats[goatId] ?: return
        goats[goatId] = goat.copy(status = status)
    }

    fun updateGoatPrice(goatId: String, price: Double) {
        val goat = goats[goatId] ?: return
        goats[goatId] = goat.copy(price = price)
    }

    fun createBookingHold(caller: UserProfile, goatId: String, notes: String? = null): Result<String> {
        val goat = goats[goatId] ?: return Result.failure(IllegalArgumentException("Goat not found"))
        if (!goat.isApprovedByAdmin) {
            return Result.failure(IllegalStateException("Goat is not approved for marketplace listing"))
        }
        if (goat.status != "AVAILABLE") {
            return Result.failure(IllegalStateException("Goat is not available for booking (status: ${goat.status})"))
        }
        val farm = farms[goat.farmId] ?: return Result.failure(IllegalArgumentException("Farm not found"))

        // Farm admin cannot book own farm goats
        if (caller.role == UserRole.FARM_ADMIN && (farm.ownerId == caller.id || farm.id == caller.farmId)) {
            return Result.failure(SecurityException("You cannot book goats listed by your own farm."))
        }

        // Active duplicate check
        val hasActive = bookings.values.any { it.goatId == goatId && it.status in listOf("PENDING", "RESERVED") }
        if (hasActive) {
            return Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
        }

        val bookingId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val booking = BookingDto(
            id = bookingId,
            goatId = goatId,
            farmId = goat.farmId,
            customerId = caller.id,
            totalPrice = goat.price,
            status = "RESERVED",
            customerNotes = notes,
            bookingDate = now,
            holdExpiresAt = now + 24 * 60 * 60 * 1000L
        )
        bookings[bookingId] = booking
        goats[goatId] = goat.copy(status = "RESERVED")
        return Result.success(bookingId)
    }

    fun createReport(id: String, reporterId: String, reason: String, status: String) {
        reports[id] = ReportDto(id, reporterId, reason, status)
    }

    fun evaluateProfileSelect(callerId: String?, targetProfileId: String): Boolean {
        if (callerId == null) {
            val targetDto = profiles[targetProfileId] ?: return false
            return farms.values.any { it.ownerId == targetDto.id && it.verificationStatus == VerificationStatus.APPROVED }
        }
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        if (callerId == targetProfileId) return true

        if (caller.role == "FARM_ADMIN") {
            return bookings.values.any { it.customerId == targetProfileId && it.farmId == caller.farmId }
        }

        return farms.values.any { it.ownerId == targetProfileId && it.verificationStatus == VerificationStatus.APPROVED }
    }

    fun evaluateProfileUpdate(
        callerId: String,
        targetProfileId: String,
        newRole: String,
        newFarmId: String?,
        newIsSuspended: Boolean
    ): ProfileDto {
        val caller = profiles[callerId] ?: throw SecurityException("Unauthorized")
        val target = profiles[targetProfileId] ?: throw NoSuchElementException()

        if (caller.role == "SUPER_ADMIN") {
            val updated = target.copy(role = newRole, farmId = newFarmId, isSuspended = newIsSuspended)
            profiles[targetProfileId] = updated
            return updated
        }

        // enforce_profile_security_guard trigger:
        val safeRole = target.role
        val safeSuspended = target.isSuspended
        val safeFarmId = if (target.role == "CUSTOMER") null else target.farmId

        val updated = target.copy(role = safeRole, farmId = safeFarmId, isSuspended = safeSuspended)
        profiles[targetProfileId] = updated
        return updated
    }

    fun evaluateFarmSelect(callerId: String?, farmId: String): Boolean {
        val farm = farms[farmId] ?: return false
        if (farm.verificationStatus == VerificationStatus.APPROVED) return true
        if (callerId == null) return false
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        return farm.ownerId == callerId
    }

    fun evaluateFarmUpdate(
        callerId: String,
        farmId: String,
        attemptedStatus: VerificationStatus,
        attemptedIsAmmal: Boolean,
        attemptedQuota: Int,
        attemptedOwnerId: String
    ): Farm {
        val caller = profiles[callerId] ?: throw SecurityException("Unauthorized")
        val farm = farms[farmId] ?: throw NoSuchElementException()

        if (caller.role == "SUPER_ADMIN") {
            val updated = farm.copy(
                verificationStatus = attemptedStatus,
                isAmmalOwnFarm = attemptedIsAmmal,
                goatListingLimit = attemptedQuota,
                ownerId = attemptedOwnerId
            )
            farms[farmId] = updated
            return updated
        }

        // tr_enforce_farm_metadata_integrity trigger:
        val safeFarm = farm.copy(
            verificationStatus = farm.verificationStatus,
            isAmmalOwnFarm = farm.isAmmalOwnFarm,
            goatListingLimit = farm.goatListingLimit,
            ownerId = farm.ownerId
        )
        farms[farmId] = safeFarm
        return safeFarm
    }

    fun evaluateGoatInsert(callerId: String, targetFarmId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        val farm = farms[targetFarmId] ?: return false
        return caller.role == "FARM_ADMIN" && farm.ownerId == callerId && farm.verificationStatus == VerificationStatus.APPROVED
    }

    fun evaluateGoatUpdate(callerId: String, goatId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        val goat = goats[goatId] ?: return false
        val farm = farms[goat.farmId] ?: return false
        return farm.ownerId == callerId
    }

    fun evaluateGoatQuotaInsert(targetFarmId: String, quota: Int, currentCount: Int): Boolean {
        val isAmmalFarm = (targetFarmId == "00000000-0000-0000-0000-000000000001") ||
                (farms[targetFarmId]?.isAmmalOwnFarm == true)
        if (isAmmalFarm) return true
        return currentCount < quota
    }

    fun evaluateBookingUpdate(
        callerId: String,
        bookingId: String,
        attemptedPrice: Double,
        attemptedStatus: String
    ): BookingDto {
        val caller = profiles[callerId] ?: throw SecurityException("Unauthorized")
        val booking = bookings[bookingId] ?: throw NoSuchElementException()

        if (caller.role == "SUPER_ADMIN") {
            val updated = booking.copy(totalPrice = attemptedPrice, status = attemptedStatus)
            bookings[bookingId] = updated
            return updated
        }

        // enforce_booking_price_snapshot trigger:
        // Price is immutable, and customer can only cancel pending/reserved booking
        val safeStatus = if (caller.role == "CUSTOMER") {
            if (attemptedStatus == "CANCELLED") "CANCELLED" else booking.status
        } else {
            attemptedStatus
        }

        val updated = booking.copy(totalPrice = booking.totalPrice, status = safeStatus)
        bookings[bookingId] = updated
        return updated
    }

    // Server-side booking rules:
    // CUSTOMER: can book from any farm
    // FARM_ADMIN: can book other farms, but cannot book goats from their own farm
    // SUPER_ADMIN: unrestricted
    fun canBookGoat(
        callerId: String,
        callerRole: UserRole,
        callerFarmId: String?,
        goatFarmId: String,
        farmOwnerId: String
    ): Boolean {
        if (callerRole == UserRole.SUPER_ADMIN) return true
        if (callerRole == UserRole.CUSTOMER) return true
        if (callerRole == UserRole.FARM_ADMIN) {
            // Cannot book if it's the admin's own farm
            if (callerFarmId != null && callerFarmId == goatFarmId) return false
            if (callerId == farmOwnerId) return false
            return true
        }
        return false
    }

    // Goat images visibility rule:
    // Image metadata must only be visible according to the associated goat's permitted visibility
    // No USING (true)
    fun canViewGoatImage(callerId: String?, isGoatApproved: Boolean, goatOwnerId: String): Boolean {
        if (isGoatApproved) return true
        if (callerId == null) return false
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        return callerId == goatOwnerId
    }

    fun evaluateWishlistSelect(callerId: String, wishlistOwnerId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        return caller.role == "SUPER_ADMIN" || callerId == wishlistOwnerId
    }

    fun evaluateNotificationSelect(callerId: String, notificationUserId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        return caller.role == "SUPER_ADMIN" || callerId == notificationUserId
    }

    fun evaluateReviewInsert(callerId: String, bookingStatus: String): Boolean {
        val caller = profiles[callerId] ?: return false
        if (caller.role == "SUPER_ADMIN") return true
        return bookingStatus in listOf("COMPLETED", "CONFIRMED")
    }

    fun evaluateReportUpdate(callerId: String, reportId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        return caller.role == "SUPER_ADMIN"
    }

    fun evaluateBreedModify(callerId: String): Boolean {
        val caller = profiles[callerId] ?: return false
        return caller.role == "SUPER_ADMIN"
    }

    // =========================================================================
    // STAGE 6A: SECURE ACCOUNT DELETION SIMULATION (delete_user_account RPC)
    // =========================================================================

    fun addWishlistItem(userId: String, goatId: String) {
        wishlist.getOrPut(userId) { mutableSetOf() }.add(goatId)
    }

    fun getWishlistForUser(userId: String): Set<String> {
        return wishlist[userId] ?: emptySet()
    }

    fun addNotification(userId: String, message: String) {
        notifications.getOrPut(userId) { mutableListOf() }.add(message)
    }

    fun getNotificationsForUser(userId: String): List<String> {
        return notifications[userId] ?: emptyList()
    }

    fun getProfile(userId: String): ProfileDto? = profiles[userId]
    fun getFarm(farmId: String): Farm? = farms[farmId]
    fun getGoat(goatId: String): GoatDto? = goats[goatId]
    fun getAllBookings(): List<BookingDto> = bookings.values.toList()
    fun getBookingsForFarm(farmId: String): List<BookingDto> = bookings.values.filter { it.farmId == farmId }

    /**
     * Simulates the server-authoritative delete_user_account() stored procedure.
     * Uses auth.uid() = callerId. If caller attempts to delete another user (targetUserId != callerId),
     * the operation is rejected (unless caller is Super Admin).
     */
    fun executeAccountDeletion(callerId: String, targetUserId: String = callerId): Boolean {
        // Security rule: A user can NEVER delete another user's account
        val caller = profiles[callerId] ?: return false
        if (targetUserId != callerId && caller.role != "SUPER_ADMIN") {
            return false // Unauthorized! Cannot delete another user's account
        }

        val target = profiles[targetUserId] ?: return false

        // Safety check: Cannot delete Central Ammal Farm owner account without transfer
        val ownsAmmalFarm = farms.values.any { it.ownerId == targetUserId && it.isAmmalOwnFarm }
        if (ownsAmmalFarm) {
            throw IllegalStateException("The owner account of the central Ammal Farm cannot be deleted. Transfer ownership first.")
        }

        // 1. If user is FARM_ADMIN: safely remove farms and listings
        if (target.role == "FARM_ADMIN") {
            val userFarms = farms.values.filter { it.ownerId == targetUserId }
            for (farm in userFarms) {
                // Remove goats belonging to this farm
                val farmGoatIds = goats.values.filter { it.farmId == farm.id }.map { it.id }
                farmGoatIds.forEach { goats.remove(it) }

                // Cancel pending bookings on this farm
                bookings.values.filter { it.farmId == farm.id && it.status in listOf("PENDING", "RESERVED") }.forEach { b ->
                    bookings[b.id] = b.copy(status = "CANCELLED")
                }

                // Remove farm record completely
                farms.remove(farm.id)
            }
        }

        // 2. Bookings made by this user as customer:
        // Cancel active holds and return goat to AVAILABLE
        bookings.values.filter { it.customerId == targetUserId && it.status in listOf("PENDING", "RESERVED") }.forEach { b ->
            val goat = goats[b.goatId]
            if (goat != null && goat.status == "RESERVED") {
                goats[b.goatId] = goat.copy(status = "AVAILABLE")
            }
            bookings[b.id] = b.copy(status = "CANCELLED", customerId = null, customerNotes = "[Account deleted]")
        }

        // Anonymize completed, confirmed, or expired past bookings (preserves transaction history)
        bookings.values.filter { it.customerId == targetUserId }.forEach { b ->
            bookings[b.id] = b.copy(customerId = null, customerNotes = null)
        }

        // 3. Remove wishlist items
        wishlist.remove(targetUserId)

        // 4. Remove notifications
        notifications.remove(targetUserId)

        // 5. Remove pending reports filed by user
        reports.values.filter { it.reporterId == targetUserId && it.status == "PENDING" }.forEach { r ->
            reports.remove(r.id)
        }

        // 6. Delete profile (simulates auth.users cascade delete)
        profiles.remove(targetUserId)

        return true
    }

    // =========================================================================
    // STAGE 6B: SECURE GOAT DELETION & STORAGE CLEANUP SIMULATION
    // =========================================================================

    fun createGoatWithImages(
        id: String,
        farmId: String,
        name: String,
        price: Double,
        isApproved: Boolean,
        images: List<String>,
        status: String = "AVAILABLE"
    ): Boolean {
        createGoat(id, farmId, name, price, isApproved, status)
        goatImages[id] = images.toMutableList()
        images.forEach { img ->
            val cleanPath = cleanStoragePath(img)
            addStorageObject("goat-images", cleanPath)
        }
        return true
    }

    fun addStorageObject(bucket: String, path: String) {
        val set = storageObjects.getOrPut(bucket) { mutableSetOf() }
        set.add(path)
    }

    fun getStorageObjects(bucket: String): Set<String> {
        return storageObjects[bucket]?.toSet() ?: emptySet()
    }

    fun getGoatImages(goatId: String): List<String> {
        return goatImages[goatId]?.toList() ?: emptyList()
    }

    fun getStorageCleanupQueue(): List<StorageQueueItem> {
        return storageCleanupQueue.toList()
    }

    fun addToWishlist(userId: String, goatId: String) {
        wishlist.getOrPut(userId) { mutableSetOf() }.add(goatId)
    }

    fun getWishlist(userId: String): Set<String> {
        return wishlist[userId]?.toSet() ?: emptySet()
    }

    fun createBookingWithStatus(
        goatId: String,
        farmId: String,
        customerId: String,
        price: Double,
        status: String
    ): String {
        val id = UUID.randomUUID().toString()
        bookings[id] = BookingDto(id, goatId, farmId, customerId, price, status)
        return id
    }

    fun getBooking(bookingId: String): BookingDto? {
        return bookings[bookingId]
    }

    // =========================================================================
    // STAGE 6C: DEFERRED GOAT IMAGE UPLOAD SIMULATION
    // =========================================================================

    data class DeferredImageUploadResult(
        val success: Boolean,
        val goatId: String? = null,
        val uploadedStoragePaths: List<String> = emptyList(),
        val removedStoragePaths: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    fun executeAddGoatDeferredUpload(
        callerId: String,
        targetFarmId: String,
        goat: Goat,
        simulatedUploadFailIndex: Int = -1,
        simulatedDbFail: Boolean = false
    ): DeferredImageUploadResult {
        // 1. RLS / Auth Check
        val caller = profiles[callerId] ?: return DeferredImageUploadResult(false, errorMessage = "Caller not found")
        val farm = farms[targetFarmId] ?: return DeferredImageUploadResult(false, errorMessage = "Farm not found")
        if (caller.role != "SUPER_ADMIN" && farm.ownerId != callerId) {
            return DeferredImageUploadResult(false, errorMessage = "Unauthorized: FARM_ADMIN can only add goats for their own farm")
        }

        // 2. Input Validation
        if (goat.breed.isBlank()) {
            return DeferredImageUploadResult(false, errorMessage = "Goat breed is required")
        }
        if (goat.price <= 0.0) {
            return DeferredImageUploadResult(false, errorMessage = "Price must be greater than zero")
        }
        if (goat.discountPercentage < 0.0 || goat.discountPercentage > 100.0) {
            return DeferredImageUploadResult(false, errorMessage = "Discount percentage must be between 0 and 100")
        }

        // 3. Process deferred uploads for local URIs
        val newlyUploadedPaths = mutableListOf<String>()
        val finalPhotos = mutableListOf<String>()

        for ((idx, photo) in goat.photos.withIndex()) {
            if (isLocalUri(photo)) {
                if (idx == simulatedUploadFailIndex) {
                    // Simulated upload failure -> rollback partial uploads
                    newlyUploadedPaths.forEach { storageObjects["goat-images"]?.remove(it) }
                    return DeferredImageUploadResult(false, errorMessage = "Storage upload failed at index $idx")
                }
                val storagePath = "farm/$targetFarmId/goat/${goat.id}/photo_${idx + 1}.jpg"
                addStorageObject("goat-images", storagePath)
                newlyUploadedPaths.add(storagePath)
                finalPhotos.add("https://xyz.supabase.co/storage/v1/object/public/goat-images/$storagePath")
            } else {
                finalPhotos.add(photo)
            }
        }

        // 4. Database Insert
        if (simulatedDbFail) {
            // Simulated DB failure -> rollback partial uploads
            newlyUploadedPaths.forEach { storageObjects["goat-images"]?.remove(it) }
            return DeferredImageUploadResult(false, errorMessage = "Database insertion failed")
        }

        val goatDto = GoatDto.fromDomain(goat.copy(photos = finalPhotos)).copy(
            farmId = targetFarmId
        )
        goats[goat.id] = goatDto
        goatImages[goat.id] = finalPhotos.toMutableList()

        return DeferredImageUploadResult(
            success = true,
            goatId = goat.id,
            uploadedStoragePaths = newlyUploadedPaths
        )
    }

    fun executeEditGoatDeferredUpload(
        callerId: String,
        goat: Goat,
        simulatedUploadFailIndex: Int = -1,
        simulatedDbFail: Boolean = false
    ): DeferredImageUploadResult {
        // 1. Check existing goat
        val existingGoat = goats[goat.id] ?: return DeferredImageUploadResult(false, errorMessage = "Goat not found")

        // 2. RLS / Auth Check
        val caller = profiles[callerId] ?: return DeferredImageUploadResult(false, errorMessage = "Caller not found")
        val farm = farms[existingGoat.farmId] ?: return DeferredImageUploadResult(false, errorMessage = "Farm not found")
        if (caller.role != "SUPER_ADMIN" && farm.ownerId != callerId) {
            return DeferredImageUploadResult(false, errorMessage = "Unauthorized: FARM_ADMIN can only edit goats for their own farm")
        }

        // 3. Input Validation
        if (goat.breed.isBlank()) {
            return DeferredImageUploadResult(false, errorMessage = "Goat breed is required")
        }
        if (goat.price <= 0.0) {
            return DeferredImageUploadResult(false, errorMessage = "Price must be greater than zero")
        }
        if (goat.discountPercentage < 0.0 || goat.discountPercentage > 100.0) {
            return DeferredImageUploadResult(false, errorMessage = "Discount percentage must be between 0 and 100")
        }

        val existingPhotos = goatImages[goat.id] ?: emptyList()

        // 4. Process new local URIs
        val newlyUploadedPaths = mutableListOf<String>()
        val finalPhotos = mutableListOf<String>()

        for ((idx, photo) in goat.photos.withIndex()) {
            if (isLocalUri(photo)) {
                if (idx == simulatedUploadFailIndex) {
                    // Simulated upload failure -> rollback newly uploaded files only, keep existing images
                    newlyUploadedPaths.forEach { storageObjects["goat-images"]?.remove(it) }
                    return DeferredImageUploadResult(false, errorMessage = "Storage upload failed at index $idx")
                }
                val storagePath = "farm/${existingGoat.farmId}/goat/${goat.id}/photo_${idx + 1}_${UUID.randomUUID().toString().take(4)}.jpg"
                addStorageObject("goat-images", storagePath)
                newlyUploadedPaths.add(storagePath)
                finalPhotos.add("https://xyz.supabase.co/storage/v1/object/public/goat-images/$storagePath")
            } else {
                finalPhotos.add(photo)
            }
        }

        // 5. Database Update
        if (simulatedDbFail) {
            // Simulated DB failure -> rollback newly uploaded files only
            newlyUploadedPaths.forEach { storageObjects["goat-images"]?.remove(it) }
            return DeferredImageUploadResult(false, errorMessage = "Database update failed")
        }

        val updatedDto = existingGoat.copy(
            name = goat.name,
            breedName = goat.breed,
            gender = goat.gender.name,
            ageMonths = goat.ageMonths,
            weightKg = goat.weightKg,
            purpose = goat.purpose.name,
            description = goat.description,
            price = goat.price,
            discountPercentage = goat.discountPercentage,
            status = goat.availabilityStatus.name,
            isApprovedByAdmin = goat.approvalStatus == ApprovalStatus.APPROVED
        )
        goats[goat.id] = updatedDto
        goatImages[goat.id] = finalPhotos.toMutableList()

        // 6. Delete old removed Storage files ONLY AFTER successful DB update
        val finalStoragePaths = finalPhotos.map { cleanStoragePath(it) }.toSet()
        val removedStoragePaths = mutableListOf<String>()

        for (oldPhoto in existingPhotos) {
            val oldCleanPath = cleanStoragePath(oldPhoto)
            if (oldCleanPath.isNotBlank() && !finalStoragePaths.contains(oldCleanPath) && !isProtectedPath(oldCleanPath)) {
                storageObjects["goat-images"]?.remove(oldCleanPath)
                removedStoragePaths.add(oldCleanPath)
            }
        }

        return DeferredImageUploadResult(
            success = true,
            goatId = goat.id,
            uploadedStoragePaths = newlyUploadedPaths,
            removedStoragePaths = removedStoragePaths
        )
    }

    private fun isLocalUri(pathOrUrl: String): Boolean {
        if (pathOrUrl.isBlank()) return false
        val lower = pathOrUrl.lowercase().trim()
        return lower.startsWith("content://") ||
               lower.startsWith("file://") ||
               lower.startsWith("/data/") ||
               lower.startsWith("/storage/") ||
               lower.startsWith("android.resource://")
    }

    private fun isProtectedPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("farm_logo") || lower.contains("farm_banner") || lower.contains("/logo") || lower.contains("/banner")
    }

    private fun cleanStoragePath(rawPathOrUrl: String): String {
        val marker = "/storage/v1/object/public/goat-images/"
        return if (rawPathOrUrl.contains(marker)) {
            rawPathOrUrl.substringAfter(marker).trimStart('/')
        } else {
            rawPathOrUrl.trimStart('/')
        }
    }

    fun executeGoatDeletion(callerId: String, goatId: String): GoatDeletionResult {
        val caller = profiles[callerId] ?: throw SecurityException("Caller profile not found.")
        val goat = goats[goatId] ?: throw NoSuchElementException("Goat listing $goatId not found.")
        val farm = farms[goat.farmId] ?: throw NoSuchElementException("Farm ${goat.farmId} not found.")

        // 1. Authorization: Only owning Farm Admin or Super Admin
        if (caller.role != "SUPER_ADMIN" && (caller.role != "FARM_ADMIN" || farm.ownerId != callerId)) {
            if (caller.role == "CUSTOMER") {
                throw SecurityException("Customers cannot delete goat listings")
            }
            throw SecurityException("Farm Admin cannot delete another farm's goat")
        }

        // 2. Booking Safety: Cannot delete goat with active reservation or booking
        val activeBookings = bookings.values.filter {
            it.goatId == goatId && it.status in listOf("PENDING", "RESERVED", "CONFIRMED")
        }
        if (activeBookings.isNotEmpty()) {
            throw IllegalStateException("Cannot delete goat listing with active reservations or bookings (${activeBookings.size} active). Complete or cancel active reservations first.")
        }

        // 3. Historical Booking Preservation: Preserve booking records by setting goatId to empty/null
        val historicalBookings = bookings.values.filter { it.goatId == goatId }
        val historicalCount = historicalBookings.size
        historicalBookings.forEach { b ->
            bookings[b.id] = b.copy(
                goatId = "", // Preserves customer, farm, financial amount, status, notes
                customerNotes = "[Historical listing for ${goat.name ?: "Goat"}]"
            )
        }

        // 4. Retrieve associated image paths BEFORE deleting database records
        val imagesToDelete = goatImages[goatId]?.toList() ?: emptyList()

        // 5. Enqueue in storage cleanup queue (for atomicity / retryability)
        imagesToDelete.forEach { img ->
            val cleanPath = cleanStoragePath(img)
            // Safety guard: do not enqueue or delete farm logos/banners
            if (!cleanPath.contains("farm_logo") && !cleanPath.contains("farm_banner") && !cleanPath.contains("/logo") && !cleanPath.contains("/banner")) {
                storageCleanupQueue.add(
                    StorageQueueItem(
                        goatId = goatId,
                        farmId = goat.farmId,
                        storagePath = cleanPath,
                        status = "PENDING"
                    )
                )
            }
        }

        // 6. Database records cleanup
        // Remove from wishlists
        wishlist.values.forEach { set -> set.remove(goatId) }

        // Remove from reports
        reports.values.filter { it.reason.contains(goatId) && it.status == "PENDING" }.forEach { r ->
            reports.remove(r.id)
        }

        // Delete goat_images table rows
        goatImages.remove(goatId)

        // Delete goat record
        goats.remove(goatId)

        // 7. Storage file deletion
        val bucketFiles = storageObjects["goat-images"]
        imagesToDelete.forEach { img ->
            val cleanPath = cleanStoragePath(img)
            // Safety guard: NEVER delete farm logos, farm banners, or files from another goat
            if (!cleanPath.contains("farm_logo") && !cleanPath.contains("farm_banner") && !cleanPath.contains("/logo") && !cleanPath.contains("/banner")) {
                bucketFiles?.remove(cleanPath)
                // Mark queue item completed
                storageCleanupQueue.firstOrNull { it.goatId == goatId && it.storagePath == cleanPath }?.status = "COMPLETED"
            }
        }

        return GoatDeletionResult(
            success = true,
            goatId = goatId,
            farmId = goat.farmId,
            deletedImages = imagesToDelete,
            historicalBookingsPreserved = historicalCount
        )
    }

    fun retryPendingStorageCleanups(): Int {
        val pending = storageCleanupQueue.filter { it.status == "PENDING" || it.status == "FAILED" }
        var cleaned = 0
        pending.forEach { item ->
            storageObjects[item.bucketName]?.remove(item.storagePath)
            item.status = "COMPLETED"
            cleaned++
        }
        return cleaned
    }
}
