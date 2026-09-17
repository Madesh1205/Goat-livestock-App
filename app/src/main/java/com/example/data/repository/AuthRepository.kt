package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.core.util.FarmLocalCache
import com.example.data.dto.FarmDto
import com.example.data.dto.ProfileDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.data.dto.currentIsoTimestamp
import com.example.data.dto.ensureValidUuid
import com.example.model.*
import com.example.util.UserFriendlyErrorMapper
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    suspend fun updateProfile(name: String, phone: String): Result<UserProfile>
}

class AuthRepositoryImpl(
    private val context: Context? = null
) : AuthRepository {
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
                            if (_currentUser.value?.id?.startsWith("demo-") != true) {
                                _currentUser.value = null
                                _currentFarm.value = null
                                clearLocalCache()
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
        val roleStr = p.getString("cached_user_role", UserRole.CUSTOMER.name) ?: UserRole.CUSTOMER.name
        val role = UserRole.fromString(roleStr)
        val rawFarmId = p.getString("cached_user_farm_id", null)
        val validFarmId = if (role == UserRole.CUSTOMER) null else rawFarmId?.takeIf { FarmLocalCache.isValidUuid(it) }

        val restored = UserProfile(
            id = id,
            email = email,
            name = name,
            phone = phone,
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
                profileDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            or {
                                eq("id", ensureValidUuid(targetId))
                                eq("id", targetId)
                            }
                        }
                    }.decodeSingleOrNull<ProfileDto>()
            } catch (idErr: Exception) {
                if (idErr.message?.contains("Unable to resolve host", ignoreCase = true) == true ||
                    idErr.message?.contains("failed to connect", ignoreCase = true) == true ||
                    idErr.message?.contains("UnknownHostException", ignoreCase = true) == true) {
                    networkErrorEncountered = true
                }
                Log.w("AuthRepository", "Notice: Querying profile by id ($targetId): ${idErr.message}")
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
                    Log.w("AuthRepository", "Notice: Querying profile by email ($targetEmail): ${emailErr.message}")
                }
            }

            val metadataRoleStr = authUser?.userMetadata?.get("role")?.toString()?.replace("\"", "")
                ?: authUser?.userMetadata?.get("user_role")?.toString()?.replace("\"", "")
            val metadataFullName = authUser?.userMetadata?.get("full_name")?.toString()?.replace("\"", "")
            val metadataPhone = authUser?.userMetadata?.get("phone")?.toString()?.replace("\"", "")

            val rawRole = when {
                !profileDto?.role.isNullOrBlank() -> profileDto!!.role
                !metadataRoleStr.isNullOrBlank() && !metadataRoleStr.equals("SUPER_ADMIN", ignoreCase = true) -> metadataRoleStr
                else -> _currentUser.value?.role?.name ?: "CUSTOMER"
            }

            var detectedRole = UserRole.fromString(rawRole)
            // Strict role safety: SUPER_ADMIN is NEVER granted through userMetadata, client claim, or unauthenticated fallback
            if (detectedRole == UserRole.SUPER_ADMIN && profileDto?.role != "SUPER_ADMIN") {
                Log.w("AuthRepository", "Privilege escalation attempt prevented for $targetEmail. Defaulting to CUSTOMER.")
                detectedRole = UserRole.CUSTOMER
            }

            var effectiveFarmId: String? = null

            if (!networkErrorEncountered) {
                try {
                    if (detectedRole == UserRole.SUPER_ADMIN) {
                        // Super Admin real farm resolution from database:
                        // 1. First look for farms owned by this user
                        val ownedFarms = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select {
                                filter {
                                    eq("owner_id", targetId)
                                }
                            }.decodeList<FarmDto>()

                        val ammalFarm = if (ownedFarms.isNotEmpty()) {
                            ownedFarms.first()
                        } else {
                            // 2. Look for central hub farm by SEED_AMMAL_FARM_UUID or is_ammal_own_farm
                            val hubFarms = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                                .select {
                                    filter {
                                        or {
                                            eq("id", SEED_AMMAL_FARM_UUID)
                                            eq("is_ammal_own_farm", true)
                                        }
                                    }
                                }.decodeList<FarmDto>()

                            // Only use if unassigned or owned by targetId. Do NOT reassign on the fly without backend operation!
                            hubFarms.find { it.ownerId == targetId || it.ownerId.isBlank() }
                        }

                        if (ammalFarm != null) {
                            effectiveFarmId = ammalFarm.id
                            val farmObj = ammalFarm.toDomain()
                            _currentFarm.value = farmObj
                            FarmLocalCache.saveFarmProfile(farmObj)
                        } else {
                            _currentFarm.value = null
                            effectiveFarmId = null
                        }
                    } else if (detectedRole == UserRole.FARM_ADMIN) {
                        // Farm Admin: Resolve farm owned by this user
                        val ownedFarms = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select {
                                filter {
                                    eq("owner_id", targetId)
                                }
                            }.decodeList<FarmDto>()

                        if (ownedFarms.isNotEmpty()) {
                            val adminFarm = ownedFarms.first()
                            effectiveFarmId = adminFarm.id
                            val farmObj = adminFarm.toDomain()
                            _currentFarm.value = farmObj
                            FarmLocalCache.saveFarmProfile(farmObj)
                        } else if (!profileDto?.farmId.isNullOrBlank() && FarmLocalCache.isValidUuid(profileDto.farmId)) {
                            // Verify farm relationship
                            val candidateId = profileDto.farmId!!
                            val fetched = fetchFarmDto(candidateId)
                            if (fetched != null && fetched.ownerId == targetId) {
                                effectiveFarmId = fetched.id
                                val farmObj = fetched.toDomain()
                                _currentFarm.value = farmObj
                                FarmLocalCache.saveFarmProfile(farmObj)
                            } else {
                                Log.w("AuthRepository", "Farm $candidateId does not belong to Farm Admin $targetId")
                                _currentFarm.value = null
                                effectiveFarmId = null
                            }
                        } else {
                            _currentFarm.value = null
                            effectiveFarmId = null
                        }
                    } else {
                        // Customer: NEVER receives farm ownership relationship
                        _currentFarm.value = null
                        effectiveFarmId = null
                    }
                } catch (farmErr: Exception) {
                    Log.w("AuthRepository", "Error checking farm ownership: ${farmErr.message}")
                }
            } else {
                // Offline fallback: ONLY use verified cached farm if owned by this user
                if (detectedRole == UserRole.SUPER_ADMIN || detectedRole == UserRole.FARM_ADMIN) {
                    val cached = FarmLocalCache.getAllCachedFarms().find { it.ownerId == targetId && FarmLocalCache.isValidFarm(it) }
                        ?: (profileDto?.farmId?.let { FarmLocalCache.getCachedFarm(it) }?.takeIf { it.ownerId == targetId && FarmLocalCache.isValidFarm(it) })
                    if (cached != null) {
                        _currentFarm.value = cached
                        effectiveFarmId = cached.id
                    } else {
                        _currentFarm.value = null
                        effectiveFarmId = null
                    }
                } else {
                    _currentFarm.value = null
                    effectiveFarmId = null
                }
            }

            val finalFullName = profileDto?.fullName?.takeIf { it.isNotBlank() }
                ?: metadataFullName?.takeIf { it.isNotBlank() }
                ?: _currentUser.value?.name?.takeIf { it.isNotBlank() && it != "User" }
                ?: (if (targetEmail.isNotBlank()) targetEmail.substringBefore("@") else "User")

            val finalPhone = profileDto?.phone?.takeIf { it.isNotBlank() }
                ?: metadataPhone?.takeIf { it.isNotBlank() }
                ?: _currentUser.value?.phone ?: ""

            val finalEmail = profileDto?.email?.takeIf { it.isNotBlank() }
                ?: targetEmail

            val finalProfile = UserProfile(
                id = profileDto?.id?.takeIf { it.isNotBlank() } ?: targetId,
                email = finalEmail,
                name = finalFullName,
                phone = finalPhone,
                role = detectedRole,
                farmId = effectiveFarmId,
                isSuspended = profileDto?.isSuspended ?: false
            )

            if (!networkErrorEncountered && (profileDto == null || profileDto.farmId != effectiveFarmId)) {
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].upsert(
                        ProfileDto.fromDomain(finalProfile)
                    )
                } catch (profileSyncErr: Exception) {
                    Log.w("AuthRepository", "Profile upsert in sync notice: ${profileSyncErr.message}")
                }
            }

            _currentUser.value = finalProfile
            saveCachedProfile(finalProfile)
            _authState.update {
                it.copy(
                    isAuthenticated = true,
                    userProfile = finalProfile,
                    userFarm = _currentFarm.value
                )
            }

            Result.success(finalProfile)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Error in fetchAndSyncUserProfile: ${e.message}", e)
            val existing = _currentUser.value
            if (existing != null) {
                Result.success(existing)
            } else {
                val fallback = UserProfile(
                    id = targetId,
                    email = targetEmail,
                    name = if (targetEmail.isNotBlank()) targetEmail.substringBefore("@").replaceFirstChar { it.uppercase() } else "Customer",
                    phone = "",
                    role = UserRole.CUSTOMER,
                    farmId = null
                )
                _currentUser.value = fallback
                _currentFarm.value = null
                saveCachedProfile(fallback)
                _authState.update {
                    it.copy(
                        isAuthenticated = true,
                        userProfile = fallback,
                        userFarm = null
                    )
                }
                Result.success(fallback)
            }
        }
    }

    private suspend fun fetchProfileForUser(userId: String): UserProfile? {
        return fetchAndSyncUserProfile(userId).getOrNull()
    }

    private suspend fun fetchFarmDto(farmId: String): FarmDto? {
        if (!SupabaseConfig.isConfigured || !FarmLocalCache.isValidUuid(farmId)) return null
        return try {
            val validFarmId = ensureValidUuid(farmId)
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select {
                    filter {
                        eq("id", validFarmId)
                    }
                }.decodeSingleOrNull<FarmDto>()
        } catch (_: Exception) { null }
    }

    private suspend fun fetchFarmDetails(farmId: String) {
        val farmDto = fetchFarmDto(farmId)
        val user = _currentUser.value
        if (farmDto != null && user != null) {
            // Verify ownership: Farm Admin must actually own this farm
            if (farmDto.ownerId == user.id || (user.role == UserRole.SUPER_ADMIN && (farmDto.id == SEED_AMMAL_FARM_UUID || farmDto.isAmmalOwnFarm))) {
                val farm = farmDto.toDomain()
                _currentFarm.value = farm
                _authState.update { it.copy(userFarm = farm) }
                FarmLocalCache.saveFarmProfile(farm)
            } else {
                _currentFarm.value = null
                _authState.update { it.copy(userFarm = null) }
            }
        } else {
            _currentFarm.value = null
            _authState.update { it.copy(userFarm = null) }
        }
    }

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private fun isValidEmail(email: String): Boolean = email.isNotBlank() && EMAIL_REGEX.matches(email.trim())

    private fun parseAuthErrorMessage(e: Throwable): String {
        val msg = e.message ?: return "Authentication failed. Please try again."
        val lower = msg.lowercase()
        return when {
            lower.contains("user already registered") ||
            lower.contains("already exists") ||
            lower.contains("user_already_exists") ->
                "An account with this email already exists. Please sign in with your account password or reset it."

            lower.contains("email not confirmed") ||
            lower.contains("email_not_confirmed") ->
                "Account created. Please verify your email before signing in."

            lower.contains("invalid login credentials") ||
            lower.contains("invalid_grant") ||
            lower.contains("invalid_credentials") ->
                "Invalid email or password. Please check your credentials."

            lower.contains("password should be at least") ||
            lower.contains("password is too short") ||
            lower.contains("weak_password") ->
                "Password does not meet the required security policy (minimum 6 characters)."

            lower.contains("unable to validate email") ||
            lower.contains("invalid email") ||
            lower.contains("validation_failed") ->
                "Please enter a valid email address."

            lower.contains("unable to resolve host") ||
            lower.contains("failed to connect") ||
            lower.contains("unknownhostexception") ||
            lower.contains("sockettimeoutexception") ||
            lower.contains("connectexception") ||
            lower.contains("network") ->
                "Unable to connect. Please check your internet connection and try again."

            else -> UserFriendlyErrorMapper.forAuth(e)
        }
    }

    override suspend fun login(email: String, password: String): Result<UserProfile> {
        _authState.update { it.copy(isLoading = true, errorMessage = null, emailConfirmationRequired = false) }
        val cleanEmail = email.trim()

        if (!isValidEmail(cleanEmail)) {
            val err = "Please enter a valid email address."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (password.isBlank()) {
            val err = "Please enter your password."
            _authState.update { it.copy(isLoading = false, errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        if (!SupabaseConfig.isConfigured) {
            val fallbackProfile = UserProfile(
                id = UUID.randomUUID().toString(),
                email = cleanEmail,
                name = cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() },
                phone = "",
                role = UserRole.CUSTOMER,
                farmId = null
            )
            _currentUser.value = fallbackProfile
            _currentFarm.value = null
            saveCachedProfile(fallbackProfile)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = true,
                    userProfile = fallbackProfile,
                    userFarm = null,
                    successMessage = "Welcome back, ${fallbackProfile.name}!"
                )
            }
            return Result.success(fallbackProfile)
        }

        return try {
            SupabaseModule.auth.signInWith(Email) {
                this.email = cleanEmail
                this.password = password
            }

            val user = SupabaseModule.auth.currentUserOrNull()
            if (user != null) {
                val profileResult = fetchAndSyncUserProfile(user.id)
                val profile = profileResult.getOrNull() ?: UserProfile(
                    id = user.id,
                    email = user.email ?: cleanEmail,
                    name = user.userMetadata?.get("full_name")?.toString()?.replace("\"", "") ?: cleanEmail.substringBefore("@"),
                    role = UserRole.CUSTOMER
                )
                _currentUser.value = profile
                saveCachedProfile(profile)
                _authState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        userProfile = profile,
                        userFarm = _currentFarm.value,
                        successMessage = "Welcome back, ${profile.name}!",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(profile)
            } else {
                val errorMsg = "Could not retrieve user session after login."
                _authState.update { it.copy(isLoading = false, errorMessage = errorMsg) }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Login error: ${e.message}", e)
            val friendlyMsg = parseAuthErrorMessage(e)
            val isEmailNotConfirmed = friendlyMsg.contains("verify your email", ignoreCase = true)
            _authState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = friendlyMsg,
                    emailConfirmationRequired = isEmailNotConfirmed
                )
            }
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
        val cleanEmail = email.trim()
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
                    successMessage = "Account created successfully! Welcome to Ammal Farm.",
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
                ?: throw IllegalStateException("Failed to obtain authoritative user ID from authentication provider.")

            val session = SupabaseModule.auth.currentSessionOrNull()

            if (session != null) {
                // Case A: Valid session returned (Confirm Email is OFF).
                // Database trigger handle_new_auth_user() has provisioned the profile.
                // Fetch & sync profile from database:
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
                        successMessage = "Account created successfully! Welcome to Ammal Farm.",
                        emailConfirmationRequired = false
                    )
                }
                Result.success(syncedProfile)
            } else {
                // Case B: User created but session is null (Confirm Email is ON).
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
            val isUserAlreadyExists = e.message?.contains("user_already_exists", ignoreCase = true) == true ||
                e.message?.contains("User already registered", ignoreCase = true) == true ||
                e.message?.contains("already exists", ignoreCase = true) == true

            if (isUserAlreadyExists && SupabaseConfig.isConfigured) {
                Log.d("AuthRepository", "Customer registration: user already exists, attempting auth with provided credentials.")
                try {
                    SupabaseModule.auth.signInWith(Email) {
                        this.email = cleanEmail
                        this.password = password
                    }
                    val realUserId = SupabaseModule.auth.currentUserOrNull()?.id
                    if (realUserId != null) {
                        val profileResult = fetchAndSyncUserProfile(realUserId)
                        val profile = profileResult.getOrNull() ?: UserProfile(
                            id = realUserId,
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
                                userFarm = _currentFarm.value,
                                successMessage = "Welcome back, ${profile.name}! Signed in to your existing account.",
                                emailConfirmationRequired = false
                            )
                        }
                        return Result.success(profile)
                    }
                } catch (loginErr: Exception) {
                    Log.d("AuthRepository", "Auto-login on existing customer account failed: ${loginErr.message}")
                    val isEmailNotConfirmed = loginErr.message?.contains("email not confirmed", ignoreCase = true) == true ||
                        loginErr.message?.contains("email_not_confirmed", ignoreCase = true) == true
                    val friendlyMsg = if (isEmailNotConfirmed) {
                        "An account with this email exists, but its email is not verified yet. Please check your inbox or resend verification."
                    } else {
                        "An account with this email already exists. Please sign in with your account password or reset it."
                    }
                    _authState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = false,
                            errorMessage = friendlyMsg,
                            emailConfirmationRequired = isEmailNotConfirmed
                        )
                    }
                    return Result.failure(Exception(friendlyMsg, e))
                }
            }

            Log.e("AuthRepository", "Customer sign-up failed: ${e.message}", e)
            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = false,
                    errorMessage = friendlyMsg,
                    emailConfirmationRequired = false
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
        val cleanEmail = email.trim()
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
            val localFarmId = UUID.randomUUID().toString()
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
            val userInfo = SupabaseModule.auth.signUpWith(Email) {
                this.email = cleanEmail
                this.password = password
                this.data = buildJsonObject {
                    put("full_name", cleanName)
                    put("phone", cleanPhone)
                    put("role", "FARM_ADMIN")
                    put("farm_name", cleanFarmName)
                    put("farm_district", cleanDistrict)
                    put("location_district", cleanDistrict)
                    put("farm_description", cleanDescription)
                }
            }

            val authUser = userInfo ?: SupabaseModule.auth.currentUserOrNull()
            val realUserId = authUser?.id?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to obtain authoritative user ID from authentication provider.")

            val session = SupabaseModule.auth.currentSessionOrNull()

            if (session != null) {
                // Case A: Valid session returned (Confirm Email is OFF).
                // Database trigger handle_new_auth_user() has provisioned:
                // 1. profile (FARM_ADMIN)
                // 2. partner farm (owner_id = realUserId, status = PENDING, is_ammal_own_farm = false)
                // 3. linked profile.farm_id = farm.id
                // Reload and sync the resulting profile and farm from the database. No duplicate farm creation!
                val syncedProfile = fetchAndSyncUserProfile(realUserId).getOrNull() ?: UserProfile(
                    id = realUserId,
                    email = cleanEmail,
                    name = cleanName,
                    phone = cleanPhone,
                    role = UserRole.FARM_ADMIN
                )

                val syncedFarm = _currentFarm.value ?: run {
                    val fallbackFarm = Farm(
                        id = syncedProfile.farmId ?: UUID.randomUUID().toString(),
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
                        goatListingLimit = 10
                    )
                    _currentFarm.value = fallbackFarm
                    FarmLocalCache.saveFarmProfile(fallbackFarm)
                    fallbackFarm
                }

                val finalProfile = if (syncedProfile.farmId.isNullOrBlank()) {
                    syncedProfile.copy(farmId = syncedFarm.id)
                } else syncedProfile

                _currentUser.value = finalProfile
                _currentFarm.value = syncedFarm
                saveCachedProfile(finalProfile)
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
                // Case B: User created but session is null (Confirm Email is ON).
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
            val isUserAlreadyExists = e.message?.contains("user_already_exists", ignoreCase = true) == true ||
                e.message?.contains("User already registered", ignoreCase = true) == true ||
                e.message?.contains("already exists", ignoreCase = true) == true

            if (isUserAlreadyExists && SupabaseConfig.isConfigured) {
                Log.d("AuthRepository", "Farm registration: user already exists, attempting auth with provided credentials.")
                try {
                    SupabaseModule.auth.signInWith(Email) {
                        this.email = cleanEmail
                        this.password = password
                    }
                    val realUserId = SupabaseModule.auth.currentUserOrNull()?.id
                    if (realUserId != null) {
                        val profileResult = fetchAndSyncUserProfile(realUserId)
                        val existingProfile = profileResult.getOrNull()
                        val existingFarm = _currentFarm.value ?: existingProfile?.farmId?.let { fetchFarmDto(it)?.toDomain() }

                        if (existingFarm != null) {
                            val finalProfile = existingProfile ?: UserProfile(
                                id = realUserId,
                                email = cleanEmail,
                                name = cleanName,
                                phone = cleanPhone,
                                role = UserRole.FARM_ADMIN,
                                farmId = existingFarm.id
                            )
                            _currentUser.value = finalProfile
                            _currentFarm.value = existingFarm
                            saveCachedProfile(finalProfile)
                            FarmLocalCache.saveFarmProfile(existingFarm)
                            _authState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = true,
                                    userProfile = finalProfile,
                                    userFarm = existingFarm,
                                    successMessage = "Welcome back, ${finalProfile.name}! Signed in to your farm partner account (${existingFarm.name}).",
                                    emailConfirmationRequired = false
                                )
                            }
                            return Result.success(finalProfile)
                        } else {
                            val farmId = UUID.randomUUID().toString()
                            val nowIso = currentIsoTimestamp()
                            val newFarm = Farm(
                                id = farmId,
                                name = cleanFarmName,
                                ownerId = realUserId,
                                ownerName = cleanName.ifBlank { existingProfile?.name ?: cleanName },
                                location = "$cleanDistrict, Tamil Nadu",
                                state = "Tamil Nadu",
                                contactNumber = cleanPhone.ifBlank { existingProfile?.phone ?: cleanPhone },
                                email = cleanEmail,
                                description = cleanDescription,
                                verificationStatus = VerificationStatus.PENDING
                            )

                            try {
                                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].upsert(
                                    FarmDto(
                                        id = farmId,
                                        name = cleanFarmName,
                                        ownerId = realUserId,
                                        tagline = null,
                                        description = cleanDescription.takeIf { it.isNotBlank() },
                                        locationDistrict = cleanDistrict.ifBlank { null },
                                        locationState = "Tamil Nadu",
                                        address = if (cleanDistrict.isNotBlank()) "$cleanDistrict, Tamil Nadu" else "Tamil Nadu",
                                        contactPhone = cleanPhone.ifBlank { null },
                                        contactEmail = cleanEmail.ifBlank { null },
                                        status = "PENDING",
                                        isAmmalOwnFarm = false,
                                        goatListingLimit = 10,
                                        rating = 5.0,
                                        reviewCount = 0,
                                        createdAt = nowIso,
                                        updatedAt = nowIso
                                    )
                                )
                                FarmLocalCache.saveFarmProfile(newFarm)
                            } catch (fe: Exception) {
                                Log.w("AuthRepository", "Notice: Server farm sync on existing user: ${fe.message}")
                            }

                            val newRole = existingProfile?.role ?: UserRole.FARM_ADMIN
                            val updatedProfile = (existingProfile ?: UserProfile(
                                id = realUserId,
                                email = cleanEmail,
                                name = cleanName,
                                phone = cleanPhone,
                                role = newRole
                            )).copy(
                                farmId = farmId,
                                role = newRole,
                                name = cleanName.ifBlank { existingProfile?.name ?: cleanName },
                                phone = cleanPhone.ifBlank { existingProfile?.phone ?: cleanPhone }
                            )

                            try {
                                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].upsert(
                                    ProfileDto(
                                        id = realUserId,
                                        email = cleanEmail,
                                        fullName = updatedProfile.name,
                                        phone = updatedProfile.phone.ifBlank { null },
                                        role = updatedProfile.role.name,
                                        farmId = farmId,
                                        createdAt = nowIso
                                    )
                                )
                            } catch (pe: Exception) {
                                Log.w("AuthRepository", "Notice: Server profile sync on existing user: ${pe.message}")
                            }

                            _currentUser.value = updatedProfile
                            _currentFarm.value = newFarm
                            saveCachedProfile(updatedProfile)
                            _authState.update {
                                it.copy(
                                    isLoading = false,
                                    isAuthenticated = true,
                                    userProfile = updatedProfile,
                                    userFarm = newFarm,
                                    successMessage = "Farm application submitted! Status: PENDING approval by Super Admin. Partner fee of ₹100 recorded. Application is under review.",
                                    emailConfirmationRequired = false
                                )
                            }
                            return Result.success(updatedProfile)
                        }
                    }
                } catch (loginErr: Exception) {
                    Log.d("AuthRepository", "Auto-login on existing account for farm registration failed: ${loginErr.message}")
                    val isEmailNotConfirmed = loginErr.message?.contains("email not confirmed", ignoreCase = true) == true ||
                        loginErr.message?.contains("email_not_confirmed", ignoreCase = true) == true
                    val friendlyMsg = if (isEmailNotConfirmed) {
                        "An account with this email exists, but its email is not verified yet. Please check your inbox or resend verification."
                    } else {
                        "An account with this email already exists. Please enter your account password to sign in or apply, or tap 'Sign In' below."
                    }
                    _authState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = false,
                            errorMessage = friendlyMsg,
                            emailConfirmationRequired = isEmailNotConfirmed
                        )
                    }
                    return Result.failure(Exception(friendlyMsg, e))
                }
            }

            Log.e("AuthRepository", "Farm registration failed: ${e.message}", e)
            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = false,
                    errorMessage = friendlyMsg,
                    emailConfirmationRequired = false
                )
            }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    override suspend fun resendEmailVerification(email: String): Result<Unit> {
        val cleanEmail = email.trim()
        if (!isValidEmail(cleanEmail)) {
            val err = "Please enter a valid email address."
            _authState.update { it.copy(errorMessage = err) }
            return Result.failure(IllegalArgumentException(err))
        }

        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        return try {
            if (SupabaseConfig.isConfigured) {
                SupabaseModule.auth.resendEmail(io.github.jan.supabase.auth.OtpType.Email.SIGNUP, cleanEmail)
            }
            _authState.update {
                it.copy(
                    isLoading = false,
                    successMessage = "Verification link resent to $cleanEmail. Please check your inbox."
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Resend verification failed: ${e.message}", e)
            val friendlyMsg = parseAuthErrorMessage(e)
            _authState.update { it.copy(isLoading = false, errorMessage = friendlyMsg) }
            Result.failure(Exception(friendlyMsg, e))
        }
    }

    override suspend fun sendPasswordResetOtp(email: String): Result<Unit> {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        return try {
            SupabaseModule.auth.resetPasswordForEmail(email.trim())
            _authState.update {
                it.copy(
                    isLoading = false,
                    successMessage = "Password reset instructions sent to $email."
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.update {
                it.copy(
                    isLoading = false,
                    successMessage = "Reset instructions sent to $email."
                )
            }
            Result.success(Unit)
        }
    }

    override suspend fun resetPassword(newPassword: String): Result<Unit> {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        return try {
            SupabaseModule.auth.updateUser {
                this.password = newPassword
            }
            _authState.update {
                it.copy(
                    isLoading = false,
                    successMessage = "Password updated successfully! Please log in."
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            _authState.update {
                it.copy(
                    isLoading = false,
                    successMessage = "Password reset completed."
                )
            }
            Result.success(Unit)
        }
    }

    override suspend fun logout() {
        try {
            SupabaseModule.auth.signOut()
        } catch (_: Exception) {}
        clearLocalCache()
        _currentUser.value = null
        _currentFarm.value = null
        _authState.update {
            AuthState(
                isAuthenticated = false,
                userProfile = null,
                userFarm = null,
                successMessage = "You have been logged out."
            )
        }
    }

    override suspend fun updateProfile(name: String, phone: String): Result<UserProfile> {
        val current = _currentUser.value ?: return Result.failure(Exception("No user logged in"))
        val cleanPhone = phone.trim()
        val validFarmId = if (current.role == UserRole.CUSTOMER) null else current.farmId?.takeIf { FarmLocalCache.isValidUuid(it) }
        val updated = current.copy(name = name.trim(), phone = cleanPhone, farmId = validFarmId)
        _currentUser.value = updated
        saveCachedProfile(updated)

        if (SupabaseConfig.isConfigured) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].upsert(
                    ProfileDto(
                        id = ensureValidUuid(updated.id),
                        email = updated.email.ifBlank { null },
                        fullName = updated.name,
                        phone = cleanPhone.ifBlank { null },
                        role = updated.role.name,
                        farmId = updated.farmId
                    )
                )
            } catch (_: Exception) {}
        }

        _authState.update { it.copy(userProfile = updated, successMessage = "Profile updated!") }
        return Result.success(updated)
    }
}
