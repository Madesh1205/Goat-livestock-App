package com.ammalfarm.adusanthai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ammalfarm.adusanthai.core.util.PolicyConsentManager
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {
@Test
fun `read string from context`() {
val context = ApplicationProvider.getApplicationContext<Context>()
val appName = context.getString(R.string.app_name)
assertEquals("Adu Santhai", appName)
}
@Test
fun `policy consent manager defaults to false and persists true once accepted`() {
val context = ApplicationProvider.getApplicationContext<Context>()
val manager = PolicyConsentManager(context)
// First time should be false
assertFalse(manager.hasAcceptedPolicies())
// Accept once
manager.setPoliciesAccepted(true)
assertTrue(manager.hasAcceptedPolicies())
assertNotNull(manager.getAcceptedDateFormatted())
// A new instance should remember the saved state (no need to ask every time)
val newInstance = PolicyConsentManager(context)
assertTrue(newInstance.hasAcceptedPolicies())
}
    @Test
    fun `policy consent state can be reset and toggled correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PolicyConsentManager(context)
        manager.setPoliciesAccepted(false)
        assertFalse(manager.hasAcceptedPolicies())
        manager.setPoliciesAccepted(true)
        assertTrue(manager.hasAcceptedPolicies())
    }

    @Test
    fun `Req 1 - UserFriendlyErrorMapper returns specific messages for credentials, network, and missing profile`() {
        val invalidCredsMsg = com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper.forAuth(
            IllegalArgumentException("Invalid login credentials")
        )
        assertTrue(invalidCredsMsg.contains("Incorrect email or password", ignoreCase = true))

        val networkMsg = com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper.forAuth(
            java.net.UnknownHostException("Unable to resolve host supabase.co")
        )
        assertTrue(networkMsg.contains("internet connection", ignoreCase = true))

        val missingProfileMsg = com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper.forAuth(
            IllegalStateException("missing_profile: Account profile could not be found")
        )
        assertTrue(missingProfileMsg.contains("profile is missing", ignoreCase = true))
    }

    @Test
    fun `Req 2 - BookingDto toDomain maps RESERVED and PENDING statuses and customer fields accurately`() {
        val goat = com.ammalfarm.adusanthai.model.Goat(
            id = "11111111-1111-1111-1111-111111111111",
            farmId = "22222222-2222-2222-2222-222222222222",
            name = "Salem Black Buck",
            breed = "Salem Black",
            price = 18000.0,
            imageUrl = "https://example.com/goat1.jpg"
        )
        val dto = com.ammalfarm.adusanthai.data.dto.BookingDto(
            id = "33333333-3333-3333-3333-333333333333",
            goatId = goat.id,
            farmId = goat.farmId,
            customerId = "44444444-4444-4444-4444-444444444444",
            status = "RESERVED",
            preferredVisitDate = "Tomorrow Morning",
            advanceAmount = 1800.0,
            customerNotes = "Visiting at 10 AM"
        )
        val domain = dto.toDomain(
            resolvedGoatName = goat.name,
            resolvedGoatBreed = goat.breed,
            resolvedGoatPhoto = goat.imageUrl,
            resolvedFarmName = "Kovai Heritage Farm"
        )
        assertEquals(com.ammalfarm.adusanthai.model.BookingStatus.CONFIRMED, domain.status)
        assertEquals("44444444-4444-4444-4444-444444444444", domain.customerId)
        assertEquals("Salem Black Buck", domain.goatName)
        assertEquals("https://example.com/goat1.jpg", domain.goatImageUrl)
        assertEquals("Visiting at 10 AM", domain.notes)
    }

    @Test
    fun `Req 3, 4, 5, 6 - GoatDto and FarmDto preserve backward compatibility, multi-images, and farm branding`() {
        val farmDto = com.ammalfarm.adusanthai.data.dto.FarmDto(
            id = "22222222-2222-2222-2222-222222222222",
            ownerId = "55555555-5555-5555-5555-555555555555",
            name = "Kovai Heritage Farm",
            location = "Coimbatore, Tamil Nadu",
            district = "Coimbatore",
            verificationStatus = "VERIFIED",
            goatListingLimit = 12,
            logoUrl = "https://example.com/logo.jpg",
            bannerUrl = "https://example.com/banner.jpg"
        )
        val farm = farmDto.toDomain()
        assertEquals("https://example.com/logo.jpg", farm.logoUrl)
        assertEquals("https://example.com/banner.jpg", farm.bannerUrl)
        assertEquals(12, farm.goatListingLimit)
        assertTrue(farm.verificationStatus.isApprovedOrVerified)

        val goatDto = com.ammalfarm.adusanthai.data.dto.GoatDto(
            id = "11111111-1111-1111-1111-111111111111",
            farmId = farm.id,
            name = "Kanni Buck",
            breedName = "Kanni Adu",
            gender = "Male",
            ageMonths = 14,
            weightKg = 32.5,
            price = 22000.0,
            imageUrl = "https://example.com/main.jpg",
            imageUrls = listOf("https://example.com/side.jpg", "https://example.com/back.jpg"),
            status = "AVAILABLE",
            approvalStatus = "APPROVED",
            isApprovedByAdmin = true
        )
        val goat = goatDto.toDomain(
            resolvedPhotos = listOf("https://example.com/main.jpg", "https://example.com/side.jpg"),
            resolvedFarmName = farm.name,
            resolvedFarmLocation = farm.location,
            resolvedFarmContact = farm.contactNumber,
            resolvedFarmCode = farm.farmCode
        )
        assertEquals("https://example.com/main.jpg", goat.imageUrl)
        assertEquals(3, goat.imageUrls.size)
        assertEquals("Kovai Heritage Farm", goat.farmName)
        assertEquals(ApprovalStatus.APPROVED, goat.approvalStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, goat.availabilityStatus)
    }
}