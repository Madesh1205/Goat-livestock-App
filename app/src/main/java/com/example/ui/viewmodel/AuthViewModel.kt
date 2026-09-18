package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.AuthRepository
import com.example.model.*
import com.example.util.PhoneValidator
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class AuthUiState(
    val isAuthenticated: Boolean = false,
    val currentUser: UserProfile? = null,
    val currentFarm: Farm? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val currentScreenMode: AuthScreenMode = AuthScreenMode.LOGIN,
    val resetEmailSent: Boolean = false,
    val emailConfirmationRequired: Boolean = false,
    val prefilledEmail: String? = null
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        // Collect real-time authentication & farm state from repository
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update {
                    it.copy(
                        isAuthenticated = authState.isAuthenticated,
                        currentUser = authState.userProfile,
                        currentFarm = authState.userFarm,
                        isLoading = authState.isLoading,
                        errorMessage = authState.errorMessage,
                        successMessage = authState.successMessage,
                        emailConfirmationRequired = authState.emailConfirmationRequired
                    )
                }
            }
        }

        // Check persistent Supabase Auth session on initialization
        viewModelScope.launch {
            authRepository.checkExistingSession()
        }
    }

    fun setScreenMode(mode: AuthScreenMode) {
        _uiState.update { it.copy(currentScreenMode = mode, errorMessage = null) }
    }

    fun login(email: String, password: String, onNavigateByRole: (UserRole) -> Unit) {
        if (email.isBlank() || password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter both email and password.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.login(email, password)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { user ->
                onNavigateByRole(user.role)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Invalid credentials.") }
            }
        }
    }

    fun registerCustomer(
        name: String,
        email: String,
        password: String,
        confirmPass: String,
        phone: String,
        onSuccess: (emailVerificationRequired: Boolean) -> Unit
    ) {
        if (name.isBlank() || email.isBlank() || password.isBlank() || phone.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please fill in all required fields.") }
            return
        }
        val phoneErr = PhoneValidator.getValidationErrorMessage(phone)
        if (phoneErr != null) {
            _uiState.update { it.copy(errorMessage = phoneErr) }
            return
        }
        if (password != confirmPass) {
            _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.registerCustomer(name, email, password, phone)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                val requiresVerification = authRepository.authState.value.emailConfirmationRequired
                onSuccess(requiresVerification)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Customer registration failed.") }
            }
        }
    }

    fun registerCustomer(
        name: String,
        email: String,
        password: String,
        confirmPass: String,
        phone: String,
        onSuccess: () -> Unit
    ) {
        registerCustomer(name, email, password, confirmPass, phone) { _ -> onSuccess() }
    }

    fun registerFarmAdmin(
        name: String,
        email: String,
        password: String,
        confirmPass: String,
        phone: String,
        farmName: String,
        farmDistrict: String,
        farmDescription: String,
        onSuccess: (emailVerificationRequired: Boolean) -> Unit
    ) {
        if (name.isBlank() || email.isBlank() || password.isBlank() || phone.isBlank() || farmName.isBlank() || farmDistrict.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please fill in all farm application fields.") }
            return
        }
        val phoneErr = PhoneValidator.getValidationErrorMessage(phone)
        if (phoneErr != null) {
            _uiState.update { it.copy(errorMessage = phoneErr) }
            return
        }
        if (password != confirmPass) {
            _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }
        if (password.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.registerFarmAdmin(
                name = name,
                email = email,
                password = password,
                phone = phone,
                farmName = farmName,
                farmDistrict = farmDistrict,
                farmDescription = farmDescription
            )
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                val requiresVerification = authRepository.authState.value.emailConfirmationRequired
                onSuccess(requiresVerification)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Farm application registration failed.") }
            }
        }
    }

    fun registerFarmAdmin(
        name: String,
        email: String,
        password: String,
        confirmPass: String,
        phone: String,
        farmName: String,
        farmDistrict: String,
        farmDescription: String,
        onSuccess: () -> Unit
    ) {
        registerFarmAdmin(
            name, email, password, confirmPass, phone, farmName, farmDistrict, farmDescription
        ) { _ -> onSuccess() }
    }

    fun sendPasswordReset(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your registered email address.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.sendPasswordResetOtp(email)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update { it.copy(resetEmailSent = true, successMessage = "Password reset link sent to $email. Please check your inbox.") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to send reset link.") }
            }
        }
    }

    fun resendEmailVerification(email: String) {
        if (email.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter your registered email address.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.resendEmailVerification(email)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update {
                    it.copy(successMessage = "Verification email resent to $email. Please check your inbox.")
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.message ?: "Failed to resend email verification link.")
                }
            }
        }
    }

    fun resetPassword(newPassword: String, confirmPass: String, onSuccess: () -> Unit) {
        if (newPassword.isBlank() || newPassword.length < 6) {
            _uiState.update { it.copy(errorMessage = "Password must be at least 6 characters.") }
            return
        }
        if (newPassword != confirmPass) {
            _uiState.update { it.copy(errorMessage = "Passwords do not match.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = authRepository.resetPassword(newPassword)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update { it.copy(successMessage = "Password reset successfully. Please log in.") }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to reset password.") }
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onLoggedOut()
        }
    }

    fun updateProfile(name: String, phone: String) {
        val cleanPhone = phone.trim()
        if (cleanPhone.isNotBlank()) {
            val phoneErr = PhoneValidator.getValidationErrorMessage(cleanPhone)
            if (phoneErr != null) {
                _uiState.update { it.copy(errorMessage = phoneErr) }
                return
            }
        }
        viewModelScope.launch {
            authRepository.updateProfile(name, phone)
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    fun prepareSignIn(email: String) {
        _uiState.update { it.copy(prefilledEmail = email.trim(), errorMessage = null) }
    }

    fun clearPrefilledEmail() {
        _uiState.update { it.copy(prefilledEmail = null) }
    }

    fun refreshUserProfile() {
        viewModelScope.launch {
            authRepository.checkExistingSession()
        }
    }

    companion object {
        fun provideFactory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AuthViewModel(authRepository) as T
                }
            }
    }
}
