package com.ammalfarm.adusanthai.data.dto

import com.ammalfarm.adusanthai.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

const val SEED_AMMAL_FARM_UUID = "00000000-0000-0000-0000-000000000001"
const val SEED_AMMAL_OWNER_UUID = "00000000-0000-0000-0000-000000000010"

fun currentIsoTimestamp(): String = Instant.now().toString()

fun ensureValidUuid(input: String): String {
    return try {
        UUID.fromString(input).toString()
    } catch (_: Exception) {
        UUID.nameUUIDFromBytes(input.toByteArray()).toString()
    }
}

@Serializable
data class ProfileDto(
    val id: String = "",
    val email: String? = null,
    @SerialName("full_name") val fullName: String = "",
    val phone: String = "",
    @SerialName("is_phone_verified") val isPhoneVerified: Boolean = false,
    val role: String = "CUSTOMER",
    @SerialName("farm_id") val farmId: String? = null,
    @SerialName("is_suspended") val isSuspended: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): UserProfile {
        val parsedRole = UserRole.fromString(role)
        return UserProfile(
            id = id,
            email = email ?: "",
            name = fullName,
            phone = phone,
            isPhoneVerified = isPhoneVerified,
            role = parsedRole,
            farmId = if (parsedRole == UserRole.CUSTOMER) null else farmId,
            isSuspended = isSuspended
        )
    }

    companion object {
        fun fromDomain(profile: UserProfile): ProfileDto {
            return ProfileDto(
                id = profile.id,
                email = profile.email,
                fullName = profile.name,
                phone = profile.phone,
                isPhoneVerified = profile.isPhoneVerified,
                role = profile.role.name,
                farmId = if (profile.role == UserRole.CUSTOMER) null else profile.farmId,
                isSuspended = profile.isSuspended
            )
        }
    }
}

@Serializable
data class FarmDto(
    val id: String = "",
    @SerialName("farm_code") val farmCode: String = "",
    val name: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    @SerialName("owner_name") val ownerName: String = "",
    val location: String = "",
    val district: String = "",
    val state: String = "Tamil Nadu",
    @SerialName("contact_number") val contactNumber: String = "",
    val email: String = "",
    val description: String = "",
    @SerialName("verification_status") val verificationStatus: String = "PENDING",
    @SerialName("is_ammal_own_farm") val isAmmalOwnFarm: Boolean = false,
    @SerialName("goat_listing_limit") val goatListingLimit: Int = 2,
    @SerialName("approval_fee_paid") val approvalFeePaid: Boolean = false,
    @SerialName("approval_fee_amount") val approvalFeeAmount: Double = 500.0,
    @SerialName("rating") val rating: Double = 4.5,
    @SerialName("total_reviews") val totalReviews: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): Farm {
        val resolvedDistrict = district.ifBlank {
            location.substringBefore(",").trim().ifBlank { "Madurai" }
        }
        val resolvedLocation = location.ifBlank { "$resolvedDistrict, $state" }
        return Farm(
            id = id,
            farmCode = farmCode,
            name = name,
            ownerId = ownerId,
            ownerName = ownerName,
            location = resolvedLocation,
            district = resolvedDistrict,
            state = state,
            contactNumber = contactNumber,
            email = email,
            description = description,
            verificationStatus = VerificationStatus.fromString(verificationStatus),
            isAmmalOwnFarm = isAmmalOwnFarm,
            goatListingLimit = goatListingLimit,
            approvalFeePaid = approvalFeePaid,
            approvalFeeAmount = approvalFeeAmount,
            rating = rating,
            totalReviews = totalReviews
        )
    }

    companion object {
        fun fromDomain(farm: Farm): FarmDto {
            return FarmDto(
                id = farm.id,
                farmCode = farm.farmCode,
                name = farm.name,
                ownerId = farm.ownerId,
                ownerName = farm.ownerName,
                location = farm.location,
                district = farm.district.ifBlank { farm.location.substringBefore(",").trim() },
                state = farm.state,
                contactNumber = farm.contactNumber,
                email = farm.email,
                description = farm.description,
                verificationStatus = farm.verificationStatus.name,
                isAmmalOwnFarm = farm.isAmmalOwnFarm,
                goatListingLimit = farm.goatListingLimit,
                approvalFeePaid = farm.approvalFeePaid,
                approvalFeeAmount = farm.approvalFeeAmount,
                rating = farm.rating,
                totalReviews = farm.totalReviews
            )
        }
    }
}

@Serializable
data class GoatDto(
    val id: String = "",
    @SerialName("goat_code") val goatCode: String = "",
    @SerialName("tag_id") val tagId: String = "",
    val name: String = "",
    val breed: String = "",
    val gender: String = "Male",
    @SerialName("age_months") val ageMonths: Int = 12,
    @SerialName("weight_kg") val weightKg: Double = 25.0,
    val price: Double = 0.0,
    @SerialName("discount_percentage") val discountPercentage: Double = 0.0,
    @SerialName("final_price") val finalPrice: Double = 0.0,
    val color: String = "",
    val purpose: String = "Breeding",
    val description: String = "",
    @SerialName("health_notes") val healthNotes: String = "",
    @SerialName("is_vaccinated") val isVaccinated: Boolean = true,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("image_urls") val imageUrls: List<String> = emptyList(),
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("farm_name") val farmName: String = "",
    @SerialName("farm_location") val farmLocation: String = "",
    @SerialName("farm_contact") val farmContact: String = "",
    @SerialName("approval_status") val approvalStatus: String = "PENDING",
    @SerialName("availability_status") val availabilityStatus: String = "AVAILABLE",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): Goat {
        val computedFinalPrice = if (finalPrice > 0.0) {
            finalPrice
        } else if (discountPercentage > 0.0) {
            price * (1.0 - (discountPercentage / 100.0))
        } else {
            price
        }
        val resolvedImages = if (imageUrls.isNotEmpty()) {
            imageUrls
        } else if (imageUrl.isNotBlank()) {
            listOf(imageUrl)
        } else {
            emptyList()
        }
        val primaryImg = imageUrl.ifBlank { resolvedImages.firstOrNull() ?: "" }
        return Goat(
            id = id,
            goatCode = goatCode,
            tagId = tagId.ifBlank { goatCode },
            name = name,
            breed = breed,
            gender = gender,
            ageMonths = ageMonths,
            weightKg = weightKg,
            price = price,
            discountPercentage = discountPercentage,
            finalPrice = computedFinalPrice,
            color = color,
            purpose = purpose,
            description = description,
            healthNotes = healthNotes,
            isVaccinated = isVaccinated,
            isVerified = isVerified,
            imageUrl = primaryImg,
            imageUrls = resolvedImages,
            farmId = farmId,
            farmName = farmName,
            farmLocation = farmLocation,
            farmContact = farmContact,
            approvalStatus = ApprovalStatus.fromString(approvalStatus),
            availabilityStatus = AvailabilityStatus.fromString(availabilityStatus),
            isActive = isActive,
            rejectionReason = rejectionReason
        )
    }

    companion object {
        fun fromDomain(goat: Goat): GoatDto {
            val computedFinal = if (goat.discountPercentage > 0.0) {
                goat.price * (1.0 - (goat.discountPercentage / 100.0))
            } else {
                goat.price
            }
            val allImages = if (goat.imageUrls.isNotEmpty()) goat.imageUrls else listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() })
            return GoatDto(
                id = goat.id,
                goatCode = goat.goatCode,
                tagId = goat.tagId,
                name = goat.name,
                breed = goat.breed,
                gender = goat.gender,
                ageMonths = goat.ageMonths,
                weightKg = goat.weightKg,
                price = goat.price,
                discountPercentage = goat.discountPercentage,
                finalPrice = computedFinal,
                color = goat.color,
                purpose = goat.purpose,
                description = goat.description,
                healthNotes = goat.healthNotes,
                isVaccinated = goat.isVaccinated,
                isVerified = goat.isVerified,
                imageUrl = goat.imageUrl.ifBlank { allImages.firstOrNull() ?: "" },
                imageUrls = allImages,
                farmId = goat.farmId,
                farmName = goat.farmName,
                farmLocation = goat.farmLocation,
                farmContact = goat.farmContact,
                approvalStatus = goat.approvalStatus.name,
                availabilityStatus = goat.availabilityStatus.name,
                isActive = goat.isActive,
                rejectionReason = goat.rejectionReason
            )
        }
    }
}

@Serializable
data class BookingDto(
    val id: String = "",
    @SerialName("booking_code") val bookingCode: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("goat_name") val goatName: String = "",
    @SerialName("goat_breed") val goatBreed: String = "",
    @SerialName("goat_image_url") val goatImageUrl: String = "",
    @SerialName("goat_price") val goatPrice: Double = 0.0,
    @SerialName("advance_amount") val advanceAmount: Double = 0.0,
    @SerialName("customer_id") val customerId: String = "",
    @SerialName("customer_name") val customerName: String = "",
    @SerialName("customer_phone") val customerPhone: String = "",
    @SerialName("customer_email") val customerEmail: String = "",
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("farm_name") val farmName: String = "",
    @SerialName("farm_phone") val farmPhone: String = "",
    @SerialName("preferred_visit_date") val preferredVisitDate: String = "",
    val notes: String = "",
    val status: String = "PENDING",
    @SerialName("payment_status") val paymentStatus: String = "UNPAID",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): Booking {
        return Booking(
            id = id,
            bookingCode = bookingCode,
            goatId = goatId,
            goatName = goatName,
            goatBreed = goatBreed,
            goatImageUrl = goatImageUrl,
            goatPrice = goatPrice,
            advanceAmount = advanceAmount,
            customerId = customerId,
            customerName = customerName,
            customerPhone = customerPhone,
            customerEmail = customerEmail,
            farmId = farmId,
            farmName = farmName,
            farmPhone = farmPhone,
            preferredVisitDate = preferredVisitDate,
            notes = notes,
            status = BookingStatus.fromString(status),
            paymentStatus = paymentStatus,
            createdAt = createdAt ?: ""
        )
    }

    companion object {
        fun fromDomain(booking: Booking): BookingDto {
            return BookingDto(
                id = booking.id,
                bookingCode = booking.bookingCode,
                goatId = booking.goatId,
                goatName = booking.goatName,
                goatBreed = booking.goatBreed,
                goatImageUrl = booking.goatImageUrl,
                goatPrice = booking.goatPrice,
                advanceAmount = booking.advanceAmount,
                customerId = booking.customerId,
                customerName = booking.customerName,
                customerPhone = booking.customerPhone,
                customerEmail = booking.customerEmail,
                farmId = booking.farmId,
                farmName = booking.farmName,
                farmPhone = booking.farmPhone,
                preferredVisitDate = booking.preferredVisitDate,
                notes = booking.notes,
                status = booking.status.name,
                paymentStatus = booking.paymentStatus,
                createdAt = booking.createdAt.ifBlank { null }
            )
        }
    }
}

@Serializable
data class WishlistDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class NotificationDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val message: String = "",
    val type: String = "GENERAL",
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): AppNotification {
        return AppNotification(
            id = id,
            userId = userId,
            title = title,
            message = message,
            type = type,
            isRead = isRead,
            referenceId = referenceId,
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class ReviewDto(
    val id: String = "",
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("customer_id") val customerId: String = "",
    @SerialName("customer_name") val customerName: String = "",
    val rating: Int = 5,
    val comment: String = "",
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): FarmReview {
        return FarmReview(
            id = id,
            farmId = farmId,
            customerId = customerId,
            customerName = customerName,
            rating = rating,
            comment = comment,
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class ListingReportDto(
    val id: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("reporter_id") val reporterId: String = "",
    val reason: String = "",
    val status: String = "OPEN",
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): ListingReport {
        return ListingReport(
            id = id,
            goatId = goatId,
            reporterId = reporterId,
            reason = reason,
            status = status,
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class PlatformPricingSettingsDto(
    val id: Int = 1,
    @SerialName("farm_approval_fee_inr") val farmApprovalFeeInr: Double = 500.0,
    @SerialName("goat_listing_pack_fee_inr") val goatListingPackFeeInr: Double = 200.0,
    @SerialName("goat_listing_pack_size") val goatListingPackSize: Int = 5
)
