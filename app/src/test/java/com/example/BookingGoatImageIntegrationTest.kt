package com.example

import com.example.core.supabase.SupabaseConfig
import com.example.core.util.GoatImageResolver
import com.example.data.dto.BookingDto
import com.example.data.dto.GoatDto
import com.example.data.dto.GoatImageDto
import com.example.model.AvailabilityStatus
import com.example.model.Booking
import com.example.model.Goat
import com.example.model.GoatGender
import com.example.model.GoatPurpose
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Unit & Integration test suite verifying that goat images are properly resolved
 * and populated in My Livestock Bookings and Booking Details, reusing the existing
 * Marketplace image loading logic and Supabase Storage data without presets/fake URLs.
 */
class BookingGoatImageIntegrationTest {

    @Before
    fun setUp() {
        // Clear or prepare state
    }

    @Test
    fun testBookingDtoToDomain_withResolvedGoatPhoto_populatesGoatPhoto() {
        val goatId = UUID.randomUUID().toString()
        val realStoragePhotoUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/f1/goat/$goatId/photo_1.jpg"

        val dto = BookingDto(
            id = "booking-101",
            goatId = goatId,
            farmId = "farm-1",
            customerId = "cust-1",
            status = "PENDING",
            totalPrice = 25000.0,
            depositPaid = 0.0,
            customerNotes = "Ready for inspection",
            bookingDate = "2026-09-15T10:00:00Z",
            holdExpiresAt = "2026-09-17T10:00:00Z"
        )

        val domain = dto.toDomain(
            resolvedGoatName = "Salem Champion Stud",
            resolvedGoatBreed = "Sirohi",
            resolvedGoatPhoto = realStoragePhotoUrl,
            resolvedFarmName = "Salem Royal Farm",
            resolvedCustomerName = "Arun Kumar",
            resolvedCustomerPhone = "+91 98400 12345"
        )

        assertEquals("booking-101", domain.id)
        assertEquals(goatId, domain.goatId)
        assertEquals("Salem Champion Stud", domain.goatName)
        assertEquals("Sirohi", domain.goatBreed)
        assertEquals("Goat photo must match the resolved storage photo URL", realStoragePhotoUrl, domain.goatPhoto)
        assertFalse("Goat photo must not be blank", domain.goatPhoto.isBlank())
    }

    @Test
    fun testBookingDtoToDomain_whenGoatHasNoImage_remainsEmptyForPlaceholder() {
        val goatId = UUID.randomUUID().toString()

        val dto = BookingDto(
            id = "booking-102",
            goatId = goatId,
            farmId = "farm-1",
            customerId = "cust-1",
            status = "PENDING",
            totalPrice = 18000.0
        )

        val domain = dto.toDomain(
            resolvedGoatName = "Young Kid",
            resolvedGoatBreed = "Tellicherry",
            resolvedGoatPhoto = null, // Genuinely no image
            resolvedFarmName = "Green Hills Farm"
        )

        assertTrue("When goat has no image, goatPhoto must remain blank so generic paw placeholder is shown", domain.goatPhoto.isBlank())
    }

    @Test
    fun testGoatImageResolver_cacheAndRetrieve() {
        val goatId = UUID.randomUUID().toString()
        val photoUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/f-abc/goat/$goatId/headshot.png"

        assertNull(GoatImageResolver.getCachedPhoto(goatId))

        GoatImageResolver.cachePhoto(goatId, photoUrl)
        assertEquals(photoUrl, GoatImageResolver.getCachedPhoto(goatId))

        val map = mapOf(
            "goat-x" to listOf("https://example.supabase.co/x.jpg", "https://example.supabase.co/y.jpg"),
            "goat-y" to listOf("https://example.supabase.co/z.jpg")
        )
        GoatImageResolver.cachePhotos(map)
        assertEquals("https://example.supabase.co/x.jpg", GoatImageResolver.getCachedPhoto("goat-x"))
        assertEquals("https://example.supabase.co/z.jpg", GoatImageResolver.getCachedPhoto("goat-y"))
    }

    @Test
    fun testGoatImageResolver_resolveStorageUrlPreservesBucketAndPath() {
        val relativePath = "farm/f-123/goat/g-456/main.jpg"
        val resolved = SupabaseConfig.resolveStorageUrl(relativePath, SupabaseConfig.BUCKET_GOAT_IMAGES)

        assertTrue("Resolved URL must contain Supabase storage public endpoint", resolved.contains("/storage/v1/object/public/goat-images/"))
        assertTrue("Resolved URL must end with relative path", resolved.endsWith(relativePath))
        assertFalse("Resolved URL must not contain duplicate slashes", resolved.contains("//storage"))
    }

    @Test
    fun testGoatImageResolver_fullUrlsArePreserved() {
        val fullHttpUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/f1/goat/g1/photo.jpg"
        val resolved = SupabaseConfig.resolveStorageUrl(fullHttpUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
        assertEquals("Full HTTP URL must be preserved without modification", fullHttpUrl, resolved)
    }

    @Test
    fun testGoatImageResolver_noPresetOrFakeUrls() {
        val blankResult = SupabaseConfig.resolveStorageUrl("", SupabaseConfig.BUCKET_GOAT_IMAGES)
        assertEquals("Blank input must return empty string, never a preset", "", blankResult)

        val nullResult = SupabaseConfig.resolveStorageUrl(null, SupabaseConfig.BUCKET_GOAT_IMAGES)
        assertEquals("Null input must return empty string, never a preset", "", nullResult)
    }
}
