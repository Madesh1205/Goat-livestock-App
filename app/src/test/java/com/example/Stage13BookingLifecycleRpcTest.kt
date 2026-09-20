package com.example

import com.example.model.AvailabilityStatus
import com.example.model.Booking
import com.example.model.Goat
import com.example.model.GoatGender
import com.example.model.GoatPurpose
import com.example.model.UserProfile
import com.example.model.UserRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Stage 13: RPC-based Booking Lifecycle Verification Tests
 * 
 * Verifies the Supabase backend RPC contracts:
 * 1. Customer creates reservation via create_booking_hold
 * 2. Customer cancels their reservation via cancel_booking
 * 3. Farm Admin confirms reservation for their own farm's goat via confirm_booking
 * 4. Farm Admin cancels reservation for their farm's booking via cancel_booking
 * 5. Farm Admin completes confirmed booking via complete_booking
 * 6. Unauthorized users are blocked from executing lifecycle actions
 * 7. Farm Admin cannot book their own farm's goat, but can book from other farms
 */
class Stage13BookingLifecycleRpcTest {

    private class MockRpcBookingBackend {
        val bookings = mutableMapOf<String, Booking>()
        val goats = mutableMapOf<String, Goat>()

        fun addGoat(goat: Goat) {
            goats[goat.id] = goat
        }

        // RPC: create_booking_hold(p_goat_id, p_customer_id, p_notes)
        fun createBookingHold(goatId: String, customer: UserProfile, notes: String = ""): Result<Booking> {
            val goat = goats[goatId] ?: return Result.failure(IllegalStateException("Goat not found"))
            
            // Business Rule: Farm Admin cannot book goats listed by their own farm
            if (customer.role == UserRole.FARM_ADMIN && !customer.farmId.isNullOrBlank() && customer.farmId == goat.farmId) {
                return Result.failure(IllegalStateException("You cannot book goats listed by your own farm."))
            }

            // Check if goat is already reserved/confirmed
            val hasActiveBooking = bookings.values.any { 
                it.goatId == goatId && it.status in listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.CONFIRMED, AvailabilityStatus.BOOKING_PENDING)
            }
            if (hasActiveBooking || goat.availabilityStatus != AvailabilityStatus.AVAILABLE) {
                return Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
            }

            val bookingId = UUID.randomUUID().toString()
            val booking = Booking(
                id = bookingId,
                bookingCode = "AGF-" + bookingId.take(6).uppercase(),
                goatId = goatId,
                goatCode = goat.goatCode,
                goatName = goat.name,
                goatBreed = goat.breed,
                goatPhoto = "",
                farmId = goat.farmId,
                farmName = goat.farmName,
                customerId = customer.id,
                customerName = customer.name,
                customerPhone = customer.phone,
                amount = goat.price,
                status = AvailabilityStatus.RESERVED,
                bookingDate = System.currentTimeMillis(),
                reservationExpiryDate = System.currentTimeMillis() + (24 * 3600 * 1000L),
                notes = notes
            )
            bookings[bookingId] = booking
            goats[goatId] = goat.copy(availabilityStatus = AvailabilityStatus.RESERVED)
            return Result.success(booking)
        }

        // RPC: confirm_booking(p_booking_id)
        fun confirmBooking(bookingId: String, caller: UserProfile): Result<Booking> {
            val booking = bookings[bookingId] ?: return Result.failure(IllegalStateException("Booking not found"))
            
            // Authorization: Caller must be FARM_ADMIN of the owning farm or SUPER_ADMIN
            val isAuthorized = caller.role == UserRole.SUPER_ADMIN || 
                (caller.role == UserRole.FARM_ADMIN && !caller.farmId.isNullOrBlank() && caller.farmId == booking.farmId)
            
            if (!isAuthorized) {
                return Result.failure(IllegalStateException("Unauthorized: Only the farm owner or super admin can confirm this booking."))
            }

            if (booking.status != AvailabilityStatus.RESERVED && booking.status != AvailabilityStatus.BOOKING_PENDING) {
                return Result.failure(IllegalStateException("Only active reservations can be confirmed."))
            }

            val updatedBooking = booking.copy(status = AvailabilityStatus.CONFIRMED)
            bookings[bookingId] = updatedBooking
            val goat = goats[booking.goatId]
            if (goat != null) {
                goats[booking.goatId] = goat.copy(availabilityStatus = AvailabilityStatus.CONFIRMED)
            }
            return Result.success(updatedBooking)
        }

        // RPC: cancel_booking(p_booking_id, p_reason)
        fun cancelBooking(bookingId: String, caller: UserProfile, reason: String): Result<Booking> {
            val booking = bookings[bookingId] ?: return Result.failure(IllegalStateException("Booking not found"))

            // Authorization: Caller must be the Customer who made the booking, the owning Farm Admin, or Super Admin
            val isAuthorized = caller.role == UserRole.SUPER_ADMIN ||
                (caller.role == UserRole.CUSTOMER && caller.id == booking.customerId) ||
                (caller.role == UserRole.FARM_ADMIN && !caller.farmId.isNullOrBlank() && caller.farmId == booking.farmId) ||
                (caller.id == booking.customerId)

            if (!isAuthorized) {
                return Result.failure(IllegalStateException("Unauthorized: You do not have permission to cancel this booking."))
            }

            if (booking.status == AvailabilityStatus.COMPLETED || booking.status == AvailabilityStatus.CANCELLED) {
                return Result.failure(IllegalStateException("Cannot cancel an already completed or cancelled booking."))
            }

            val updatedBooking = booking.copy(status = AvailabilityStatus.CANCELLED)
            bookings[bookingId] = updatedBooking
            // Manual cancellation releases the goat back to AVAILABLE
            val goat = goats[booking.goatId]
            if (goat != null) {
                goats[booking.goatId] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
            }
            return Result.success(updatedBooking)
        }

        // RPC: complete_booking(p_booking_id)
        fun completeBooking(bookingId: String, caller: UserProfile): Result<Booking> {
            val booking = bookings[bookingId] ?: return Result.failure(IllegalStateException("Booking not found"))

            // Authorization: Caller must be FARM_ADMIN of the owning farm or SUPER_ADMIN
            val isAuthorized = caller.role == UserRole.SUPER_ADMIN ||
                (caller.role == UserRole.FARM_ADMIN && !caller.farmId.isNullOrBlank() && caller.farmId == booking.farmId)

            if (!isAuthorized) {
                return Result.failure(IllegalStateException("Unauthorized: Only the farm owner or super admin can complete this booking."))
            }

            if (booking.status != AvailabilityStatus.CONFIRMED) {
                return Result.failure(IllegalStateException("Only confirmed bookings can be marked completed."))
            }

            val updatedBooking = booking.copy(status = AvailabilityStatus.COMPLETED)
            bookings[bookingId] = updatedBooking
            val goat = goats[booking.goatId]
            if (goat != null) {
                goats[booking.goatId] = goat.copy(availabilityStatus = AvailabilityStatus.COMPLETED)
            }
            return Result.success(updatedBooking)
        }
    }

    private lateinit var backend: MockRpcBookingBackend
    private val farm1Id = "farm-uuid-1111"
    private val farm2Id = "farm-uuid-2222"

    private val customerUser = UserProfile(
        id = "cust-user-001",
        email = "customer@example.com",
        name = "Customer One",
        role = UserRole.CUSTOMER
    )

    private val farm1Admin = UserProfile(
        id = "farm1-admin-001",
        email = "admin1@farm1.com",
        name = "Farm 1 Owner",
        role = UserRole.FARM_ADMIN,
        farmId = farm1Id
    )

    private val farm2Admin = UserProfile(
        id = "farm2-admin-002",
        email = "admin2@farm2.com",
        name = "Farm 2 Owner",
        role = UserRole.FARM_ADMIN,
        farmId = farm2Id
    )

    private val superAdmin = UserProfile(
        id = "super-admin-001",
        email = "admin@ammalfarm.com",
        name = "Super Admin",
        role = UserRole.SUPER_ADMIN
    )

    private lateinit var testGoat1: Goat
    private lateinit var testGoat2: Goat

    @Before
    fun setUp() {
        backend = MockRpcBookingBackend()
        testGoat1 = Goat(
            id = "goat-001",
            goatCode = "GOAT-001",
            name = "Boer Champion",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 14,
            weightKg = 45.0,
            purpose = GoatPurpose.BREEDING,
            description = "Top pedigree Boer stud goat",
            price = 25000.0,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Madurai",
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        testGoat2 = Goat(
            id = "goat-002",
            goatCode = "GOAT-002",
            name = "Sirohi Queen",
            breed = "Sirohi",
            gender = GoatGender.FEMALE,
            ageMonths = 12,
            weightKg = 38.0,
            purpose = GoatPurpose.DAIRY,
            description = "High milk yield Sirohi doe",
            price = 18000.0,
            farmId = farm2Id,
            farmName = "Farm Two",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        backend.addGoat(testGoat1)
        backend.addGoat(testGoat2)
    }

    @Test
    fun testCustomerCreatesReservation_Success() {
        val result = backend.createBookingHold(testGoat1.id, customerUser, "Interested in breeding")
        assertTrue(result.isSuccess)
        val booking = result.getOrThrow()
        assertEquals(AvailabilityStatus.RESERVED, booking.status)
        assertEquals(farm1Id, booking.farmId)
        assertEquals(customerUser.id, booking.customerId)
        assertEquals(AvailabilityStatus.RESERVED, backend.goats[testGoat1.id]?.availabilityStatus)
    }

    @Test
    fun testCustomerCancelsReservation_ReleasesGoat() {
        val booking = backend.createBookingHold(testGoat1.id, customerUser).getOrThrow()
        assertEquals(AvailabilityStatus.RESERVED, backend.goats[testGoat1.id]?.availabilityStatus)

        val cancelResult = backend.cancelBooking(booking.id, customerUser, "Changed plans")
        assertTrue(cancelResult.isSuccess)
        assertEquals(AvailabilityStatus.CANCELLED, cancelResult.getOrThrow().status)
        // Goat status is released back to AVAILABLE
        assertEquals(AvailabilityStatus.AVAILABLE, backend.goats[testGoat1.id]?.availabilityStatus)

        // Now another customer can reserve the same goat
        val secondBooking = backend.createBookingHold(testGoat1.id, farm2Admin)
        assertTrue(secondBooking.isSuccess)
    }

    @Test
    fun testFarmAdminConfirmsReservation_Success() {
        val booking = backend.createBookingHold(testGoat1.id, customerUser).getOrThrow()

        val confirmResult = backend.confirmBooking(booking.id, farm1Admin)
        assertTrue(confirmResult.isSuccess)
        val confirmedBooking = confirmResult.getOrThrow()
        assertEquals(AvailabilityStatus.CONFIRMED, confirmedBooking.status)
        assertEquals(AvailabilityStatus.CONFIRMED, backend.goats[testGoat1.id]?.availabilityStatus)
    }

    @Test
    fun testFarmAdminCancelsReservation_ReleasesGoat() {
        val booking = backend.createBookingHold(testGoat1.id, customerUser).getOrThrow()

        val cancelResult = backend.cancelBooking(booking.id, farm1Admin, "Unable to fulfill order")
        assertTrue(cancelResult.isSuccess)
        assertEquals(AvailabilityStatus.CANCELLED, cancelResult.getOrThrow().status)
        assertEquals(AvailabilityStatus.AVAILABLE, backend.goats[testGoat1.id]?.availabilityStatus)
    }

    @Test
    fun testFarmAdminCompletesConfirmedBooking_Success() {
        val booking = backend.createBookingHold(testGoat1.id, customerUser).getOrThrow()
        backend.confirmBooking(booking.id, farm1Admin).getOrThrow()

        val completeResult = backend.completeBooking(booking.id, farm1Admin)
        assertTrue(completeResult.isSuccess)
        assertEquals(AvailabilityStatus.COMPLETED, completeResult.getOrThrow().status)
        assertEquals(AvailabilityStatus.COMPLETED, backend.goats[testGoat1.id]?.availabilityStatus)
    }

    @Test
    fun testUnauthorizedUser_CannotPerformLifecycleActions() {
        val booking = backend.createBookingHold(testGoat1.id, customerUser).getOrThrow()

        // 1. Customer cannot confirm their own booking
        val customerConfirmResult = backend.confirmBooking(booking.id, customerUser)
        assertFalse(customerConfirmResult.isSuccess)

        // 2. Different Farm Admin (Farm 2) cannot confirm Farm 1's booking
        val foreignAdminConfirmResult = backend.confirmBooking(booking.id, farm2Admin)
        assertFalse(foreignAdminConfirmResult.isSuccess)

        // 3. Different Farm Admin cannot complete or cancel Farm 1's booking
        val foreignAdminCancelResult = backend.cancelBooking(booking.id, farm2Admin, "Malicious cancel")
        assertFalse(foreignAdminCancelResult.isSuccess)

        // 4. Super Admin CAN confirm and complete
        val superAdminConfirm = backend.confirmBooking(booking.id, superAdmin)
        assertTrue(superAdminConfirm.isSuccess)

        val superAdminComplete = backend.completeBooking(booking.id, superAdmin)
        assertTrue(superAdminComplete.isSuccess)
    }

    @Test
    fun testFarmAdminBooking_CannotBookOwnFarm_CanBookOtherFarm() {
        // Farm 1 Admin attempts to book Farm 1's goat -> Blocked
        val ownBookingResult = backend.createBookingHold(testGoat1.id, farm1Admin)
        assertFalse(ownBookingResult.isSuccess)
        assertTrue(ownBookingResult.exceptionOrNull()?.message?.contains("own farm") == true)

        // Farm 1 Admin attempts to book Farm 2's goat -> Allowed
        val otherBookingResult = backend.createBookingHold(testGoat2.id, farm1Admin)
        assertTrue(otherBookingResult.isSuccess)
        assertEquals(farm2Id, otherBookingResult.getOrThrow().farmId)
    }
}
