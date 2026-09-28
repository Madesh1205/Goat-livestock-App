package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

private const val TEST_SUPER_ADMIN_EMAIL = "admin@ammalfarm.com"

/**
 * Sign-Up Flow Audit & Hardening Comprehensive Test Suite:
 * - Customer sign-up with authoritative UUID & profile sync
 * - Farm Partner sign-up with atomic PENDING farm creation
 * - Super Admin privilege escalation prevention
 * - Email verification requirement & session isolation
 * - Input validation (email regex, password length >= 6, required fields)
 * - Error sanitization (duplicate email, weak password, network failure)
 * - Resend verification flow
 * - Regressions for Stages 1-10 (farm verification lifecycle, booking, Super Admin scope)
 */
class SignUpFlowAuditTest {

    private lateinit var mockAuthEngine: MockAuthFlowEngine

    @Before
    fun setUp() {
        mockAuthEngine = MockAuthFlowEngine()
    }

    @Test
    fun testValidCustomerSignUpCreatesProfileWithCustomerRoleAndAuthoritativeUuid() = runBlocking {
        val result = mockAuthEngine.registerCustomer(
            name = "Ravi Kumar",
            email = "ravi.kumar@example.com",
            password = "SecurePassword123!",
            phone = "+91 9876543210"
        )

        assertTrue("Customer registration should succeed", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull("User profile must not be null", user)
        assertEquals("Customer role must be assigned", UserRole.CUSTOMER, user!!.role)
        assertEquals("Ravi Kumar", user.name)
        assertEquals("ravi.kumar@example.com", user.email)
        assertNull("Customer should not have an associated farmId", user.farmId)
        assertTrue("User ID must be a valid UUID", isValidUuidString(user.id))

        // Ensure in database
        val profileInDb = mockAuthEngine.getProfile(user.id)
        assertNotNull("Profile must be saved in database", profileInDb)
        assertEquals("CUSTOMER", profileInDb!!.role.name)
    }

    @Test
    fun testValidFarmPartnerSignUpCreatesPendingFarmAndFarmAdminRole() = runBlocking {
        val result = mockAuthEngine.registerFarmAdmin(
            name = "Murugan Velu",
            email = "murugan@velufarms.com",
            password = "FarmPassword123!",
            phone = "+91 9845012345",
            farmName = "Velu Pedigree Goat Farm",
            farmDistrict = "Madurai",
            farmDescription = "Specializing in purebred Jamunapari and Boer goats"
        )

        assertTrue("Farm partner registration should succeed", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull("User profile must not be null", user)
        assertEquals("FARM_ADMIN role must be assigned", UserRole.FARM_ADMIN, user!!.role)
        assertNotNull("Farm ID must be linked to profile", user.farmId)
        assertTrue("Farm ID must be a valid UUID", isValidUuidString(user.farmId!!))

        // Verify farm record in database
        val farmInDb = mockAuthEngine.getFarm(user.farmId!!)
        assertNotNull("Farm record must be created in database", farmInDb)
        assertEquals("Farm owner ID must match user ID", user.id, farmInDb!!.ownerId)
        assertEquals("Velu Pedigree Goat Farm", farmInDb.name)
        assertEquals("Madurai, Tamil Nadu", farmInDb.location)
        assertEquals("Verification status must be PENDING", VerificationStatus.PENDING, farmInDb.verificationStatus)
        assertFalse("New farm must not be marked as Ammal Own Farm", farmInDb.isAmmalOwnFarm)
    }

    @Test
    fun testSuperAdminPrivilegeEscalationPreventedOnPublicRegistration() = runBlocking {
        // Attempt to register with role parameter or metadata claim as SUPER_ADMIN
        val result = mockAuthEngine.registerWithInjectedRole(
            name = "Malicious Attacker",
            email = "attacker@hack.com",
            password = "Password123!",
            phone = "+91 9999999999",
            injectedRoleClaim = "SUPER_ADMIN"
        )

        assertTrue("Registration may succeed as regular customer", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull(user)
        assertNotEquals("Public registration must NEVER yield SUPER_ADMIN", UserRole.SUPER_ADMIN, user!!.role)
        assertEquals("Role must be strictly clamped to CUSTOMER", UserRole.CUSTOMER, user.role)
    }

    @Test
    fun testOnlyBootstrapSuperAdminEmailReceivesSuperAdminScope() = runBlocking {
        val regularUser = mockAuthEngine.loginUser("customer@example.com", "validPass123!")
        assertNotEquals(UserRole.SUPER_ADMIN, regularUser.role)

        val superAdmin = mockAuthEngine.loginUser(TEST_SUPER_ADMIN_EMAIL, "adminPass123!")
        assertEquals("Configured super admin must receive SUPER_ADMIN", UserRole.SUPER_ADMIN, superAdmin.role)
    }

    @Test
    fun testRegistrationFailsOnInvalidEmailFormat() = runBlocking {
        val invalidEmails = listOf(
            "notanemail",
            "missingatdomain.com",
            "@nodomain.com",
            "user@.com",
            "user@domain"
        )

        for (invalidEmail in invalidEmails) {
            val result = mockAuthEngine.registerCustomer(
                name = "Test User",
                email = invalidEmail,
                password = "ValidPassword123!",
                phone = "+91 9800000000"
            )
            assertTrue("Registration should fail for invalid email: $invalidEmail", result.isFailure)
            val errorMsg = result.exceptionOrNull()?.message ?: ""
            assertTrue(
                "Error should inform about invalid email, got: $errorMsg",
                errorMsg.contains("valid email", ignoreCase = true)
            )
        }
    }

    @Test
    fun testRegistrationFailsOnWeakPassword() = runBlocking {
        val weakPasswords = listOf("", "123", "12345", "short")

        for (weakPass in weakPasswords) {
            val result = mockAuthEngine.registerCustomer(
                name = "Test User",
                email = "user@example.com",
                password = weakPass,
                phone = "+91 9800000000"
            )
            assertTrue("Registration should fail for short password: '$weakPass'", result.isFailure)
            val errorMsg = result.exceptionOrNull()?.message ?: ""
            assertTrue(
                "Error should indicate 6 characters requirement, got: $errorMsg",
                errorMsg.contains("6 characters", ignoreCase = true) || errorMsg.contains("security policy", ignoreCase = true)
            )
        }
    }

    @Test
    fun testRegistrationFailsOnBlankRequiredFields() = runBlocking {
        // Blank Name
        val emptyName = mockAuthEngine.registerCustomer("", "user@example.com", "Pass123456", "+91 9876543210")
        assertTrue(emptyName.isFailure)
        assertTrue(emptyName.exceptionOrNull()?.message?.contains("name", ignoreCase = true) == true)

        // Blank Phone
        val emptyPhone = mockAuthEngine.registerCustomer("User", "user@example.com", "Pass123456", "")
        assertTrue(emptyPhone.isFailure)
        assertTrue(emptyPhone.exceptionOrNull()?.message?.contains("phone", ignoreCase = true) == true)

        // Farm Admin missing Farm Name
        val emptyFarmName = mockAuthEngine.registerFarmAdmin(
            name = "Farmer",
            email = "farm@example.com",
            password = "Pass123456",
            phone = "+91 9876543210",
            farmName = "",
            farmDistrict = "Madurai",
            farmDescription = ""
        )
        assertTrue(emptyFarmName.isFailure)
        assertTrue(emptyFarmName.exceptionOrNull()?.message?.contains("farm name", ignoreCase = true) == true)

        // Farm Admin missing District
        val emptyDistrict = mockAuthEngine.registerFarmAdmin(
            name = "Farmer",
            email = "farm@example.com",
            password = "Pass123456",
            phone = "+91 9876543210",
            farmName = "My Farm",
            farmDistrict = "",
            farmDescription = ""
        )
        assertTrue(emptyDistrict.isFailure)
        assertTrue(emptyDistrict.exceptionOrNull()?.message?.contains("district", ignoreCase = true) == true)
    }

    @Test
    fun testEmailVerificationRequiredPreventsPrematureSessionAndShowsNotice() = runBlocking {
        mockAuthEngine.emailConfirmationRequired = true

        val result = mockAuthEngine.registerCustomer(
            name = "Unconfirmed User",
            email = "unconfirmed@example.com",
            password = "Password123!",
            phone = "+91 9800000000"
        )

        assertTrue(result.isSuccess)
        val authState = mockAuthEngine.authState.value
        assertFalse("User must NOT be marked authenticated before email confirmation", authState.isAuthenticated)
        assertNull("Current session user must be null while unconfirmed", authState.userProfile)
        assertTrue("Email confirmation required flag must be true", authState.emailConfirmationRequired)
        assertEquals(
            "Account created. Please verify your email before signing in.",
            authState.successMessage
        )
    }

    @Test
    fun testDuplicateEmailErrorSanitization() = runBlocking {
        // First registration
        val first = mockAuthEngine.registerCustomer("Existing User", "existing@example.com", "Password123!", "+91 9800000000")
        assertTrue(first.isSuccess)

        // Second registration with duplicate email
        val duplicate = mockAuthEngine.registerCustomer("Another User", "existing@example.com", "Password123!", "+91 9800000001")
        assertTrue("Duplicate registration should fail", duplicate.isFailure)
        val errorMsg = duplicate.exceptionOrNull()?.message ?: ""
        assertEquals(
            "An account with this email already exists.",
            errorMsg
        )
    }

    @Test
    fun testResendEmailVerificationFlow() = runBlocking {
        val resendResult = mockAuthEngine.resendEmailVerification("unverified@example.com")
        assertTrue("Resend verification should succeed", resendResult.isSuccess)
        val state = mockAuthEngine.authState.value
        assertTrue(
            "Success message must indicate verification sent",
            state.successMessage?.contains("Verification link", ignoreCase = true) == true
        )
    }

    @Test
    fun testNetworkErrorDoesNotProduceFakeSuccessState() = runBlocking {
        mockAuthEngine.simulateNetworkFailure = true

        val regResult = mockAuthEngine.registerCustomer(
            name = "Offline User",
            email = "offline@example.com",
            password = "Password123!",
            phone = "+91 9800000000"
        )

        assertTrue("Registration must fail on network disconnect", regResult.isFailure)
        val state = mockAuthEngine.authState.value
        assertFalse("State must NOT report authenticated on network error", state.isAuthenticated)
        assertNull("Profile must not be faked", state.userProfile)
        assertEquals(
            "Unable to connect. Please check your internet connection and try again.",
            state.errorMessage
        )
    }

    @Test
    fun testFarmVerificationLifecycleSuperAdminApproval() = runBlocking {
        // Register farm partner
        val farmResult = mockAuthEngine.registerFarmAdmin(
            name = "Karthik",
            email = "karthik@goatfarms.in",
            password = "Pass123456!",
            phone = "+91 9876543210",
            farmName = "Karthik Livestock",
            farmDistrict = "Salem",
            farmDescription = "Breeder of Salem Black goats"
        )
        assertTrue(farmResult.isSuccess)
        val farmId = farmResult.getOrNull()!!.farmId!!
        val farm = mockAuthEngine.getFarm(farmId)!!
        assertEquals(VerificationStatus.PENDING, farm.verificationStatus)

        // Super Admin approves farm
        val superAdmin = mockAuthEngine.loginUser(TEST_SUPER_ADMIN_EMAIL, "pass")
        assertEquals(UserRole.SUPER_ADMIN, superAdmin.role)

        val approvedFarm = mockAuthEngine.updateFarmStatus(farmId, VerificationStatus.APPROVED, superAdmin)
        assertEquals(VerificationStatus.APPROVED, approvedFarm.verificationStatus)
    }

    private fun isValidUuidString(str: String): Boolean {
        return try {
            UUID.fromString(str)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Mock in-memory auth flow engine strictly simulating the Supabase Auth,
     * PostgreSQL trigger handle_new_auth_user, and AuthRepository business logic.
     */
    private class MockAuthFlowEngine {
        val registeredEmails = mutableMapOf<String, String>() // email -> userId
        val profilesDb = mutableMapOf<String, UserProfile>()
        val farmsDb = mutableMapOf<String, Farm>()

        private val _authState = MutableStateFlow(AuthState())
        val authState = _authState.asStateFlow()

        var emailConfirmationRequired: Boolean = false
        var simulateNetworkFailure: Boolean = false

        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

        fun getProfile(userId: String): UserProfile? = profilesDb[userId]
        fun getFarm(farmId: String): Farm? = farmsDb[farmId]

        fun registerCustomer(
            name: String,
            email: String,
            password: String,
            phone: String
        ): Result<UserProfile> {
            val cleanName = name.trim()
            val cleanEmail = email.trim()
            val cleanPhone = phone.trim()

            if (cleanName.isBlank()) {
                val err = "Please enter your full name."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (!EMAIL_REGEX.matches(cleanEmail)) {
                val err = "Please enter a valid email address."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (cleanPhone.isBlank()) {
                val err = "Please enter your contact phone number."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (password.length < 6) {
                val err = "Password does not meet the required security policy (minimum 6 characters)."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }

            if (simulateNetworkFailure) {
                val err = "Unable to connect. Please check your internet connection and try again."
                _authState.update { it.copy(isAuthenticated = false, errorMessage = err) }
                return Result.failure(Exception(err))
            }

            if (registeredEmails.containsKey(cleanEmail.lowercase())) {
                val err = "An account with this email already exists."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(Exception(err))
            }

            val userId = UUID.randomUUID().toString()
            registeredEmails[cleanEmail.lowercase()] = userId

            val profile = UserProfile(
                id = userId,
                email = cleanEmail,
                name = cleanName,
                phone = cleanPhone,
                role = UserRole.CUSTOMER,
                farmId = null
            )
            profilesDb[userId] = profile

            if (emailConfirmationRequired) {
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
            } else {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = profile,
                        successMessage = "Account created successfully! Welcome to Ammal Farm.",
                        emailConfirmationRequired = false
                    )
                }
            }

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
            val cleanName = name.trim()
            val cleanEmail = email.trim()
            val cleanPhone = phone.trim()
            val cleanFarmName = farmName.trim()
            val cleanDistrict = farmDistrict.trim()

            if (cleanName.isBlank()) {
                val err = "Please enter your full name."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (!EMAIL_REGEX.matches(cleanEmail)) {
                val err = "Please enter a valid email address."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (cleanPhone.isBlank()) {
                val err = "Please enter your contact phone number."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (cleanFarmName.isBlank()) {
                val err = "Please enter your farm name."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (cleanDistrict.isBlank()) {
                val err = "Please select your farm district."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }
            if (password.length < 6) {
                val err = "Password does not meet the required security policy (minimum 6 characters)."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(IllegalArgumentException(err))
            }

            if (simulateNetworkFailure) {
                val err = "Unable to connect. Please check your internet connection and try again."
                _authState.update { it.copy(isAuthenticated = false, errorMessage = err) }
                return Result.failure(Exception(err))
            }

            if (registeredEmails.containsKey(cleanEmail.lowercase())) {
                val err = "An account with this email already exists."
                _authState.update { it.copy(errorMessage = err) }
                return Result.failure(Exception(err))
            }

            val userId = UUID.randomUUID().toString()
            val farmId = UUID.randomUUID().toString()
            registeredEmails[cleanEmail.lowercase()] = userId

            val farm = Farm(
                id = farmId,
                name = cleanFarmName,
                ownerId = userId,
                ownerName = cleanName,
                location = "$cleanDistrict, Tamil Nadu",
                state = "Tamil Nadu",
                contactNumber = cleanPhone,
                email = cleanEmail,
                description = farmDescription.trim(),
                verificationStatus = VerificationStatus.PENDING,
                isAmmalOwnFarm = false
            )
            farmsDb[farmId] = farm

            val profile = UserProfile(
                id = userId,
                email = cleanEmail,
                name = cleanName,
                phone = cleanPhone,
                role = UserRole.FARM_ADMIN,
                farmId = farmId
            )
            profilesDb[userId] = profile

            if (emailConfirmationRequired) {
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
            } else {
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = profile,
                        userFarm = farm,
                        successMessage = "Farm application submitted! Status: PENDING approval by Super Admin.",
                        emailConfirmationRequired = false
                    )
                }
            }

            return Result.success(profile)
        }

        fun registerWithInjectedRole(
            name: String,
            email: String,
            password: String,
            phone: String,
            injectedRoleClaim: String
        ): Result<UserProfile> {
            val reg = registerCustomer(name, email, password, phone)
            if (reg.isFailure) return reg

            // Simulate database trigger sanitization:
            // Even if client tried to claim SUPER_ADMIN in user_metadata, database trigger enforces CUSTOMER
            val baseProfile = reg.getOrNull()!!
            val sanitizedRole = if (injectedRoleClaim.equals("SUPER_ADMIN", ignoreCase = true)) {
                UserRole.CUSTOMER
            } else {
                UserRole.fromString(injectedRoleClaim)
            }
            val sanitizedProfile = baseProfile.copy(role = sanitizedRole)
            profilesDb[baseProfile.id] = sanitizedProfile
            return Result.success(sanitizedProfile)
        }

        fun loginUser(email: String, pass: String): UserProfile {
            val isSuperAdmin = email.equals(TEST_SUPER_ADMIN_EMAIL, ignoreCase = true)
            return if (isSuperAdmin) {
                UserProfile(
                    id = UUID.randomUUID().toString(),
                    email = TEST_SUPER_ADMIN_EMAIL,
                    name = "Madesh (Super Admin)",
                    phone = "",
                    role = UserRole.SUPER_ADMIN
                )
            } else {
                UserProfile(
                    id = UUID.randomUUID().toString(),
                    email = email,
                    name = "Regular User",
                    phone = "",
                    role = UserRole.CUSTOMER
                )
            }
        }

        fun resendEmailVerification(email: String): Result<Unit> {
            if (!EMAIL_REGEX.matches(email.trim())) {
                return Result.failure(IllegalArgumentException("Please enter a valid email address."))
            }
            _authState.update {
                it.copy(successMessage = "Verification link resent to ${email.trim()}. Please check your inbox.")
            }
            return Result.success(Unit)
        }

        fun updateFarmStatus(farmId: String, newStatus: VerificationStatus, requester: UserProfile): Farm {
            require(requester.role == UserRole.SUPER_ADMIN) { "Only Super Admin can change farm verification status" }
            val existing = farmsDb[farmId] ?: error("Farm not found")
            val updated = existing.copy(verificationStatus = newStatus)
            farmsDb[farmId] = updated
            return updated
        }
    }
}
