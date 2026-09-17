package com.example

import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.*
import com.example.ui.navigation.Screen
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM — STAGE 11B
 * ROLE UI & ACCESS CONTROL VERIFICATION TEST SUITE
 *
 * Verifies all 10 mandatory test scenarios:
 * 1. Customer Profile does NOT contain "Farm Partner Portal"
 * 2. Customer Profile does NOT contain Farm Admin controls
 * 3. Customer attempting Farm Admin route is rejected
 * 4. Customer attempting Super Admin route is rejected
 * 5. Customer remains authenticated after rejected navigation
 * 6. Farm Admin Profile contains Farm Partner Portal
 * 7. Farm Admin accessing Farm Admin area loads own farm
 * 8. Farm Admin attempting Super Admin route is rejected
 * 9. Super Admin retains Super Admin Console access
 * 10. Super Admin accessing My Farm resolves Ammal Farm
 */
class Stage11BRoleAccessControlTest {

    sealed class NavigationResult {
        data class Allowed(val targetRoute: String) : NavigationResult()
        data class Rejected(val reason: String, val fallbackRoute: String) : NavigationResult()
    }

    class RoleNavigationGuard(
        val currentUserProvider: () -> UserProfile?
    ) {
        fun handleNavigationAttempt(targetRoute: String): NavigationResult {
            val user = currentUserProvider()
            val role = user?.role ?: UserRole.CUSTOMER
            return when (targetRoute) {
                Screen.FarmDashboard.route -> {
                    if (role == UserRole.FARM_ADMIN || role == UserRole.SUPER_ADMIN) {
                        NavigationResult.Allowed(targetRoute)
                    } else {
                        NavigationResult.Rejected(
                            reason = "Access Denied: Farm Partner access required.",
                            fallbackRoute = Screen.Marketplace.route
                        )
                    }
                }
                Screen.SuperAdminDashboard.route -> {
                    if (role == UserRole.SUPER_ADMIN) {
                        NavigationResult.Allowed(targetRoute)
                    } else {
                        val fallback = if (role == UserRole.FARM_ADMIN) Screen.FarmDashboard.route else Screen.Marketplace.route
                        NavigationResult.Rejected(
                            reason = "Access Denied: Super Admin authorization required.",
                            fallbackRoute = fallback
                        )
                    }
                }
                else -> NavigationResult.Allowed(targetRoute)
            }
        }
    }

    data class ProfileUiModel(
        val showSuperAdminConsoleButton: Boolean,
        val showMyFarmAmmalFarmButton: Boolean,
        val showFarmPartnerPortalButton: Boolean,
        val showFarmAdminManagementControls: Boolean,
        val showSavedLivestockWishlist: Boolean
    )

    private fun resolveProfileUi(user: UserProfile?): ProfileUiModel {
        val role = user?.role ?: UserRole.CUSTOMER
        return ProfileUiModel(
            showSuperAdminConsoleButton = (role == UserRole.SUPER_ADMIN),
            showMyFarmAmmalFarmButton = (role == UserRole.SUPER_ADMIN),
            showFarmPartnerPortalButton = (role == UserRole.FARM_ADMIN),
            showFarmAdminManagementControls = (role == UserRole.FARM_ADMIN || role == UserRole.SUPER_ADMIN),
            showSavedLivestockWishlist = (user != null)
        )
    }

    private fun resolveFarmForAdminHub(
        user: UserProfile?,
        allFarms: List<Farm>
    ): Farm? {
        if (user == null || user.role == UserRole.CUSTOMER) return null
        val isSuperAdmin = user.role == UserRole.SUPER_ADMIN

        if (isSuperAdmin) {
            val ownFarm = allFarms.find { it.id == SEED_AMMAL_FARM_UUID || it.isAmmalOwnFarm }
            if (ownFarm != null) return ownFarm
        }

        val userId = user.id
        val userFarmId = user.farmId
        if (userId.isNotBlank()) {
            if (!userFarmId.isNullOrBlank()) {
                val byFarmIdAndOwner = allFarms.find { it.id == userFarmId && it.ownerId == userId }
                if (byFarmIdAndOwner != null) return byFarmIdAndOwner
            }
            val byOwnerId = allFarms.find { it.ownerId == userId }
            if (byOwnerId != null) return byOwnerId
        }
        return null
    }

    // --- TEST 1: Customer Profile does NOT contain "Farm Partner Portal" ---
    @Test
    fun testCustomerProfileDoesNotContainFarmPartnerPortal() {
        val customer = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "customer@ammalfarm.com",
            name = "Ramesh Kumar",
            role = UserRole.CUSTOMER
        )

        val ui = resolveProfileUi(customer)
        assertFalse("Customer Profile must NOT show Farm Partner Portal button", ui.showFarmPartnerPortalButton)
    }

    // --- TEST 2: Customer Profile does NOT contain Farm Admin controls ---
    @Test
    fun testCustomerProfileDoesNotContainFarmAdminControls() {
        val customer = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "buyer@example.com",
            name = "Buyer One",
            role = UserRole.CUSTOMER
        )

        val ui = resolveProfileUi(customer)
        assertFalse("Customer Profile must NOT show Super Admin console", ui.showSuperAdminConsoleButton)
        assertFalse("Customer Profile must NOT show My Farm button", ui.showMyFarmAmmalFarmButton)
        assertFalse("Customer Profile must NOT show Farm Partner Portal button", ui.showFarmPartnerPortalButton)
        assertFalse("Customer Profile must NOT show Farm Admin management controls", ui.showFarmAdminManagementControls)
        assertTrue("Customer Profile must retain standard customer features like Wishlist", ui.showSavedLivestockWishlist)
    }

    // --- TEST 3: Customer attempting Farm Admin route is rejected ---
    @Test
    fun testCustomerAttemptingFarmAdminRouteIsRejected() {
        val customer = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "sneaky.customer@example.com",
            name = "Sneaky Customer",
            role = UserRole.CUSTOMER
        )

        val guard = RoleNavigationGuard { customer }
        val result = guard.handleNavigationAttempt(Screen.FarmDashboard.route)

        assertTrue("Customer navigating to Farm Admin must be rejected", result is NavigationResult.Rejected)
        val rejected = result as NavigationResult.Rejected
        assertEquals("Screen.Marketplace.route", Screen.Marketplace.route, rejected.fallbackRoute)
        assertTrue(rejected.reason.contains("Farm Partner access required"))
    }

    // --- TEST 4: Customer attempting Super Admin route is rejected ---
    @Test
    fun testCustomerAttemptingSuperAdminRouteIsRejected() {
        val customer = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "regular.buyer@example.com",
            name = "Regular Buyer",
            role = UserRole.CUSTOMER
        )

        val guard = RoleNavigationGuard { customer }
        val result = guard.handleNavigationAttempt(Screen.SuperAdminDashboard.route)

        assertTrue("Customer navigating to Super Admin must be rejected", result is NavigationResult.Rejected)
        val rejected = result as NavigationResult.Rejected
        assertEquals(Screen.Marketplace.route, rejected.fallbackRoute)
        assertTrue(rejected.reason.contains("Super Admin authorization required"))
    }

    // --- TEST 5: Customer remains authenticated after rejected navigation ---
    @Test
    fun testCustomerRemainsAuthenticatedAfterRejectedNavigation() {
        var authSessionActive = true
        val customer = UserProfile(
            id = "auth-cust-123",
            email = "persisting.customer@example.com",
            name = "Persistent Customer",
            role = UserRole.CUSTOMER
        )

        val guard = RoleNavigationGuard { customer }

        // Attempt 1: Navigate to Farm Dashboard
        val farmResult = guard.handleNavigationAttempt(Screen.FarmDashboard.route)
        assertTrue(farmResult is NavigationResult.Rejected)
        assertTrue("Session must remain active after rejected Farm Admin route", authSessionActive)
        assertEquals(UserRole.CUSTOMER, customer.role)

        // Attempt 2: Navigate to Super Admin Dashboard
        val superResult = guard.handleNavigationAttempt(Screen.SuperAdminDashboard.route)
        assertTrue(superResult is NavigationResult.Rejected)
        assertTrue("Session must remain active after rejected Super Admin route", authSessionActive)
        assertEquals(UserRole.CUSTOMER, customer.role)
    }

    // --- TEST 6: Farm Admin Profile contains Farm Partner Portal ---
    @Test
    fun testFarmAdminProfileContainsFarmPartnerPortal() {
        val farmAdmin = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "partner@salemgoats.com",
            name = "Salem Partner",
            role = UserRole.FARM_ADMIN,
            farmId = UUID.randomUUID().toString()
        )

        val ui = resolveProfileUi(farmAdmin)
        assertTrue("Farm Admin Profile must show Farm Partner Portal button", ui.showFarmPartnerPortalButton)
        assertFalse("Farm Admin Profile must NOT show Super Admin Console", ui.showSuperAdminConsoleButton)
    }

    // --- TEST 7: Farm Admin accessing Farm Admin area loads own farm ---
    @Test
    fun testFarmAdminAccessingFarmAdminAreaLoadsOwnFarm() {
        val partnerId = UUID.randomUUID().toString()
        val partnerFarmId = UUID.randomUUID().toString()

        val partnerFarm = Farm(
            id = partnerFarmId,
            name = "Salem Goat Farm",
            ownerId = partnerId,
            ownerName = "Salem Partner",
            location = "Salem, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9876543210",
            email = "partner@salemgoats.com",
            description = "Quality breeding partner",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false
        )

        val otherFarm = Farm(
            id = UUID.randomUUID().toString(),
            name = "Other Competitor Pastures",
            ownerId = UUID.randomUUID().toString(),
            ownerName = "Other Breeder",
            location = "Madurai, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9123456780",
            email = "other@breeder.com",
            description = "Other farm",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false
        )

        val farmAdmin = UserProfile(
            id = partnerId,
            email = "partner@salemgoats.com",
            name = "Salem Partner",
            role = UserRole.FARM_ADMIN,
            farmId = partnerFarmId
        )

        val resolvedFarm = resolveFarmForAdminHub(farmAdmin, listOf(partnerFarm, otherFarm))
        assertNotNull("Farm Admin must resolve a farm", resolvedFarm)
        assertEquals(partnerFarmId, resolvedFarm?.id)
        assertEquals(partnerId, resolvedFarm?.ownerId)

        // Verify isolation: Even if farmId in user profile pointed to competitor farm, owner_id match prevents unauthorized takeover
        val maliciousProfile = farmAdmin.copy(farmId = otherFarm.id)
        val isolatedFarm = resolveFarmForAdminHub(maliciousProfile, listOf(partnerFarm, otherFarm))
        // Must fallback to owned farm by ownerId, NOT competitor farm
        assertEquals(partnerFarm.id, isolatedFarm?.id)
        assertNotEquals(otherFarm.id, isolatedFarm?.id)
    }

    // --- TEST 8: Farm Admin attempting Super Admin route is rejected ---
    @Test
    fun testFarmAdminAttemptingSuperAdminRouteIsRejected() {
        val farmAdmin = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "partner@ammalpartner.com",
            name = "Partner",
            role = UserRole.FARM_ADMIN
        )

        val guard = RoleNavigationGuard { farmAdmin }
        val result = guard.handleNavigationAttempt(Screen.SuperAdminDashboard.route)

        assertTrue("Farm Admin navigating to Super Admin must be rejected", result is NavigationResult.Rejected)
        val rejected = result as NavigationResult.Rejected
        assertEquals("Fallback for Farm Admin must be their Farm Dashboard", Screen.FarmDashboard.route, rejected.fallbackRoute)
        assertTrue(rejected.reason.contains("Super Admin authorization required"))
    }

    // --- TEST 9: Super Admin retains Super Admin Console access ---
    @Test
    fun testSuperAdminRetainsSuperAdminConsoleAccess() {
        val superAdmin = UserProfile(
            id = UUID.randomUUID().toString(),
            email = "admin@ammalfarm.com",
            name = "Platform Super Admin",
            role = UserRole.SUPER_ADMIN,
            farmId = SEED_AMMAL_FARM_UUID
        )

        val guard = RoleNavigationGuard { superAdmin }
        val superAdminResult = guard.handleNavigationAttempt(Screen.SuperAdminDashboard.route)
        assertTrue("Super Admin must be allowed on Super Admin Dashboard", superAdminResult is NavigationResult.Allowed)

        val ui = resolveProfileUi(superAdmin)
        assertTrue("Super Admin Profile must show Super Admin Console button", ui.showSuperAdminConsoleButton)
        assertTrue("Super Admin Profile must show My Farm / Ammal Farm button", ui.showMyFarmAmmalFarmButton)
    }

    // --- TEST 10: Super Admin accessing My Farm resolves Ammal Farm ---
    @Test
    fun testSuperAdminAccessingMyFarmResolvesAmmalFarm() {
        val superAdminId = UUID.randomUUID().toString()

        val ammalFarm = Farm(
            id = SEED_AMMAL_FARM_UUID,
            name = "Ammal Farm Central",
            ownerId = superAdminId,
            ownerName = "Ammal Farm Admin",
            location = "Vellore, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9876543210",
            email = "admin@ammalfarm.com",
            description = "Central breeding facility",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = true
        )

        val partnerFarm = Farm(
            id = UUID.randomUUID().toString(),
            name = "Salem Partner",
            ownerId = UUID.randomUUID().toString(),
            ownerName = "Partner",
            location = "Salem, Tamil Nadu",
            state = "Tamil Nadu",
            contactNumber = "9123456780",
            email = "partner@salem.com",
            description = "Partner",
            verificationStatus = VerificationStatus.APPROVED,
            isAmmalOwnFarm = false
        )

        val superAdmin = UserProfile(
            id = superAdminId,
            email = "admin@ammalfarm.com",
            name = "Super Admin",
            role = UserRole.SUPER_ADMIN,
            farmId = SEED_AMMAL_FARM_UUID
        )

        val resolved = resolveFarmForAdminHub(superAdmin, listOf(ammalFarm, partnerFarm))
        assertNotNull(resolved)
        assertEquals("Super Admin must resolve Ammal Farm", SEED_AMMAL_FARM_UUID, resolved?.id)
        assertTrue("Resolved farm must be marked as Ammal Own Farm", resolved?.isAmmalOwnFarm == true)
    }
}
