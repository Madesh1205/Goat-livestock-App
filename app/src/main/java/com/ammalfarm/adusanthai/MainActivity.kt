package com.ammalfarm.adusanthai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ammalfarm.adusanthai.di.AppContainer
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.ui.navigation.Screen
import com.ammalfarm.adusanthai.ui.screens.*
import com.ammalfarm.adusanthai.ui.screens.auth.ForgotPasswordScreen
import com.ammalfarm.adusanthai.ui.screens.auth.LoginScreen
import com.ammalfarm.adusanthai.ui.screens.auth.ProfileScreen
import com.ammalfarm.adusanthai.ui.screens.auth.RegisterScreen
import com.ammalfarm.adusanthai.ui.theme.AmmalFarmTheme
import com.ammalfarm.adusanthai.ui.viewmodel.AuthViewModel
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as AmmalFarmApplication).container

        setContent {
            val currentThemeMode by appContainer.themeManager.themeMode.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val isDarkTheme = when (currentThemeMode) {
                com.ammalfarm.adusanthai.ui.theme.ThemeMode.LIGHT -> false
                com.ammalfarm.adusanthai.ui.theme.ThemeMode.DARK -> true
                com.ammalfarm.adusanthai.ui.theme.ThemeMode.SYSTEM -> systemDark
            }

            AmmalFarmTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val authViewModel: AuthViewModel = viewModel(
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return AuthViewModel(appContainer.authRepository) as T
                            }
                        }
                    )
                    val marketplaceViewModel: MarketplaceViewModel = viewModel(
                        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                                return MarketplaceViewModel(appContainer.marketplaceRepository) as T
                            }
                        }
                    )

                    val authUiState by authViewModel.uiState.collectAsStateWithLifecycle()
                    val marketplaceUiState by marketplaceViewModel.uiState.collectAsStateWithLifecycle()

                    fun destinationForRole(role: UserRole): String {
                        return when (role) {
                            UserRole.SUPER_ADMIN -> Screen.SuperAdmin.route
                            UserRole.FARM_ADMIN -> Screen.FarmAdmin.route
                            UserRole.CUSTOMER -> Screen.MarketplaceHome.route
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = Screen.Splash.route
                    ) {
                        composable(Screen.Splash.route) {
                            SplashScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                onNavigateToLogin = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(Screen.Splash.route) { inclusive = true }
                                    }
                                },
                                onNavigateByRole = { role ->
                                    marketplaceViewModel.refreshMarketplace()
                                    marketplaceViewModel.loadUserSpecificData(authUiState.currentUser)
                                    val dest = destinationForRole(role)
                                    navController.navigate(dest) {
                                        popUpTo(Screen.Splash.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.Login.route) {
                            LoginScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                onNavigateToRegister = {
                                    navController.navigate(Screen.Register.createRoute(false))
                                },
                                onNavigateToForgotPassword = {
                                    navController.navigate("forgot_password")
                                },
                                onNavigateByRole = { role ->
                                    marketplaceViewModel.refreshMarketplace()
                                    marketplaceViewModel.loadUserSpecificData(authUiState.currentUser)
                                    val dest = destinationForRole(role)
                                    navController.navigate(dest) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(
                            route = Screen.Register.route,
                            arguments = listOf(navArgument("isFarm") { type = NavType.BoolType; defaultValue = false })
                        ) { backStackEntry ->
                            val isFarm = backStackEntry.arguments?.getBoolean("isFarm") ?: false
                            RegisterScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                initialFarmMode = isFarm,
                                onNavigateToLogin = { navController.popBackStack() },
                                onNavigateToForgotPassword = { navController.navigate("forgot_password") },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.TermsConditions.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) },
                                onRegistrationSuccess = { role ->
                                    marketplaceViewModel.refreshMarketplace()
                                    marketplaceViewModel.loadUserSpecificData(authUiState.currentUser)
                                    val dest = destinationForRole(role)
                                    navController.navigate(dest) {
                                        popUpTo(Screen.Login.route) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable("forgot_password") {
                            ForgotPasswordScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                onNavigateBack = { navController.popBackStack() },
                                onPasswordResetSuccess = {
                                    navController.popBackStack(Screen.Login.route, inclusive = false)
                                }
                            )
                        }

                        composable(Screen.MarketplaceHome.route) {
                            if (!authUiState.isAuthenticated || authUiState.currentUser == null) {
                                LaunchedEffect(authUiState.isAuthenticated, authUiState.currentUser) {
                                    if (!authUiState.isLoading) {
                                        navController.navigate(Screen.Login.route) {
                                            popUpTo(Screen.MarketplaceHome.route) { inclusive = true }
                                        }
                                    }
                                }
                            } else {
                                MarketplaceHomeScreen(
                                    marketplaceViewModel = marketplaceViewModel,
                                    currentUser = authUiState.currentUser,
                                    onNavigateToGoatDetail = { goatId ->
                                        navController.navigate(Screen.GoatDetail.createRoute(goatId))
                                    },
                                    onNavigateToFarmDetail = { farmId ->
                                        navController.navigate(Screen.FarmDetail.createRoute(farmId))
                                    },
                                    onNavigateToWishlist = {
                                        navController.navigate(Screen.Wishlist.route)
                                    },
                                    onNavigateToBookings = {
                                        navController.navigate(Screen.Bookings.route)
                                    },
                                    onNavigateToOrders = {
                                        navController.navigate(Screen.Orders.route)
                                    },
                                    onNavigateToNotifications = {
                                        navController.navigate(Screen.Notifications.route)
                                    },
                                    onNavigateToProfile = {
                                        navController.navigate(Screen.Profile.route)
                                    },
                                    onNavigateToAdminDashboard = {
                                        when (authUiState.currentUser?.role) {
                                            UserRole.SUPER_ADMIN -> navController.navigate(Screen.SuperAdmin.route)
                                            UserRole.FARM_ADMIN -> navController.navigate(Screen.FarmAdmin.route)
                                            else -> {}
                                        }
                                    }
                                )
                            }
                        }

                        composable(
                            route = Screen.GoatDetail.route,
                            arguments = listOf(navArgument("goatId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val goatId = backStackEntry.arguments?.getString("goatId") ?: ""
                            GoatDetailScreen(
                                goatId = goatId,
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToFarmDetail = { farmId ->
                                    navController.navigate(Screen.FarmDetail.createRoute(farmId))
                                },
                                onNavigateToBookings = {
                                    navController.navigate(Screen.Bookings.route)
                                }
                            )
                        }

                        composable(
                            route = Screen.FarmDetail.route,
                            arguments = listOf(navArgument("farmId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val farmId = backStackEntry.arguments?.getString("farmId") ?: ""
                            FarmDetailScreen(
                                farmId = farmId,
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToGoatDetail = { goatId ->
                                    navController.navigate(Screen.GoatDetail.createRoute(goatId))
                                }
                            )
                        }

                        composable(Screen.Wishlist.route) {
                            WishlistScreen(
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToGoatDetail = { goatId ->
                                    navController.navigate(Screen.GoatDetail.createRoute(goatId))
                                }
                            )
                        }

                        composable(Screen.Bookings.route) {
                            BookingsScreen(
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToGoatDetail = { goatId ->
                                    navController.navigate(Screen.GoatDetail.createRoute(goatId))
                                }
                            )
                        }

                        composable(Screen.Orders.route) {
                            OrdersScreen(
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Notifications.route) {
                            NotificationCenterScreen(
                                marketplaceViewModel = marketplaceViewModel,
                                currentUser = authUiState.currentUser,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }

                        composable(Screen.Profile.route) {
                            ProfileScreen(
                                authViewModel = authViewModel,
                                uiState = authUiState,
                                wishlistCount = marketplaceUiState.wishlistedIds.size,
                                currentThemeMode = currentThemeMode,
                                onThemeModeChanged = { mode ->
                                    appContainer.themeManager.setThemeMode(mode)
                                },
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLogin = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                },
                                onNavigateToSuperAdmin = {
                                    navController.navigate(Screen.SuperAdmin.route)
                                },
                                onNavigateToFarmAdmin = {
                                    navController.navigate(Screen.FarmAdmin.route)
                                },
                                onNavigateToOrders = { navController.navigate(Screen.Orders.route) },
                                onNavigateToWishlist = { navController.navigate(Screen.Wishlist.route) },
                                onNavigateToPrivacyPolicy = { navController.navigate(Screen.TermsConditions.route) },
                                onNavigateToTermsConditions = { navController.navigate(Screen.TermsConditions.route) },
                                onLogoutSuccess = {
                                    navController.navigate(Screen.Login.route) {
                                        popUpTo(0) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(Screen.SuperAdmin.route) {
                            val currentRole = authUiState.currentUser?.role
                            if (currentRole != UserRole.SUPER_ADMIN) {
                                LaunchedEffect(currentRole) {
                                    val fallback = if (currentRole == UserRole.FARM_ADMIN) Screen.FarmAdmin.route
                                    else if (authUiState.isAuthenticated) Screen.MarketplaceHome.route
                                    else Screen.Login.route
                                    navController.navigate(fallback) {
                                        popUpTo(Screen.SuperAdmin.route) { inclusive = true }
                                    }
                                }
                            } else {
                                SuperAdminScreen(
                                    marketplaceViewModel = marketplaceViewModel,
                                    currentUser = authUiState.currentUser,
                                    onNavigateToMarketplace = { navController.navigate(Screen.MarketplaceHome.route) },
                                    onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                                    onNavigateToNotifications = { navController.navigate(Screen.Notifications.route) },
                                    onLogout = {
                                        authViewModel.logout {
                                            navController.navigate(Screen.Login.route) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        composable(Screen.FarmAdmin.route) {
                            val currentRole = authUiState.currentUser?.role
                            if (currentRole != UserRole.FARM_ADMIN && currentRole != UserRole.SUPER_ADMIN) {
                                LaunchedEffect(currentRole) {
                                    val fallback = if (authUiState.isAuthenticated) Screen.MarketplaceHome.route else Screen.Login.route
                                    navController.navigate(fallback) {
                                        popUpTo(Screen.FarmAdmin.route) { inclusive = true }
                                    }
                                }
                            } else {
                                FarmAdminScreen(
                                    marketplaceViewModel = marketplaceViewModel,
                                    currentUser = authUiState.currentUser,
                                    userFarm = authUiState.currentFarm,
                                    onNavigateToMarketplace = { navController.navigate(Screen.MarketplaceHome.route) },
                                    onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                                    onNavigateToNotifications = { navController.navigate(Screen.Notifications.route) },
                                    onLogout = {
                                        authViewModel.logout {
                                            navController.navigate(Screen.Login.route) {
                                                popUpTo(0) { inclusive = true }
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        composable(Screen.TermsConditions.route) {
                            LegalPolicyScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
