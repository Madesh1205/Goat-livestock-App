package com.ammalfarm.adusanthai.model

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    PENDING_APPROVAL,
    DRAFT,
    SUSPENDED;

    companion object {
        fun fromString(value: String?): ApprovalStatus {
            if (value == null) return PENDING
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

object GoatGender {
    const val MALE = "Male"
    const val FEMALE = "Female"
    val entries = listOf(MALE, FEMALE)

    fun valueOf(value: String): String = if (value.equals("FEMALE", ignoreCase = true)) FEMALE else MALE
}

object GoatPurpose {
    const val BREEDING = "Breeding"
    const val MEAT = "Meat"
    const val MILK = "Milk"
    const val DAIRY = "Dairy"
    val entries = listOf(BREEDING, MEAT, MILK, DAIRY)

    fun valueOf(value: String): String = when (value.uppercase()) {
        "MEAT" -> MEAT
        "MILK", "DAIRY" -> MILK
        else -> BREEDING
    }
}

enum class AvailabilityStatus {
    AVAILABLE,
    RESERVED,
    SOLD,
    UNAVAILABLE,
    BOOKING_PENDING,
    PENDING,
    CONFIRMED,
    APPROVED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    EXPIRED;

    object BookingStatus {
        val PENDING = AvailabilityStatus.PENDING
        val BOOKING_PENDING = AvailabilityStatus.BOOKING_PENDING
        val CONFIRMED = AvailabilityStatus.CONFIRMED
        val APPROVED = AvailabilityStatus.APPROVED
        val COMPLETED = AvailabilityStatus.COMPLETED
        val REJECTED = AvailabilityStatus.REJECTED
        val CANCELLED = AvailabilityStatus.CANCELLED
        val EXPIRED = AvailabilityStatus.EXPIRED
    }

    companion object {
        fun fromString(value: String?): AvailabilityStatus {
            if (value == null) return AVAILABLE
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: AVAILABLE
        }
    }
}

typealias BookingStatus = AvailabilityStatus

enum class NotificationType {
    BOOKING_CREATED,
    BOOKING_CONFIRMED,
    BOOKING_REJECTED,
    BOOKING_CANCELLED,
    BOOKING_CANCELLATION,
    RESERVATION_EXPIRY,
    LISTING_UPDATE,
    NEW_BOOKING,
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
    SYSTEM_ALERT,
    GENERAL
}

data class GoatFilterCriteria(
    val searchQuery: String = "",
    val breed: String? = null,
    val gender: String? = null,
    val purpose: String? = null,
    val district: String? = null,
    val farmName: String? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minAgeMonths: Int? = null,
    val maxAgeMonths: Int? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val onlyAvailable: Boolean = false,
    val availability: AvailabilityStatus? = null
) {
    fun matches(goat: Goat): Boolean {
        if (onlyAvailable && goat.availabilityStatus != AvailabilityStatus.AVAILABLE) return false
        if (availability != null && goat.availabilityStatus != availability) return false
        if (searchQuery.isNotBlank()) {
            val q = searchQuery.trim().lowercase()
            val matchesQuery = goat.name.lowercase().contains(q) ||
                goat.breed.lowercase().contains(q) ||
                goat.farmName.lowercase().contains(q) ||
                goat.farmLocation.lowercase().contains(q) ||
                goat.description.lowercase().contains(q)
            if (!matchesQuery) return false
        }
        if (!breed.isNullOrBlank() && !goat.breed.equals(breed.trim(), ignoreCase = true)) return false
        if (!gender.isNullOrBlank() && !goat.gender.equals(gender.trim(), ignoreCase = true)) return false
        if (!purpose.isNullOrBlank() && !goat.purpose.equals(purpose.trim(), ignoreCase = true)) return false
        if (!district.isNullOrBlank() && !goat.farmLocation.contains(district.trim(), ignoreCase = true)) return false
        if (!farmName.isNullOrBlank() && !goat.farmName.contains(farmName.trim(), ignoreCase = true)) return false
        if (minPrice != null && goat.finalPrice < minPrice) return false
        if (maxPrice != null && goat.finalPrice > maxPrice) return false
        if (minAgeMonths != null && goat.ageMonths < minAgeMonths) return false
        if (maxAgeMonths != null && goat.ageMonths > maxAgeMonths) return false
        if (minWeightKg != null && goat.weightKg < minWeightKg) return false
        if (maxWeightKg != null && goat.weightKg > maxWeightKg) return false
        return true
    }
}

data class WishlistItem(
    val id: String = "",
    val userId: String = "",
    val goatId: String = "",
    val goat: Goat? = null,
    val createdAt: String? = null
)

data class Goat(
    val id: String = "",
    val goatCode: String = "",
    val tagId: String = "",
    val name: String = "",
    val breed: String = "",
    val gender: String = "Male",
    val ageMonths: Int = 12,
    val weightKg: Double = 25.0,
    val price: Double = 0.0,
    val discountPercentage: Double = 0.0,
    val finalPrice: Double = com.ammalfarm.adusanthai.core.util.PriceUtils.calculateFinalPrice(price, discountPercentage),
    val color: String = "",
    val purpose: String = "Breeding",
    val description: String = "",
    val healthNotes: String = "",
    val isVaccinated: Boolean = true,
    val isVerified: Boolean = false,
    val isFeatured: Boolean = false,
    val imageUrl: String = "",
    val imageUrls: List<String> = emptyList(),
    val photos: List<String> = emptyList(),
    val farmId: String = "",
    val farmName: String = "",
    val farmLocation: String = "",
    val farmContact: String = "",
    val farmCode: String = "",
    val approvalStatus: ApprovalStatus = ApprovalStatus.APPROVED,
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val isActive: Boolean = true,
    val rejectionReason: String? = null,
    val listingFeePaid: Boolean = false,
    val listingFeeAmount: Double = 0.0,
    val rating: Double = 5.0,
    val reviewCount: Int = 0
) {
    val effectivePrice: Double
        get() = if (finalPrice > 0.0 && finalPrice < price) finalPrice
        else if (discountPercentage > 0.0) com.ammalfarm.adusanthai.core.util.PriceUtils.calculateFinalPrice(price, discountPercentage)
        else price

    val savingsAmount: Double
        get() = com.ammalfarm.adusanthai.core.util.PriceUtils.calculateSavings(price, discountPercentage)

    val primaryPhoto: String
        get() = imageUrl.ifBlank { imageUrls.firstOrNull() ?: photos.firstOrNull() ?: "" }

    val hasDiscount: Boolean
        get() = discountPercentage > 0.0 && price > 0.0

    val formattedPrice: String
        get() = com.ammalfarm.adusanthai.core.util.PriceUtils.formatCurrency(price)

    val formattedFinalPrice: String
        get() = com.ammalfarm.adusanthai.core.util.PriceUtils.formatCurrency(effectivePrice)

    val formattedDiscountBadge: String
        get() = com.ammalfarm.adusanthai.core.util.PriceUtils.formatDiscountBadge(discountPercentage)
}

data class Booking(
    val id: String = "",
    val bookingCode: String = "",
    val goatId: String = "",
    val goatCode: String = "",
    val goatName: String = "",
    val goatBreed: String = "",
    val goatImageUrl: String = "",
    val goatPhoto: String = goatImageUrl,
    val goatPrice: Double = 0.0,
    val amount: Double = goatPrice,
    val advanceAmount: Double = 0.0,
    val customerId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val farmId: String = "",
    val farmName: String = "",
    val farmPhone: String = "",
    val preferredVisitDate: String = "",
    val reservationExpiryDate: Long = 0L,
    val bookingDate: Long = 0L,
    val notes: String = "",
    val status: BookingStatus = BookingStatus.PENDING,
    val paymentStatus: String = "UNPAID",
    val createdAt: String = ""
) {
    fun calculateFinalPrice(): Double = if (amount > 0.0) amount else goatPrice

    val formattedAmount: String
        get() = com.ammalfarm.adusanthai.core.util.PriceUtils.formatCurrency(calculateFinalPrice())

    val isHoldExpired: Boolean
        get() = reservationExpiryDate > 0L && System.currentTimeMillis() > reservationExpiryDate

    val displayBookingCode: String
        get() = bookingCode.ifBlank { id.take(8).uppercase() }

    val displayGoatCode: String
        get() = goatCode.ifBlank { goatId.take(8).uppercase() }
}

data class AppNotification(
    val id: String = "",
    val userId: String = "",
    val recipientUserId: String = userId,
    val title: String = "",
    val message: String = "",
    val type: Any = "GENERAL",
    val isRead: Boolean = false,
    val referenceId: String? = null,
    val eventKey: String? = null,
    val createdAt: String = "",
    val timestamp: Long = System.currentTimeMillis()
) {
    val linkType: String get() = type.toString()
    val linkId: String? get() = referenceId
    val body: String get() = message
}

enum class ReportReason(val displayName: String) {
    FRAUD("Fraud or Scam"),
    WRONG_PRICE("Wrong or Misleading Price"),
    INCORRECT_INFO("Incorrect Breed or Info"),
    SUSPICIOUS_SELLER("Suspicious Seller"),
    ANIMAL_WELFARE("Animal Welfare Concern"),
    OTHER("Other")
}

enum class ReportStatus {
    NEW,
    PENDING,
    UNDER_REVIEW,
    RESOLVED,
    DISMISSED
}

data class PlatformReport(
    val id: String = "",
    val reporterId: String = "",
    val reporterName: String = "",
    val reporterEmail: String = "",
    val targetType: String = "GOAT_LISTING",
    val targetId: String = "",
    val targetTitle: String = "",
    val reason: ReportReason = ReportReason.OTHER,
    val description: String = "",
    val status: ReportStatus = ReportStatus.NEW,
    val resolutionNotes: String? = null,
    val resolvedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class FarmReview(
    val id: String = "",
    val farmId: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val rating: Int = 5,
    val comment: String = "",
    val createdAt: String = ""
)

data class ListingReport(
    val id: String = "",
    val goatId: String = "",
    val reporterId: String = "",
    val reason: String = "",
    val status: String = "OPEN",
    val createdAt: String = ""
)

data class PlatformStats(
    val totalFarms: Int = 0,
    val verifiedFarms: Int = 0,
    val pendingFarms: Int = 0,
    val totalGoats: Int = 0,
    val approvedGoats: Int = 0,
    val pendingGoats: Int = 0,
    val availableGoats: Int = 0,
    val reservedGoats: Int = 0,
    val soldGoats: Int = 0,
    val totalBookings: Int = 0,
    val pendingBookings: Int = 0,
    val confirmedBookings: Int = 0,
    val completedBookings: Int = 0,
    val totalUsers: Int = 0,
    val openReports: Int = 0
)
