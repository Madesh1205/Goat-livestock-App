package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage9AFarmAdminBookingTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine

    @Before
    fun setup() {
        dbEngine = MockRlsDatabaseEngine()
    }

    @Test
    fun test1_FarmAdmin_BooksAnotherFarmsGoat_Success() = runBlocking {
        val farmAdminA = dbEngine.createUser("Admin A", "admina@farm.com", UserRole.FARM_ADMIN, "9876543201")
        val farmAdminB = dbEngine.createUser("Admin B", "adminb@farm.com", UserRole.FARM_ADMIN, "9876543202")

        val farmAId = UUID.randomUUID().toString()
        val farmBId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmAId, farmAdminA.id, "Farm A", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(farmBId, farmAdminB.id, "Farm B", VerificationStatus.APPROVED, false, 10)

        val goatBId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatBId, farmBId, "Goat B", 15000.0, true)

        val canBook = dbEngine.canBookGoat(
            callerId = farmAdminA.id,
            callerRole = UserRole.FARM_ADMIN,
            callerFarmId = farmAId,
            goatFarmId = farmBId,
            farmOwnerId = farmAdminB.id
        )
        assertTrue("FARM_ADMIN A must be allowed to book goat from Farm B", canBook)

        val bookingId = dbEngine.createBooking(goatBId, farmBId, farmAdminA.id, 15000.0)
        assertNotNull("Booking should be created successfully", bookingId)
    }

    @Test
    fun test2_FarmAdmin_AttemptsOwnFarmGoat_Blocked() = runBlocking {
        val farmAdminA = dbEngine.createUser("Admin A", "admina@farm.com", UserRole.FARM_ADMIN, "9876543201")
        val farmAId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmAId, farmAdminA.id, "Farm A", VerificationStatus.APPROVED, false, 10)

        val goatAId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatAId, farmAId, "Goat A", 12000.0, true)

        val canBookByFarmId = dbEngine.canBookGoat(
            callerId = farmAdminA.id,
            callerRole = UserRole.FARM_ADMIN,
            callerFarmId = farmAId,
            goatFarmId = farmAId,
            farmOwnerId = farmAdminA.id
        )
        assertFalse("FARM_ADMIN A must be blocked from booking goat from own Farm A", canBookByFarmId)

        val canBookByOwnerId = dbEngine.canBookGoat(
            callerId = farmAdminA.id,
            callerRole = UserRole.FARM_ADMIN,
            callerFarmId = null,
            goatFarmId = farmAId,
            farmOwnerId = farmAdminA.id
        )
        assertFalse("FARM_ADMIN A must be blocked from booking goat owned by themselves", canBookByOwnerId)
    }

    @Test
    fun test3_Customer_BooksAnotherFarmsGoat_Success() = runBlocking {
        val customer = dbEngine.createUser("Customer 1", "customer1@test.com", UserRole.CUSTOMER, "9876543203")
        val farmAdmin = dbEngine.createUser("Breeder", "breeder@test.com", UserRole.FARM_ADMIN, "9876543204")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, farmAdmin.id, "Partner Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Partner Goat", 18000.0, true)

        val canBook = dbEngine.canBookGoat(
            callerId = customer.id,
            callerRole = UserRole.CUSTOMER,
            callerFarmId = null,
            goatFarmId = farmId,
            farmOwnerId = farmAdmin.id
        )
        assertTrue("CUSTOMER must be allowed to book goat from partner farm", canBook)
    }

    @Test
    fun test4_Customer_BooksAmmalFarmGoat_Success() = runBlocking {
        val customer = dbEngine.createUser("Customer 2", "customer2@test.com", UserRole.CUSTOMER, "9876543205")
        val ammalAdmin = dbEngine.createUser("Ammal Admin", "ammal@test.com", UserRole.SUPER_ADMIN, "9876543206")

        val ammalFarmId = UUID.randomUUID().toString()
        dbEngine.createFarm(ammalFarmId, ammalAdmin.id, "Ammal Farm", VerificationStatus.APPROVED, true, 50)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, ammalFarmId, "Ammal Champion Goat", 25000.0, true)

        val canBook = dbEngine.canBookGoat(
            callerId = customer.id,
            callerRole = UserRole.CUSTOMER,
            callerFarmId = null,
            goatFarmId = ammalFarmId,
            farmOwnerId = ammalAdmin.id
        )
        assertTrue("CUSTOMER must be allowed to book goat from Ammal Farm", canBook)
    }

    @Test
    fun test5_DirectApiRpc_FarmAdmin_AnotherFarmGoat_Success() = runBlocking {
        val farmAdminA = dbEngine.createUser("Admin A", "admina_rpc@farm.com", UserRole.FARM_ADMIN, "9876543207")
        val farmAdminB = dbEngine.createUser("Admin B", "adminb_rpc@farm.com", UserRole.FARM_ADMIN, "9876543208")

        val farmAId = UUID.randomUUID().toString()
        val farmBId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmAId, farmAdminA.id, "Farm A RPC", VerificationStatus.APPROVED, false, 10)
        dbEngine.createFarm(farmBId, farmAdminB.id, "Farm B RPC", VerificationStatus.APPROVED, false, 10)

        val goatBId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatBId, farmBId, "Goat B RPC", 16000.0, true)

        // Simulate RPC server-side check
        val canBook = dbEngine.canBookGoat(
            callerId = farmAdminA.id,
            callerRole = UserRole.FARM_ADMIN,
            callerFarmId = farmAId,
            goatFarmId = farmBId,
            farmOwnerId = farmAdminB.id
        )
        assertTrue("Direct RPC for FARM_ADMIN A booking Farm B goat must succeed", canBook)
    }

    @Test
    fun test6_DirectApiRpc_FarmAdmin_OwnFarmGoat_Blocked() = runBlocking {
        val farmAdminA = dbEngine.createUser("Admin A", "admina_rpc2@farm.com", UserRole.FARM_ADMIN, "9876543209")

        val farmAId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmAId, farmAdminA.id, "Farm A RPC 2", VerificationStatus.APPROVED, false, 10)

        val goatAId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatAId, farmAId, "Goat A RPC 2", 14000.0, true)

        // Simulate RPC server-side check
        val canBook = dbEngine.canBookGoat(
            callerId = farmAdminA.id,
            callerRole = UserRole.FARM_ADMIN,
            callerFarmId = farmAId,
            goatFarmId = farmAId,
            farmOwnerId = farmAdminA.id
        )
        assertFalse("Direct RPC for FARM_ADMIN A booking own Farm A goat must be blocked", canBook)
    }

    @Test
    fun test7_SuperAdmin_ExistingBehavior_Unchanged() = runBlocking {
        val superAdmin = dbEngine.createUser("Super Admin", "super@test.com", UserRole.SUPER_ADMIN, "9876543210")
        val farmAdmin = dbEngine.createUser("Breeder", "breeder2@test.com", UserRole.FARM_ADMIN, "9876543211")

        val farmId = UUID.randomUUID().toString()
        dbEngine.createFarm(farmId, farmAdmin.id, "Farm X", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, farmId, "Goat X", 20000.0, true)

        val canBookOther = dbEngine.canBookGoat(
            callerId = superAdmin.id,
            callerRole = UserRole.SUPER_ADMIN,
            callerFarmId = null,
            goatFarmId = farmId,
            farmOwnerId = farmAdmin.id
        )
        assertTrue("SUPER_ADMIN can book any goat", canBookOther)
    }
}
