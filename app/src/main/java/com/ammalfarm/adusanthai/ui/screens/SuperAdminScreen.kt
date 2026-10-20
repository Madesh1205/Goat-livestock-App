package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.core.util.PlatformPricingManager
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminScreen(
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateToMarketplace: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    var selectedSection by remember { mutableIntStateOf(0) }
    // 0: Overview & Stats, 1: Farm Verification, 2: Goat Moderation, 3: Global Bookings, 4: Users & Reports
    var showAddAmmalGoatDialog by remember { mutableStateOf(false) }
    var editingGoat by remember { mutableStateOf<Goat?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        marketplaceViewModel?.refreshAdminData()
    }

    LaunchedEffect(uiState?.actionSuccessMessage, uiState?.actionErrorMessage) {
        uiState?.actionSuccessMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
        uiState?.actionErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
    }

    val ammalFarm = remember(uiState?.farms) {
        uiState?.farms?.find { it.isAmmalOwnFarm || it.id == SEED_AMMAL_FARM_UUID }
            ?: Farm(
                id = SEED_AMMAL_FARM_UUID,
                name = "Ammal Farm (Official)",
                location = "Madurai, Tamil Nadu",
                contactNumber = "+91 63808 98358",
                verificationStatus = VerificationStatus.VERIFIED,
                isAmmalOwnFarm = true,
                goatListingLimit = 999
            )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Super Admin Console", fontWeight = FontWeight.ExtraBold)
                        Text(
                            text = "Adu Santhai Platform Control & Moderation",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { marketplaceViewModel?.refreshAdminData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Platform Data")
                    }
                    IconButton(onClick = onNavigateToMarketplace) {
                        Icon(Icons.Default.Storefront, contentDescription = "Marketplace")
                    }
                    val unreadNotifications = remember(uiState?.notifications) {
                        uiState?.notifications.orEmpty().count { !it.isRead }
                    }
                    IconButton(onClick = onNavigateToNotifications) {
                        BadgedBox(
                            badge = {
                                if (unreadNotifications > 0) {
                                    Badge { Text(unreadNotifications.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Notifications")
                        }
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedSection == 0 || selectedSection == 2) {
                ExtendedFloatingActionButton(
                    onClick = { showAddAmmalGoatDialog = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Publish Ammal Goat") },
                    modifier = Modifier.testTag("fab_super_admin_add_goat")
                )
            }
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("Stats") }
                )
                NavigationBarItem(
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    icon = {
                        BadgedBox(
                            badge = {
                                val pendingFarms = uiState?.platformStats?.pendingFarms ?: 0
                                if (pendingFarms > 0) Badge { Text(pendingFarms.toString()) }
                            }
                        ) {
                            Icon(Icons.Default.Agriculture, contentDescription = null)
                        }
                    },
                    label = { Text("Farms") }
                )
                NavigationBarItem(
                    selected = selectedSection == 2,
                    onClick = { selectedSection = 2 },
                    icon = {
                        BadgedBox(
                            badge = {
                                val pendingGoats = uiState?.platformStats?.pendingGoats ?: 0
                                if (pendingGoats > 0) Badge { Text(pendingGoats.toString()) }
                            }
                        ) {
                            Icon(Icons.Default.Pets, contentDescription = null)
                        }
                    },
                    label = { Text("Goats") }
                )
                NavigationBarItem(
                    selected = selectedSection == 3,
                    onClick = { selectedSection = 3 },
                    icon = { Icon(Icons.Default.EventNote, contentDescription = null) },
                    label = { Text("Bookings") }
                )
                NavigationBarItem(
                    selected = selectedSection == 4,
                    onClick = { selectedSection = 4 },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Users") }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedSection) {
                0 -> SuperAdminStatsTab(
                    stats = uiState?.platformStats ?: PlatformStats(),
                    onUpdatePricing = { approvalFee, packFee, packSize ->
                        marketplaceViewModel?.updatePlatformPricing(approvalFee, packFee, packSize)
                    },
                    onLogout = onLogout
                )

                1 -> SuperAdminFarmsTab(
                    farms = uiState?.farms.orEmpty(),
                    allGoats = uiState?.allGoats.orEmpty(),
                    onVerifyFarm = { farmId, status ->
                        marketplaceViewModel?.moderateFarm(farmId, status)
                    },
                    onAddSlots = { farmId, slots ->
                        marketplaceViewModel?.increaseFarmSlots(farmId, slots)
                    }
                )

                2 -> SuperAdminGoatsModerationTab(
                    goats = uiState?.allGoats.orEmpty(),
                    onModerateGoat = { goatId, status, reason ->
                        marketplaceViewModel?.moderateGoatListing(goatId, status, reason)
                    },
                    onDeleteGoat = { goatId ->
                        marketplaceViewModel?.deleteGoat(goatId)
                    },
                    onEditGoat = { goat ->
                        editingGoat = goat
                    }
                )

                3 -> FarmBookingsTab(
                    bookings = uiState?.bookings.orEmpty(),
                    onUpdateStatus = { bookingId, status ->
                        marketplaceViewModel?.updateBookingStatus(bookingId, status)
                    }
                )

                4 -> SuperAdminUsersAndReportsTab(
                    users = uiState?.allUsers.orEmpty(),
                    reports = uiState?.reports.orEmpty(),
                    onToggleSuspend = { userId, suspend ->
                        marketplaceViewModel?.toggleUserSuspension(userId, suspend)
                    },
                    onResolveReport = { reportId ->
                        marketplaceViewModel?.resolveReport(reportId, "RESOLVED")
                    }
                )
            }
        }
    }

    val editingTargetFarm = remember(editingGoat, uiState?.farms, ammalFarm) {
        if (editingGoat != null) {
            uiState?.farms?.find { it.id.equals(editingGoat!!.farmId, ignoreCase = true) }
                ?: Farm(
                    id = editingGoat!!.farmId.ifBlank { ammalFarm.id },
                    name = editingGoat!!.farmName.ifBlank { "Partner Farm" },
                    location = editingGoat!!.farmLocation.ifBlank { "Tamil Nadu" },
                    contactNumber = editingGoat!!.farmContact
                )
        } else ammalFarm
    }

    if ((showAddAmmalGoatDialog || editingGoat != null) && marketplaceViewModel != null) {
        AddEditGoatDialog(
            initialGoat = editingGoat,
            farm = editingTargetFarm,
            isSuperAdmin = true,
            availableBreeds = uiState?.availableBreeds.orEmpty(),
            isSaving = uiState?.isLoading == true,
            onDismiss = {
                showAddAmmalGoatDialog = false
                editingGoat = null
            },
            onSave = { goat, imageBytes ->
                marketplaceViewModel.saveGoat(
                    goat = goat,
                    imageBytesList = imageBytes,
                    isSuperAdmin = true,
                    actorUserId = currentUser?.id,
                    actorFarmId = goat.farmId.ifBlank { editingTargetFarm.id },
                    onSuccess = {
                        showAddAmmalGoatDialog = false
                        editingGoat = null
                    }
                )
            }
        )
    }
}

@Composable
private fun SuperAdminStatsTab(
    stats: PlatformStats,
    onUpdatePricing: (Double, Double, Int) -> Unit,
    onLogout: () -> Unit
) {
    var approvalFeeStr by remember { mutableStateOf(PlatformPricingManager.farmApprovalFeeInr.toInt().toString()) }
    var packFeeStr by remember { mutableStateOf(PlatformPricingManager.goatListingPackFeeInr.toInt().toString()) }
    var packSizeStr by remember { mutableStateOf(PlatformPricingManager.goatListingPackSize.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Platform Statistics", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricStatCard(
                    title = "Partner Farms",
                    value = stats.totalFarms.toString(),
                    subtitle = "${stats.verifiedFarms} Verified • ${stats.pendingFarms} Pending",
                    icon = Icons.Default.Agriculture,
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    title = "Total Goats",
                    value = stats.totalGoats.toString(),
                    subtitle = "${stats.approvedGoats} Approved • ${stats.pendingGoats} Pending",
                    icon = Icons.Default.Pets,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricStatCard(
                    title = "Global Bookings",
                    value = stats.totalBookings.toString(),
                    subtitle = "${stats.pendingBookings} Pending • ${stats.completedBookings} Closed",
                    icon = Icons.Default.EventNote,
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    title = "Users & Reports",
                    value = stats.totalUsers.toString(),
                    subtitle = "${stats.openReports} Open Reports",
                    icon = Icons.Default.People,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Platform Pricing Settings Card
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Platform Pricing & Paid Slot-Pack Rules", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Configure standard registration fees and self-service slot pack pricing. To grant custom additional listing slots to an individual partner farm without charging a slot-pack fee, use the Farms tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = approvalFeeStr,
                            onValueChange = { approvalFeeStr = it },
                            label = { Text("Farm Approval (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = packFeeStr,
                            onValueChange = { packFeeStr = it },
                            label = { Text("Slot Pack Fee (₹)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = packSizeStr,
                            onValueChange = { packSizeStr = it },
                            label = { Text("Pack Slots") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            onUpdatePricing(
                                approvalFeeStr.toDoubleOrNull() ?: 500.0,
                                packFeeStr.toDoubleOrNull() ?: 200.0,
                                packSizeStr.toIntOrNull() ?: 5
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Save Platform Pricing")
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign Out of Super Admin")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuperAdminFarmsTab(
    farms: List<Farm>,
    allGoats: List<Goat>,
    onVerifyFarm: (String, VerificationStatus) -> Unit,
    onAddSlots: (String, Int) -> Unit
) {
    var selectedFarmForQuota by remember { mutableStateOf<Farm?>(null) }

    if (farms.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No farms registered in database.")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(farms, key = { it.id }) { farm ->
                val usedCount = remember(allGoats, farm.id) {
                    allGoats.count {
                        it.farmId == farm.id &&
                            it.isActive &&
                            it.availabilityStatus != AvailabilityStatus.SOLD &&
                            it.availabilityStatus != AvailabilityStatus.UNAVAILABLE &&
                            it.approvalStatus != ApprovalStatus.REJECTED
                    }
                }

                ElevatedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    if (farm.logoUrl.isNotBlank()) {
                                        AsyncImage(
                                            model = farm.logoUrl,
                                            contentDescription = farm.name,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.Agriculture,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                                Column {
                                    Text(farm.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text("${farm.ownerName.ifBlank { "Partner" }} • ${farm.location}", style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        text = "Phone: ${farm.contactNumber.ifBlank { "N/A" }} • Active Usage: $usedCount / ${farm.goatListingLimit} Slots",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Surface(
                                color = if (farm.verificationStatus.isApprovedOrVerified) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = farm.verificationStatus.name,
                                    color = if (farm.verificationStatus.isApprovedOrVerified) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (!farm.isAmmalOwnFarm) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (!farm.verificationStatus.isApprovedOrVerified) {
                                    Button(
                                        onClick = { onVerifyFarm(farm.id, VerificationStatus.VERIFIED) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Verify & Approve")
                                    }
                                }
                                OutlinedButton(
                                    onClick = { selectedFarmForQuota = farm },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("btn_manage_slots_${farm.id}")
                                ) {
                                    Icon(Icons.Default.AddChart, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Increase Slots")
                                }
                                if (farm.verificationStatus != VerificationStatus.SUSPENDED) {
                                    OutlinedButton(
                                        onClick = { onVerifyFarm(farm.id, VerificationStatus.SUSPENDED) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("Suspend")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedFarmForQuota?.let { targetFarm ->
        // Keep latest farm snapshot from list if updated
        val currentFarm = farms.find { it.id == targetFarm.id } ?: targetFarm
        val currentUsed = allGoats.count {
            it.farmId == currentFarm.id &&
                it.isActive &&
                it.availabilityStatus != AvailabilityStatus.SOLD &&
                it.availabilityStatus != AvailabilityStatus.UNAVAILABLE &&
                it.approvalStatus != ApprovalStatus.REJECTED
        }
        CustomListingSlotIncreaseDialog(
            farm = currentFarm,
            currentUsedSlots = currentUsed,
            onDismiss = { selectedFarmForQuota = null },
            onConfirmAdditionalSlots = { additionalSlots ->
                onAddSlots(currentFarm.id, additionalSlots)
                selectedFarmForQuota = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomListingSlotIncreaseDialog(
    farm: Farm,
    currentUsedSlots: Int,
    onDismiss: () -> Unit,
    onConfirmAdditionalSlots: (Int) -> Unit
) {
    var additionalSlotsInput by remember { mutableStateOf("5") }
    var validationError by remember { mutableStateOf<String?>(null) }

    val currentQuota = farm.goatListingLimit.coerceAtLeast(0)
    val parsedAdditional = additionalSlotsInput.trim().toIntOrNull()
    val newQuota = if (parsedAdditional != null && parsedAdditional > 0) currentQuota + parsedAdditional else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Increase Listing Quota",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Grant custom additional listing slots to ${farm.name}. This directly increases the farm's listing quota in Supabase without resetting existing usage or charging a slot-pack fee.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Current vs New Quota Summary Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Active Used Listings:", style = MaterialTheme.typography.bodySmall)
                            Text("$currentUsedSlots used", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Current Listing Quota:", style = MaterialTheme.typography.bodyMedium)
                            Text("$currentQuota slots", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "New Total Quota:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (newQuota != null) "$newQuota slots (+$parsedAdditional)" else "Enter valid slots",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (newQuota != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Quick Preset Chips
                Text(
                    text = "Quick Select Additional Slots",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(1, 3, 5, 10, 20, 50).forEach { preset ->
                        FilterChip(
                            selected = parsedAdditional == preset,
                            onClick = {
                                additionalSlotsInput = preset.toString()
                                validationError = null
                            },
                            label = { Text("+$preset Slots") }
                        )
                    }
                }

                OutlinedTextField(
                    value = additionalSlotsInput,
                    onValueChange = {
                        additionalSlotsInput = it.filter { ch -> ch.isDigit() }
                        validationError = null
                    },
                    label = { Text("Additional Slots to Grant *") },
                    placeholder = { Text("e.g. 5") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = validationError != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_custom_additional_slots")
                )

                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val slots = additionalSlotsInput.trim().toIntOrNull()
                    when {
                        slots == null || slots <= 0 -> {
                            validationError = "Please enter a positive number of additional slots (at least 1)."
                        }
                        slots > 1000 -> {
                            validationError = "Cannot add more than 1,000 slots in a single update."
                        }
                        currentQuota + slots > 10000 -> {
                            validationError = "Total farm quota cannot exceed 10,000 listing slots."
                        }
                        else -> {
                            onConfirmAdditionalSlots(slots)
                        }
                    }
                },
                modifier = Modifier.testTag("btn_confirm_custom_slots")
            ) {
                Text("Save New Quota")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SuperAdminGoatsModerationTab(
    goats: List<Goat>,
    onModerateGoat: (String, ApprovalStatus, String?) -> Unit,
    onDeleteGoat: (String) -> Unit,
    onEditGoat: (Goat) -> Unit
) {
    var filterStatus by remember { mutableStateOf("ALL") }
    val displayed = remember(goats, filterStatus) {
        when (filterStatus) {
            "PENDING" -> goats.filter { it.approvalStatus == ApprovalStatus.PENDING }
            "APPROVED" -> goats.filter { it.approvalStatus == ApprovalStatus.APPROVED }
            "REJECTED" -> goats.filter { it.approvalStatus == ApprovalStatus.REJECTED }
            else -> goats
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL", "PENDING", "APPROVED", "REJECTED").forEach { tab ->
                FilterChip(
                    selected = filterStatus == tab,
                    onClick = { filterStatus = tab },
                    label = { Text(tab) }
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(displayed, key = { it.id }) { goat ->
                ElevatedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (goat.imageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = goat.imageUrl,
                                        contentDescription = goat.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(Icons.Default.Pets, contentDescription = null)
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(goat.name.ifBlank { "${goat.breed} Goat" }, fontWeight = FontWeight.Bold)
                                Text("${goat.breed} • ${goat.farmName.ifBlank { "Farm" }}", style = MaterialTheme.typography.bodySmall)
                                Text(PriceUtils.formatINR(goat.effectivePrice), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }

                            Text(
                                text = goat.approvalStatus.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = when (goat.approvalStatus) {
                                    ApprovalStatus.APPROVED -> MaterialTheme.colorScheme.primary
                                    ApprovalStatus.REJECTED, ApprovalStatus.SUSPENDED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onEditGoat(goat) },
                                modifier = Modifier.testTag("btn_edit_goat_${goat.id}")
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit")
                            }
                            if (goat.approvalStatus != ApprovalStatus.APPROVED) {
                                Button(
                                    onClick = { onModerateGoat(goat.id, ApprovalStatus.APPROVED, null) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Approve")
                                }
                            }
                            if (goat.approvalStatus != ApprovalStatus.REJECTED) {
                                OutlinedButton(
                                    onClick = { onModerateGoat(goat.id, ApprovalStatus.REJECTED, "Does not meet listing quality standards") },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject")
                                }
                            }
                            IconButton(onClick = { onDeleteGoat(goat.id) }) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperAdminUsersAndReportsTab(
    users: List<UserProfile>,
    reports: List<ListingReport>,
    onToggleSuspend: (String, Boolean) -> Unit,
    onResolveReport: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (reports.isNotEmpty()) {
            item {
                Text("Listing Reports (${reports.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(reports, key = { it.id }) { report ->
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Goat ID: ${report.goatId.take(8)}", fontWeight = FontWeight.Bold)
                            Text("Reason: ${report.reason}", style = MaterialTheme.typography.bodySmall)
                            Text("Status: ${report.status}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (report.status.equals("OPEN", ignoreCase = true)) {
                            OutlinedButton(onClick = { onResolveReport(report.id) }) {
                                Text("Resolve")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Registered Users (${users.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }

        items(users, key = { it.id }) { user ->
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(user.name.ifBlank { "User" }, fontWeight = FontWeight.Bold)
                        Text(user.email, style = MaterialTheme.typography.bodySmall)
                        Text(
                            text = "Role: ${user.role.name}${if (user.isSuspended) " • SUSPENDED" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (user.isSuspended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    }
                    if (user.role != UserRole.SUPER_ADMIN) {
                        OutlinedButton(
                            onClick = { onToggleSuspend(user.id, !user.isSuspended) },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (user.isSuspended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(if (user.isSuspended) "Activate" else "Suspend")
                        }
                    }
                }
            }
        }
    }
}
