package com.ammalfarm.adusanthai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ammalfarm.adusanthai.ui.navigation.Screen
import com.ammalfarm.adusanthai.ui.screens.auth.LoginScreen
import com.ammalfarm.adusanthai.ui.screens.auth.ProfileScreen
import com.ammalfarm.adusanthai.ui.screens.auth.RegisterScreen
import com.ammalfarm.adusanthai.ui.theme.AmmalFarmTheme
import com.ammalfarm.adusanthai.ui.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AmmalFarmApplication
        val authRepository = app.container.authRepository

        setContent {
            AmmalFarmTheme {
                val navController = rememberNavController()
                val authViewModel: AuthViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                        return AuthViewModel(authRepository) as T
                    }
                })
                val uiState by authViewModel.uiState.collectAsState()

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
                                        com.ammalfarm.adusanthai.model.UserRole.FARM_ADMIN -> navController.navigate(Screen.FarmAdmin.route) { popUpTo(Screen.Login.route) { inclusive = true } }
                                        com.ammalfarm.adusanthai.model.UserRole.SUPER_ADMIN -> navController.navigate(Screen.SuperAdmin.route) { popUpTo(Screen.Login.route) { inclusive = true } }
                                        else -> navController.navigate(Screen.MarketplaceHome.route) { popUpTo(Screen.Login.route) { inclusive = true } }
                                    }
                                },
                                onNavigateToRegister = { navController.navigate(Screen.Register.createRoute(isFarm = false)) },
                                onNavigateToRegisterFarm = { navController.navigate(Screen.Register.createRoute(isFarm = true)) },
                                onNavigateToForgotPassword = {}
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
                        composable(Screen.Profile.route) {
                            ProfileScreen(
                                authViewModel = authViewModel,
                                uiState = uiState,
                                onLogoutSuccess = { navController.navigate(Screen.Login.route) { popUpTo(Screen.Profile.route) { inclusive = true } } },
                                onNavigateBack = { navController.popBackStack() },
                                onNavigateToLogin = { navController.navigate(Screen.Login.route) }
                            )
                        }
                        composable(Screen.MarketplaceHome.route) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("Marketplace Home (Welcome!)", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        composable(Screen.FarmAdmin.route) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("Farm Admin Dashboard", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                        composable(Screen.SuperAdmin.route) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                Text("Super Admin Dashboard", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}
