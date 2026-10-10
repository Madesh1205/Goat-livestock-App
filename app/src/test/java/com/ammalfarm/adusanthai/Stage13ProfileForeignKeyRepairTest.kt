package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage13ProfileForeignKeyRepairTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testProfileDtoOmitsNonExistentPhoneVerifiedColumnsFromSerialization() {
        val domainProfile = UserProfile(
            id = "39c33c2d-9964-48b6-ba85-fd064ff9bb3a",
            email = "ammalfarm@gmail.com",
            name = "sdadasd",
            phone = "6379458397",
            isPhoneVerified = false,
            role = UserRole.CUSTOMER
        )

        val dto = ProfileDto.fromDomain(domainProfile)
        val serialized = json.encodeToString(dto)

        // Ensure database table `profiles` schema compatibility
        assertFalse("Serialized JSON must not contain is_phone_verified column", serialized.contains("is_phone_verified"))
        assertFalse("Serialized JSON must not contain phone_verified column", serialized.contains("phone_verified"))
        assertTrue("Serialized JSON must include full_name", serialized.contains("full_name"))
        assertTrue("Serialized JSON must include phone", serialized.contains("phone"))
        assertTrue("Serialized JSON must include email", serialized.contains("email"))
        assertTrue("Serialized JSON must include role", serialized.contains("CUSTOMER"))
    }

    @Test
    fun testUserFriendlyErrorMapperHandlesForeignKeyViolationGracefully() {
        val fkeyException = IllegalStateException(
            """insert or update on table "bookings" violates foreign key constraint "bookings_customer_id_fkey" (Key (customer_id)=(39c33c2d-9964-48b6-ba85-fd064ff9bb3a) is not present in table "profiles".)"""
        )

        val friendlyMessage = UserFriendlyErrorMapper.forBooking(fkeyException)

        assertFalse("Raw constraint must not leak to user", friendlyMessage.contains("bookings_customer_id_fkey"))
        assertFalse("Raw table details must not leak to user", friendlyMessage.contains("violates foreign key"))
        assertTrue(
            "User should receive informative sync message",
            friendlyMessage.contains("syncing", ignoreCase = true) || friendlyMessage.contains("try again", ignoreCase = true)
        )
    }

    @Test
    fun testProfileDtoConstructorBackwardCompatibility() {
        val testDto = ProfileDto(
            id = "39c33c2d-9964-48b6-ba85-fd064ff9bb3a",
            email = "test@example.com",
            fullName = "Test User",
            phone = "9876543210",
            role = "CUSTOMER"
        )

        val domain = testDto.toDomain()
        assertEquals("39c33c2d-9964-48b6-ba85-fd064ff9bb3a", domain.id)
        assertEquals("test@example.com", domain.email)
        assertEquals("Test User", domain.name)
        assertEquals("9876543210", domain.phone)
        assertEquals(UserRole.CUSTOMER, domain.role)
    }
}
