package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Booking
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.GoatFilterCriteria
import com.ammalfarm.adusanthai.model.GoatGender
import com.ammalfarm.adusanthai.model.GoatPurpose
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class GoatAvailabilitySyncAuditTest {

    @Test
    fun testGoatDtoStatusMapping_Available() {
        val dto = GoatDto(id = UUID.randomUUID().toString(), status = "AVAILABLE")
        val domain = dto.toDomain()
        assertEquals(AvailabilityStatus.AVAILABLE, domain.availabilityStatus)
    }

    @Test
    fun testGoatDtoStatusMapping_ReservedAndHoldVariations() {
        val reservedDto = GoatDto(id = UUID.randomUUID().toString(), status = "RESERVED")
        assertEquals(AvailabilityStatus.RESERVED, reservedDto.toDomain().availabilityStatus)

        val holdDto = GoatDto(id = UUID.randomUUID().toString(), status = "HOLD")
        assertEquals(AvailabilityStatus.RESERVED, holdDto.toDomain().availabilityStatus)

        val heldDto = GoatDto(id = UUID.randomUUID().toString(), status = "HELD")
        assertEquals(AvailabilityStatus.RESERVED, heldDto.toDomain().availabilityStatus)
    }

    @Test
    fun testGoatDtoStatusMapping_ConfirmedAndBooked() {
        val confirmedDto = GoatDto(id = UUID.randomUUID().toString(), status = "CONFIRMED")
        assertEquals(AvailabilityStatus.CONFIRMED, confirmedDto.toDomain().availabilityStatus)

        val bookedDto = GoatDto(id = UUID.randomUUID().toString(), status = "BOOKED")
        assertEquals(AvailabilityStatus.CONFIRMED, bookedDto.toDomain().availabilityStatus)
    }

    @Test
    fun testGoatDtoStatusMapping_CompletedAndSold() {
        val completedDto = GoatDto(id = UUID.randomUUID().toString(), status = "COMPLETED")
        assertEquals(AvailabilityStatus.COMPLETED, completedDto.toDomain().availabilityStatus)

        val soldDto = GoatDto(id = UUID.randomUUID().toString(), status = "SOLD")
        assertEquals(AvailabilityStatus.SOLD, soldDto.toDomain().availabilityStatus)
    }

    @Test
    fun testGoatDtoStatusMapping_UnknownStatusNeverFallsBackToAvailable() {
        // Unknown or unexpected non-available status should never be falsely advertised as AVAILABLE
        val unknownDto = GoatDto(id = UUID.randomUUID().toString(), status = "SOME_CUSTOM_HOLD_STATUS")
        assertNotEquals(AvailabilityStatus.AVAILABLE, unknownDto.toDomain().availabilityStatus)
        assertEquals(AvailabilityStatus.RESERVED, unknownDto.toDomain().availabilityStatus)
    }

    @Test
    fun testAvailabilityFilter_ExcludesReservedAndConfirmedGoats() {
        val availableGoat = createTestGoat("g1", AvailabilityStatus.AVAILABLE)
        val reservedGoat = createTestGoat("g2", AvailabilityStatus.RESERVED)
        val confirmedGoat = createTestGoat("g3", AvailabilityStatus.CONFIRMED)
        val soldGoat = createTestGoat("g4", AvailabilityStatus.SOLD)
        val completedGoat = createTestGoat("g5", AvailabilityStatus.COMPLETED)

        val availableFilter = GoatFilterCriteria(availability = AvailabilityStatus.AVAILABLE)

        assertTrue(availableFilter.matches(availableGoat))
        assertFalse(availableFilter.matches(reservedGoat))
        assertFalse(availableFilter.matches(confirmedGoat))
        assertFalse(availableFilter.matches(soldGoat))
        assertFalse(availableFilter.matches(completedGoat))
    }

    @Test
    fun testGoatLifecycleTransitions_Simulation() {
        val goatId = UUID.randomUUID().toString()
        var currentGoat = createTestGoat(goatId, AvailabilityStatus.AVAILABLE)

        // Step 1: Customer reserves goat
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.RESERVED)
        assertEquals(AvailabilityStatus.RESERVED, currentGoat.availabilityStatus)
        assertFalse("Reserved goat must not match AVAILABLE filter",
            GoatFilterCriteria(availability = AvailabilityStatus.AVAILABLE).matches(currentGoat))

        // Step 2: Farm admin confirms booking
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.CONFIRMED)
        assertEquals(AvailabilityStatus.CONFIRMED, currentGoat.availabilityStatus)
        assertFalse("Confirmed goat must not match AVAILABLE filter",
            GoatFilterCriteria(availability = AvailabilityStatus.AVAILABLE).matches(currentGoat))

        // Step 3: Cancellation restores availability
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
        assertEquals(AvailabilityStatus.AVAILABLE, currentGoat.availabilityStatus)
        assertTrue("Cancelled reservation restores AVAILABLE state",
            GoatFilterCriteria(availability = AvailabilityStatus.AVAILABLE).matches(currentGoat))

        // Step 4: Re-reservation + Confirmation + Completion
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.RESERVED)
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.CONFIRMED)
        currentGoat = currentGoat.copy(availabilityStatus = AvailabilityStatus.COMPLETED)
        assertEquals(AvailabilityStatus.COMPLETED, currentGoat.availabilityStatus)
        assertFalse("Completed goat must not match AVAILABLE filter",
            GoatFilterCriteria(availability = AvailabilityStatus.AVAILABLE).matches(currentGoat))
    }

    private fun createTestGoat(id: String, status: AvailabilityStatus): Goat {
        return Goat(
            id = id,
            goatCode = "GOAT-001",
            name = "Test Boer",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 45.0,
            purpose = GoatPurpose.BREEDING,
            description = "Healthy breeder",
            price = 15000.0,
            discountPercentage = 0.0,
            photos = listOf("https://example.com/goat.jpg"),
            farmId = UUID.randomUUID().toString(),
            farmCode = "FARM-001",
            farmName = "Ammal Farm",
            farmLocation = "Salem, Tamil Nadu",
            availabilityStatus = status,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0,
            isFeatured = true
        )
    }
}
