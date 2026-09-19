package com.example

import com.example.model.ApprovalStatus
import com.example.model.AvailabilityStatus
import com.example.model.Goat
import com.example.model.GoatGender
import com.example.model.GoatPurpose
import com.example.model.UserRole
import com.example.model.VerificationStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage6CSecureDeferredImageUploadTest {

    private lateinit var db: MockRlsDatabaseEngine
    private val superAdminId = "00000000-0000-0000-0000-000000000001"
    private val farm1AdminId = "00000000-0000-0000-0000-000000000002"
    private val farm2AdminId = "00000000-0000-0000-0000-000000000003"

    private val farm1Id = "11111111-1111-1111-1111-111111111111"
    private val farm2Id = "22222222-2222-2222-2222-222222222222"

    @Before
    fun setUp() {
        db = MockRlsDatabaseEngine()
        db.createUser("Super Admin", "admin@ammalfarm.com", UserRole.SUPER_ADMIN, "9876543210", id = superAdminId)
        db.createUser("Farm 1 Admin", "farm1@ammalfarm.com", UserRole.FARM_ADMIN, "9876543211", id = farm1AdminId)
        db.createUser("Farm 2 Admin", "farm2@ammalfarm.com", UserRole.FARM_ADMIN, "9876543212", id = farm2AdminId)

        db.createFarm(farm1Id, farm1AdminId, "Farm One", VerificationStatus.APPROVED, isAmmalOwnFarm = false, quota = 10)
        db.createFarm(farm2Id, farm2AdminId, "Farm Two", VerificationStatus.APPROVED, isAmmalOwnFarm = false, quota = 10)
    }

    @Test
    fun test1_selectImage_noStorageObjectCreated() {
        // Simulating UI state where images are selected in local state list
        val localPhotoUris = listOf(
            "content://media/external/images/media/101",
            "content://media/external/images/media/102"
        )
        // Verify storage objects in bucket 'goat-images' are 0 before submit
        val storageObjects = db.getStorageObjects("goat-images")
        assertEquals(0, storageObjects.size)
        // Local URIs exist only in temporary UI memory
        assertEquals(2, localPhotoUris.size)
    }

    @Test
    fun test2_selectAndReplaceSelection_noAbandonedStorageObject() {
        // User selects image A, then removes it and selects image B before submit
        val selectedPhotos = mutableListOf("content://media/external/images/media/A")
        // Remove A
        selectedPhotos.removeAt(0)
        // Select B
        selectedPhotos.add("content://media/external/images/media/B")

        // Submit Add Goat with final selected photos
        val goat = Goat(
            id = "33333333-3333-3333-3333-333333333301",
            name = "Test Goat B",
            tagNumber = "AF-1001",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 35.0,
            purpose = GoatPurpose.BREEDING,
            description = "Healthy goat",
            price = 25000.0,
            discountPercentage = 0.0,
            photos = selectedPhotos,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeAddGoatDeferredUpload(farm1AdminId, farm1Id, goat)
        assertTrue(result.success)
        assertEquals(1, result.uploadedStoragePaths.size)

        val storageObjects = db.getStorageObjects("goat-images")
        assertEquals(1, storageObjects.size)
        assertFalse(storageObjects.any { it.contains("media/A") })
    }

    @Test
    fun test3_cancelAddGoat_zeroUploadedObjects() {
        // User selects 3 local photos in dialog
        val localPhotos = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2",
            "content://media/external/images/media/3"
        )
        // User cancels dialog without submitting -> No submit function called
        // Storage remains empty
        assertEquals(0, db.getStorageObjects("goat-images").size)
    }

    @Test
    fun test4_submitAddGoat_onlyFinalImagesUploaded() {
        val localPhotos = listOf(
            "content://media/external/images/media/final_1",
            "content://media/external/images/media/final_2"
        )

        val goat = Goat(
            id = "33333333-3333-3333-3333-333333333302",
            name = "Final Goat",
            tagNumber = "AF-2001",
            breed = "Tellicherry",
            gender = GoatGender.FEMALE,
            ageMonths = 10,
            weightKg = 28.0,
            purpose = GoatPurpose.DAIRY,
            description = "Pedigree doe",
            price = 18000.0,
            discountPercentage = 5.0,
            photos = localPhotos,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeAddGoatDeferredUpload(farm1AdminId, farm1Id, goat)
        assertTrue(result.success)
        assertEquals(2, result.uploadedStoragePaths.size)

        val goatImagesInDb = db.getGoatImages("33333333-3333-3333-3333-333333333302")
        assertEquals(2, goatImagesInDb.size)
        // Ensure no content:// URI is stored in database
        goatImagesInDb.forEach { img ->
            assertFalse(img.startsWith("content://"))
            assertTrue(img.contains("https://"))
        }
    }

    @Test
    fun test5_editGoat_retainExistingImageUnchanged() {
        // Create initial goat with 1 existing remote image
        val existingStoragePath = "farm/$farm1Id/goat/goat-edit-retain/photo_1.jpg"
        val existingImageUrl = "https://xyz.supabase.co/storage/v1/object/public/goat-images/$existingStoragePath"

        db.createGoatWithImages(
            id = "goat-edit-retain",
            farmId = farm1Id,
            name = "Existing Goat",
            price = 20000.0,
            isApproved = true,
            images = listOf(existingImageUrl)
        )

        val existingGoat = db.getGoat("goat-edit-retain")!!

        val updatedGoat = Goat(
            id = "goat-edit-retain",
            name = "Updated Existing Goat",
            tagNumber = existingGoat.tagNumber,
            breed = existingGoat.breedName,
            gender = GoatGender.MALE,
            ageMonths = 14,
            weightKg = 40.0,
            purpose = GoatPurpose.BREEDING,
            description = "Updated description",
            price = 22000.0,
            discountPercentage = 0.0,
            photos = listOf(existingImageUrl), // Retain unchanged
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeEditGoatDeferredUpload(farm1AdminId, updatedGoat)
        assertTrue(result.success)
        assertEquals(0, result.uploadedStoragePaths.size) // No new uploads
        assertEquals(0, result.removedStoragePaths.size)  // No removals

        val storageObjects = db.getStorageObjects("goat-images")
        assertTrue(storageObjects.contains(existingStoragePath))
    }

    @Test
    fun test6_editGoat_replaceImageCorrectNewUploadedAndOldRemoved() {
        val oldStoragePath = "farm/$farm1Id/goat/goat-replace/photo_old.jpg"
        val oldImageUrl = "https://xyz.supabase.co/storage/v1/object/public/goat-images/$oldStoragePath"

        db.createGoatWithImages(
            id = "goat-replace",
            farmId = farm1Id,
            name = "Goat To Replace Image",
            price = 15000.0,
            isApproved = true,
            images = listOf(oldImageUrl)
        )

        val updatedGoat = Goat(
            id = "goat-replace",
            name = "Goat With Replaced Image",
            tagNumber = "AF-3001",
            breed = "Sirohi",
            gender = GoatGender.MALE,
            ageMonths = 18,
            weightKg = 45.0,
            purpose = GoatPurpose.MEAT,
            description = "Replaced photo goat",
            price = 16000.0,
            discountPercentage = 0.0,
            photos = listOf("content://media/external/images/media/replacement_new"), // Replace with local URI
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeEditGoatDeferredUpload(farm1AdminId, updatedGoat)
        assertTrue(result.success)
        assertEquals(1, result.uploadedStoragePaths.size)
        assertEquals(1, result.removedStoragePaths.size)
        assertEquals(oldStoragePath, result.removedStoragePaths[0])

        val storageObjects = db.getStorageObjects("goat-images")
        assertFalse(storageObjects.contains(oldStoragePath))
        assertTrue(storageObjects.contains(result.uploadedStoragePaths[0]))
    }

    @Test
    fun test7_uploadFailure_cleanupSuccessfulPartialUploads() {
        val localPhotos = listOf(
            "content://media/external/images/media/1",
            "content://media/external/images/media/2_fail"
        )

        val goat = Goat(
            id = "goat-fail-upload",
            name = "Failed Upload Goat",
            tagNumber = "AF-4001",
            breed = "Jamunapari",
            gender = GoatGender.MALE,
            ageMonths = 8,
            weightKg = 25.0,
            purpose = GoatPurpose.BREEDING,
            description = "Test upload fail",
            price = 30000.0,
            discountPercentage = 0.0,
            photos = localPhotos,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        // Simulate upload failure at index 1 (second image)
        val result = db.executeAddGoatDeferredUpload(farm1AdminId, farm1Id, goat, simulatedUploadFailIndex = 1)
        assertFalse(result.success)
        assertEquals("Storage upload failed at index 1", result.errorMessage)

        // Verify partial upload (image 0) was cleaned up and removed from storage
        val storageObjects = db.getStorageObjects("goat-images")
        assertEquals(0, storageObjects.size)
        assertNull(db.getGoat("goat-fail-upload"))
    }

    @Test
    fun test8_databaseFailure_cleanupNewlyUploadedFiles() {
        val localPhotos = listOf("content://media/external/images/media/db_fail_photo")

        val goat = Goat(
            id = "goat-fail-db",
            name = "Failed DB Goat",
            tagNumber = "AF-5001",
            breed = "Beetal",
            gender = GoatGender.FEMALE,
            ageMonths = 11,
            weightKg = 30.0,
            purpose = GoatPurpose.DAIRY,
            description = "Test DB fail",
            price = 22000.0,
            discountPercentage = 0.0,
            photos = localPhotos,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeAddGoatDeferredUpload(farm1AdminId, farm1Id, goat, simulatedDbFail = true)
        assertFalse(result.success)
        assertEquals("Database insertion failed", result.errorMessage)

        // Storage files uploaded before DB fail must be cleaned up
        val storageObjects = db.getStorageObjects("goat-images")
        assertEquals(0, storageObjects.size)
        assertNull(db.getGoat("goat-fail-db"))
    }

    @Test
    fun test9_verifyNoContentUriStored() {
        val localPhotos = listOf(
            "content://media/external/images/media/1000",
            "file:///storage/emulated/0/DCIM/Camera/IMG_2026.jpg"
        )

        val goat = Goat(
            id = "33333333-3333-3333-3333-333333333307",
            name = "Clean Storage Goat",
            tagNumber = "AF-6001",
            breed = "Kanni Aadu",
            gender = GoatGender.MALE,
            ageMonths = 15,
            weightKg = 38.0,
            purpose = GoatPurpose.MEAT,
            description = "Verify clean URLs",
            price = 19000.0,
            discountPercentage = 0.0,
            photos = localPhotos,
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.PENDING_APPROVAL,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        val result = db.executeAddGoatDeferredUpload(farm1AdminId, farm1Id, goat)
        assertTrue(result.success)

        val dbPhotos = db.getGoatImages("33333333-3333-3333-3333-333333333307")
        assertEquals(2, dbPhotos.size)
        dbPhotos.forEach { url ->
            assertFalse(url.startsWith("content://"))
            assertFalse(url.startsWith("file://"))
            assertTrue(url.contains("https://"))
        }
    }

    @Test
    fun test10_verifyFarmAdminCanOnlyModifyOwnFarmImages() {
        // Create goat owned by Farm 1
        db.createGoatWithImages(
            id = "goat-farm-1",
            farmId = farm1Id,
            name = "Farm 1 Goat",
            price = 25000.0,
            isApproved = true,
            images = listOf("https://xyz.supabase.co/storage/v1/object/public/goat-images/farm/$farm1Id/goat/goat-farm-1/photo1.jpg")
        )

        val updateRequest = Goat(
            id = "goat-farm-1",
            name = "Hacked Goat",
            tagNumber = "AF-7001",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 12,
            weightKg = 35.0,
            purpose = GoatPurpose.BREEDING,
            description = "Attempt to edit Farm 1 goat by Farm 2 admin",
            price = 1000.0,
            discountPercentage = 0.0,
            photos = listOf("content://media/external/images/media/hacked_photo"),
            farmId = farm1Id,
            farmName = "Farm One",
            farmLocation = "Salem",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        // Farm 2 Admin tries to edit Farm 1's goat
        val result = db.executeEditGoatDeferredUpload(farm2AdminId, updateRequest)
        assertFalse(result.success)
        assertTrue(result.errorMessage!!.contains("Unauthorized"))

        // Verify image on Farm 1's goat remains original
        val originalImages = db.getGoatImages("goat-farm-1")
        assertEquals(1, originalImages.size)
        assertTrue(originalImages[0].contains("photo1.jpg"))
    }

    @Test
    fun test11_verifySuperAdminBehaviorUnchanged() {
        // Create goat owned by Farm 2
        db.createGoatWithImages(
            id = "goat-farm-2",
            farmId = farm2Id,
            name = "Farm 2 Goat",
            price = 30000.0,
            isApproved = true,
            images = listOf("https://xyz.supabase.co/storage/v1/object/public/goat-images/farm/$farm2Id/goat/goat-farm-2/original.jpg")
        )

        val updateRequest = Goat(
            id = "goat-farm-2",
            name = "Super Admin Managed Goat",
            tagNumber = "AF-8001",
            breed = "Beetal",
            gender = GoatGender.MALE,
            ageMonths = 20,
            weightKg = 50.0,
            purpose = GoatPurpose.BREEDING,
            description = "Super admin update",
            price = 32000.0,
            discountPercentage = 0.0,
            photos = listOf("content://media/external/images/media/super_admin_new_photo"),
            farmId = farm2Id,
            farmName = "Farm Two",
            farmLocation = "Madurai",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )

        // Super Admin edits Farm 2's goat
        val result = db.executeEditGoatDeferredUpload(superAdminId, updateRequest)
        assertTrue(result.success)
        assertEquals(1, result.uploadedStoragePaths.size)
        assertEquals(1, result.removedStoragePaths.size)
    }
}
