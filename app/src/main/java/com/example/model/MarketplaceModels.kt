package com.example.model

import com.example.core.util.PriceUtils

enum class UserRole {
    SUPER_ADMIN,
    FARM_ADMIN,
    CUSTOMER;

    companion object {
        fun fromString(roleStr: String?): UserRole {
            if (roleStr.isNullOrBlank()) return CUSTOMER
            val normalized = roleStr.trim().lowercase()
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")

            return when {
                normalized in listOf(
                    "superadmin", "superadministrator", "super", "admin", "administrator", "root"
                ) || normalized.contains("superadmin") || (normalized.contains("super") && normalized.contains("admin")) || normalized == "admin" -> SUPER_ADMIN
                normalized in listOf(
                    "farmadmin", "farm", "farmer", "seller", "breeder", "partner"
                ) || normalized.contains("farm") || normalized.contains("farmer") || normalized.contains("seller") -> FARM_ADMIN
                else -> CUSTOMER
            }
        }
    }
}

enum class VerificationStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUSPENDED
}

enum class ApprovalStatus {
    DRAFT,
    PAYMENT_PENDING,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    SUSPENDED
}

enum class AvailabilityStatus {
    AVAILABLE,
    RESERVED,
    BOOKING_PENDING,
    CONFIRMED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    SOLD
}

enum class GoatGender {
    MALE,
    FEMALE
}

enum class GoatPurpose {
    DAIRY,
    MEAT,
    BREEDING,
    PET,
    SHOW
}

data class UserProfile(
    val id: String,
    val email: String,
    val name: String,
    val phone: String = "",
    val role: UserRole = UserRole.CUSTOMER,
    val isPhoneVerified: Boolean = false,
    val farmId: String? = null,
    val isSuspended: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class Farm(
    val id: String,
    val farmCode: String = "",
    val name: String,
    val ownerId: String,
    val ownerName: String,
    val location: String,
    val state: String = "Tamil Nadu",
    val contactNumber: String,
    val email: String,
    val description: String,
    val logoUrl: String = "",
    val bannerUrl: String = "",
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,
    val isAmmalOwnFarm: Boolean = false,
    val rating: Double = 0.0,
    val totalReviews: Int = 0,
    val totalGoatsListed: Int = 0,
    val goatListingLimit: Int = 2,
    val createdAt: Long = System.currentTimeMillis()
)

data class Goat(
    val id: String,
    val goatCode: String = "",
    val name: String,
    val breed: String,
    val gender: GoatGender,
    val ageMonths: Int,
    val weightKg: Double,
    val purpose: GoatPurpose,
    val description: String,
    val price: Double,
    val discountPercentage: Double = 0.0,
    val photos: List<String> = emptyList(),
    val farmId: String,
    val farmCode: String = "",
    val farmName: String,
    val farmLocation: String,
    val availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
    val approvalStatus: ApprovalStatus = ApprovalStatus.DRAFT,
    val listingFeePaid: Boolean = false,
    val listingFeeAmount: Double = 100.0,
    val rating: Double = 0.0,
    val reviewCount: Int = 0,
    val isFeatured: Boolean = false,
    val activeBookingId: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val finalPrice: Double
        get() = PriceUtils.calculateFinalPrice(price, discountPercentage)

    val savingsAmount: Double
        get() = PriceUtils.calculateSavings(price, discountPercentage)

    val hasDiscount: Boolean
        get() = PriceUtils.hasDiscount(price, discountPercentage)

    val formattedPrice: String
        get() = PriceUtils.formatCurrency(price)

    val formattedFinalPrice: String
        get() = PriceUtils.formatCurrency(finalPrice)

    val formattedSavings: String
        get() = PriceUtils.formatCurrency(savingsAmount)

    val formattedDiscountBadge: String
        get() = PriceUtils.formatDiscountBadge(discountPercentage)

    val formattedDiscountPercent: String
        get() = PriceUtils.formatDiscountPercent(discountPercentage)
}

data class Booking(
    val id: String,
    val bookingCode: String = "",
    val goatId: String,
    val goatCode: String = "",
    val goatName: String,
    val goatBreed: String,
    val goatPhoto: String,
    val customerId: String,
    val customerName: String,
    val customerPhone: String,
    val farmId: String,
    val farmName: String,
    val amount: Double,
    val status: AvailabilityStatus = AvailabilityStatus.BOOKING_PENDING,
    val bookingDate: Long = System.currentTimeMillis(),
    val reservationExpiryDate: Long = System.currentTimeMillis() + (24 * 3600 * 1000L), // 24h hold
    val notes: String = ""
) {
    val displayBookingCode: String
        get() = if (bookingCode.isNotBlank()) bookingCode else "AMM-${id.take(6).uppercase()}"

    val displayGoatCode: String
        get() = if (goatCode.isNotBlank()) goatCode else "GOAT-${goatId.take(4).uppercase()}"

    val isHoldExpired: Boolean
        get() = (status == AvailabilityStatus.BOOKING_PENDING || status == AvailabilityStatus.RESERVED) &&
                System.currentTimeMillis() > reservationExpiryDate

    val formattedAmount: String
        get() = PriceUtils.formatCurrency(amount)
}

enum class NotificationType {
    // Customer
    BOOKING_CREATED,
    BOOKING_CONFIRMED,
    BOOKING_REJECTED,
    BOOKING_CANCELLED,
    RESERVATION_EXPIRY,
    LISTING_UPDATE,
    // Farm Admin
    NEW_BOOKING,
    BOOKING_CANCELLATION,
    LISTING_PAYMENT_SUCCESS,
    LISTING_PAYMENT_FAILED,
    LISTING_APPROVED,
    LISTING_REJECTED,
    // Super Admin
    NEW_FARM_APPLICATION,
    NEW_LISTING_PENDING,
    NEW_PAYMENT_RECEIVED,
    NEW_REPORT_SUBMITTED,
    // System
    SYSTEM_ALERT
}

data class AppNotification(
    val id: String,
    val recipientUserId: String = "",
    val targetRole: UserRole = UserRole.CUSTOMER,
    val title: String,
    val message: String,
    val type: NotificationType = NotificationType.SYSTEM_ALERT,
    val timestamp: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
    val referenceId: String? = null,
    val deepLinkRoute: String? = null,
    val eventKey: String? = null
)

enum class PaymentStatus {
    PENDING,
    AUTHORIZED,
    PAID,
    FAILED,
    CANCELLED,
    REFUNDED
}

data class ListingPayment(
    val id: String,
    val goatId: String,
    val goatName: String,
    val goatCode: String = "",
    val farmId: String,
    val farmName: String,
    val amount: Double = 0.0,
    val currency: String = "INR",
    val status: PaymentStatus = PaymentStatus.PAID,
    val orderId: String? = null,
    val razorpayPaymentId: String? = null,
    val receiptNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class ListingPaymentInitiation(
    val success: Boolean,
    val feeRequired: Boolean,
    val amount: Double,
    val currency: String = "INR",
    val orderId: String? = null,
    val paymentRecordId: String? = null,
    val goatId: String = "",
    val alreadyPaid: Boolean = false,
    val message: String? = null
)

data class PaymentVerificationResult(
    val success: Boolean,
    val message: String,
    val payment: ListingPayment? = null,
    val approvalStatus: ApprovalStatus = ApprovalStatus.PENDING_APPROVAL,
    val alreadyPaid: Boolean = true,
    val receiptNumber: String? = null
)

data class PlatformStats(
    val totalGoats: Int = 0,
    val activeFarms: Int = 0,
    val pendingFarms: Int = 0,
    val suspendedFarms: Int = 0,
    val pendingListings: Int = 0,
    val totalBookings: Int = 0,
    val activeBookings: Int = 0,
    val completedBookings: Int = 0,
    val totalCustomers: Int = 0,
    val totalRevenue: Double = 0.0,
    val totalListingFeesCollected: Double = 0.0,
    val pendingReports: Int = 0
)

enum class ReportReason(val displayName: String) {
    INCORRECT_INFO("Incorrect information"),
    WRONG_PRICE("Wrong price"),
    ALREADY_SOLD("Goat already sold"),
    MISLEADING_PHOTOS("Misleading photos"),
    SUSPICIOUS_SELLER("Suspicious seller"),
    FRAUD("Fraud"),
    OTHER("Other");

    val title: String get() = displayName

    companion object {
        fun fromString(value: String): ReportReason {
            return try {
                when (value.uppercase().replace(" ", "_").replace("/", "_")) {
                    "MISLEADING_INFO" -> INCORRECT_INFO
                    "HEALTH_CONCERN" -> INCORRECT_INFO
                    "HARASSMENT" -> SUSPICIOUS_SELLER
                    "INAPPROPRIATE_CONTENT" -> OTHER
                    "PRICING_VIOLATION" -> WRONG_PRICE
                    else -> valueOf(value.uppercase().replace(" ", "_").replace("/", "_"))
                }
            } catch (_: Exception) {
                entries.find { it.displayName.equals(value, ignoreCase = true) } ?: OTHER
            }
        }
    }
}

typealias ReportType = ReportReason

enum class ReportTargetType(val displayName: String) {
    GOAT_LISTING("Goat Listing"),
    FARM("Farm"),
    OTHER("Other Content")
}

enum class ReportStatus(val displayName: String) {
    NEW("New"),
    UNDER_REVIEW("Under Review"),
    RESOLVED("Resolved"),
    DISMISSED("Dismissed");

    companion object {
        val PENDING: ReportStatus get() = NEW
        val UNDER_INVESTIGATION: ReportStatus get() = UNDER_REVIEW

        fun fromString(value: String): ReportStatus {
            return try {
                when (value.uppercase().replace(" ", "_")) {
                    "PENDING" -> NEW
                    "UNDER_INVESTIGATION" -> UNDER_REVIEW
                    else -> valueOf(value.uppercase().replace(" ", "_"))
                }
            } catch (_: Exception) {
                NEW
            }
        }
    }
}

data class PlatformReport(
    val id: String,
    val reporterId: String,
    val reporterName: String,
    val reporterEmail: String = "",
    val targetType: String, // "GOAT_LISTING" / "GOAT", "FARM", "OTHER"
    val targetId: String,
    val targetTitle: String,
    val reason: ReportReason,
    val description: String,
    val evidencePhotoUrl: String? = null,
    val evidencePhotos: List<String> = emptyList(),
    val status: ReportStatus = ReportStatus.NEW,
    val resolutionNotes: String? = null,
    val adminActionTaken: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null
)

enum class SortOption(val displayName: String) {
    RELEVANCE("Relevance"),
    PRICE_LOW_HIGH("Price: Low to High"),
    PRICE_HIGH_LOW("Price: High to Low"),
    NEWEST("Newest First"),
    AGE_YOUNGEST("Age: Youngest"),
    AGE_OLDEST("Age: Oldest"),
    WEIGHT_HEAVIEST("Weight: Heaviest"),
    WEIGHT_LIGHTEST("Weight: Lightest")
}

data class GoatFilterCriteria(
    val searchQuery: String = "",
    val breed: String? = null,
    val farmId: String? = null,
    val farmName: String? = null,
    val gender: GoatGender? = null,
    val minAgeMonths: Int? = null,
    val maxAgeMonths: Int? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val purpose: GoatPurpose? = null,
    val location: String? = null,
    val availability: AvailabilityStatus? = null,
    val sortBy: SortOption = SortOption.RELEVANCE
) {
    val activeFilterCount: Int
        get() {
            var count = 0
            if (breed != null) count++
            if (farmId != null || farmName != null) count++
            if (gender != null) count++
            if (minAgeMonths != null || maxAgeMonths != null) count++
            if (minWeightKg != null || maxWeightKg != null) count++
            if (minPrice != null || maxPrice != null) count++
            if (purpose != null) count++
            if (!location.isNullOrBlank()) count++
            if (availability != null) count++
            return count
        }

    val isDefault: Boolean
        get() = searchQuery.isBlank() &&
                activeFilterCount == 0 &&
                sortBy == SortOption.RELEVANCE

    fun matches(goat: Goat): Boolean {
        // Search query across goat name, breed, farm name, goat code, description, farm location
        if (searchQuery.isNotBlank()) {
            val terms = searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            val matchesSearch = terms.all { term ->
                goat.name.lowercase().contains(term) ||
                        goat.breed.lowercase().contains(term) ||
                        goat.farmName.lowercase().contains(term) ||
                        goat.goatCode.lowercase().contains(term) ||
                        goat.description.lowercase().contains(term) ||
                        goat.farmLocation.lowercase().contains(term)
            }
            if (!matchesSearch) return false
        }

        // Breed filter
        if (breed != null && !breed.equals("All", ignoreCase = true) && breed.isNotBlank()) {
            if (!goat.breed.contains(breed, ignoreCase = true)) return false
        }

        // Farm filter
        if (farmId != null && goat.farmId != farmId) return false
        if (farmName != null && !goat.farmName.contains(farmName, ignoreCase = true)) return false

        // Gender filter
        if (gender != null && goat.gender != gender) return false

        // Age range filter
        if (minAgeMonths != null && goat.ageMonths < minAgeMonths) return false
        if (maxAgeMonths != null && goat.ageMonths > maxAgeMonths) return false

        // Weight range filter
        if (minWeightKg != null && goat.weightKg < minWeightKg) return false
        if (maxWeightKg != null && goat.weightKg > maxWeightKg) return false

        // Price range filter (based on final discounted price payable by customer)
        if (minPrice != null && goat.finalPrice < minPrice) return false
        if (maxPrice != null && goat.finalPrice > maxPrice) return false

        // Purpose filter
        if (purpose != null && goat.purpose != purpose) return false

        // Location filter
        if (!location.isNullOrBlank()) {
            val loc = location.trim().lowercase()
            val matchesLoc = goat.farmLocation.lowercase().contains(loc) ||
                    goat.farmName.lowercase().contains(loc)
            if (!matchesLoc) return false
        }

        // Availability filter
        if (availability != null && goat.availabilityStatus != availability) return false

        return true
    }
}

data class WishlistItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val userId: String,
    val goatId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val goat: Goat? = null
)

