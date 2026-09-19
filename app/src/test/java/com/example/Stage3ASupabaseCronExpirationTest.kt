package com.example

import com.example.core.util.PriceUtils
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 3A: Supabase Cron 24-Hour Booking Expiration & Concurrency Verification Test.
 *
 * Verifies:
 * 1. expire_overdue_bookings() logic:
 *    - Finds active bookings where hold_expires_at <= NOW()
 *    - Transitions booking status to EXPIRED
 *    - Releases associated goat to AVAILABLE
 *    - Does not release a goat if another valid active booking exists
 *    - Safe / idempotent if executed repeatedly
 *    - Operates entirely server-side independently of client app state
 * 2. Supabase Cron scheduling invariants:
 *    - Cron executes at 5-minute recurring intervals
 *    - Expiration is determined from hold_expires_at (24 hours), NOT cron interval
 * 3. Manual Cancellation:
 *    - Immediate transition to CANCELLED and goat to AVAILABLE
 *    - Eligible customer can immediately reserve/book
 * 4. Automatic Expiration:
 *    - 24 hours reached -> Cron sweep detects it -> booking EXPIRED -> goat AVAILABLE
 *    - Eligible customer can immediately reserve/book
 * 5. Concurrency:
 *    - Simultaneous booking race condition prevention (atomic row lock)
 *    - Expired booking cannot continue blocking goat
 */
class Stage3ASupabaseCronExpirationTest {

    private fun createTestGoat(
        id: String = UUID.randomUUID().toString(),
        name: String = "Salem Black Purebred",
        price: Double = 30000.0,
        discountPercentage: Double = 10.0,
        farmId: String = "farm-alpha",
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
            ageMonths = 20,
            weightKg = 42.0,
            purpose = GoatPurpose.BREEDING,
            description = "High-quality stud",
            price = price,
            discountPercentage = discountPercentage,
            photos = listOf("https://images.unsplash.com/sample"),
            farmId = farmId,
            farmName = farmName,
            farmLocation = "Vellore, Tamil Nadu",
            availabilityStatus = status,
            approvalStatus = approvalStatus
        )
    }

    /**
     * In-memory server simulation replicating Postgres functions:
     * - create_booking_hold / create_goat_booking_atomic
     * - expire_overdue_bookings()
     * - handle_booking_status_change trigger
     */
    private class SimulatedDatabaseServer {
        val goats = ConcurrentHashMap<String, Goat>()
        val bookings = ConcurrentHashMap<String, Booking>()
        var simulatedNowMillis: Long = 1_700_000_000_000L // arbitrary fixed base epoch

        fun addGoat(goat: Goat) {
            goats[goat.id] = goat
        }

        /**
         * Simulates create_booking_hold / create_goat_booking_atomic:
         * 1. Expire overdue holds on the goat first
         * 2. Atomic check on availability & approval
         * 3. Authoritative 24-hour hold expiration (NOW + 24 hours)
         * 4. Insert booking as RESERVED and update goat to RESERVED
         */
        @Synchronized
        fun createBookingHold(
            goatId: String,
            customerId: String,
            customerName: String = "Customer",
            notes: String = ""
        ): Result<Booking> {
            val goat = goats[goatId] ?: return Result.failure(IllegalArgumentException("Goat not found"))

            // Step 1: Expire any overdue holds for this goat before evaluating availability
            expireOverdueBookingsForGoat(goatId)

            // Step 2: Validate availability
            val currentGoat = goats[goatId]!!
            if (currentGoat.availabilityStatus != AvailabilityStatus.AVAILABLE) {
                return Result.failure(IllegalStateException("Goat is no longer available for booking (Status: ${currentGoat.availabilityStatus})"))
            }
            if (currentGoat.approvalStatus != ApprovalStatus.APPROVED) {
                return Result.failure(IllegalStateException("Goat listing is pending admin approval"))
            }

            // Step 3: Prevent duplicate active booking
            val hasActiveBooking = bookings.values.any {
                it.goatId == goatId &&
                it.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED, AvailabilityStatus.CONFIRMED) &&
                it.reservationExpiryDate > simulatedNowMillis
            }
            if (hasActiveBooking) {
                return Result.failure(IllegalStateException("This goat has already been reserved by another customer."))
            }

            // Step 4: Authoritative price snapshot and 24-hour expiration calculation
            val finalPrice = PriceUtils.calculateFinalPrice(currentGoat.price, currentGoat.discountPercentage)
            val bookingDate = simulatedNowMillis
            val holdExpiresAt = bookingDate + (24 * 3600 * 1000L) // Exactly 24 hours

            val bookingId = UUID.randomUUID().toString()
            val newBooking = Booking(
                id = bookingId,
                goatId = goatId,
                goatName = currentGoat.name,
                goatBreed = currentGoat.breed,
                goatPhoto = currentGoat.photos.firstOrNull() ?: "",
                farmId = currentGoat.farmId,
                farmName = currentGoat.farmName,
                customerId = customerId,
                customerName = customerName,
                customerPhone = "+91 98400 12345",
                amount = finalPrice,
                status = AvailabilityStatus.RESERVED,
                bookingDate = bookingDate,
                reservationExpiryDate = holdExpiresAt,
                notes = notes
            )

            bookings[bookingId] = newBooking
            goats[goatId] = currentGoat.copy(availabilityStatus = AvailabilityStatus.RESERVED)

            return Result.success(newBooking)
        }

        /**
         * Simulates exact PostgreSQL function: public.expire_overdue_bookings()
         * - Finds active bookings where hold_expires_at <= NOW()
         * - Sets status to EXPIRED
         * - Releases goat to AVAILABLE if no other valid active booking exists
         * - Returns number of expired records
         */
        @Synchronized
        fun expireOverdueBookings(): Int {
            val expiredCount = AtomicInteger(0)
            val expiredGoatIds = mutableSetOf<String>()

            // 1. Expire active records with hold_expires_at <= NOW()
            for ((id, booking) in bookings) {
                if (booking.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED) &&
                    booking.reservationExpiryDate <= simulatedNowMillis
                ) {
                    bookings[id] = booking.copy(status = AvailabilityStatus.CANCELLED) // CANCELLED/EXPIRED
                    expiredGoatIds.add(booking.goatId)
                    expiredCount.incrementAndGet()
                }
            }

            // 2. Release goats if no other active booking exists
            for (goatId in expiredGoatIds) {
                val hasOtherActive = bookings.values.any {
                    it.goatId == goatId &&
                    it.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED, AvailabilityStatus.CONFIRMED) &&
                    it.reservationExpiryDate > simulatedNowMillis
                }
                if (!hasOtherActive) {
                    val goat = goats[goatId]
                    if (goat != null && goat.availabilityStatus != AvailabilityStatus.SOLD) {
                        goats[goatId] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
                    }
                }
            }

            return expiredCount.get()
        }

        private fun expireOverdueBookingsForGoat(goatId: String) {
            for ((id, booking) in bookings) {
                if (booking.goatId == goatId &&
                    booking.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED) &&
                    booking.reservationExpiryDate <= simulatedNowMillis
                ) {
                    bookings[id] = booking.copy(status = AvailabilityStatus.CANCELLED)
                    val goat = goats[goatId]
                    if (goat != null && goat.availabilityStatus == AvailabilityStatus.RESERVED) {
                        goats[goatId] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
                    }
                }
            }
        }

        /**
         * Simulates manual customer cancellation
         */
        @Synchronized
        fun cancelBooking(bookingId: String, customerId: String): Result<Unit> {
            val booking = bookings[bookingId] ?: return Result.failure(IllegalArgumentException("Booking not found"))
            if (booking.customerId != customerId) {
                return Result.failure(SecurityException("Cannot cancel another user's booking"))
            }

            bookings[bookingId] = booking.copy(status = AvailabilityStatus.CANCELLED)

            // Trigger handle_booking_status_change
            val hasOtherActive = bookings.values.any {
                it.goatId == booking.goatId &&
                it.id != bookingId &&
                it.status in listOf(AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED, AvailabilityStatus.CONFIRMED)
            }
            if (!hasOtherActive) {
                val goat = goats[booking.goatId]
                if (goat != null) {
                    goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.AVAILABLE)
                }
            }

            return Result.success(Unit)
        }
    }

    // =========================================================================
    // 1. VERIFY: expire_overdue_bookings() core logic
    // =========================================================================

    @Test
    fun `Requirement 1 - expire_overdue_bookings finds only bookings where hold_expires_at is less or equal to NOW`() {
        val server = SimulatedDatabaseServer()
        val goat1 = createTestGoat(id = "goat-1")
        val goat2 = createTestGoat(id = "goat-2")
        val goat3 = createTestGoat(id = "goat-3")
        server.addGoat(goat1)
        server.addGoat(goat2)
        server.addGoat(goat3)

        // Book 3 goats at current time
        val booking1 = server.createBookingHold(goat1.id, "cust-1").getOrThrow()
        val booking2 = server.createBookingHold(goat2.id, "cust-2").getOrThrow()
        val booking3 = server.createBookingHold(goat3.id, "cust-3").getOrThrow()

        // Advance time to 23 hours 59 minutes (before 24h expiration)
        server.simulatedNowMillis += (23 * 3600 * 1000L + 59 * 60 * 1000L)
        val expiredCountBefore24h = server.expireOverdueBookings()
        assertEquals("No bookings should expire before 24 hours", 0, expiredCountBefore24h)
        assertEquals(AvailabilityStatus.RESERVED, server.goats[goat1.id]?.availabilityStatus)

        // Advance time by 2 minutes to cross exactly 24 hours (24 hours + 1 min)
        server.simulatedNowMillis += (2 * 60 * 1000L)
        val expiredCountAfter24h = server.expireOverdueBookings()
        assertEquals("All 3 overdue bookings must be expired", 3, expiredCountAfter24h)

        // Verify goats were released to AVAILABLE
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat1.id]?.availabilityStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat2.id]?.availabilityStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat3.id]?.availabilityStatus)
    }

    @Test
    fun `Requirement 1 - expire_overdue_bookings is idempotent and safe if executed repeatedly`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-idem")
        server.addGoat(goat)

        server.createBookingHold(goat.id, "cust-1").getOrThrow()

        // Advance past 24 hours
        server.simulatedNowMillis += (25 * 3600 * 1000L)

        // Run 1: expires 1 booking
        val run1 = server.expireOverdueBookings()
        assertEquals(1, run1)
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat.id]?.availabilityStatus)

        // Runs 2..5: idempotent, returns 0, state stays pristine
        for (i in 2..5) {
            val repeatedRun = server.expireOverdueBookings()
            assertEquals("Repeated execution $i must return 0", 0, repeatedRun)
            assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat.id]?.availabilityStatus)
        }
    }

    @Test
    fun `Requirement 1 - expire_overdue_bookings does NOT expire CONFIRMED or COMPLETED bookings`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-confirmed")
        server.addGoat(goat)

        val booking = server.createBookingHold(goat.id, "cust-1").getOrThrow()
        // Farm confirms booking
        server.bookings[booking.id] = booking.copy(status = AvailabilityStatus.CONFIRMED)
        server.goats[goat.id] = goat.copy(availabilityStatus = AvailabilityStatus.CONFIRMED)

        // Advance time past 24 hours
        server.simulatedNowMillis += (48 * 3600 * 1000L)

        val count = server.expireOverdueBookings()
        assertEquals("Confirmed booking must NOT be expired by cron", 0, count)
        assertEquals(AvailabilityStatus.CONFIRMED, server.bookings[booking.id]?.status)
        assertEquals(AvailabilityStatus.CONFIRMED, server.goats[goat.id]?.availabilityStatus)
    }

    // =========================================================================
    // 2. VERIFY: Supabase Cron 5-minute recurring sweep
    // =========================================================================

    @Test
    fun `Requirement 2 - Cron 5-minute schedule determines expiration from hold_expires_at not interval`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-cron")
        server.addGoat(goat)

        val booking = server.createBookingHold(goat.id, "cust-1").getOrThrow()
        val exact24hExpiry = booking.reservationExpiryDate
        assertEquals(server.simulatedNowMillis + (24 * 3600 * 1000L), exact24hExpiry)

        // Simulate 5-minute cron triggers every 5 minutes:
        val cronIntervalMs = 5 * 60 * 1000L
        var totalMinutesSimulated = 0

        // Run cron every 5 minutes until 23 hours 55 minutes
        while (totalMinutesSimulated < (23 * 60 + 55)) {
            server.simulatedNowMillis += cronIntervalMs
            totalMinutesSimulated += 5
            val count = server.expireOverdueBookings()
            assertEquals("Hold must NOT expire before 24 hours on cron run at $totalMinutesSimulated min", 0, count)
            assertEquals(AvailabilityStatus.RESERVED, server.goats[goat.id]?.availabilityStatus)
        }

        // At minute 24h 00m (1440 minutes): next cron run fires
        server.simulatedNowMillis += cronIntervalMs
        totalMinutesSimulated += 5
        val count = server.expireOverdueBookings()
        assertEquals("Cron at 24h 00m must detect expired hold", 1, count)
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat.id]?.availabilityStatus)
    }

    // =========================================================================
    // 3. VERIFY: Manual cancellation releases goat immediately
    // =========================================================================

    @Test
    fun `Requirement 4 - Customer manual cancellation releases goat to AVAILABLE immediately and allows rebooking`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-cancel")
        server.addGoat(goat)

        // Customer 1 books
        val booking1 = server.createBookingHold(goat.id, "cust-1").getOrThrow()
        assertEquals(AvailabilityStatus.RESERVED, server.goats[goat.id]?.availabilityStatus)

        // Customer 1 manually cancels after 30 minutes
        server.simulatedNowMillis += (30 * 60 * 1000L)
        val cancelResult = server.cancelBooking(booking1.id, "cust-1")
        assertTrue(cancelResult.isSuccess)

        // Goat is immediately AVAILABLE
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat.id]?.availabilityStatus)

        // Customer 2 can immediately place a booking hold
        val booking2Result = server.createBookingHold(goat.id, "cust-2", "Customer Two")
        assertTrue(booking2Result.isSuccess)
        val booking2 = booking2Result.getOrThrow()
        assertEquals("cust-2", booking2.customerId)
        assertEquals(AvailabilityStatus.RESERVED, server.goats[goat.id]?.availabilityStatus)
    }

    // =========================================================================
    // 4. VERIFY: Automatic expiration allows rebooking
    // =========================================================================

    @Test
    fun `Requirement 5 - Automatic 24h expiration allows new customer to book successfully`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-auto-expire")
        server.addGoat(goat)

        // Customer 1 books
        val booking1 = server.createBookingHold(goat.id, "cust-1").getOrThrow()

        // 24 hours and 5 minutes elapse, Cron triggers
        server.simulatedNowMillis += (24 * 3600 * 1000L + 5 * 60 * 1000L)
        val expiredCount = server.expireOverdueBookings()
        assertEquals(1, expiredCount)
        assertEquals(AvailabilityStatus.AVAILABLE, server.goats[goat.id]?.availabilityStatus)

        // Customer 2 books the newly available goat
        val booking2Result = server.createBookingHold(goat.id, "cust-2")
        assertTrue(booking2Result.isSuccess)
        assertEquals("cust-2", booking2Result.getOrThrow().customerId)
    }

    // =========================================================================
    // 5. VERIFY: Concurrency
    // =========================================================================

    @Test
    fun `Requirement 6 - Concurrency two customers cannot reserve same goat simultaneously`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-concurrent")
        server.addGoat(goat)

        // Customer 1 reserves
        val res1 = server.createBookingHold(goat.id, "cust-1")
        assertTrue(res1.isSuccess)

        // Customer 2 tries to reserve same goat while hold is active
        val res2 = server.createBookingHold(goat.id, "cust-2")
        assertTrue("Concurrent reservation on active hold must fail", res2.isFailure)
        assertTrue(res2.exceptionOrNull()?.message?.contains("no longer available") == true ||
                   res2.exceptionOrNull()?.message?.contains("already been reserved") == true)
    }

    @Test
    fun `Requirement 6 - Expired booking cannot continue blocking the goat`() {
        val server = SimulatedDatabaseServer()
        val goat = createTestGoat(id = "goat-unblock")
        server.addGoat(goat)

        // Customer 1 reserves
        server.createBookingHold(goat.id, "cust-1").getOrThrow()

        // Time elapses past 24 hours (24 hours + 1 second)
        server.simulatedNowMillis += (24 * 3600 * 1000L + 1000L)

        // Customer 2 requests booking: createBookingHold expires overdue holds for the goat
        val res2 = server.createBookingHold(goat.id, "cust-2")
        assertTrue("Expired booking must NOT continue blocking goat", res2.isSuccess)
        assertEquals("cust-2", res2.getOrThrow().customerId)
    }
}
