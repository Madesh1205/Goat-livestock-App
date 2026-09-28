package com.ammalfarm.adusanthai.ui.navigation

sealed class Screen(val route: String) {
    // Auth
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object ForgotPassword : Screen("forgot_password")
    data object Profile : Screen("profile")
    data object Account : Screen("account")

    // Common / Customer
    data object Marketplace : Screen("marketplace")
    data object GoatDetail : Screen("goat_detail")
    data object FarmDetail : Screen("farm_detail")
    data object Orders : Screen("orders")
    data object MyBookings : Screen("my_bookings")
    data object Wishlist : Screen("wishlist")
    data object Notifications : Screen("notifications")

    // Legal & Policy
    data object PrivacyPolicy : Screen("privacy_policy")
    data object TermsConditions : Screen("terms_conditions")

    // Farm Admin
    data object FarmDashboard : Screen("farm_dashboard")
    data object AddGoat : Screen("add_goat")
    data object FarmBookings : Screen("farm_bookings")

    // Super Admin
    data object SuperAdminDashboard : Screen("super_admin_dashboard")
    data object PendingApprovals : Screen("pending_approvals")
    data object ManageFarms : Screen("manage_farms")
    data object AllGoatListings : Screen("all_goat_listings")
}
