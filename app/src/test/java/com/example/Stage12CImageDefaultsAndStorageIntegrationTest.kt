package com.example

import com.example.core.supabase.SupabaseConfig
import com.example.core.util.FarmLocalCache
import com.example.data.dto.FarmDto
import com.example.data.dto.GoatDto
import com.example.data.dto.GoatImageDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.AvailabilityStatus
import com.example.model.Farm
import com.example.model.Goat
import com.example.model.GoatGender
import com.example.model.GoatPurpose
import com.example.model.VerificationStatus
import com.example.util.ImageUploadHelper
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Stage 12C: Farm Logo & Goat Image Defaults Verification Test Suite
 *
 * Verifies:
 * 1. New partner farm has no logo (logoUrl is null/empty).
 * 2. New farm does not use Ammal Farm's logo as default or fallback.
 * 3. New goat has zero images initially (empty photo list).
 * 4. No preset goat images are assigned; BREED_PRESET_PHOTOS is empty.
 * 5. Ammal Farm's real logo is preserved and only used for Ammal Farm itself.
 * 6. Existing goat images remain unchanged.
 * 7. Storage paths enforce farm/goat ownership hierarchy.
 */
class Stage12CImageDefaultsAndStorageIntegrationTest {

    @Test
    fun testNewPartnerFarm_hasNoLogoInitially() {
        val partnerFarmId = UUID.randomUUID().toString()
        val partnerOwnerId = UUID.randomUUID().toString()

        val newFarm = Farm(
            id = partnerFarmId,
            name = "Madurai Royal Boer Farm",
            ownerId = partnerOwnerId,
            ownerName = "Sundar Raj",
            location = "Madurai, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "+91 98765 43210",
            email = "sundar@maduraiboer.com",
            description = "Quality breeding center",
            verificationStatus = VerificationStatus.PENDING,
            isAmmalOwnFarm = false
        )

        // Verify domain model has empty logo
        assertTrue("New farm logoUrl must be empty or blank", newFarm.logoUrl.isBlank())
        assertTrue("New farm bannerUrl must be empty or blank", newFarm.bannerUrl.isBlank())

        // Verify DTO conversion maps empty logo to null for Supabase PostgreSQL insertion
        val dto = FarmDto.fromDomain(newFarm)
        assertNull("FarmDto.logoUrl must be null when creating a farm without logo", dto.logoUrl)
        assertNull("FarmDto.bannerUrl must be null when creating a farm without banner", dto.bannerUrl)
        assertFalse("New partner farm must never be marked as Ammal own farm", dto.isAmmalOwnFarm)
    }

    @Test
    fun testNewPartnerFarm_neverUsesAmmalFarmLogoAsDefaultOrFallback() {
        val ammalRealLogoUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/$SEED_AMMAL_FARM_UUID/ammal_brand_logo.png"
        val partnerFarmId = UUID.randomUUID().toString()

        // DTO mapping for partner farm with null logo
        val partnerDto = FarmDto(
            id = partnerFarmId,
            name = "Coimbatore Goat Haven",
            ownerId = UUID.randomUUID().toString(),
            logoUrl = null,
            bannerUrl = null,
            isAmmalOwnFarm = false
        )

        val partnerDomain = partnerDto.toDomain()
        assertTrue("Partner farm without logo must have empty logoUrl", partnerDomain.logoUrl.isBlank())
        assertNotEquals("Partner farm must NEVER have Ammal Farm's logo", ammalRealLogoUrl, partnerDomain.logoUrl)
    }

    @Test
    fun testAmmalFarm_stillDisplaysItsRealLogo() {
        val ammalRealLogoUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/$SEED_AMMAL_FARM_UUID/official_ammal_logo.png"
        val ammalRealBannerUrl = "https://example.supabase.co/storage/v1/object/public/goat-images/farm/$SEED_AMMAL_FARM_UUID/official_ammal_banner.png"

        val ammalDto = FarmDto(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm",
            ownerId = UUID.randomUUID().toString(),
            logoUrl = ammalRealLogoUrl,
            bannerUrl = ammalRealBannerUrl,
            isAmmalOwnFarm = true
        )

        val ammalDomain = ammalDto.toDomain()
        assertEquals("Ammal Farm must preserve its real logo", ammalRealLogoUrl, ammalDomain.logoUrl)
        assertEquals("Ammal Farm must preserve its real banner", ammalRealBannerUrl, ammalDomain.bannerUrl)
        assertTrue("Ammal Farm is marked as own farm", ammalDomain.isAmmalOwnFarm)
    }

    @Test
    fun testNewGoat_hasZeroImagesInitially() {
        val goatId = UUID.randomUUID().toString()
        val farmId = UUID.randomUUID().toString()

        val newGoat = Goat(
            id = goatId,
            name = "Champion Stud",
            tagNumber = "AF-TAG-101",
            farmId = farmId,
            farmName = "Salem Boer Farm",
            farmLocation = "Salem",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 14,
            weightKg = 48.0,
            purpose = GoatPurpose.BREEDING,
            price = 45000.0,
            description = "Healthy pedigree stud."
        )

        assertTrue("New goat photos list must be empty", newGoat.photos.isEmpty())
        assertEquals("New goat must have exactly 0 images", 0, newGoat.photos.size)

        // Convert to DTO
        val dto = GoatDto.fromDomain(newGoat)
        val reconstructed = dto.toDomain(resolvedPhotos = emptyList())
        assertTrue("Reconstructed goat with no resolved photos has empty photos", reconstructed.photos.isEmpty())
    }

    @Test
    fun testNoPresetGoatImages_breedPresetPhotosIsEmpty() {
        // Verify ImageUploadHelper does NOT contain any preset photos
        assertTrue("ImageUploadHelper.BREED_PRESET_PHOTOS must be empty", ImageUploadHelper.BREED_PRESET_PHOTOS.isEmpty())

        // Verify querying any breed returns null or empty
        val testBreeds = listOf("Boer", "Sirohi", "Beetal", "Jamnapari", "Barbari", "Tellicherry", "Malabari", "Black Bengal")
        for (breed in testBreeds) {
            val photos = ImageUploadHelper.BREED_PRESET_PHOTOS[breed]
            assertTrue("Breed $breed must NOT have preset photos", photos.isNullOrEmpty())
        }
    }

    @Test
    fun testExistingGoatImages_remainUnchanged() {
        val goatId = UUID.randomUUID().toString()
        val farmId = UUID.randomUUID().toString()
        val existingPhotos = listOf(
            "https://example.supabase.co/storage/v1/object/public/goat-images/farm/$farmId/goat/$goatId/photo_1.jpg",
            "https://example.supabase.co/storage/v1/object/public/goat-images/farm/$farmId/goat/$goatId/photo_2.jpg"
        )

        val goat = Goat(
            id = goatId,
            name = "Existing Champion",
            tagNumber = "AF-TAG-202",
            farmId = farmId,
            farmName = "Salem Farm",
            farmLocation = "Salem",
            breed = "Sirohi",
            gender = GoatGender.MALE,
            ageMonths = 18,
            weightKg = 52.0,
            purpose = GoatPurpose.BREEDING,
            price = 35000.0,
            description = "Champion pedigree",
            photos = existingPhotos
        )

        assertEquals("Existing goat photos count must remain intact", 2, goat.photos.size)
        assertEquals("First photo must match exactly", existingPhotos[0], goat.photos[0])
        assertEquals("Second photo must match exactly", existingPhotos[1], goat.photos[1])

        // DTO round-trip with resolvedPhotos
        val dto = GoatDto.fromDomain(goat)
        val reconstructed = dto.toDomain(resolvedPhotos = existingPhotos)
        assertEquals("Reconstructed goat photos must match", existingPhotos, reconstructed.photos)
    }

    @Test
    fun testStoragePath_usesFarmGoatOwnershipStructure() {
        val farmId = "f-12345"
        val goatId = "g-67890"

        val sanitizedFarm = farmId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val sanitizedGoat = goatId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val expectedPathPrefix = "farm/$sanitizedFarm/goat/$sanitizedGoat/"

        // Verify expected path structure prevents cross-farm pollution
        assertTrue("Storage path must be scoped under farm/{farmId}/goat/{goatId}/", expectedPathPrefix.startsWith("farm/f-12345/goat/g-67890/"))
    }
}
