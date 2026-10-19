package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.data.repository.SupabaseMarketplaceRepositoryImpl
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.VerificationStatus
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddGoatListingQuotaAndRpcRegressionTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testFarmId = "134be28d-84c9-4030-9ac6-2bbd327357d5"
    private val testFarmOwnerId = "4b0724ee-491c-463a-a4de-4469bd9ef4aa"
    private val otherFarmId = "99999999-84c9-4030-9ac6-2bbd327357d5"
    private val otherFarmOwnerId = "88888888-491c-463a-a4de-4469bd9ef4aa"

    private class FakeRpcMarketplaceRepository(
        initialFarms: List<Farm> = emptyList(),
        initialGoats: List<Goat> = emptyList(),
        var currentAuthUserId: String? = null,
        val remoteFarmsById: MutableMap<String, FarmDto> = mutableMapOf(),
        var simulatedRpcFailure: Exception? = null
    ) : SupabaseMarketplaceRepositoryImpl(
        context = null,
        initialFarms = initialFarms,
        initialGoats = initialGoats
    ) {
        var createRpcCallCount = 0
        var lastCreateRpcPayload: JsonObject? = null
        var updateRemoteCallCount = 0
        var lastUpdatedGoatId: String? = null
        var lastUpdatePayload: JsonObject? = null
        var refreshFarmsCallCount = 0
        var refreshGoatsCallCount = 0

        override val isRemoteBackendEnabled: Boolean
            get() = true

        override suspend fun resolveCurrentAuthUserId(): String? = currentAuthUserId

        override suspend fun fetchRemoteFarmById(farmId: String): FarmDto? {
            return remoteFarmsById[farmId.lowercase()]
        }

        override suspend fun fetchRemoteFarmByOwnerId(ownerId: String): FarmDto? {
            return remoteFarmsById.values.find { it.ownerId.equals(ownerId, ignoreCase = true) }
        }

        override suspend fun executeCreateGoatListingSecureRpc(
            goatPayload: JsonObject,
            fallbackGoatId: String,
            fallbackFarmId: String
        ): GoatDto? {
            createRpcCallCount++
            lastCreateRpcPayload = goatPayload
            simulatedRpcFailure?.let { throw it }

            val farmId = goatPayload["farm_id"]?.jsonPrimitive?.content ?: fallbackFarmId
            val existingDto = remoteFarmsById[farmId.lowercase()]
            if (existingDto != null && !existingDto.isAmmalOwnFarm) {
                remoteFarmsById[farmId.lowercase()] = existingDto.copy(
                    consumedListingSlots = existingDto.consumedListingSlots + 1
                )
            }

            return GoatDto(
                id = goatPayload["id"]?.jsonPrimitive?.content ?: fallbackGoatId,
                goatCode = "GOAT-1001",
                farmId = farmId,
                name = goatPayload["name"]?.jsonPrimitive?.content ?: "",
                breedName = goatPayload["breed_name"]?.jsonPrimitive?.content ?: "",
                gender = goatPayload["gender"]?.jsonPrimitive?.content ?: "MALE",
                ageMonths = goatPayload["age_months"]?.jsonPrimitive?.content?.toIntOrNull() ?: 12,
                weightKg = goatPayload["weight_kg"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 28.0,
                purpose = goatPayload["purpose"]?.jsonPrimitive?.content ?: "BREEDING",
                price = goatPayload["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 18000.0,
                discountPercentage = goatPayload["discount_percentage"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                status = goatPayload["status"]?.jsonPrimitive?.content ?: "AVAILABLE",
                description = goatPayload["description"]?.jsonPrimitive?.content ?: "",
                vaccinationStatus = goatPayload["vaccination_status"]?.jsonPrimitive?.content ?: "Healthy",
                isApprovedByAdmin = goatPayload["is_approved_by_admin"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            )
        }

        override suspend fun executeUpdateGoatListingRemote(
            goatId: String,
            farmId: String,
            payloadWithoutTag: JsonObject,
            payloadWithTag: JsonObject
        ): GoatDto? {
            updateRemoteCallCount++
            lastUpdatedGoatId = goatId
            lastUpdatePayload = payloadWithoutTag
            simulatedRpcFailure?.let { throw it }

            return GoatDto(
                id = goatId,
                goatCode = "GOAT-EDIT",
                farmId = farmId,
                name = payloadWithoutTag["name"]?.jsonPrimitive?.content ?: "",
                breedName = payloadWithoutTag["breed_name"]?.jsonPrimitive?.content ?: "",
                gender = payloadWithoutTag["gender"]?.jsonPrimitive?.content ?: "MALE",
                ageMonths = payloadWithoutTag["age_months"]?.jsonPrimitive?.content?.toIntOrNull() ?: 12,
                weightKg = payloadWithoutTag["weight_kg"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 28.0,
                purpose = payloadWithoutTag["purpose"]?.jsonPrimitive?.content ?: "BREEDING",
                price = payloadWithoutTag["price"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 18000.0,
                discountPercentage = payloadWithoutTag["discount_percentage"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                status = payloadWithoutTag["status"]?.jsonPrimitive?.content ?: "AVAILABLE",
                description = payloadWithoutTag["description"]?.jsonPrimitive?.content ?: "",
                vaccinationStatus = payloadWithoutTag["vaccination_status"]?.jsonPrimitive?.content ?: "Healthy",
                isApprovedByAdmin = false
            )
        }

        override suspend fun syncGoatImagesRemote(
            goatId: String,
            mergedImages: List<String>,
            isExisting: Boolean
        ) {
            // No-op in unit tests
        }

        override suspend fun refreshFarms(): Result<List<Farm>> {
            refreshFarmsCallCount++
            return Result.success(farms.value)
        }

        override suspend fun refreshGoats(): Result<List<Goat>> {
            refreshGoatsCallCount++
            return Result.success(goats.value)
        }
    }

    private fun buildTestFarmDto(
        limit: Int = 7,
        consumed: Int = 0,
        status: String = "APPROVED"
    ) = FarmDto(
        id = testFarmId,
        farmCode = "FARM-0006",
        name = "test",
        ownerId = testFarmOwnerId,
        locationDistrict = "Erode",
        locationState = "Tamil Nadu",
        contactPhone = "9345724968",
        status = status,
        isAmmalOwnFarm = false,
        goatListingLimit = limit,
        consumedListingSlots = consumed
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        FarmLocalCache.invalidate()
    }

    @After
    fun tearDown() {
        FarmLocalCache.invalidate()
        Dispatchers.resetMain()
    }

    @Test
    fun `FarmDto mapping preserves 0 of 7 slots and APPROVED verification status`() {
        val dto = buildTestFarmDto(limit = 7, consumed = 0, status = "APPROVED")
        val domain = dto.toDomain()

        assertEquals(testFarmId, domain.id)
        assertEquals(testFarmOwnerId, domain.ownerId)
        assertEquals(VerificationStatus.APPROVED, domain.verificationStatus)
        assertTrue(domain.verificationStatus.isApprovedOrVerified)
        assertEquals(7, domain.goatListingLimit)
        assertEquals(0, domain.consumedListingSlots)
        assertTrue(domain.hasRemainingListingQuota)
    }

    @Test
    fun `Approved farm with 0 of 7 slots creates goat via create_goat_listing_secure RPC and consumes 1 slot`() = runTest {
        val farmDto = buildTestFarmDto(limit = 7, consumed = 0)
        val farm = farmDto.toDomain()
        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(farm),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(testFarmId.lowercase() to farmDto)
        )
        val viewModel = MarketplaceViewModel(repo)

        val newGoat = Goat(
            name = "Prime Tellicherry Buck",
            breed = "Tellicherry",
            gender = "Male",
            ageMonths = 14,
            weightKg = 32.0,
            price = 21000.0,
            farmId = testFarmId,
            farmName = farm.name
        )

        val result = repo.addOrUpdateGoat(
            goat = newGoat,
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue(
            "Expected addOrUpdateGoat to succeed for approved 0/7 farm (error: ${result.exceptionOrNull()?.message})",
            result.isSuccess
        )
        assertEquals(1, repo.createRpcCallCount)
        assertEquals(0, repo.updateRemoteCallCount)
        assertNotNull(repo.lastCreateRpcPayload)
        assertEquals(testFarmId, repo.lastCreateRpcPayload!!["farm_id"]?.jsonPrimitive?.content)
        assertEquals("Prime Tellicherry Buck", repo.lastCreateRpcPayload!!["name"]?.jsonPrimitive?.content)

        // Exactly 1 slot consumed (0/7 -> 1/7) and state refreshed
        val updatedFarm = repo.farms.value.first { it.id == testFarmId }
        assertEquals(1, updatedFarm.consumedListingSlots)
        assertEquals(7, updatedFarm.goatListingLimit)
        assertTrue(repo.refreshFarmsCallCount >= 1)
        assertTrue(repo.refreshGoatsCallCount >= 1)
    }

    @Test
    fun `Stale local farm cache is refreshed from Supabase before quota check so 0 of 7 farm succeeds`() = runTest {
        // Simulate stale cached farm showing 2/2 consumed, while production server has 0/7
        val staleLocalFarm = buildTestFarmDto(limit = 2, consumed = 2).toDomain()
        FarmLocalCache.saveFarmProfile(staleLocalFarm)

        val liveServerFarmDto = buildTestFarmDto(limit = 7, consumed = 0)
        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(staleLocalFarm),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(testFarmId.lowercase() to liveServerFarmDto)
        )

        val result = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Kangayam Buck",
                breed = "Kanni Adu",
                ageMonths = 12,
                weightKg = 29.0,
                price = 19500.0,
                farmId = testFarmId
            ),
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue("Expected fresh server 0/7 quota to allow listing creation", result.isSuccess)
        assertEquals(1, repo.createRpcCallCount)
        val updatedFarm = repo.farms.value.first { it.id == testFarmId }
        assertEquals(7, updatedFarm.goatListingLimit)
        assertEquals(1, updatedFarm.consumedListingSlots)
    }

    @Test
    fun `Farm at its 7 of 7 limit is rejected without calling RPC or consuming another slot`() = runTest {
        val fullFarmDto = buildTestFarmDto(limit = 7, consumed = 7)
        val fullFarm = fullFarmDto.toDomain()
        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(fullFarm),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(testFarmId.lowercase() to fullFarmDto)
        )

        val result = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Extra Goat",
                breed = "Boer",
                ageMonths = 10,
                weightKg = 26.0,
                price = 17000.0,
                farmId = testFarmId
            ),
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue("Expected failure when farm is at 7/7 limit", result.isFailure)
        assertEquals(0, repo.createRpcCallCount)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("quota has been fully consumed", ignoreCase = true) == true
        )
        assertEquals(7, repo.farms.value.first { it.id == testFarmId }.consumedListingSlots)
    }

    @Test
    fun `Failed insert consumes zero slots and surfaces accurate error`() = runTest {
        val farmDto = buildTestFarmDto(limit = 7, consumed = 0)
        val farm = farmDto.toDomain()
        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(farm),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(testFarmId.lowercase() to farmDto),
            simulatedRpcFailure = IllegalStateException("Only Super Admin can change consumed_listing_slots\nURL: https://wphgctwmjcvrblpybktd.supabase.co/rest/v1/goats")
        )

        val result = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Failed Goat",
                breed = "Tellicherry",
                ageMonths = 12,
                weightKg = 28.0,
                price = 18000.0,
                farmId = testFarmId
            ),
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue(result.isFailure)
        val errMsg = result.exceptionOrNull()?.message.orEmpty()
        assertFalse(
            "Privilege error mentioning consumed_listing_slots must not be misreported as quota full",
            errMsg.contains("quota has been fully consumed", ignoreCase = true)
        )
        assertEquals(0, repo.farms.value.first { it.id == testFarmId }.consumedListingSlots)
        assertTrue(repo.goats.value.isEmpty())
    }

    @Test
    fun `Unauthorized farm ID is rejected and never substituted with another farm ID`() = runTest {
        val ownFarmDto = buildTestFarmDto(limit = 7, consumed = 0)
        val otherFarmDto = FarmDto(
            id = otherFarmId,
            farmCode = "FARM-0099",
            name = "Other Partner Farm",
            ownerId = otherFarmOwnerId,
            status = "APPROVED",
            goatListingLimit = 5,
            consumedListingSlots = 0
        )
        val ammalFarm = Farm(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm",
            ownerId = "00000000-0000-0000-0000-000000000010",
            verificationStatus = VerificationStatus.VERIFIED,
            isAmmalOwnFarm = true
        )

        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(ownFarmDto.toDomain(), otherFarmDto.toDomain(), ammalFarm),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(
                testFarmId.lowercase() to ownFarmDto,
                otherFarmId.lowercase() to otherFarmDto
            )
        )

        // 1. Attempt by FARM_ADMIN to create listing for another partner farm
        val crossFarmAttempt = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Spoofed Farm Goat",
                breed = "Boer",
                ageMonths = 12,
                weightKg = 30.0,
                price = 20000.0,
                farmId = otherFarmId
            ),
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue(crossFarmAttempt.isFailure)
        assertEquals(0, repo.createRpcCallCount)
        assertTrue(
            crossFarmAttempt.exceptionOrNull()?.message?.contains("your own farm", ignoreCase = true) == true
        )
        assertEquals(0, repo.farms.value.first { it.id == testFarmId }.consumedListingSlots)
        assertEquals(0, repo.farms.value.first { it.id == otherFarmId }.consumedListingSlots)

        // 2. Attempt by FARM_ADMIN to create listing for Ammal Farm
        val ammalAttempt = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Spoofed Ammal Goat",
                breed = "Boer",
                ageMonths = 12,
                weightKg = 30.0,
                price = 20000.0,
                farmId = SEED_AMMAL_FARM_UUID
            ),
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId
        )

        assertTrue(ammalAttempt.isFailure)
        assertEquals(0, repo.createRpcCallCount)

        // 3. Super Admin creating listing for Ammal Farm succeeds and is exempt from partner quota
        val superAdminResult = repo.addOrUpdateGoat(
            goat = Goat(
                name = "Official Ammal Goat",
                breed = "Tellicherry",
                ageMonths = 15,
                weightKg = 35.0,
                price = 25000.0,
                farmId = SEED_AMMAL_FARM_UUID
            ),
            isSuperAdmin = true
        )

        assertTrue(superAdminResult.isSuccess)
        assertEquals(1, repo.createRpcCallCount)
        assertEquals(SEED_AMMAL_FARM_UUID, superAdminResult.getOrNull()?.farmId)
        assertEquals(ApprovalStatus.APPROVED, superAdminResult.getOrNull()?.approvalStatus)
    }

    @Test
    fun `Editing an existing goat uses update path and does not consume another listing slot`() = runTest {
        val existingGoatId = "11111111-2222-3333-4444-555555555555"
        val existingGoat = Goat(
            id = existingGoatId,
            name = "Original Buck",
            breed = "Tellicherry",
            gender = "Male",
            ageMonths = 12,
            weightKg = 28.0,
            price = 18000.0,
            farmId = testFarmId,
            farmName = "test",
            approvalStatus = ApprovalStatus.PENDING,
            availabilityStatus = AvailabilityStatus.AVAILABLE
        )
        // Farm is already at 7/7 slots used; editing an existing goat must still succeed and keep 7/7
        val farmDtoAtLimit = buildTestFarmDto(limit = 7, consumed = 7)
        val repo = FakeRpcMarketplaceRepository(
            initialFarms = listOf(farmDtoAtLimit.toDomain()),
            initialGoats = listOf(existingGoat),
            currentAuthUserId = testFarmOwnerId,
            remoteFarmsById = mutableMapOf(testFarmId.lowercase() to farmDtoAtLimit)
        )

        val editedGoat = existingGoat.copy(
            name = "Updated Champion Buck",
            price = 22500.0,
            weightKg = 31.5
        )

        val editResult = repo.addOrUpdateGoat(
            goat = editedGoat,
            isSuperAdmin = false,
            actorUserId = testFarmOwnerId,
            actorFarmId = testFarmId
        )

        assertTrue("Editing existing goat must succeed even when farm is at 7/7 slots", editResult.isSuccess)
        assertEquals(0, repo.createRpcCallCount)
        assertEquals(1, repo.updateRemoteCallCount)
        assertEquals(existingGoatId, repo.lastUpdatedGoatId)
        assertEquals("Updated Champion Buck", editResult.getOrNull()?.name)
        assertEquals(testFarmId, editResult.getOrNull()?.farmId)
        assertEquals(7, repo.farms.value.first { it.id == testFarmId }.consumedListingSlots)
    }

    @Test
    fun `UserFriendlyErrorMapper distinguishes real quota exhaustion from non-quota errors`() {
        val realQuotaError = Exception(
            "Goat listing quota has been fully consumed (7/7). Contact Super Admin (+91 63808 98358) to purchase additional listing slots.\nURL: https://wphgctwmjcvrblpybktd.supabase.co/rest/v1/rpc/create_goat_listing_secure"
        )
        assertEquals(
            "Your goat listing quota has been fully consumed. Contact Super Admin (+91 63808 98358) to purchase additional listing slots.",
            UserFriendlyErrorMapper.forGoatListing(realQuotaError)
        )

        val nonQuotaTriggerError = Exception(
            "Only Super Admin can change goat_listing_limit or consumed_listing_slots\nURL: https://wphgctwmjcvrblpybktd.supabase.co/rest/v1/goats"
        )
        assertEquals(
            "You do not have permission to add or modify this goat listing.",
            UserFriendlyErrorMapper.forGoatListing(nonQuotaTriggerError)
        )
    }
}
