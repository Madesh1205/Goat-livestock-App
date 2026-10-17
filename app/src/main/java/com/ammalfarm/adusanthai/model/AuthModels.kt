package com.ammalfarm.adusanthai.model

enum class UserRole {
    CUSTOMER,
    FARM_ADMIN,
    SUPER_ADMIN;

    companion object {
        fun fromString(value: String?): UserRole {
            if (value.isNullOrBlank()) return CUSTOMER
            return try {
                valueOf(value.trim().uppercase())
            } catch (_: Exception) {
                CUSTOMER
            }
        }
    }
}

enum class VerificationStatus {
    PENDING,
    VERIFIED,
    APPROVED,
    REJECTED,
    SUSPENDED;

    val isApprovedOrVerified: Boolean
        get() = this == VERIFIED || this == APPROVED

    companion object {
        fun fromString(value: String?): VerificationStatus {
            if (value.isNullOrBlank()) return PENDING
            return entries.find { it.name.equals(value.trim(), ignoreCase = true) } ?: PENDING
        }
    }
}

enum class AuthScreenMode {
    LOGIN,
    REGISTER,
    FORGOT_PASSWORD
}

data class UserProfile(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val role: UserRole = UserRole.CUSTOMER,
    val farmId: String? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,
    val avatarUrl: String? = null,
    val isPhoneVerified: Boolean = false,
    val isSuspended: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class Farm(
    val id: String = "",
    val farmCode: String = "",
    val name: String = "",
    val ownerId: String = "",
    val ownerName: String = "",
    val location: String = "",
    val district: String = "",
    val state: String = "Tamil Nadu",
    val contactNumber: String = "",
    val email: String = "",
    val description: String = "",
    val imageUrl: String = "",
    val logoUrl: String = "",
    val bannerUrl: String? = null,
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,
    val approvalStatus: ApprovalStatus = ApprovalStatus.PENDING,
    val goatListingLimit: Int = 5,
    val consumedListingSlots: Int = 0,
    val isAmmalOwnFarm: Boolean = false,
    val approvalFeePaid: Boolean = false,
    val approvalFeeAmount: Double = 500.0,
    val rating: Double = 5.0,
    val reviewCount: Int = 0,
    val totalReviews: Int = reviewCount,
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalGoatsListed: Int get() = consumedListingSlots
}

data class AuthState(
    val isAuthenticated: Boolean = false,
    val userProfile: UserProfile? = null,
    val userFarm: Farm? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val emailConfirmationRequired: Boolean = false
)
