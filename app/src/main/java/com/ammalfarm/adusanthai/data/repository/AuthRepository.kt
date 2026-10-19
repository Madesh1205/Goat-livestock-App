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
                                val targetCtx = context ?: SupabaseModule.getApplicationContext()
                                if (targetCtx != null) {
                                    com.ammalfarm.adusanthai.core.notification.NotificationConfig.setActiveUser(targetCtx, userId)
                                }
                            }
                        }
                        is SessionStatus.NotAuthenticated -> {
                            val targetCtx = context ?: SupabaseModule.getApplicationContext()
                            val oldUserId = _currentUser.value?.id
                            if (targetCtx != null && !oldUserId.isNullOrBlank()) {
                                com.ammalfarm.adusanthai.core.notification.NotificationConfig.removeTokenOnLogout(targetCtx, oldUserId)
                            }
                            clearLocalCache()
                            _currentUser.value = null
                            _currentFarm.value = null
                            _authState.update { it.copy(isAuthenticated = false, userProfile = null, userFarm = null) }
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

        _authState.update {
            it.copy(
                isAuthenticated = true,
                userProfile = restored,
                userFarm = _currentFarm.value,
                isLoading = SupabaseConfig.isConfigured
            )
        }
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
            _authState.update { it.copy(isLoading = false) }
            return Result.success(null)
        }
        return try {
            try {
                SupabaseModule.auth.awaitInitialization()
            } catch (_: Exception) {}

            val session = SupabaseModule.auth.currentSessionOrNull()
            if (session != null && session.user != null) {
                val profileResult = fetchAndSyncUserProfile(session.user!!.id)
                val profile = profileResult.getOrNull() ?: _currentUser.value
                if (profile != null && profile.isSuspended) {
                    logout()
                    val suspendedMsg = "Your account has been suspended. Please contact platform support."
                    _authState.update { it.copy(isLoading = false, errorMessage = suspendedMsg) }
                    Result.failure(IllegalStateException(suspendedMsg))
                } else if (profile != null) {
                    val targetCtx = context ?: SupabaseModule.getApplicationContext()
                    if (targetCtx != null) {
                        com.ammalfarm.adusanthai.core.notification.NotificationConfig.setActiveUser(targetCtx, profile.id)
                    }
                    _authState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            userProfile = profile,
                            userFarm = _currentFarm.value,
                            errorMessage = null
                        )
                    }
                    Result.success(profile)
                } else {
                    val err = profileResult.exceptionOrNull()
                    val friendly = if (err != null) parseAuthErrorMessage(err) else null
                    clearLocalCache()
                    _currentUser.value = null
                    _currentFarm.value = null
                    _authState.update {
                        it.copy(
                            isAuthenticated = false,
                            userProfile = null,
                            userFarm = null,
                            isLoading = false,
                            errorMessage = friendly
                        )
                    }
                    if (err != null) Result.failure(Exception(friendly ?: "Session restoration failed", err))
                    else Result.success(null)
                }
            } else {
                clearLocalCache()
                _currentUser.value = null
                _currentFarm.value = null
                _authState.update { it.copy(isAuthenticated = false, userProfile = null, userFarm = null, isLoading = false) }
                Result.success(null)
            }
        } catch (e: Exception) {
            val cached = _currentUser.value
            _authState.update {
                it.copy(
                    isLoading = false,
                    isAuthenticated = cached != null,
                    userProfile = cached,
                    userFarm = _currentFarm.value
                )
            }
            Result.success(cached)
        }
    }

    private fun isNetworkError(e: Throwable): Boolean {
        val msg = (e.message ?: "").lowercase()
        return e is java.io.IOException ||
            msg.contains("unable to resolve host") ||
            msg.contains("failed to connect") ||
            msg.contains("unknownhostexception") ||
            msg.contains("sockettimeoutexception") ||
            msg.contains("timed out") ||
            msg.contains("connection refused") ||
            msg.contains("network is unreachable")
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
            var lastNetworkException: Exception? = null
            try {
                val cleanUuid = try { ensureValidUuid(targetId) } catch (_: Exception) { targetId }
                profileDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .select {
                        filter {
                            eq("id", cleanUuid)
                        }
                    }.decodeSingleOrNull<ProfileDto>()
            } catch (idErr: Exception) {
                if (isNetworkError(idErr)) {
                    networkErrorEncountered = true
                    lastNetworkException = idErr
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
                    if (isNetworkError(emailErr)) {
                        networkErrorEncountered = true
                        lastNetworkException = emailErr
                    }
                    Log.w(TAG, "Notice: Querying profile by email ($targetEmail): ${emailErr.message}")
                }
            }

            if (networkErrorEncountered && profileDto == null) {
                val existing = _currentUser.value
                if (existing != null && existing.id == targetId) {
                    return Result.success(existing)
                }
                throw java.io.IOException(
                    "Unable to connect. Please check your internet connection and try again.",
                    lastNetworkException
                )
            }

            val metadataFullName = authUser?.userMetadata?.get("full_name")?.toString()?.replace("\"", "")
                ?: authUser?.userMetadata?.get("name")?.toString()?.replace("\"", "")
            val metadataPhone = authUser?.userMetadata?.get("phone")?.toString()?.replace("\"", "")
            val metadataRoleStr = authUser?.userMetadata?.get("role")?.toString()?.replace("\"", "")

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
                if (resolvedFarm == null && !networkErrorEncountered && !profileDto?.farmId.isNullOrBlank()) {
                    val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("id", profileDto!!.farmId!!) } }
                        .decodeList<FarmDto>()
                    resolvedFarm = farmDtos.firstOrNull()?.toDomain()
                }
                if (resolvedFarm == null && !networkErrorEncountered && profileRoleParsed == UserRole.SUPER_ADMIN) {
                    val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("is_ammal_own_farm", true) } }
                        .decodeList<FarmDto>()
                    resolvedFarm = farmDtos.firstOrNull()?.toDomain()
                }
            } catch (_: Exception) {}

            if (profileDto == null && resolvedFarm == null && metadataFullName.isNullOrBlank() && metadataRoleStr.isNullOrBlank()) {
                throw IllegalStateException("missing_profile: User profile not found in the database.")
            }

            // Never grant SUPER_ADMIN from client metadata or hardcoded emails; derive from trusted database profile.
            val sanitizedMetadataRole = metadataRoleStr?.let {
                val parsed = UserRole.fromString(it)
                if (parsed == UserRole.SUPER_ADMIN) UserRole.CUSTOMER else parsed
            }

            val resolvedRole = when {
                profileRoleParsed != null -> profileRoleParsed
                resolvedFarm?.isAmmalOwnFarm == true -> UserRole.SUPER_ADMIN
                resolvedFarm != null -> UserRole.FARM_ADMIN
                sanitizedMetadataRole != null -> sanitizedMetadataRole
                else -> UserRole.CUSTOMER
            }

            val resolvedName = profileDto?.fullName?.takeIf { it.isNotBlank() }
                ?: metadataFullName?.takeIf { it.isNotBlank() }
                ?: if (targetEmail.isNotBlank()) targetEmail.substringBefore("@").replaceFirstChar { it.uppercase() } else "User"

            val resolvedPhone = profileDto?.phone?.takeIf { it.isNotBlank() }
                ?: metadataPhone?.takeIf { it.isNotBlank() }
                ?: ""

            val resolvedFarmId = if (resolvedRole == UserRole.CUSTOMER) null else (resolvedFarm?.id ?: profileDto?.farmId)

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
            Log.w(TAG, "Error syncing user profile: ${e.message}")
            val existing = _currentUser.value
            if (isNetworkError(e) && existing != null && existing.id == targetId) {
                Result.success(existing)
            } else {
                Result.failure(e)
            }
        }
    }

    override suspend fun login(email: String, password: String): Result<UserProfile> {
        _authState.update { it.copy(isLoading = true, errorMessage = null) }
        val cleanEmail = email.trim().lowercase()

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
            val err = "Backend authentication service is not configured."
            _authState.update { it.copy(isLoading = false, isAuthenticated = false, errorMessage = err) }
            return Result.failure(IllegalStateException(err))
        }

        return try {
            SupabaseModule.auth.signInWith(Email) {
                this.email = cleanEmail
                this.password = password
            }

            val session = SupabaseModule.auth.currentSessionOrNull()
            val userId = session?.user?.id ?: SupabaseModule.auth.currentUserOrNull()?.id
            if (userId.isNullOrBlank()) {
                throw IllegalStateException("Authentication succeeded but no valid user session was returned.")
            }

            val syncResult = fetchAndSyncUserProfile(userId)
            val user = syncResult.getOrElse { syncErr ->
                try { SupabaseModule.auth.signOut() } catch (_: Exception) {}
                clearLocalCache()
                throw syncErr
            }

            if (user.isSuspended) {
                logout()
                val suspendedMsg = "Your account has been suspended. Please contact platform support."
                _authState.update { it.copy(isLoading = false, isAuthenticated = false, errorMessage = suspendedMsg) }
                return Result.failure(IllegalStateException(suspendedMsg))
            }

            _currentUser.value = user
            saveCachedProfile(user)
            val targetCtx = context ?: SupabaseModule.getApplicationContext()
            if (targetCtx != null) {
                com.ammalfarm.adusanthai.core.notification.NotificationConfig.setActiveUser(targetCtx, user.id)
            }
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
            Log.w(TAG, "Login failed: ${e.message}")
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
        val current = _currentUser.value
            ?: return Result.failure(IllegalStateException("No authenticated user session."))
        val cleanName = name.trim()
        val cleanPhone = phone.trim()
        val updated = current.copy(name = cleanName, phone = cleanPhone)

        if (SupabaseConfig.isConfigured && current.id.isNotBlank()) {
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES].update(
                    {
                        set("full_name", cleanName)
                        set("phone", cleanPhone)
                        set("updated_at", currentIsoTimestamp())
                    }
                ) {
                    filter { eq("id", current.id) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error updating remote profile: ${e.message}", e)
                return Result.failure(Exception(UserFriendlyErrorMapper.toUserMessage(e), e))
            }
        }

        _currentUser.value = updated
        saveCachedProfile(updated)
        _authState.update { it.copy(userProfile = updated) }
        return Result.success(updated)
    }

    override suspend fun logout() {
        val targetCtx = context ?: SupabaseModule.getApplicationContext()
        val uid = _currentUser.value?.id
        if (targetCtx != null && !uid.isNullOrBlank()) {
            try {
                com.ammalfarm.adusanthai.core.notification.NotificationConfig.removeTokenOnLogout(targetCtx, uid)
            } catch (e: Exception) {
                Log.w(TAG, "Notice removing token on logout: ${e.message}")
            }
        }
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
        return try {
            if (SupabaseConfig.isConfigured) {
                SupabaseModule.client.postgrest.rpc("delete_user_account")
            }
            logout()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete account: ${e.message}", e)
            val msg = UserFriendlyErrorMapper.toUserMessage(e, "Unable to delete account right now.")
            Result.failure(Exception(msg, e))
        }
    }

    private fun isValidEmail(email: String): Boolean {
        return email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun parseAuthErrorMessage(e: Throwable): String {
        return UserFriendlyErrorMapper.forAuth(e)
    }
}
