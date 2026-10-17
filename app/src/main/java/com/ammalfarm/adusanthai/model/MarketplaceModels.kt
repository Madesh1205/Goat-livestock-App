package com.ammalfarm.adusanthai.model

enum class ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED;

    companion object {
        fun fromString(value: String?): ApprovalStatus {
            if (value == null) return PENDING
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

enum class AvailabilityStatus {
    AVAILABLE,
    RESERVED,
    SOLD,
    UNAVAILABLE;

    companion object {
        fun fromString(value: String?): AvailabilityStatus {
            if (value == null) return AVAILABLE
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: AVAILABLE
        }
    }
}

enum class BookingStatus {
    PENDING,
    CONFIRMED,
    APPROVED,
    COMPLETED,
    REJECTED,
    CANCELLED,
    EXPIRED;

    companion object {
        fun fromString(value: String?): BookingStatus {
            if (value == null) return PENDING
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

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
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val onlyAvailable: Boolean = false
)

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
    val finalPrice: Double = price,
    val color: String = "",
    val purpose: String = "Breeding",
    val description: String = "",
    val healthNotes: String = "",
    val isVaccinated: Boolean = true,
    val isVerified: Boolean = false,
    val imageUrl: String = "",
    val imageUrls: List<String> = emptyList(),
    val farmId: String = "",
    val farmName: String = "",
    val farmLocation: String = "",
    val farmContact: String = "",
    val approvalStatus: ApprovalStatus = ApprovalStatus.APPROVED,
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val isActive: Boolean = true,
    val rejectionReason: String? = null
) {
    val effectivePrice: Double
        get() = if (finalPrice > 0.0 && finalPrice < price) finalPrice
        else if (discountPercentage > 0.0) price * (1.0 - discountPercentage / 100.0)
        else price
}

data class Booking(
    val id: String = "",
    val bookingCode: String = "",
    val goatId: String = "",
    val goatName: String = "",
    val goatBreed: String = "",
    val goatImageUrl: String = "",
    val goatPrice: Double = 0.0,
    val advanceAmount: Double = 0.0,
    val customerId: String = "",
    val customerName: String = "",
    val customerPhone: String = "",
    val customerEmail: String = "",
    val farmId: String = "",
    val farmName: String = "",
    val farmPhone: String = "",
    val preferredVisitDate: String = "",
    val notes: String = "",
    val status: BookingStatus = BookingStatus.PENDING,
    val paymentStatus: String = "UNPAID",
    val createdAt: String = ""
)

data class AppNotification(
    val id: String = "",
    val userId: String = "",
    val title: String = "",
    val message: String = "",
    val type: String = "GENERAL",
    val isRead: Boolean = false,
    val referenceId: String? = null,
    val createdAt: String = ""
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
