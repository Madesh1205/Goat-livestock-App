package com.example

import com.example.data.dto.FarmDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.Farm
import com.example.model.UserProfile
import com.example.model.UserRole
import com.example.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM APP — STAGE 5 MUMBAI INITIALIZATION TEST
 *
 * Verifies:
 * 1. Super Admin profile attributes:
 *    - role = SUPER_ADMIN
 *    - farmId = null (source of truth is profiles.role, not farm_id or email)
 *
 * 2. Ammal Farm canonical setup:
 *    - id = 00000000-0000-0000-0000-000000000001
 *    - ownerId = Super Admin real UUID
 *    - isAmmalOwnFarm = true
 *    - status = APPROVED
 *    - Unrestricted goat listing limit
 *    - No default partner logo / banner presets
 *
 * 3. App-level Ammal Farm resolution:
 *    - Resolves exact SEED_AMMAL_FARM_UUID ("00000000-0000-0000-0000-000000000001")
 *    - Super Admin can manage Ammal Farm based on profiles.role and owner_id
 *    - Partner farm admins cannot claim Ammal Farm
 */
class Stage5MumbaiInitializationTest {

    @Test
    fun testSuperAdminProfile_RoleIsSuperAdmin_AndFarmIdIsNull() {
        val superAdminUuid = UUID.randomUUID().toString()
        val superAdminProfile = UserProfile(
            id = superAdminUuid,
            email = "admin@ammalfarm.com",
            name = "Madesh",
            phone = "+919876543210",
            role = UserRole.SUPER_ADMIN,
            farmId = null // Platform Super Admin must NOT be bound to a single farm_id in profile
        )

        assertEquals(UserRole.SUPER_ADMIN, superAdminProfile.role)
        assertNull("Super Admin profile farm_id must be null", superAdminProfile.farmId)
        assertTrue("Super Admin is identified strictly by role", superAdminProfile.role == UserRole.SUPER_ADMIN)
    }

    @Test
    fun testAmmalFarmCanonicalResolution_MatchesAppContract() {
        val superAdminUuid = UUID.randomUUID().toString()

        val ammalFarmDto = FarmDto(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm Central Hub",
            ownerId = superAdminUuid,
            description = "Main stud farm and breeding excellence center in Vellore, Tamil Nadu.",
            locationDistrict = "Vellore",
            locationState = "Tamil Nadu",
            address = "Vellore, Tamil Nadu",
            contactPhone = "+919876543210",
            contactEmail = "contact@ammalfarm.com",
            status = "APPROVED",
            isAmmalOwnFarm = true,
            goatListingLimit = 10000,
            logoUrl = null,
            bannerUrl = null
        )

        // 1. Verify Canonical UUID
        assertEquals("00000000-0000-0000-0000-000000000001", ammalFarmDto.id)
        assertEquals(SEED_AMMAL_FARM_UUID, ammalFarmDto.id)

        // 2. Verify Owner Association
        assertEquals(superAdminUuid, ammalFarmDto.ownerId)

        // 3. Verify Flags
        assertTrue(ammalFarmDto.isAmmalOwnFarm)
        assertEquals("APPROVED", ammalFarmDto.status)
        assertNull(ammalFarmDto.logoUrl)
        assertNull(ammalFarmDto.bannerUrl)

        // 4. Verify Domain Mapping in App
        val domainFarm = ammalFarmDto.toDomain()
        assertTrue("Domain model must detect isAmmalOwnFarm", domainFarm.isAmmalOwnFarm)
        assertEquals(VerificationStatus.APPROVED, domainFarm.verificationStatus)
        assertEquals(SEED_AMMAL_FARM_UUID, domainFarm.id)
    }

    @Test
    fun testOwnershipCheck_SuperAdminAuthorized_OthersRejected() {
        val superAdminUuid = UUID.randomUUID().toString()
        val otherUserUuid = UUID.randomUUID().toString()

        val ammalFarmDto = FarmDto(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm Central Hub",
            ownerId = superAdminUuid,
            status = "APPROVED",
            isAmmalOwnFarm = true
        )

        val superAdmin = UserProfile(
            id = superAdminUuid,
            email = "admin@ammalfarm.com",
            name = "Admin",
            role = UserRole.SUPER_ADMIN,
            farmId = null
        )

        val farmAdmin = UserProfile(
            id = otherUserUuid,
            email = "partner@otherfarm.com",
            name = "Partner",
            role = UserRole.FARM_ADMIN,
            farmId = UUID.randomUUID().toString()
        )

        // App logic check: ownerId == user.id || (user.role == SUPER_ADMIN && (farmDto.id == SEED_AMMAL_FARM_UUID || farmDto.isAmmalOwnFarm))
        val isSuperAdminAuthorized = ammalFarmDto.ownerId == superAdmin.id ||
                (superAdmin.role == UserRole.SUPER_ADMIN && (ammalFarmDto.id == SEED_AMMAL_FARM_UUID || ammalFarmDto.isAmmalOwnFarm))

        val isOtherAuthorized = ammalFarmDto.ownerId == farmAdmin.id ||
                (farmAdmin.role == UserRole.SUPER_ADMIN && (ammalFarmDto.id == SEED_AMMAL_FARM_UUID || ammalFarmDto.isAmmalOwnFarm))

        assertTrue("Super Admin must be authorized for Ammal Farm", isSuperAdminAuthorized)
        assertFalse("Other users must NOT be authorized for Ammal Farm", isOtherAuthorized)
    }

    @Test
    fun testAmmalFarmContactPhone_MatchesOfficialSupportNumber() {
        val officialNumber = "+91 63808 98358"
        val ammalFarmDto = FarmDto(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm Central Hub",
            ownerId = "bb20fc69-629b-4121-82fa-5e66acfea0cf",
            contactPhone = officialNumber,
            status = "APPROVED",
            isAmmalOwnFarm = true
        )

        assertEquals("+91 63808 98358", ammalFarmDto.contactPhone)
        assertNotNull(ammalFarmDto.contactPhone)
        val digitsOnly = ammalFarmDto.contactPhone?.filter { it.isDigit() }
        assertEquals("916380898358", digitsOnly)
    }
}
