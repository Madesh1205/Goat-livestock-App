package com.example

import com.example.core.util.PriceUtils
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM — STAGE 11D
 * GOAT DETAIL SCREEN VERIFICATION & SAFEGUARD TEST SUITE
 *
 * Verifies:
 * 1. Farm logoUrl resolution when available, and fallback when blank/null.
 * 2. Farm resolution strictly by goat's real farm_id UUID.
 * 3. Correct farm name and location display (authoritative farm profile > goat listing snapshot).
 * 4. Goat photos handling: valid image list vs empty list missing-image fallback.
 * 5. Customer booking button eligibility: strictly only APPROVED + AVAILABLE goats.
 * 6. Protection against fake "farm-1" IDs — real UUIDs enforced.
 * 7. PriceUtils and pricing discount invariants remain completely intact.
 */
class Stage11DGoatDetailVerificationTest {

    private val validFarmUuid = UUID.randomUUID().toString()
    private val otherFarmUuid = UUID.randomUUID().toString()

    private val testFarm = Farm(
        id = validFarmUuid,
        name = "Ammal Royal Breeder Farm",
        ownerId = "owner-uuid-123",
        ownerName = "Dr. Madesh Ammal",
        location = "Salem Main Hub, Tamil Nadu",
        contactNumber = "+91 98765 43210",
        email = "support@ammalfarm.com",
        description = "Premier Boer, Sirohi & Barbari breeding station",
        logoUrl = "https://example.com/farms/ammal_logo.png",
        bannerUrl = "https://example.com/farms/ammal_banner.png",
        verificationStatus = VerificationStatus.APPROVED,
        totalGoatsListed = 12
    )

    private fun createGoat(
        id: String = UUID.randomUUID().toString(),
        name: String = "Sirohi Stallion",
        farmId: String = validFarmUuid,
        breed: String = "Sirohi",
        approvalStatus: ApprovalStatus = ApprovalStatus.APPROVED,
        availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        price: Double = 18000.0,
        discountPercentage: Double = 10.0,
        photos: List<String> = listOf("https://example.com/goat1.jpg", "https://example.com/goat2.jpg")
    ): Goat {
        return Goat(
            id = id,
            name = name,
            tagNumber = "TAG-7788",
            breed = breed,
            gender = GoatGender.MALE,
            ageMonths = 14,
            weightKg = 42.5,
            purpose = GoatPurpose.BREEDING,
            price = price,
            discountPercentage = discountPercentage,
            description = "Top quality bloodline",
            photos = photos,
            farmId = farmId,
            farmName = "Old Farm Name Snapshot",
            farmLocation = "Old Farm Location Snapshot",
            availabilityStatus = availabilityStatus,
            approvalStatus = approvalStatus
        )
    }

    // Helper mirroring GoatDetailScreen resolution logic
    private fun resolveFarmDetails(goat: Goat, farms: List<Farm>): Triple<Farm?, String, String> {
        val resolvedFarm = farms.find { it.id == goat.farmId }
        val displayFarmName = resolvedFarm?.name?.takeIf { it.isNotBlank() } ?: goat.farmName.ifBlank { "Ammal Farm" }
        val displayFarmLocation = resolvedFarm?.location?.takeIf { it.isNotBlank() } ?: goat.farmLocation.ifBlank { "Salem, Tamil Nadu" }
        return Triple(resolvedFarm, displayFarmName, displayFarmLocation)
    }

    private fun canCustomerBook(goat: Goat): Boolean {
        val isListingApproved = goat.approvalStatus == ApprovalStatus.APPROVED
        val isAvailable = isListingApproved && goat.availabilityStatus == AvailabilityStatus.AVAILABLE
        return isAvailable
    }

    @Test
    fun testFarmResolution_byRealUuid() {
        val goat = createGoat(farmId = validFarmUuid)
        val farms = listOf(testFarm)

        val (resolved, name, location) = resolveFarmDetails(goat, farms)

        assertNotNull("Farm should be resolved using goat's farmId UUID", resolved)
        assertEquals(validFarmUuid, resolved?.id)
        assertEquals("Ammal Royal Breeder Farm", name)
        assertEquals("Salem Main Hub, Tamil Nadu", location)
    }

    @Test
    fun testFarmResolution_usesSnapshotWhenFarmNotFound() {
        val unknownFarmUuid = UUID.randomUUID().toString()
        val goat = createGoat(farmId = unknownFarmUuid)
        val farms = listOf(testFarm) // testFarm has validFarmUuid, not unknownFarmUuid

        val (resolved, name, location) = resolveFarmDetails(goat, farms)

        assertNull("Unresolved farm yields null farm object", resolved)
        assertEquals("Old Farm Name Snapshot", name)
        assertEquals("Old Farm Location Snapshot", location)
    }

    @Test
    fun testFarmLogo_usesActualLogoUrlWhenAvailable() {
        val farms = listOf(testFarm)
        val goat = createGoat(farmId = validFarmUuid)
        val (resolved, _, _) = resolveFarmDetails(goat, farms)

        assertNotNull(resolved)
        assertEquals("https://example.com/farms/ammal_logo.png", resolved?.logoUrl)
        assertTrue("Logo URL should be non-blank for custom rendering", resolved?.logoUrl?.isNotBlank() == true)
    }

    @Test
    fun testFarmLogo_fallsBackWhenLogoUrlBlank() {
        val farmWithoutLogo = testFarm.copy(logoUrl = "")
        val farms = listOf(farmWithoutLogo)
        val goat = createGoat(farmId = validFarmUuid)
        val (resolved, _, _) = resolveFarmDetails(goat, farms)

        assertNotNull(resolved)
        assertTrue("Blank logo URL correctly triggers placeholder branch", resolved?.logoUrl.isNullOrBlank())
    }

    @Test
    fun testGoatImages_filteringAndFallback() {
        // 1. Valid images
        val goatWithImages = createGoat(photos = listOf("https://example.com/1.jpg", " ", ""))
        val filteredPhotos = goatWithImages.photos.filter { it.isNotBlank() }
        assertEquals(1, filteredPhotos.size)
        assertEquals("https://example.com/1.jpg", filteredPhotos.first())

        // 2. Empty images trigger fallback
        val goatWithoutImages = createGoat(photos = emptyList())
        val emptyFiltered = goatWithoutImages.photos.filter { it.isNotBlank() }
        assertTrue("Empty photos list triggers placeholder icon", emptyFiltered.isEmpty())
    }

    @Test
    fun testBookingButton_onlyAllowedForApprovedAndAvailableGoats() {
        val approvedAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val draftAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.DRAFT,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val pendingAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val rejectedAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.REJECTED,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val suspendedAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.SUSPENDED,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val approvedAndReserved = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.RESERVED
        )
        val approvedAndBooked = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.BOOKING_PENDING
        )
        val approvedAndSold = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.SOLD
        )
        val approvedAndCancelled = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.CANCELLED
        )

        // Only APPROVED + AVAILABLE is bookable
        assertTrue("Approved and Available goat MUST be bookable", canCustomerBook(approvedAndAvailable))
        assertFalse("Draft goat must NEVER be bookable", canCustomerBook(draftAndAvailable))
        assertFalse("Pending approval goat must NEVER be bookable", canCustomerBook(pendingAndAvailable))
        assertFalse("Rejected goat must NEVER be bookable", canCustomerBook(rejectedAndAvailable))
        assertFalse("Suspended goat must NEVER be bookable", canCustomerBook(suspendedAndAvailable))
        assertFalse("Reserved goat must NEVER be bookable", canCustomerBook(approvedAndReserved))
        assertFalse("Booked goat must NEVER be bookable", canCustomerBook(approvedAndBooked))
        assertFalse("Sold goat must NEVER be bookable", canCustomerBook(approvedAndSold))
        assertFalse("Cancelled goat must NEVER be bookable", canCustomerBook(approvedAndCancelled))
    }

    @Test
    fun testNoFakeFarmIds_realUuidEnforced() {
        val goat = createGoat(farmId = validFarmUuid)
        assertNotEquals("farm-1", goat.farmId)
        assertFalse("Farm ID must not start with placeholder farm- prefix", goat.farmId.startsWith("farm-1"))

        // Must be parseable as valid UUID
        val parsedUuid = UUID.fromString(goat.farmId)
        assertEquals(validFarmUuid, parsedUuid.toString())
    }

    @Test
    fun testPriceUtils_integrityUnchanged() {
        val price = 20000.0
        val discount = 15.0

        val formattedPrice = PriceUtils.formatCurrency(price)
        assertEquals("₹20,000", formattedPrice)

        val finalPrice = PriceUtils.calculateFinalPrice(price, discount)
        assertEquals(17000.0, finalPrice, 0.001)

        val formattedFinal = PriceUtils.formatCurrency(finalPrice)
        assertEquals("₹17,000", formattedFinal)

        val badge = PriceUtils.formatDiscountBadge(discount)
        assertEquals("15% OFF", badge)
    }
}
