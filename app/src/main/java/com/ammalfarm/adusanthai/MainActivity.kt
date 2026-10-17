package com.ammalfarm.adusanthai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ammalfarm.adusanthai.ui.navigation.Screen
import com.ammalfarm.adusanthai.ui.screens.*
import com.ammalfarm.adusanthai.ui.screens.auth.*
import com.ammalfarm.adusanthai.ui.theme.AmmalFarmTheme
import com.ammalfarm.adusanthai.ui.viewmodel.AuthViewModel
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AmmalFarmApplication
        val authRepository = app.container.authRepository
        val marketplaceRepository = app.container.marketplaceRepository

        setContent {
            AmmalFarmTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return AuthViewModel(authRepository) as T
                    }
                })
                val marketplaceViewModel: MarketplaceViewModel = viewModel(factory = MarketplaceViewModel.provideFactory(marketplaceRepository))

                val uiState by authViewModel.uiState.collectAsState()
                val marketplaceUiState by marketplaceViewModel.uiState.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Login.route
                    ) {
                        composable(Screen.Login.route) {
                            LoginScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                onNavigateByRole = { role ->
                                    when (role) {
                                        com.ammalfarm.adusanthai.model.UserRole.FARM_ADMIN -> navController.navigate(Screen.FarmAdmin.route) {
                                            popUpTo(Screen.Login.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                        com.ammalfarm.adusanthai.model.UserRole.SUPER_ADMIN -> navController.navigate(Screen.SuperAdmin.route) {
                                            popUpTo(Screen.Login.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                        else -> navController.navigate(Screen.MarketplaceHome.route) {
                                            popUpTo(Screen.Login.route) { inclusive = true }
                                            launchSingleTop = true
                                        }
                                    }
                                },
                                onNavigateToRegister = { navController.navigate(Screen.Register.createRoute(isFarm = false)) },
                                onNavigateToRegisterFarm = { navController.navigate(Screen.Register.createRoute(isFarm = true)) },
                                onNavigateToForgotPassword = { navController.navigate("forgot_password") }
                            )
                        }
                        composable(Screen.Register.route) { backStackEntry ->
                            val isFarmArg = backStackEntry.arguments?.getString("isFarm")?.toBoolean() ?: false
                            RegisterScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                initialFarmMode = isFarmArg,
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) { popUpTo(Screen.Register.route) { inclusive = true } } },
                                onRegistrationSuccess = { role ->
                                    when (role) {
                                        com.ammalfarm.adusanthai.model.UserRole.FARM_ADMIN -> navController.navigate(Screen.FarmAdmin.route) { popUpTo(Screen.Register.route) { inclusive = true } }
                                        else -> navController.navigate(Screen.Login.route) { popUpTo(Screen.Register.route) { inclusive = true } }
                                    }
                                }
                            )
                        }
                        composable("forgot_password") {
                            ForgotPasswordScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.Profile.route) {
                            ProfileScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                onLogoutSuccess = { navController.navigate(Screen.Login.route) { popUpTo(Screen.Profile.route) { inclusive = true } } },
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                                onNavigateToOrders = { navController.navigate("orders") },
                                onNavigateToWishlist = { navController.navigate(Screen.Wishlist.route) },
                                onNavigateToSuperAdmin = { navController.navigate(Screen.SuperAdmin.route) },
                                onNavigateToFarmAdmin = { navController.navigate(Screen.FarmAdmin.route) }
                            )
                        }
                        composable(Screen.MarketplaceHome.route) {
                            MarketplaceHomeScreen(
                                marketplaceViewModel = marketplaceViewModel,
                                marketplaceUiState = marketplaceUiState,
                                onNavigateToProfile = { navController.navigate(Screen.Profile.route) },
                                onNavigateToWishlist = { navController.navigate(Screen.Wishlist.route) },
                                onNavigateToBookings = { navController.navigate("bookings") },
                                onNavigateToOrders = { navController.navigate("orders") },
                                onNavigateToGoatDetail = { goatId -> navController.navigate("goat_detail/$goatId") }
                            )
                        }
                        composable(
                            route = "goat_detail/{goatId}",
                            arguments = listOf(navArgument("goatId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val goatId = backStackEntry.arguments?.getString("goatId") ?: ""
                            GoatDetailScreen(
                                goatId = goatId,
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("bookings") {
                            BookingsScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable("orders") {
                            OrdersScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                        composable(Screen.FarmAdmin.route) {
                            FarmAdminScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                marketplaceViewModel = marketplaceViewModel,
                                marketplaceUiState = marketplaceUiState,
                                onNavigateToProfile = { navController.navigate(Screen.Profile.route) }
                            )
                        }
                        composable(Screen.SuperAdmin.route) {
                            SuperAdminScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                marketplaceViewModel = marketplaceViewModel,
                                marketplaceUiState = marketplaceUiState,
                                onNavigateToProfile = { navController.navigate(Screen.Profile.route) }
                            )
                        }
                        composable(Screen.Wishlist.route) {
                            WishlistScreen(
                                onNavigateBack = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
