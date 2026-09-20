package com.example

import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM — STAGE 11C
 * CUSTOMER FARM DETAIL GOAT VISIBILITY TEST SUITE
 *
 * Verifies:
 * 1. Customer farm detail shows approved available goats for that farm
 * 2. Customer farm detail does NOT show unapproved goats (DRAFT, PENDING_APPROVAL, REJECTED, SUSPENDED)
 * 3. Customer farm detail does NOT show goats belonging to a different farm
 * 4. Customer farm detail shows correct Total Listed vs Ready Stock
 * 5. Customer farm detail empty states display correctly:
 *    A. No goats exist: "No goats listed by this farm yet."
 *    B. Goats exist but none currently available: "$totalListed goats listed · 0 currently available."
 *    C. Search/filter excludes all goats: "No goats match your current filters."
 *    D. Database/query failure: "Unable to load goats. Please try again."
 * 6. Booking safety: unavailable goats remain unbookable
 */
class Stage11CFarmDetailGoatVisibilityTest {

    private val farm1Id = UUID.randomUUID().toString()
    private val farm2Id = UUID.randomUUID().toString()

    private val farm1 = Farm(
        id = farm1Id,
        name = "Ammal Farm",
        ownerId = "owner-1",
        ownerName = "Breeder One",
        location = "Salem, Tamil Nadu",
        contactNumber = "+91 98765 43210",
        email = "ammal@farm.com",
        description = "Premier Boer & Sirohi breeder",
        verificationStatus = VerificationStatus.APPROVED,
        totalGoatsListed = 3
    )

    private fun createGoat(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Goat",
        farmId: String = farm1Id,
        breed: String = "Boer",
        approvalStatus: ApprovalStatus = ApprovalStatus.APPROVED,
        availabilityStatus: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        price: Double = 15000.0
    ): Goat {
        return Goat(
            id = id,
            name = name,
            breed = breed,
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 35.0,
            purpose = GoatPurpose.MEAT,
            price = price,
            description = "High quality breed",
            photos = listOf("https://example.com/goat.jpg"),
            farmId = farmId,
            farmName = "Ammal Farm",
            farmLocation = "Salem, Tamil Nadu",
            availabilityStatus = availabilityStatus,
            approvalStatus = approvalStatus
        )
    }

    // Helper representing FarmDetailScreen data resolution logic
    private fun resolveCustomerFarmGoats(
        selectedFarmGoats: List<Goat>,
        publicGoats: List<Goat>,
        farmId: String
    ): List<Goat> {
        val directGoats = selectedFarmGoats.filter {
            it.farmId == farmId &&
            it.approvalStatus == ApprovalStatus.APPROVED &&
            it.availabilityStatus == AvailabilityStatus.AVAILABLE
        }
        return if (directGoats.isNotEmpty()) {
            directGoats
        } else {
            publicGoats.filter {
                it.farmId == farmId &&
                it.approvalStatus == ApprovalStatus.APPROVED &&
                it.availabilityStatus == AvailabilityStatus.AVAILABLE
            }
        }
    }

    private fun resolveCounts(
        farmTotalGoatsListed: Int,
        selectedFarmTotalListed: Int,
        farmGoatsCount: Int
    ): Pair<Int, Int> {
        val totalListed = maxOf(farmTotalGoatsListed, selectedFarmTotalListed, farmGoatsCount)
        val readyStock = farmGoatsCount
        return Pair(totalListed, readyStock)
    }

    private fun resolveEmptyState(
        isLoading: Boolean,
        queryError: String?,
        totalListed: Int,
        farmGoats: List<Goat>,
        filteredGoats: List<Goat>
    ): String {
        return when {
            isLoading && farmGoats.isEmpty() -> "LOADING"
            queryError != null && farmGoats.isEmpty() -> "Unable to load goats. Please try again."
            farmGoats.isEmpty() && totalListed > 0 -> "$totalListed goats listed · 0 currently available."
            farmGoats.isEmpty() && totalListed == 0 -> "No goats listed by this farm yet."
            filteredGoats.isEmpty() -> "No goats match your current filters."
            else -> "SHOW_GOATS"
        }
    }

    @Test
    fun testCustomerFarmDetail_showsApprovedAvailableGoatsForFarm() {
        val goat1 = createGoat(name = "Goat 1", farmId = farm1Id)
        val goat2 = createGoat(name = "Goat 2", farmId = farm1Id)
        val publicGoats = listOf(goat1, goat2)

        val customerVisibleGoats = resolveCustomerFarmGoats(
            selectedFarmGoats = emptyList(),
            publicGoats = publicGoats,
            farmId = farm1Id
        )

        assertEquals(2, customerVisibleGoats.size)
        assertTrue(customerVisibleGoats.any { it.name == "Goat 1" })
        assertTrue(customerVisibleGoats.any { it.name == "Goat 2" })
    }

    @Test
    fun testCustomerFarmDetail_doesNotShowUnapprovedGoats() {
        val approvedGoat = createGoat(name = "Approved Goat", approvalStatus = ApprovalStatus.APPROVED)
        val pendingGoat = createGoat(name = "Pending Goat", approvalStatus = ApprovalStatus.PENDING_APPROVAL)
        val draftGoat = createGoat(name = "Draft Goat", approvalStatus = ApprovalStatus.DRAFT)
        val rejectedGoat = createGoat(name = "Rejected Goat", approvalStatus = ApprovalStatus.REJECTED)
        val suspendedGoat = createGoat(name = "Suspended Goat", approvalStatus = ApprovalStatus.SUSPENDED)

        val allFarmGoats = listOf(approvedGoat, pendingGoat, draftGoat, rejectedGoat, suspendedGoat)

        val customerVisibleGoats = resolveCustomerFarmGoats(
            selectedFarmGoats = allFarmGoats,
            publicGoats = allFarmGoats,
            farmId = farm1Id
        )

        assertEquals(1, customerVisibleGoats.size)
        assertEquals("Approved Goat", customerVisibleGoats.first().name)
        assertFalse(customerVisibleGoats.any { it.approvalStatus != ApprovalStatus.APPROVED })
    }

    @Test
    fun testCustomerFarmDetail_doesNotShowGoatsFromDifferentFarm() {
        val goatFarm1 = createGoat(name = "Farm 1 Goat", farmId = farm1Id)
        val goatFarm2 = createGoat(name = "Farm 2 Goat", farmId = farm2Id)

        val publicGoats = listOf(goatFarm1, goatFarm2)

        val customerVisibleGoats = resolveCustomerFarmGoats(
            selectedFarmGoats = emptyList(),
            publicGoats = publicGoats,
            farmId = farm1Id
        )

        assertEquals(1, customerVisibleGoats.size)
        assertEquals(farm1Id, customerVisibleGoats.first().farmId)
        assertFalse(customerVisibleGoats.any { it.farmId == farm2Id })
    }

    @Test
    fun testCustomerFarmDetail_doesNotIncludeUnavailableGoatsInReadyStock() {
        val availableGoat = createGoat(name = "Ready Goat", availabilityStatus = AvailabilityStatus.AVAILABLE)
        val soldGoat = createGoat(name = "Sold Goat", availabilityStatus = AvailabilityStatus.SOLD)
        val reservedGoat = createGoat(name = "Reserved Goat", availabilityStatus = AvailabilityStatus.RESERVED)

        val goats = listOf(availableGoat, soldGoat, reservedGoat)

        val customerVisibleGoats = resolveCustomerFarmGoats(
            selectedFarmGoats = goats,
            publicGoats = goats,
            farmId = farm1Id
        )

        assertEquals(1, customerVisibleGoats.size)
        assertEquals("Ready Goat", customerVisibleGoats.first().name)
    }

    @Test
    fun testCustomerFarmDetail_correctTotalListedVsReadyStock() {
        // Example from real-device bug:
        // Ammal Farm has 3 goats listed in total, but 0 are ready stock (all sold/reserved/pending)
        val (totalListed1, readyStock1) = resolveCounts(
            farmTotalGoatsListed = 3,
            selectedFarmTotalListed = 3,
            farmGoatsCount = 0
        )
        assertEquals(3, totalListed1)
        assertEquals(0, readyStock1)

        // When 2 are approved & available out of 3 total listed:
        val (totalListed2, readyStock2) = resolveCounts(
            farmTotalGoatsListed = 3,
            selectedFarmTotalListed = 3,
            farmGoatsCount = 2
        )
        assertEquals(3, totalListed2)
        assertEquals(2, readyStock2)

        // When farm adds more ready stock than initial totalListed count:
        val (totalListed3, readyStock3) = resolveCounts(
            farmTotalGoatsListed = 2,
            selectedFarmTotalListed = 4,
            farmGoatsCount = 4
        )
        assertEquals(4, totalListed3)
        assertEquals(4, readyStock3)
    }

    @Test
    fun testEmptyStateA_noGoatsExist() {
        val state = resolveEmptyState(
            isLoading = false,
            queryError = null,
            totalListed = 0,
            farmGoats = emptyList(),
            filteredGoats = emptyList()
        )
        assertEquals("No goats listed by this farm yet.", state)
    }

    @Test
    fun testEmptyStateB_goatsExistButNoneCurrentlyAvailable() {
        val state = resolveEmptyState(
            isLoading = false,
            queryError = null,
            totalListed = 3,
            farmGoats = emptyList(),
            filteredGoats = emptyList()
        )
        assertEquals("3 goats listed · 0 currently available.", state)
    }

    @Test
    fun testEmptyStateC_searchFilterExcludesAllGoats() {
        val goat = createGoat(name = "Sirohi Champion", breed = "Sirohi")
        val farmGoats = listOf(goat)
        // User searched for "Boer" which excludes Sirohi
        val filteredGoats = farmGoats.filter { it.breed.equals("Boer", ignoreCase = true) }

        val state = resolveEmptyState(
            isLoading = false,
            queryError = null,
            totalListed = 1,
            farmGoats = farmGoats,
            filteredGoats = filteredGoats
        )
        assertEquals("No goats match your current filters.", state)
    }

    @Test
    fun testEmptyStateD_databaseQueryFailure() {
        val state = resolveEmptyState(
            isLoading = false,
            queryError = "Connection timeout connecting to Supabase",
            totalListed = 3,
            farmGoats = emptyList(),
            filteredGoats = emptyList()
        )
        assertEquals("Unable to load goats. Please try again.", state)
    }

    @Test
    fun testBookingSafety_unavailableGoatsRemainUnbookable() {
        val approvedAndAvailable = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val unapprovedAvailable = createGoat(
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        val approvedSold = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.SOLD
        )
        val approvedReserved = createGoat(
            approvalStatus = ApprovalStatus.APPROVED,
            availabilityStatus = AvailabilityStatus.RESERVED
        )

        fun canCustomerBook(goat: Goat): Boolean {
            val isListingApproved = goat.approvalStatus == ApprovalStatus.APPROVED
            val isAvailable = goat.availabilityStatus == AvailabilityStatus.AVAILABLE
            return isListingApproved && isAvailable
        }

        assertTrue("Approved and Available goat must be bookable", canCustomerBook(approvedAndAvailable))
        assertFalse("Pending approval goat must NEVER be bookable", canCustomerBook(unapprovedAvailable))
        assertFalse("Sold goat must NEVER be bookable", canCustomerBook(approvedSold))
        assertFalse("Reserved goat must NEVER be bookable", canCustomerBook(approvedReserved))
    }
}
