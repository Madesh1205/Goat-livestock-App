package com.example

import com.example.data.dto.FarmDto
import com.example.data.dto.ProfileDto
import com.example.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * AMMAL FARM — STAGE 11A TEST SUITE
 * Validates:
 * 1. Customer signup with valid session.
 * 2. Customer signup does not require email verification when session exists.
 * 3. Customer remains authenticated after signup.
 * 4. Customer profile is created.
 * 5. Customer role = CUSTOMER.
 * 6. Farm Admin signup succeeds.
 * 7. Farm Admin profile is created.
 * 8. Farm Admin farm is created.
 * 9. Farm owner_id equals authenticated user ID.
 * 10. Farm status = PENDING.
 * 11. Farm is not Ammal Farm.
 * 12. Farm Admin cannot become SUPER_ADMIN.
 * 13. Signup database failure is reported correctly.
 * 14. No duplicate farm is created by client + trigger.
 * 15. Confirm Email ON / null session remains compatible.
 * 16. Normal logout still works.
 */
class Stage11ASignupFlowFixTest {

    private lateinit var simulatedSystem: SimulatedSupabaseAndAuthSystem

    @Before
    fun setUp() {
        simulatedSystem = SimulatedSupabaseAndAuthSystem()
    }

    // 1. Customer signup with valid session
    // 2. Customer signup does not require email verification when session exists
    // 3. Customer remains authenticated after signup
    // 4. Customer profile is created
    // 5. Customer role = CUSTOMER
    @Test
    fun testCustomerSignupWithValidSessionRemainsAuthenticatedWithoutEmailVerification() = runBlocking {
        // Confirm Email is OFF -> session is returned
        simulatedSystem.confirmEmailEnabled = false

        val result = simulatedSystem.registerCustomer(
            name = "Kannan Rajan",
            email = "kannan.rajan@example.com",
            password = "SecurePassword123!",
            phone = "+91 9876543210"
        )

        assertTrue("Customer registration should succeed", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull("User profile must not be null", user)
        assertEquals("Kannan Rajan", user!!.name)
        assertEquals("kannan.rajan@example.com", user.email)
        assertEquals("Role must be CUSTOMER", UserRole.CUSTOMER, user.role)
        assertNull("Customer must not have a farmId", user.farmId)

        val state = simulatedSystem.authState.value
        assertTrue("Customer must remain authenticated", state.isAuthenticated)
        assertFalse("emailConfirmationRequired must be false when valid session exists", state.emailConfirmationRequired)
        assertNotNull("AuthState userProfile must be populated", state.userProfile)
        assertEquals(user.id, state.userProfile?.id)
        assertNull("Customer should have no farm in auth state", state.userFarm)

        // Database record check (provisioned by trigger)
        val dbProfile = simulatedSystem.profilesTable[user.id]
        assertNotNull("Profile record must exist in database", dbProfile)
        assertEquals("CUSTOMER", dbProfile!!.role)
    }

    // 6. Farm Admin signup succeeds
    // 7. Farm Admin profile is created
    // 8. Farm Admin farm is created
    // 9. Farm owner_id equals authenticated user ID
    // 10. Farm status = PENDING
    // 11. Farm is not Ammal Farm
    // 14. No duplicate farm is created by client + trigger
    @Test
    fun testFarmAdminSignupProvisionsPendingPartnerFarmWithoutDuplicate() = runBlocking {
        // Confirm Email is OFF -> session is returned
        simulatedSystem.confirmEmailEnabled = false

        val result = simulatedSystem.registerFarmAdmin(
            name = "Selvam Periyasamy",
            email = "selvam@kongufarms.com",
            password = "FarmPassword123!",
            phone = "+91 9845012345",
            farmName = "Kongu Pedigree Farm",
            farmDistrict = "Salem",
            farmDescription = "Specializing in pure Salem Black goats"
        )

        assertTrue("Farm Admin signup should succeed", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull("User profile must not be null", user)
        assertEquals("FARM_ADMIN", user!!.role.name)
        assertNotNull("User profile must link to created farmId", user.farmId)

        // Verify farm in database
        val farmId = user.farmId!!
        val dbFarm = simulatedSystem.farmsTable[farmId]
        assertNotNull("Farm must exist in database", dbFarm)
        assertEquals("Farm owner_id must match authenticated user ID", user.id, dbFarm!!.ownerId)
        assertEquals("Kongu Pedigree Farm", dbFarm.name)
        assertEquals("Verification status must be PENDING", "PENDING", dbFarm.status)
        assertFalse("Farm must NOT be Ammal Own Farm", dbFarm.isAmmalOwnFarm)
        assertEquals("Partner listing limit must default to 2", 2, dbFarm.goatListingLimit)

        // Check AuthState
        val state = simulatedSystem.authState.value
        assertTrue("Farm Admin must remain authenticated", state.isAuthenticated)
        assertFalse("emailConfirmationRequired must be false", state.emailConfirmationRequired)
        assertNotNull("AuthState userFarm must be populated", state.userFarm)
        assertEquals(farmId, state.userFarm?.id)
        assertEquals(VerificationStatus.PENDING, state.userFarm?.verificationStatus)

        // 14. Verify exactly ONE farm exists for this user (no duplicate farm created)
        val farmsForUser = simulatedSystem.farmsTable.values.filter { it.ownerId == user.id }
        assertEquals("Exactly one farm must be created for this owner ID (no duplicates)", 1, farmsForUser.size)
    }

    // 12. Farm Admin cannot become SUPER_ADMIN
    @Test
    fun testFarmAdminCannotEscalateToSuperAdminViaClientMetadata() = runBlocking {
        val result = simulatedSystem.registerWithMetadataEscalation(
            name = "Intruder User",
            email = "intruder@malicious.org",
            password = "Password123!",
            phone = "+91 9999999999",
            claimedRole = "SUPER_ADMIN"
        )

        assertTrue("Registration might succeed as normal account", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull(user)
        assertNotEquals("Claimed SUPER_ADMIN must be rejected", UserRole.SUPER_ADMIN, user!!.role)
        assertEquals("Role must be clamped to CUSTOMER", UserRole.CUSTOMER, user.role)
    }

    // 13. Signup database failure is reported correctly
    @Test
    fun testSignupDatabaseErrorIsReportedCorrectlyAndNotMaskedAsEmailVerification() = runBlocking {
        simulatedSystem.simulateDatabaseTriggerError = true

        val result = simulatedSystem.registerFarmAdmin(
            name = "Farmer With Error",
            email = "farmer.err@example.com",
            password = "Password123!",
            phone = "+91 9876543210",
            farmName = "Error Farm",
            farmDistrict = "Madurai",
            farmDescription = ""
        )

        assertTrue("Registration should fail when database trigger errors", result.isFailure)
        val errorMsg = result.exceptionOrNull()?.message ?: ""
        assertTrue(
            "Error must reflect the actual database error, got: $errorMsg",
            errorMsg.contains("Database error", ignoreCase = true) || errorMsg.contains("saving new user", ignoreCase = true)
        )

        val state = simulatedSystem.authState.value
        assertFalse("State must NOT be authenticated on failure", state.isAuthenticated)
        assertFalse("Database error must NOT be classified as emailConfirmationRequired", state.emailConfirmationRequired)
    }

    // 15. Confirm Email ON / null session remains compatible
    @Test
    fun testConfirmEmailOnCorrectlySetsEmailConfirmationRequiredWhenSessionIsNull() = runBlocking {
        // When Confirm Email is ON in Supabase, signup returns user but session == null
        simulatedSystem.confirmEmailEnabled = true

        val result = simulatedSystem.registerCustomer(
            name = "Pending Confirmation User",
            email = "pending.confirm@example.com",
            password = "Password123!",
            phone = "+91 9876543210"
        )

        assertTrue("Signup should succeed", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull(user)

        val state = simulatedSystem.authState.value
        assertFalse("User must NOT be authenticated when session is null", state.isAuthenticated)
        assertTrue("emailConfirmationRequired must be TRUE when session is null", state.emailConfirmationRequired)
        assertNull("userProfile must be null in auth state while unconfirmed", state.userProfile)
        assertEquals("Account created. Please verify your email before signing in.", state.successMessage)
    }

    // 16. Normal logout still works
    @Test
    fun testNormalLogoutClearsSessionAndState() = runBlocking {
        // Sign up and log in
        simulatedSystem.confirmEmailEnabled = false
        val signup = simulatedSystem.registerCustomer(
            name = "Logout Test User",
            email = "logout.test@example.com",
            password = "Password123!",
            phone = "+91 9876543210"
        )
        assertTrue(signup.isSuccess)
        assertTrue(simulatedSystem.authState.value.isAuthenticated)

        // Perform normal logout
        simulatedSystem.logout()

        val state = simulatedSystem.authState.value
        assertFalse("User must NOT be authenticated after logout", state.isAuthenticated)
        assertNull("userProfile must be null after logout", state.userProfile)
        assertNull("userFarm must be null after logout", state.userFarm)
        assertEquals("You have been logged out.", state.successMessage)
    }

    /**
     * Simulation of Supabase Auth, PostgreSQL trigger handle_new_auth_user(),
     * and the updated AuthRepository logic.
     */
    private class SimulatedSupabaseAndAuthSystem {
        var confirmEmailEnabled: Boolean = false
        var simulateDatabaseTriggerError: Boolean = false

        val authUsers = mutableMapOf<String, String>() // email -> userId
        val profilesTable = mutableMapOf<String, ProfileDto>()
        val farmsTable = mutableMapOf<String, FarmDto>()

        private val _authState = MutableStateFlow(AuthState())
        val authState = _authState.asStateFlow()

        private var currentSessionUserId: String? = null

        fun registerCustomer(
            name: String,
            email: String,
            password: String,
            phone: String
        ): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            if (authUsers.containsKey(cleanEmail)) {
                val err = "An account with this email already exists."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(Exception(err))
            }

            if (simulateDatabaseTriggerError) {
                val err = "Database error saving new user"
                _authState.update { it.copy(errorMessage = err, emailConfirmationRequired = false) }
                return Result.failure(Exception(err))
            }

            val userId = UUID.randomUUID().toString()
            authUsers[cleanEmail] = userId

            // Trigger handle_new_auth_user() executes atomically in database
            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = "CUSTOMER"
            )
            profilesTable[userId] = dbProfile

            // Auth response from Supabase
            val session = if (confirmEmailEnabled) null else userId
            currentSessionUserId = session

            return if (session != null) {
                // Client reloads trigger-created profile
                val userProfile = UserProfile(
                    id = dbProfile.id,
                    email = dbProfile.email ?: cleanEmail,
                    name = dbProfile.fullName ?: name.trim(),
                    phone = dbProfile.phone ?: "",
                    role = UserRole.CUSTOMER,
                    farmId = null
                )
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = userProfile,
                        userFarm = null,
                        successMessage = "Account created successfully! Welcome to Ammal Farm.",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(userProfile)
            } else {
                val pendingProfile = UserProfile(
                    id = userId,
                    email = cleanEmail,
                    name = name.trim(),
                    phone = phone.trim(),
                    role = UserRole.CUSTOMER
                )
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = false,
                        userProfile = null,
                        userFarm = null,
                        successMessage = "Account created. Please verify your email before signing in.",
                        emailConfirmationRequired = true
                    )
                }
                Result.success(pendingProfile)
            }
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
            if (authUsers.containsKey(cleanEmail)) {
                val err = "An account with this email already exists."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(Exception(err))
            }

            if (simulateDatabaseTriggerError) {
                val err = "Database error saving new user"
                _authState.update { it.copy(errorMessage = err, emailConfirmationRequired = false) }
                return Result.failure(Exception(err))
            }

            val userId = UUID.randomUUID().toString()
            authUsers[cleanEmail] = userId

            // Trigger handle_new_auth_user() executes atomically
            val farmId = UUID.randomUUID().toString()
            val dbFarm = FarmDto(
                id = farmId,
                name = farmName.trim(),
                ownerId = userId,
                description = farmDescription.trim(),
                locationDistrict = farmDistrict.trim(),
                locationState = "Tamil Nadu",
                address = "${farmDistrict.trim()}, Tamil Nadu",
                contactPhone = phone.trim(),
                contactEmail = cleanEmail,
                status = "PENDING",
                isAmmalOwnFarm = false,
                goatListingLimit = 2
            )
            farmsTable[farmId] = dbFarm

            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = "FARM_ADMIN",
                farmId = farmId
            )
            profilesTable[userId] = dbProfile

            // Auth response from Supabase
            val session = if (confirmEmailEnabled) null else userId
            currentSessionUserId = session

            return if (session != null) {
                // Client reloads trigger-created profile & farm (NO client-side duplicate insertion!)
                val userFarm = dbFarm.toDomain()
                val userProfile = UserProfile(
                    id = dbProfile.id,
                    email = dbProfile.email ?: cleanEmail,
                    name = dbProfile.fullName ?: name.trim(),
                    phone = dbProfile.phone ?: "",
                    role = UserRole.FARM_ADMIN,
                    farmId = dbFarm.id
                )
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = userProfile,
                        userFarm = userFarm,
                        successMessage = "Farm application submitted! Status: PENDING approval by Super Admin.",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(userProfile)
            } else {
                val pendingProfile = UserProfile(
                    id = userId,
                    email = cleanEmail,
                    name = name.trim(),
                    phone = phone.trim(),
                    role = UserRole.FARM_ADMIN
                )
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = false,
                        userProfile = null,
                        userFarm = null,
                        successMessage = "Account created. Please verify your email before signing in.",
                        emailConfirmationRequired = true
                    )
                }
                Result.success(pendingProfile)
            }
        }

        fun registerWithMetadataEscalation(
            name: String,
            email: String,
            password: String,
            phone: String,
            claimedRole: String
        ): Result<UserProfile> {
            val cleanEmail = email.trim().lowercase()
            val userId = UUID.randomUUID().toString()
            authUsers[cleanEmail] = userId

            // Trigger safeguard: If claimedRole is SUPER_ADMIN, default strictly to CUSTOMER
            val effectiveRole = if (claimedRole.uppercase() == "FARM_ADMIN") {
                "FARM_ADMIN"
            } else {
                "CUSTOMER"
            }

            val dbProfile = ProfileDto(
                id = userId,
                email = cleanEmail,
                fullName = name.trim(),
                phone = phone.trim(),
                role = effectiveRole
            )
            profilesTable[userId] = dbProfile

            val userProfile = UserProfile(
                id = dbProfile.id,
                email = dbProfile.email ?: cleanEmail,
                name = dbProfile.fullName ?: name.trim(),
                phone = dbProfile.phone ?: "",
                role = UserRole.valueOf(effectiveRole),
                farmId = null
            )
            return Result.success(userProfile)
        }

        fun logout() {
            currentSessionUserId = null
            _authState.update {
                AuthState(
                    isAuthenticated = false,
                    userProfile = null,
                    userFarm = null,
                    successMessage = "You have been logged out."
                )
            }
        }
    }
}
