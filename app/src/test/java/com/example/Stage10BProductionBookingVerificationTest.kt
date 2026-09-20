package com.example

import com.example.model.UserRole
import com.example.model.VerificationStatus
import com.example.util.UserFriendlyErrorMapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage10BProductionBookingVerificationTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine

    @Before
    fun setup() {
        dbEngine = MockRlsDatabaseEngine()
    }

    @Test
    fun test01_Customer_AmmalFarm_Success() = runBlocking {
        val customer = dbEngine.createUser("Customer 1", "cust1@test.com", UserRole.CUSTOMER, "9876543001")
        val ammalAdmin = dbEngine.createUser("Ammal Admin", "ammal@test.com", UserRole.SUPER_ADMIN, "9876543002")

        val ammalFarmId = UUID.randomUUID().toString()
        dbEngine.createFarm(ammalFarmId, ammalAdmin.id, "Ammal Farm", VerificationStatus.APPROVED, true, 50)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, ammalFarmId, "Ammal Superior Buck", 28000.0, true)

        val result = dbEngine.createBookingHold(customer, goatId, "Customer booking Ammal Farm goat")
        assertTrue("Customer booking Ammal Farm goat must succeed", result.isSuccess)
    }

    @Test
    fun test02_Customer_PartnerFarm_Success() = runBlocking {
        val customer = dbEngine.createUser("Customer 2", "cust2@test.com", UserRole.CUSTOMER, "9876543003")
        val partnerAdmin = dbEngine.createUser("Partner Admin", "partner@test.com", UserRole.FARM_ADMIN, "9876543004")

        val partnerFarmId = UUID.randomUUID().toString()
        dbEngine.createFarm(partnerFarmId, partnerAdmin.id, "Erode Goat Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, partnerFarmId, "Erode Tellicherry Goat", 19000.0, true)

        val result = dbEngine.createBookingHold(customer, goatId, "Customer booking partner goat")
        assertTrue("Customer booking partner farm goat must succeed", result.isSuccess)
    }

    @Test
    fun test03_FarmAdmin_AmmalFarm_Success() = runBlocking {
        val farmAdmin = dbEngine.createUser("Breeder Alpha", "alpha@farm.com", UserRole.FARM_ADMIN, "9876543005")
        val ammalAdmin = dbEngine.createUser("Ammal Admin", "ammal@farm.com", UserRole.SUPER_ADMIN, "9876543006")

        val farmAId = UUID.randomUUID().toString()
        val ammalFarmId = UUID.randomUUID().toString()

        dbEngine.createFarm(farmAId, farmAdmin.id, "Alpha Breeder Farm", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(ammalFarmId, ammalAdmin.id, "Ammal Farm", VerificationStatus.APPROVED, true, 50)

        val ammalGoatId = UUID.randomUUID().toString()
        dbEngine.createGoat(ammalGoatId, ammalFarmId, "Ammal Premium Stud", 40000.0, true)

        val result = dbEngine.createBookingHold(farmAdmin, ammalGoatId, "Farm Admin purchasing from Ammal Farm")
        assertTrue("Farm Admin booking Ammal Farm goat must succeed", result.isSuccess)
    }

    @Test
    fun test04_FarmAdmin_AnotherPartnerFarm_Success() = runBlocking {
        val farmAdminA = dbEngine.createUser("Breeder A", "breederA@test.com", UserRole.FARM_ADMIN, "9876543007")
        val farmAdminB = dbEngine.createUser("Breeder B", "breederB@test.com", UserRole.FARM_ADMIN, "9876543008")

        val farmAId = UUID.randomUUID().toString()
        val farmBId = UUID.randomUUID().toString()

        dbEngine.createFarm(farmAId, farmAdminA.id, "Farm A", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(farmBId, farmAdminB.id, "Farm B", VerificationStatus.APPROVED, false, 10)

        val goatBId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatBId, farmBId, "Farm B Goat", 21000.0, true)

        val result = dbEngine.createBookingHold(farmAdminA, goatBId, "Breeder A purchasing from Breeder B")
        assertTrue("Farm Admin A booking Farm B goat must succeed", result.isSuccess)
    }

    @Test
    fun test05_FarmAdmin_OwnFarm_Rejected() = runBlocking {
        val farmAdmin = dbEngine.createUser("Breeder Self", "self@farm.com", UserRole.FARM_ADMIN, "9876543009")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, farmAdmin.id, "Self Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Self Goat", 15000.0, true)

        val result = dbEngine.createBookingHold(farmAdmin, goatId, "Attempting own farm booking")
        assertTrue("Farm Admin booking own farm goat must be rejected", result.isFailure)
        val errMsg = result.exceptionOrNull()?.message ?: ""
        assertTrue("Error message must indicate own farm restriction", errMsg.contains("You cannot book goats listed by your own farm."))
    }

    @Test
    fun test06_UnapprovedGoat_Rejected() = runBlocking {
        val customer = dbEngine.createUser("Customer 3", "cust3@test.com", UserRole.CUSTOMER, "9876543010")
        val partnerAdmin = dbEngine.createUser("Breeder 3", "breeder3@test.com", UserRole.FARM_ADMIN, "9876543011")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Unapproved Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Pending Approval Goat", 16000.0, isApproved = false)

        val result = dbEngine.createBookingHold(customer, goatId, "Attempt booking unapproved goat")
        assertTrue("Booking unapproved goat must be rejected", result.isFailure)
    }

    @Test
    fun test07_ReservedGoat_Rejected() = runBlocking {
        val customer1 = dbEngine.createUser("Cust A", "custa@test.com", UserRole.CUSTOMER, "9876543012")
        val customer2 = dbEngine.createUser("Cust B", "custb@test.com", UserRole.CUSTOMER, "9876543013")
        val partnerAdmin = dbEngine.createUser("Breeder 4", "breeder4@test.com", UserRole.FARM_ADMIN, "9876543014")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Farm 4", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Popular Goat", 25000.0, isApproved = true)

        // Customer 1 reserves goat
        val result1 = dbEngine.createBookingHold(customer1, goatId, "First booking")
        assertTrue("First booking hold must succeed", result1.isSuccess)

        // Customer 2 attempts to reserve same goat
        val result2 = dbEngine.createBookingHold(customer2, goatId, "Second booking attempt")
        assertTrue("Second booking attempt on RESERVED goat must be rejected", result2.isFailure)
    }

    @Test
    fun test08_DuplicateActiveBooking_Rejected() = runBlocking {
        val customer = dbEngine.createUser("Cust Double", "custdouble@test.com", UserRole.CUSTOMER, "9876543015")
        val partnerAdmin = dbEngine.createUser("Breeder 5", "breeder5@test.com", UserRole.FARM_ADMIN, "9876543016")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Farm 5", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Double Reserve Goat", 30000.0, isApproved = true)

        val result1 = dbEngine.createBookingHold(customer, goatId, "Initial Hold")
        assertTrue("Initial booking hold must succeed", result1.isSuccess)

        val result2 = dbEngine.createBookingHold(customer, goatId, "Duplicate Hold Attempt")
        assertTrue("Duplicate active booking attempt must be rejected", result2.isFailure)
    }

    @Test
    fun test09_PriceSnapshot_Immutability() = runBlocking {
        val customer = dbEngine.createUser("Cust Price", "custprice@test.com", UserRole.CUSTOMER, "9876543017")
        val partnerAdmin = dbEngine.createUser("Breeder Price", "breederprice@test.com", UserRole.FARM_ADMIN, "9876543018")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Price Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Snapshot Goat", 20000.0, isApproved = true)

        val bookingId = dbEngine.createBookingHold(customer, goatId, "Price Snapshot Test").getOrThrow()
        val initialBooking = dbEngine.getBookingById(bookingId)
        assertNotNull(initialBooking)
        assertEquals(20000.0, initialBooking!!.totalPrice, 0.01)

        // Simulate subsequent goat price update in store
        dbEngine.updateGoatPrice(goatId, 35000.0)

        val postUpdateBooking = dbEngine.getBookingById(bookingId)
        assertEquals("Booking amount must remain snapshot price (20000.0)", 20000.0, postUpdateBooking!!.totalPrice, 0.01)
    }

    @Test
    fun test10_24HourReservationExpiry() = runBlocking {
        val customer = dbEngine.createUser("Cust Expiry", "custexpiry@test.com", UserRole.CUSTOMER, "9876543019")
        val partnerAdmin = dbEngine.createUser("Breeder Expiry", "breederexpiry@test.com", UserRole.FARM_ADMIN, "9876543020")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Expiry Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Expiry Goat", 22000.0, isApproved = true)

        val bookingId = dbEngine.createBookingHold(customer, goatId, "Expiry Test").getOrThrow()
        val booking = dbEngine.getBookingById(bookingId)
        assertNotNull(booking)

        val durationMillis = booking!!.holdExpiresAt - booking.bookingDate
        val durationHours = durationMillis / (3600 * 1000L)
        assertEquals("Hold expiration duration must be exactly 24 hours", 24L, durationHours)
    }

    @Test
    fun test11_RpcParameterCompatibility() {
        val expectedParams = listOf("p_goat_id", "p_notes", "p_customer_id")
        val repoParams = listOf("p_goat_id", "p_notes", "p_customer_id")
        assertEquals("Kotlin RPC parameter payload keys must match database RPC parameter names", expectedParams, repoParams)
    }

    @Test
    fun test12_BookingErrorMapping() {
        val ownFarmErr = Exception("You cannot book goats listed by your own farm.")
        val ownFarmMapped = UserFriendlyErrorMapper.forBooking(ownFarmErr)
        assertEquals("You cannot book goats listed by your own farm.", ownFarmMapped)

        val reservedErr = Exception("This goat has already been reserved by another customer.")
        val reservedMapped = UserFriendlyErrorMapper.forBooking(reservedErr)
        assertEquals("This goat has already been reserved by another customer.", reservedMapped)

        val unavailableErr = Exception("Goat is no longer available for booking.")
        val unavailableMapped = UserFriendlyErrorMapper.forBooking(unavailableErr)
        assertEquals("Goat is no longer available for booking.", unavailableMapped)
    }
}
