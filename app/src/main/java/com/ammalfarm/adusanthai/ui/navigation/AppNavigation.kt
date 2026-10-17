package com.ammalfarm.adusanthai.ui.navigation

sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Login : Screen("login")
    data object Register : Screen("register?isFarm={isFarm}") {
        fun createRoute(isFarm: Boolean = false) = "register?isFarm=$isFarm"
    }
    data object MarketplaceHome : Screen("marketplace")
    data object GoatDetail : Screen("goat_detail/{goatId}") {
        fun createRoute(goatId: String) = "goat_detail/$goatId"
    }
    data object FarmDetail : Screen("farm_detail/{farmId}") {
        fun createRoute(farmId: String) = "farm_detail/$farmId"
    }
    data object Wishlist : Screen("wishlist")
    data object Bookings : Screen("bookings")
    data object Orders : Screen("orders")
    data object Notifications : Screen("notifications")
    data object Profile : Screen("profile")
    data object SuperAdmin : Screen("super_admin")
    data object FarmAdmin : Screen("farm_admin")
    data object TermsConditions : Screen("terms")
}
