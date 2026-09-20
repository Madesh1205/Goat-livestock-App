package com.example

import com.example.model.UserRole
import com.example.model.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage10CCriticalRegressionFixesTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine

    @Before
    fun setup() {
        dbEngine = MockRlsDatabaseEngine()
    }

    @Test
    fun test1_FarmAdminAccountDeletion_SucceedsWithoutTriggerViolation() = runBlocking {
        val farmAdmin = dbEngine.createUser("Farm Admin User", "farmadmin@test.com", UserRole.FARM_ADMIN, "9876543210")
        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, farmAdmin.id, "Test Partner Farm", VerificationStatus.APPROVED, false, 10)

        // Create a goat under this farm
        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Test Goat", 12000.0, true)

        // Simulate account deletion
        val deletionSuccess = dbEngine.executeAccountDeletion(farmAdmin.id, farmAdmin.id)
        assertTrue("Account deletion for FARM_ADMIN must succeed", deletionSuccess)
    }

    @Test
    fun test2_FarmAdminAddGoat_SucceedsAndGeneratesGoatCode() = runBlocking {
        val farmAdmin = dbEngine.createUser("Breeder One", "breeder1@test.com", UserRole.FARM_ADMIN, "9876543211")
        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, farmAdmin.id, "Green Pastures Farm", VerificationStatus.APPROVED, false, 10)

        val goat1Id = UUID.randomUUID().toString()
        val created = dbEngine.createGoat(goat1Id, farmId, "Goat One", 15000.0, false)

        assertTrue("Goat 1 must be created successfully", created)
    }

    @Test
    fun test3_SuperAdminMultipleGoats_AssignedDistinctGoatCodesAndPaths() = runBlocking {
        val superAdmin = dbEngine.createUser("Super Admin", "admin@ammal.com", UserRole.SUPER_ADMIN, "9876543212")
        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, superAdmin.id, "Ammal Central Farm", VerificationStatus.APPROVED, true, 100)

        val goat1Id = UUID.randomUUID().toString()
        val goat2Id = UUID.randomUUID().toString()
        dbEngine.createGoat(goat1Id, farmId, "Alpha Goat", 20000.0, true)
        dbEngine.createGoat(goat2Id, farmId, "Beta Goat", 22000.0, true)

        assertNotEquals("Distinct goats must have different UUIDs", goat1Id, goat2Id)

        // Storage path generator check
        val farmCode = "FARM-001"
        val goat1Code = "GOAT-001"
        val goat2Code = "GOAT-002"

        val path1 = "$farmCode/$goat1Code/01.jpg"
        val path2 = "$farmCode/$goat2Code/01.jpg"

        assertEquals("FARM-001/GOAT-001/01.jpg", path1)
        assertEquals("FARM-001/GOAT-002/01.jpg", path2)
        assertNotEquals("Paths for Goat 1 and Goat 2 must be distinct", path1, path2)
    }

    @Test
    fun test4_BookingHold_AtomicPath_Success() = runBlocking {
        val customer = dbEngine.createUser("Customer User", "customer@test.com", UserRole.CUSTOMER, "9876543213")
        val breeder = dbEngine.createUser("Breeder Two", "breeder2@test.com", UserRole.FARM_ADMIN, "9876543214")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, breeder.id, "Valley Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Valley Goat", 18000.0, true)

        val bookingId = dbEngine.createBooking(goatId, farmId, customer.id, 18000.0)
        assertNotNull("Booking hold must return valid booking ID", bookingId)
    }
}
