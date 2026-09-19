package com.example.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.model.UserRole
import com.example.ui.viewmodel.AuthUiState
import com.example.ui.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    policyConsentManager: com.example.core.util.PolicyConsentManager? = null,
    onNavigateToLogin: () -> Unit,
    onRegistrationSuccess: (UserRole) -> Unit,
    initialFarmMode: Boolean = false,
    onNavigateToForgotPassword: () -> Unit = {},
    onNavigateToPrivacyPolicy: () -> Unit = {},
    onNavigateToTermsConditions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val consentManager = remember(policyConsentManager) {
        policyConsentManager ?: com.example.core.util.PolicyConsentManager(context)
    }
    var selectedTab by remember { mutableIntStateOf(if (initialFarmMode) 1 else 0) } // 0: Customer, 1: Farm Partner

    // Common fields
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var acceptedPolicies by remember { mutableStateOf(false) }

    // Farm specific fields
    var farmName by remember { mutableStateOf("") }
    var farmDistrict by remember { mutableStateOf("Madurai") }
    var farmDescription by remember { mutableStateOf("") }
    var showEmailConfirmationDialog by remember { mutableStateOf(false) }
    var showFarmPartnerPendingDialog by remember { mutableStateOf(false) }
    var registeredEmail by remember { mutableStateOf("") }

    val districts = remember { com.example.core.util.FarmLocations.TAMIL_NADU_DISTRICTS }
    var districtDropdownExpanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Account", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateToLogin) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to login")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Account Type Selector Tabs
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                modifier = Modifier.fillMaxWidth()
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        authViewModel.clearMessages()
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Customer", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        authViewModel.clearMessages()
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Farm Partner", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                )
            }

            // Info notice for Farm Partner
            if (selectedTab == 1) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Farm Partner Onboarding Notice",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = "Your farm account will be registered in PENDING status. Super Admin will verify your farm details before goat listings can be published to the public marketplace.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // Error banner with account resolution actions
            if (uiState.errorMessage != null) {
                val isAccountAlreadyExists = uiState.errorMessage.contains("already exists", ignoreCase = true) ||
                    uiState.errorMessage.contains("already registered", ignoreCase = true)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isAccountAlreadyExists) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.errorContainer,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isAccountAlreadyExists) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isAccountAlreadyExists) Icons.Default.Info else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (isAccountAlreadyExists) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                            Text(
                                text = if (isAccountAlreadyExists) "Account Already Registered" else "Registration Notice",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isAccountAlreadyExists) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }

                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAccountAlreadyExists) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )

                        if (isAccountAlreadyExists) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        authViewModel.prepareSignIn(email)
                                        onNavigateToLogin()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sign In", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        authViewModel.prepareSignIn(email)
                                        onNavigateToForgotPassword()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Reset Password", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // Common User Info Fields
            OutlinedTextField(
                value = fullName,
                onValueChange = {
                    fullName = it
                    if (uiState.errorMessage != null) authViewModel.clearMessages()
                },
                label = { Text("Full Name *") },
                placeholder = { Text(if (selectedTab == 0) "e.g. Full Name" else "e.g. Authorized Farm Representative") },
                leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            OutlinedTextField(
                value = email,
                onValueChange = {
                    email = it
                    if (uiState.errorMessage != null) authViewModel.clearMessages()
                },
                label = { Text("Email Address *") },
                placeholder = { Text("e.g. user@example.com") },
                leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone Number *") },
                placeholder = { Text("e.g. +91 98000 00000") },
                leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            // Farm-Specific Section
            if (selectedTab == 1) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Column {
                            Text(
                                text = "Partner Farm Application",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Partner applications are created with status PENDING for Super Admin review and verification.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Farm Information",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                OutlinedTextField(
                    value = farmName,
                    onValueChange = { farmName = it },
                    label = { Text("Farm / Ranch Name *") },
                    placeholder = { Text("e.g. Green Valley Livestock Farm") },
                    leadingIcon = { Icon(Icons.Outlined.Store, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                // District Dropdown
                ExposedDropdownMenuBox(
                    expanded = districtDropdownExpanded,
                    onExpandedChange = { districtDropdownExpanded = !districtDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = farmDistrict,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("District (Tamil Nadu) *") },
                        leadingIcon = { Icon(Icons.Outlined.LocationOn, contentDescription = null) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = districtDropdownExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = districtDropdownExpanded,
                        onDismissRequest = { districtDropdownExpanded = false }
                    ) {
                        districts.forEach { district ->
                            DropdownMenuItem(
                                text = { Text(district) },
                                onClick = {
                                    farmDistrict = district
                                    districtDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = farmDescription,
                    onValueChange = { farmDescription = it },
                    label = { Text("Farm Description / Breeds Maintained") },
                    placeholder = { Text("e.g. 5-acre breeding farm specializing in pure Boer and Tellicherry stock.") },
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // Password Fields
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (min 6 characters) *") },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = null
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password *") },
                leadingIcon = { Icon(Icons.Outlined.LockReset, contentDescription = null) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            // Mandatory Terms & Privacy Agreement Checkbox
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (acceptedPolicies) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(
                    1.dp,
                    if (acceptedPolicies) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("register_policy_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { acceptedPolicies = !acceptedPolicies }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = acceptedPolicies,
                        onCheckedChange = { acceptedPolicies = it },
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("checkbox_register_policy_terms")
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "I agree to the",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Privacy Policy",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onNavigateToPrivacyPolicy() }
                            )
                            Text(
                                text = "&",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Terms & Conditions",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onNavigateToTermsConditions() }
                            )
                        }
                        Text(
                            text = if (acceptedPolicies) "Policies accepted. You can create your account." else "Mandatory: You must accept policies to create an account.",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (acceptedPolicies) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Submit Button (Enabled ONLY when policies are accepted and fields filled)
            val canSubmit = !uiState.isLoading && acceptedPolicies &&
                fullName.isNotBlank() && email.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank() && phone.isNotBlank() &&
                (selectedTab == 0 || (farmName.isNotBlank() && farmDistrict.isNotBlank()))

            Button(
                onClick = {
                    focusManager.clearFocus()
                    if (!acceptedPolicies) {
                        android.widget.Toast.makeText(context, "Please accept the Privacy Policy and Terms & Conditions to create an account.", android.widget.Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    if (selectedTab == 0) {
                        authViewModel.registerCustomer(
                            name = fullName,
                            email = email,
                            password = password,
                            confirmPass = confirmPassword,
                            phone = phone,
                            onSuccess = { requiresVerification ->
                                consentManager.setPoliciesAccepted(true)
                                registeredEmail = email.trim()
                                if (requiresVerification) {
                                    showEmailConfirmationDialog = true
                                } else {
                                    onRegistrationSuccess(UserRole.CUSTOMER)
                                }
                            }
                        )
                    } else {
                        authViewModel.registerFarmAdmin(
                            name = fullName,
                            email = email,
                            password = password,
                            confirmPass = confirmPassword,
                            phone = phone,
                            farmName = farmName,
                            farmDistrict = farmDistrict,
                            farmDescription = farmDescription,
                            onSuccess = { requiresVerification ->
                                consentManager.setPoliciesAccepted(true)
                                registeredEmail = email.trim()
                                if (requiresVerification) {
                                    showEmailConfirmationDialog = true
                                } else {
                                    showFarmPartnerPendingDialog = true
                                }
                            }
                        )
                    }
                },
                enabled = canSubmit,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("button_create_account")
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text = if (!acceptedPolicies) {
                            "Accept Policies to Create Account"
                        } else if (selectedTab == 0) {
                            "Create Customer Account"
                        } else {
                            "Submit Farm Application"
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Back to Login
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Already have an account? ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "Sign In",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToLogin() }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showEmailConfirmationDialog || uiState.emailConfirmationRequired) {
        val targetEmail = if (registeredEmail.isNotBlank()) registeredEmail else email
        AlertDialog(
            onDismissRequest = { /* require explicit action */ },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            icon = {
                Icon(
                    Icons.Outlined.MarkEmailRead,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Verify Your Email",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Account created successfully. A verification link has been sent to $targetEmail.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Please check your inbox (and spam folder) to verify your email before signing in.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (uiState.successMessage != null && uiState.successMessage.contains("resent", ignoreCase = true)) {
                        Text(
                            text = uiState.successMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showEmailConfirmationDialog = false
                        onNavigateToLogin()
                    },
                    modifier = Modifier.testTag("button_confirm_go_to_login")
                ) {
                    Text("Proceed to Sign In")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        authViewModel.resendEmailVerification(targetEmail)
                    },
                    modifier = Modifier.testTag("button_resend_verification")
                ) {
                    Text("Resend Email")
                }
            }
        )
    }

    if (showFarmPartnerPendingDialog) {
        AlertDialog(
            onDismissRequest = { /* require explicit action */ },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            icon = {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Application Under Review",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Your Farm Partner account and farm profile have been submitted successfully!",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Status: PENDING VERIFICATION",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        text = "Your application is currently under review by Super Admin. Farm listings and public marketplace visibility will be enabled once your farm verification is completed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFarmPartnerPendingDialog = false
                        onRegistrationSuccess(UserRole.FARM_ADMIN)
                    },
                    modifier = Modifier.testTag("button_farm_pending_continue")
                ) {
                    Text("Understood")
                }
            }
        )
    }
}
