package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.repository.AuthRepository
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import com.ammalfarm.adusanthai.ui.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class Stage6ASecureAccountDeletionTest {

    private lateinit var dbEngine: MockRlsDatabaseEngine
    private lateinit var customer: UserProfile
    private lateinit var customer2: UserProfile
    private lateinit var farmAdmin: UserProfile
    private lateinit var superAdmin: UserProfile
    private lateinit var partnerFarm: Farm
    private lateinit var ammalFarm: Farm

    @Before
    fun setUp() {
        dbEngine = MockRlsDatabaseEngine()

        customer = dbEngine.createUser("John Customer", "john@example.com", UserRole.CUSTOMER, "9876543210")
        customer2 = dbEngine.createUser("Jane Customer", "jane@example.com", UserRole.CUSTOMER, "9876543211")
        farmAdmin = dbEngine.createUser("Salem Breeder", "salem@example.com", UserRole.FARM_ADMIN, "9876543212")
        superAdmin = dbEngine.createUser("Madesh Super Admin", "madesh1205@gmail.com", UserRole.SUPER_ADMIN, "6380898358")

        ammalFarm = dbEngine.createFarm(
            farmId = "00000000-0000-0000-0000-000000000001",
            ownerId = superAdmin.id,
            name = "Ammal Farm Central Hub",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = true,
            quota = 10000
        )

        partnerFarm = dbEngine.createFarm(
            farmId = UUID.randomUUID().toString(),
            ownerId = farmAdmin.id,
            name = "Salem Goat Haven",
            status = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false,
            quota = 10
        )
    }

    @Test
    fun testCustomerCanDeleteOwnAccount() {
        // Setup customer data: wishlist, notifications, bookings
        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, partnerFarm.id, "Salem Boer Champion", 25000.0, true)
        dbEngine.addWishlistItem(customer.id, goatId)
        dbEngine.addNotification(customer.id, "Welcome to Ammal Farm!")
        val bookingId = dbEngine.createBooking(goatId, partnerFarm.id, customer.id, 25000.0)

        // Verify profile exists before deletion
        assertNotNull("Profile must exist before deletion", dbEngine.getProfile(customer.id))
        assertEquals(1, dbEngine.getWishlistForUser(customer.id).size)
        assertEquals(1, dbEngine.getNotificationsForUser(customer.id).size)

        // Customer executes account self-deletion
        val success = dbEngine.executeAccountDeletion(callerId = customer.id, targetUserId = customer.id)
        assertTrue("Account deletion must succeed for customer", success)

        // 1. Profile must be permanently deleted
        assertNull("Customer profile must be removed", dbEngine.getProfile(customer.id))

        // 2. Wishlist and notifications must be deleted
        assertTrue("Wishlist must be deleted", dbEngine.getWishlistForUser(customer.id).isEmpty())
        assertTrue("Notifications must be deleted", dbEngine.getNotificationsForUser(customer.id).isEmpty())

        // 3. Booking must remain preserved for accounting/audit, but customer_id and personal notes anonymized
        val preservedBookings = dbEngine.getAllBookings().filter { it.id == bookingId }
        assertEquals("Booking must be retained for transaction records", 1, preservedBookings.size)
        val booking = preservedBookings.first()
        assertNull("Customer ID must be anonymized (null)", booking.customerId)
        assertNotEquals("Booking price must NOT be altered", 0.0, booking.totalPrice)
    }

    @Test
    fun testFarmAdminCanDeleteOwnAccountAndNoOrphanedListings() {
        // Setup farm admin goats and bookings
        val goatId1 = UUID.randomUUID().toString()
        val goatId2 = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId1, partnerFarm.id, "Salem Sirohi 1", 18000.0, true, "AVAILABLE")
        dbEngine.createGoat(goatId2, partnerFarm.id, "Salem Sirohi 2", 20000.0, true, "AVAILABLE")

        // Customer booked goatId1
        val bookingId = dbEngine.createBooking(goatId1, partnerFarm.id, customer.id, 18000.0)

        // Farm admin deletes their account
        val success = dbEngine.executeAccountDeletion(callerId = farmAdmin.id, targetUserId = farmAdmin.id)
        assertTrue("Account deletion must succeed for farm admin", success)

        // 1. Farm admin profile removed
        assertNull("Farm admin profile must be removed", dbEngine.getProfile(farmAdmin.id))

        // 2. Farm status must be SUSPENDED with contact info redacted
        val farm = dbEngine.getFarm(partnerFarm.id)
        assertNotNull("Farm record must remain for historic audit", farm)
        assertEquals(VerificationStatus.SUSPENDED, farm?.verificationStatus)
        assertEquals("REDACTED", farm?.contactNumber)
        assertEquals("", farm?.ownerId)

        // 3. Goats must be deactivated to prevent orphaned listings on public marketplace
        val goat1 = dbEngine.getGoat(goatId1)
        val goat2 = dbEngine.getGoat(goatId2)
        assertEquals("Goat 1 must be deactivated", "INACTIVE", goat1?.status)
        assertEquals("Goat 2 must be deactivated", "INACTIVE", goat2?.status)

        // 4. Pending bookings on this farm must be cancelled
        val b = dbEngine.getAllBookings().first { it.id == bookingId }
        assertEquals("Pending booking must be cancelled on farm closure", "CANCELLED", b.status)
    }

    @Test
    fun testFarmAdminWithHistoricalBookingsCanDeleteAccountSafely() {
        // Setup farm admin with active goat, sold goat, and historical completed/cancelled bookings
        val activeGoatId = UUID.randomUUID().toString()
        val soldGoatId = UUID.randomUUID().toString()
        dbEngine.createGoat(activeGoatId, partnerFarm.id, "Active Breeder", 25000.0, true, "AVAILABLE")
        dbEngine.createGoat(soldGoatId, partnerFarm.id, "Champion Sire", 40000.0, true, "SOLD")

        // Create a completed booking (historical record)
        val completedBookingId = dbEngine.createBooking(soldGoatId, partnerFarm.id, customer.id, 40000.0)
        dbEngine.evaluateBookingUpdate(
            callerId = superAdmin.id,
            bookingId = completedBookingId,
            attemptedPrice = 40000.0,
            attemptedStatus = "COMPLETED"
        )

        // Create a cancelled booking (historical record)
        val cancelledBookingId = dbEngine.createBooking(activeGoatId, partnerFarm.id, customer2.id, 25000.0)
        dbEngine.evaluateBookingUpdate(
            callerId = superAdmin.id,
            bookingId = cancelledBookingId,
            attemptedPrice = 25000.0,
            attemptedStatus = "CANCELLED"
        )

        // Verify pre-deletion state
        assertNotNull("Farm admin profile exists", dbEngine.getProfile(farmAdmin.id))
        assertEquals(2, dbEngine.getAllBookings().filter { it.farmId == partnerFarm.id }.size)

        // Execute FARM_ADMIN account deletion
        val deletionSuccess = dbEngine.executeAccountDeletion(callerId = farmAdmin.id, targetUserId = farmAdmin.id)
        assertTrue("Account deletion must succeed for FARM_ADMIN with transaction history", deletionSuccess)

        // 1. Auth & profile cleanup
        assertNull("Farm Admin profile must be removed", dbEngine.getProfile(farmAdmin.id))

        // 2. Historical bookings MUST be preserved with full pricing and audit integrity
        val completedBooking = dbEngine.getAllBookings().first { it.id == completedBookingId }
        assertEquals("COMPLETED status must be preserved", "COMPLETED", completedBooking.status)
        assertEquals("Historical price must remain intact", 40000.0, completedBooking.totalPrice, 0.001)
        assertEquals("Farm ID foreign key must remain intact", partnerFarm.id, completedBooking.farmId)

        val cancelledBooking = dbEngine.getAllBookings().first { it.id == cancelledBookingId }
        assertEquals("CANCELLED status must be preserved", "CANCELLED", cancelledBooking.status)
        assertEquals("Historical price must remain intact", 25000.0, cancelledBooking.totalPrice, 0.001)

        // 3. Farm is suspended and personal metadata redacted
        val farm = dbEngine.getFarm(partnerFarm.id)
        assertNotNull("Farm record remains for historical transaction foreign key integrity", farm)
        assertEquals(VerificationStatus.SUSPENDED, farm?.verificationStatus)
        assertEquals("REDACTED", farm?.contactNumber)
        assertEquals("", farm?.ownerId)

        // 4. Goats state: active goats become INACTIVE, already SOLD goats remain SOLD
        val activeGoat = dbEngine.getGoat(activeGoatId)
        val soldGoat = dbEngine.getGoat(soldGoatId)
        assertEquals("Active goat becomes INACTIVE", "INACTIVE", activeGoat?.status)
        assertEquals("SOLD goat remains SOLD", "SOLD", soldGoat?.status)

        // 5. Unrelated users/farms/goats unaffected
        assertNotNull("Customer profile intact", dbEngine.getProfile(customer.id))
        assertNotNull("Customer 2 profile intact", dbEngine.getProfile(customer2.id))
        assertNotNull("Super admin intact", dbEngine.getProfile(superAdmin.id))
        assertNotNull("Ammal farm intact", dbEngine.getFarm(ammalFarm.id))
    }

    @Test
    fun testUserCannotDeleteAnotherUserAccount() {
        // Customer 1 tries to delete Customer 2's account
        val deleted = dbEngine.executeAccountDeletion(callerId = customer.id, targetUserId = customer2.id)
        assertFalse("Customer 1 must be forbidden from deleting Customer 2's account", deleted)

        // Verify Customer 2's profile is completely unaffected
        assertNotNull("Customer 2 profile must remain untouched", dbEngine.getProfile(customer2.id))

        // Customer tries to delete Farm Admin's account
        val adminDeleted = dbEngine.executeAccountDeletion(callerId = customer.id, targetUserId = farmAdmin.id)
        assertFalse("Customer must be forbidden from deleting Farm Admin account", adminDeleted)
        assertNotNull("Farm Admin profile must remain untouched", dbEngine.getProfile(farmAdmin.id))
    }

    @Test
    fun testCentralAmmalFarmOwnerCannotBeDeletedWithoutTransfer() {
        // Attempting to delete the central Ammal Farm owner without transfer must be blocked
        try {
            dbEngine.executeAccountDeletion(callerId = superAdmin.id, targetUserId = superAdmin.id)
            fail("Expected exception when deleting Central Ammal Farm owner")
        } catch (e: IllegalStateException) {
            assertTrue("Exception must state Ammal Farm ownership protection", e.message?.contains("Ammal Farm") == true)
        }

        // Ammal Farm must remain intact
        assertNotNull("Super Admin profile must remain intact", dbEngine.getProfile(superAdmin.id))
    }

    @Test
    fun testCompletedTransactionRecordsPreservedForDisputeAndTax() {
        val goatId = UUID.randomUUID().toString()
        dbEngine.createGoat(goatId, partnerFarm.id, "Breeder Buck", 30000.0, true)
        val bookingId = dbEngine.createBooking(goatId, partnerFarm.id, customer.id, 30000.0)

        // Complete the booking
        dbEngine.evaluateBookingUpdate(
            callerId = superAdmin.id,
            bookingId = bookingId,
            attemptedPrice = 30000.0,
            attemptedStatus = "COMPLETED"
        )

        // Customer deletes account
        dbEngine.executeAccountDeletion(callerId = customer.id)

        // Verify booking
        val booking = dbEngine.getAllBookings().first { it.id == bookingId }
        assertEquals("Booking status must remain COMPLETED", "COMPLETED", booking.status)
        assertEquals("Booking price must remain exact for tax & ledger", 30000.0, booking.totalPrice, 0.001)
        assertNull("Customer personal ID must be unlinked/anonymized", booking.customerId)
        assertNull("Customer personal notes must be cleared", booking.customerNotes)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun testViewModelAndRepositorySessionClearing() = runTest {
        val testDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)
        try {
            // Mock AuthRepository to verify ViewModel clears state on account deletion
            val mockRepo = object : AuthRepository {
                var deleteCalled = false
                private val _user = MutableStateFlow<UserProfile?>(customer)
                override val currentUser: StateFlow<UserProfile?> = _user.asStateFlow()
                override val currentFarm: StateFlow<Farm?> = MutableStateFlow(null).asStateFlow()
                override val authState: StateFlow<com.ammalfarm.adusanthai.model.AuthState> =
                    MutableStateFlow(com.ammalfarm.adusanthai.model.AuthState(isAuthenticated = true, userProfile = customer)).asStateFlow()

                override suspend fun checkExistingSession(): Result<UserProfile?> = Result.success(_user.value)
                override suspend fun fetchAndSyncUserProfile(userId: String?): Result<UserProfile> = Result.success(customer)
                override suspend fun login(email: String, password: String): Result<UserProfile> = Result.success(customer)
                override suspend fun registerCustomer(name: String, email: String, password: String, phone: String) = Result.success(customer)
                override suspend fun registerFarmAdmin(name: String, email: String, password: String, phone: String, farmName: String, farmDistrict: String, farmDescription: String) = Result.success(customer)
                override suspend fun resendEmailVerification(email: String) = Result.success(Unit)
                override suspend fun sendPasswordResetOtp(email: String) = Result.success(Unit)
                override suspend fun resetPassword(newPassword: String) = Result.success(Unit)
                override suspend fun logout() { _user.value = null }
                override suspend fun deleteAccount(): Result<Unit> {
                    deleteCalled = true
                    _user.value = null
                    return Result.success(Unit)
                }
                override suspend fun updateProfile(name: String, phone: String) = Result.success(customer)
            }

            val viewModel = AuthViewModel(mockRepo)
            var logoutNavigated = false

            viewModel.deleteAccount {
                logoutNavigated = true
            }

            testScheduler.advanceUntilIdle()

            assertTrue("deleteAccount on repository must be called", mockRepo.deleteCalled)
            assertTrue("onSuccess navigation callback must be triggered", logoutNavigated)
            assertEquals("UI state must reflect success", "Account deleted successfully.", viewModel.uiState.value.successMessage)
        } finally {
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }
}
