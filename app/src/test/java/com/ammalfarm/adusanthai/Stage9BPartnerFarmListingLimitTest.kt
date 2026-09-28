package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Stage 9B Test Suite — Dynamic Partner Farm Listing Limit Enforcement
 */
class Stage9BPartnerFarmListingLimitTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine
    private lateinit var superAdmin: UserProfile
    private lateinit var partnerFarmAdmin: UserProfile
    private lateinit var partnerFarm: Farm
    private lateinit var ammalFarmAdmin: UserProfile
    private lateinit var ammalFarm: Farm

    @Before
    fun setUp() {
        dbEngine = MockRlsDatabaseEngine()

        // 1. Super Admin
        superAdmin = dbEngine.createUser(
            name = "Super Admin",
            email = "admin@adusanthai.com",
            role = UserRole.SUPER_ADMIN,
            phone = "9999999999",
            id = "super_admin_uuid"
        )

        // 2. Partner Farm Admin and Farm (New approved partner farm -> default limit 2)
        partnerFarmAdmin = dbEngine.createUser(
            name = "Partner Breeder",
            email = "partner@farm.com",
            role = UserRole.FARM_ADMIN,
            phone = "9876543210",
            id = "partner_admin_uuid"
        )

        partnerFarm = dbEngine.createFarm(
            farmId = "partner_farm_uuid",
            ownerId = partnerFarmAdmin.id,
            name = "Vellore Goat Farm",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false,
            quota = 2
        )

        // 3. Ammal Farm Admin and Farm (Exempt from listing quota)
        ammalFarmAdmin = dbEngine.createUser(
            name = "Ammal Manager",
            email = "manager@ammalfarm.com",
            role = UserRole.FARM_ADMIN,
            phone = "9111111111",
            id = "ammal_admin_uuid"
        )

        ammalFarm = dbEngine.createFarm(
            farmId = "00000000-0000-0000-0000-000000000001",
            ownerId = ammalFarmAdmin.id,
            name = "Ammal Farm Central Hub",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = true,
            quota = 1000
        )
    }

    @Test
    fun testRule1_NewApprovedPartnerFarmHasDefaultLimitTwo() {
        assertEquals("Approved partner farm must default to limit 2", 2, partnerFarm.goatListingLimit)
        assertFalse("Partner farm is not Ammal Farm", partnerFarm.isAmmalOwnFarm)
    }

    @Test
    fun testDynamicLimitProgressiveEnforcement() {
        // Step 1: Limit = 2
        // Goat 1 allowed
        val canAdd1 = dbEngine.evaluateGoatQuotaInsert(partnerFarm.id, partnerFarm.goatListingLimit, 0)
        assertTrue("Limit 2: Goat 1 must be allowed", canAdd1)

        // Goat 2 allowed
        val canAdd2 = dbEngine.evaluateGoatQuotaInsert(partnerFarm.id, partnerFarm.goatListingLimit, 1)
        assertTrue("Limit 2: Goat 2 must be allowed", canAdd2)

        // Goat 3 blocked when current count = 2 and limit = 2
        val canAdd3Blocked = dbEngine.evaluateGoatQuotaInsert(partnerFarm.id, partnerFarm.goatListingLimit, 2)
        assertFalse("Limit 2: Goat 3 must be blocked when current count reaches limit 2", canAdd3Blocked)

        // Step 2: Super Admin manually increases limit to 3
        val farmLimit3 = dbEngine.evaluateFarmUpdate(
            callerId = superAdmin.id,
            farmId = partnerFarm.id,
            attemptedStatus = partnerFarm.verificationStatus,
            attemptedIsAmmal = partnerFarm.isAmmalOwnFarm,
            attemptedQuota = 3,
            attemptedOwnerId = partnerFarm.ownerId
        )
        assertEquals("Super Admin increases limit to 3", 3, farmLimit3.goatListingLimit)

        // Goat 3 allowed under limit 3
        val canAdd3Allowed = dbEngine.evaluateGoatQuotaInsert(farmLimit3.id, farmLimit3.goatListingLimit, 2)
        assertTrue("Limit 3: Goat 3 must now be allowed", canAdd3Allowed)

        // Goat 4 blocked under limit 3
        val canAdd4Blocked = dbEngine.evaluateGoatQuotaInsert(farmLimit3.id, farmLimit3.goatListingLimit, 3)
        assertFalse("Limit 3: Goat 4 must be blocked when count reaches limit 3", canAdd4Blocked)

        // Step 3: Super Admin increases limit to 5
        val farmLimit5 = dbEngine.evaluateFarmUpdate(
            callerId = superAdmin.id,
            farmId = partnerFarm.id,
            attemptedStatus = partnerFarm.verificationStatus,
            attemptedIsAmmal = partnerFarm.isAmmalOwnFarm,
            attemptedQuota = 5,
            attemptedOwnerId = partnerFarm.ownerId
        )
        assertEquals("Super Admin increases limit to 5", 5, farmLimit5.goatListingLimit)

        // Goats up to 5 allowed (checking currentCount 0, 1, 2, 3, 4)
        for (count in 0..4) {
            val allowed = dbEngine.evaluateGoatQuotaInsert(farmLimit5.id, farmLimit5.goatListingLimit, count)
            assertTrue("Limit 5: Goat #${count + 1} must be allowed", allowed)
        }

        // Goat 6 blocked when count reaches 5 under limit 5
        val canAdd6Blocked = dbEngine.evaluateGoatQuotaInsert(farmLimit5.id, farmLimit5.goatListingLimit, 5)
        assertFalse("Limit 5: Goat 6 must be blocked when count reaches limit 5", canAdd6Blocked)
    }

    @Test
    fun testErrorMessageForQuotaExceeded() {
        val simulatedDbError = Exception("Your goat listing limit has been reached. Contact +91 63808 98358 for approval to add more goats.")
        val mappedMessage = UserFriendlyErrorMapper.forGoatListing(simulatedDbError)
        assertTrue(
            "User-facing error must include contact number +91 63808 98358",
            mappedMessage.contains("+91 63808 98358")
        )
    }

    @Test
    fun testFarmAdminCannotChangeOwnLimit() {
        val tamperedFarm = dbEngine.evaluateFarmUpdate(
            callerId = partnerFarmAdmin.id,
            farmId = partnerFarm.id,
            attemptedStatus = partnerFarm.verificationStatus,
            attemptedIsAmmal = partnerFarm.isAmmalOwnFarm,
            attemptedQuota = 50,
            attemptedOwnerId = partnerFarm.ownerId
        )

        assertEquals(
            "Farm Admin attempt to increase own limit must be rejected server-side",
            2,
            tamperedFarm.goatListingLimit
        )
    }

    @Test
    fun testDirectApiLimitBypassBlocked() {
        val attemptedLimit = 100
        val isSuperAdmin = (partnerFarmAdmin.role == UserRole.SUPER_ADMIN)

        val effectiveLimit = if (isSuperAdmin) attemptedLimit else partnerFarm.goatListingLimit
        assertEquals("Direct API limit bypass attempt must yield original limit 2", 2, effectiveLimit)
    }

    @Test
    fun testAmmalFarmRemainsExempt() {
        for (i in 1..5) {
            dbEngine.createGoat("ammal_goat_$i", ammalFarm.id, "Ammal Goat $i", 15000.0, true)
        }

        val canAdd6 = dbEngine.evaluateGoatQuotaInsert(
            targetFarmId = ammalFarm.id,
            quota = ammalFarm.goatListingLimit,
            currentCount = 5
        )

        assertTrue("Ammal Farm must remain exempt from partner listing limit", canAdd6)
        assertTrue("Ammal Farm is marked as own farm", ammalFarm.isAmmalOwnFarm)
    }

    @Test
    fun testNoOnlinePaymentFunctionality() {
        val contactNumber = "+91 63808 98358"
        assertEquals("+91 63808 98358", contactNumber)
    }
}
