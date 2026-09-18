package com.example.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.UserRole
import com.example.model.VerificationStatus
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.AuthUiState
import com.example.ui.viewmodel.AuthViewModel
import com.example.util.PhoneValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    wishlistCount: Int = 0,
    currentThemeMode: ThemeMode = ThemeMode.DARK,
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onLogoutSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToWishlist: () -> Unit = {},
    onNavigateToSuperAdmin: () -> Unit = {},
    onNavigateToFarmAdmin: () -> Unit = {},
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTermsConditions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val user = uiState.currentUser
    val farm = uiState.currentFarm
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember(user) { mutableStateOf(user?.name ?: "") }
    var editPhone by remember(user) { mutableStateOf(user?.phone ?: "") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Account", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (user != null) {
                        IconButton(onClick = {
                            authViewModel.refreshUserProfile()
                        }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh"
                            )
                        }
                        IconButton(onClick = {
                            if (isEditing) {
                                val phoneErr = if (editPhone.isNotBlank()) PhoneValidator.getValidationErrorMessage(editPhone) else null
                                if (phoneErr == null) {
                                    authViewModel.updateProfile(editName, editPhone)
                                    isEditing = false
                                }
                            } else {
                                isEditing = true
                            }
                        }) {
                            Icon(
                                imageVector = if (isEditing) Icons.Default.Check else Icons.Default.Edit,
                                contentDescription = if (isEditing) "Save" else "Edit"
                            )
                        }
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (user == null) {
            // Guest state
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Outlined.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "You are not logged in",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Sign in to manage bookings, publish livestock, or manage your farm.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onNavigateToLogin,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    Text("Sign In / Register", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Guest Appearance Card
                ThemeSelectionCard(
                    currentThemeMode = currentThemeMode,
                    onThemeModeChanged = onThemeModeChanged,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // User Header Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = user.name.take(2).uppercase(),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Text(
                            text = user.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = user.email,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Role Badge
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = when (user.role) {
                                UserRole.SUPER_ADMIN -> MaterialTheme.colorScheme.errorContainer
                                UserRole.FARM_ADMIN -> MaterialTheme.colorScheme.primaryContainer
                                UserRole.CUSTOMER -> MaterialTheme.colorScheme.secondaryContainer
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = when (user.role) {
                                        UserRole.SUPER_ADMIN -> Icons.Default.AdminPanelSettings
                                        UserRole.FARM_ADMIN -> Icons.Default.Agriculture
                                        UserRole.CUSTOMER -> Icons.Default.Person
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = when (user.role) {
                                        UserRole.SUPER_ADMIN -> MaterialTheme.colorScheme.onErrorContainer
                                        UserRole.FARM_ADMIN -> MaterialTheme.colorScheme.onPrimaryContainer
                                        UserRole.CUSTOMER -> MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                )
                                Text(
                                    text = when (user.role) {
                                        UserRole.SUPER_ADMIN -> "Super Admin"
                                        UserRole.FARM_ADMIN -> "Farm Partner Admin"
                                        UserRole.CUSTOMER -> "Verified Customer"
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = when (user.role) {
                                        UserRole.SUPER_ADMIN -> MaterialTheme.colorScheme.onErrorContainer
                                        UserRole.FARM_ADMIN -> MaterialTheme.colorScheme.onPrimaryContainer
                                        UserRole.CUSTOMER -> MaterialTheme.colorScheme.onSecondaryContainer
                                    }
                                 )
                            }
                        }

                        // Quick Access Actions for Admin Roles
                        if (user.role == UserRole.SUPER_ADMIN) {
                            Button(
                                onClick = onNavigateToSuperAdmin,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AdminPanelSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Open Super Admin Console", fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = onNavigateToFarmAdmin,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("My Farm / Ammal Farm", fontWeight = FontWeight.Bold)
                            }
                        } else if (user.role == UserRole.FARM_ADMIN) {
                            Button(
                                onClick = onNavigateToFarmAdmin,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Farm Partner Portal", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // Theme & Appearance Selection Card
                ThemeSelectionCard(
                    currentThemeMode = currentThemeMode,
                    onThemeModeChanged = onThemeModeChanged,
                    modifier = Modifier.fillMaxWidth()
                )

                // Saved Livestock / Wishlist Card (Available for all authenticated users)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToWishlist() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFEBEE)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = "Saved Livestock",
                                    tint = Color(0xFFE91E63),
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = "Saved Livestock & Wishlist",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (wishlistCount > 0) "$wishlistCount saved ${if (wishlistCount == 1) "goat" else "goats"}" else "No saved livestock yet",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Open Wishlist",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Farm Status Card (For Farm Admins)
                if (user.role == UserRole.FARM_ADMIN) {
                    val farmStatus = farm?.verificationStatus ?: VerificationStatus.PENDING
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = when (farmStatus) {
                                VerificationStatus.APPROVED -> MaterialTheme.colorScheme.surface
                                VerificationStatus.PENDING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                else -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                            }
                        ),
                        border = BorderStroke(
                            1.dp,
                            when (farmStatus) {
                                VerificationStatus.APPROVED -> MaterialTheme.colorScheme.outlineVariant
                                VerificationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.error
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = farm?.name ?: "Farm Profile",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = when (farmStatus) {
                                        VerificationStatus.APPROVED -> Color(0xFF2E7D32)
                                        VerificationStatus.PENDING -> Color(0xFFED6C02)
                                        else -> Color(0xFFD32F2F)
                                    }
                                ) {
                                    Text(
                                        text = farmStatus.name,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Location: ${farm?.location ?: "Tamil Nadu"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (farmStatus == VerificationStatus.PENDING) {
                                Text(
                                    text = "⚠️ Farm Verification Pending: Super Admin must review and approve your farm credentials before public goat listings can be published.",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            } else if (farmStatus == VerificationStatus.APPROVED) {
                                Text(
                                    text = "✓ Verified Partner: Full access enabled to create listings and manage reservations.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                    }
                }

                // Profile Fields Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Contact Details",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        if (isEditing) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("Full Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            val phoneError = if (editPhone.isNotBlank()) PhoneValidator.getValidationErrorMessage(editPhone) else null
                            OutlinedTextField(
                                value = editPhone,
                                onValueChange = { editPhone = it },
                                label = { Text("Phone Number") },
                                placeholder = { Text("e.g. 9876543210") },
                                isError = phoneError != null,
                                supportingText = {
                                    if (phoneError != null) {
                                        Text(phoneError, color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text("10-digit Indian mobile number (starts with 6, 7, 8, or 9)", fontSize = 11.sp)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Outlined.Phone,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text("Phone", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(
                                            text = if (user.phone.isNotBlank()) PhoneValidator.formatDisplay(user.phone) else "Not provided",
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (user.phone.isBlank()) {
                                    TextButton(
                                        onClick = { isEditing = true },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Add Phone", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Email, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("Email", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(user.email, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // Legal & Compliance Card
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Legal & Compliance",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        // Privacy Policy row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToPrivacyPolicy() }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Privacy Policy",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Data protection, security standards & user rights",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Open Privacy Policy",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Terms & Conditions row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNavigateToTermsConditions() }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Gavel,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Terms & Conditions",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "48-Hour reservations, farm rules & welfare",
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "Open Terms & Conditions",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        val context = androidx.compose.ui.platform.LocalContext.current
                        val consentManager = remember { com.example.core.util.PolicyConsentManager(context) }
                        val acceptedDate = remember { consentManager.getAcceptedDateFormatted() }
                        val isPolicyAccepted = remember { consentManager.hasAcceptedPolicies() }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPolicyAccepted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPolicyAccepted) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    tint = if (isPolicyAccepted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isPolicyAccepted) {
                                        if (acceptedDate != null) "Policies Accepted on $acceptedDate" else "Policies & Terms Accepted (Active)"
                                    } else {
                                        "Policies & Terms Accepted during registration"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                // Logout Button
                OutlinedButton(
                    onClick = {
                        authViewModel.logout(onLogoutSuccess)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.Default.Logout, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign Out", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun ThemeSelectionCard(
    currentThemeMode: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (currentThemeMode) {
                                ThemeMode.LIGHT -> Icons.Default.LightMode
                                ThemeMode.DARK -> Icons.Default.DarkMode
                                ThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                            },
                            contentDescription = "Theme Icon",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "App Appearance & Theme",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Switch between Dark and Light mode anytime",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 3-Option Segmented Control Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThemeOptionButton(
                    title = "Light",
                    icon = Icons.Default.LightMode,
                    isSelected = currentThemeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChanged(ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionButton(
                    title = "Dark",
                    icon = Icons.Default.DarkMode,
                    isSelected = currentThemeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChanged(ThemeMode.DARK) },
                    modifier = Modifier.weight(1f)
                )
                ThemeOptionButton(
                    title = "System",
                    icon = Icons.Default.BrightnessAuto,
                    isSelected = currentThemeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChanged(ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionButton(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = modifier.height(44.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
