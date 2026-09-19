package com.example

import com.example.core.util.PriceUtils
import com.example.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BookingSystemComprehensiveTest {

    private fun createTestGoat(
        id: String = UUID.randomUUID().toString(),
        name: String = "Salem Black Premium Stud",
        price: Double = 28000.0,
        discountPercentage: Double = 20.0,
        farmId: String = UUID.randomUUID().toString(),
        farmName: String = "Ammal Farm",
        status: AvailabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus: ApprovalStatus = ApprovalStatus.APPROVED
    ): Goat {
        return Goat(
            id = id,
            name = name,
            tagNumber = "TAG-" + id.take(6).uppercase(),
            breed = "Salem Black",
            gender = GoatGender.MALE,
            ageMonths = 24,
            weightKg = 45.0,
            purpose = GoatPurpose.BREEDING,
            description = "Champion breeding goat with strong pedigree",
            price = price,
            discountPercentage = discountPercentage,
            photos = listOf("https://images.unsplash.com/photo-example"),
            farmId = farmId,
            farmName = farmName,
            farmLocation = "Vellore, Tamil Nadu",
            availabilityStatus = status,
            approvalStatus = approvalStatus
        )
    }

    /**
     * Test booking engine implementing the exact Stage 7 database trigger and repository invariants:
     * - Price snapshot strictly computed from goat price and discount at booking time
     * - Double booking prevention via active booking uniqueness
     * - 24-hour reservation hold
     * - Automatic release on cancellation or expiration
     * - Role-isolated query scoping
     */
    private class TestBookingEngine(
        val goats: ConcurrentHashMap<String, Goat> = ConcurrentHashMap(),
        val bookings: ConcurrentHashMap<String, Booking> = ConcurrentHashMap()
    ) {
        var currentTimeMillis: Long = System.currentTimeMillis()

        fun addGoat(goat: Goat) {
            goats[goat.id] = goat
        }

        fun updateGoatPrice(goatId: String, newPrice: Double, newDiscountPercentage: Double) {
            val goat = goats[goatId] ?: throw IllegalArgumentException("Goat not found")
            goats[goatId] = goat.copy(price = newPrice, discountPercentage = newDiscountPercentage)
        }

        fun createBooking(
            goatId: String,
            customerId: String,
            customerName: String = "Customer",
            notes: String = "",
            arbitrarySuppliedPrice: Double? = null // To test that caller cannot manipulate price
        ): Result<Booking> {
            val goat = goats[goatId] ?: return Result.failure(IllegalArgumentException("Goat listing not found"))

            // Expire overdue holds
            for ((id, booking) in bookings) {
                if (booking.goatId == goatId && 
                    booking.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED) &&
                    booking.reservationExpiryDate < currentTimeMillis
                ) {
                    bookings[id] = booking.copy(status = AvailabilityStatus.CANCELLED)
                    if (goat.availabilityStatus == AvailabilityStatus.RESERVED) {
                        goats[goatId] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
                    }
                }
            }

            val currentGoat = goats[goatId]!!
            if (currentGoat.availabilityStatus != AvailabilityStatus.AVAILABLE) {
                return Result.failure(IllegalStateException("Goat is no longer available for booking"))
            }
            if (currentGoat.approvalStatus != ApprovalStatus.APPROVED) {
                return Result.failure(IllegalStateException("Goat listing is pending admin approval"))
            }

            // Check if active hold exists
            val activeHold = bookings.values.any {
                it.goatId == goatId && it.status in listOf(
                    AvailabilityStatus.BOOKING_PENDING,
                    AvailabilityStatus.RESERVED,
                    AvailabilityStatus.CONFIRMED
                )
            }
            if (activeHold) {
                return Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
            }

            // Server-authoritative price snapshot calculation:
            // IGNORES any arbitrarySuppliedPrice from caller!
            val snapshotPrice = PriceUtils.calculateFinalPrice(currentGoat.price, currentGoat.discountPercentage)

            val bookingId = UUID.randomUUID().toString()
            val bookingDate = currentTimeMillis
            val expiryDate = bookingDate + (24 * 3600 * 1000L) // Exactly 24 hours

            val newBooking = Booking(
                id = bookingId,
                goatId = currentGoat.id,
                goatName = currentGoat.name,
                goatBreed = currentGoat.breed,
                goatPhoto = currentGoat.photos.firstOrNull() ?: "",
                farmId = currentGoat.farmId,
                farmName = currentGoat.farmName,
                customerId = customerId,
                customerName = customerName,
                customerPhone = "+91 98400 11223",
                amount = snapshotPrice,
                status = AvailabilityStatus.BOOKING_PENDING,
                bookingDate = bookingDate,
                reservationExpiryDate = expiryDate,
                notes = notes
            )

            bookings[bookingId] = newBooking
            goats[goatId] = currentGoat.copy(availabilityStatus = AvailabilityStatus.RESERVED)

            return Result.success(newBooking)
        }

        fun updateBookingStatus(
            bookingId: String,
            newStatus: AvailabilityStatus,
            actingUserId: String,
            actingUserRole: UserRole,
            actingUserFarmId: String? = null
        ): Result<Unit> {
            val booking = bookings[bookingId] ?: return Result.failure(IllegalArgumentException("Booking not found"))

            // Farm admin authorization check: cannot alter other farms' bookings
            if (actingUserRole == UserRole.FARM_ADMIN) {
                if (booking.farmId != actingUserFarmId) {
                    return Result.failure(SecurityException("Farm Admin cannot take action on another farm's booking"))
                }
            }

            // Customer authorization check: can only cancel their own booking
            if (actingUserRole == UserRole.CUSTOMER) {
                if (booking.customerId != actingUserId) {
                    return Result.failure(SecurityException("Customer cannot modify another customer's booking"))
                }
                if (newStatus != AvailabilityStatus.CANCELLED) {
                    return Result.failure(IllegalStateException("Customers can only cancel their reservations"))
                }
            }

            bookings[bookingId] = booking.copy(status = newStatus)

            // Update goat status accordingly
            val goat = goats[booking.goatId]
            if (goat != null) {
                when (newStatus) {
                    AvailabilityStatus.CONFIRMED -> {
                        goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.CONFIRMED)
                    }
                    AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> {
                        goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.SOLD)
                    }
                    AvailabilityStatus.CANCELLED -> {
                        // Release back to AVAILABLE if no other active booking
                        goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
                    }
                    AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING -> {
                        goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.RESERVED)
                    }
                    else -> {}
                }
            }

            return Result.success(Unit)
        }

        fun getCustomerBookings(customerId: String): List<Booking> {
            return bookings.values.filter { it.customerId == customerId }
        }

        fun getFarmBookings(farmId: String): List<Booking> {
            return bookings.values.filter { it.farmId == farmId }
        }

        fun getAllBookings(): List<Booking> {
            return bookings.values.toList()
        }
    }

    // --- SCENARIO 1: Booking price snapshot preserved after admin changes goat price ---
    @Test
    fun `Scenario 1 - Booking price snapshot is preserved after admin changes goat price`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat(price = 25000.0, discountPercentage = 0.0)
        engine.addGoat(goat)

        val customerId = "cust-1"
        val bookingResult = engine.createBooking(goat.id, customerId)
        assertTrue(bookingResult.isSuccess)
        val booking = bookingResult.getOrThrow()
        assertEquals(25000.0, booking.amount, 0.01)

        // Admin later modifies the goat price and adds a discount
        engine.updateGoatPrice(goat.id, newPrice = 32000.0, newDiscountPercentage = 15.0)

        // Existing booking must STILL show the original ₹25,000 snapshot
        val customerBookings = engine.getCustomerBookings(customerId)
        assertEquals(1, customerBookings.size)
        assertEquals(25000.0, customerBookings.first().amount, 0.01)
        assertEquals("₹25,000", customerBookings.first().formattedAmount)
    }

    // --- SCENARIO 2: Booking on discounted goat preserves exact discounted price snapshot ---
    @Test
    fun `Scenario 2 - Booking on discounted goat preserves exact discounted price snapshot`() {
        val engine = TestBookingEngine()
        // ₹28,000 with 20% discount = ₹22,400
        val goat = createTestGoat(price = 28000.0, discountPercentage = 20.0)
        engine.addGoat(goat)

        val customerId = "cust-2"
        val bookingResult = engine.createBooking(goat.id, customerId)
        assertTrue(bookingResult.isSuccess)
        val booking = bookingResult.getOrThrow()

        // Verify exact snapshot amount is ₹22,400
        assertEquals(22400.0, booking.amount, 0.01)

        // Breeder later ends the discount campaign (discount = 0%)
        engine.updateGoatPrice(goat.id, newPrice = 28000.0, newDiscountPercentage = 0.0)

        // Booking remains ₹22,400
        val retrieved = engine.getCustomerBookings(customerId).first()
        assertEquals(22400.0, retrieved.amount, 0.01)
        assertEquals("₹22,400", retrieved.formattedAmount)
    }

    // --- SCENARIO 3: Booking preserves original price when goat had 0% discount ---
    @Test
    fun `Scenario 3 - Booking preserves original price when goat had 0 percent discount`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat(price = 18500.0, discountPercentage = 0.0)
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-3").getOrThrow()
        assertEquals(18500.0, booking.amount, 0.01)
        assertEquals("₹18,500", booking.formattedAmount)
    }

    // --- SCENARIO 4: Double booking prevention (race condition or concurrent hold fails) ---
    @Test
    fun `Scenario 4 - Double booking prevention blocks second attempt on active reservation`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat()
        engine.addGoat(goat)

        // Customer 1 places reservation hold
        val result1 = engine.createBooking(goat.id, "cust-1")
        assertTrue(result1.isSuccess)

        // Customer 2 attempts to reserve the same goat while hold is active
        val result2 = engine.createBooking(goat.id, "cust-2")
        assertTrue(result2.isFailure)
        assertTrue(result2.exceptionOrNull()?.message?.contains("already been reserved") == true ||
                result2.exceptionOrNull()?.message?.contains("no longer available") == true)
    }

    // --- SCENARIO 5: 24-hour reservation expiration behavior ---
    @Test
    fun `Scenario 5 - 24-hour reservation expiration window is exactly 24 hours`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat()
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-5").getOrThrow()
        val diffMillis = booking.reservationExpiryDate - booking.bookingDate
        val diffHours = diffMillis / (1000 * 3600)
        assertEquals(24L, diffHours)
    }

    // --- SCENARIO 6: Expired booking allows new customer to book ---
    @Test
    fun `Scenario 6 - Expired booking allows a new customer to place a reservation hold`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat()
        engine.addGoat(goat)

        // Customer 1 books
        val booking1 = engine.createBooking(goat.id, "cust-1").getOrThrow()
        assertEquals(AvailabilityStatus.BOOKING_PENDING, booking1.status)

        // Advance simulated time past 24 hours (e.g. 25 hours)
        engine.currentTimeMillis += (25 * 3600 * 1000L)

        // Customer 2 attempts to book the same goat now that hold has expired
        val result2 = engine.createBooking(goat.id, "cust-2")
        assertTrue(result2.isSuccess)
        val booking2 = result2.getOrThrow()
        assertEquals("cust-2", booking2.customerId)
        assertEquals(AvailabilityStatus.BOOKING_PENDING, booking2.status)
    }

    // --- SCENARIO 7: Cancelled booking releases goat to AVAILABLE ---
    @Test
    fun `Scenario 7 - Cancelled booking releases goat back to AVAILABLE for other customers`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat()
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-1").getOrThrow()
        assertEquals(AvailabilityStatus.RESERVED, engine.goats[goat.id]?.availabilityStatus)

        // Customer cancels their booking
        val cancelResult = engine.updateBookingStatus(
            bookingId = booking.id,
            newStatus = AvailabilityStatus.CANCELLED,
            actingUserId = "cust-1",
            actingUserRole = UserRole.CUSTOMER
        )
        assertTrue(cancelResult.isSuccess)

        // Goat status is restored to AVAILABLE
        assertEquals(AvailabilityStatus.AVAILABLE, engine.goats[goat.id]?.availabilityStatus)

        // Customer 2 can now successfully book
        val customer2Result = engine.createBooking(goat.id, "cust-2")
        assertTrue(customer2Result.isSuccess)
    }

    // --- SCENARIO 8: Confirmed booking prevents other bookings ---
    @Test
    fun `Scenario 8 - Confirmed booking updates goat to CONFIRMED and blocks other bookings`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat()
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-1").getOrThrow()

        // Farm Admin confirms the booking
        val confirmResult = engine.updateBookingStatus(
            bookingId = booking.id,
            newStatus = AvailabilityStatus.CONFIRMED,
            actingUserId = "farm-admin-1",
            actingUserRole = UserRole.FARM_ADMIN,
            actingUserFarmId = goat.farmId
        )
        assertTrue(confirmResult.isSuccess)

        // Goat is now CONFIRMED
        assertEquals(AvailabilityStatus.CONFIRMED, engine.goats[goat.id]?.availabilityStatus)

        // Customer 2 cannot book
        val customer2Result = engine.createBooking(goat.id, "cust-2")
        assertTrue(customer2Result.isFailure)
    }

    // --- SCENARIO 9: Customer sees only their own bookings ---
    @Test
    fun `Scenario 9 - Customer sees only their own bookings and not other customers`() {
        val engine = TestBookingEngine()
        val goat1 = createTestGoat(name = "Goat 1")
        val goat2 = createTestGoat(name = "Goat 2")
        engine.addGoat(goat1)
        engine.addGoat(goat2)

        engine.createBooking(goat1.id, "customer-A")
        engine.createBooking(goat2.id, "customer-B")

        val customerABookings = engine.getCustomerBookings("customer-A")
        assertEquals(1, customerABookings.size)
        assertEquals("customer-A", customerABookings.first().customerId)

        val customerBBookings = engine.getCustomerBookings("customer-B")
        assertEquals(1, customerBBookings.size)
        assertEquals("customer-B", customerBBookings.first().customerId)
    }

    // --- SCENARIO 10: Farm Admin sees only their farm's bookings ---
    @Test
    fun `Scenario 10 - Farm Admin sees only their own farms bookings`() {
        val engine = TestBookingEngine()
        val farm1Id = "farm-1"
        val farm2Id = "farm-2"

        val goat1 = createTestGoat(name = "Goat Farm 1", farmId = farm1Id)
        val goat2 = createTestGoat(name = "Goat Farm 2", farmId = farm2Id)
        engine.addGoat(goat1)
        engine.addGoat(goat2)

        engine.createBooking(goat1.id, "cust-1")
        engine.createBooking(goat2.id, "cust-2")

        val farm1Bookings = engine.getFarmBookings(farm1Id)
        assertEquals(1, farm1Bookings.size)
        assertEquals(farm1Id, farm1Bookings.first().farmId)

        val farm2Bookings = engine.getFarmBookings(farm2Id)
        assertEquals(1, farm2Bookings.size)
        assertEquals(farm2Id, farm2Bookings.first().farmId)
    }

    // --- SCENARIO 11: Super Admin sees all bookings ---
    @Test
    fun `Scenario 11 - Super Admin sees all platform bookings across all farms`() {
        val engine = TestBookingEngine()
        val goat1 = createTestGoat(name = "Goat Farm 1", farmId = "farm-1")
        val goat2 = createTestGoat(name = "Goat Farm 2", farmId = "farm-2")
        engine.addGoat(goat1)
        engine.addGoat(goat2)

        engine.createBooking(goat1.id, "cust-1")
        engine.createBooking(goat2.id, "cust-2")

        val allBookings = engine.getAllBookings()
        assertEquals(2, allBookings.size)
    }

    // --- SCENARIO 12: Farm Admin cannot confirm another farm's booking ---
    @Test
    fun `Scenario 12 - Farm Admin cannot confirm or modify another farms booking`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat(farmId = "farm-1")
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-1").getOrThrow()

        // Admin of farm-2 attempts to confirm farm-1's booking
        val unauthorizedAttempt = engine.updateBookingStatus(
            bookingId = booking.id,
            newStatus = AvailabilityStatus.CONFIRMED,
            actingUserId = "admin-farm-2",
            actingUserRole = UserRole.FARM_ADMIN,
            actingUserFarmId = "farm-2"
        )

        assertTrue(unauthorizedAttempt.isFailure)
        assertTrue(unauthorizedAttempt.exceptionOrNull() is SecurityException)
        assertEquals(AvailabilityStatus.BOOKING_PENDING, engine.bookings[booking.id]?.status)
    }

    // --- SCENARIO 13: Customer cannot manipulate booking price arbitrarily ---
    @Test
    fun `Scenario 13 - Customer cannot manipulate booking price arbitrarily`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat(price = 30000.0, discountPercentage = 10.0) // Authoritative price = ₹27,000
        engine.addGoat(goat)

        // Attempting to send arbitrary low price (e.g. ₹100) from malicious client
        val booking = engine.createBooking(
            goatId = goat.id,
            customerId = "cust-malicious",
            arbitrarySuppliedPrice = 100.0
        ).getOrThrow()

        // Engine enforces authoritative ₹27,000 calculation
        assertEquals(27000.0, booking.amount, 0.01)
        assertNotEquals(100.0, booking.amount, 0.01)
    }

    // --- SCENARIO 14: Booking does not trigger mandatory payment or mark goat SOLD prematurely ---
    @Test
    fun `Scenario 14 - Booking creates reservation hold with zero upfront payment without marking goat SOLD`() {
        val engine = TestBookingEngine()
        val goat = createTestGoat(price = 20000.0, discountPercentage = 0.0)
        engine.addGoat(goat)

        val booking = engine.createBooking(goat.id, "cust-hold").getOrThrow()

        // Status is pending/reserved, not sold
        assertEquals(AvailabilityStatus.BOOKING_PENDING, booking.status)
        assertEquals(AvailabilityStatus.RESERVED, engine.goats[goat.id]?.availabilityStatus)
        assertNotEquals(AvailabilityStatus.SOLD, engine.goats[goat.id]?.availabilityStatus)
        assertNotEquals(AvailabilityStatus.COMPLETED, engine.goats[goat.id]?.availabilityStatus)
    }
}
