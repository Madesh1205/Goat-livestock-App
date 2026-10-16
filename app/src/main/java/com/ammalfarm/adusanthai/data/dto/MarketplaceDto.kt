package com.ammalfarm.adusanthai.data.dto

import com.ammalfarm.adusanthai.model.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val SEED_AMMAL_FARM_UUID = "00000000-0000-0000-0000-000000000001"

fun ensureValidUuid(id: String?): String {
    if (id.isNullOrBlank()) return SEED_AMMAL_FARM_UUID
    return try {
        java.util.UUID.fromString(id)
        id
    } catch (e: Exception) {
        SEED_AMMAL_FARM_UUID
    }
}

fun currentIsoTimestamp(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
}

@Serializable
data class WishlistItemDto(
    val id: String = "",
    @SerialName("user_id") val userId: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class GoatImageDto(
    val id: String = "",
    @SerialName("goat_id") val goatId: String = "",
    @SerialName("image_url") val imageUrl: String = "",
    @SerialName("is_primary") val isPrimary: Boolean = false
)

@Serializable
data class GoatDto(
    val id: String = "",
    @SerialName("goat_code") val goatCode: String? = null,
    @SerialName("farm_id") val farmId: String = "",
    @SerialName("farm_code") val farmCode: String? = null,
    val name: String = "",
    @SerialName("breed_name") val breedName: String = "Boer",
    val gender: String = "MALE",
    @SerialName("age_months") val ageMonths: Int = 0,
    @SerialName("weight_kg") val weightKg: Double = 0.0,
    val purpose: String = "BREEDING",
    val price: Double = 0.0,
    val description: String? = null
)

@Serializable
data class ProfileDto(
    val id: String = "",
    val email: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    @Transient val isPhoneVerified: Boolean? = null,
    val role: String? = "CUSTOMER",
    @SerialName("farm_id") val farmId: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_suspended") val isSuspended: Boolean? = false,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): UserProfile {
        val effectiveName = fullName?.takeIf { it.isNotBlank() } ?: "User"
        val effectivePhone = phone?.takeIf { it.isNotBlank() } ?: ""
        val effectiveRole = UserRole.fromString(role)
        return UserProfile(
            id = id,
            email = email ?: "",
            name = effectiveName,
            phone = effectivePhone,
            isPhoneVerified = isPhoneVerified == true,
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
                role = domain.role.name,
                farmId = domain.farmId?.takeIf { it.isNotBlank() }?.let { ensureValidUuid(it) },
                isSuspended = domain.isSuspended,
                createdAt = currentIsoTimestamp()
            )
        }
    }
}

@Serializable
data class FarmDto(
    val id: String = "",
    @SerialName("farm_code") val farmCode: String? = null,
    val name: String = "",
    @SerialName("owner_id") val ownerId: String = "",
    val description: String? = null,
    @SerialName("location_district") val locationDistrict: String? = null,
    @SerialName("location_state") val locationState: String? = null,
    @SerialName("address") val address: String? = null,
    @SerialName("contact_phone") val contactPhone: String? = null,
    @SerialName("contact_email") val contactEmail: String? = null,
    val status: String? = null,
    @SerialName("is_ammal_own_farm") val isAmmalOwnFarm: Boolean = false,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("logo_url") val logoUrl: String? = null,
    @SerialName("banner_url") val bannerUrl: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val tagline: String? = null,
    @SerialName("goat_listing_limit") val goatListingLimit: Int? = 0,
    @SerialName("consumed_listing_slots") val consumedListingSlots: Int? = null,
    val rating: Double = 0.0,
    @SerialName("review_count") val reviewCount: Int = 0
) {
    fun toDomain(totalGoats: Int = 0, customLimit: Int? = null): Farm {
        val effectiveDistrict = locationDistrict?.trim() ?: ""
        val effectiveState = locationState?.trim()?.ifBlank { "Tamil Nadu" } ?: "Tamil Nadu"
        val effectivePhone = contactPhone?.trim() ?: ""
        val effectiveEmail = contactEmail?.trim() ?: ""
        val isAmmal = isAmmalOwnFarm || id == SEED_AMMAL_FARM_UUID
        val effectiveStatus = try {
            VerificationStatus.valueOf(status?.uppercase() ?: "PENDING")
        } catch (_: Exception) {
            VerificationStatus.PENDING
        }

        return Farm(
            id = id,
            farmCode = farmCode ?: "",
            name = name,
            ownerId = ownerId,
            location = if (effectiveDistrict.isNotBlank()) "$effectiveDistrict, $effectiveState" else effectiveState,
            description = description ?: "",
            contactNumber = effectivePhone,
            email = effectiveEmail,
            verificationStatus = effectiveStatus,
            approvalStatus = if (effectiveStatus == VerificationStatus.APPROVED) ApprovalStatus.APPROVED else ApprovalStatus.PENDING,
            goatListingLimit = customLimit ?: (goatListingLimit ?: 5),
            consumedListingSlots = consumedListingSlots ?: totalGoats,
            isAmmalOwnFarm = isAmmal,
            logoUrl = logoUrl ?: "",
            bannerUrl = bannerUrl
        )
    }

    companion object {
        fun fromDomain(farm: Farm): FarmDto {
            return FarmDto(
                id = ensureValidUuid(farm.id),
                farmCode = farm.farmCode.ifBlank { null },
                name = farm.name,
                ownerId = ensureValidUuid(farm.ownerId),
                description = farm.description.ifBlank { null },
                contactPhone = farm.contactNumber.ifBlank { null },
                contactEmail = farm.email.ifBlank { null },
                status = farm.verificationStatus.name,
                isAmmalOwnFarm = farm.isAmmalOwnFarm,
                logoUrl = farm.logoUrl.ifBlank { null },
                bannerUrl = farm.bannerUrl?.ifBlank { null },
                goatListingLimit = farm.goatListingLimit,
                consumedListingSlots = farm.consumedListingSlots,
                rating = farm.rating,
                reviewCount = farm.reviewCount
            )
        }
    }
}

@Serializable
data class ReportDto(
    val id: String = "",
    @SerialName("reporter_id") val reporterId: String = "",
    @SerialName("reporter_name") val reporterName: String = "",
    @SerialName("reporter_email") val reporterEmail: String = "",
    @SerialName("target_type") val targetType: String = "",
    @SerialName("target_id") val targetId: String = "",
    @SerialName("target_title") val targetTitle: String = "",
    val reason: String = "",
    val description: String = "",
    @SerialName("evidence_photo_url") val evidencePhotoUrl: String? = null,
    val status: String = "PENDING",
    @SerialName("resolution_notes") val resolutionNotes: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    fun toDomain(): PlatformReport {
        return PlatformReport(
            id = id,
            reporterId = reporterId,
            reporterName = reporterName,
            reporterEmail = reporterEmail,
            targetType = targetType,
            targetId = targetId,
            targetTitle = targetTitle,
            reason = reason,
            description = description,
            evidencePhotoUrl = evidencePhotoUrl,
            status = status,
            resolutionNotes = resolutionNotes ?: ""
        )
    }

    companion object {
        fun fromDomain(report: PlatformReport): ReportDto {
            return ReportDto(
                id = ensureValidUuid(report.id),
                reporterId = ensureValidUuid(report.reporterId),
                reporterName = report.reporterName,
                reporterEmail = report.reporterEmail,
                targetType = report.targetType.ifBlank { "GOAT" },
                targetId = ensureValidUuid(report.targetId),
                targetTitle = report.targetTitle,
                reason = report.reason,
                description = report.description,
                evidencePhotoUrl = report.evidencePhotoUrl,
                status = report.status.ifBlank { "PENDING" },
                resolutionNotes = report.resolutionNotes.ifBlank { null },
                createdAt = currentIsoTimestamp()
            )
        }
    }
}
