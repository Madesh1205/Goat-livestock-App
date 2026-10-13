package com.ammalfarm.adusanthai.ui.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Register : Screen("register?isFarm={isFarm}") {
        fun createRoute(isFarm: Boolean = false) = "register?isFarm=$isFarm"
    }
    data object MarketplaceHome : Screen("marketplace")
    data object Profile : Screen("profile")
    data object SuperAdmin : Screen("super_admin")
    data object FarmAdmin : Screen("farm_admin")
    data object Orders : Screen("orders")
    data object Wishlist : Screen("wishlist")
    data object TermsConditions : Screen("terms")
}
