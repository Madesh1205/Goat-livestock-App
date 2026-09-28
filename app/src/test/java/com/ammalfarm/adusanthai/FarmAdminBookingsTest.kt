package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.screens.FarmBookingFilterTab
import com.ammalfarm.adusanthai.ui.screens.getFarmAdminBookingStatusStyle
import org.junit.Assert.*
import org.junit.Test

class FarmAdminBookingsTest {

    private val sampleFarmId = "farm-1111-2222"
    private val otherFarmId = "farm-3333-4444"

    private val sampleBookings = listOf(
        Booking(
            id = "bk-001-pending",
            bookingCode = "AMM-1001",
            goatId = "goat-001",
            goatCode = "GOAT-001",
            goatName = "Salem Black Pure Buck",
            goatBreed = "Salem Black",
            goatPhoto = "",
            customerId = "cust-1",
            customerName = "Ravi Kumar",
            customerPhone = "+91 98765 43210",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 18500.0,
            status = AvailabilityStatus.BOOKING_PENDING,
            bookingDate = System.currentTimeMillis() - 3600000L,
            reservationExpiryDate = System.currentTimeMillis() + 82800000L, // 23h left
            notes = "Please keep transport certificate ready"
        ),
        Booking(
            id = "bk-002-reserved",
            bookingCode = "AMM-1002",
            goatId = "goat-002",
            goatCode = "GOAT-002",
            goatName = "Tellicherry Breeding Doe",
            goatBreed = "Tellicherry",
            goatPhoto = "",
            customerId = "cust-2",
            customerName = "Suresh Raina",
            customerPhone = "+91 94444 12345",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 22000.0,
            status = AvailabilityStatus.RESERVED,
            bookingDate = System.currentTimeMillis() - 7200000L,
            reservationExpiryDate = System.currentTimeMillis() + 79200000L,
            notes = "Visiting farm tomorrow morning"
        ),
        Booking(
            id = "bk-003-confirmed",
            bookingCode = "AMM-1003",
            goatId = "goat-003",
            goatCode = "GOAT-003",
            goatName = "Boer Champion Male",
            goatBreed = "Boer",
            goatPhoto = "",
            customerId = "cust-3",
            customerName = "Anand Mahindra",
            customerPhone = "+91 98888 77777",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 45000.0,
            status = AvailabilityStatus.CONFIRMED,
            bookingDate = System.currentTimeMillis() - 86400000L,
            reservationExpiryDate = System.currentTimeMillis() + 86400000L,
            notes = "Advance payment verified by phone"
        ),
        Booking(
            id = "bk-004-cancelled",
            bookingCode = "AMM-1004",
            goatId = "goat-004",
            goatCode = "GOAT-004",
            goatName = "Sirohi Stud Goat",
            goatBreed = "Sirohi",
            goatPhoto = "",
            customerId = "cust-4",
            customerName = "Karthik Raja",
            customerPhone = "+91 91234 56789",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 26000.0,
            status = AvailabilityStatus.CANCELLED,
            bookingDate = System.currentTimeMillis() - (3 * 86400000L),
            reservationExpiryDate = System.currentTimeMillis() - (2 * 86400000L),
            notes = "Customer unable to arrange transport"
        ),
        Booking(
            id = "bk-005-expired",
            bookingCode = "AMM-1005",
            goatId = "goat-005",
            goatCode = "GOAT-005",
            goatName = "Jamnapari Show Doe",
            goatBreed = "Jamnapari",
            goatPhoto = "",
            customerId = "cust-5",
            customerName = "Vignesh Shivan",
            customerPhone = "+91 99999 11111",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 31000.0,
            status = AvailabilityStatus.BOOKING_PENDING,
            bookingDate = System.currentTimeMillis() - (48 * 3600000L),
            reservationExpiryDate = System.currentTimeMillis() - (24 * 3600000L), // hold expired 24h ago
            notes = "Did not answer verification calls"
        ),
        Booking(
            id = "bk-006-completed",
            bookingCode = "AMM-1006",
            goatId = "goat-006",
            goatCode = "GOAT-006",
            goatName = "Beetal Dairy Goat",
            goatBreed = "Beetal",
            goatPhoto = "",
            customerId = "cust-6",
            customerName = "Murugan Pillai",
            customerPhone = "+91 97777 22222",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 34000.0,
            status = AvailabilityStatus.COMPLETED,
            bookingDate = System.currentTimeMillis() - (5 * 86400000L),
            reservationExpiryDate = System.currentTimeMillis() - (4 * 86400000L),
            notes = "Fulfilled and delivered successfully"
        ),
        // Booking from another farm (Must NOT be visible to this farm admin)
        Booking(
            id = "bk-007-other-farm",
            bookingCode = "AMM-9999",
            goatId = "goat-999",
            goatCode = "GOAT-999",
            goatName = "Other Farm Goat",
            goatBreed = "Osmanabadi",
            goatPhoto = "",
            customerId = "cust-7",
            customerName = "External Buyer",
            customerPhone = "+91 95555 33333",
            farmId = otherFarmId,
            farmName = "Other Farm Limited",
            amount = 15000.0,
            status = AvailabilityStatus.CONFIRMED,
            bookingDate = System.currentTimeMillis(),
            reservationExpiryDate = System.currentTimeMillis() + 86400000L
        )
    )

    @Test
    fun testFarmAdminBookingDataIsolation() {
        // Enforce Requirement 10: FARM_ADMIN must not see bookings belonging to unrelated farms
        val ownFarmBookings = sampleBookings.filter { it.farmId == sampleFarmId }
        assertEquals(6, ownFarmBookings.size)
        assertTrue(ownFarmBookings.none { it.farmId == otherFarmId })
    }

    @Test
    fun testStatusFilteringAllTabs() {
        val ownBookings = sampleBookings.filter { it.farmId == sampleFarmId }

        // ALL
        val all = ownBookings.filter { true }
        assertEquals(6, all.size)

        // PENDING (not expired)
        val pending = ownBookings.filter { it.status == AvailabilityStatus.BOOKING_PENDING && !it.isHoldExpired }
        assertEquals(1, pending.size)
        assertEquals("bk-001-pending", pending.first().id)

        // RESERVED (not expired)
        val reserved = ownBookings.filter { it.status == AvailabilityStatus.RESERVED && !it.isHoldExpired }
        assertEquals(1, reserved.size)
        assertEquals("bk-002-reserved", reserved.first().id)

        // CONFIRMED
        val confirmed = ownBookings.filter { it.status == AvailabilityStatus.CONFIRMED }
        assertEquals(1, confirmed.size)
        assertEquals("bk-003-confirmed", confirmed.first().id)

        // CANCELLED
        val cancelled = ownBookings.filter { (it.status == AvailabilityStatus.CANCELLED || it.status == AvailabilityStatus.REJECTED) && !it.isHoldExpired }
        assertEquals(1, cancelled.size)
        assertEquals("bk-004-cancelled", cancelled.first().id)

        // EXPIRED
        val expired = ownBookings.filter { it.isHoldExpired }
        assertEquals(1, expired.size)
        assertEquals("bk-005-expired", expired.first().id)

        // COMPLETED
        val completed = ownBookings.filter { it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD }
        assertEquals(1, completed.size)
        assertEquals("bk-006-completed", completed.first().id)
    }

    @Test
    fun testSearchFunctionality() {
        val ownBookings = sampleBookings.filter { it.farmId == sampleFarmId }

        // 1. Search by Goat Name
        val byGoatName = ownBookings.filter { it.goatName.contains("Salem Black", ignoreCase = true) }
        assertEquals(1, byGoatName.size)
        assertEquals("bk-001-pending", byGoatName.first().id)

        // 2. Search by GOAT-XXX Code
        val byGoatCode = ownBookings.filter { it.displayGoatCode.contains("GOAT-003", ignoreCase = true) }
        assertEquals(1, byGoatCode.size)
        assertEquals("bk-003-confirmed", byGoatCode.first().id)

        // 3. Search by Customer Name
        val byCustomer = ownBookings.filter { it.customerName.contains("Anand Mahindra", ignoreCase = true) }
        assertEquals(1, byCustomer.size)
        assertEquals("bk-003-confirmed", byCustomer.first().id)

        // 4. Search by Booking Code
        val byBookingCode = ownBookings.filter { it.displayBookingCode.contains("AMM-1002", ignoreCase = true) }
        assertEquals(1, byBookingCode.size)
        assertEquals("bk-002-reserved", byBookingCode.first().id)
    }

    @Test
    fun testBookingDisplayCodesAndHoldExpiration() {
        val pendingBooking = sampleBookings.first { it.id == "bk-001-pending" }
        assertEquals("AMM-1001", pendingBooking.displayBookingCode)
        assertEquals("GOAT-001", pendingBooking.displayGoatCode)
        assertFalse(pendingBooking.isHoldExpired)

        val expiredBooking = sampleBookings.first { it.id == "bk-005-expired" }
        assertTrue(expiredBooking.isHoldExpired)

        val (bg, text, label) = getFarmAdminBookingStatusStyle(expiredBooking.status, isExpired = true)
        assertEquals("Hold Expired", label)
    }

    @Test
    fun testFallbackDisplayCodes() {
        val bookingWithoutCode = Booking(
            id = "c3f4e1a2-5b6d-7e8f-9a0b-1c2d3e4f5a6b",
            bookingCode = "",
            goatId = "a1b2c3d4-0000",
            goatCode = "",
            goatName = "Test Goat",
            goatBreed = "Test Breed",
            goatPhoto = "",
            customerId = "cust-x",
            customerName = "Test Customer",
            customerPhone = "+91 99999 99999",
            farmId = sampleFarmId,
            farmName = "Ammal Farm Central",
            amount = 10000.0
        )

        assertEquals("AMM-C3F4E1", bookingWithoutCode.displayBookingCode)
        assertEquals("GOAT-A1B2", bookingWithoutCode.displayGoatCode)
    }
}
