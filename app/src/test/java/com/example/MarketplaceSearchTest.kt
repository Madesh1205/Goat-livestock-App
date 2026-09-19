package com.example

import com.example.data.repository.MarketplaceRepository
import com.example.model.*
import com.example.ui.viewmodel.MarketplaceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MarketplaceSearchTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var marketplaceRepository: MockMarketplaceSearchRepository
    private lateinit var viewModel: MarketplaceViewModel

    private val approvedBoerSultan = Goat(
        id = "goat-approved-1",
        name = "Sultan",
        tagNumber = "AF-001",
        breed = "Boer",
        gender = GoatGender.MALE,
        ageMonths = 24,
        weightKg = 65.0,
        purpose = GoatPurpose.BREEDING,
        description = "Prized champion stud buck from Salem breeding line",
        price = 25000.0,
        farmId = "farm-1",
        farmName = "Sunrise Agro Farm",
        farmLocation = "Salem, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus = ApprovalStatus.APPROVED
    )

    private val approvedTellicherryRaja = Goat(
        id = "goat-approved-2",
        name = "Raja",
        tagNumber = "AF-002",
        breed = "Tellicherry",
        gender = GoatGender.MALE,
        ageMonths = 18,
        weightKg = 45.0,
        purpose = GoatPurpose.MEAT,
        description = "Healthy young Tellicherry male",
        price = 14000.0,
        farmId = "farm-2",
        farmName = "Salem Goat Sanctuary",
        farmLocation = "Salem, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus = ApprovalStatus.APPROVED
    )

    private val approvedBarbariDaisy = Goat(
        id = "goat-approved-3",
        name = "Daisy",
        tagNumber = "AF-003",
        breed = "Barbari",
        gender = GoatGender.FEMALE,
        ageMonths = 14,
        weightKg = 32.0,
        purpose = GoatPurpose.DAIRY,
        description = "High yield dairy doe",
        price = 16000.0,
        farmId = "farm-3",
        farmName = "Ammal Farm",
        farmLocation = "Madurai, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus = ApprovalStatus.APPROVED
    )

    private val unapprovedBoerPending = Goat(
        id = "goat-unapproved-1",
        name = "Pending Sultan Junior",
        tagNumber = "AF-004",
        breed = "Boer",
        gender = GoatGender.MALE,
        ageMonths = 6,
        weightKg = 20.0,
        purpose = GoatPurpose.BREEDING,
        description = "Unapproved draft listing",
        price = 10000.0,
        farmId = "farm-1",
        farmName = "Sunrise Agro Farm",
        farmLocation = "Salem, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus = ApprovalStatus.PENDING_APPROVAL
    )

    private val rejectedGoat = Goat(
        id = "goat-rejected-1",
        name = "Rejected Boer",
        tagNumber = "AF-005",
        breed = "Boer",
        gender = GoatGender.MALE,
        ageMonths = 12,
        weightKg = 30.0,
        purpose = GoatPurpose.BREEDING,
        description = "Listing rejected by admin",
        price = 8000.0,
        farmId = "farm-1",
        farmName = "Sunrise Agro Farm",
        farmLocation = "Salem, Tamil Nadu",
        availabilityStatus = AvailabilityStatus.AVAILABLE,
        approvalStatus = ApprovalStatus.REJECTED
    )

    private val approvedFarm1 = Farm(
        id = "farm-1",
        name = "Sunrise Agro Farm",
        ownerId = "owner-1",
        ownerName = "Ravi Kumar",
        location = "Salem, Tamil Nadu",
        contactNumber = "9876543210",
        email = "sunrise@example.com",
        description = "Specialists in champion Boer breeding stock",
        verificationStatus = VerificationStatus.APPROVED
    )

    private val approvedFarm2 = Farm(
        id = "farm-2",
        name = "Salem Goat Sanctuary",
        ownerId = "owner-2",
        ownerName = "Priya Dharshini",
        location = "Salem, Tamil Nadu",
        contactNumber = "9876543211",
        email = "salemgoats@example.com",
        description = "Heritage breeds and meat goat farm",
        verificationStatus = VerificationStatus.APPROVED
    )

    private val approvedFarm3 = Farm(
        id = "farm-3",
        name = "Ammal Farm",
        ownerId = "owner-3",
        ownerName = "Madesh K",
        location = "Madurai, Tamil Nadu",
        contactNumber = "9876543212",
        email = "ammalfarm@example.com",
        description = "Premium dairy and show goat breeding",
        verificationStatus = VerificationStatus.APPROVED
    )

    private val pendingFarm = Farm(
        id = "farm-pending",
        name = "Unverified Salem Farm",
        ownerId = "owner-4",
        ownerName = "Karthik Raja",
        location = "Salem, Tamil Nadu",
        contactNumber = "9876543213",
        email = "pending@example.com",
        description = "Pending verification farm",
        verificationStatus = VerificationStatus.PENDING
    )

    private val rejectedFarm = Farm(
        id = "farm-rejected",
        name = "Rejected Agro Farm",
        ownerId = "owner-5",
        ownerName = "Suresh Raina",
        location = "Coimbatore, Tamil Nadu",
        contactNumber = "9876543214",
        email = "rejected@example.com",
        description = "Rejected private farm",
        verificationStatus = VerificationStatus.REJECTED
    )

    private class MockMarketplaceSearchRepository(
        val initialGoats: List<Goat> = emptyList(),
        val initialFarms: List<Farm> = emptyList()
    ) : MarketplaceRepository {
        val goatList = CopyOnWriteArrayList(initialGoats)
        val farmList = CopyOnWriteArrayList(initialFarms)
        private val currentUserFlow = MutableStateFlow<UserProfile?>(null)

        override fun getCurrentUser(): Flow<UserProfile?> = currentUserFlow.asStateFlow()
        override suspend fun registerUser(name: String, email: String, phone: String, role: UserRole, farmName: String?): Result<UserProfile> =
            Result.success(UserProfile("u1", email, name, phone, role))
        override suspend fun login(email: String): Result<UserProfile> =
            Result.success(UserProfile("u1", email, "Test User", role = UserRole.CUSTOMER))
        override suspend fun logout() {}

        override fun getApprovedGoats(): Flow<List<Goat>> =
            flowOf(goatList.filter { it.approvalStatus == ApprovalStatus.APPROVED && it.availabilityStatus == AvailabilityStatus.AVAILABLE })

        override fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> {
            val approvedGoats = goatList.filter { it.approvalStatus == ApprovalStatus.APPROVED }
            val filtered = approvedGoats.filter { criteria.matches(it) }
            return flowOf(filtered)
        }

        override fun getGoatById(id: String): Flow<Goat?> = flowOf(goatList.find { it.id == id })
        override fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = flowOf(goatList.filter { it.farmId == farmId })
        override fun getAllGoatsForAdmin(): Flow<List<Goat>> = flowOf(goatList)
        override fun getAvailableBreeds(): Flow<List<String>> = flowOf(goatList.map { it.breed }.filter { it.isNotBlank() }.distinct().sorted())

        override suspend fun addGoatListing(goat: Goat): Result<Goat> {
            goatList.add(goat)
            return Result.success(goat)
        }

        override suspend fun updateGoatListing(goat: Goat): Result<Goat> {
            val idx = goatList.indexOfFirst { it.id == goat.id }
            if (idx >= 0) goatList[idx] = goat
            return Result.success(goat)
        }

        override suspend fun deleteGoatListing(goatId: String): Result<Unit> {
            goatList.removeIf { it.id == goatId }
            return Result.success(Unit)
        }

        override suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit> {
            val idx = goatList.indexOfFirst { it.id == goatId }
            if (idx >= 0) goatList[idx] = goatList[idx].copy(approvalStatus = status)
            return Result.success(Unit)
        }

        override fun invalidateCache() {}

        override fun getCustomerBookings(customerId: String): Flow<List<Booking>> = flowOf(emptyList())
        override fun getFarmBookings(farmId: String): Flow<List<Booking>> = flowOf(emptyList())
        override fun getAllBookings(): Flow<List<Booking>> = flowOf(emptyList())
        override suspend fun createBooking(goatId: String, notes: String): Result<Booking> = Result.failure(NotImplementedError())
        override suspend fun updateBookingStatus(bookingId: String, status: AvailabilityStatus): Result<Unit> = Result.success(Unit)

        override fun getAllFarms(): Flow<List<Farm>> = flowOf(farmList)
        override fun getFarmById(farmId: String): Flow<Farm?> = flowOf(null)
        override suspend fun registerFarm(farm: Farm): Result<Farm> = Result.success(farm)
        override suspend fun updateFarmProfile(farm: Farm): Result<Farm> = Result.success(farm)
        override suspend fun updateFarmLogo(farmId: String, logoUrl: String): Result<String> = Result.success(logoUrl)
        override suspend fun updateFarmVerification(farmId: String, status: VerificationStatus): Result<Unit> = Result.success(Unit)
        override suspend fun updateFarmListingLimit(farmId: String, limit: Int): Result<Unit> = Result.success(Unit)

        override fun getAllReports(): Flow<List<PlatformReport>> = flowOf(emptyList())
        override suspend fun submitReport(report: PlatformReport): Result<PlatformReport> = Result.success(report)
        override suspend fun updateReportStatus(reportId: String, status: ReportStatus, resolutionNotes: String?): Result<Unit> = Result.success(Unit)
        override suspend fun resolveReportWithAction(reportId: String, removeListingId: String?, suspendFarmId: String?, resolutionNotes: String): Result<Unit> = Result.success(Unit)

        override fun getAllCustomers(): Flow<List<UserProfile>> = flowOf(emptyList())
        override suspend fun updateUserSuspension(userId: String, isSuspended: Boolean): Result<Unit> = Result.success(Unit)

        override suspend fun suspendGoatListing(goatId: String): Result<Unit> = Result.success(Unit)
        override suspend fun restoreGoatListing(goatId: String): Result<Unit> = Result.success(Unit)

        override fun getNotificationsForUser(userId: String): Flow<List<AppNotification>> = flowOf(emptyList())
        override fun getNotificationsForRole(role: UserRole): Flow<List<AppNotification>> = flowOf(emptyList())
        override fun getAllNotifications(): Flow<List<AppNotification>> = flowOf(emptyList())
        override suspend fun sendNotification(notification: AppNotification): Result<Unit> = Result.success(Unit)
        override suspend fun markNotificationAsRead(notificationId: String): Result<Unit> = Result.success(Unit)
        override suspend fun markAllNotificationsAsRead(userId: String, role: UserRole): Result<Unit> = Result.success(Unit)
        override suspend fun deleteNotification(notificationId: String): Result<Unit> = Result.success(Unit)
        override suspend fun clearAllNotifications(userId: String, role: UserRole): Result<Unit> = Result.success(Unit)
        override suspend fun triggerSampleNotification(type: NotificationType): Result<Unit> = Result.success(Unit)

        override fun getWishlistForUser(userId: String): Flow<List<WishlistItem>> = flowOf(emptyList())
        override fun isGoatInWishlist(userId: String, goatId: String): Flow<Boolean> = flowOf(false)
        override suspend fun addToWishlist(userId: String, goatId: String): Result<WishlistItem> = Result.failure(NotImplementedError())
        override suspend fun removeFromWishlist(userId: String, goatId: String): Result<Unit> = Result.success(Unit)
        override suspend fun toggleWishlist(userId: String, goatId: String): Result<Boolean> = Result.success(true)

        override fun getListingPaymentsForFarm(farmId: String): Flow<List<ListingPayment>> = flowOf(emptyList())
        override fun getAllListingPayments(): Flow<List<ListingPayment>> = flowOf(emptyList())
        override suspend fun initiateListingPayment(goatId: String): Result<ListingPaymentInitiation> = Result.failure(NotImplementedError())
        override suspend fun verifyListingPayment(goatId: String, orderId: String, paymentId: String, signature: String, amount: Double): Result<PaymentVerificationResult> = Result.failure(NotImplementedError())
        override suspend fun recordPaymentFailure(goatId: String, orderId: String?, error: String): Result<Unit> = Result.success(Unit)
        override suspend fun recordPaymentCancellation(goatId: String, orderId: String?): Result<Unit> = Result.success(Unit)

        override fun getPlatformStats(): Flow<PlatformStats> = flowOf(PlatformStats())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        marketplaceRepository = MockMarketplaceSearchRepository(
            initialGoats = listOf(
                approvedBoerSultan,
                approvedTellicherryRaja,
                approvedBarbariDaisy,
                unapprovedBoerPending,
                rejectedGoat
            ),
            initialFarms = listOf(
                approvedFarm1,
                approvedFarm2,
                approvedFarm3,
                pendingFarm,
                rejectedFarm
            )
        )

        viewModel = MarketplaceViewModel(marketplaceRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSearchByGoatName_returnsMatchingApprovedGoat() = runTest(testDispatcher) {
        val criteria = GoatFilterCriteria(searchQuery = "Sultan")
        val results = marketplaceRepository.searchAndFilterGoats(criteria).first()

        assertEquals(1, results.size)
        assertEquals("Sultan", results[0].name)
        assertEquals("Boer", results[0].breed)
        assertEquals(ApprovalStatus.APPROVED, results[0].approvalStatus)
    }

    @Test
    fun testSearchByBreed_returnsAllMatchingBreedGoats() = runTest(testDispatcher) {
        val criteria = GoatFilterCriteria(searchQuery = "Tellicherry")
        val results = marketplaceRepository.searchAndFilterGoats(criteria).first()

        assertEquals(1, results.size)
        assertEquals("Raja", results[0].name)
        assertEquals("Tellicherry", results[0].breed)
    }

    @Test
    fun testSearchByFarmName_returnsGoatsFromThatFarm() = runTest(testDispatcher) {
        val criteria = GoatFilterCriteria(searchQuery = "Salem Goat Sanctuary")
        val results = marketplaceRepository.searchAndFilterGoats(criteria).first()

        assertEquals(1, results.size)
        assertEquals("Salem Goat Sanctuary", results[0].farmName)
        assertEquals("Raja", results[0].name)
    }

    @Test
    fun testSearch_filtersOnlyApprovedListings() = runTest(testDispatcher) {
        // Search query "Boer" matches Sultan (approved), Pending Sultan Junior (pending), Rejected Boer (rejected)
        val criteria = GoatFilterCriteria(searchQuery = "Boer")
        val results = marketplaceRepository.searchAndFilterGoats(criteria).first()

        assertEquals(1, results.size)
        assertEquals("Sultan", results[0].name)
        assertEquals(ApprovalStatus.APPROVED, results[0].approvalStatus)
    }

    @Test
    fun testSearchAndBreedFilter_workTogetherCorrectly() = runTest(testDispatcher) {
        // Filter by breed "Boer" and search by farm location/name "Salem"
        val criteria = GoatFilterCriteria(
            breed = "Boer",
            searchQuery = "Sunrise"
        )
        val results = marketplaceRepository.searchAndFilterGoats(criteria).first()

        assertEquals(1, results.size)
        assertEquals("Sultan", results[0].name)
        assertEquals("Boer", results[0].breed)
        assertEquals("Sunrise Agro Farm", results[0].farmName)

        // Filter by breed "Barbari" and search "Sunrise" -> should yield 0 results
        val noMatchCriteria = GoatFilterCriteria(
            breed = "Barbari",
            searchQuery = "Sunrise"
        )
        val noMatchResults = marketplaceRepository.searchAndFilterGoats(noMatchCriteria).first()
        assertTrue(noMatchResults.isEmpty())
    }

    @Test
    fun testFarmSearch_onlyApprovedFarmsAreAvailableInApprovedFarms() = runTest(testDispatcher) {
        advanceUntilIdle()
        val approvedFarms = viewModel.uiState.value.approvedFarms

        // 3 approved farms, pending and rejected are excluded
        assertEquals(3, approvedFarms.size)
        assertTrue(approvedFarms.all { it.verificationStatus == VerificationStatus.APPROVED })
        assertFalse(approvedFarms.any { it.id == "farm-pending" || it.id == "farm-rejected" })
    }

    @Test
    fun testFarmSearch_filterApprovedFarmsByName() = runTest(testDispatcher) {
        advanceUntilIdle()
        val approved = viewModel.uiState.value.approvedFarms
        val query = "Sunrise"
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        val matchedFarms = approved.filter { farm ->
            terms.all { term ->
                farm.name.lowercase().contains(term) ||
                farm.location.lowercase().contains(term) ||
                farm.description.lowercase().contains(term)
            }
        }

        assertEquals(1, matchedFarms.size)
        assertEquals("Sunrise Agro Farm", matchedFarms[0].name)
        assertEquals(VerificationStatus.APPROVED, matchedFarms[0].verificationStatus)
    }

    @Test
    fun testFarmSearch_filterApprovedFarmsByLocation() = runTest(testDispatcher) {
        advanceUntilIdle()
        val approved = viewModel.uiState.value.approvedFarms
        val query = "Salem"
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        val matchedFarms = approved.filter { farm ->
            terms.all { term ->
                farm.name.lowercase().contains(term) ||
                farm.location.lowercase().contains(term) ||
                farm.description.lowercase().contains(term)
            }
        }

        // Both Sunrise Agro Farm and Salem Goat Sanctuary are in Salem and APPROVED. Unverified Salem Farm is excluded!
        assertEquals(2, matchedFarms.size)
        assertTrue(matchedFarms.any { it.name == "Sunrise Agro Farm" })
        assertTrue(matchedFarms.any { it.name == "Salem Goat Sanctuary" })
        assertFalse(matchedFarms.any { it.name == "Unverified Salem Farm" })
    }

    @Test
    fun testFarmSearch_noUnapprovedOrPrivateFarmsReturned() = runTest(testDispatcher) {
        advanceUntilIdle()
        val approved = viewModel.uiState.value.approvedFarms
        val query = "Rejected"
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

        val matchedFarms = approved.filter { farm ->
            terms.all { term ->
                farm.name.lowercase().contains(term) ||
                farm.location.lowercase().contains(term) ||
                farm.description.lowercase().contains(term)
            }
        }

        // Rejected Agro Farm should NEVER match because it's not approved
        assertTrue(matchedFarms.isEmpty())
    }

    @Test
    fun testViewModelSearchStateUpdatesSynchronouslyAndEmitsFilteredGoats() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.setSearchQuery("Daisy")
        // Check synchronous UI state synchronization
        assertEquals("Daisy", viewModel.uiState.value.filterCriteria.searchQuery)

        // Advance debounce time to let flow execute
        advanceTimeBy(300L)
        advanceUntilIdle()

        val goats = viewModel.uiState.value.goats
        assertEquals(1, goats.size)
        assertEquals("Daisy", goats[0].name)
        assertEquals("Barbari", goats[0].breed)
        assertEquals("Ammal Farm", goats[0].farmName)
    }

    @Test
    fun testViewModelClearSearchRestoresAllApprovedGoats() = runTest(testDispatcher) {
        advanceUntilIdle()
        viewModel.setSearchQuery("Daisy")
        advanceTimeBy(300L)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.goats.size)

        viewModel.removeSearchFilter()
        assertEquals("", viewModel.uiState.value.filterCriteria.searchQuery)
        advanceTimeBy(300L)
        advanceUntilIdle()

        // All 3 approved goats restored
        assertEquals(3, viewModel.uiState.value.goats.size)
    }
}

