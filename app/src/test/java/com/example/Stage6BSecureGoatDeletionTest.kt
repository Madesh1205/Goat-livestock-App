package com.example

import com.example.model.UserRole
import com.example.model.VerificationStatus
import com.example.util.ImageUploadHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * STAGE 6B: SECURE GOAT DELETION AND STORAGE IMAGE CLEANUP TEST SUITE
 *
 * Verifies:
 * 1. Goat deletion with multiple images removes all corresponding storage files.
 * 2. Goat deletion with no images succeeds cleanly.
 * 3. Owning Farm Admin can delete their own goat listing.
 * 4. Farm Admin CANNOT delete another farm's goat listing (SecurityException).
 * 5. Customer CANNOT delete any goat listing.
 * 6. Super Admin CAN delete permitted goats across farms.
 * 7. Active reservation/booking safety: Deletion is strictly blocked if active bookings exist.
 * 8. Historical booking preservation: Completed/cancelled bookings are preserved with financial/customer details intact.
 * 9. Storage safety: Farm logos, farm banners, and another goat's images are NEVER deleted.
 * 10. Database atomicity: Associated goat_images and wishlist items are removed, leaving no orphaned records.
 * 11. Storage cleanup queue: Records each storage file deletion for retryability and atomicity.
 * 12. ImageUploadHelper: Protected path validation properly blocks deletion of logo and banner assets.
 */
class Stage6BSecureGoatDeletionTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine

    @Before
    fun setUp() {
        dbEngine = MockRlsDatabaseEngine()
    }

    @Test
    fun `test delete goat with multiple images removes all corresponding storage files`() {
        // Setup Farm and Admin
        val farmAdmin = dbEngine.createUser("Senguttuvan Admin", "admin@ammalfarm.com", UserRole.FARM_ADMIN, "+91 63808 98358")
        val farm = dbEngine.createFarm(
            farmId = UUID.randomUUID().toString(),
            ownerId = farmAdmin.id,
            name = "Ammal Farm",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = true,
            quota = 50
        )

        val goatId = UUID.randomUUID().toString()
        val image1 = "farm/${farm.id}/goat/$goatId/photo_1.jpg"
        val image2 = "farm/${farm.id}/goat/$goatId/photo_2.jpg"
        val image3 = "https://mock.supabase.co/storage/v1/object/public/goat-images/farm/${farm.id}/goat/$goatId/photo_3.jpg"

        dbEngine.createGoatWithImages(
            id = goatId,
            farmId = farm.id,
            name = "Sirohi Stud Male",
            price = 28000.0,
            isApproved = true,
            images = listOf(image1, image2, image3)
        )

        // Verify storage files exist before deletion
        val storageBefore = dbEngine.getStorageObjects("goat-images")
        assertTrue(storageBefore.contains("farm/${farm.id}/goat/$goatId/photo_1.jpg"))
        assertTrue(storageBefore.contains("farm/${farm.id}/goat/$goatId/photo_2.jpg"))
        assertTrue(storageBefore.contains("farm/${farm.id}/goat/$goatId/photo_3.jpg"))
        assertEquals(3, dbEngine.getGoatImages(goatId).size)

        // Execute deletion by owning Farm Admin
        val result = dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
        assertTrue(result.success)
        assertEquals(3, result.deletedImages.size)

        // Verify goat record removed from database
        assertNull(dbEngine.getGoat(goatId))

        // Verify goat_images database records are completely cleaned up (no orphaned records)
        assertTrue(dbEngine.getGoatImages(goatId).isEmpty())

        // Verify all 3 corresponding storage files were removed
        val storageAfter = dbEngine.getStorageObjects("goat-images")
        assertFalse(storageAfter.contains("farm/${farm.id}/goat/$goatId/photo_1.jpg"))
        assertFalse(storageAfter.contains("farm/${farm.id}/goat/$goatId/photo_2.jpg"))
        assertFalse(storageAfter.contains("farm/${farm.id}/goat/$goatId/photo_3.jpg"))
    }

    @Test
    fun `test delete goat with no images succeeds cleanly`() {
        val farmAdmin = dbEngine.createUser("Farm Admin A", "admina@farm.com", UserRole.FARM_ADMIN, "+91 99999 11111")
        val farm = dbEngine.createFarm(
            farmId = UUID.randomUUID().toString(),
            ownerId = farmAdmin.id,
            name = "Cauvery Goats",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false,
            quota = 20
        )

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(
            id = goatId,
            farmId = farm.id,
            name = "No Image Goat",
            price = 15000.0,
            isApproved = true,
            images = emptyList()
        )

        val result = dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
        assertTrue(result.success)
        assertEquals(0, result.deletedImages.size)
        assertNull(dbEngine.getGoat(goatId))
        assertTrue(dbEngine.getGoatImages(goatId).isEmpty())
    }

    @Test
    fun `test farm admin can delete only their own goat`() {
        val farmAdmin = dbEngine.createUser("Owner Admin", "owner@farm.com", UserRole.FARM_ADMIN, "+91 98765 43210")
        val farm = dbEngine.createFarm(
            farmId = UUID.randomUUID().toString(),
            ownerId = farmAdmin.id,
            name = "Salem Breeders",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false,
            quota = 10
        )

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(
            id = goatId,
            farmId = farm.id,
            name = "Salem Black Stud",
            price = 22000.0,
            isApproved = true,
            images = listOf("farm/${farm.id}/goat/$goatId/pic1.jpg")
        )

        val result = dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
        assertTrue(result.success)
        assertNull(dbEngine.getGoat(goatId))
    }

    @Test
    fun `test farm admin cannot delete another farms goat`() {
        val farmAdmin1 = dbEngine.createUser("Admin 1", "admin1@farm.com", UserRole.FARM_ADMIN, "+91 98765 00001")
        val farmAdmin2 = dbEngine.createUser("Admin 2", "admin2@farm.com", UserRole.FARM_ADMIN, "+91 98765 00002")

        val farm1 = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin1.id, "Farm 1", VerificationStatus.APPROVED, false, 10)
        val farm2 = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin2.id, "Farm 2", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(
            id = goatId,
            farmId = farm1.id,
            name = "Farm 1 Champion",
            price = 35000.0,
            isApproved = true,
            images = listOf("farm/${farm1.id}/goat/$goatId/champion.jpg")
        )

        try {
            // Farm Admin 2 tries to delete Farm 1's goat
            dbEngine.executeGoatDeletion(callerId = farmAdmin2.id, goatId = goatId)
            fail("Expected SecurityException when Farm Admin attempts to delete another farm's goat")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("another farm's goat"))
        }

        // Goat and images must still exist intact
        assertNotNull(dbEngine.getGoat(goatId))
        assertEquals(1, dbEngine.getGoatImages(goatId).size)
    }

    @Test
    fun `test customer cannot delete any goat listing`() {
        val customer = dbEngine.createUser("Customer User", "cust@test.com", UserRole.CUSTOMER, "+91 98765 11111")
        val farmAdmin = dbEngine.createUser("Farm Admin", "admin@farm.com", UserRole.FARM_ADMIN, "+91 98765 22222")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "Farm X", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(goatId, farm.id, "Test Goat", 12000.0, true, emptyList())

        try {
            dbEngine.executeGoatDeletion(callerId = customer.id, goatId = goatId)
            fail("Expected SecurityException when Customer attempts to delete goat")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("Customers cannot delete goat listings"))
        }
        assertNotNull(dbEngine.getGoat(goatId))
    }

    @Test
    fun `test super admin can delete permitted goats across farms`() {
        val superAdmin = dbEngine.createUser("Platform Super Admin", "super@ammal.com", UserRole.SUPER_ADMIN, "+91 63808 98358")
        val farmAdmin = dbEngine.createUser("Third Party Admin", "thirdparty@farm.com", UserRole.FARM_ADMIN, "+91 99999 88888")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "Third Party Farm", VerificationStatus.APPROVED, false, 15)

        val goatId = UUID.randomUUID().toString()
        val imagePath = "farm/${farm.id}/goat/$goatId/photo_mod.jpg"
        dbEngine.createGoatWithImages(
            id = goatId,
            farmId = farm.id,
            name = "Flagged Listing",
            price = 10000.0,
            isApproved = true,
            images = listOf(imagePath)
        )

        // Super Admin deletes the listing
        val result = dbEngine.executeGoatDeletion(callerId = superAdmin.id, goatId = goatId)
        assertTrue(result.success)
        assertNull(dbEngine.getGoat(goatId))
        assertFalse(dbEngine.getStorageObjects("goat-images").contains(imagePath))
    }

    @Test
    fun `test booking safety blocks deletion when active reservations exist`() {
        val farmAdmin = dbEngine.createUser("Admin Safe", "admin@safe.com", UserRole.FARM_ADMIN, "+91 98765 33333")
        val customer = dbEngine.createUser("Buyer One", "buyer@test.com", UserRole.CUSTOMER, "+91 98765 44444")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "Safe Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(goatId, farm.id, "Reserved Goat", 20000.0, true, listOf("farm/${farm.id}/goat/$goatId/1.jpg"), status = "RESERVED")

        // Create an active reservation
        val bookingId = dbEngine.createBookingWithStatus(
            goatId = goatId,
            farmId = farm.id,
            customerId = customer.id,
            price = 20000.0,
            status = "RESERVED"
        )

        try {
            dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
            fail("Expected IllegalStateException when attempting to delete goat with active reservation")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("active reservations or bookings"))
        }

        // Goat, booking, and image must all be intact
        assertNotNull(dbEngine.getGoat(goatId))
        assertNotNull(dbEngine.getBooking(bookingId))
        assertEquals(1, dbEngine.getGoatImages(goatId).size)
    }

    @Test
    fun `test booking history remains intact with financial and audit records preserved`() {
        val farmAdmin = dbEngine.createUser("Admin History", "history@farm.com", UserRole.FARM_ADMIN, "+91 98765 55555")
        val customer = dbEngine.createUser("Past Buyer", "pastbuyer@test.com", UserRole.CUSTOMER, "+91 98765 66666")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "History Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoatWithImages(goatId, farm.id, "Archived Champion", 32000.0, true, listOf("farm/${farm.id}/goat/$goatId/archive.jpg"), status = "SOLD")

        // Create a historical COMPLETED booking
        val bookingId = dbEngine.createBookingWithStatus(
            goatId = goatId,
            farmId = farm.id,
            customerId = customer.id,
            price = 32000.0,
            status = "COMPLETED"
        )

        // Deletion should succeed because booking is historical (not active)
        val result = dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
        assertTrue(result.success)
        assertEquals(1, result.historicalBookingsPreserved)

        // Goat is deleted
        assertNull(dbEngine.getGoat(goatId))

        // Booking record is strictly preserved!
        val preservedBooking = dbEngine.getBooking(bookingId)
        assertNotNull(preservedBooking)
        assertEquals(customer.id, preservedBooking!!.customerId)
        assertEquals(farm.id, preservedBooking.farmId)
        assertEquals(32000.0, preservedBooking.totalPrice, 0.001)
        assertEquals("COMPLETED", preservedBooking.status)
        // Foreign key reference unlinked safely (does not point to non-existent goat ID)
        assertEquals("", preservedBooking.goatId)
        assertTrue(preservedBooking.customerNotes!!.contains("Historical listing"))
    }

    @Test
    fun `test storage safety never deletes farm logos banners or other goats images`() {
        val farmAdmin = dbEngine.createUser("Brand Admin", "brand@farm.com", UserRole.FARM_ADMIN, "+91 98765 77777")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "Branded Farm", VerificationStatus.APPROVED, false, 20)

        val goat1Id = UUID.randomUUID().toString()
        val goat2Id = UUID.randomUUID().toString()

        val goat1Image = "farm/${farm.id}/goat/$goat1Id/photo.jpg"
        val goat2Image = "farm/${farm.id}/goat/$goat2Id/photo.jpg"
        val farmLogo = "farm/${farm.id}/farm_logo_2026.png"
        val farmBanner = "farm/${farm.id}/farm_banner_hero.jpg"

        // Put farm logo and banner into goat-images bucket as well
        dbEngine.addStorageObject("goat-images", farmLogo)
        dbEngine.addStorageObject("goat-images", farmBanner)

        dbEngine.createGoatWithImages(goat1Id, farm.id, "Goat 1", 10000.0, true, listOf(goat1Image))
        dbEngine.createGoatWithImages(goat2Id, farm.id, "Goat 2", 15000.0, true, listOf(goat2Image))

        // Also test ImageUploadHelper.isProtectedStoragePath
        assertTrue(ImageUploadHelper.isProtectedStoragePath(farmLogo))
        assertTrue(ImageUploadHelper.isProtectedStoragePath(farmBanner))
        assertTrue(ImageUploadHelper.isProtectedStoragePath("farm/${farm.id}/logo/current.png"))
        assertTrue(ImageUploadHelper.isProtectedStoragePath("farm/${farm.id}/banner/header.jpg"))
        assertFalse(ImageUploadHelper.isProtectedStoragePath(goat1Image))
        assertFalse(ImageUploadHelper.isProtectedStoragePath(goat2Image))

        // Delete Goat 1
        dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goat1Id)

        val storageRemaining = dbEngine.getStorageObjects("goat-images")
        // Goat 1 image is deleted
        assertFalse(storageRemaining.contains(goat1Image))

        // Farm logo, farm banner, and Goat 2 image MUST still exist!
        assertTrue(storageRemaining.contains(farmLogo))
        assertTrue(storageRemaining.contains(farmBanner))
        assertTrue(storageRemaining.contains(goat2Image))
    }

    @Test
    fun `test atomicity and storage cleanup queue tracking`() {
        val farmAdmin = dbEngine.createUser("Queue Admin", "queue@farm.com", UserRole.FARM_ADMIN, "+91 98765 88888")
        val farm = dbEngine.createFarm(UUID.randomUUID().toString(), farmAdmin.id, "Queue Farm", VerificationStatus.APPROVED, false, 10)

        val goatId = UUID.randomUUID().toString()
        val img1 = "farm/${farm.id}/goat/$goatId/img1.jpg"
        val img2 = "farm/${farm.id}/goat/$goatId/img2.jpg"

        dbEngine.createGoatWithImages(goatId, farm.id, "Queue Goat", 18000.0, true, listOf(img1, img2))

        val customer = dbEngine.createUser("Wishlist User", "w@test.com", UserRole.CUSTOMER, "+91 99999 00000")
        dbEngine.addToWishlist(customer.id, goatId)
        assertTrue(dbEngine.getWishlist(customer.id).contains(goatId))

        // Execute deletion
        val result = dbEngine.executeGoatDeletion(callerId = farmAdmin.id, goatId = goatId)
        assertTrue(result.success)

        // Wishlist record removed
        assertFalse(dbEngine.getWishlist(customer.id).contains(goatId))

        // Queue records exist and are marked COMPLETED
        val queue = dbEngine.getStorageCleanupQueue().filter { it.goatId == goatId }
        assertEquals(2, queue.size)
        assertTrue(queue.all { it.status == "COMPLETED" })
    }
}
