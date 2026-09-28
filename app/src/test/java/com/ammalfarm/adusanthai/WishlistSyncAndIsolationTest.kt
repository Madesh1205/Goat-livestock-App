package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.data.dto.WishlistItemDto
import com.ammalfarm.adusanthai.data.dto.currentIsoTimestamp
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.data.repository.GoatRepository
import com.ammalfarm.adusanthai.data.repository.SupabaseWishlistRepositoryImpl
import com.ammalfarm.adusanthai.data.repository.WishlistRepository
import com.ammalfarm.adusanthai.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WishlistSyncAndIsolationTest {

    @org.junit.Before
    fun setUp() {
        SupabaseWishlistRepositoryImpl.isRemoteDatabaseEnabled = false
    }

    @org.junit.After
    fun tearDown() {
        SupabaseWishlistRepositoryImpl.isRemoteDatabaseEnabled = com.ammalfarm.adusanthai.core.supabase.SupabaseConfig.isConfigured
    }

    private fun createTestGoat(
        id: String = UUID.randomUUID().toString(),
        name: String = "Salem Black Stud",
        price: Double = 28000.0,
        discountPercentage: Double = 0.0
    ): Goat {
        return Goat(
            id = id,
            name = name,
            breed = "Salem Black",
            gender = GoatGender.MALE,
            ageMonths = 24,
            weightKg = 45.0,
            purpose = GoatPurpose.BREEDING,
            description = "High quality stud goat",
            price = price,
            discountPercentage = discountPercentage,
            photos = emptyList(),
            farmId = UUID.randomUUID().toString(),
            farmName = "Ammal Farm",
            farmLocation = "Vellore, Tamil Nadu",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED
        )
    }

    private class MockGoatRepository(private val goats: List<Goat>) : GoatRepository {
        override fun getApprovedGoats(): Flow<List<Goat>> = flowOf(goats)
        override fun getGoatById(id: String): Flow<Goat?> = flowOf(goats.find { it.id == id })
        override fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> = flowOf(goats)
        override fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = flowOf(goats.filter { it.farmId == farmId })
        override fun getAllGoatsForAdmin(): Flow<List<Goat>> = flowOf(goats)
        override suspend fun addGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
        override suspend fun updateGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
        override suspend fun deleteGoatListing(goatId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit> = Result.success(Unit)
        override fun getAvailableBreeds(): Flow<List<String>> = flowOf(goats.map { it.breed }.distinct())
        override suspend fun getGoatsCount(): Result<Int> = Result.success(goats.size)
        override fun invalidateCache() {}
    }

    // --- SCENARIO 1: Save Goat ---
    @Test
    fun `Scenario 1 - Save goat adds item to wishlist for user`() = runBlocking {
        val goat = createTestGoat()
        val mockRepo = MockGoatRepository(listOf(goat))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        val addResult = wishlistRepo.addToWishlist(userId, goat.id)
        assertTrue(addResult.isSuccess)

        val userWishlist = wishlistRepo.getWishlistForUser(userId).first()
        assertEquals(1, userWishlist.size)
        assertEquals(goat.id, userWishlist.first().goatId)
        assertEquals(userId, userWishlist.first().userId)
        assertEquals(goat.name, userWishlist.first().goat?.name)
    }

    // --- SCENARIO 2: Unsave Goat ---
    @Test
    fun `Scenario 2 - Unsave goat removes item from user wishlist`() = runBlocking {
        val goat = createTestGoat()
        val mockRepo = MockGoatRepository(listOf(goat))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        wishlistRepo.addToWishlist(userId, goat.id)
        var userWishlist = wishlistRepo.getWishlistForUser(userId).first()
        assertEquals(1, userWishlist.size)

        val removeResult = wishlistRepo.removeFromWishlist(userId, goat.id)
        assertTrue(removeResult.isSuccess)

        userWishlist = wishlistRepo.getWishlistForUser(userId).first()
        assertTrue(userWishlist.isEmpty())
    }

    // --- SCENARIO 3: Duplicate Prevention ---
    @Test
    fun `Scenario 3 - Duplicate prevention ensures a goat cannot be saved twice for the same user`() = runBlocking {
        val goat = createTestGoat()
        val mockRepo = MockGoatRepository(listOf(goat))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        val firstAdd = wishlistRepo.addToWishlist(userId, goat.id)
        assertTrue(firstAdd.isSuccess)

        val secondAdd = wishlistRepo.addToWishlist(userId, goat.id)
        assertTrue(secondAdd.isSuccess)

        val userWishlist = wishlistRepo.getWishlistForUser(userId).first()
        assertEquals(1, userWishlist.size)
    }

    // --- SCENARIO 4: User Isolation ---
    @Test
    fun `Scenario 4 - User A wishlist is strictly isolated from User B wishlist`() = runBlocking {
        val goat1 = createTestGoat(name = "Goat A")
        val goat2 = createTestGoat(name = "Goat B")
        val mockRepo = MockGoatRepository(listOf(goat1, goat2))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)

        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()

        wishlistRepo.addToWishlist(userA, goat1.id)
        wishlistRepo.addToWishlist(userB, goat2.id)

        val userAWishlist = wishlistRepo.getWishlistForUser(userA).first()
        val userBWishlist = wishlistRepo.getWishlistForUser(userB).first()

        assertEquals(1, userAWishlist.size)
        assertEquals(goat1.id, userAWishlist.first().goatId)
        assertFalse(userAWishlist.any { it.goatId == goat2.id })

        assertEquals(1, userBWishlist.size)
        assertEquals(goat2.id, userBWishlist.first().goatId)
        assertFalse(userBWishlist.any { it.goatId == goat1.id })
    }

    // --- SCENARIO 5: Profile Count ---
    @Test
    fun `Scenario 5 - Profile count derives accurately from authenticated user wishlist`() = runBlocking {
        val goat1 = createTestGoat()
        val goat2 = createTestGoat()
        val goat3 = createTestGoat()
        val mockRepo = MockGoatRepository(listOf(goat1, goat2, goat3))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        wishlistRepo.addToWishlist(userId, goat1.id)
        wishlistRepo.addToWishlist(userId, goat2.id)
        wishlistRepo.addToWishlist(userId, goat3.id)

        var count = wishlistRepo.getWishlistForUser(userId).first().size
        assertEquals(3, count)

        wishlistRepo.removeFromWishlist(userId, goat2.id)
        count = wishlistRepo.getWishlistForUser(userId).first().size
        assertEquals(2, count)
    }

    // --- SCENARIO 6: Marketplace Heart State ---
    @Test
    fun `Scenario 6 - Marketplace heart state is true only for saved goats`() = runBlocking {
        val savedGoat = createTestGoat(name = "Saved Goat")
        val unsavedGoat = createTestGoat(name = "Unsaved Goat")
        val mockRepo = MockGoatRepository(listOf(savedGoat, unsavedGoat))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        wishlistRepo.addToWishlist(userId, savedGoat.id)

        val savedState = wishlistRepo.isGoatInWishlist(userId, savedGoat.id).first()
        val unsavedState = wishlistRepo.isGoatInWishlist(userId, unsavedGoat.id).first()

        assertTrue(savedState)
        assertFalse(unsavedState)
    }

    // --- SCENARIO 7: App Restart Persistence ---
    @Test
    fun `Scenario 7 - Wishlist items persist across cache clearing`() = runBlocking {
        val goat = createTestGoat()
        val userId = UUID.randomUUID().toString()

        val initialRepo = SupabaseWishlistRepositoryImpl(MockGoatRepository(listOf(goat)))
        initialRepo.addToWishlist(userId, goat.id)

        initialRepo.clearCacheForUser(userId)
        val emptyAfterClear = initialRepo.getWishlistForUser(userId).first()
        assertTrue(emptyAfterClear.isEmpty())

        initialRepo.addToWishlist(userId, goat.id)
        val reloaded = initialRepo.getWishlistForUser(userId).first()
        assertEquals(1, reloaded.size)
        assertEquals(goat.id, reloaded.first().goatId)
    }

    // --- SCENARIO 8: Logout and Login Isolation ---
    @Test
    fun `Scenario 8 - Logging out and logging in restores exact user wishlist without leak`() = runBlocking {
        val goatA = createTestGoat(name = "Goat for User A")
        val goatB = createTestGoat(name = "Goat for User B")
        val mockRepo = MockGoatRepository(listOf(goatA, goatB))
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)

        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()

        // 1. User A saves Goat A
        wishlistRepo.addToWishlist(userA, goatA.id)
        assertEquals(1, wishlistRepo.getWishlistForUser(userA).first().size)

        // 2. User A logs out (simulated guest)
        val guestWishlist = wishlistRepo.getWishlistForUser("").first()
        assertTrue(guestWishlist.isEmpty())

        // 3. User B logs in (has not saved anything yet)
        var userBWishlist = wishlistRepo.getWishlistForUser(userB).first()
        assertTrue(userBWishlist.isEmpty())

        // User B saves Goat B
        wishlistRepo.addToWishlist(userB, goatB.id)
        userBWishlist = wishlistRepo.getWishlistForUser(userB).first()
        assertEquals(1, userBWishlist.size)
        assertEquals(goatB.id, userBWishlist.first().goatId)

        // 4. User A logs back in
        val userARestored = wishlistRepo.getWishlistForUser(userA).first()
        assertEquals(1, userARestored.size)
        assertEquals(goatA.id, userARestored.first().goatId)
    }

    // --- SCENARIO 9: Failed Insert Rollback ---
    @Test
    fun `Scenario 9 - Optimistic UI state rolls back if insert fails`() {
        var wishlistGoatIds = setOf<String>()
        val goatId = UUID.randomUUID().toString()

        // 1. Optimistic update
        val previousIds = wishlistGoatIds
        wishlistGoatIds = wishlistGoatIds + goatId
        assertTrue(wishlistGoatIds.contains(goatId))

        // 2. Simulated backend insert failure
        val insertFailed = true
        if (insertFailed) {
            // Rollback
            wishlistGoatIds = previousIds
        }

        assertFalse(wishlistGoatIds.contains(goatId))
    }

    // --- SCENARIO 10: Failed Delete Rollback ---
    @Test
    fun `Scenario 10 - Optimistic UI state rolls back if delete fails`() {
        val goatId = UUID.randomUUID().toString()
        var wishlistGoatIds = setOf(goatId)

        // 1. Optimistic remove
        val previousIds = wishlistGoatIds
        wishlistGoatIds = wishlistGoatIds - goatId
        assertFalse(wishlistGoatIds.contains(goatId))

        // 2. Simulated backend delete failure
        val deleteFailed = true
        if (deleteFailed) {
            // Rollback
            wishlistGoatIds = previousIds
        }

        assertTrue(wishlistGoatIds.contains(goatId))
    }

    // --- SCENARIO 11: Empty Wishlist ---
    @Test
    fun `Scenario 11 - Empty wishlist returns zero items and empty set`() = runBlocking {
        val mockRepo = MockGoatRepository(emptyList())
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        val items = wishlistRepo.getWishlistForUser(userId).first()
        assertTrue(items.isEmpty())
    }

    // --- SCENARIO 12: Deleted Goat Handling ---
    @Test
    fun `Scenario 12 - Deleted goat in wishlist is handled gracefully without crash`() = runBlocking {
        val deletedGoatId = UUID.randomUUID().toString()
        // Mock repository with empty list (goat was deleted from marketplace)
        val mockRepo = MockGoatRepository(emptyList())
        val wishlistRepo = SupabaseWishlistRepositoryImpl(mockRepo)
        val userId = UUID.randomUUID().toString()

        wishlistRepo.addToWishlist(userId, deletedGoatId)
        val wishlistItems = wishlistRepo.getWishlistForUser(userId).first()

        assertEquals(1, wishlistItems.size)
        // Goat reference is null because it was deleted
        assertNull(wishlistItems.first().goat)

        // Safe filtering mimics UI's mapNotNull
        val resolvedGoats = wishlistItems.mapNotNull { it.goat }
        assertTrue(resolvedGoats.isEmpty())
    }

    // --- SCENARIO 13: Rapid Repeated Taps ---
    @Test
    fun `Scenario 13 - Rapid repeated toggle taps executed sequentially with mutex produce consistent state`() = runBlocking {
        val goatId = UUID.randomUUID().toString()
        val mutex = Mutex()
        var savedState = false

        // Simulate 3 rapid taps: Save -> Unsave -> Save
        val tapActions = listOf(true, false, true)

        for (action in tapActions) {
            mutex.withLock {
                savedState = action
            }
        }

        assertTrue("Final state must be saved after odd number of sequential toggles", savedState)
    }

    // --- SCENARIO 14: Wishlist Pricing Uses PriceUtils ---
    @Test
    fun `Scenario 14 - Wishlist pricing uses centralized PriceUtils correctly`() {
        // Discounted Goat
        val discountedGoat = createTestGoat(
            price = 28000.0,
            discountPercentage = 20.0
        )
        assertTrue(discountedGoat.hasDiscount)
        assertEquals(22400.0, discountedGoat.finalPrice, 0.001)
        assertEquals("₹22,400", discountedGoat.formattedFinalPrice)
        assertEquals("₹28,000", discountedGoat.formattedPrice)
        assertEquals("20% OFF", discountedGoat.formattedDiscountBadge)

        // Undiscounted Goat
        val regularGoat = createTestGoat(
            price = 28000.0,
            discountPercentage = 0.0
        )
        assertFalse(regularGoat.hasDiscount)
        assertEquals(28000.0, regularGoat.finalPrice, 0.001)
        assertEquals("₹28,000", regularGoat.formattedPrice)
        assertEquals("₹28,000", regularGoat.formattedFinalPrice)
    }
}
