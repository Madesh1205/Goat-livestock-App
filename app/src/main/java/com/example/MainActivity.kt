package com.example

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.core.firebase.FirebaseConfig
import com.example.core.supabase.SupabaseConfig
import com.example.model.AvailabilityStatus
import com.example.model.UserRole
import com.example.ui.components.DatabaseConnectionErrorScreen
import com.example.ui.components.GlobalNetworkRetrySnackbar
import com.example.ui.navigation.Screen
import com.example.ui.screens.*
import com.example.ui.screens.auth.*
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AuthViewModel
import com.example.ui.viewmodel.MarketplaceViewModel
import com.example.util.UserFriendlyErrorMapper

class MainActivity : ComponentActivity() {

    private val marketplaceViewModel: MarketplaceViewModel by viewModels {
        val app = application as AmmalFarmApplication
        MarketplaceViewModel.provideFactory(app.container.marketplaceRepository)
    }

    private val authViewModel: AuthViewModel by viewModels {
        val app = application as AmmalFarmApplication
        AuthViewModel.provideFactory(app.container.authRepository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingNotificationIntent(intent)
        val app = application as AmmalFarmApplication
        val themeManager = app.container.themeManager

        setContent {
            val currentThemeMode by themeManager.themeMode.collectAsStateWithLifecycle()

            MyApplicationTheme(themeMode = currentThemeMode) {
                val uiState by marketplaceViewModel.uiState.collectAsStateWithLifecycle()
                val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val context = LocalContext.current

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    if (isGranted) {
                        Log.d("MainActivity", "Notification permission granted")
                    } else {
                        Log.w("MainActivity", "Notification permission denied")
                    }
                }

                // Sync AuthViewModel user with MarketplaceViewModel & handle notification permission prompt
                LaunchedEffect(authUiState.currentUser) {
                    val user = authUiState.currentUser
                    marketplaceViewModel.setUser(user)
                    FirebaseConfig.activeUserId = user?.id
                    if (user != null) {
                        // Request notification permission once on Android 13+ (Tiramisu)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val prefs = context.getSharedPreferences("ammal_app_prefs", Context.MODE_PRIVATE)
                            val hasPrompted = prefs.getBoolean("has_prompted_notif_perm", false)
                            val hasPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED

                            if (!hasPermission && !hasPrompted) {
                                prefs.edit().putBoolean("has_prompted_notif_perm", true).apply()
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                }

                // Handle system / auth messages
                LaunchedEffect(authUiState.errorMessage, authUiState.successMessage) {
                    authUiState.errorMessage?.let {
                        val safeMsg = UserFriendlyErrorMapper.sanitize(it)
                        Toast.makeText(context, safeMsg, Toast.LENGTH_SHORT).show()
                        authViewModel.clearMessages()
                    }
                    authUiState.successMessage?.let {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        authViewModel.clearMessages()
                    }
                }

                LaunchedEffect(uiState.errorMessage, uiState.successMessage) {
                    uiState.errorMessage?.let {
                        val safeMsg = UserFriendlyErrorMapper.sanitize(it)
                        Toast.makeText(context, safeMsg, Toast.LENGTH_SHORT).show()
                        marketplaceViewModel.clearMessages()
                    }
                    uiState.successMessage?.let {
                        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                        marketplaceViewModel.clearMessages()
                    }
                }

                // Deep Link Navigation Handler
                val pendingDeepLink by marketplaceViewModel.pendingDeepLink.collectAsStateWithLifecycle()

                LaunchedEffect(pendingDeepLink, authUiState.isAuthenticated, authUiState.isLoading, uiState.goats, uiState.farms) {
                    val payload = pendingDeepLink ?: return@LaunchedEffect

                    // Wait for auth initialization to complete during cold-start
                    if (authUiState.isLoading) return@LaunchedEffect

                    val currentUser = authUiState.currentUser
                    val activeUserId = currentUser?.id

                    // Security: User Recipient Isolation
                    if (!payload.recipientUserId.isNullOrBlank() && activeUserId != null && !payload.recipientUserId.equals(activeUserId, ignoreCase = true)) {
                        if (!payload.intentKey.startsWith("inapp_")) {
                            Toast.makeText(context, "Access Denied: Notification is meant for a different account.", Toast.LENGTH_SHORT).show()
                            marketplaceViewModel.clearPendingDeepLink()
                            return@LaunchedEffect
                        }
                    }

                    val targetRoute = payload.route?.takeIf { it.isNotBlank() }
                        ?: payload.notificationType?.let { com.example.util.DeepLinkUtils.resolveDeepLinkRoute(it, payload.referenceId) }
                        ?: Screen.Marketplace.route

                    // Wait for marketplace data to finish initial load if routing to goat detail
                    if (uiState.isLoading && uiState.goats.isEmpty() && (targetRoute == "goat_detail" || targetRoute == Screen.GoatDetail.route)) {
                        return@LaunchedEffect
                    }

                    val authoritativeRole = currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                    val requiresAuth = targetRoute in listOf("orders", Screen.Orders.route, "my_bookings", Screen.MyBookings.route, "farm_dashboard", Screen.FarmDashboard.route, "super_admin_dashboard", Screen.SuperAdminDashboard.route)

                    if (requiresAuth && !authUiState.isAuthenticated) {
                        navController.navigate(Screen.Login.route) { launchSingleTop = true }
                        marketplaceViewModel.clearPendingDeepLink()
                        return@LaunchedEffect
                    }

                    when (targetRoute) {
                        Screen.GoatDetail.route, "goat_detail" -> {
                            val refId = payload.referenceId
                            if (!refId.isNullOrBlank()) {
                                val goat = marketplaceViewModel.selectOrFetchGoatById(refId)
                                if (goat != null) {
                                    navController.navigate(Screen.GoatDetail.route) { launchSingleTop = true }
                                } else {
                                    Toast.makeText(context, "This goat listing is no longer available or was removed.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                                }
                            } else if (uiState.selectedGoat != null) {
                                navController.navigate(Screen.GoatDetail.route) { launchSingleTop = true }
                            } else {
                                navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                            }
                        }

                        Screen.FarmDetail.route, "farm_detail" -> {
                            val refId = payload.referenceId
                            if (!refId.isNullOrBlank()) {
                                val farm = marketplaceViewModel.selectOrFetchFarmById(refId)
                                if (farm != null && farm.verificationStatus == com.example.model.VerificationStatus.APPROVED) {
                                    navController.navigate(Screen.FarmDetail.route) { launchSingleTop = true }
                                } else {
                                    Toast.makeText(context, "This farm profile is no longer available.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                                }
                            } else if (uiState.selectedFarm != null) {
                                navController.navigate(Screen.FarmDetail.route) { launchSingleTop = true }
                            } else {
                                navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                            }
                        }

                        Screen.Orders.route, "orders", Screen.MyBookings.route, "my_bookings" -> {
                            val refId = payload.referenceId
                            if (!refId.isNullOrBlank()) {
                                marketplaceViewModel.selectBookingForDetail(refId)
                            }
                            navController.navigate(Screen.Orders.route) { launchSingleTop = true }
                        }

                        Screen.FarmDashboard.route, "farm_dashboard" -> {
                            if (authoritativeRole == UserRole.FARM_ADMIN || authoritativeRole == UserRole.SUPER_ADMIN) {
                                navController.navigate(Screen.FarmDashboard.route) { launchSingleTop = true }
                            } else {
                                Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                            }
                        }

                        Screen.SuperAdminDashboard.route, "super_admin_dashboard" -> {
                            if (authoritativeRole == UserRole.SUPER_ADMIN) {
                                navController.navigate(Screen.SuperAdminDashboard.route) { launchSingleTop = true }
                            } else {
                                Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                val safeRoute = if (authoritativeRole == UserRole.FARM_ADMIN) Screen.FarmDashboard.route else Screen.Marketplace.route
                                navController.navigate(safeRoute) { launchSingleTop = true }
                            }
                        }

                        Screen.Notifications.route, "notifications" -> {
                            if (navController.currentDestination?.route != Screen.Notifications.route) {
                                navController.navigate(Screen.Notifications.route) { launchSingleTop = true }
                            }
                        }

                        else -> {
                            try {
                                navController.navigate(targetRoute) { launchSingleTop = true }
                            } catch (_: Exception) {
                                navController.navigate(Screen.Marketplace.route) { launchSingleTop = true }
                            }
                        }
                    }

                    marketplaceViewModel.clearPendingDeepLink()
                }

                val currentRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                val unreadNotifsCount = remember(uiState.notifications, currentRole) {
                    uiState.notifications.count { !it.isRead && (it.targetRole == currentRole || it.targetRole == UserRole.CUSTOMER) }
                }
                val isAuthScreen = currentRoute in listOf(
                    Screen.Login.route,
                    Screen.Register.route,
                    Screen.ForgotPassword.route,
                    Screen.PrivacyPolicy.route,
                    Screen.TermsConditions.route
                )
                val isPrimaryTabRoute = currentRoute in listOf(
                    Screen.Marketplace.route,
                    Screen.Orders.route,
                    Screen.Account.route,
                    Screen.Profile.route,
                    Screen.MyBookings.route,
                    Screen.FarmDashboard.route,
                    Screen.FarmBookings.route,
                    "farm_bookings",
                    "farm_admin_bookings",
                    Screen.SuperAdminDashboard.route,
                    Screen.Wishlist.route
                )

                val snackbarHostState = remember { SnackbarHostState() }

                // Global retry snackbar for network calls when synchronizing platform data
                LaunchedEffect(uiState.networkError) {
                    val error = uiState.networkError
                    if (!error.isNullOrBlank()) {
                        val result = snackbarHostState.showSnackbar(
                            message = error,
                            actionLabel = "Retry",
                            duration = SnackbarDuration.Short,
                            withDismissAction = true
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            marketplaceViewModel.retryNetworkCall()
                        }
                        marketplaceViewModel.clearNetworkError()
                    }
                }

                // Strict Authentication Guard: The marketplace and features are strictly hidden without login
                val isAuthenticated = authUiState.isAuthenticated && authUiState.currentUser != null
                val publicRoutes = remember {
                    listOf(
                        Screen.Login.route,
                        Screen.Register.route,
                        Screen.ForgotPassword.route,
                        Screen.PrivacyPolicy.route,
                        Screen.TermsConditions.route
                    )
                }

                LaunchedEffect(isAuthenticated, currentRoute) {
                    if (!isAuthenticated) {
                        if (currentRoute != null && currentRoute !in publicRoutes) {
                            navController.navigate(Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }
                }

                // Handle Deep Link from Push Notification Intent
                LaunchedEffect(intent) {
                    val deepLinkRoute = intent?.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_DEEP_LINK_ROUTE)
                    if (!deepLinkRoute.isNullOrBlank() && isAuthenticated) {
                        val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                        when (deepLinkRoute) {
                            "orders", "my_bookings" -> navController.navigate(Screen.Orders.route)
                            "farm_dashboard" -> {
                                if (authoritativeRole == UserRole.FARM_ADMIN || authoritativeRole == UserRole.SUPER_ADMIN) {
                                    navController.navigate(Screen.FarmDashboard.route)
                                } else {
                                    Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(Screen.Marketplace.route)
                                }
                            }
                            "super_admin_dashboard" -> {
                                if (authoritativeRole == UserRole.SUPER_ADMIN) {
                                    navController.navigate(Screen.SuperAdminDashboard.route)
                                } else {
                                    Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                    val safeRoute = if (authoritativeRole == UserRole.FARM_ADMIN) Screen.FarmDashboard.route else Screen.Marketplace.route
                                    navController.navigate(safeRoute)
                                }
                            }
                            "notifications" -> navController.navigate(Screen.Notifications.route)
                            "marketplace" -> navController.navigate(Screen.Marketplace.route)
                            else -> navController.navigate(deepLinkRoute)
                        }
                    }
                }

                // Role-based routing helper
                val navigateByRole: (UserRole) -> Unit = { role ->
                    when (role) {
                        UserRole.CUSTOMER -> {
                            navController.navigate(Screen.Marketplace.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                        UserRole.FARM_ADMIN -> {
                            navController.navigate(Screen.FarmDashboard.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                        UserRole.SUPER_ADMIN -> {
                            navController.navigate(Screen.SuperAdminDashboard.route) {
                                popUpTo(Screen.Login.route) { inclusive = true }
                            }
                        }
                    }
                }

                val isDatabaseConnected = SupabaseConfig.isConfigured && !(uiState.networkError != null && uiState.goats.isEmpty() && uiState.farms.isEmpty() && !uiState.isLoading)

                if (!isDatabaseConnected) {
                    DatabaseConnectionErrorScreen(
                        errorMessage = uiState.networkError ?: if (!SupabaseConfig.isConfigured) "Database not connected: Supabase configuration (SUPABASE_URL and SUPABASE_ANON_KEY) is missing or unconfigured." else null,
                        isRetrying = uiState.isRetrying,
                        onRetry = {
                            marketplaceViewModel.refreshMarketplace()
                            authViewModel.refreshUserProfile()
                        }
                    )
                } else {
                    Scaffold(
                        snackbarHost = {
                        SnackbarHost(hostState = snackbarHostState) { snackbarData ->
                            GlobalNetworkRetrySnackbar(snackbarData = snackbarData)
                        }
                    },
                    bottomBar = {
                        if (isPrimaryTabRoute && isAuthenticated) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = 0.dp
                                ) {
                                    val isMarketplace = currentRoute == Screen.Marketplace.route
                                    NavigationBarItem(
                                        selected = isMarketplace,
                                        onClick = { navController.navigate(Screen.Marketplace.route) },
                                        icon = { Icon(Icons.Default.Storefront, contentDescription = "Marketplace") },
                                        label = { Text("Marketplace", fontWeight = if (isMarketplace) FontWeight.Bold else FontWeight.Medium) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )

                                    val isOrders = currentRoute == Screen.Orders.route || currentRoute == Screen.MyBookings.route || currentRoute == Screen.FarmBookings.route || currentRoute == "farm_bookings"
                                    NavigationBarItem(
                                        selected = isOrders,
                                        onClick = { navController.navigate(Screen.Orders.route) },
                                        icon = { Icon(Icons.Default.ReceiptLong, contentDescription = "Orders") },
                                        label = { Text("Orders", fontWeight = if (isOrders) FontWeight.Bold else FontWeight.Medium) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )

                                    if (currentRole == UserRole.SUPER_ADMIN) {
                                        val isAdminConsole = currentRoute == Screen.SuperAdminDashboard.route
                                        NavigationBarItem(
                                            selected = isAdminConsole,
                                            onClick = { navController.navigate(Screen.SuperAdminDashboard.route) },
                                            icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = "Admin Console") },
                                            label = { Text("Admin Console", fontWeight = if (isAdminConsole) FontWeight.Bold else FontWeight.Medium) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onErrorContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                indicatorColor = MaterialTheme.colorScheme.errorContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    } else if (currentRole == UserRole.FARM_ADMIN) {
                                        val isFarmPortal = currentRoute == Screen.FarmDashboard.route
                                        NavigationBarItem(
                                            selected = isFarmPortal,
                                            onClick = { navController.navigate(Screen.FarmDashboard.route) },
                                            icon = { Icon(Icons.Default.Agriculture, contentDescription = "My Farm") },
                                            label = { Text("My Farm", fontWeight = if (isFarmPortal) FontWeight.Bold else FontWeight.Medium) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }

                                    val isAccount = currentRoute == Screen.Account.route || currentRoute == Screen.Profile.route
                                    NavigationBarItem(
                                        selected = isAccount,
                                        onClick = { navController.navigate(Screen.Account.route) },
                                        icon = { Icon(Icons.Default.AccountCircle, contentDescription = "Account") },
                                        label = { Text("Account", fontWeight = if (isAccount) FontWeight.Bold else FontWeight.Medium) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = if (authUiState.currentUser != null) Screen.Marketplace.route else Screen.Login.route,
                        modifier = Modifier
                            .padding(innerPadding)
                            .consumeWindowInsets(innerPadding)
                    ) {
                        // --- AUTH ROUTES ---
                        composable(Screen.Login.route) {
                            LoginScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                policyConsentManager = app.container.policyConsentManager,
                                onNavigateByRole = { role ->
                                    marketplaceViewModel.loadAllPlatformData()
                                    authViewModel.refreshUserProfile()
                                    navigateByRole(role)
                                },
                                onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                                onNavigateToRegisterFarm = { navController.navigate(Screen.Register.route) },
                                onNavigateToForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) }
                            )
                        }

                        composable(Screen.Register.route) {
                            RegisterScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                policyConsentManager = app.container.policyConsentManager,
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                                onNavigateToForgotPassword = { navController.navigate(Screen.ForgotPassword.route) },
                                onRegistrationSuccess = { role ->
                                    marketplaceViewModel.loadAllPlatformData()
                                    authViewModel.refreshUserProfile()
                                    navigateByRole(role)
                                },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) }
                            )
                        }

                        composable(Screen.ForgotPassword.route) {
                            ForgotPasswordScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Account.route) {
                            ProfileScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                wishlistCount = uiState.wishlistGoatIds.size,
                                currentThemeMode = currentThemeMode,
                                onThemeModeChanged = { newMode ->
                                    themeManager.setThemeMode(newMode)
                                },
                                onLogoutSuccess = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                                onNavigateToOrders = { navController.navigate(Screen.Orders.route) },
                                onNavigateToWishlist = { navController.navigate(Screen.Wishlist.route) },
                                onNavigateToSuperAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.SuperAdminDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNavigateToFarmAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.FARM_ADMIN || authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.FarmDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) }
                            )
                        }

                        composable(Screen.Profile.route) {
                            ProfileScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                wishlistCount = uiState.wishlistGoatIds.size,
                                currentThemeMode = currentThemeMode,
                                onThemeModeChanged = { newMode ->
                                    themeManager.setThemeMode(newMode)
                                },
                                onLogoutSuccess = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                                onNavigateToOrders = { navController.navigate(Screen.Orders.route) },
                                onNavigateToWishlist = { navController.navigate(Screen.Wishlist.route) },
                                onNavigateToSuperAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.SuperAdminDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNavigateToFarmAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.FARM_ADMIN || authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.FarmDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.PrivacyPolicy.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) }
                            )
                        }

                        // --- MARKETPLACE & CUSTOMER ROUTES ---
                        composable(Screen.Marketplace.route) {
                            MarketplaceHomeScreen(
                                uiState = uiState,
                                onSearchQueryChange = { marketplaceViewModel.setSearchQuery(it) },
                                onApplyCriteria = { marketplaceViewModel.updateFilterCriteria(it) },
                                onBreedSelect = { marketplaceViewModel.selectBreed(it) },
                                onSortOptionSelect = { marketplaceViewModel.setSortOption(it) },
                                onClearAllFilters = { marketplaceViewModel.clearFilters() },
                                onRemoveBreed = { marketplaceViewModel.removeBreedFilter() },
                                onRemoveFarm = { marketplaceViewModel.removeFarmFilter() },
                                onRemoveGender = { marketplaceViewModel.removeGenderFilter() },
                                onRemoveAge = { marketplaceViewModel.removeAgeFilter() },
                                onRemoveWeight = { marketplaceViewModel.removeWeightFilter() },
                                onRemovePrice = { marketplaceViewModel.removePriceFilter() },
                                onRemoveLocation = { marketplaceViewModel.removeLocationFilter() },
                                onRemoveAvailability = { marketplaceViewModel.removeAvailabilityFilter() },
                                onRemoveSearchQuery = { marketplaceViewModel.removeSearchFilter() },
                                onGoatClick = { goat ->
                                    marketplaceViewModel.selectGoat(goat)
                                    navController.navigate(Screen.GoatDetail.route)
                                },
                                onFarmClick = { farmId ->
                                    val farm = marketplaceViewModel.selectFarmById(farmId)
                                    if (farm != null && farm.verificationStatus == com.example.model.VerificationStatus.APPROVED) {
                                        navController.navigate(Screen.FarmDetail.route)
                                    } else {
                                        marketplaceViewModel.selectFarmFilter(farmId, farm?.name)
                                    }
                                },
                                onBookingsClick = {
                                    navController.navigate(Screen.Orders.route)
                                },
                                onProfileClick = {
                                    if (authUiState.isAuthenticated) {
                                        navController.navigate(Screen.Account.route)
                                    } else {
                                        navController.navigate(Screen.Login.route)
                                    }
                                },
                                onWishlistClick = {
                                    if (authUiState.isAuthenticated) {
                                        navController.navigate(Screen.Wishlist.route)
                                    } else {
                                        Toast.makeText(context, "Please sign in to view your Saved Livestock", Toast.LENGTH_SHORT).show()
                                        navController.navigate(Screen.Login.route)
                                    }
                                },
                                onToggleWishlist = { goat ->
                                    marketplaceViewModel.toggleWishlist(goat) {
                                        Toast.makeText(context, "Please sign in to save goats to your profile", Toast.LENGTH_SHORT).show()
                                        navController.navigate(Screen.Login.route)
                                    }
                                },
                                onNotificationsClick = {
                                    navController.navigate(Screen.Notifications.route)
                                },
                                onNavigateToSuperAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.SuperAdminDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNavigateToFarmAdmin = {
                                    val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                                    if (authoritativeRole == UserRole.FARM_ADMIN || authoritativeRole == UserRole.SUPER_ADMIN) {
                                        navController.navigate(Screen.FarmDashboard.route)
                                    } else {
                                        Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onRefresh = {
                                    authViewModel.refreshUserProfile()
                                    marketplaceViewModel.refreshMarketplace()
                                }
                            )
                        }

                        composable(Screen.Wishlist.route) {
                            WishlistScreen(
                                uiState = uiState,
                                onBackClick = { navController.popBackStack() },
                                onGoatClick = { goat ->
                                    marketplaceViewModel.selectGoat(goat)
                                    navController.navigate(Screen.GoatDetail.route)
                                },
                                onRemoveFromWishlist = { goatId ->
                                    marketplaceViewModel.removeFromWishlist(goatId)
                                },
                                onBookGoat = { goat ->
                                    val currentUser = authUiState.currentUser ?: uiState.currentUser
                                    val userFarm = uiState.farms.find { it.ownerId == currentUser?.id || (currentUser?.farmId != null && it.id == currentUser.farmId) }
                                    val userFarmId = currentUser?.farmId ?: userFarm?.id
                                    val isOwnFarm = currentUser?.role == UserRole.FARM_ADMIN && (
                                        (userFarmId != null && userFarmId == goat.farmId) ||
                                        uiState.farms.find { it.id == goat.farmId }?.ownerId == currentUser.id
                                    )
                                    if (isOwnFarm) {
                                        Toast.makeText(context, "You cannot book goats listed by your own farm.", Toast.LENGTH_LONG).show()
                                    } else if (authUiState.isAuthenticated || currentUser != null) {
                                        marketplaceViewModel.createBooking(goat.id, "Wishlist direct reservation") {
                                            Toast.makeText(context, "24-Hour Reservation Placed for ${goat.name}!", Toast.LENGTH_LONG).show()
                                            navController.navigate(Screen.Orders.route)
                                        }
                                    } else {
                                        Toast.makeText(context, "Please sign in to book a goat.", Toast.LENGTH_SHORT).show()
                                        navController.navigate(Screen.Login.route)
                                    }
                                },
                                onBrowseMarketplace = {
                                    navController.navigate(Screen.Marketplace.route) {
                                        popUpTo(Screen.Marketplace.route) { inclusive = true }
                                    }
                                },
                                onRefresh = {
                                    marketplaceViewModel.refreshWishlist()
                                }
                            )
                        }

                        composable(Screen.FarmDetail.route) {
                            uiState.selectedFarm?.let { farm ->
                                FarmDetailScreen(
                                    farm = farm,
                                    uiState = uiState,
                                    onBackClick = { navController.popBackStack() },
                                    onGoatClick = { goat ->
                                        marketplaceViewModel.selectGoat(goat)
                                        navController.navigate(Screen.GoatDetail.route)
                                    },
                                    onBreedFilterClick = { breed ->
                                        marketplaceViewModel.selectBreed(breed)
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.Marketplace.route) { inclusive = false }
                                        }
                                    },
                                    onToggleWishlist = { goat ->
                                        marketplaceViewModel.toggleWishlist(goat) {
                                            Toast.makeText(context, "Please sign in to save goats to your profile", Toast.LENGTH_SHORT).show()
                                            navController.navigate(Screen.Login.route)
                                        }
                                    },
                                    onRetryLoadFarmGoats = {
                                        marketplaceViewModel.loadFarmPublicGoats(farm.id)
                                    },
                                    onSubmitReport = { targetType, targetId, targetTitle, reason, description, evidencePhotoUrl ->
                                        marketplaceViewModel.submitReport(
                                            targetType = targetType,
                                            targetId = targetId,
                                            targetTitle = targetTitle,
                                            reason = reason,
                                            description = description,
                                            evidencePhotoUrl = evidencePhotoUrl
                                        )
                                    }
                                )
                            }
                        }

                        composable(Screen.GoatDetail.route) {
                            uiState.selectedGoat?.let { goat ->
                                GoatDetailScreen(
                                    goat = goat,
                                    uiState = uiState,
                                    isAuthenticated = authUiState.isAuthenticated,
                                    onBackClick = { navController.popBackStack() },
                                    onFarmClick = { farmId ->
                                        val farm = marketplaceViewModel.selectFarmById(farmId)
                                        if (farm != null && farm.verificationStatus == com.example.model.VerificationStatus.APPROVED) {
                                            navController.navigate(Screen.FarmDetail.route)
                                        } else {
                                            Toast.makeText(context, "This farm is currently not publicly accessible.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onBreedClick = { breed ->
                                        marketplaceViewModel.selectBreed(breed)
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.Marketplace.route) { inclusive = false }
                                        }
                                    },
                                    onLoginRequired = {
                                        navController.navigate(Screen.Login.route)
                                    },
                                    onToggleWishlist = {
                                        marketplaceViewModel.toggleWishlist(goat) {
                                            Toast.makeText(context, "Please sign in to save goats to your profile", Toast.LENGTH_SHORT).show()
                                            navController.navigate(Screen.Login.route)
                                        }
                                    },
                                    onBookGoat = { goatId, notes ->
                                        val currentUser = authUiState.currentUser ?: uiState.currentUser
                                        val userFarm = uiState.farms.find { it.ownerId == currentUser?.id || (currentUser?.farmId != null && it.id == currentUser.farmId) }
                                        val userFarmId = currentUser?.farmId ?: userFarm?.id
                                        val isOwnFarm = currentUser?.role == UserRole.FARM_ADMIN && (
                                            (userFarmId != null && userFarmId == goat.farmId) ||
                                            uiState.farms.find { it.id == goat.farmId }?.ownerId == currentUser.id
                                        )
                                        if (isOwnFarm) {
                                            Toast.makeText(context, "You cannot book goats listed by your own farm.", Toast.LENGTH_LONG).show()
                                        } else if (authUiState.isAuthenticated || currentUser != null) {
                                            marketplaceViewModel.createBooking(goatId, notes) {
                                                Toast.makeText(context, "24-Hour Reservation Placed! Farm breeder notified.", Toast.LENGTH_LONG).show()
                                                navController.navigate(Screen.Orders.route)
                                            }
                                        } else {
                                            Toast.makeText(context, "Please sign in to book a goat.", Toast.LENGTH_SHORT).show()
                                            navController.navigate(Screen.Login.route)
                                        }
                                    },
                                    onSubmitReport = { targetType, targetId, targetTitle, reason, description, evidencePhotoUrl ->
                                        marketplaceViewModel.submitReport(
                                            targetType = targetType,
                                            targetId = targetId,
                                            targetTitle = targetTitle,
                                            reason = reason,
                                            description = description,
                                            evidencePhotoUrl = evidencePhotoUrl
                                        )
                                    }
                                )
                            }
                        }

                        // --- UNIFIED ORDERS ROUTE (CUSTOMER & FARM ADMIN & SUPER ADMIN) ---
                        composable(Screen.Orders.route) {
                            OrdersScreen(
                                uiState = uiState,
                                onNavigateBack = { navController.navigateUp() },
                                onConfirmBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CONFIRMED)
                                },
                                onRejectBooking = { bookingId, reason ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.REJECTED, reason)
                                },
                                onCancelBooking = { bookingId, reason ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CANCELLED, reason)
                                },
                                onCompleteBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.COMPLETED)
                                },
                                onRefresh = {
                                    marketplaceViewModel.refreshMarketplace()
                                },
                                onDismissSelectedBooking = {
                                    marketplaceViewModel.clearSelectedBookingDetail()
                                }
                            )
                        }

                        // Backward-compatible alias for legacy my_bookings route
                        composable(Screen.MyBookings.route) {
                            OrdersScreen(
                                uiState = uiState,
                                onNavigateBack = { navController.navigateUp() },
                                onConfirmBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CONFIRMED)
                                },
                                onRejectBooking = { bookingId, reason ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.REJECTED, reason)
                                },
                                onCancelBooking = { bookingId, reason ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CANCELLED, reason)
                                },
                                onCompleteBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.COMPLETED)
                                },
                                onRefresh = {
                                    marketplaceViewModel.refreshMarketplace()
                                },
                                onDismissSelectedBooking = {
                                    marketplaceViewModel.clearSelectedBookingDetail()
                                }
                            )
                        }

                        // --- FARM ADMIN ROUTE (GUARDED) ---
                        composable(Screen.FarmDashboard.route) {
                            val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                            if (authoritativeRole == UserRole.CUSTOMER) {
                                LaunchedEffect(Unit) {
                                    Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(Screen.Marketplace.route) {
                                        popUpTo(Screen.FarmDashboard.route) { inclusive = true }
                                    }
                                }
                                com.example.ui.screens.FarmAdminAccessDeniedView(
                                    currentRole = authoritativeRole.name,
                                    onNavigateBack = {
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.FarmDashboard.route) { inclusive = true }
                                        }
                                    },
                                    onNavigateToMarketplace = {
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.FarmDashboard.route) { inclusive = true }
                                        }
                                    },
                                    onSyncRole = {
                                        authViewModel.refreshUserProfile()
                                        marketplaceViewModel.refreshMarketplace()
                                    }
                                )
                            } else {
                                FarmAdminScreen(
                                uiState = uiState,
                                onAddGoat = { goat ->
                                    marketplaceViewModel.addGoatListing(goat) {
                                        Toast.makeText(context, "Goat listing submitted for approval!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onEditGoat = { goat ->
                                    marketplaceViewModel.updateGoatListing(goat) {
                                        Toast.makeText(context, "Goat updated successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDeleteGoat = { goatId ->
                                    marketplaceViewModel.deleteGoatListing(goatId) {
                                        Toast.makeText(context, "Goat listing removed.", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onConfirmBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CONFIRMED)
                                },
                                onRejectBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.REJECTED)
                                },
                                onCompleteBooking = { bookingId ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.COMPLETED)
                                },
                                onUpdateFarmProfile = { farm ->
                                    marketplaceViewModel.updateFarmProfile(farm) {
                                        Toast.makeText(context, "Farm profile updated successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onUpdateFarmLogo = { farmId, logoUrl ->
                                    marketplaceViewModel.updateFarmLogo(farmId, logoUrl) {
                                        Toast.makeText(context, "Farm logo updated successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onSyncRole = {
                                    authViewModel.refreshUserProfile()
                                    marketplaceViewModel.refreshMarketplace()
                                },
                                onNavigateToLogin = {
                                    navController.navigate(Screen.Login.route)
                                },
                                onNavigateToRegisterFarm = {
                                    navController.navigate(Screen.Register.route)
                                },
                                onNavigateToBookings = {
                                    navController.navigate(Screen.Orders.route)
                                },
                                onNavigateBack = {
                                    navController.navigateUp()
                                }
                            )
                            }
                        }

                        // --- FARM ADMIN BOOKINGS ROUTE (GUARDED / REDIRECTED TO UNIFIED ORDERS) ---
                        composable(Screen.FarmBookings.route) {
                            val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                            if (authoritativeRole == UserRole.CUSTOMER) {
                                LaunchedEffect(Unit) {
                                    Toast.makeText(context, "Access Denied: Farm Partner access required.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(Screen.Marketplace.route) {
                                        popUpTo(Screen.FarmBookings.route) { inclusive = true }
                                    }
                                }
                                com.example.ui.screens.FarmAdminAccessDeniedView(
                                    currentRole = authoritativeRole.name,
                                    onNavigateBack = {
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.FarmBookings.route) { inclusive = true }
                                        }
                                    },
                                    onNavigateToMarketplace = {
                                        navController.navigate(Screen.Marketplace.route) {
                                            popUpTo(Screen.FarmBookings.route) { inclusive = true }
                                        }
                                    },
                                    onSyncRole = {
                                        authViewModel.refreshUserProfile()
                                        marketplaceViewModel.refreshMarketplace()
                                    }
                                )
                            } else {
                                OrdersScreen(
                                    uiState = uiState,
                                    onNavigateBack = { navController.navigateUp() },
                                    onConfirmBooking = { bookingId ->
                                        marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CONFIRMED)
                                    },
                                    onRejectBooking = { bookingId, reason ->
                                        marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.REJECTED, reason)
                                    },
                                    onCancelBooking = { bookingId, reason ->
                                        marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.CANCELLED, reason)
                                    },
                                    onCompleteBooking = { bookingId ->
                                        marketplaceViewModel.updateBookingStatus(bookingId, AvailabilityStatus.COMPLETED)
                                    },
                                    onRefresh = {
                                        marketplaceViewModel.refreshMarketplace()
                                    },
                                    onDismissSelectedBooking = {
                                        marketplaceViewModel.clearSelectedBookingDetail()
                                    }
                                )
                            }
                        }

                        // --- SUPER ADMIN ROUTE (GUARDED: PUBLIC REGISTRATION NEVER PERMITTED) ---
                        composable(Screen.SuperAdminDashboard.route) {
                            val authoritativeRole = authUiState.currentUser?.role ?: uiState.currentUser?.role ?: UserRole.CUSTOMER
                            if (authoritativeRole != UserRole.SUPER_ADMIN) {
                                val safeDestination = if (authoritativeRole == UserRole.FARM_ADMIN) Screen.FarmDashboard.route else Screen.Marketplace.route
                                LaunchedEffect(Unit) {
                                    Toast.makeText(context, "Access Denied: Super Admin authorization required.", Toast.LENGTH_SHORT).show()
                                    navController.navigate(safeDestination) {
                                        popUpTo(Screen.SuperAdminDashboard.route) { inclusive = true }
                                    }
                                }
                                com.example.ui.screens.SuperAdminAccessDeniedView(
                                    currentRole = authoritativeRole.name,
                                    onNavigateToLogin = {
                                        navController.navigate(safeDestination) {
                                            popUpTo(Screen.SuperAdminDashboard.route) { inclusive = true }
                                        }
                                    },
                                    onSyncRole = {
                                        authViewModel.refreshUserProfile()
                                        marketplaceViewModel.refreshMarketplace()
                                    }
                                )
                            } else {
                                SuperAdminScreen(
                                uiState = uiState,
                                onApproveGoat = { goatId ->
                                    marketplaceViewModel.approveGoatListing(goatId)
                                    Toast.makeText(context, "Goat listing approved for public marketplace!", Toast.LENGTH_SHORT).show()
                                },
                                onRejectGoat = { goatId ->
                                    marketplaceViewModel.rejectGoatListing(goatId)
                                    Toast.makeText(context, "Goat listing rejected.", Toast.LENGTH_SHORT).show()
                                },
                                onSuspendGoat = { goatId ->
                                    marketplaceViewModel.suspendGoatListing(goatId)
                                    Toast.makeText(context, "Goat listing suspended.", Toast.LENGTH_SHORT).show()
                                },
                                onRestoreGoat = { goatId ->
                                    marketplaceViewModel.restoreGoatListing(goatId)
                                    Toast.makeText(context, "Goat listing restored.", Toast.LENGTH_SHORT).show()
                                },
                                onDeleteGoat = { goatId ->
                                    marketplaceViewModel.deleteGoatListing(goatId)
                                    Toast.makeText(context, "Goat listing deleted permanently.", Toast.LENGTH_SHORT).show()
                                },
                                onEditGoat = { goat ->
                                    marketplaceViewModel.updateGoat(goat)
                                    Toast.makeText(context, "Goat listing updated.", Toast.LENGTH_SHORT).show()
                                },
                                onApproveFarm = { farmId ->
                                    marketplaceViewModel.updateFarmVerification(farmId, com.example.model.VerificationStatus.APPROVED)
                                    Toast.makeText(context, "Farm verified & approved!", Toast.LENGTH_SHORT).show()
                                },
                                onRejectFarm = { farmId ->
                                    marketplaceViewModel.updateFarmVerification(farmId, com.example.model.VerificationStatus.REJECTED)
                                    Toast.makeText(context, "Farm rejected.", Toast.LENGTH_SHORT).show()
                                },
                                onSuspendFarm = { farmId ->
                                    marketplaceViewModel.updateFarmVerification(farmId, com.example.model.VerificationStatus.SUSPENDED)
                                    Toast.makeText(context, "Farm suspended.", Toast.LENGTH_SHORT).show()
                                },
                                onReactivateFarm = { farmId ->
                                    marketplaceViewModel.updateFarmVerification(farmId, com.example.model.VerificationStatus.APPROVED)
                                    Toast.makeText(context, "Farm reactivated!", Toast.LENGTH_SHORT).show()
                                },
                                onUpdateFarmListingLimit = { farmId, limit ->
                                    marketplaceViewModel.updateFarmListingLimit(farmId, limit)
                                    Toast.makeText(context, "Farm goat listing limit updated to $limit.", Toast.LENGTH_SHORT).show()
                                },
                                onUpdateUserSuspension = { userId, isSuspended ->
                                    marketplaceViewModel.updateUserSuspension(userId, isSuspended)
                                    val msg = if (isSuspended) "Customer account suspended." else "Customer account reactivated."
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                },
                                onUpdateReportStatus = { reportId, status, notes ->
                                    marketplaceViewModel.updateReportStatus(reportId, status, notes)
                                    Toast.makeText(context, "Report status updated to ${status.name}.", Toast.LENGTH_SHORT).show()
                                },
                                onResolveReportWithAction = { reportId, removeGid, suspFid, notes ->
                                    marketplaceViewModel.resolveReportWithAction(reportId, removeGid, suspFid, notes)
                                    Toast.makeText(context, "Report resolved with administrative actions.", Toast.LENGTH_SHORT).show()
                                },
                                onUpdateBookingStatus = { bookingId, status ->
                                    marketplaceViewModel.updateBookingStatus(bookingId, status)
                                    Toast.makeText(context, "Booking status updated.", Toast.LENGTH_SHORT).show()
                                },
                                onNavigateToAmmalFarm = {
                                    navController.navigate(Screen.FarmDashboard.route)
                                },
                                onNavigateBack = {
                                    navController.navigateUp()
                                },
                                onNavigateToLogin = {
                                    navController.navigate(Screen.Login.route)
                                },
                                onSyncRole = {
                                    authViewModel.refreshUserProfile()
                                    marketplaceViewModel.refreshMarketplace()
                                }
                            )
                            }
                        }

                        // --- IN-APP NOTIFICATION CENTER ---
                        composable(Screen.Notifications.route) {
                            NotificationCenterScreen(
                                uiState = uiState,
                                onBackClick = { navController.popBackStack() },
                                onMarkAsRead = { notifId ->
                                    marketplaceViewModel.markNotificationAsRead(notifId)
                                },
                                onMarkAllAsRead = {
                                    marketplaceViewModel.markAllNotificationsAsRead()
                                },
                                onDeleteNotification = { notifId ->
                                    marketplaceViewModel.deleteNotification(notifId)
                                },
                                onClearAllNotifications = {
                                    marketplaceViewModel.clearAllNotifications()
                                },
                                onNavigateToRoute = { route ->
                                    try {
                                        if (route == "my_bookings" || route == "farm_bookings" || route == "orders") {
                                            navController.navigate(Screen.Orders.route)
                                        } else {
                                            navController.navigate(route)
                                        }
                                    } catch (_: Exception) {
                                        navController.navigate(Screen.Marketplace.route)
                                    }
                                },
                                onNotificationClick = { notification ->
                                    val route = notification.deepLinkRoute
                                        ?: com.example.util.DeepLinkUtils.resolveDeepLinkRoute(notification.type, notification.referenceId)
                                    val payload = com.example.util.NotificationDeepLinkPayload(
                                        notificationId = notification.id,
                                        route = route,
                                        referenceId = notification.referenceId,
                                        notificationType = notification.type,
                                        recipientUserId = notification.recipientUserId,
                                        intentKey = "inapp_${notification.id}_${System.currentTimeMillis()}"
                                    )
                                    marketplaceViewModel.queueNotificationDeepLink(payload)
                                }
                            )
                        }

                        // --- LEGAL & POLICIES ROUTES ---
                        composable(Screen.PrivacyPolicy.route) {
                            LegalPolicyScreen(
                                initialTab = 0,
                                onBackClick = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.TermsConditions.route) {
                            LegalPolicyScreen(
                                initialTab = 1,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingNotificationIntent(intent)
    }

    private fun handleIncomingNotificationIntent(intent: android.content.Intent?) {
        if (intent == null) return

        val notifId = intent.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_NOTIFICATION_ID)
        val route = intent.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_DEEP_LINK_ROUTE)
        val refId = intent.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_REFERENCE_ID)
        val typeStr = intent.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_NOTIFICATION_TYPE)
        val recipientId = intent.getStringExtra(com.example.core.notification.NotificationHelper.EXTRA_RECIPIENT_USER_ID)
        val timestamp = intent.getLongExtra(com.example.core.notification.NotificationHelper.EXTRA_INTENT_TIMESTAMP, 0L)

        val type = typeStr?.let {
            try { com.example.model.NotificationType.valueOf(it) } catch (_: Exception) { null }
        }

        if (!notifId.isNullOrBlank() || !route.isNullOrBlank() || type != null || !refId.isNullOrBlank()) {
            val key = notifId?.takeIf { it.isNotBlank() }
                ?: "intent_${timestamp}_${route}_${refId}_${typeStr}"

            val payload = com.example.util.NotificationDeepLinkPayload(
                notificationId = notifId,
                route = route,
                referenceId = refId,
                notificationType = type,
                recipientUserId = recipientId,
                intentKey = key
            )

            marketplaceViewModel.queueNotificationDeepLink(payload)

            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_NOTIFICATION_ID)
            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_DEEP_LINK_ROUTE)
            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_REFERENCE_ID)
            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_NOTIFICATION_TYPE)
            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_RECIPIENT_USER_ID)
            intent.removeExtra(com.example.core.notification.NotificationHelper.EXTRA_INTENT_TIMESTAMP)
        }
    }
}
