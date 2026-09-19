package com.example.data.dto

import com.example.core.supabase.SupabaseConfig
import com.example.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Utility to guarantee that any string ID sent to Supabase PostgreSQL UUID columns
 * is a strictly valid UUID format.
 */
const val SEED_AMMAL_FARM_UUID = "00000000-0000-0000-0000-000000000001"

fun isValidUuid(id: String?): Boolean {
    val trimmed = id?.trim()
    if (trimmed.isNullOrBlank()) return false
    return try {
        UUID.fromString(trimmed)
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Validates and preserves real PostgreSQL UUIDs.
 * - Generates random UUID only if id is null or blank (for new entities).
 * - Throws IllegalArgumentException for invalid string identifiers (including legacy placeholders like "farm-1").
 */
fun ensureValidUuid(id: String?): String {
    val trimmed = id?.trim()
    if (trimmed.isNullOrBlank()) return UUID.randomUUID().toString()
    return try {
        val uuid = UUID.fromString(trimmed)
        uuid.toString()
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid database UUID format: '$id'. Must be a valid UUID.")
    }
}

/**
 * Generates an ISO-8601 UTC timestamp string for PostgreSQL TIMESTAMPTZ columns.
 */
fun currentIsoTimestamp(): String {
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}

/**
 * Generates an ISO-8601 UTC timestamp string in the future for PostgreSQL TIMESTAMPTZ columns.
 */
fun futureIsoTimestamp(hours: Long = 24): String {
    val future = Date(System.currentTimeMillis() + hours * 3600 * 1000L)
    return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(future)
}

/**
 * Safely parses an ISO-8601 timestamp string from PostgreSQL TIMESTAMPTZ into epoch millis.
 */
fun parseIsoTimestamp(isoString: String?): Long? {
    if (isoString.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss"
    )
    for (pattern in patterns) {
        try {
            val sdf = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val parsed = sdf.parse(isoString)
            if (parsed != null) return parsed.time
        } catch (_: Exception) {}
    }
    return null
}

@Serializable
data class ProfileDto(
    val id: String = "",
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    @SerialName("is_phone_verified") val isPhoneVerified: Boolean? = null,
    @SerialName("phone_verified") val phoneVerified: Boolean? = null,
    val role: String? = "CUSTOMER",
    @Transient val farmId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_suspended") val isSuspended: Boolean? = false,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): UserProfile {
        val effectiveName = fullName?.takeIf { it.isNotBlank() } ?: "User"
        val effectivePhone = phone?.takeIf { it.isNotBlank() } ?: ""
        val effectiveVerified = (isPhoneVerified == true) || (phoneVerified == true)
        val effectiveRole = UserRole.fromString(role)
        return UserProfile(
            id = id,
            email = email ?: "",
            name = effectiveName,
            phone = effectivePhone,
            isPhoneVerified = effectiveVerified,
            role = effectiveRole,
            farmId = farmId,
            isSuspended = isSuspended ?: false
        )
    }

    companion object {
        fun fromDomain(domain: UserProfile): ProfileDto {
            return ProfileDto(
                id = ensureValidUuid(domain.id),
                email = domain.email.ifBlank { null },
                fullName = domain.name,
                phone = domain.phone.ifBlank { null },
                isPhoneVerified = domain.isPhoneVerified,
                role = domain.role.name,
                farmId = domain.farmId?.takeIf { it.isNotBlank() }?.let { ensureValidUuid(it) },
                isSuspended = domain.isSuspended,
                createdAt = currentIsoTimestamp()
            )
        }
    }
}

@Serializable
data class GoatDto(
    val id: String = "",
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("tag_number") val tagNumber: String = "",
    val name: String = "",
    @SerialName("breed_id") val breedId: String? = null,
    @SerialName("breed_name") val breedName: String = "Boer",
    val gender: String = "MALE",
    @SerialName("age_months") val ageMonths: Int = 0,
    @SerialName("weight_kg") val weightKg: Double = 0.0,
    val purpose: String = "BREEDING",
    val price: Double = 0.0,
    @SerialName("discount_percentage") val discountPercentage: Double = 0.0,
    val status: String = "AVAILABLE",
    val description: String? = null,
    @SerialName("vaccination_status") val vaccinationStatus: String? = "Fully Vaccinated",
    @SerialName("dewormed_date") val dewormedDate: String? = null,
    @SerialName("parentage_father_tag") val parentageFatherTag: String? = null,
    @SerialName("parentage_mother_tag") val parentageMotherTag: String? = null,
    @SerialName("is_approved_by_admin") val isApprovedByAdmin: Boolean = false,
    @Transient val listingFeePaid: Boolean? = null,
    @Transient val listingFeeAmount: Double? = null,
    @SerialName("is_featured") val isFeatured: Boolean = false,
    @Transient val rating: Double = 0.0,
    @Transient val reviewCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(
        resolvedFarmName: String? = null,
        resolvedFarmLocation: String? = null,
        resolvedPhotos: List<String>? = null
    ): Goat {
        val upperStatus = status.uppercase().trim()
        val effectiveStatus = when (upperStatus) {
            "AVAILABLE" -> AvailabilityStatus.AVAILABLE
            "RESERVED" -> AvailabilityStatus.RESERVED
            "BOOKING_PENDING" -> AvailabilityStatus.BOOKING_PENDING
            "CONFIRMED" -> AvailabilityStatus.CONFIRMED
            "SOLD", "COMPLETED" -> AvailabilityStatus.SOLD
            "CANCELLED" -> AvailabilityStatus.CANCELLED
            "REJECTED" -> AvailabilityStatus.REJECTED
            else -> AvailabilityStatus.AVAILABLE
        }

        val isAmmal = (resolvedFarmName?.contains("Ammal", ignoreCase = true) == true)
        val authoritativeFeePaid = if (isAmmal) true else (listingFeePaid ?: true)
        val authoritativeFeeAmount = if (isAmmal) 0.0 else (listingFeeAmount ?: 100.0)

        val effectiveApproval = when {
            upperStatus == "SUSPENDED" -> ApprovalStatus.SUSPENDED
            upperStatus == "REJECTED" -> ApprovalStatus.REJECTED
            upperStatus == "DRAFT" -> ApprovalStatus.DRAFT
            isApprovedByAdmin -> ApprovalStatus.APPROVED
            isAmmal && upperStatus != "SUSPENDED" && upperStatus != "REJECTED" -> ApprovalStatus.APPROVED
            else -> ApprovalStatus.PENDING_APPROVAL
        }

        val effectivePhotos = resolvedPhotos ?: emptyList()
        val mappedPhotos = effectivePhotos.map { photoPath ->
            SupabaseConfig.resolveStorageUrl(photoPath, SupabaseConfig.BUCKET_GOAT_IMAGES)
        }

        return Goat(
            id = id,
            name = name,
            tagNumber = tagNumber.ifBlank { "AF-${id.take(4).uppercase()}" },
            breed = breedName,
            gender = try {
                GoatGender.valueOf(gender.uppercase())
            } catch (_: Exception) {
                GoatGender.MALE
            },
            ageMonths = ageMonths,
            weightKg = weightKg,
            purpose = try {
                GoatPurpose.valueOf(purpose.uppercase())
            } catch (_: Exception) {
                GoatPurpose.BREEDING
            },
            description = description ?: "",
            price = price,
            discountPercentage = discountPercentage.coerceIn(0.0, 100.0),
            photos = mappedPhotos,
            farmId = farmId,
            farmName = resolvedFarmName ?: "Ammal Farm Partner",
            farmLocation = resolvedFarmLocation ?: "Tamil Nadu, India",
            availabilityStatus = effectiveStatus,
            approvalStatus = effectiveApproval,
            listingFeePaid = authoritativeFeePaid,
            listingFeeAmount = authoritativeFeeAmount,
            rating = rating,
            reviewCount = reviewCount,
            isFeatured = isFeatured
        )
    }

    companion object {
        fun fromDomain(domain: Goat): GoatDto {
            val nowIso = currentIsoTimestamp()
            val validTag = domain.tagNumber.ifBlank { "AF-${UUID.randomUUID().toString().take(6).uppercase()}" }
            return GoatDto(
                id = if (domain.id.isBlank() || domain.id.startsWith("goat-")) UUID.randomUUID().toString() else ensureValidUuid(domain.id),
                farmId = ensureValidUuid(domain.farmId),
                tagNumber = validTag,
                name = domain.name.ifBlank { "${domain.breed} Goat" },
                breedName = domain.breed.ifBlank { "Boer" },
                gender = domain.gender.name,
                ageMonths = domain.ageMonths.coerceAtLeast(1),
                weightKg = domain.weightKg.coerceAtLeast(1.0),
                purpose = domain.purpose.name,
                price = domain.price.coerceAtLeast(0.0),
                discountPercentage = domain.discountPercentage.coerceIn(0.0, 100.0),
                status = domain.availabilityStatus.name,
                isApprovedByAdmin = domain.approvalStatus == ApprovalStatus.APPROVED,
                listingFeePaid = domain.listingFeePaid,
                listingFeeAmount = domain.listingFeeAmount,
                description = domain.description.ifBlank { "High quality breed livestock." },
                vaccinationStatus = "Fully Vaccinated",
                isFeatured = domain.isFeatured,
                rating = domain.rating,
                reviewCount = domain.reviewCount,
                createdAt = nowIso,
                updatedAt = nowIso
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
data class FarmDto(
    val id: String = "",
    val name: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    val description: String? = null,
    @SerialName("location_district") val locationDistrict: String? = null,
    @SerialName("location_state") val locationState: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("contact_phone") val contactPhone: String? = null,
    @SerialName("contact_email") val contactEmail: String? = null,
    val status: String? = "APPROVED",
    @SerialName("is_ammal_own_farm") val isAmmalOwnFarm: Boolean = false,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val tagline: String? = null,
    @SerialName("goat_listing_limit") val goatListingLimit: Int? = 10,
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0
) {
    fun toDomain(totalGoats: Int = 0, customLimit: Int? = null): Farm {
        val effectiveDistrict = locationDistrict?.trim() ?: ""
        val effectiveState = locationState?.trim()?.ifBlank { "Tamil Nadu" } ?: "Tamil Nadu"
        val effectivePhone = contactPhone?.trim() ?: ""
        val effectiveEmail = contactEmail?.trim() ?: ""
        val effectiveStatus = try {
            VerificationStatus.valueOf((status ?: "APPROVED").uppercase())
        } catch (_: Exception) {
            VerificationStatus.APPROVED
        }

        // Ownership / hub status is resolved by DB column is_ammal_own_farm or seeded central hub UUID
        val isAmmal = isAmmalOwnFarm || id == SEED_AMMAL_FARM_UUID
        val effectiveLimit = customLimit ?: (if (isAmmal) 1000 else (goatListingLimit ?: 10))
        val locationStr = if (effectiveDistrict.isNotBlank()) "$effectiveDistrict, $effectiveState" else effectiveState

        return Farm(
            id = id,
            name = name,
            ownerId = ownerId,
            ownerName = if (isAmmal) "Ammal Farm Central Hub" else name,
            location = locationStr,
            state = effectiveState,
            contactNumber = effectivePhone,
            email = effectiveEmail,
            description = description ?: "",
            logoUrl = SupabaseConfig.resolveStorageUrl(logoUrl, SupabaseConfig.BUCKET_GOAT_IMAGES),
            bannerUrl = SupabaseConfig.resolveStorageUrl(bannerUrl, SupabaseConfig.BUCKET_GOAT_IMAGES),
            verificationStatus = if (isAmmal) VerificationStatus.APPROVED else effectiveStatus,
            isAmmalOwnFarm = isAmmal,
            rating = rating,
            totalReviews = reviewCount,
            totalGoatsListed = totalGoats,
            goatListingLimit = effectiveLimit
        )
    }

    companion object {
        fun fromDomain(domain: Farm): FarmDto {
            val isAmmal = domain.isAmmalOwnFarm || domain.id == SEED_AMMAL_FARM_UUID
            val nowIso = currentIsoTimestamp()
            val district = if (domain.location.contains(",")) domain.location.substringBefore(",").trim() else domain.location.trim()
            return FarmDto(
                id = ensureValidUuid(domain.id),
                name = domain.name.trim(),
                ownerId = ensureValidUuid(domain.ownerId),
                locationDistrict = district.ifBlank { null },
                locationState = domain.state.trim().ifBlank { "Tamil Nadu" },
                address = if (district.isNotBlank()) "$district, ${domain.state.trim().ifBlank { "Tamil Nadu" }}" else domain.state.trim().ifBlank { "Tamil Nadu" },
                contactPhone = domain.contactNumber.trim().ifBlank { null },
                contactEmail = domain.email.trim().ifBlank { null },
                status = if (isAmmal) "APPROVED" else (if (domain.verificationStatus == VerificationStatus.PENDING) "APPROVED" else domain.verificationStatus.name),
                isAmmalOwnFarm = isAmmal,
                goatListingLimit = domain.goatListingLimit,
                description = domain.description.takeIf { it.isNotBlank() },
                logoUrl = domain.logoUrl.takeIf { it.isNotBlank() },
                bannerUrl = domain.bannerUrl.takeIf { it.isNotBlank() },
                rating = domain.rating,
                reviewCount = domain.totalReviews,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        }
    }
}

@Serializable
data class BookingDto(
    val id: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("customer_id") val customerId: String = "",
    val status: String = "PENDING",
    @SerialName("total_price") val totalPrice: Double = 0.0,
    @SerialName("deposit_paid") val depositPaid: Double = 0.0,
    @SerialName("customer_notes") val customerNotes: String? = null,
    @SerialName("admin_notes") val adminNotes: String? = null,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("booking_date") val bookingDate: String = currentIsoTimestamp(),
    @SerialName("hold_expires_at") val holdExpiresAt: String = futureIsoTimestamp(24),
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(
        resolvedGoatName: String? = null,
        resolvedGoatBreed: String? = null,
        resolvedGoatPhoto: String? = null,
        resolvedFarmName: String? = null,
        resolvedCustomerName: String? = null,
        resolvedCustomerPhone: String? = null
    ): Booking {
        val effectiveStatus = try {
            when (status.uppercase()) {
                "PENDING", "RESERVED" -> AvailabilityStatus.BOOKING_PENDING
                "CONFIRMED" -> AvailabilityStatus.CONFIRMED
                "COMPLETED" -> AvailabilityStatus.COMPLETED
                "CANCELLED", "EXPIRED" -> AvailabilityStatus.CANCELLED
                else -> AvailabilityStatus.BOOKING_PENDING
            }
        } catch (_: Exception) {
            AvailabilityStatus.BOOKING_PENDING
        }

        val parsedBookingDate = parseIsoTimestamp(bookingDate) ?: parseIsoTimestamp(createdAt) ?: System.currentTimeMillis()
        val parsedExpiryDate = parseIsoTimestamp(holdExpiresAt) ?: (parsedBookingDate + (24 * 3600 * 1000L))

        return Booking(
            id = id,
            goatId = goatId,
            goatName = resolvedGoatName ?: "Goat Listing #$goatId",
            goatBreed = resolvedGoatBreed ?: "Certified Breed",
            goatPhoto = resolvedGoatPhoto ?: "",
            customerId = customerId,
            customerName = resolvedCustomerName ?: "Customer",
            customerPhone = resolvedCustomerPhone ?: "",
            farmId = farmId,
            farmName = resolvedFarmName ?: "Ammal Farm Partner",
            amount = totalPrice,
            status = effectiveStatus,
            bookingDate = parsedBookingDate,
            reservationExpiryDate = parsedExpiryDate,
            notes = customerNotes ?: ""
        )
    }

    companion object {
        fun fromDomain(domain: Booking): BookingDto {
            val nowIso = currentIsoTimestamp()
            val expiresIso = futureIsoTimestamp(24)
            return BookingDto(
                id = ensureValidUuid(domain.id),
                goatId = ensureValidUuid(domain.goatId),
                farmId = ensureValidUuid(domain.farmId),
                customerId = ensureValidUuid(domain.customerId),
                status = domain.status.name,
                totalPrice = domain.amount,
                depositPaid = 0.0,
                customerNotes = domain.notes,
                bookingDate = nowIso,
                holdExpiresAt = expiresIso,
                createdAt = nowIso,
                updatedAt = nowIso
            )
        }
    }
}

@Serializable
data class BreedDto(
    val id: String = "",
    val name: String = "",
    val origin: String? = null,
    @SerialName("primary_purpose") val primaryPurpose: String? = null,
    val description: String? = null,
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class NotificationDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    val title: String = "",
    val body: String = "",
    @SerialName("link_type") val linkType: String? = null,
    @SerialName("link_id") val linkId: String? = null,
    @SerialName("event_key") val eventKey: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): AppNotification {
        val parsedType = linkType?.let {
            try { NotificationType.valueOf(it.uppercase()) } catch (_: Exception) { null }
        } ?: NotificationType.SYSTEM_ALERT

        val route = com.example.util.DeepLinkUtils.resolveDeepLinkRoute(parsedType, linkId)

        val parsedTime = parseIsoTimestamp(createdAt) ?: System.currentTimeMillis()

        return AppNotification(
            id = id,
            recipientUserId = userId,
            targetRole = UserRole.CUSTOMER,
            title = title,
            message = body,
            type = parsedType,
            timestamp = parsedTime,
            isRead = isRead,
            referenceId = linkId,
            deepLinkRoute = route,
            eventKey = eventKey
        )
    }

    companion object {
        fun fromDomain(domain: AppNotification): NotificationDto {
            val nowIso = currentIsoTimestamp()
            return NotificationDto(
                id = ensureValidUuid(domain.id),
                userId = ensureValidUuid(domain.recipientUserId),
                title = domain.title,
                body = domain.message,
                linkType = domain.type.name,
                linkId = domain.referenceId,
                eventKey = domain.eventKey,
                isRead = domain.isRead,
                createdAt = nowIso
            )
        }
    }
}

@Serializable
data class ReportDto(
    val id: String = "",
    @SerialName("reporter_id") val reporterId: String = "",
    @SerialName("target_type") val targetType: String = "GOAT",
    @SerialName("target_id") val targetId: String = "",
    val reason: String = "OTHER",
    val description: String? = null,
    val status: String = "PENDING",
    @SerialName("resolution_notes") val resolutionNotes: String? = null,
    @SerialName("resolved_by") val resolvedBy: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(
        resolvedReporterName: String? = null,
        resolvedReporterEmail: String? = null,
        resolvedTargetTitle: String? = null
    ): PlatformReport {
        return PlatformReport(
            id = id,
            reporterId = reporterId,
            reporterName = resolvedReporterName ?: "Customer",
            reporterEmail = resolvedReporterEmail ?: "",
            targetType = targetType,
            targetId = targetId,
            targetTitle = resolvedTargetTitle ?: "Target #$targetId",
            reason = ReportReason.fromString(reason),
            description = description ?: "",
            evidencePhotoUrl = null,
            evidencePhotos = emptyList(),
            status = ReportStatus.fromString(status),
            resolutionNotes = resolutionNotes,
            adminActionTaken = null,
            createdAt = System.currentTimeMillis()
        )
    }

    companion object {
        fun fromDomain(domain: PlatformReport): ReportDto {
            val nowIso = currentIsoTimestamp()
            return ReportDto(
                id = ensureValidUuid(domain.id),
                reporterId = ensureValidUuid(domain.reporterId),
                targetType = domain.targetType,
                targetId = ensureValidUuid(domain.targetId),
                reason = domain.reason.name,
                description = domain.description,
                status = domain.status.name,
                resolutionNotes = domain.resolutionNotes,
                createdAt = nowIso
            )
        }
    }
}

@Serializable
data class WishlistItemDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(resolvedGoat: Goat? = null): WishlistItem {
        return WishlistItem(
            id = id,
            userId = userId,
            goatId = goatId,
            createdAt = System.currentTimeMillis(),
            goat = resolvedGoat
        )
    }

    companion object {
        fun fromDomain(domain: WishlistItem): WishlistItemDto {
            val nowIso = currentIsoTimestamp()
            return WishlistItemDto(
                id = ensureValidUuid(domain.id),
                userId = ensureValidUuid(domain.userId),
                goatId = ensureValidUuid(domain.goatId),
                createdAt = nowIso
            )
        }
    }
}

@Serializable
data class ListingPaymentDto(
    val id: String = "",
    @SerialName("goat_id") val goatId: String? = null,
    @SerialName("farm_id") val farmId: String? = null,
    @SerialName("booking_id") val bookingId: String? = null,
    @SerialName("payer_id") val payerId: String = "",
    val amount: Double = 0.0,
    val currency: String = "INR",
    @SerialName("payment_type") val paymentType: String = "LISTING_FEE",
    @SerialName("payment_status") val paymentStatus: String = "PENDING",
    @SerialName("payment_gateway_ref") val paymentGatewayRef: String? = null,
    @SerialName("razorpay_payment_id") val razorpayPaymentId: String? = null,
    @SerialName("razorpay_signature") val razorpaySignature: String? = null,
    @SerialName("receipt_number") val receiptNumber: String? = null,
    @SerialName("payment_date") val paymentDate: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    fun toDomain(goatName: String = "", goatTag: String = "", farmName: String = ""): ListingPayment {
        val parsedStatus = try {
            PaymentStatus.valueOf(paymentStatus.uppercase())
        } catch (_: Exception) {
            when (paymentStatus.uppercase()) {
                "COMPLETED", "PAID" -> PaymentStatus.PAID
                "FAILED" -> PaymentStatus.FAILED
                "CANCELLED" -> PaymentStatus.CANCELLED
                else -> PaymentStatus.PENDING
            }
        }
        val timestamp = parseIsoTimestamp(paymentDate ?: createdAt)
        return ListingPayment(
            id = id,
            goatId = goatId ?: "",
            goatName = goatName,
            goatTag = goatTag,
            farmId = farmId ?: "",
            farmName = farmName,
            amount = amount,
            currency = currency,
            status = parsedStatus,
            orderId = paymentGatewayRef,
            razorpayPaymentId = razorpayPaymentId,
            receiptNumber = receiptNumber,
            createdAt = timestamp ?: System.currentTimeMillis()
        )
    }
}

@Serializable
data class PaymentInitiationRpcResponse(
    val success: Boolean = false,
    @SerialName("fee_required") val feeRequired: Boolean = true,
    val amount: Double = 100.0,
    val currency: String = "INR",
    @SerialName("order_id") val orderId: String? = null,
    @SerialName("payment_record_id") val paymentRecordId: String? = null,
    @SerialName("goat_id") val goatId: String? = null,
    @SerialName("already_paid") val alreadyPaid: Boolean = false,
    val message: String? = null
)

@Serializable
data class PaymentVerificationRpcResponse(
    val success: Boolean = false,
    val message: String? = null,
    @SerialName("goat_id") val goatId: String? = null,
    @SerialName("payment_id") val paymentId: String? = null,
    @SerialName("receipt_number") val receiptNumber: String? = null,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("already_paid") val alreadyPaid: Boolean = false,
    @SerialName("approval_status") val approvalStatus: String? = "PENDING_APPROVAL"
)

@Serializable
data class GoatDeletionResponse(
    val success: Boolean = false,
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("deleted_images") val deletedImages: List<String> = emptyList(),
    @SerialName("historical_bookings_preserved") val historicalBookingsPreserved: Int = 0
)
