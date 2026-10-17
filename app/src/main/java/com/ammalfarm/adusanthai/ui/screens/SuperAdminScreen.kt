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
                    IconButton(onClick = onNavigateToNotifications) {
                        Icon(Icons.Default.Notifications, contentDescription = "Notifications")
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

    if (showAddAmmalGoatDialog && marketplaceViewModel != null) {
        AddEditGoatDialog(
            initialGoat = null,
            farm = ammalFarm,
            isSuperAdmin = true,
            onDismiss = { showAddAmmalGoatDialog = false },
            onSave = { goat, imageBytes ->
                marketplaceViewModel.saveGoat(
                    goat = goat,
                    imageBytesList = imageBytes,
                    isSuperAdmin = true,
                    onSuccess = { showAddAmmalGoatDialog = false }
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
                    Text("Platform Pricing & Quota Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

@Composable
private fun SuperAdminFarmsTab(
    farms: List<Farm>,
    onVerifyFarm: (String, VerificationStatus) -> Unit,
    onAddSlots: (String, Int) -> Unit
) {
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
                ElevatedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(farm.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${farm.ownerName} • ${farm.location}", style = MaterialTheme.typography.bodySmall)
                                Text("Phone: ${farm.contactNumber} • Quota: ${farm.goatListingLimit} Goats", style = MaterialTheme.typography.labelSmall)
                            }
                            Surface(
                                color = if (farm.verificationStatus.isApprovedOrVerified) Color(0xFF1B5E20).copy(alpha = 0.15f)
                                else Color(0xFFE65100).copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = farm.verificationStatus.name,
                                    color = if (farm.verificationStatus.isApprovedOrVerified) Color(0xFF1B5E20) else Color(0xFFE65100),
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
                                    onClick = { onAddSlots(farm.id, PlatformPricingManager.goatListingPackSize) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("+${PlatformPricingManager.goatListingPackSize} Slots")
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
}

@Composable
private fun SuperAdminGoatsModerationTab(
    goats: List<Goat>,
    onModerateGoat: (String, ApprovalStatus, String?) -> Unit,
    onDeleteGoat: (String) -> Unit
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
                                    ApprovalStatus.APPROVED -> Color(0xFF1B5E20)
                                    ApprovalStatus.PENDING -> Color(0xFFE65100)
                                    ApprovalStatus.REJECTED -> Color(0xFFB71C1C)
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
