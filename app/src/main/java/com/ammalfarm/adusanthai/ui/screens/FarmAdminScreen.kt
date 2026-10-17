package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.core.util.FarmLocations
import com.ammalfarm.adusanthai.core.util.GoatFormValidator
import com.ammalfarm.adusanthai.core.util.PlatformPricingManager
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmAdminScreen(
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    userFarm: Farm? = null,
    onNavigateToMarketplace: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val activeFarm = remember(uiState?.farms, userFarm, currentUser) {
        uiState?.farms?.find { it.id == currentUser?.farmId || it.ownerId == currentUser?.id } ?: userFarm
    }
    val farmGoats = remember(uiState?.allGoats, activeFarm?.id) {
        if (activeFarm == null) emptyList()
        else uiState?.allGoats?.filter { it.farmId == activeFarm.id }.orEmpty()
    }
    val farmBookings = remember(uiState?.bookings, activeFarm?.id) {
        if (activeFarm == null) emptyList()
        else uiState?.bookings?.filter { it.farmId == activeFarm.id }.orEmpty()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Dashboard, 1: Goats, 2: Bookings, 3: Farm Profile
    var showAddEditGoatDialog by remember { mutableStateOf(false) }
    var editingGoat by remember { mutableStateOf<Goat?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentUser?.id) {
        if (currentUser != null) {
            marketplaceViewModel?.loadUserSpecificData(currentUser)
            marketplaceViewModel?.refreshMarketplace()
        }
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

    val isFarmApproved = activeFarm?.verificationStatus?.isApprovedOrVerified == true || activeFarm?.isAmmalOwnFarm == true
    val consumedSlots = farmGoats.size
    val totalSlots = activeFarm?.goatListingLimit ?: 2

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(activeFarm?.name?.ifBlank { "Farm Dashboard" } ?: "Farm Dashboard", fontWeight = FontWeight.Bold)
                        Text(
                            text = if (isFarmApproved) "Verified Partner Farm • $consumedSlots/$totalSlots Slots"
                            else "Verification Status: ${activeFarm?.verificationStatus?.name ?: "PENDING"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isFarmApproved) MaterialTheme.colorScheme.primary else Color(0xFFE65100)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToMarketplace) {
                        Icon(Icons.Default.Storefront, contentDescription = "View Marketplace")
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
            if (selectedTab == 0 || selectedTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editingGoat = null
                        showAddEditGoatDialog = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Add Goat Listing") },
                    modifier = Modifier.testTag("fab_add_goat")
                )
            }
        },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Overview") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Pets, contentDescription = null) },
                    label = { Text("Goats (${farmGoats.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.EventNote, contentDescription = null) },
                    label = { Text("Bookings (${farmBookings.size})") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Agriculture, contentDescription = null) },
                    label = { Text("Farm Info") }
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
            when (selectedTab) {
                0 -> FarmOverviewTab(
                    farm = activeFarm,
                    farmGoats = farmGoats,
                    farmBookings = farmBookings,
                    onAddGoatClick = {
                        editingGoat = null
                        showAddEditGoatDialog = true
                    },
                    onManageGoatsClick = { selectedTab = 1 },
                    onManageBookingsClick = { selectedTab = 2 }
                )

                1 -> FarmGoatsManagementTab(
                    farmGoats = farmGoats,
                    onEditGoat = { goat ->
                        editingGoat = goat
                        showAddEditGoatDialog = true
                    },
                    onDeleteGoat = { goatId ->
                        marketplaceViewModel?.deleteGoat(goatId)
                    },
                    onUpdateAvailability = { goatId, status ->
                        marketplaceViewModel?.updateGoatAvailability(goatId, status)
                    }
                )

                2 -> FarmBookingsTab(
                    bookings = farmBookings,
                    onUpdateStatus = { bookingId, status ->
                        marketplaceViewModel?.updateBookingStatus(bookingId, status)
                    }
                )

                3 -> FarmProfileEditorTab(
                    farm = activeFarm,
                    currentUser = currentUser,
                    onSaveFarm = { updated ->
                        marketplaceViewModel?.updateFarmProfile(updated)
                    },
                    onLogout = onLogout
                )
            }
        }
    }

    if (showAddEditGoatDialog && activeFarm != null && marketplaceViewModel != null) {
        AddEditGoatDialog(
            initialGoat = editingGoat,
            farm = activeFarm,
            isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN,
            onDismiss = { showAddEditGoatDialog = false },
            onSave = { goat, imageBytes ->
                marketplaceViewModel.saveGoat(
                    goat = goat,
                    imageBytesList = imageBytes,
                    isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN,
                    onSuccess = { showAddEditGoatDialog = false }
                )
            }
        )
    }
}

@Composable
private fun FarmOverviewTab(
    farm: Farm?,
    farmGoats: List<Goat>,
    farmBookings: List<Booking>,
    onAddGoatClick: () -> Unit,
    onManageGoatsClick: () -> Unit,
    onManageBookingsClick: () -> Unit
) {
    val context = LocalContext.current
    val isApproved = farm?.verificationStatus?.isApprovedOrVerified == true || farm?.isAmmalOwnFarm == true

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isApproved) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PendingActions, contentDescription = null, tint = Color(0xFFE65100))
                            Text(
                                text = "Partner Farm Verification Pending",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                        Text(
                            text = "Your farm account is awaiting Super Admin verification (Approval Fee: ₹${PlatformPricingManager.farmApprovalFeeInr.toInt()}). Once verified, your approved goat listings will be visible in the public marketplace.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF4E342E)
                        )
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+916380898358"))
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Contact Super Admin (+91 63808 98358)")
                        }
                    }
                }
            }
        }

        // Metrics Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricStatCard(
                    title = "Total Goats",
                    value = "${farmGoats.size} / ${farm?.goatListingLimit ?: 2}",
                    subtitle = "Listing Quota",
                    icon = Icons.Default.Pets,
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    title = "Available",
                    value = farmGoats.count { it.availabilityStatus == AvailabilityStatus.AVAILABLE }.toString(),
                    subtitle = "${farmGoats.count { it.approvalStatus == ApprovalStatus.APPROVED }} Approved",
                    icon = Icons.Default.CheckCircle,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricStatCard(
                    title = "Reserved / Sold",
                    value = "${farmGoats.count { it.availabilityStatus == AvailabilityStatus.RESERVED }} / ${farmGoats.count { it.availabilityStatus == AvailabilityStatus.SOLD }}",
                    subtitle = "Active & Closed",
                    icon = Icons.Default.ShoppingBag,
                    modifier = Modifier.weight(1f)
                )
                MetricStatCard(
                    title = "Bookings",
                    value = farmBookings.size.toString(),
                    subtitle = "${farmBookings.count { it.status == BookingStatus.PENDING }} Pending",
                    icon = Icons.Default.EventNote,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Quick Actions
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Quick Actions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onAddGoatClick, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Goat")
                        }
                        OutlinedButton(onClick = onManageBookingsClick, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.EventNote, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Bookings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetricStatCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun FarmGoatsManagementTab(
    farmGoats: List<Goat>,
    onEditGoat: (Goat) -> Unit,
    onDeleteGoat: (String) -> Unit,
    onUpdateAvailability: (String, AvailabilityStatus) -> Unit
) {
    if (farmGoats.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No goat listings added yet. Tap 'Add Goat Listing' to create your first listing.")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(farmGoats, key = { it.id }) { goat ->
                ElevatedCard(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(10.dp))
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
                                    Icon(Icons.Default.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }

                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(goat.name.ifBlank { "${goat.breed} Goat" }, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("${goat.breed} • ${goat.gender} • ${goat.ageMonths}m • ${goat.weightKg}kg", style = MaterialTheme.typography.bodySmall)
                                Text(PriceUtils.formatINR(goat.effectivePrice), fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            }

                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    color = when (goat.approvalStatus) {
                                        ApprovalStatus.APPROVED -> Color(0xFF1B5E20).copy(alpha = 0.15f)
                                        ApprovalStatus.PENDING -> Color(0xFFE65100).copy(alpha = 0.15f)
                                        ApprovalStatus.REJECTED -> Color(0xFFB71C1C).copy(alpha = 0.15f)
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = goat.approvalStatus.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (goat.approvalStatus) {
                                            ApprovalStatus.APPROVED -> Color(0xFF1B5E20)
                                            ApprovalStatus.PENDING -> Color(0xFFE65100)
                                            ApprovalStatus.REJECTED -> Color(0xFFB71C1C)
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = goat.availabilityStatus.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        if (!goat.rejectionReason.isNullOrBlank()) {
                            Text(
                                text = "Moderation Note: ${goat.rejectionReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onEditGoat(goat) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Edit")
                            }

                            if (goat.availabilityStatus != AvailabilityStatus.SOLD) {
                                OutlinedButton(
                                    onClick = { onUpdateAvailability(goat.id, AvailabilityStatus.SOLD) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Mark Sold")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onUpdateAvailability(goat.id, AvailabilityStatus.AVAILABLE) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Relist")
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
fun FarmBookingsTab(
    bookings: List<Booking>,
    onUpdateStatus: (String, BookingStatus) -> Unit
) {
    val context = LocalContext.current
    if (bookings.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No customer booking requests received yet.")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(bookings, key = { it.id }) { booking ->
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${booking.goatName.ifBlank { "Goat" }} • ${PriceUtils.formatINR(booking.goatPrice)}",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = booking.status.name,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Text("Customer: ${booking.customerName.ifBlank { "Customer" }} (${booking.customerPhone})")
                        if (booking.preferredVisitDate.isNotBlank()) {
                            Text("Visit Date: ${booking.preferredVisitDate}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (booking.notes.isNotBlank()) {
                            Text("Notes: ${booking.notes}", style = MaterialTheme.typography.bodySmall)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (booking.customerPhone.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.customerPhone}"))
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Call")
                                }
                            }

                            if (booking.status == BookingStatus.PENDING) {
                                Button(
                                    onClick = { onUpdateStatus(booking.id, BookingStatus.CONFIRMED) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Approve")
                                }
                                OutlinedButton(
                                    onClick = { onUpdateStatus(booking.id, BookingStatus.REJECTED) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject")
                                }
                            } else if (booking.status == BookingStatus.CONFIRMED || booking.status == BookingStatus.APPROVED) {
                                Button(
                                    onClick = { onUpdateStatus(booking.id, BookingStatus.COMPLETED) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Complete Sale")
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
private fun FarmProfileEditorTab(
    farm: Farm?,
    currentUser: UserProfile?,
    onSaveFarm: (Farm) -> Unit,
    onLogout: () -> Unit
) {
    var farmName by remember(farm) { mutableStateOf(farm?.name ?: "") }
    var ownerName by remember(farm, currentUser) { mutableStateOf(farm?.ownerName?.ifBlank { currentUser?.name ?: "" } ?: "") }
    var district by remember(farm) { mutableStateOf(farm?.district?.ifBlank { "Madurai" } ?: "Madurai") }
    var phone by remember(farm, currentUser) { mutableStateOf(farm?.contactNumber?.ifBlank { currentUser?.phone ?: "" } ?: "") }
    var description by remember(farm) { mutableStateOf(farm?.description ?: "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Farm Profile Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = farmName,
            onValueChange = { farmName = it },
            label = { Text("Farm Name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = ownerName,
            onValueChange = { ownerName = it },
            label = { Text("Owner / Representative Name *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = district,
            onValueChange = { district = it },
            label = { Text("District (Tamil Nadu) *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Contact Phone *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Farm Description & Specialties") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = {
                if (farm != null) {
                    onSaveFarm(
                        farm.copy(
                            name = farmName.trim(),
                            ownerName = ownerName.trim(),
                            district = district.trim(),
                            location = "${district.trim()}, Tamil Nadu",
                            contactNumber = phone.trim(),
                            description = description.trim()
                        )
                    )
                }
            },
            enabled = farm != null && farmName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text("Save Farm Details", fontWeight = FontWeight.Bold)
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign Out")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditGoatDialog(
    initialGoat: Goat?,
    farm: Farm,
    isSuperAdmin: Boolean,
    onDismiss: () -> Unit,
    onSave: (Goat, List<ByteArray>) -> Unit
) {
    val context = LocalContext.current
    var name by remember(initialGoat) { mutableStateOf(initialGoat?.name ?: "") }
    var tagId by remember(initialGoat) { mutableStateOf(initialGoat?.tagId ?: "") }
    var breed by remember(initialGoat) { mutableStateOf(initialGoat?.breed?.ifBlank { "Tellicherry" } ?: "Tellicherry") }
    var gender by remember(initialGoat) { mutableStateOf(initialGoat?.gender?.ifBlank { "Male" } ?: "Male") }
    var ageStr by remember(initialGoat) { mutableStateOf((initialGoat?.ageMonths ?: 12).toString()) }
    var weightStr by remember(initialGoat) { mutableStateOf((initialGoat?.weightKg ?: 28.0).toString()) }
    var priceStr by remember(initialGoat) { mutableStateOf(if ((initialGoat?.price ?: 0.0) > 0.0) initialGoat!!.price.toInt().toString() else "18000") }
    var discountStr by remember(initialGoat) { mutableStateOf((initialGoat?.discountPercentage ?: 0.0).toInt().toString()) }
    var color by remember(initialGoat) { mutableStateOf(initialGoat?.color?.ifBlank { "White & Brown" } ?: "White & Brown") }
    var purpose by remember(initialGoat) { mutableStateOf(initialGoat?.purpose?.ifBlank { "Breeding" } ?: "Breeding") }
    var description by remember(initialGoat) { mutableStateOf(initialGoat?.description ?: "") }
    var healthNotes by remember(initialGoat) { mutableStateOf(initialGoat?.healthNotes?.ifBlank { "PPR & ET Vaccinated, Dewormed" } ?: "PPR & ET Vaccinated, Dewormed") }
    var isVaccinated by remember(initialGoat) { mutableStateOf(initialGoat?.isVaccinated ?: true) }
    var imageUrlInput by remember(initialGoat) { mutableStateOf(initialGoat?.imageUrl ?: "") }
    var selectedImageBytes by remember { mutableStateOf<List<ByteArray>>(emptyList()) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        val bytesList = uris.mapNotNull { uri ->
            try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Exception) { null }
        }
        if (bytesList.isNotEmpty()) {
            selectedImageBytes = bytesList
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialGoat == null) "Add Goat Listing" else "Edit Goat Listing",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (validationError != null) {
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Goat Name / Title *") },
                    placeholder = { Text("e.g. Prime Tellicherry Buck") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = breed,
                        onValueChange = { breed = it },
                        label = { Text("Breed *") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = tagId,
                        onValueChange = { tagId = it },
                        label = { Text("Ear Tag ID") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = gender.equals("Male", ignoreCase = true),
                        onClick = { gender = "Male" },
                        label = { Text("Male (Buck)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = gender.equals("Female", ignoreCase = true),
                        onClick = { gender = "Female" },
                        label = { Text("Female (Doe)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = ageStr,
                        onValueChange = { ageStr = it },
                        label = { Text("Age (Months) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = weightStr,
                        onValueChange = { weightStr = it },
                        label = { Text("Weight (kg) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = priceStr,
                        onValueChange = { priceStr = it },
                        label = { Text("Price (INR) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = discountStr,
                        onValueChange = { discountStr = it },
                        label = { Text("Discount %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = purpose,
                        onValueChange = { purpose = it },
                        label = { Text("Purpose") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = color,
                        onValueChange = { color = it },
                        label = { Text("Coat Color") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = healthNotes,
                    onValueChange = { healthNotes = it },
                    label = { Text("Health & Vaccination Notes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(
                    onClick = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (selectedImageBytes.isEmpty()) "Select Goat Photos from Gallery"
                        else "${selectedImageBytes.size} Photo(s) Selected for Upload"
                    )
                }

                OutlinedTextField(
                    value = imageUrlInput,
                    onValueChange = { imageUrlInput = it },
                    label = { Text("Or Image URL (Optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val age = ageStr.toIntOrNull() ?: 0
                    val weight = weightStr.toDoubleOrNull() ?: 0.0
                    val price = priceStr.toDoubleOrNull() ?: 0.0
                    val discount = (discountStr.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 90.0)

                    val isValid = GoatFormValidator.isValid(
                        name = name,
                        breed = breed,
                        price = price,
                        weight = weight,
                        age = age
                    )
                    if (!isValid) {
                        validationError = "Please enter valid name, breed, age (>0), weight (>0), and price (>0)."
                        return@Button
                    }

                    val goat = (initialGoat ?: Goat()).copy(
                        name = name.trim(),
                        tagId = tagId.trim(),
                        breed = breed.trim(),
                        gender = gender.trim(),
                        ageMonths = age,
                        weightKg = weight,
                        price = price,
                        discountPercentage = discount,
                        finalPrice = price * (1.0 - discount / 100.0),
                        color = color.trim(),
                        purpose = purpose.trim(),
                        description = description.trim(),
                        healthNotes = healthNotes.trim(),
                        isVaccinated = isVaccinated,
                        imageUrl = imageUrlInput.trim(),
                        farmId = farm.id,
                        farmName = farm.name,
                        farmLocation = farm.location,
                        farmContact = farm.contactNumber,
                        approvalStatus = if (isSuperAdmin) ApprovalStatus.APPROVED else (initialGoat?.approvalStatus ?: ApprovalStatus.PENDING)
                    )
                    onSave(goat, selectedImageBytes)
                }
            ) {
                Text("Save Listing")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
