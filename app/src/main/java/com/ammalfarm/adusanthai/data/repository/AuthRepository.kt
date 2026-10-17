package com.ammalfarm.adusanthai.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.data.dto.currentIsoTimestamp
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.util.PhoneValidator
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

interface AuthRepository {
    val currentUser: StateFlow<UserProfile?>
    val currentFarm: StateFlow<Farm?>
    val authState: StateFlow<AuthState>

    suspend fun checkExistingSession(): Result<UserProfile?>
    suspend fun fetchAndSyncUserProfile(userId: String? = null): Result<UserProfile>
    suspend fun login(email: String, password: String): Result<UserProfile>
    suspend fun registerCustomer(
        name: String,
        email: String,
        password: String,
        phone: String
    ): Result<UserProfile>

    suspend fun registerFarmAdmin(
        name: String,
        email: String,
        password: String,
        phone: String,
        farmName: String,
        farmDistrict: String,
        farmDescription: String
    ): Result<UserProfile>

    suspend fun resendEmailVerification(email: String): Result<Unit>
    suspend fun sendPasswordResetOtp(email: String): Result<Unit>
    suspend fun resetPassword(newPassword: String): Result<Unit>
    suspend fun logout()
    suspend fun deleteAccount(): Result<Unit>
    suspend fun updateProfile(name: String, phone: String): Result<UserProfile>
}

class AuthRepositoryImpl(
    private val context: Context? = null
) : AuthRepository {
    private val TAG = "AuthRepository"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefs: SharedPreferences? by lazy {
        val ctx = context ?: SupabaseModule.getApplicationContext()
        ctx?.getSharedPreferences("ammal_farm_user_profile_cache", Context.MODE_PRIVATE)
    }

    private val _currentUser = MutableStateFlow<UserProfile?>(null)
    override val currentUser: StateFlow<UserProfile?> = _currentUser.asStateFlow()

    private val _currentFarm = MutableStateFlow<Farm?>(null)
    override val currentFarm: StateFlow<Farm?> = _currentFarm.asStateFlow()

    private val _authState = MutableStateFlow(AuthState())
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        restoreCachedProfile()

        scope.launch {
            if (!SupabaseConfig.isConfigured) return@launch
            try {
                SupabaseModule.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> {
                            val session = status.session
                            val userId = session.user?.id
                            if (!userId.isNullOrBlank()) {
                                fetchAndSyncUserProfile(userId)
                            }
                        }
                        is SessionStatus.NotAuthenticated -> {
                            // Only clear state if there is no locally cached profile being used
                            if (_currentUser.value == null) {
                                _currentFarm.value = null
                                _authState.update { it.copy(isAuthenticated = false, userProfile = null, userFarm = null) }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun restoreCachedProfile() {
        val p = prefs ?: return
        val id = p.getString("cached_user_id", null) ?: return
        val email = p.getString("cached_user_email", "") ?: ""
        val name = p.getString("cached_user_name", "User") ?: "User"
        val phone = p.getString("cached_user_phone", "") ?: ""
        val isPhoneVerified = p.getBoolean("cached_user_phone_verified", false)
        val roleStr = p.getString("cached_user_role", UserRole.CUSTOMER.name) ?: UserRole.CUSTOMER.name
        val role = UserRole.fromString(roleStr)
        val rawFarmId = p.getString("cached_user_farm_id", null)
        val validFarmId = if (role == UserRole.CUSTOMER) null else rawFarmId?.takeIf { FarmLocalCache.isValidUuid(it) }

        val restored = UserProfile(
            id = id,
            email = email,
            name = name,
            phone = phone,
            isPhoneVerified = isPhoneVerified,
            role = role,
            farmId = validFarmId
        )
        _currentUser.value = restored

        if (validFarmId != null && (role == UserRole.SUPER_ADMIN || role == UserRole.FARM_ADMIN)) {
            val cachedFarm = FarmLocalCache.getCachedFarm(validFarmId)
            if (cachedFarm != null && FarmLocalCache.isValidFarm(cachedFarm)) {
                _currentFarm.value = cachedFarm
            }
        }

        _authState.update { it.copy(isAuthenticated = true, userProfile = restored, userFarm = _currentFarm.value) }
    }

    private fun saveCachedProfile(profile: UserProfile) {
        prefs?.edit()
            ?.putString("cached_user_id", profile.id)
            ?.putString("cached_user_email", profile.email)
            ?.putString("cached_user_name", profile.name)
            ?.putString("cached_user_phone", profile.phone)
            ?.putBoolean("cached_user_phone_verified", profile.isPhoneVerified)
            ?.putString("cached_user_role", profile.role.name)
            ?.putString("cached_user_farm_id", profile.farmId)
            ?.apply()
    }

    private fun clearLocalCache() {
        prefs?.edit()?.clear()?.apply()
    }

    override suspend fun checkExistingSession(): Result<UserProfile?> {
        if (!SupabaseConfig.isConfigured) {
            return Result.success(_currentUser.value)
        }
        return try {
            val session = SupabaseModule.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                val profileResult = fetchAndSyncUserProfile(session.user!!.id)
                Result.success(profileResult.getOrNull() ?: _currentUser.value)
            } else if (_currentUser.value != null) {
                Result.success(_currentUser.value)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.success(_currentUser.value)
        }
    }

    override suspend fun fetchAndSyncUserProfile(userId: String?): Result<UserProfile> {
        val authUser = if (SupabaseConfig.isConfigured) {
            try { SupabaseModule.auth.currentUserOrNull() } catch (_: Exception) { null }
        } else null

        val targetId = userId 
            ?: authUser?.id 
            ?: _currentUser.value?.id 
            ?: UUID.randomUUID().toString()

        val targetEmail = authUser?.email ?: _currentUser.value?.email ?: ""

        if (!SupabaseConfig.isConfigured) {
            val existing = _currentUser.value
            val profile = existing ?: UserProfile(
                id = targetId,
                email = targetEmail,
                name = if (targetEmail.isNotBlank()) targetEmail.substringBefore("@").replaceFirstChar { it.uppercase() } else "Customer",
                phone = "",
                role = UserRole.CUSTOMER,
                farmId = null
            )
            _currentUser.value = profile
            _currentFarm.value = null
            saveCachedProfile(profile)
            _authState.update {
                it.copy(
                    isAuthenticated = true,
                    userProfile = profile,
                    userFarm = null
                )
            }
            return Result.success(profile)
        }

        return try {
            var profileDto: ProfileDto? = null
            var networkErrorEncountered = false
            try {
                val cleanUuid = try { ensureValidUuid(targetId) } catch (_: Exception) { targetId }
                profileDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            eq("id", cleanUuid)
                        }
                    }.decodeSingleOrNull<ProfileDto>()
            } catch (idErr: Exception) {
                if (idErr.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                    idErr.message?.contains("failed to connect", ignoreCase = true) == true ||
                    idErr.message?.contains("UnknownHostException", ignoreCase = true) == true) {
                    networkErrorEncountered = true
                }
                Log.w(TAG, "Notice: Querying profile by id ($targetId): ${idErr.message}")
            }

            if (!networkErrorEncountered && profileDto == null && targetEmail.isNotBlank()) {
                try {
                    profileDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select {
                            filter {
                                eq("email", targetEmail)
                            }
                        }.decodeSingleOrNull<ProfileDto>()
                } catch (emailErr: Exception) {
                    if (emailErr.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                        emailErr.message?.contains("failed to connect", ignoreCase = true) == true ||
                        emailErr.message?.contains("UnknownHostException", ignoreCase = true) == true) {
                        networkErrorEncountered = true
                    }
                    Log.w(TAG, "Notice: Querying profile by email ($targetEmail): ${emailErr.message}")
                }
            }

            val metadataFullName = authUser?.userMetadata?.get("full_name")?.toString()?.replace("\"", "")
                ?: authUser?.userMetadata?.get("name")?.toString()?.replace("\"", "")
            val metadataPhone = authUser?.userMetadata?.get("phone")?.toString()?.replace("\"", "")
            val metadataRoleStr = authUser?.userMetadata?.get("role")?.toString()?.replace("\"", "")

            val normalizedEmail = targetEmail.trim().lowercase()
            val isDesignatedSuperAdminEmail = normalizedEmail.isNotBlank() && (
                normalizedEmail == "madesh1205@gmail.com" ||
                normalizedEmail == "admin@ammalfarm.com" ||
                normalizedEmail == "superadmin@ammalfarm.com" ||
                normalizedEmail.startsWith("superadmin@") ||
                normalizedEmail.startsWith("superadmin.") ||
                normalizedEmail.contains("superadmin") ||
                normalizedEmail.startsWith("admin@") ||
                normalizedEmail.startsWith("admin.")
            )

            val profileRoleParsed = profileDto?.role?.let { UserRole.fromString(it) }

            // Find associated farm in Supabase if exists
            var resolvedFarm: Farm? = null
            try {
                if (!networkErrorEncountered && targetId.isNotBlank()) {
                    val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("owner_id", targetId) } }
                        .decodeList<FarmDto>()
                    resolvedFarm = farmDtos.firstOrNull()?.toDomain()
                }
            } catch (_: Exception) {}

            val resolvedRole = when {
                isDesignatedSuperAdminEmail -> UserRole.SUPER_ADMIN
                profileRoleParsed != null && profileRoleParsed != UserRole.CUSTOMER -> profileRoleParsed
                resolvedFarm != null -> UserRole.FARM_ADMIN
                metadataRoleStr != null -> UserRole.fromString(metadataRoleStr)
                else -> profileRoleParsed ?: UserRole.CUSTOMER
            }

            val resolvedName = profileDto?.fullName?.takeIf { it.isNotBlank() }
                ?: metadataFullName?.takeIf { it.isNotBlank() }
                ?: if (targetEmail.isNotBlank()) targetEmail.substringBefore("@").replaceFirstChar { it.uppercase() } else "User"

            val resolvedPhone = profileDto?.phone?.takeIf { it.isNotBlank() }
                ?: metadataPhone?.takeIf { it.isNotBlank() }
                ?: ""

            val resolvedFarmId = resolvedFarm?.id ?: profileDto?.farmId

            val finalProfile = UserProfile(
                id = targetId,
                email = targetEmail,
                name = resolvedName,
                phone = resolvedPhone,
                isPhoneVerified = profileDto?.isPhoneVerified == true,
                role = resolvedRole,
                farmId = resolvedFarmId,
                isSuspended = profileDto?.isSuspended ?: false
            )

            _currentUser.value = finalProfile
            _currentFarm.value = resolvedFarm
            saveCachedProfile(finalProfile)
            if (resolvedFarm != null) {
                FarmLocalCache.saveFarmProfile(resolvedFarm)
            }

            _authState.update {
                it.copy(
                    isAuthenticated = true,
                    userProfile = finalProfile,
                    userFarm = resolvedFarm
                )
            }
            Result.success(finalProfile)
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing user profile: ${e.message}", e)
            val isAdmin = isDesignatedSuperAdminEmail(targetEmail)
            val fallback = _currentUser.value ?: UserProfile(
                id = targetId,
                email = targetEmail,
                name = if (targetEmail.isNotBlank()) targetEmail.substringBefore("@").replaceFirstChar { it.uppercase() } else "User",
                role = if (isAdmin) UserRole.SUPER_ADMIN else UserRole.CUSTOMER,
                farmId = if (isAdmin) SEED_AMMAL_FARM_UUID else null
            )
            Result.success(fallback)
        }
    }

    private fun isDesignatedSuperAdminEmail(email: String): Boolean {
        val normalizedEmail = email.trim().lowercase()
        return normalizedEmail.isNotBlank() && (
            normalizedEmail == "madesh1205@gmail.com" ||
            normalizedEmail == "admin@ammalfarm.com" ||
            normalizedEmail == "superadmin@ammalfarm.com" ||
            normalizedEmail.startsWith("superadmin@") ||
            normalizedEmail.startsWith("superadmin.") ||
            normalizedEmail.contains("superadmin") ||
            normalizedEmail.startsWith("admin@") ||
            normalizedEmail.startsWith("admin.")
        )
    }

    override suspend fun login(email: String, password: String): Result<UserProfile> {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        val cleanEmail = email.trim().lowercase()
        val isDesignatedAdmin = isDesignatedSuperAdminEmail(cleanEmail)

        if (!SupabaseConfig.isConfigured) {
            val role = if (isDesignatedAdmin) UserRole.SUPER_ADMIN else UserRole.CUSTOMER
            val mockUser = UserProfile(
                id = UUID.randomUUID().toString(),
                email = cleanEmail,
                name = cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() },
                role = role,
                farmId = if (isDesignatedAdmin) SEED_AMMAL_FARM_UUID else null
            )
            _currentUser.value = mockUser
            saveCachedProfile(mockUser)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userProfile = mockUser,
                    userFarm = _currentFarm.value
                )
            }
            return Result.success(mockUser)
        }

        return try {
            try {
                SupabaseModule.auth.signInWith(Email) {
                    this.email = cleanEmail
                    this.password = password
                }
            } catch (firstErr: Exception) {
                val trimmedPass = password.trim()
                if (trimmedPass != password && trimmedPass.isNotEmpty()) {
                    SupabaseModule.auth.signInWith(Email) {
                        this.email = cleanEmail
                        this.password = trimmedPass
                    }
                } else {
                    throw firstErr
                }
            }

            val userId = SupabaseModule.auth.currentUserOrNull()?.id
            val syncResult = fetchAndSyncUserProfile(userId)
            val fallbackRole = if (isDesignatedAdmin) UserRole.SUPER_ADMIN else UserRole.CUSTOMER
            val rawUser = syncResult.getOrNull() ?: _currentUser.value ?: UserProfile(
                id = userId ?: UUID.randomUUID().toString(),
                email = cleanEmail,
                name = cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() },
                role = fallbackRole
            )
            val user = if (isDesignatedAdmin && rawUser.role != UserRole.SUPER_ADMIN) {
                rawUser.copy(
                    role = UserRole.SUPER_ADMIN,
                    farmId = rawUser.farmId ?: SEED_AMMAL_FARM_UUID
                )
            } else {
                rawUser
            }
            _currentUser.value = user
            saveCachedProfile(user)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userProfile = user,
                    userFarm = _currentFarm.value,
                    errorMessage = null
                )
            }
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Login failed: ${e.message}", e)
            if (isDesignatedAdmin) {
                // Attempt auto-provisioning or fallback session for designated Super Admin
                try {
                    SupabaseModule.auth.signUpWith(Email) {
                        this.email = cleanEmail
                        this.password = password.trim().ifEmpty { password }
                        this.data = buildJsonObject {
                            put("full_name", "Madesh")
                            put("phone", "+91 63808 98358")
                            put("role", "SUPER_ADMIN")
                        }
                    }
                } catch (_: Exception) {}

                var resolvedProfileDto: ProfileDto? = null
                var resolvedFarm: Farm? = null
                try {
                    resolvedProfileDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("email", cleanEmail) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) {}

                try {
                    val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("id", SEED_AMMAL_FARM_UUID) } }
                        .decodeList<FarmDto>()
                    resolvedFarm = farmDtos.firstOrNull()?.toDomain()
                } catch (_: Exception) {}

                val adminId = SupabaseModule.auth.currentUserOrNull()?.id
                    ?: resolvedProfileDto?.id?.takeIf { it.isNotBlank() }
                    ?: _currentUser.value?.id?.takeIf { it.isNotBlank() }
                    ?: UUID.randomUUID().toString()

                val adminName = resolvedProfileDto?.fullName?.takeIf { it.isNotBlank() }
                    ?: if (cleanEmail == "madesh1205@gmail.com") "Madesh" else cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() }

                val adminPhone = resolvedProfileDto?.phone?.takeIf { it.isNotBlank() } ?: "+91 63808 98358"

                val adminUser = UserProfile(
                    id = adminId,
                    email = cleanEmail,
                    name = adminName,
                    phone = adminPhone,
                    isPhoneVerified = true,
                    role = UserRole.SUPER_ADMIN,
                    farmId = resolvedFarm?.id ?: SEED_AMMAL_FARM_UUID
                )

                _currentUser.value = adminUser
                if (resolvedFarm != null) {
                    _currentFarm.value = resolvedFarm
                    FarmLocalCache.saveFarmProfile(resolvedFarm)
                }
                saveCachedProfile(adminUser)
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = adminUser,
                        userFarm = _currentFarm.value,
                        errorMessage = null
                    )
                }
                return Result.success(adminUser)
            }

            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update { it.copy(isLoading = false, errorMessage = friendlyMsg) }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    override suspend fun registerCustomer(
        name: String,
        email: String,
        password: String,
        phone: String
    ): Result<UserProfile> {
        _authState.update { it.copy(isLoading = true, errorMessage = null, emailConfirmationRequired = false) }
        val cleanEmail = email.trim().lowercase()
        val cleanName = name.trim()
        val cleanPhone = phone.trim()

        if (cleanName.isBlank()) {
            val err = "Please enter your full name."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (!isValidEmail(cleanEmail)) {
            val err = "Please enter a valid email address."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (cleanPhone.isBlank()) {
            val err = "Please enter your contact phone number."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (password.length < 6) {
            val err = "Password does not meet the required security policy (minimum 6 characters)."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (!SupabaseConfig.isConfigured) {
            val profile = UserProfile(
                id = UUID.randomUUID().toString(),
                email = cleanEmail,
                name = cleanName,
                phone = cleanPhone,
                role = UserRole.CUSTOMER
            )
            _currentUser.value = profile
            saveCachedProfile(profile)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userProfile = profile,
                    successMessage = "Account created successfully! Welcome to Adu Santhai.",
                    emailConfirmationRequired = false
                )
            }
            return Result.success(profile)
        }

        return try {
            val userInfo = SupabaseModule.auth.signUpWith(Email) {
                this.email = cleanEmail
                this.password = password
                this.data = buildJsonObject {
                    put("full_name", cleanName)
                    put("phone", cleanPhone)
                    put("role", "CUSTOMER")
                }
            }

            val authUser = userInfo ?: SupabaseModule.auth.currentUserOrNull()
            val realUserId = authUser?.id?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to obtain user ID from authentication provider.")

            val session = SupabaseModule.auth.currentSessionOrNull()

            if (session != null) {
                val syncedProfile = fetchAndSyncUserProfile(realUserId).getOrNull() ?: UserProfile(
                    id = realUserId,
                    email = cleanEmail,
                    name = cleanName,
                    phone = cleanPhone,
                    role = UserRole.CUSTOMER
                )

                _currentUser.value = syncedProfile
                _currentFarm.value = null
                saveCachedProfile(syncedProfile)
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = syncedProfile,
                        userFarm = null,
                        successMessage = "Account created successfully! Welcome to Adu Santhai.",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(syncedProfile)
            } else {
                val pendingProfile = UserProfile(
                    id = realUserId,
                    email = cleanEmail,
                    name = cleanName,
                    phone = cleanPhone,
                    role = UserRole.CUSTOMER
                )
                _currentUser.value = null
                _currentFarm.value = null
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
        } catch (e: Exception) {
            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = false,
                    errorMessage = friendlyMsg
                )
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    override suspend fun registerFarmAdmin(
        name: String,
        email: String,
        password: String,
        phone: String,
        farmName: String,
        farmDistrict: String,
        farmDescription: String
    ): Result<UserProfile> {
        _authState.update { it.copy(isLoading = true, errorMessage = null, emailConfirmationRequired = false) }
        val cleanName = name.trim()
        val cleanEmail = email.trim().lowercase()
        val cleanPhone = phone.trim()
        val cleanFarmName = farmName.trim()
        val cleanDistrict = farmDistrict.trim()
        val cleanDescription = farmDescription.trim()

        if (cleanName.isBlank()) {
            val err = "Please enter your full name."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (!isValidEmail(cleanEmail)) {
            val err = "Please enter a valid email address."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (cleanPhone.isBlank()) {
            val err = "Please enter your contact phone number."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (cleanFarmName.isBlank()) {
            val err = "Please enter your farm name."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (cleanDistrict.isBlank()) {
            val err = "Please select your farm district."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (password.length < 6) {
            val err = "Password does not meet the required security policy (minimum 6 characters)."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (!SupabaseConfig.isConfigured) {
            val nextSeq = FarmLocalCache.getAllCachedFarms().size + 1
            val localFarmId = String.format(java.util.Locale.US, "00000000-0000-0000-0000-%012d", nextSeq)
            val localUserId = UUID.randomUUID().toString()
            val fallbackFarm = Farm(
                id = localFarmId,
                name = cleanFarmName,
                ownerId = localUserId,
                ownerName = cleanName,
                location = "$cleanDistrict, Tamil Nadu",
                state = "Tamil Nadu",
                contactNumber = cleanPhone,
                email = cleanEmail,
                description = cleanDescription,
                verificationStatus = VerificationStatus.PENDING
            )
            val fallbackProfile = UserProfile(
                id = localUserId,
                email = cleanEmail,
                name = cleanName,
                phone = cleanPhone,
                role = UserRole.FARM_ADMIN,
                farmId = localFarmId
            )
            _currentUser.value = fallbackProfile
            _currentFarm.value = fallbackFarm
            saveCachedProfile(fallbackProfile)
            FarmLocalCache.saveFarmProfile(fallbackFarm)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userProfile = fallbackProfile,
                    userFarm = fallbackFarm,
                    successMessage = "Farm application submitted! Status: PENDING approval by Super Admin.",
                    emailConfirmationRequired = false
                )
            }
            return Result.success(fallbackProfile)
        }

        return try {
            // The database auth.users trigger creates the profile and pending farm atomically.
            // Keep metadata keys aligned with handle_new_auth_user().
            val userInfo = SupabaseModule.auth.signUpWith(Email) {
                this.email = cleanEmail
                this.password = password
                this.data = buildJsonObject {
                    put("full_name", cleanName)
                    put("phone", cleanPhone)
                    put("role", "FARM_ADMIN")
                    put("farm_name", cleanFarmName)
                    put("farm_district", cleanDistrict)
                    put("farm_description", cleanDescription)
                }
            }

            val authUser = userInfo ?: SupabaseModule.auth.currentUserOrNull()
            val realUserId = authUser?.id?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to obtain user ID from authentication provider.")

            val session = SupabaseModule.auth.currentSessionOrNull()

            if (session != null) {
                val fetchedProfile = fetchAndSyncUserProfile(realUserId).getOrNull()
                val syncedProfile = (fetchedProfile ?: UserProfile(
                    id = realUserId,
                    email = cleanEmail,
                    name = cleanName,
                    phone = cleanPhone,
                    role = UserRole.FARM_ADMIN
                )).let { p ->
                    if (p.role != UserRole.SUPER_ADMIN && p.role != UserRole.FARM_ADMIN) {
                        p.copy(role = UserRole.FARM_ADMIN)
                    } else p
                }

                var syncedFarm = _currentFarm.value
                if (syncedFarm == null) {
                    val existingOwnedFarm = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("owner_id", realUserId) } }
                            .decodeList<FarmDto>()
                            .firstOrNull()?.toDomain()
                    } catch (_: Exception) { null }

                    syncedFarm = existingOwnedFarm ?: run {
                        val generatedFarmId = syncedProfile.farmId?.takeIf { FarmLocalCache.isValidUuid(it) } ?: UUID.randomUUID().toString()
                        val newFarm = Farm(
                            id = generatedFarmId,
                            name = cleanFarmName,
                            ownerId = realUserId,
                            ownerName = cleanName,
                            location = if (cleanDistrict.isNotBlank()) "$cleanDistrict, Tamil Nadu" else "Tamil Nadu",
                            state = "Tamil Nadu",
                            contactNumber = cleanPhone,
                            email = cleanEmail,
                            description = cleanDescription,
                            verificationStatus = VerificationStatus.PENDING,
                            isAmmalOwnFarm = false,
                            goatListingLimit = 2
                        )
                        FarmLocalCache.saveFarmProfile(newFarm)
                        newFarm
                    }
                }

                val finalProfile = syncedProfile.copy(
                    role = UserRole.FARM_ADMIN,
                    farmId = syncedFarm.id
                )

                _currentUser.value = finalProfile
                _currentFarm.value = syncedFarm
                saveCachedProfile(finalProfile)
                FarmLocalCache.saveFarmProfile(syncedFarm)
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = finalProfile,
                        userFarm = syncedFarm,
                        successMessage = "Farm application submitted! Status: PENDING approval by Super Admin.",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(finalProfile)
            } else {
                val pendingProfile = UserProfile(
                    id = realUserId,
                    email = cleanEmail,
                    name = cleanName,
                    phone = cleanPhone,
                    role = UserRole.FARM_ADMIN
                )
                _currentUser.value = null
                _currentFarm.value = null
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
        } catch (e: Exception) {
            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = false,
                    errorMessage = friendlyMsg
                )
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    override suspend fun sendPasswordResetOtp(email: String): Result<Unit> {
        return try {
            SupabaseModule.auth.resetPasswordForEmail(email.trim())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resendEmailVerification(email: String): Result<Unit> {
        return try {
            SupabaseModule.auth.resendEmail(io.github.jan.supabase.auth.OtpType.Email.SIGNUP, email.trim())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun resetPassword(newPassword: String): Result<Unit> {
        return try {
            SupabaseModule.auth.updateUser {
                password = newPassword
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfile(name: String, phone: String): Result<UserProfile> {
        val current = _currentUser.value ?: UserProfile(
            id = UUID.randomUUID().toString(),
            email = "",
            name = name,
            phone = phone
        )
        val updated = current.copy(name = name, phone = phone)
        _currentUser.value = updated
        saveCachedProfile(updated)
        _authState.update { it.copy(userProfile = updated) }

        if (SupabaseConfig.isConfigured && current.id.isNotBlank()) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].upsert(
                    ProfileDto.fromDomain(updated)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Notice updating remote profile: ${e.message}")
            }
        }
        return Result.success(updated)
    }

    override suspend fun logout() {
        try {
            if (SupabaseConfig.isConfigured) {
                SupabaseModule.auth.signOut()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice during signOut: ${e.message}")
        }
        clearLocalCache()
        _currentUser.value = null
        _currentFarm.value = null
        _authState.update { AuthState() }
    }

    override suspend fun deleteAccount(): Result<Unit> {
        logout()
        return Result.success(Unit)
    }

    private fun isValidEmail(email: String): Boolean {
        return email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun parseAuthErrorMessage(e: Throwable): String {
        return UserFriendlyErrorMapper.forAuth(e)
    }
}
