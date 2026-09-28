package com.ammalfarm.adusanthai.model

enum class AuthScreenMode {
    LOGIN,
    REGISTER_CUSTOMER,
    REGISTER_FARM,
    FORGOT_PASSWORD,
    RESET_PASSWORD
}

data class AuthState(
    val isAuthenticated: Boolean = false,
    val userProfile: UserProfile? = null,
    val userFarm: Farm? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val emailConfirmationRequired: Boolean = false
)
