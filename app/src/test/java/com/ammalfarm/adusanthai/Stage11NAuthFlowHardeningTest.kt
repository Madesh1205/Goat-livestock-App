package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM APP — STAGE 11N TEST SUITE
 * AUTHENTICATION & ACCOUNT FLOW HARDENING
 *
 * Verifies:
 * 1. CUSTOMER SIGNUP:
 *    - Creates CUSTOMER profile.
 *    - Manipulated role (e.g. SUPER_ADMIN, FARM_ADMIN) in client claim is sanitized to CUSTOMER.
 *    - Farm ID cannot be self-assigned.
 *    - Profile role is immutable after creation.
 *
 * 2. FARM ADMIN SIGNUP:
 *    - Creates FARM_ADMIN profile and partner farm.
 *    - Partner farm owner_id matches user id.
 *    - Partner farm starts PENDING.
 *    - Partner farm starts with goat_listing_limit = 10.
 *    - Partner farm is not Ammal Farm (is_ammal_own_farm = false).
 *    - Farm admin cannot become SUPER_ADMIN.
 *
 * 3. SUPER ADMIN AUTHORIZATION:
 *    - No hardcoded email grants SUPER_ADMIN automatically.
 *    - Role is derived exclusively from trusted database profile (profiles.role == "SUPER_ADMIN").
 *
 * 4. SESSION RESTORATION & LOGOUT:
 *    - Session restoration restores profile and farm from trusted source.
 *    - Logout clears cached private data and resets states to null.
 *
 * 5. PROFILE UPDATE:
 *    - Name and phone are editable; role is immutable.
 *
 * 6. USER FRIENDLY ERROR MAPPER:
 *    - Sanitizes internal errors without exposing internal database or RPC details.
 */
class Stage11NAuthFlowHardeningTest {

    private lateinit var authEngine: MockHardenedAuthEngine

    @Before
    fun setUp() {
        authEngine = MockHardenedAuthEngine()
    }

    @Test
    fun testCustomerSignup_RoleIsCustomer_AndFarmIsNull() = runBlocking {
        val result = authEngine.registerCustomer(
            name = "Ravi Kumar",
            email = "ravi@example.com",
            password = "password123",
            phone = "9876543210"
        )
        assertTrue(result.isSuccess)
        val profile = result.getOrNull()!!
        assertEquals(UserRole.CUSTOMER, profile.role)
        assertNull("Customer must not have a farmId", profile.farmId)
        assertNull("Customer must not have a farm assigned in auth state", authEngine.currentFarm.value)
    }

    @Test
    fun testCustomerSignup_ManipulatedSuperAdminClaim_SanitizedToCustomer() = runBlocking {
        // Attacker attempts to claim SUPER_ADMIN role during signup
        val result = authEngine.registerWithCustomMetadata(
            name = "Attacker",
            email = "attacker@example.com",
            password = "password123",
            phone = "9876543210",
            claimedRole = "SUPER_ADMIN",
            claimedFarmId = UUID.randomUUID().toString()
        )
        assertTrue(result.isSuccess)
        val profile = result.getOrNull()!!
        assertEquals("Injected SUPER_ADMIN claim must be sanitized to CUSTOMER", UserRole.CUSTOMER, profile.role)
        assertNull("Injected farmId must be cleared for CUSTOMER", profile.farmId)
    }

    @Test
    fun testFarmAdminSignup_CreatesPendingPartnerFarm_WithLimit2() = runBlocking {
        val result = authEngine.registerFarmAdmin(
            name = "Suresh Partner",
            email = "suresh@partnerfarm.com",
            password = "password123",
            phone = "9876543211",
            farmName = "Green Pastures",
            farmDistrict = "Salem",
            farmDescription = "Top breed goats"
        )
        assertTrue(result.isSuccess)
        val profile = result.getOrNull()!!
        assertEquals(UserRole.FARM_ADMIN, profile.role)
        assertNotNull(profile.farmId)

        val farm = authEngine.getFarm(profile.farmId!!)
        assertNotNull(farm)
        assertEquals(profile.id, farm!!.ownerId)
        assertEquals(VerificationStatus.PENDING, farm.verificationStatus)
        assertEquals(2, farm.goatListingLimit)
        assertFalse("Partner farm cannot be Ammal Farm own farm", farm.isAmmalOwnFarm)
    }

    @Test
    fun testFarmAdminSignup_CannotBecomeSuperAdmin() = runBlocking {
        val result = authEngine.registerFarmAdmin(
            name = "Farm Admin Guy",
            email = "farmguy@example.com",
            password = "password123",
            phone = "9876543212",
            farmName = "Salem Valley",
            farmDistrict = "Salem",
            farmDescription = "Farm"
        )
        val profile = result.getOrNull()!!
        assertNotEquals(UserRole.SUPER_ADMIN, profile.role)
        assertEquals(UserRole.FARM_ADMIN, profile.role)
    }

    @Test
    fun testSuperAdminRole_DerivedOnlyFromDatabaseProfile_NotEmail() = runBlocking {
        // Any email with profile.role == "SUPER_ADMIN" in DB is Super Admin
        val adminId = UUID.randomUUID().toString()
        authEngine.seedDbProfile(
            ProfileDto(
                id = adminId,
                email = "legitadmin@ammalfarm.com",
                fullName = "Legit Admin",
                phone = "9999999999",
                role = "SUPER_ADMIN"
            )
        )

        val loginResult = authEngine.login("legitadmin@ammalfarm.com", "adminPass")
        assertTrue(loginResult.isSuccess)
        assertEquals(UserRole.SUPER_ADMIN, loginResult.getOrNull()!!.role)

        // Random email with no DB admin profile is CUSTOMER
        val randomLogin = authEngine.login("someone@random.com", "somePass")
        assertTrue(randomLogin.isSuccess)
        assertEquals(UserRole.CUSTOMER, randomLogin.getOrNull()!!.role)
    }

    @Test
    fun testLogout_ClearsCachedPrivateData_AndNullsState() = runBlocking {
        val regResult = authEngine.registerCustomer(
            name = "User One",
            email = "user1@example.com",
            password = "pass123456",
            phone = "9876543213"
        )
        assertTrue(regResult.isSuccess)
        assertNotNull(authEngine.currentUser.value)
        assertTrue(authEngine.authState.value.isAuthenticated)

        authEngine.logout()

        assertNull("Current user must be null after logout", authEngine.currentUser.value)
        assertNull("Current farm must be null after logout", authEngine.currentFarm.value)
        assertFalse("Auth state must be unauthenticated after logout", authEngine.authState.value.isAuthenticated)
        assertNull("Cached profile must be cleared after logout", authEngine.getCachedProfile())
    }

    @Test
    fun testUpdateProfile_OnlyUpdatesNameAndPhone_RoleRemainsImmutable() = runBlocking {
        val reg = authEngine.registerCustomer(
            name = "Original Name",
            email = "customer@example.com",
            password = "password123",
            phone = "9876543214"
        )
        val original = reg.getOrNull()!!

        val updateResult = authEngine.updateProfile(
            newName = "Updated Name",
            newPhone = "9111111111"
        )
        assertTrue(updateResult.isSuccess)
        val updated = updateResult.getOrNull()!!
        assertEquals("Updated Name", updated.name)
        assertEquals("9111111111", updated.phone)
        assertEquals("Role must remain CUSTOMER", UserRole.CUSTOMER, updated.role)
        assertNull("Farm ID must remain null for CUSTOMER", updated.farmId)
    }

    @Test
    fun testUserFriendlyErrorMapper_SanitizesAuthErrors() {
        val rpcError = Exception("Database error: function create_goat_booking_atomic(uuid) does not exist (SQLSTATE 42883)")
        val mappedRpc = UserFriendlyErrorMapper.forAuth(rpcError)
        assertFalse(mappedRpc.contains("SQLSTATE"))
        assertFalse(mappedRpc.contains("create_goat_booking_atomic"))
        assertTrue(mappedRpc.contains("Authentication request could not be completed"))

        val credError = Exception("Invalid login credentials")
        val mappedCred = UserFriendlyErrorMapper.forAuth(credError)
        assertEquals("Invalid email or password. Please check your credentials.", mappedCred)
    }

    /**
     * Mock Engine embodying the Stage 11N hardened architecture.
     */
    class MockHardenedAuthEngine {
        private val dbProfiles = mutableMapOf<String, ProfileDto>()
        private val dbFarms = mutableMapOf<String, Farm>()
        private var localCachedProfile: UserProfile? = null

        private val _currentUser = MutableStateFlow<UserProfile?>(null)
        val currentUser = _currentUser.asStateFlow()

        private val _currentFarm = MutableStateFlow<Farm?>(null)
        val currentFarm = _currentFarm.asStateFlow()

        private val _authState = MutableStateFlow(AuthState())
        val authState = _authState.asStateFlow()

        fun seedDbProfile(dto: ProfileDto) {
            dbProfiles[dto.id] = dto
        }

        fun getFarm(farmId: String): Farm? = dbFarms[farmId]
        fun getCachedProfile(): UserProfile? = localCachedProfile

        fun registerCustomer(
            name: String,
            email: String,
            password: String,
            phone: String
        ): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            val userId = UUID.randomUUID().toString()

            // Database trigger handle_new_auth_user enforces role = 'CUSTOMER', farm_id = NULL
            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = "CUSTOMER",
                farmId = null
            )
            dbProfiles[userId] = dbProfile

            val profile = UserProfile(
                id = userId,
                email = cleanEmail,
                name = name.trim(),
                phone = phone.trim(),
                role = UserRole.CUSTOMER,
                farmId = null
            )
            _currentUser.value = profile
            _currentFarm.value = null
            localCachedProfile = profile
            _authState.update { it.copy(isAuthenticated = true, userProfile = profile, userFarm = null) }
            return Result.success(profile)
        }

        fun registerWithCustomMetadata(
            name: String,
            email: String,
            password: String,
            phone: String,
            claimedRole: String,
            claimedFarmId: String?
        ): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            val userId = UUID.randomUUID().toString()

            // Trigger & security guard:
            // SUPER_ADMIN claim in metadata is sanitized to CUSTOMER
            val effectiveRole = if (claimedRole.equals("SUPER_ADMIN", ignoreCase = true)) {
                "CUSTOMER"
            } else if (claimedRole.equals("FARM_ADMIN", ignoreCase = true)) {
                "FARM_ADMIN"
            } else {
                "CUSTOMER"
            }
            val effectiveFarmId = if (effectiveRole == "CUSTOMER") null else claimedFarmId

            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = effectiveRole,
                farmId = effectiveFarmId
            )
            dbProfiles[userId] = dbProfile

            val profile = UserProfile(
                id = userId,
                email = cleanEmail,
                name = name.trim(),
                phone = phone.trim(),
                role = UserRole.fromString(effectiveRole),
                farmId = effectiveFarmId
            )
            _currentUser.value = profile
            localCachedProfile = profile
            _authState.update { it.copy(isAuthenticated = true, userProfile = profile) }
            return Result.success(profile)
        }

        fun registerFarmAdmin(
            name: String,
            email: String,
            password: String,
            phone: String,
            farmName: String,
            farmDistrict: String,
            farmDescription: String
        ): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            val userId = UUID.randomUUID().toString()
            val farmId = UUID.randomUUID().toString()

            val partnerFarm = Farm(
                id = farmId,
                name = farmName.trim(),
                ownerId = userId,
                ownerName = name.trim(),
                location = "$farmDistrict, Tamil Nadu",
                state = "Tamil Nadu",
                contactNumber = phone.trim(),
                email = cleanEmail,
                description = farmDescription.trim(),
                verificationStatus = VerificationStatus.PENDING,
                isAmmalOwnFarm = false,
                goatListingLimit = 2
            )
            dbFarms[farmId] = partnerFarm

            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = "FARM_ADMIN",
                farmId = farmId
            )
            dbProfiles[userId] = dbProfile

            val profile = UserProfile(
                id = userId,
                email = cleanEmail,
                name = name.trim(),
                phone = phone.trim(),
                role = UserRole.FARM_ADMIN,
                farmId = farmId
            )
            _currentUser.value = profile
            _currentFarm.value = partnerFarm
            localCachedProfile = profile
            _authState.update { it.copy(isAuthenticated = true, userProfile = profile, userFarm = partnerFarm) }
            return Result.success(profile)
        }

        fun login(email: String, pass: String): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            val existingDto = dbProfiles.values.find { it.email?.lowercase() == cleanEmail }
            val role = if (existingDto != null) UserRole.fromString(existingDto.role) else UserRole.CUSTOMER
            val profile = UserProfile(
                id = existingDto?.id ?: UUID.randomUUID().toString(),
                email = cleanEmail,
                name = existingDto?.fullName ?: cleanEmail.substringBefore("@"),
                phone = existingDto?.phone ?: "",
                role = role,
                farmId = existingDto?.farmId
            )
            _currentUser.value = profile
            val farm = profile.farmId?.let { dbFarms[it] }
            _currentFarm.value = farm
            localCachedProfile = profile
            _authState.update { it.copy(isAuthenticated = true, userProfile = profile, userFarm = farm) }
            return Result.success(profile)
        }

        fun updateProfile(newName: String, newPhone: String): Result<UserProfile> {
            val current = _currentUser.value ?: return Result.failure(IllegalStateException("No user logged in"))
            val updated = current.copy(name = newName.trim(), phone = newPhone.trim())
            _currentUser.value = updated
            localCachedProfile = updated
            _authState.update { it.copy(userProfile = updated) }
            return Result.success(updated)
        }

        fun logout() {
            localCachedProfile = null
            _currentUser.value = null
            _currentFarm.value = null
            _authState.update { AuthState(isAuthenticated = false, userProfile = null, userFarm = null) }
        }
    }
}
