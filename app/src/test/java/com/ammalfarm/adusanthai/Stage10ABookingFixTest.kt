package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage10ABookingFixTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine

    @Before
    fun setup() {
        dbEngine = MockRlsDatabaseEngine()
    }

    @Test
    fun test1_Customer_CanBook_PartnerFarm_And_AmmalFarm() = runBlocking {
        val customer = dbEngine.createUser("John Customer", "customer@example.com", UserRole.CUSTOMER, "9876543210")
        val partnerAdmin = dbEngine.createUser("Partner Admin", "partner@farm.com", UserRole.FARM_ADMIN, "9876543211")
        val ammalAdmin = dbEngine.createUser("Ammal Admin", "ammal@farm.com", UserRole.SUPER_ADMIN, "9876543212")

        val partnerFarmId = UUID.randomUUID().toString()
        val ammalFarmId = UUID.randomUUID().toString()

        dbEngine.createFarm(partnerFarmId, partnerAdmin.id, "Salem Breeding Farm", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(ammalFarmId, ammalAdmin.id, "Ammal Central Farm", VerificationStatus.APPROVED, true, 50)

        val partnerGoatId = UUID.randomUUID().toString()
        val ammalGoatId = UUID.randomUUID().toString()

        dbEngine.createGoat(partnerGoatId, partnerFarmId, "Jamunapari Buck", 22000.0, true)
        dbEngine.createGoat(ammalGoatId, ammalFarmId, "Ammal Champion Buck", 35000.0, true)

        // Customer books Partner Farm goat
        val partnerBookingResult = dbEngine.createBookingHold(customer, partnerGoatId, "Customer booking partner goat")
        assertTrue("Customer must be able to book eligible goat from partner farm", partnerBookingResult.isSuccess)

        // Customer books Ammal Farm goat
        val ammalBookingResult = dbEngine.createBookingHold(customer, ammalGoatId, "Customer booking Ammal goat")
        assertTrue("Customer must be able to book eligible goat from Ammal Farm", ammalBookingResult.isSuccess)
    }

    @Test
    fun test2_FarmAdmin_CanBook_OtherFarm_BlockedFrom_OwnFarm() = runBlocking {
        val farmAdminA = dbEngine.createUser("Admin Alpha", "alpha@farm.com", UserRole.FARM_ADMIN, "9876543220")
        val farmAdminB = dbEngine.createUser("Admin Beta", "beta@farm.com", UserRole.FARM_ADMIN, "9876543221")

        val farmAId = UUID.randomUUID().toString()
        val farmBId = UUID.randomUUID().toString()

        dbEngine.createFarm(farmAId, farmAdminA.id, "Alpha Farm", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(farmBId, farmAdminB.id, "Beta Farm", VerificationStatus.APPROVED, false, 10)

        val goatAId = UUID.randomUUID().toString()
        val goatBId = UUID.randomUUID().toString()

        dbEngine.createGoat(goatAId, farmAId, "Alpha Goat", 15000.0, true)
        dbEngine.createGoat(goatBId, farmBId, "Beta Goat", 18000.0, true)

        // Farm Admin A attempts to book own goat -> Must be blocked
        val ownBookingResult = dbEngine.createBookingHold(farmAdminA, goatAId, "Attempt own farm booking")
        assertTrue("Farm Admin booking own farm goat must fail", ownBookingResult.isFailure)
        val ownErrMsg = ownBookingResult.exceptionOrNull()?.message ?: ""
        assertTrue("Error message must contain own farm restriction", ownErrMsg.contains("You cannot book goats listed by your own farm."))

        // Farm Admin A attempts to book Farm B goat -> Must succeed
        val otherBookingResult = dbEngine.createBookingHold(farmAdminA, goatBId, "Booking goat from Beta Farm")
        assertTrue("Farm Admin A must be able to book goat from Beta Farm", otherBookingResult.isSuccess)
    }

    @Test
    fun test3_SuperAdmin_PreserveBookingCapability() = runBlocking {
        val superAdmin = dbEngine.createUser("Super Admin", "super@ammal.com", UserRole.SUPER_ADMIN, "9876543230")
        val partnerAdmin = dbEngine.createUser("Breeder", "breeder@farm.com", UserRole.FARM_ADMIN, "9876543231")

        val partnerFarmId = UUID.randomUUID().toString()
        dbEngine.createFarm(partnerFarmId, partnerAdmin.id, "Partner Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, partnerFarmId, "Super Test Goat", 20000.0, true)

        val bookingResult = dbEngine.createBookingHold(superAdmin, goatId, "Super Admin verification booking")
        assertTrue("Super Admin must be allowed to place booking holds", bookingResult.isSuccess)
    }

    @Test
    fun test4_UnapprovedOrUnavailableGoat_BookingFails() = runBlocking {
        val customer = dbEngine.createUser("Customer", "cust@example.com", UserRole.CUSTOMER, "9876543240")
        val partnerAdmin = dbEngine.createUser("Breeder", "breeder2@farm.com", UserRole.FARM_ADMIN, "9876543241")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, partnerAdmin.id, "Test Farm", VerificationStatus.APPROVED, false, 10)

        val unapprovedGoatId = UUID.randomUUID().toString()
        val unavailableGoatId = UUID.randomUUID().toString()

        // Unapproved goat
        dbEngine.createGoat(unapprovedGoatId, farmId, "Pending Goat", 10000.0, isApproved = false)
        val unapprovedBooking = dbEngine.createBookingHold(customer, unapprovedGoatId, "Attempt unapproved")
        assertTrue("Booking unapproved goat must fail", unapprovedBooking.isFailure)

        // Approved goat, but status != AVAILABLE
        dbEngine.createGoat(unavailableGoatId, farmId, "Sold Goat", 12000.0, isApproved = true)
        dbEngine.updateGoatStatus(unavailableGoatId, "SOLD")
        val unavailableBooking = dbEngine.createBookingHold(customer, unavailableGoatId, "Attempt sold")
        assertTrue("Booking non-available goat must fail", unavailableBooking.isFailure)
    }
}
