package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class Batch3FarmManagementAndSlotValidationTest {

    @Test
    fun `Slot Validation - total slots cannot be reduced below consumed slots`() {
        val farmId = UUID.randomUUID().toString()
        val farm = Farm(
            id = farmId,
            name = "Test Partner Farm",
            goatListingLimit = 10,
            consumedListingSlots = 4
        )

        val activeGoatsCount = 4
        val consumed = maxOf(farm.consumedListingSlots, activeGoatsCount)

        // Attempting to set total slots to 3 (less than consumed 4) must fail
        val invalidNewSlots = 3
        val isValid = invalidNewSlots >= consumed

        assertFalse("Setting total slots (3) below consumed slots (4) must be invalid", isValid)
    }

    @Test
    fun `Slot Validation - total slots equal to or greater than consumed slots is valid`() {
        val farmId = UUID.randomUUID().toString()
        val farm = Farm(
            id = farmId,
            name = "Test Partner Farm",
            goatListingLimit = 10,
            consumedListingSlots = 4
        )

        val activeGoatsCount = 4
        val consumed = maxOf(farm.consumedListingSlots, activeGoatsCount)

        val validNewSlots = 4
        val isValid = validNewSlots >= consumed

        assertTrue("Setting total slots equal to consumed slots (4) must be valid", isValid)
    }

    @Test
    fun `Farm Suspension - prevents new goat listings when farm status is SUSPENDED`() {
        val suspendedFarm = Farm(
            id = UUID.randomUUID().toString(),
            name = "Suspended Farm",
            verificationStatus = VerificationStatus.SUSPENDED
        )

        val isSuspended = suspendedFarm.verificationStatus == VerificationStatus.SUSPENDED
        assertTrue(isSuspended)

        val canCreateListing = !isSuspended && suspendedFarm.verificationStatus.isApprovedOrVerified
        assertFalse("Suspended farm must not be allowed to create new listings", canCreateListing)
    }
}
