package com.example.data.repository

import com.example.model.*
import kotlinx.coroutines.flow.Flow

interface MarketplaceRepository {
    // Auth & Session
    fun getCurrentUser(): Flow<UserProfile?>
    suspend fun registerUser(name: String, email: String, phone: String, role: UserRole, farmName: String? = null): Result<UserProfile>
    suspend fun login(email: String): Result<UserProfile>
    suspend fun logout()

    // Goats (Marketplace)
    fun getApprovedGoats(): Flow<List<Goat>>
    fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>>
    fun getGoatById(id: String): Flow<Goat?>
    fun getGoatsByFarm(farmId: String): Flow<List<Goat>>
    fun getAllGoatsForAdmin(): Flow<List<Goat>>
    fun getAvailableBreeds(): Flow<List<String>>
    suspend fun addGoatListing(goat: Goat): Result<Goat>
    suspend fun updateGoatListing(goat: Goat): Result<Goat>
    suspend fun deleteGoatListing(goatId: String): Result<Unit>
    suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit>
    fun invalidateCache()

    // Bookings
    fun getCustomerBookings(customerId: String): Flow<List<Booking>>
    fun getFarmBookings(farmId: String): Flow<List<Booking>>
    fun getAllBookings(): Flow<List<Booking>>
    suspend fun createBooking(goatId: String, notes: String): Result<Booking>
    suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus): Result<Unit>

    // Farms
    fun getAllFarms(): Flow<List<Farm>>
    fun getFarmById(farmId: String): Flow<Farm?>
    suspend fun registerFarm(farm: Farm): Result<Farm>
    suspend fun updateFarmProfile(farm: Farm): Result<Farm>
    suspend fun updateFarmLogo(farmId: String, logoUrl: String): Result<String>
    suspend fun updateFarmVerification(farmId: String, status: VerificationStatus): Result<Unit>
    suspend fun updateFarmListingLimit(farmId: String, limit: Int): Result<Unit>

    // Reviews & Moderation
    fun getGoatReviews(goatId: String): Flow<List<Review>>
    fun getFarmReviews(farmId: String): Flow<List<Review>>
    fun getAllReviews(): Flow<List<Review>>
    suspend fun addReview(bookingId: String, goatId: String, rating: Int, comment: String, photos: List<String> = emptyList()): Result<Review>
    suspend fun addReview(goatId: String, rating: Int, comment: String): Result<Review>
    suspend fun deleteReview(reviewId: String): Result<Unit>
    suspend fun reportReview(reviewId: String, reason: String): Result<Unit>
    suspend fun dismissReviewReport(reviewId: String): Result<Unit>
    suspend fun hideReview(reviewId: String): Result<Unit>
    suspend fun restoreReview(reviewId: String): Result<Unit>

    // Reports & Moderation
    fun getAllReports(): Flow<List<PlatformReport>>
    suspend fun submitReport(report: PlatformReport): Result<PlatformReport>
    suspend fun updateReportStatus(reportId: String, status: ReportStatus, resolutionNotes: String? = null): Result<Unit>
    suspend fun resolveReportWithAction(reportId: String, removeListingId: String?, suspendFarmId: String?, resolutionNotes: String): Result<Unit>

    // Customers / Users Management
    fun getAllCustomers(): Flow<List<UserProfile>>
    suspend fun updateUserSuspension(userId: String, isSuspended: Boolean): Result<Unit>

    // Goats Moderation Actions
    suspend fun suspendGoatListing(goatId: String): Result<Unit>
    suspend fun restoreGoatListing(goatId: String): Result<Unit>

    // Notifications
    fun getNotificationsForUser(userId: String): Flow<List<AppNotification>>
    fun getNotificationsForRole(role: UserRole): Flow<List<AppNotification>>
    fun getAllNotifications(): Flow<List<AppNotification>>
    suspend fun sendNotification(notification: AppNotification): Result<Unit>
    suspend fun markNotificationAsRead(notificationId: String): Result<Unit>
    suspend fun markAllNotificationsAsRead(userId: String, role: UserRole): Result<Unit>
    suspend fun deleteNotification(notificationId: String): Result<Unit>
    suspend fun clearAllNotifications(userId: String, role: UserRole): Result<Unit>
    suspend fun triggerSampleNotification(type: NotificationType): Result<Unit>

    // Wishlist
    fun getWishlistForUser(userId: String): Flow<List<WishlistItem>>
    fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean>
    suspend fun addToWishlist(userId: String, goatId: String): Result<WishlistItem>
    suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit>
    suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean>

    // Payments & Listing Fees
    fun getListingPaymentsForFarm(farmId: String): Flow<List<ListingPayment>>
    fun getAllListingPayments(): Flow<List<ListingPayment>>
    suspend fun initiateListingPayment(goatId: String): Result<ListingPaymentInitiation>
    suspend fun verifyListingPayment(goatId: String, orderId: String, paymentId: String, signature: String, amount: Double = 100.0): Result<PaymentVerificationResult>
    suspend fun recordPaymentFailure(goatId: String, orderId: String?, error: String): Result<Unit>
    suspend fun recordPaymentCancellation(goatId: String, orderId: String?): Result<Unit>

    // Platform Stats
    fun getPlatformStats(): Flow<PlatformStats>
}
