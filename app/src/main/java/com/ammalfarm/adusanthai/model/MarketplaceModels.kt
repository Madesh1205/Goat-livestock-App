package com.ammalfarm.adusanthai.model

import com.ammalfarm.adusanthai.data.dto.ApprovalStatus
import com.ammalfarm.adusanthai.data.dto.AvailabilityStatus

enum class SortOption {
    PRICE_LOW_TO_HIGH,
    PRICE_HIGH_TO_LOW,
    NEWEST,
    POPULAR
}

enum class GoatGender {
    MALE,
    FEMALE,
    OTHER
}

enum class ReportStatus {
    PENDING,
    RESOLVED,
    DISMISSED
}

enum class ReportReason {
    INAPPROPRIATE,
    FRAUD,
    SPAM,
    OTHER
}

enum class NotificationType {
    GENERAL,
    BOOKING,
    FARM,
    ADMIN,
    PAYMENT,
    BOOKING_CREATED,
    BOOKING_CONFIRMED,
    BOOKING_REJECTED,
    BOOKING_CANCELLED,
    RESERVATION_EXPIRY,
    LISTING_UPDATE,
    NEW_BOOKING,
    BOOKING_CANCELLATION,
    LISTING_PAYMENT_SUCCESS,
    LISTING_PAYMENT_FAILED,
    LISTING_APPROVED,
    LISTING_REJECTED,
    FARM_APPROVED,
    FARM_APPROVAL_PAYMENT_CONFIRMED,
    LISTING_QUOTA_INCREASED,
    RECEIPT_AVAILABLE,
    NEW_FARM_APPLICATION,
    NEW_LISTING_PENDING,
    NEW_PAYMENT_RECEIVED,
    NEW_REPORT_SUBMITTED,
    SYSTEM_ALERT
}

data class Goat(
    val id: String = "",
    val goatCode: String = "",
    val farmId: String = "",
    val farmName: String = "",
    val name: String = "",
    val breed: String = "",
    val goatBreed: String = "",
    val ageMonths: Int = 0,
    val weightKg: Double = 0.0,
    val price: Double = 0.0,
    val finalPrice: Double = 0.0,
    val description: String = "",
    val imageUrls: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val goatPhoto: String? = null,
    val gender: String = "Male",
    val healthStatus: String = "Healthy",
    val isAvailable: Boolean = true,
    val isFeatured: Boolean = false,
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val approvalStatus: ApprovalStatus = ApprovalStatus.PENDING,
    val listingFeePaid: Boolean = false,
    val listingFeeAmount: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis()
)

data class Booking(
    val id: String = "",
    val goatId: String = "",
    val goatCode: String = "",
    val customerId: String = "",
    val customerPhone: String = "",
    val farmId: String = "",
    val customerName: String = "",
    val goatName: String = "",
    val goatBreed: String = "",
    val goatPhoto: String? = null,
    val farmName: String = "",
    val amount: Double = 0.0,
    val finalPrice: Double = 0.0,
    val status: String = "PENDING",
    val paymentStatus: String = "PENDING",
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val bookingDate: Long = System.currentTimeMillis(),
    val reservationExpiryDate: Long = System.currentTimeMillis(),
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class WishlistItem(
    val id: String = "",
    val userId: String = "",
    val goatId: String = "",
    val goat: Goat? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class AppNotification(
    val id: String = "",
    val userId: String = "",
    val recipientUserId: String = "",
    val targetRole: UserRole? = null,
    val title: String = "",
    val message: String = "",
    val isRead: Boolean = false,
    val type: String = "GENERAL",
    val notificationType: NotificationType = NotificationType.GENERAL,
    val referenceId: String? = null,
    val deepLinkRoute: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PlatformReport(
    val id: String = "",
    val reporterId: String = "",
    val reporterName: String = "",
    val reporterEmail: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val targetTitle: String = "",
    val reason: String = "",
    val description: String = "",
    val evidencePhotoUrl: String? = null,
    val evidencePhotos: List<String> = emptyList(),
    val status: String = "PENDING",
    val reportStatus: ReportStatus = ReportStatus.PENDING,
    val resolutionNotes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class ListingPayment(
    val id: String = "",
    val farmId: String = "",
    val goatId: String = "",
    val amount: Double = 0.0,
    val status: String = "PENDING",
    val transactionId: String = "",
    val receiptNumber: String = "",
    val slotsAdded: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
)

data class ListingPaymentInitiation(
    val orderId: String = "",
    val amount: Double = 0.0
)

data class PlatformPricing(
    val commissionPercent: Double = 5.0,
    val featuredListingFee: Double = 100.0,
    val approvalPrice: Double = 50.0,
    val slotPrice: Double = 50.0,
    val additionalSlotPrice: Double = 50.0,
    val defaultInitialSlots: Int = 5,
    val currency: String = "INR"
)

data class PlatformStats(
    val totalUsers: Int = 0,
    val totalFarms: Int = 0,
    val totalGoats: Int = 0,
    val totalBookings: Int = 0,
    val totalRevenue: Double = 0.0
)

data class GoatFilterCriteria(
    val searchQuery: String = "",
    val breed: String? = null,
    val goatBreed: String? = null,
    val farmId: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minAgeMonths: Int? = null,
    val maxAgeMonths: Int? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val location: String? = null,
    val gender: String? = null,
    val availability: AvailabilityStatus? = null,
    val sortOption: SortOption = SortOption.NEWEST,
    val sortBy: SortOption = SortOption.NEWEST,
    val verificationStatus: VerificationStatus? = null
)
