package com.example.ui.screens

import com.example.core.util.PriceUtils

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.*
import com.example.ui.viewmodel.MarketplaceUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminScreen(
    uiState: MarketplaceUiState,
    onApproveGoat: (String) -> Unit,
    onRejectGoat: (String) -> Unit,
    onSuspendGoat: (String) -> Unit = {},
    onRestoreGoat: (String) -> Unit = {},
    onDeleteGoat: (String) -> Unit = {},
    onEditGoat: (Goat) -> Unit = {},
    onApproveFarm: (String) -> Unit,
    onRejectFarm: (String) -> Unit,
    onSuspendFarm: (String) -> Unit,
    onReactivateFarm: (String) -> Unit = onApproveFarm,
    onUpdateFarmListingLimit: (String, Int) -> Unit = { _, _ -> },
    onUpdateUserSuspension: (String, Boolean) -> Unit = { _, _ -> },
    onUpdateReportStatus: (String, ReportStatus, String?) -> Unit = { _, _, _ -> },
    onResolveReportWithAction: (String, String?, String?, String) -> Unit = { _, _, _, _ -> },
    onUpdateBookingStatus: (String, AvailabilityStatus) -> Unit = { _, _ -> },
    onNavigateBack: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToAmmalFarm: () -> Unit = {},
    onSyncRole: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentUser = uiState.currentUser
    val isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN

    if (!isSuperAdmin) {
        SuperAdminAccessDeniedView(
            currentRole = currentUser?.role?.name ?: "Guest",
            onNavigateToLogin = onNavigateToLogin,
            onSyncRole = onSyncRole
        )
        return
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val stats = uiState.platformStats

    val allPlatformGoats = remember(uiState.allAdminGoats, uiState.goats) {
        (uiState.allAdminGoats + uiState.goats).distinctBy { it.id }
    }

    val resolvedAllBookings = remember(uiState.allBookings, allPlatformGoats, uiState.farms) {
        val baseList = uiState.allBookings
        val existingBookingGoatIds = baseList.mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
        val missingReservedGoats = allPlatformGoats.filter { goat ->
            (goat.availabilityStatus == AvailabilityStatus.RESERVED || goat.availabilityStatus == AvailabilityStatus.BOOKING_PENDING) &&
            !existingBookingGoatIds.contains(goat.id)
        }
        val syntheticBookings = missingReservedGoats.map { goat ->
            val farm = uiState.farms.find { it.id == goat.farmId }
            val effectiveFarmName = goat.farmName.ifBlank { farm?.name ?: "Ammal Farm" }
            Booking(
                id = "res-" + goat.id,
                goatId = goat.id,
                goatCode = goat.goatCode,
                farmId = goat.farmId,
                customerId = "",
                customerName = "Customer Reservation / Hold",
                customerPhone = "Contact Farm Admin",
                goatName = goat.name,
                goatBreed = goat.breed,
                goatPhoto = goat.photos.firstOrNull() ?: "",
                farmName = effectiveFarmName,
                amount = goat.finalPrice,
                status = goat.availabilityStatus,
                bookingDate = goat.createdAt,
                reservationExpiryDate = goat.createdAt + (24 * 3600 * 1000L),
                notes = "Active reservation hold on ${goat.name}"
            )
        }
        (baseList + syntheticBookings).distinctBy { it.id }
    }

    val pendingGoatsCount = allPlatformGoats.count { it.approvalStatus == ApprovalStatus.PENDING_APPROVAL }
    val pendingFarmsCount = uiState.farms.count { it.verificationStatus == VerificationStatus.PENDING }
    val pendingReportsCount = uiState.allReports.count { it.status == ReportStatus.PENDING || it.status == ReportStatus.UNDER_INVESTIGATION }

    var farmDetailToView by remember { mutableStateOf<Farm?>(null) }
    var farmToSetLimit by remember { mutableStateOf<Farm?>(null) }
    var goatToEdit by remember { mutableStateOf<Goat?>(null) }
    var reportToInvestigate by remember { mutableStateOf<PlatformReport?>(null) }
    var itemToDeleteConfirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.AdminPanelSettings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Super Admin Console", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF0C2340)
                                ) {
                                    Text(
                                        "ROOT ACCESS",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                "Adu Santhai Platform Control",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = onNavigateToAmmalFarm,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF13663C)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Ammal Farm", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("OWN FARM (₹0 FREE)", fontSize = 8.sp, color = Color(0xFFE8F5E9))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (pendingGoatsCount > 0 || pendingFarmsCount > 0 || pendingReportsCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Action Required: $pendingGoatsCount Goats, $pendingFarmsCount Farms, $pendingReportsCount Reports pending",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(
                            onClick = {
                                selectedTab = when {
                                    pendingGoatsCount > 0 -> 2
                                    pendingFarmsCount > 0 -> 1
                                    else -> 5
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text("Review", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 12.dp,
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Overview") },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Farms (${uiState.farms.size})")
                            if (pendingFarmsCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("$pendingFarmsCount", fontSize = 10.sp)
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Goats (${allPlatformGoats.size})")
                            if (pendingGoatsCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("$pendingGoatsCount", fontSize = 10.sp)
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Bookings (${resolvedAllBookings.size})") },
                    icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("Customers (${uiState.allCustomers.size})") },
                    icon = { Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Reports (${uiState.allReports.size})")
                            if (pendingReportsCount > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.error) {
                                    Text("$pendingReportsCount", fontSize = 10.sp)
                                }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Report, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                Tab(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    text = { Text("Listing Fees (${uiState.listingPayments.size})") },
                    icon = { Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> SuperAdminOverviewTab(
                        uiState = uiState,
                        allBookings = resolvedAllBookings,
                        allGoats = allPlatformGoats,
                        onNavigateToTab = { selectedTab = it },
                        onNavigateToAmmalFarm = onNavigateToAmmalFarm
                    )
                    1 -> SuperAdminFarmsTab(
                        farms = uiState.farms,
                        onApproveFarm = onApproveFarm,
                        onRejectFarm = onRejectFarm,
                        onSuspendFarm = onSuspendFarm,
                        onReactivateFarm = onReactivateFarm,
                        onViewDetails = { farmDetailToView = it },
                        onSetListingLimit = { farmToSetLimit = it },
                        onNavigateToAmmalFarm = onNavigateToAmmalFarm
                    )
                    2 -> SuperAdminGoatsTab(
                        goats = allPlatformGoats,
                        onApproveGoat = onApproveGoat,
                        onRejectGoat = onRejectGoat,
                        onSuspendGoat = onSuspendGoat,
                        onRestoreGoat = onRestoreGoat,
                        onDeleteGoat = { gid ->
                            itemToDeleteConfirm = "Delete this goat listing permanently?" to { onDeleteGoat(gid) }
                        },
                        onEditGoat = { goatToEdit = it }
                    )
                    3 -> SuperAdminBookingsTab(
                        bookings = resolvedAllBookings,
                        onUpdateBookingStatus = onUpdateBookingStatus
                    )
                    4 -> SuperAdminCustomersTab(
                        customers = uiState.allCustomers,
                        bookings = resolvedAllBookings,
                        onUpdateUserSuspension = onUpdateUserSuspension
                    )
                    5 -> SuperAdminReportsTab(
                        reports = uiState.allReports,
                        onInvestigateReport = { reportToInvestigate = it },
                        onUpdateReportStatus = onUpdateReportStatus
                    )
                    6 -> SuperAdminListingFeesTab(
                        payments = uiState.listingPayments,
                        goats = allPlatformGoats,
                        stats = uiState.platformStats
                    )
                }
            }
        }
    }

    farmDetailToView?.let { farm ->
        SuperAdminFarmDetailDialog(
            farm = farm,
            goats = uiState.allAdminGoats.filter { it.farmId == farm.id },
            onDismiss = { farmDetailToView = null },
            onApprove = { onApproveFarm(farm.id); farmDetailToView = null },
            onSuspend = { onSuspendFarm(farm.id); farmDetailToView = null },
            onReactivate = { onReactivateFarm(farm.id); farmDetailToView = null },
            onSetListingLimit = { farmDetailToView = null; farmToSetLimit = farm }
        )
    }

    farmToSetLimit?.let { farm ->
        SuperAdminSetFarmLimitDialog(
            farm = farm,
            onDismiss = { farmToSetLimit = null },
            onSaveLimit = { newLimit ->
                onUpdateFarmListingLimit(farm.id, newLimit)
            }
        )
    }

    goatToEdit?.let { goat ->
        SuperAdminEditGoatDialog(
            goat = goat,
            onDismiss = { goatToEdit = null },
            onSave = { updated ->
                onEditGoat(updated)
                goatToEdit = null
            }
        )
    }

    reportToInvestigate?.let { report ->
        SuperAdminInvestigateReportDialog(
            report = report,
            uiState = uiState,
            onDismiss = { reportToInvestigate = null },
            onUpdateReportStatus = onUpdateReportStatus,
            onResolve = { actionRemoveListing, actionSuspendFarm, notes ->
                onResolveReportWithAction(report.id, actionRemoveListing, actionSuspendFarm, notes)
                reportToInvestigate = null
            },
            onDismissReport = { dismissNotes ->
                onUpdateReportStatus(report.id, ReportStatus.DISMISSED, dismissNotes.ifBlank { "Dismissed by Super Admin after review." })
                reportToInvestigate = null
            }
        )
    }

    itemToDeleteConfirm?.let { (prompt, action) ->
        AlertDialog(
            onDismissRequest = { itemToDeleteConfirm = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            title = { Text("Confirm Administrative Deletion", fontWeight = FontWeight.Bold) },
            text = { Text(prompt, fontSize = 14.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        action()
                        itemToDeleteConfirm = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// TAB 0: EXECUTIVE OVERVIEW
// ==========================================

@Composable
private fun SuperAdminOverviewTab(
    uiState: MarketplaceUiState,
    allBookings: List<Booking> = uiState.allBookings,
    allGoats: List<Goat> = uiState.allAdminGoats,
    onNavigateToTab: (Int) -> Unit,
    onNavigateToAmmalFarm: () -> Unit = {}
) {
    val stats = uiState.platformStats
    val activeBookingsCount = allBookings.count {
        it.status == AvailabilityStatus.RESERVED || it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.CONFIRMED
    }
    val ammalFarm = uiState.farms.find { it.isAmmalOwnFarm }
    val ammalGoats = allGoats.filter {
        (ammalFarm != null && it.farmId == ammalFarm.id) ||
        (ammalFarm != null && it.farmName.equals(ammalFarm.name, ignoreCase = true)) ||
        it.listingFeeAmount == 0.0
    }
    val ammalBookings = allBookings.filter {
        (ammalFarm != null && it.farmId == ammalFarm.id) ||
        ammalGoats.any { g -> g.id == it.goatId }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Ammal Farm Owner Command Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F3822)),
                border = BorderStroke(1.5.dp, Color(0xFF4CAF50)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF13663C),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Agriculture, contentDescription = null, tint = Color(0xFF81C784))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("Ammal Farm (My Farm)", fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFF1B5E20)
                                    ) {
                                        Text(
                                            "PLATFORM OWNER • ZERO COST",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Black,
                                            color = Color(0xFFC8E6C9),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Your farm 'Ammal Farm' is granted direct root access. No listing fees (₹0 fee waiver applied) and listings are published immediately without payment barriers.",
                        fontSize = 12.sp,
                        color = Color(0xFFE8F5E9),
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricMiniItem("Ammal Goats", "${ammalGoats.size}", Color(0xFF81C784))
                        MetricMiniItem("Listing Cost", "₹0 (Free)", Color(0xFFFFD54F))
                        MetricMiniItem("Ammal Bookings", "${ammalBookings.size}", Color(0xFF80DEEA))
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onNavigateToAmmalFarm,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Farm Admin Portal", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C2340)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Platform Governance", fontSize = 12.sp, color = Color(0xFF90CAF9), fontWeight = FontWeight.Bold)
                            Text("Ammal Farm Marketplace", fontSize = 20.sp, color = Color.White, fontWeight = FontWeight.Black)
                        }
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF1E88E5).copy(alpha = 0.2f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF90CAF9))
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricMiniItem("Gross Volume", "₹${stats.totalRevenue.toInt()}", Color(0xFF81C784))
                        MetricMiniItem("Active Goats", "${stats.totalGoats}", Color(0xFFFFD54F))
                        MetricMiniItem("Farms", "${uiState.farms.size}", Color(0xFF4FC3F7))
                    }
                }
            }
        }

        item {
            Text("Core Platform Metrics", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiStatCard(
                        title = "Total Customers",
                        value = "${stats.totalCustomers}",
                        subtitle = "Active platform buyers",
                        icon = Icons.Default.People,
                        color = Color(0xFF1976D2),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(4) }
                    )
                    KpiStatCard(
                        title = "Total Farms",
                        value = "${uiState.farms.size}",
                        subtitle = "${stats.pendingFarms} Pending KYC",
                        icon = Icons.Default.Agriculture,
                        color = if (stats.pendingFarms > 0) Color(0xFFE65100) else Color(0xFF2E7D32),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(1) }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiStatCard(
                        title = "Total Goats",
                        value = "${maxOf(stats.totalGoats, allGoats.size)}",
                        subtitle = "${stats.pendingListings} Pending approval",
                        icon = Icons.Default.Pets,
                        color = if (stats.pendingListings > 0) Color(0xFFE65100) else Color(0xFF5E35B1),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(2) }
                    )
                    KpiStatCard(
                        title = "Bookings",
                        value = "${maxOf(stats.totalBookings, allBookings.size)}",
                        subtitle = "${maxOf(stats.activeBookings, activeBookingsCount)} Active Holds",
                        icon = Icons.Default.ReceiptLong,
                        color = Color(0xFF00897B),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(3) }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KpiStatCard(
                        title = "Reports / Flags",
                        value = "${stats.pendingReports}",
                        subtitle = "Require investigation",
                        icon = Icons.Default.Report,
                        color = if (stats.pendingReports > 0) Color(0xFFC62828) else Color(0xFF455A64),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(5) }
                    )
                    KpiStatCard(
                        title = "Listing Fees (₹100)",
                        value = "₹${stats.totalListingFeesCollected.toInt()}",
                        subtitle = "${uiState.listingPayments.count { it.status == com.example.model.PaymentStatus.PAID }} Partner Fees Paid",
                        icon = Icons.Default.Payments,
                        color = Color(0xFF00796B),
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigateToTab(6) }
                    )
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Administrative Quick Shortcuts", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onNavigateToTab(1) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Text("Manage Farms", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onNavigateToTab(2) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Text("Approve Goats", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onNavigateToTab(5) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Text("View Reports", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 1: FARM MANAGEMENT
// ==========================================

@Composable
private fun SuperAdminFarmsTab(
    farms: List<Farm>,
    onApproveFarm: (String) -> Unit,
    onRejectFarm: (String) -> Unit,
    onSuspendFarm: (String) -> Unit,
    onReactivateFarm: (String) -> Unit,
    onViewDetails: (Farm) -> Unit,
    onSetListingLimit: (Farm) -> Unit = {},
    onNavigateToAmmalFarm: () -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<VerificationStatus?>(null) }

    val filteredFarms = remember(farms, searchQuery, selectedFilter) {
        farms.filter { farm ->
            val matchesQuery = searchQuery.isBlank() ||
                    farm.name.contains(searchQuery, ignoreCase = true) ||
                    farm.ownerName.contains(searchQuery, ignoreCase = true) ||
                    farm.location.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || farm.verificationStatus == selectedFilter
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search farms by name, owner, or district...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("All (${farms.size})", fontSize = 12.sp) }
                        )
                    }
                    VerificationStatus.values().forEach { status ->
                        val count = farms.count { it.verificationStatus == status }
                        item {
                            FilterChip(
                                selected = selectedFilter == status,
                                onClick = { selectedFilter = status },
                                label = { Text("${status.name} ($count)", fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        Divider()

        if (filteredFarms.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No farms match the selected criteria.", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredFarms, key = { it.id }) { farm ->
                    FarmAdminCard(
                        farm = farm,
                        onApprove = { onApproveFarm(farm.id) },
                        onReject = { onRejectFarm(farm.id) },
                        onSuspend = { onSuspendFarm(farm.id) },
                        onReactivate = { onReactivateFarm(farm.id) },
                        onViewDetails = { onViewDetails(farm) },
                        onSetListingLimit = { onSetListingLimit(farm) },
                        onNavigateToAmmalFarm = onNavigateToAmmalFarm
                    )
                }
            }
        }
    }
}

@Composable
private fun FarmAdminCard(
    farm: Farm,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onViewDetails: () -> Unit,
    onSetListingLimit: () -> Unit = {},
    onNavigateToAmmalFarm: () -> Unit = {}
) {
    val isAmmal = farm.isAmmalOwnFarm

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmmal) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            if (isAmmal) 1.5.dp else 1.dp,
            if (isAmmal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (!farm.logoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = farm.logoUrl,
                            contentDescription = farm.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Agriculture,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = farm.name,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (isAmmal) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.Verified,
                                    contentDescription = "Owner Farm",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Text("Owner: ${farm.ownerName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${farm.location}, ${farm.state}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isAmmal) Color(0xFF1B5E20) else when (farm.verificationStatus) {
                        VerificationStatus.APPROVED -> Color(0xFF13663C)
                        VerificationStatus.PENDING -> Color(0xFFD48B06)
                        VerificationStatus.SUSPENDED -> Color(0xFFC0392B)
                        VerificationStatus.REJECTED -> Color(0xFF7F8C8D)
                    }
                ) {
                    Text(
                        text = if (isAmmal) "PLATFORM OWNER (FREE)" else farm.verificationStatus.name,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = farm.description.ifBlank { if (isAmmal) "Platform official breeder headquarters and sanctuary." else "Livestock breeding partner." },
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isAmmal) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.Speed,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = "Listing Quota: ${farm.totalGoatsListed} / ${farm.goatListingLimit} goats",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Auto-approved up to limit • Add locked at limit",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        FilledTonalButton(
                            onClick = onSetListingLimit,
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Set Limit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Contact: ${farm.contactNumber}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isAmmal) {
                        Button(
                            onClick = onNavigateToAmmalFarm,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Manage Farm", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    OutlinedButton(
                        onClick = onViewDetails,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Details", fontSize = 11.sp)
                    }

                    if (!isAmmal) {
                        when (farm.verificationStatus) {
                            VerificationStatus.PENDING -> {
                                OutlinedButton(
                                    onClick = onReject,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                                ) {
                                    Text("Reject", fontSize = 11.sp)
                                }
                                Button(
                                    onClick = onApprove,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Approve KYC", fontSize = 11.sp)
                                }
                            }
                            VerificationStatus.APPROVED -> {
                                OutlinedButton(
                                    onClick = onSuspend,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B))
                                ) {
                                    Text("Suspend Farm", fontSize = 11.sp)
                                }
                            }
                            VerificationStatus.SUSPENDED -> {
                                Button(
                                    onClick = onReactivate,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Reactivate Farm", fontSize = 11.sp)
                                }
                            }
                            VerificationStatus.REJECTED -> {
                                Button(
                                    onClick = onApprove,
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Text("Re-Approve", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 2: GOAT MANAGEMENT
// ==========================================

@Composable
private fun SuperAdminGoatsTab(
    goats: List<Goat>,
    onApproveGoat: (String) -> Unit,
    onRejectGoat: (String) -> Unit,
    onSuspendGoat: (String) -> Unit,
    onRestoreGoat: (String) -> Unit,
    onDeleteGoat: (String) -> Unit,
    onEditGoat: (Goat) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<ApprovalStatus?>(null) }

    val filteredGoats = remember(goats, searchQuery, selectedFilter) {
        goats.filter { goat ->
            val matchesQuery = searchQuery.isBlank() ||
                    goat.name.contains(searchQuery, ignoreCase = true) ||
                    goat.breed.contains(searchQuery, ignoreCase = true) ||
                    goat.goatCode.contains(searchQuery, ignoreCase = true) ||
                    goat.farmName.contains(searchQuery, ignoreCase = true)
            val matchesFilter = selectedFilter == null || goat.approvalStatus == selectedFilter
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by goat name, breed, code, or farm...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = selectedFilter == null,
                            onClick = { selectedFilter = null },
                            label = { Text("All (${goats.size})", fontSize = 12.sp) }
                        )
                    }
                    ApprovalStatus.values().forEach { status ->
                        val count = goats.count { it.approvalStatus == status }
                        item {
                            FilterChip(
                                selected = selectedFilter == status,
                                onClick = { selectedFilter = status },
                                label = { Text("${status.name.replace("_", " ")} ($count)", fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        Divider()

        if (filteredGoats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No goats match the filter criteria.", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredGoats, key = { it.id }) { goat ->
                    GoatAdminCard(
                        goat = goat,
                        onApprove = { onApproveGoat(goat.id) },
                        onReject = { onRejectGoat(goat.id) },
                        onSuspend = { onSuspendGoat(goat.id) },
                        onRestore = { onRestoreGoat(goat.id) },
                        onDelete = { onDeleteGoat(goat.id) },
                        onEdit = { onEditGoat(goat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoatAdminCard(
    goat: Goat,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onSuspend: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(modifier = Modifier.weight(1f)) {
                    val photoUrl = goat.photos.firstOrNull()
                    if (!photoUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = goat.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.LightGray)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Pets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(goat.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Code: ${goat.goatCode.ifBlank { "GOAT-" + goat.id.take(6).uppercase() }} • ${goat.breed}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Farm: ${goat.farmName} (${goat.farmCode.ifBlank { "FARM-001" }})", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        Text("${goat.gender.name} • ${goat.ageMonths} mos • ${goat.weightKg} kg", fontSize = 11.sp, color = Color.Gray)
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    if (goat.hasDiscount) {
                        Text(goat.formattedFinalPrice, fontWeight = FontWeight.Black, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                goat.formattedPrice,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                goat.formattedDiscountBadge,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    } else {
                        Text(goat.formattedPrice, fontWeight = FontWeight.Black, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (goat.approvalStatus) {
                            ApprovalStatus.APPROVED -> Color(0xFF13663C)
                            ApprovalStatus.PENDING_APPROVAL -> Color(0xFFD48B06)
                            ApprovalStatus.SUSPENDED -> Color(0xFFC0392B)
                            ApprovalStatus.REJECTED -> Color(0xFF7F8C8D)
                            ApprovalStatus.DRAFT -> Color(0xFF607D8B)
                            ApprovalStatus.PAYMENT_PENDING -> Color(0xFFE65100)
                        }
                    ) {
                        Text(
                            text = goat.approvalStatus.name.replace("_", " "),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            val isAmmalGoat = goat.farmId == SEED_AMMAL_FARM_UUID || goat.farmName.contains("Ammal", ignoreCase = true)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Availability: ${goat.availabilityStatus.name}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (isAmmalGoat) Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                ) {
                    Text(
                        text = if (isAmmalGoat) "Ammal Farm (Free Listing)" else "Partner Farm Listing",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAmmalGoat) Color(0xFF2E7D32) else Color(0xFF1565C0),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = Color.Red)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (goat.approvalStatus) {
                        ApprovalStatus.PENDING_APPROVAL -> {
                            OutlinedButton(
                                onClick = onReject,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                            ) {
                                Text("Reject", fontSize = 11.sp)
                            }
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Approve Listing", fontSize = 11.sp)
                            }
                        }
                        ApprovalStatus.APPROVED -> {
                            OutlinedButton(
                                onClick = onSuspend,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B))
                            ) {
                                Text("Suspend", fontSize = 11.sp)
                            }
                        }
                        ApprovalStatus.SUSPENDED -> {
                            Button(
                                onClick = onRestore,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Restore Listing", fontSize = 11.sp)
                            }
                        }
                        ApprovalStatus.REJECTED -> {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Re-Approve", fontSize = 11.sp)
                            }
                        }
                        ApprovalStatus.PAYMENT_PENDING,
                        ApprovalStatus.DRAFT -> {
                            Button(
                                onClick = onApprove,
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Publish", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 3: BOOKINGS MANAGEMENT
// ==========================================

@Composable
private fun SuperAdminBookingsTab(
    bookings: List<Booking>,
    onUpdateBookingStatus: (String, AvailabilityStatus) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatus by remember { mutableStateOf<AvailabilityStatus?>(null) }

    val filteredBookings = remember(bookings, searchQuery, selectedStatus) {
        bookings.filter { b ->
            val matchesQuery = searchQuery.isBlank() ||
                    b.goatName.contains(searchQuery, ignoreCase = true) ||
                    b.customerName.contains(searchQuery, ignoreCase = true) ||
                    b.farmName.contains(searchQuery, ignoreCase = true) ||
                    b.id.contains(searchQuery, ignoreCase = true)
            val matchesStatus = selectedStatus == null || b.status == selectedStatus
            matchesQuery && matchesStatus
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search by buyer, goat, farm, or booking ID...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = selectedStatus == null,
                            onClick = { selectedStatus = null },
                            label = { Text("All (${bookings.size})", fontSize = 12.sp) }
                        )
                    }
                    listOf(
                        AvailabilityStatus.CONFIRMED,
                        AvailabilityStatus.BOOKING_PENDING,
                        AvailabilityStatus.RESERVED,
                        AvailabilityStatus.COMPLETED,
                        AvailabilityStatus.CANCELLED
                    ).forEach { status ->
                        val count = bookings.count { it.status == status }
                        item {
                            FilterChip(
                                selected = selectedStatus == status,
                                onClick = { selectedStatus = status },
                                label = { Text("${status.name.replace("_", " ")} ($count)", fontSize = 12.sp) }
                            )
                        }
                    }
                }
            }
        }

        Divider()

        if (filteredBookings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No bookings match your filter criteria.", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredBookings, key = { it.id }) { booking ->
                    BookingAdminCard(
                        booking = booking,
                        onUpdateStatus = { status -> onUpdateBookingStatus(booking.id, status) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingAdminCard(
    booking: Booking,
    onUpdateStatus: (AvailabilityStatus) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${booking.goatName} (#${booking.id})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Buyer: ${booking.customerName} (${booking.customerPhone})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Breeder: ${booking.farmName}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "₹${booking.amount.toInt()}",
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (booking.status) {
                            AvailabilityStatus.CONFIRMED -> Color(0xFFE8F5E9)
                            AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED -> Color(0xFFFFF3E0)
                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFFE3F2FD)
                            else -> Color(0xFFFFEBEE)
                        }
                    ) {
                        Text(
                            text = booking.status.name.replace("_", " "),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (booking.status) {
                                AvailabilityStatus.CONFIRMED -> Color(0xFF2E7D32)
                                AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED -> Color(0xFFE65100)
                                AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF1565C0)
                                else -> Color(0xFFC62828)
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            if (booking.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Special Instructions: ${booking.notes}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Divider()
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) {
                    OutlinedButton(
                        onClick = { onUpdateStatus(AvailabilityStatus.CANCELLED) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                    ) {
                        Text("Cancel Hold", fontSize = 11.sp)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Button(
                        onClick = { onUpdateStatus(AvailabilityStatus.CONFIRMED) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Confirm Booking", fontSize = 11.sp)
                    }
                } else if (booking.status == AvailabilityStatus.CONFIRMED) {
                    Button(
                        onClick = { onUpdateStatus(AvailabilityStatus.COMPLETED) },
                        modifier = Modifier.height(30.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("Mark Fulfilled & Completed", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ==========================================
// TAB 4: CUSTOMER MANAGEMENT
// ==========================================

@Composable
private fun SuperAdminCustomersTab(
    customers: List<UserProfile>,
    bookings: List<Booking>,
    onUpdateUserSuspension: (String, Boolean) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredCustomers = remember(customers, searchQuery, selectedFilter) {
        customers.filter { c ->
            val matchesQuery = searchQuery.isBlank() ||
                    c.name.contains(searchQuery, ignoreCase = true) ||
                    c.email.contains(searchQuery, ignoreCase = true) ||
                    c.phone.contains(searchQuery, ignoreCase = true)
            val matchesFilter = when (selectedFilter) {
                "ACTIVE" -> !c.isSuspended
                "SUSPENDED" -> c.isSuspended
                else -> true
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search customers by name, email, or phone...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("All (${customers.size})", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedFilter == "ACTIVE",
                        onClick = { selectedFilter = "ACTIVE" },
                        label = { Text("Active (${customers.count { !it.isSuspended }})", fontSize = 12.sp) }
                    )
                    FilterChip(
                        selected = selectedFilter == "SUSPENDED",
                        onClick = { selectedFilter = "SUSPENDED" },
                        label = { Text("Suspended (${customers.count { it.isSuspended }})", fontSize = 12.sp) }
                    )
                }
            }
        }

        Divider()

        if (filteredCustomers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.People, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No customer accounts match your search.", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredCustomers, key = { it.id }) { customer ->
                    val userBookingsCount = bookings.count { it.customerId == customer.id }
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (customer.isSuspended) Color(0xFFFFCDD2) else MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                if (customer.isSuspended) Icons.Default.Block else Icons.Default.Person,
                                                contentDescription = null,
                                                tint = if (customer.isSuspended) Color(0xFFC62828) else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(customer.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Text(customer.email, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("Phone: ${customer.phone}", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (customer.isSuspended) Color(0xFFC0392B) else Color(0xFF13663C)
                                ) {
                                    Text(
                                        text = if (customer.isSuspended) "SUSPENDED" else "ACTIVE",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Reservations: $userBookingsCount total bookings",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (customer.isSuspended) {
                                    Button(
                                        onClick = { onUpdateUserSuspension(customer.id, false) },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("Reactivate Customer", fontSize = 11.sp)
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { onUpdateUserSuspension(customer.id, true) },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B))
                                    ) {
                                        Text("Suspend Account", fontSize = 11.sp)
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

// ==========================================
// TAB 5: REPORTS & INVESTIGATION
// ==========================================

@Composable
private fun SuperAdminReportsTab(
    reports: List<PlatformReport>,
    onInvestigateReport: (PlatformReport) -> Unit,
    onUpdateReportStatus: (String, ReportStatus, String?) -> Unit
) {
    var selectedStatus by remember { mutableStateOf<ReportStatus?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    val filteredReports = remember(reports, selectedStatus) {
        reports.filter { r ->
            selectedStatus == null || r.status == selectedStatus
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedStatus == null,
                        onClick = { selectedStatus = null },
                        label = { Text("All (${reports.size})", fontSize = 12.sp) }
                    )
                }
                ReportStatus.values().forEach { status ->
                    val count = reports.count { it.status == status }
                    item {
                        FilterChip(
                            selected = selectedStatus == status,
                            onClick = { selectedStatus = status },
                            label = { Text("${status.displayName} ($count)", fontSize = 12.sp) }
                        )
                    }
                }
            }
        }

        Divider()

        if (filteredReports.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF13663C))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No reports currently flagged in this status category.", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredReports, key = { it.id }) { report ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(
                            1.dp,
                            when (report.status) {
                                ReportStatus.NEW -> MaterialTheme.colorScheme.error
                                ReportStatus.UNDER_REVIEW -> Color(0xFFE67E22)
                                ReportStatus.RESOLVED -> Color(0xFF2E7D32)
                                ReportStatus.DISMISSED -> MaterialTheme.colorScheme.outlineVariant
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF0C2340)
                                        ) {
                                            Text(
                                                report.targetType.replace("_", " "),
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(report.targetTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer
                                        ) {
                                            Text(
                                                report.reason.displayName,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Reporter: ${report.reporterName} (${report.reporterEmail})", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = when (report.status) {
                                        ReportStatus.NEW -> Color(0xFFC0392B)
                                        ReportStatus.UNDER_REVIEW -> Color(0xFFE67E22)
                                        ReportStatus.RESOLVED -> Color(0xFF13663C)
                                        ReportStatus.DISMISSED -> Color(0xFF7F8C8D)
                                    }
                                ) {
                                    Text(
                                        report.status.displayName,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "\"${report.description}\"",
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp
                                    )
                                    if (report.evidencePhotoUrl != null || report.evidencePhotos.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Attachment,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                "Evidence photo attached",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }

                            report.adminActionTaken?.let { action ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Admin Action: $action", fontSize = 11.sp, color = Color(0xFF0C2340), fontWeight = FontWeight.SemiBold)
                            }

                            report.resolutionNotes?.let { notes ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Resolution Notes: $notes", fontSize = 11.sp, color = Color(0xFF13663C), fontWeight = FontWeight.Medium)
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Divider()
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    dateFormat.format(Date(report.createdAt)),
                                    fontSize = 10.sp,
                                    color = Color.Gray
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (report.status == ReportStatus.NEW) {
                                        OutlinedButton(
                                            onClick = { onUpdateReportStatus(report.id, ReportStatus.UNDER_REVIEW, "Placed under review by Super Admin.") },
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Review", fontSize = 11.sp)
                                        }
                                    }
                                    if (report.status != ReportStatus.DISMISSED && report.status != ReportStatus.RESOLVED) {
                                        OutlinedButton(
                                            onClick = { onUpdateReportStatus(report.id, ReportStatus.DISMISSED, "Dismissed as invalid flag after moderation review.") },
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Dismiss", fontSize = 11.sp)
                                        }
                                    }
                                    Button(
                                        onClick = { onInvestigateReport(report) },
                                        modifier = Modifier.height(30.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("Investigate & Action", fontSize = 11.sp)
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

// ==========================================
// MODAL DIALOGS
// ==========================================

@Composable
private fun SuperAdminFarmDetailDialog(
    farm: Farm,
    goats: List<Goat>,
    onDismiss: () -> Unit,
    onApprove: () -> Unit,
    onSuspend: () -> Unit,
    onReactivate: () -> Unit,
    onSetListingLimit: () -> Unit = {}
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Farm Profile Audit", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                val bannerOrLogo = farm.bannerUrl.ifBlank { farm.logoUrl }
                if (bannerOrLogo.isNotBlank()) {
                    AsyncImage(
                        model = bannerOrLogo,
                        contentDescription = farm.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Agriculture,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(farm.name, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("Breeder / Owner: ${farm.ownerName}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("District / Location: ${farm.location}, ${farm.state}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                Text("Contact Phone: ${farm.contactNumber}", fontSize = 12.sp)
                Text("Email: ${farm.email}", fontSize = 12.sp)

                Spacer(modifier = Modifier.height(8.dp))
                Text("Farm Description:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(farm.description, fontSize = 12.sp)

                val isAmmal = farm.isAmmalOwnFarm
                if (!isAmmal) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Listing Limit & Quota", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        "${farm.totalGoatsListed} / ${farm.goatListingLimit} goats listed",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Auto-approved up to limit. Farm admin cannot add more than limit.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Button(
                                    onClick = onSetListingLimit,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Change Limit", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Listed Livestock (${goats.size}):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                goats.forEach { g ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("• ${g.name} (${g.breed})", fontSize = 12.sp, maxLines = 1)
                        Text("₹${g.price.toInt()} [${g.approvalStatus.name}]", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (farm.verificationStatus) {
                        VerificationStatus.PENDING -> {
                            Button(onClick = onApprove) {
                                Text("Approve KYC")
                            }
                        }
                        VerificationStatus.APPROVED -> {
                            OutlinedButton(
                                onClick = onSuspend,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC0392B))
                            ) {
                                Text("Suspend Farm")
                            }
                        }
                        VerificationStatus.SUSPENDED -> {
                            Button(onClick = onReactivate) {
                                Text("Reactivate Farm")
                            }
                        }
                        VerificationStatus.REJECTED -> {
                            Button(onClick = onApprove) {
                                Text("Approve Farm")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperAdminSetFarmLimitDialog(
    farm: Farm,
    onDismiss: () -> Unit,
    onSaveLimit: (Int) -> Unit
) {
    var limitInput by remember { mutableStateOf(farm.goatListingLimit.toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Set Listing Limit",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Farm: ${farm.name} (${farm.ownerName})",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Currently Listed: ${farm.totalGoatsListed} goats",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                "Auto-Approval Policy",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Goats listed by this farm up to this limit will be automatically approved and published live immediately. Once the farm reaches this limit, they cannot add more goats.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text("Quick Limit Presets:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                val presets = listOf(5, 10, 15, 20, 30, 50, 100)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presets) { preset ->
                        FilterChip(
                            selected = limitInput.toIntOrNull() == preset,
                            onClick = {
                                limitInput = preset.toString()
                            },
                            label = { Text("$preset Goats", fontSize = 12.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = limitInput,
                    onValueChange = { input ->
                        val filtered = input.filter { it.isDigit() }
                        limitInput = filtered
                    },
                    label = { Text("Maximum Goats Allowed") },
                    placeholder = { Text("e.g., 15") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        val parsed = limitInput.toIntOrNull() ?: 0
                        if (parsed < farm.totalGoatsListed) {
                            Text(
                                "Note: Current listed count (${farm.totalGoatsListed}) exceeds this limit. Farm admin won't be able to add new goats until older ones are removed.",
                                color = Color(0xFFE65100),
                                fontSize = 11.sp
                            )
                        } else {
                            Text(
                                "Slots remaining for auto-approved listings: ${parsed - farm.totalGoatsListed}",
                                fontSize = 11.sp
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val newLimit = limitInput.toIntOrNull()
                            if (newLimit != null && newLimit >= 0) {
                                onSaveLimit(newLimit)
                                onDismiss()
                            }
                        },
                        enabled = (limitInput.toIntOrNull() ?: -1) >= 0
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Limit")
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperAdminEditGoatDialog(
    goat: Goat,
    onDismiss: () -> Unit,
    onSave: (Goat) -> Unit
) {
    var name by remember { mutableStateOf(goat.name) }
    var breed by remember { mutableStateOf(goat.breed) }
    var priceText by remember {
        mutableStateOf(
            if (goat.price % 1.0 == 0.0) goat.price.toInt().toString() else goat.price.toString()
        )
    }
    var discountText by remember {
        mutableStateOf(
            if (goat.discountPercentage > 0.0) {
                if (goat.discountPercentage % 1.0 == 0.0) goat.discountPercentage.toInt().toString() else goat.discountPercentage.toString()
            } else "0"
        )
    }
    var weightText by remember { mutableStateOf(goat.weightKg.toString()) }
    var ageText by remember { mutableStateOf(goat.ageMonths.toString()) }
    var description by remember { mutableStateOf(goat.description) }

    val priceValidation = PriceUtils.validatePrice(priceText)
    val discountValidation = PriceUtils.validateDiscount(discountText)
    val isPriceValid = priceValidation is PriceUtils.PriceValidationResult.Valid
    val isDiscountValid = discountValidation is PriceUtils.DiscountValidationResult.Valid

    val validPrice = (priceValidation as? PriceUtils.PriceValidationResult.Valid)?.price
    val validDiscount = (discountValidation as? PriceUtils.DiscountValidationResult.Valid)?.discount ?: 0.0

    val calculatedFinalPrice: Double? = if (validPrice != null && isDiscountValid) {
        PriceUtils.calculateFinalPrice(validPrice, validDiscount)
    } else null

    val calculatedSavings: Double? = if (validPrice != null && isDiscountValid) {
        PriceUtils.calculateSavings(validPrice, validDiscount)
    } else null

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Super Admin Edit Goat", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Goat Name / Title") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = breed,
                    onValueChange = { breed = it },
                    label = { Text("Breed") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        label = { Text("Price (₹) *") },
                        isError = priceText.isNotBlank() && !isPriceValid,
                        supportingText = if (priceText.isNotBlank() && priceValidation is PriceUtils.PriceValidationResult.Error) {
                            { Text(priceValidation.message, color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1.2f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = discountText,
                        onValueChange = { discountText = it },
                        label = { Text("Discount (%)") },
                        isError = discountText.isNotBlank() && !isDiscountValid,
                        supportingText = if (discountText.isNotBlank() && discountValidation is PriceUtils.DiscountValidationResult.Error) {
                            { Text(discountValidation.message, color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isPriceValid && isDiscountValid) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                           else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(
                        1.dp,
                        if (isPriceValid && isDiscountValid) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isPriceValid && isDiscountValid && validPrice != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("Price", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(PriceUtils.formatCurrency(validPrice), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Discount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        PriceUtils.formatDiscountPercent(validDiscount),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (validDiscount > 0.0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("Discount Amount", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        PriceUtils.formatCurrency(calculatedSavings ?: 0.0),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if ((calculatedSavings ?: 0.0) > 0.0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Final Price", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    PriceUtils.formatCurrency(calculatedFinalPrice ?: validPrice),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Enter valid price and discount to view price breakdown", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = { weightText = it },
                        label = { Text("Weight (kg)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                    OutlinedTextField(
                        value = ageText,
                        onValueChange = { ageText = it },
                        label = { Text("Age (Months)") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description & Pedigree") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        enabled = isPriceValid && isDiscountValid,
                        onClick = {
                            val finalPriceVal = (priceValidation as? PriceUtils.PriceValidationResult.Valid)?.price ?: return@Button
                            val finalDiscountVal = (discountValidation as? PriceUtils.DiscountValidationResult.Valid)?.discount ?: return@Button
                            val updated = goat.copy(
                                name = name,
                                breed = breed,
                                price = finalPriceVal,
                                discountPercentage = finalDiscountVal,
                                weightKg = weightText.toDoubleOrNull() ?: goat.weightKg,
                                ageMonths = ageText.toIntOrNull() ?: goat.ageMonths,
                                description = description
                            )
                            onSave(updated)
                        }
                    ) {
                        Text("Save Changes")
                    }
                }
            }
        }
    }
}

@Composable
private fun SuperAdminInvestigateReportDialog(
    report: PlatformReport,
    uiState: MarketplaceUiState,
    onDismiss: () -> Unit,
    onUpdateReportStatus: (String, ReportStatus, String?) -> Unit,
    onResolve: (actionRemoveListing: String?, actionSuspendFarm: String?, resolutionNotes: String) -> Unit,
    onDismissReport: (dismissNotes: String) -> Unit
) {
    var resolutionNotes by remember { mutableStateOf(report.resolutionNotes ?: "") }
    var shouldSuspendTargetFarm by remember {
        mutableStateOf(report.targetType == "FARM" || report.targetType == "BREEDER")
    }
    var shouldRemoveTargetListing by remember {
        mutableStateOf(report.targetType == "GOAT" || report.targetType == "GOAT_LISTING")
    }
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()) }

    // Find related entities
    val relatedGoat = remember(report, uiState.allAdminGoats) {
        if (report.targetType in listOf("GOAT", "GOAT_LISTING")) {
            uiState.allAdminGoats.find { it.id == report.targetId }
        } else null
    }

    val relatedFarm = remember(report, uiState.farms, relatedGoat) {
        if (report.targetType in listOf("FARM", "BREEDER")) {
            uiState.farms.find { it.id == report.targetId }
        } else if (relatedGoat != null) {
            uiState.farms.find { it.id == relatedGoat.farmId }
        } else null
    }

    val relatedCustomer = remember(report, uiState.allCustomers) {
        if (report.targetType == "CUSTOMER") {
            uiState.allCustomers.find { it.id == report.targetId }
        } else {
            uiState.allCustomers.find { it.id == report.reporterId }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Report #${report.id.takeLast(8)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            dateFormat.format(Date(report.createdAt)),
                            fontSize = 10.sp,
                            color = Color.Gray
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when (report.status) {
                            ReportStatus.NEW -> Color(0xFFC0392B)
                            ReportStatus.UNDER_REVIEW -> Color(0xFFE67E22)
                            ReportStatus.RESOLVED -> Color(0xFF13663C)
                            ReportStatus.DISMISSED -> Color(0xFF7F8C8D)
                        }
                    ) {
                        Text(
                            report.status.displayName,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Target & Reason Box
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF0C2340)
                            ) {
                                Text(
                                    report.targetType.replace("_", " "),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(report.targetTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Flag Reason: ${report.reason.displayName}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Reporter: ${report.reporterName} (${report.reporterEmail})",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Claim Description
                Text("Report Description / Claim:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"${report.description}\"",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp),
                        lineHeight = 16.sp
                    )
                }

                // Evidence Photo Preview
                val evidencePhoto = report.evidencePhotoUrl ?: report.evidencePhotos.firstOrNull()
                if (!evidencePhoto.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Evidence / Photo Proof:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    AsyncImage(
                        model = evidencePhoto,
                        contentDescription = "Report Evidence Photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    )
                }

                // Related Entity Details
                if (relatedGoat != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Related Goat Listing:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val relPhoto = relatedGoat.photos.firstOrNull()
                            if (!relPhoto.isNullOrBlank()) {
                                AsyncImage(
                                    model = relPhoto,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Pets,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(relatedGoat.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Breed: ${relatedGoat.breed} • Age: ${relatedGoat.ageMonths}m • ${relatedGoat.weightKg} kg", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Price: $${relatedGoat.price} • Status: ${relatedGoat.approvalStatus} / ${relatedGoat.availabilityStatus}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                if (relatedFarm != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Related Farm / Breeder:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(relatedFarm.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Location: ${relatedFarm.location} • Phone: ${relatedFarm.contactNumber}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Farm Status: ${relatedFarm.verificationStatus} • Verified: ${if (relatedFarm.verificationStatus == VerificationStatus.APPROVED) "YES" else "NO"}", fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                // Administrative Actions
                Text("Administrative Moderation Actions:", fontWeight = FontWeight.Bold, fontSize = 12.sp)

                if (report.targetType in listOf("GOAT", "GOAT_LISTING") || relatedGoat != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shouldRemoveTargetListing,
                            onCheckedChange = { shouldRemoveTargetListing = it }
                        )
                        Text("Suspend / Remove reported goat listing", fontSize = 12.sp)
                    }
                }

                if (report.targetType in listOf("FARM", "BREEDER") || relatedFarm != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = shouldSuspendTargetFarm,
                            onCheckedChange = { shouldSuspendTargetFarm = it }
                        )
                        Text("Suspend reported farm account", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = resolutionNotes,
                    onValueChange = { resolutionNotes = it },
                    label = { Text("Resolution Notes & Audit Remarks") },
                    placeholder = { Text("e.g., Listing suspended due to false health claim. Seller warned.") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Status Shortcuts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (report.status != ReportStatus.UNDER_REVIEW) {
                        OutlinedButton(
                            onClick = {
                                onUpdateReportStatus(
                                    report.id,
                                    ReportStatus.UNDER_REVIEW,
                                    resolutionNotes.ifBlank { "Marked under investigation by Super Admin." }
                                )
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("Set Under Review", fontSize = 11.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onDismissReport(resolutionNotes.ifBlank { "Dismissed as invalid or duplicate report." })
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Dismiss Report", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        val removeGid = if (shouldRemoveTargetListing && (report.targetType in listOf("GOAT", "GOAT_LISTING") || relatedGoat != null)) {
                            if (report.targetType in listOf("GOAT", "GOAT_LISTING")) report.targetId else relatedGoat?.id
                        } else null

                        val suspendFid = if (shouldSuspendTargetFarm && (report.targetType in listOf("FARM", "BREEDER") || relatedFarm != null)) {
                            if (report.targetType in listOf("FARM", "BREEDER")) report.targetId else relatedFarm?.id
                        } else null

                        onResolve(
                            removeGid,
                            suspendFid,
                            resolutionNotes.ifBlank { "Resolved by Super Admin with moderation enforcement." }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Apply Actions & Resolve Report", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SuperAdminAccessDeniedView(
    currentRole: String,
    onNavigateToLogin: () -> Unit = {},
    onSyncRole: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("Super Admin Authorization Required", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "You do not possess platform governance authority to view or modify marketplace administration controls. Please sign in with an authorized administrator account.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Detected Role: $currentRole",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                OutlinedButton(
                    onClick = onSyncRole,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Permissions", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onNavigateToLogin,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign In with Admin Credentials")
                }
            }
        }
    }
}

@Composable
private fun KpiStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Black, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MetricMiniItem(label: String, value: String, color: Color) {
    Column {
        Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
        Text(value, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
    }
}

// ==========================================
// TAB 7: LISTING FEES & TRANSACTIONS AUDIT
// ==========================================

@Composable
private fun SuperAdminListingFeesTab(
    payments: List<com.example.model.ListingPayment>,
    goats: List<com.example.model.Goat>,
    stats: com.example.model.PlatformStats
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf("ALL") }

    val ammalGoatsCount = goats.count {
        it.farmId == SEED_AMMAL_FARM_UUID || it.farmName.contains("Ammal", ignoreCase = true)
    }

    val filteredPayments = remember(payments, searchQuery, filterStatus) {
        payments.filter { payment ->
            val matchesFilter = when (filterStatus) {
                "PAID" -> payment.status == com.example.model.PaymentStatus.PAID
                "PENDING" -> payment.status == com.example.model.PaymentStatus.PENDING
                "FAILED" -> payment.status == com.example.model.PaymentStatus.FAILED
                else -> true
            }
            val matchesSearch = searchQuery.isBlank() ||
                payment.goatName.contains(searchQuery, ignoreCase = true) ||
                payment.goatCode.contains(searchQuery, ignoreCase = true) ||
                (payment.receiptNumber?.contains(searchQuery, ignoreCase = true) == true) ||
                (payment.orderId?.contains(searchQuery, ignoreCase = true) == true) ||
                (payment.razorpayPaymentId?.contains(searchQuery, ignoreCase = true) == true)

            matchesFilter && matchesSearch
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Platform Listing Fee Metrics
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Payments, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Partner Listing Fee Ledger", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                "Total: ₹${stats.totalListingFeesCollected.toInt()}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE8F5E9),
                            border = BorderStroke(1.dp, Color(0xFF81C784)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Ammal Waivers", fontSize = 10.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold)
                                Text("$ammalGoatsCount (₹0)", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF2E7D32))
                                Text("Seed Farm 100%", fontSize = 9.sp, color = Color.Gray)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFE0F2F1),
                            border = BorderStroke(1.dp, Color(0xFF4DB6AC)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Verified Paid", fontSize = 10.sp, color = Color(0xFF00695C), fontWeight = FontWeight.SemiBold)
                                Text("${payments.count { it.status == com.example.model.PaymentStatus.PAID }}", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFF00695C))
                                Text("₹100 / listing", fontSize = 9.sp, color = Color.Gray)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFF3E0),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Pending", fontSize = 10.sp, color = Color(0xFFE65100), fontWeight = FontWeight.SemiBold)
                                Text("${payments.count { it.status == com.example.model.PaymentStatus.PENDING }}", fontSize = 14.sp, fontWeight = FontWeight.Black, color = Color(0xFFE65100))
                                Text("Awaiting payment", fontSize = 9.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        // Search & Filter row
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by receipt, goat, tag, or payment ID...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "ALL" to "All (${payments.size})",
                    "PAID" to "Paid (${payments.count { it.status == com.example.model.PaymentStatus.PAID }})",
                    "PENDING" to "Pending (${payments.count { it.status == com.example.model.PaymentStatus.PENDING }})",
                    "FAILED" to "Failed (${payments.count { it.status == com.example.model.PaymentStatus.FAILED }})"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = filterStatus == key,
                        onClick = { filterStatus = key },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }

        // Security Notice Banner
        item {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFECEFF1),
                border = BorderStroke(1.dp, Color(0xFFB0BEC5)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFF37474F), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Audit Note: Listing payments are verified atomically on the server. Super Admin approval is blocked until the ₹100 partner fee is verified.",
                        fontSize = 11.sp,
                        color = Color(0xFF37474F)
                    )
                }
            }
        }

        if (filteredPayments.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Receipt,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No payment records found", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (searchQuery.isNotBlank()) "Try clearing your search query." else "Listing fee payments from partner farms will appear here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(filteredPayments, key = { it.id }) { payment ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = payment.receiptNumber ?: "RCPT-${payment.id.take(8).uppercase()}",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Goat: ${payment.goatName.ifBlank { payment.goatCode.ifBlank { "Listing" } }}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (payment.status) {
                                    com.example.model.PaymentStatus.PAID -> Color(0xFF13663C)
                                    com.example.model.PaymentStatus.PENDING -> Color(0xFFD48B06)
                                    com.example.model.PaymentStatus.FAILED -> Color(0xFFC62828)
                                    else -> Color.Gray
                                }
                            ) {
                                Text(
                                    text = payment.status.name,
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "Amount: ₹${payment.amount.toInt()} ${payment.currency}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                "Order: ${payment.orderId?.takeLast(12) ?: "N/A"}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        if (!payment.razorpayPaymentId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Payment Ref: ${payment.razorpayPaymentId}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            color = Color(0xFFF1F8E9),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = Color(0xFF33691E), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "Server-Authoritative Atomic Supabase RPC",
                                    fontSize = 10.sp,
                                    color = Color(0xFF33691E),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
