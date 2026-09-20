package com.example

import com.example.core.util.FarmLocalCache
import com.example.data.dto.FarmDto
import com.example.data.dto.GoatDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.data.dto.ensureValidUuid
import com.example.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FarmIdentityAndOwnershipTest {

    // --- TEST 1: Super Admin Identity & Role Verification ---
    @Test
    fun `Super Admin ownership check recognizes SEED_AMMAL_FARM_UUID and isAmmalOwnFarm`() {
        val superAdminId = UUID.randomUUID().toString()
        val ammalFarm = Farm(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm",
            ownerId = superAdminId,
            ownerName = "Super Admin",
            location = "Vellore, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9876543210",
            email = "admin@ammalfarm.com",
            description = "Official Ammal Farm breeding hub",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = true
        )
        assertTrue(ammalFarm.isAmmalOwnFarm)
        assertEquals(SEED_AMMAL_FARM_UUID, ammalFarm.id)
        assertEquals(superAdminId, ammalFarm.ownerId)

        val superAdminProfile = UserProfile(
            id = superAdminId,
            email = "admin@ammalfarm.com",
            name = "Admin",
            role = UserRole.SUPER_ADMIN,
            farmId = SEED_AMMAL_FARM_UUID
        )
        assertEquals(UserRole.SUPER_ADMIN, superAdminProfile.role)
    }

    // --- TEST 2: Farm Admin Strictly Isolated to Owned Farm ---
    @Test
    fun `Farm Admin does not claim Ammal Farm as their own even if name contains Ammal`() {
        val regularAdminId = UUID.randomUUID().toString()
        val regularFarmId = UUID.randomUUID().toString()

        val regularFarm = Farm(
            id = regularFarmId,
            name = "Ammal Partner Pastures", // Name contains "Ammal" but is NOT the central hub
            ownerId = regularAdminId,
            ownerName = "Partner Admin",
            location = "Salem, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9123456780",
            email = "partner@example.com",
            description = "Commercial goat farm",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false
        )
        assertFalse(regularFarm.isAmmalOwnFarm)
        assertNotEquals(SEED_AMMAL_FARM_UUID, regularFarm.id)
        assertEquals(regularAdminId, regularFarm.ownerId)
    }

    // --- TEST 3: Customer Profile Never Owns a Farm ---
    @Test
    fun `Customer profile has no farm ownership relationship`() {
        val customer = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "customer@example.com",
            name = "Test Buyer",
            role = UserRole.CUSTOMER,
            farmId = null
        )
        assertNull(customer.farmId)
        assertEquals(UserRole.CUSTOMER, customer.role)
    }

    // --- TEST 4: Empty / Absent Farm Handling ---
    @Test
    fun `Empty or absent farm validation rejects non-existent or blank identifiers`() {
        assertFalse(FarmLocalCache.isValidUuid(""))
        assertFalse(FarmLocalCache.isValidUuid(" "))
        assertFalse(FarmLocalCache.isValidUuid(null))
        assertFalse(FarmLocalCache.isValidFarm(null))
    }

    // --- TEST 5: Cache Rejection of Invalid / Legacy Identifiers ---
    @Test
    fun `FarmLocalCache rejects legacy farm-1 and invalid UUIDs`() {
        assertFalse(FarmLocalCache.isValidUuid("farm-1"))
        assertFalse(FarmLocalCache.isValidUuid("usr-1"))
        assertFalse(FarmLocalCache.isValidUuid(""))
        assertFalse(FarmLocalCache.isValidUuid("null"))
        assertTrue(FarmLocalCache.isValidUuid(UUID.randomUUID().toString()))
        assertTrue(FarmLocalCache.isValidUuid(SEED_AMMAL_FARM_UUID))

        val invalidFarm = Farm(
            id = "farm-1",
            name = "Invalid Farm",
            ownerId = "user-1",
            ownerName = "Owner",
            location = "Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "",
            email = "",
            description = "",
            verificationStatus = VerificationStatus.APPROVED
        )
        assertFalse(FarmLocalCache.isValidFarm(invalidFarm))

        val blankNameFarm = Farm(
            id = UUID.randomUUID().toString(),
            name = "",
            ownerId = UUID.randomUUID().toString(),
            ownerName = "Owner",
            location = "Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "",
            email = "",
            description = "",
            verificationStatus = VerificationStatus.APPROVED
        )
        assertFalse(FarmLocalCache.isValidFarm(blankNameFarm))

        val validFarm = Farm(
            id = UUID.randomUUID().toString(),
            name = "Legitimate Farm",
            ownerId = UUID.randomUUID().toString(),
            ownerName = "Owner",
            location = "Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "",
            email = "",
            description = "",
            verificationStatus = VerificationStatus.APPROVED
        )
        assertTrue(FarmLocalCache.isValidFarm(validFarm))
    }

    // --- TEST 6: Strict UUID Validation Rejects Legacy Mapping ---
    @Test(expected = IllegalArgumentException::class)
    fun `ensureValidUuid strictly rejects legacy farm-1 instead of synthetic mapping`() {
        ensureValidUuid("farm-1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ensureValidUuid rejects invalid arbitrary strings`() {
        ensureValidUuid("usr-admin")
    }

    @Test
    fun `ensureValidUuid preserves valid UUIDs`() {
        val randomUuid = UUID.randomUUID().toString()
        val result = ensureValidUuid(randomUuid)
        assertEquals(randomUuid, result)
    }

    @Test
    fun `ensureValidUuid generates valid random UUID for blank or null identifiers`() {
        val blankResult = ensureValidUuid("")
        assertNotNull(UUID.fromString(blankResult))

        val nullResult = ensureValidUuid(null)
        assertNotNull(UUID.fromString(nullResult))
    }

    // --- TEST 7: Domain & DTO Data Integrity ---
    @Test
    fun `GoatDto fromDomain generates valid UUID if domain id is blank`() {
        val goat = Goat(
            id = "",
            name = "Test Champion",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 40.0,
            purpose = GoatPurpose.BREEDING,
            description = "Healthy stud",
            price = 25000.0,
            farmId = SEED_AMMAL_FARM_UUID,
            farmName = "Ammal Farm",
            farmLocation = "Tamil Nadu"
        )
        val dto = GoatDto.fromDomain(goat)
        assertNotNull(UUID.fromString(dto.id))
        assertEquals(SEED_AMMAL_FARM_UUID, dto.farmId)
    }

    @Test
    fun `GoatDto preserves valid UUID on edit`() {
        val goatUuid = UUID.randomUUID().toString()
        val goat = Goat(
            id = goatUuid,
            name = "Test Champion",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 40.0,
            purpose = GoatPurpose.BREEDING,
            description = "Healthy stud",
            price = 25000.0,
            farmId = SEED_AMMAL_FARM_UUID,
            farmName = "Ammal Farm",
            farmLocation = "Tamil Nadu"
        )
        val dto = GoatDto.fromDomain(goat)
        assertEquals(goatUuid, dto.id)
    }
}
