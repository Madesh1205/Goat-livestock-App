package com.ammalfarm.adusanthai.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.model.*
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AuthRepository(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ammal_auth_prefs", Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    suspend fun checkExistingSession() {
        try {
            val session = SupabaseModule.auth.currentSessionOrNull()
            if (session != null) {
                val userId = session.user?.id ?: ""
                val email = session.user?.email ?: ""
                val roleStr = prefs.getString("cached_user_role", "CUSTOMER") ?: "CUSTOMER"
                val role = try { UserRole.valueOf(roleStr) } catch (e: Exception) { UserRole.CUSTOMER }

                val profile = UserProfile(
                    id = userId,
                    name = session.user?.userMetadata?.get("name")?.toString() ?: "User",
                    email = email,
                    role = role
                )
                _authState.update {
                    it.copy(
                        isAuthenticated = true,
                        userProfile = profile
                    )
                }
            }
        } catch (e: Exception) {
        }
    }

    suspend fun login(email: String, pass: String): Result<UserProfile> {
        return try {
            SupabaseModule.auth.signInWith(Email) {
                this.email = email
                this.password = pass
            }
            val session = SupabaseModule.auth.currentSessionOrNull()
            val userId = session?.user?.id ?: ""
            val userEmail = session?.user?.email ?: email
            val roleStr = prefs.getString("cached_user_role", "CUSTOMER") ?: "CUSTOMER"
            val role = try { UserRole.valueOf(roleStr) } catch (e: Exception) { UserRole.CUSTOMER }

            val profile = UserProfile(
                id = userId,
                name = session?.user?.userMetadata?.get("name")?.toString() ?: "User",
                email = userEmail,
                role = role
            )
            prefs.edit().putString("cached_user_role", role.name).apply()

            _authState.update {
                it.copy(
                    isAuthenticated = true,
                    userProfile = profile
                )
            }
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerCustomer(name: String, email: String, pass: String, phone: String): Result<UserProfile> {
        return try {
            prefs.edit().putString("cached_user_role", UserRole.CUSTOMER.name).apply()
            SupabaseModule.auth.signUpWith(Email) {
                this.email = email
                this.password = pass
                data = kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name), "role" to kotlinx.serialization.json.JsonPrimitive("CUSTOMER")))
            }
            val profile = UserProfile(name = name, email = email, phone = phone, role = UserRole.CUSTOMER)
            _authState.update { it.copy(userProfile = profile) }
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun registerFarmAdmin(
        name: String,
        email: String,
        password: String,
        phone: String,
        farmName: String,
        farmDistrict: String,
        farmDescription: String
    ): Result<UserProfile> {
        return try {
            prefs.edit().putString("cached_user_role", UserRole.FARM_ADMIN.name).apply()
            SupabaseModule.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = kotlinx.serialization.json.JsonObject(mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name), "role" to kotlinx.serialization.json.JsonPrimitive("FARM_ADMIN")))
            }
            val profile = UserProfile(name = name, email = email, phone = phone, role = UserRole.FARM_ADMIN)
            val farm = Farm(name = farmName, location = farmDistrict, description = farmDescription)
            _authState.update { it.copy(userProfile = profile, userFarm = farm) }
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordResetOtp(email: String): Result<Unit> {
        return try {
            SupabaseModule.auth.resetPasswordForEmail(email)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resendEmailVerification(email: String): Result<Unit> {
        return try {
            SupabaseModule.auth.resendEmail(io.github.jan.supabase.auth.OtpType.Email.SIGNUP, email)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resetPassword(newPassword: String): Result<Unit> {
        return try {
            SupabaseModule.auth.updateUser {
                password = newPassword
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateProfile(name: String, phone: String): Result<Unit> {
        val current = _authState.value.userProfile ?: UserProfile()
        val updated = current.copy(name = name, phone = phone)
        _authState.update { it.copy(userProfile = updated) }
        return Result.success(Unit)
    }

    suspend fun updateProfile(profile: UserProfile): Result<Unit> {
        _authState.update { it.copy(userProfile = profile) }
        return Result.success(Unit)
    }

    suspend fun logout() {
        try {
            SupabaseModule.auth.signOut()
        } catch (e: Exception) {}
        prefs.edit().clear().apply()
        _authState.update { AuthState() }
    }

    suspend fun deleteAccount(): Result<Unit> {
        logout()
        return Result.success(Unit)
    }
}
