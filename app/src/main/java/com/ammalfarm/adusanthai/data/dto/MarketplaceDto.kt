package com.ammalfarm.adusanthai.data.dto

import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
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
    @SerialName("full_name") val fullName: String? = "",
    val phone: String? = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @Transient val isPhoneVerified: Boolean = false,
    val role: String? = "CUSTOMER",
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
            name = fullName ?: "",
            phone = phone ?: "",
            avatarUrl = avatarUrl,
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
                avatarUrl = profile.avatarUrl,
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
    @SerialName("farm_code") val farmCode: String? = "",
    val name: String = "",
    @SerialName("owner_id") val ownerId: String? = "",
    @Transient val ownerName: String = "",
    val tagline: String? = null,
    val description: String? = null,
    @SerialName("location_district") val locationDistrict: String? = "",
    @SerialName("location_state") val locationState: String? = "Tamil Nadu",
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("contact_phone") val contactPhone: String? = "",
    @SerialName("contact_email") val contactEmail: String? = null,
    val status: String? = "PENDING",
    @SerialName("is_ammal_own_farm") val isAmmalOwnFarm: Boolean = false,
    @SerialName("goat_listing_limit") val goatListingLimit: Int = 2,
    @SerialName("consumed_listing_slots") val consumedListingSlots: Int = 0,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("verified_by") val verifiedBy: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val rating: Double = 5.0,
    @Transient val reviewCount: Int = 0,
    @Transient val location: String = "",
    @Transient val district: String = "",
    @Transient val state: String = "Tamil Nadu",
    @Transient val contactNumber: String = "",
    @Transient val email: String = "",
    @Transient val verificationStatus: String = "",
    @Transient val approvalFeePaid: Boolean = false,
    @Transient val approvalFeeAmount: Double = 500.0,
    @Transient val totalReviews: Int = 0
) {
    fun toDomain(resolvedOwnerName: String = ownerName): Farm {
        val rawDistrict = locationDistrict?.takeIf { it.isNotBlank() }
            ?: district.takeIf { it.isNotBlank() }
            ?: address?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
            ?: location.substringBefore(",").trim().takeIf { it.isNotBlank() }
            ?: "Madurai"
        val rawState = locationState?.takeIf { it.isNotBlank() } ?: state.ifBlank { "Tamil Nadu" }
        val resolvedLocation = address?.takeIf { it.isNotBlank() }
            ?: location.takeIf { it.isNotBlank() }
            ?: "$rawDistrict, $rawState"
        val rawStatus = verificationStatus.takeIf { it.isNotBlank() } ?: status ?: "PENDING"
        val parsedVerStatus = VerificationStatus.fromString(rawStatus)
        val resolvedLogo = logoUrl?.takeIf { it.isNotBlank() }?.let {
            SupabaseConfig.resolveStorageUrl(it, SupabaseConfig.BUCKET_GOAT_IMAGES)
        } ?: ""
        val resolvedBanner = bannerUrl?.takeIf { it.isNotBlank() }?.let {
            SupabaseConfig.resolveStorageUrl(it, SupabaseConfig.BUCKET_GOAT_IMAGES)
        }
        val effectiveIsAmmal = isAmmalOwnFarm || id == SEED_AMMAL_FARM_UUID
        val effectiveReviews = if (reviewCount > 0) reviewCount else totalReviews

        return Farm(
            id = id,
            farmCode = farmCode ?: "",
            name = name,
            ownerId = ownerId ?: "",
            ownerName = resolvedOwnerName,
            location = resolvedLocation,
            district = rawDistrict,
            state = rawState,
            contactNumber = contactPhone?.takeIf { it.isNotBlank() } ?: contactNumber,
            email = contactEmail?.takeIf { it.isNotBlank() } ?: email,
            description = description ?: tagline ?: "",
            imageUrl = resolvedLogo,
            logoUrl = resolvedLogo,
            bannerUrl = resolvedBanner,
            verificationStatus = parsedVerStatus,
            approvalStatus = if (parsedVerStatus.isApprovedOrVerified) ApprovalStatus.APPROVED else ApprovalStatus.PENDING,
            isAmmalOwnFarm = effectiveIsAmmal,
            goatListingLimit = goatListingLimit,
            consumedListingSlots = consumedListingSlots,
            approvalFeePaid = approvalFeePaid || parsedVerStatus.isApprovedOrVerified || effectiveIsAmmal,
            approvalFeeAmount = approvalFeeAmount,
            rating = rating,
            reviewCount = effectiveReviews,
            totalReviews = effectiveReviews
        )
    }

    companion object {
        fun fromDomain(farm: Farm): FarmDto {
            val resolvedDist = farm.district.ifBlank {
                farm.location.substringBefore(",").trim().ifBlank { "Madurai" }
            }
            return FarmDto(
                id = farm.id,
                farmCode = farm.farmCode.ifBlank { null },
                name = farm.name,
                ownerId = farm.ownerId.ifBlank { null },
                ownerName = farm.ownerName,
                description = farm.description.ifBlank { null },
                locationDistrict = resolvedDist,
                locationState = farm.state.ifBlank { "Tamil Nadu" },
                address = farm.location.ifBlank { "$resolvedDist, ${farm.state.ifBlank { "Tamil Nadu" }}" },
                contactPhone = farm.contactNumber,
                contactEmail = farm.email.ifBlank { null },
                status = farm.verificationStatus.name,
                isAmmalOwnFarm = farm.isAmmalOwnFarm,
                goatListingLimit = farm.goatListingLimit,
                consumedListingSlots = farm.consumedListingSlots,
                rating = farm.rating,
                reviewCount = farm.totalReviews,
                logoUrl = farm.logoUrl.takeIf { it.isNotBlank() },
                bannerUrl = farm.bannerUrl?.takeIf { it.isNotBlank() }
            )
        }
    }
}

@Serializable
data class GoatImageDto(
    val id: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("display_order") val displayOrder: Int = 0,
    @SerialName("is_primary") val isPrimary: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class GoatDto(
    val id: String = "",
    @SerialName("goat_code") val goatCode: String? = "",
    @SerialName("farm_id") val farmId: String = "",
    val name: String = "",
    @SerialName("breed_id") val breedId: String? = null,
    @SerialName("breed_name") val breedName: String? = "",
    val gender: String? = "MALE",
    @SerialName("age_months") val ageMonths: Int = 12,
    @SerialName("weight_kg") val weightKg: Double = 25.0,
    val purpose: String? = "BREEDING",
    val price: Double = 0.0,
    @SerialName("discount_percentage") val discountPercentage: Double = 0.0,
    @SerialName("final_price") val finalPrice: Double = 0.0,
    val status: String? = "AVAILABLE",
    val description: String? = "",
    @SerialName("vaccination_status") val vaccinationStatus: String? = "Fully Vaccinated",
    @SerialName("dewormed_date") val dewormedDate: String? = null,
    @SerialName("parentage_father_tag") val parentageFatherTag: String? = null,
    @SerialName("parentage_mother_tag") val parentageMotherTag: String? = null,
    @SerialName("is_approved_by_admin") val isApprovedByAdmin: Boolean = false,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("approved_by") val approvedBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val tagNumber: String? = "",
    @Transient val rating: Double = 5.0,
    @Transient val reviewCount: Int = 0,
    @Transient val tagId: String = "",
    @Transient val breed: String = "",
    @Transient val color: String = "",
    @Transient val healthNotes: String = "",
    @Transient val isVaccinated: Boolean = true,
    @Transient val isVerified: Boolean = false,
    @Transient val imageUrl: String = "",
    @Transient val imageUrls: List<String> = emptyList(),
    @Transient val farmName: String = "",
    @Transient val farmLocation: String = "",
    @Transient val farmContact: String = "",
    @Transient val approvalStatus: String? = null,
    @Transient val availabilityStatus: String? = null,
    @Transient val isActive: Boolean = true,
    @Transient val rejectionReason: String? = null
) {
    fun toDomain(
        resolvedPhotos: List<String> = emptyList(),
        resolvedFarmName: String = "",
        resolvedFarmLocation: String = "",
        resolvedFarmContact: String = "",
        resolvedFarmCode: String = ""
    ): Goat {
        val computedFinalPrice = if (finalPrice > 0.0) {
            finalPrice
        } else if (discountPercentage > 0.0) {
            price * (1.0 - (discountPercentage / 100.0))
        } else {
            price
        }
        val allPhotos = when {
            resolvedPhotos.isNotEmpty() -> resolvedPhotos
            imageUrls.isNotEmpty() -> imageUrls
            imageUrl.isNotBlank() -> listOf(imageUrl)
            else -> emptyList()
        }.map { SupabaseConfig.resolveStorageUrl(it, SupabaseConfig.BUCKET_GOAT_IMAGES) }
            .filter { it.isNotBlank() }

        val primaryImg = allPhotos.firstOrNull() ?: ""
        val resolvedBreed = breedName?.takeIf { it.isNotBlank() } ?: breed
        val resolvedCode = goatCode?.takeIf { it.isNotBlank() } ?: ""
        val resolvedTag = tagNumber?.takeIf { it.isNotBlank() } ?: tagId.ifBlank { resolvedCode }

        val rawGender = (gender ?: "MALE").trim()
        val displayGender = when (rawGender.uppercase()) {
            "MALE" -> "Male"
            "FEMALE" -> "Female"
            "CASTRATED" -> "Castrated"
            else -> rawGender.replaceFirstChar { it.uppercase() }
        }

        val rawPurpose = (purpose ?: "BREEDING").trim()
        val displayPurpose = when (rawPurpose.uppercase()) {
            "BREEDING" -> "Breeding"
            "MEAT" -> "Meat"
            "MILK", "DAIRY" -> "Dairy"
            "SHOW" -> "Show"
            "PET" -> "Pet"
            else -> rawPurpose.replaceFirstChar { it.uppercase() }
        }

        val rawStatus = (availabilityStatus ?: status ?: "AVAILABLE").trim().uppercase()
        val parsedAvailability = when (rawStatus) {
            "", "AVAILABLE" -> AvailabilityStatus.AVAILABLE
            "CONFIRMED", "BOOKED", "BOOKINGSTATUS.CONFIRMED" -> AvailabilityStatus.CONFIRMED
            "COMPLETED", "BOOKINGSTATUS.COMPLETED" -> AvailabilityStatus.COMPLETED
            "RESERVED", "HOLD", "HELD", "BOOKING_PENDING" -> AvailabilityStatus.RESERVED
            "SOLD" -> AvailabilityStatus.SOLD
            "INACTIVE", "UNAVAILABLE" -> AvailabilityStatus.UNAVAILABLE
            else -> AvailabilityStatus.RESERVED
        }

        val parsedApproval = when {
            !approvalStatus.isNullOrBlank() -> ApprovalStatus.fromString(approvalStatus)
            isApprovedByAdmin -> ApprovalStatus.APPROVED
            rawStatus == "INACTIVE" -> ApprovalStatus.REJECTED
            else -> ApprovalStatus.PENDING
        }

        val vaxNotes = vaccinationStatus?.takeIf { it.isNotBlank() } ?: healthNotes.ifBlank { "Fully Vaccinated" }
        val vaccinatedFlag = !vaxNotes.contains("not", ignoreCase = true) && isVaccinated

        return Goat(
            id = id,
            goatCode = resolvedCode,
            tagId = resolvedTag,
            name = name,
            breed = resolvedBreed,
            gender = displayGender,
            ageMonths = ageMonths,
            weightKg = weightKg,
            price = price,
            discountPercentage = discountPercentage,
            finalPrice = computedFinalPrice,
            color = color,
            purpose = displayPurpose,
            description = description ?: "",
            healthNotes = vaxNotes,
            isVaccinated = vaccinatedFlag,
            isVerified = isApprovedByAdmin || isVerified,
            imageUrl = primaryImg,
            imageUrls = allPhotos,
            farmId = farmId,
            farmName = resolvedFarmName.ifBlank { farmName },
            farmLocation = resolvedFarmLocation.ifBlank { farmLocation },
            farmContact = resolvedFarmContact.ifBlank { farmContact },
            approvalStatus = parsedApproval,
            availabilityStatus = parsedAvailability,
            isActive = rawStatus != "INACTIVE" && isActive,
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
            val dbGender = when (goat.gender.trim().uppercase()) {
                "FEMALE" -> "FEMALE"
                "CASTRATED" -> "CASTRATED"
                else -> "MALE"
            }
            val dbPurpose = when (goat.purpose.trim().uppercase()) {
                "MEAT" -> "MEAT"
                "MILK", "DAIRY" -> "MILK"
                "SHOW" -> "SHOW"
                "PET" -> "PET"
                else -> "BREEDING"
            }
            val dbStatus = when {
                !goat.isActive -> "INACTIVE"
                goat.availabilityStatus == AvailabilityStatus.UNAVAILABLE -> "INACTIVE"
                else -> goat.availabilityStatus.name
            }
            return GoatDto(
                id = goat.id,
                goatCode = goat.goatCode.ifBlank { null },
                farmId = goat.farmId,
                tagNumber = goat.tagId.ifBlank { goat.goatCode.ifBlank { "TAG-${goat.id.take(6).uppercase()}" } },
                name = goat.name,
                breedName = goat.breed,
                gender = dbGender,
                ageMonths = goat.ageMonths,
                weightKg = goat.weightKg,
                purpose = dbPurpose,
                price = goat.price,
                discountPercentage = goat.discountPercentage,
                finalPrice = computedFinal,
                status = dbStatus,
                description = goat.description,
                vaccinationStatus = goat.healthNotes.ifBlank { if (goat.isVaccinated) "Fully Vaccinated" else "Pending Vaccination" },
                isApprovedByAdmin = goat.approvalStatus == ApprovalStatus.APPROVED,
                tagId = goat.tagId,
                breed = goat.breed,
                color = goat.color,
                healthNotes = goat.healthNotes,
                isVaccinated = goat.isVaccinated,
                isVerified = goat.isVerified,
                imageUrl = goat.imageUrl.ifBlank { allImages.firstOrNull() ?: "" },
                imageUrls = allImages,
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
    @SerialName("booking_code") val bookingCode: String? = "",
    @SerialName("goat_id") val goatId: String? = "",
    @SerialName("farm_id") val farmId: String? = "",
    @SerialName("customer_id") val customerId: String? = "",
    val status: String? = "PENDING",
    @SerialName("booking_date") val bookingDate: String? = null,
    @SerialName("hold_expires_at") val holdExpiresAt: String? = null,
    @SerialName("total_price") val totalPrice: Double = 0.0,
    @SerialName("deposit_paid") val depositPaid: Double = 0.0,
    @SerialName("customer_notes") val customerNotes: String? = null,
    val notes: String? = null,
    @SerialName("admin_notes") val adminNotes: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val goatName: String = "",
    @Transient val goatBreed: String = "",
    @Transient val goatImageUrl: String = "",
    @Transient val goatPrice: Double = 0.0,
    @Transient val advanceAmount: Double = 0.0,
    @Transient val customerName: String = "",
    @Transient val customerPhone: String = "",
    @Transient val customerEmail: String = "",
    @Transient val farmName: String = "",
    @Transient val farmPhone: String = "",
    @Transient val preferredVisitDate: String = "",
    @Transient val paymentStatus: String = "UNPAID"
) {
    fun toDomain(
        resolvedGoatName: String = goatName,
        resolvedGoatBreed: String = goatBreed,
        resolvedGoatPhoto: String? = goatImageUrl.takeIf { it.isNotBlank() },
        resolvedFarmName: String = farmName,
        resolvedFarmPhone: String = farmPhone,
        resolvedCustomerName: String = customerName,
        resolvedCustomerPhone: String = customerPhone,
        resolvedCustomerEmail: String = customerEmail
    ): Booking {
        val effectivePrice = if (totalPrice > 0.0) totalPrice else goatPrice
        val effectiveAdvance = if (depositPaid > 0.0) depositPaid else advanceAmount
        val combinedNotes = customerNotes?.takeIf { it.isNotBlank() }
            ?: notes?.takeIf { it.isNotBlank() }
            ?: adminNotes.orEmpty()
        val parsedStatus = when ((status ?: "PENDING").uppercase()) {
            "RESERVED", "PENDING" -> BookingStatus.PENDING
            "CONFIRMED", "APPROVED" -> BookingStatus.CONFIRMED
            "COMPLETED" -> BookingStatus.COMPLETED
            "CANCELLED", "REJECTED" -> BookingStatus.CANCELLED
            "EXPIRED" -> BookingStatus.EXPIRED
            else -> BookingStatus.fromString(status)
        }
        return Booking(
            id = id,
            bookingCode = bookingCode?.takeIf { it.isNotBlank() } ?: "AMM-${id.take(6).uppercase()}",
            goatId = goatId ?: "",
            goatName = resolvedGoatName,
            goatBreed = resolvedGoatBreed,
            goatImageUrl = resolvedGoatPhoto ?: "",
            goatPrice = effectivePrice,
            advanceAmount = effectiveAdvance,
            customerId = customerId ?: "",
            customerName = resolvedCustomerName,
            customerPhone = resolvedCustomerPhone,
            customerEmail = resolvedCustomerEmail,
            farmId = farmId ?: "",
            farmName = resolvedFarmName,
            farmPhone = resolvedFarmPhone,
            preferredVisitDate = preferredVisitDate.ifBlank { bookingDate?.take(10) ?: "" },
            notes = combinedNotes,
            status = parsedStatus,
            paymentStatus = if (parsedStatus == BookingStatus.COMPLETED || parsedStatus == BookingStatus.CONFIRMED) "PAID" else paymentStatus,
            createdAt = createdAt ?: bookingDate ?: ""
        )
    }

    companion object {
        fun fromDomain(booking: Booking): BookingDto {
            return BookingDto(
                id = booking.id,
                bookingCode = booking.bookingCode,
                goatId = booking.goatId.ifBlank { null },
                farmId = booking.farmId,
                customerId = booking.customerId.ifBlank { null },
                status = booking.status.name,
                totalPrice = booking.goatPrice,
                depositPaid = booking.advanceAmount,
                customerNotes = booking.notes.ifBlank { null },
                notes = booking.notes.ifBlank { null },
                createdAt = booking.createdAt.ifBlank { null },
                goatName = booking.goatName,
                goatBreed = booking.goatBreed,
                goatImageUrl = booking.goatImageUrl,
                goatPrice = booking.goatPrice,
                advanceAmount = booking.advanceAmount,
                customerName = booking.customerName,
                customerPhone = booking.customerPhone,
                customerEmail = booking.customerEmail,
                farmName = booking.farmName,
                farmPhone = booking.farmPhone,
                preferredVisitDate = booking.preferredVisitDate,
                paymentStatus = booking.paymentStatus
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

typealias WishlistItemDto = WishlistDto

@Serializable
data class NotificationDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val body: String? = null,
    val message: String? = null,
    @SerialName("link_type") val linkType: String? = null,
    val type: String? = null,
    @SerialName("link_id") val linkId: String? = null,
    @SerialName("reference_id") val referenceId: String? = null,
    @SerialName("event_key") val eventKey: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): AppNotification {
        return AppNotification(
            id = id,
            userId = userId,
            title = title,
            message = body?.takeIf { it.isNotBlank() } ?: message.orEmpty(),
            type = linkType?.takeIf { it.isNotBlank() } ?: type ?: "GENERAL",
            isRead = isRead,
            referenceId = linkId ?: referenceId,
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class ReviewDto(
    val id: String = "",
    @SerialName("booking_id") val bookingId: String? = null,
    @SerialName("goat_id") val goatId: String? = null,
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("customer_id") val customerId: String = "",
    @Transient val customerName: String = "",
    val rating: Int = 5,
    val comment: String? = "",
    @SerialName("is_verified_purchase") val isVerifiedPurchase: Boolean = true,
    @SerialName("is_approved") val isApproved: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(resolvedCustomerName: String = customerName): FarmReview {
        return FarmReview(
            id = id,
            farmId = farmId,
            customerId = customerId,
            customerName = resolvedCustomerName.ifBlank { "Verified Customer" },
            rating = rating,
            comment = comment ?: "",
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class ListingReportDto(
    val id: String = "",
    @SerialName("reporter_id") val reporterId: String? = "",
    @SerialName("target_type") val targetType: String? = "GOAT",
    @SerialName("target_id") val targetId: String? = "",
    @SerialName("goat_id") val goatId: String? = null,
    val reason: String? = "",
    val description: String? = null,
    val status: String? = "PENDING",
    @SerialName("resolution_notes") val resolutionNotes: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(): ListingReport {
        val rawStatus = (status ?: "PENDING").uppercase()
        val displayStatus = if (rawStatus == "PENDING") "OPEN" else rawStatus
        return ListingReport(
            id = id,
            goatId = targetId?.takeIf { it.isNotBlank() } ?: goatId.orEmpty(),
            reporterId = reporterId ?: "",
            reason = listOfNotNull(reason?.takeIf { it.isNotBlank() }, description?.takeIf { it.isNotBlank() }).joinToString(" — "),
            status = displayStatus,
            createdAt = createdAt ?: ""
        )
    }
}

@Serializable
data class PlatformSettingRowDto(
    val key: String = "",
    val value: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class PlatformPricingSettingsDto(
    val id: Int = 1,
    @SerialName("farm_approval_fee_inr") val farmApprovalFeeInr: Double = 500.0,
    @SerialName("goat_listing_pack_fee_inr") val goatListingPackFeeInr: Double = 150.0,
    @SerialName("goat_listing_pack_size") val goatListingPackSize: Int = 2
)
