package com.ammalfarm.adusanthai.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.launch

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
        val farms = uiState?.farms.orEmpty()
        val matchedByOwner = if (!currentUser?.id.isNullOrBlank()) {
            farms.find { it.ownerId.equals(currentUser!!.id, ignoreCase = true) }
        } else null
        val matchedByFarmId = if (!currentUser?.farmId.isNullOrBlank()) {
            farms.find { it.id.equals(currentUser!!.farmId, ignoreCase = true) }
        } else null
        val fallbackUserFarm = userFarm?.takeIf {
            currentUser == null ||
                currentUser.role == UserRole.SUPER_ADMIN ||
                (currentUser.id.isNotBlank() && it.ownerId.equals(currentUser.id, ignoreCase = true)) ||
                (!currentUser.farmId.isNullOrBlank() && it.id.equals(currentUser.farmId, ignoreCase = true))
        }
        matchedByOwner ?: matchedByFarmId ?: fallbackUserFarm
    }
    val farmGoats = remember(uiState?.allGoats, activeFarm?.id) {
        if (activeFarm == null) emptyList()
        else uiState?.allGoats?.filter { it.farmId.equals(activeFarm.id, ignoreCase = true) }.orEmpty()
    }
    val farmBookings = remember(uiState?.bookings, activeFarm?.id) {
        if (activeFarm == null) emptyList()
        else uiState?.bookings?.filter { it.farmId.equals(activeFarm.id, ignoreCase = true) }.orEmpty()
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Dashboard, 1: Goats, 2: Bookings, 3: Farm Profile
    var showAddEditGoatDialog by remember { mutableStateOf(false) }
    var editingGoat by remember { mutableStateOf<Goat?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
    val activeGoatCount = farmGoats.count { it.isActive && it.availabilityStatus != AvailabilityStatus.UNAVAILABLE }
    val consumedSlots = maxOf(activeFarm?.consumedListingSlots ?: 0, activeGoatCount)
    val totalSlots = activeFarm?.goatListingLimit ?: 2

    val isFarmSuspended = activeFarm?.verificationStatus == VerificationStatus.SUSPENDED

    val handleOpenAddGoat: () -> Unit = {
        if (activeFarm == null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Loading your farm profile. Please wait or check Farm Info tab.")
            }
            marketplaceViewModel?.refreshMarketplace()
        } else if (isFarmSuspended) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Your farm is currently suspended. Creating new goat listings is disabled.")
            }
        } else {
            editingGoat = null
            showAddEditGoatDialog = true
        }
    }

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
                            color = if (isFarmApproved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToMarketplace) {
                        Icon(Icons.Default.Storefront, contentDescription = "View Marketplace")
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
            if (selectedTab == 0 || selectedTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = handleOpenAddGoat,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isFarmSuspended) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Text(
                            text = "Farm Suspended: This farm account is suspended. Creating new goat listings and farm profile edits are disabled. Contact Super Admin for assistance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (selectedTab) {
                0 -> FarmOverviewTab(
                    farm = activeFarm,
                    farmGoats = farmGoats,
                    farmBookings = farmBookings,
                    onAddGoatClick = handleOpenAddGoat,
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
                    isSaving = uiState?.isLoading == true,
                    onSaveFarm = { updated, logoBytes, bannerBytes ->
                        marketplaceViewModel?.updateFarmProfile(updated, logoBytes, bannerBytes)
                    },
                    onLogout = onLogout
                )
            }
        }
    }
    }

    if (showAddEditGoatDialog && activeFarm != null && marketplaceViewModel != null) {
        AddEditGoatDialog(
            initialGoat = editingGoat,
            farm = activeFarm,
            isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN,
            availableBreeds = uiState?.availableBreeds.orEmpty(),
            isSaving = uiState?.isLoading == true,
            onDismiss = { showAddEditGoatDialog = false },
            onSave = { goat, imageBytes ->
                marketplaceViewModel.saveGoat(
                    goat = goat,
                    imageBytesList = imageBytes,
                    isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN,
                    actorUserId = currentUser?.id,
                    actorFarmId = activeFarm.id,
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
    val activeGoatCount = farmGoats.count { it.isActive && it.availabilityStatus != AvailabilityStatus.UNAVAILABLE }
    val activeCount = maxOf(farm?.consumedListingSlots ?: 0, activeGoatCount)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isApproved) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PendingActions, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Text(
                                text = "Partner Farm Verification Pending",
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        Text(
                            text = "Your farm account is awaiting Super Admin verification (Approval Fee: ₹${PlatformPricingManager.farmApprovalFeeInr.toInt()}). Once verified, your approved goat listings will be visible in the public marketplace.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
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
                    title = "Listing Quota",
                    value = "$activeCount / ${farm?.goatListingLimit ?: 2}",
                    subtitle = "${((farm?.goatListingLimit ?: 2) - activeCount).coerceAtLeast(0)} Slots Available",
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
                val displayPhoto = goat.primaryPhoto
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
                                if (displayPhoto.isNotBlank()) {
                                    AsyncImage(
                                        model = displayPhoto,
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
                                        ApprovalStatus.APPROVED -> MaterialTheme.colorScheme.primaryContainer
                                        ApprovalStatus.REJECTED, ApprovalStatus.SUSPENDED -> MaterialTheme.colorScheme.errorContainer
                                        else -> MaterialTheme.colorScheme.tertiaryContainer
                                    },
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = goat.approvalStatus.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (goat.approvalStatus) {
                                            ApprovalStatus.APPROVED -> MaterialTheme.colorScheme.onPrimaryContainer
                                            ApprovalStatus.REJECTED, ApprovalStatus.SUSPENDED -> MaterialTheme.colorScheme.onErrorContainer
                                            else -> MaterialTheme.colorScheme.onTertiaryContainer
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
    isSaving: Boolean = false,
    onSaveFarm: (Farm, ByteArray?, ByteArray?) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var farmName by remember(farm) { mutableStateOf(farm?.name ?: "") }
    var ownerName by remember(farm, currentUser) { mutableStateOf(farm?.ownerName?.ifBlank { currentUser?.name ?: "" } ?: "") }
    var district by remember(farm) { mutableStateOf(farm?.district?.ifBlank { "Madurai" } ?: "Madurai") }
    var phone by remember(farm, currentUser) { mutableStateOf(farm?.contactNumber?.ifBlank { currentUser?.phone ?: "" } ?: "") }
    var description by remember(farm) { mutableStateOf(farm?.description ?: "") }

    var selectedLogoBytes by remember(farm?.id) { mutableStateOf<ByteArray?>(null) }
    var selectedBannerBytes by remember(farm?.id) { mutableStateOf<ByteArray?>(null) }

    val logoBitmap = remember(selectedLogoBytes) {
        selectedLogoBytes?.let { bytes ->
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } catch (_: Exception) { null }
        }
    }
    val bannerBitmap = remember(selectedBannerBytes) {
        selectedBannerBytes?.let { bytes ->
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() } catch (_: Exception) { null }
        }
    }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bytes = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                selectedLogoBytes = bytes
            }
        }
    }

    val bannerPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bytes = try { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (_: Exception) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                selectedBannerBytes = bytes
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Farm Profile & Branding", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        // Farm Banner & Logo Preview Card (matches FarmDetailScreen appearance)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        )
                ) {
                    val existingBannerUrl = farm?.bannerUrl
                    when {
                        bannerBitmap != null -> {
                            Image(
                                bitmap = bannerBitmap,
                                contentDescription = "Selected Farm Banner Preview",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        !existingBannerUrl.isNullOrBlank() -> {
                            AsyncImage(
                                model = existingBannerUrl,
                                contentDescription = "Farm Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    if (selectedBannerBytes != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("New Banner Preview", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { selectedBannerBytes = null },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear Banner Selection", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                        modifier = Modifier.size(64.dp)
                    ) {
                        val existingLogoUrl = farm?.logoUrl?.ifBlank { farm.imageUrl }.orEmpty()
                        when {
                            logoBitmap != null -> {
                                Image(
                                    bitmap = logoBitmap,
                                    contentDescription = "Selected Farm Logo Preview",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            existingLogoUrl.isNotBlank() -> {
                                AsyncImage(
                                    model = existingLogoUrl,
                                    contentDescription = "Farm Logo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            else -> {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.Agriculture,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = farmName.ifBlank { farm?.name ?: "Your Farm" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$district, Tamil Nadu",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selectedLogoBytes != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "New Logo Preview",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { selectedLogoBytes = null },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear Logo Selection", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            logoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_upload_farm_logo")
                    ) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (farm?.logoUrl.isNullOrBlank()) "Upload Logo" else "Change Logo")
                    }

                    OutlinedButton(
                        onClick = {
                            bannerPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_upload_farm_banner")
                    ) {
                        Icon(Icons.Default.Wallpaper, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (farm?.bannerUrl.isNullOrBlank()) "Upload Banner" else "Change Banner")
                    }
                }
            }
        }

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
                val baseFarm = farm ?: Farm(
                    id = currentUser?.farmId ?: "",
                    ownerId = currentUser?.id ?: ""
                )
                onSaveFarm(
                    baseFarm.copy(
                        name = farmName.trim(),
                        ownerName = ownerName.trim(),
                        district = district.trim(),
                        location = "${district.trim()}, Tamil Nadu",
                        contactNumber = phone.trim(),
                        description = description.trim()
                    ),
                    selectedLogoBytes,
                    selectedBannerBytes
                )
                selectedLogoBytes = null
                selectedBannerBytes = null
            },
            enabled = !isSaving && farmName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("btn_save_farm_profile")
        ) {
            if (isSaving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Save Farm Details & Branding", fontWeight = FontWeight.Bold)
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
    availableBreeds: List<String> = emptyList(),
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Goat, List<ByteArray>) -> Unit
) {
    val context = LocalContext.current
    var name by remember(initialGoat) { mutableStateOf(initialGoat?.name ?: "") }

    val standardBreeds = remember(availableBreeds) {
        val fromState = availableBreeds.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }
        val defaults = listOf(
            "Jamunapari", "Boer", "Sirohi", "Barbari", "Tellicherry (Malabari)",
            "Beetal", "Black Bengal", "Kanni Aadu", "Salem Black", "Kodi Aadu",
            "Tellicherry", "Kanni Adu", "Kodi Adu", "Osmanabadi"
        )
        (fromState + defaults).distinct().sorted()
    }

    val initialBreedValue = remember(initialGoat) {
        initialGoat?.breed?.ifBlank { "Tellicherry" } ?: "Tellicherry"
    }
    var breed by remember(initialBreedValue) { mutableStateOf(initialBreedValue) }
    val isInitialCustom = remember(initialBreedValue, standardBreeds) {
        initialBreedValue.isNotBlank() && !standardBreeds.any { it.equals(initialBreedValue, ignoreCase = true) }
    }
    var isCustomBreed by remember(isInitialCustom) { mutableStateOf(isInitialCustom) }
    var customBreedName by remember(initialBreedValue, isInitialCustom) {
        mutableStateOf(if (isInitialCustom) initialBreedValue else "")
    }
    var breedDropdownExpanded by remember { mutableStateOf(false) }

    var gender by remember(initialGoat) { mutableStateOf(initialGoat?.gender?.ifBlank { "Male" } ?: "Male") }
    var ageStr by remember(initialGoat) { mutableStateOf((initialGoat?.ageMonths ?: 12).toString()) }
    var weightStr by remember(initialGoat) { mutableStateOf((initialGoat?.weightKg ?: 28.0).toString()) }
    var priceStr by remember(initialGoat) { mutableStateOf(if ((initialGoat?.price ?: 0.0) > 0.0) initialGoat!!.price.toInt().toString() else "18000") }
    var discountStr by remember(initialGoat) { mutableStateOf((initialGoat?.discountPercentage ?: 0.0).toInt().toString()) }
    var description by remember(initialGoat) { mutableStateOf(initialGoat?.description ?: "") }

    // Existing uploaded photos (for editing) and newly selected photos (before upload)
    var existingImageUrls by remember(initialGoat) {
        val urls = ((initialGoat?.imageUrls ?: emptyList()) + listOfNotNull(initialGoat?.imageUrl?.takeIf { it.isNotBlank() }))
            .distinct()
            .filter { it.isNotBlank() }
        mutableStateOf(urls)
    }
    var selectedImageBytes by remember(initialGoat) { mutableStateOf<List<ByteArray>>(emptyList()) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSubmittingLocal by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 5)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val loaded = mutableListOf<ByteArray>()
            var readFailed = false
            uris.forEach { uri ->
                val bytes = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Exception) { null }
                if (bytes != null && bytes.isNotEmpty()) {
                    loaded.add(bytes)
                } else {
                    readFailed = true
                }
            }
            if (readFailed) {
                validationError = "One or more selected photos could not be read. Please choose valid image files."
            } else {
                validationError = null
            }
            if (loaded.isNotEmpty()) {
                selectedImageBytes = (selectedImageBytes + loaded).take(6)
            }
        }
    }

    var cameraPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && cameraPhotoUri != null) {
            val bytes = try {
                context.contentResolver.openInputStream(cameraPhotoUri!!)?.use { it.readBytes() }
            } catch (_: Exception) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                selectedImageBytes = (selectedImageBytes + listOf(bytes)).take(6)
                validationError = null
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                val photoFile = File.createTempFile("goat_cam_", ".jpg", context.cacheDir)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile
                )
                cameraPhotoUri = uri
                cameraLauncher.launch(uri)
            } catch (e: Exception) {
                validationError = "Could not start camera: ${e.message}"
            }
        } else {
            validationError = "Camera permission is required to capture goat photos."
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingLocal && !isSaving) onDismiss()
        },
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
                    modifier = Modifier.fillMaxWidth().testTag("input_goat_name")
                )

                // Breed selection: dropdown populated from existing breeds table + option to enter custom breed
                ExposedDropdownMenuBox(
                    expanded = breedDropdownExpanded,
                    onExpandedChange = { breedDropdownExpanded = it },
                    modifier = Modifier.fillMaxWidth().testTag("dropdown_goat_breed")
                ) {
                    OutlinedTextField(
                        value = if (isCustomBreed) "Other (Custom Breed)" else breed,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Breed *") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = breedDropdownExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = breedDropdownExpanded,
                        onDismissRequest = { breedDropdownExpanded = false }
                    ) {
                        standardBreeds.forEach { standardBreed ->
                            DropdownMenuItem(
                                text = { Text(standardBreed) },
                                onClick = {
                                    breed = standardBreed
                                    isCustomBreed = false
                                    breedDropdownExpanded = false
                                },
                                modifier = Modifier.testTag("menu_item_breed_${standardBreed.replace(" ", "_")}")
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "Other (Enter Custom Breed)...",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = {
                                isCustomBreed = true
                                if (customBreedName.isNotBlank()) {
                                    breed = customBreedName
                                }
                                breedDropdownExpanded = false
                            },
                            modifier = Modifier.testTag("menu_item_breed_custom")
                        )
                    }
                }

                if (isCustomBreed) {
                    OutlinedTextField(
                        value = customBreedName,
                        onValueChange = {
                            customBreedName = it
                            breed = it
                        },
                        label = { Text("Enter Custom Breed Name *") },
                        placeholder = { Text("e.g. Attappady Black, Crossbred") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_breed")
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
                        modifier = Modifier.weight(1f).testTag("input_goat_age")
                    )
                    OutlinedTextField(
                        value = weightStr,
                        onValueChange = { weightStr = it },
                        label = { Text("Weight (kg) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("input_goat_weight")
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = priceStr,
                        onValueChange = { priceStr = it },
                        label = { Text("Price (INR) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("input_goat_price")
                    )
                    OutlinedTextField(
                        value = discountStr,
                        onValueChange = { discountStr = it },
                        label = { Text("Discount %") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("input_goat_discount")
                    )
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    placeholder = { Text("Additional details about this goat...") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("input_goat_description")
                )

                // Photos Section: Take Photo (Camera) + Gallery Selection
                Text(
                    text = "Goat Photos",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                if (existingImageUrls.isNotEmpty() || selectedImageBytes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        existingImageUrls.forEachIndexed { index, url ->
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                AsyncImage(
                                    model = url,
                                    contentDescription = "Existing Goat Photo ${index + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            existingImageUrls = existingImageUrls.filterIndexed { i, _ -> i != index }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove Photo",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }

                        selectedImageBytes.forEachIndexed { index, bytes ->
                            val bitmap = remember(bytes) {
                                try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                } catch (_: Exception) { null }
                            }
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap,
                                        contentDescription = "Selected Goat Photo ${index + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Image, contentDescription = null)
                                    }
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(4.dp)
                                        .size(24.dp)
                                ) {
                                    IconButton(
                                        onClick = {
                                            selectedImageBytes = selectedImageBytes.filterIndexed { i, _ -> i != index }
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Remove Selected Photo",
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Dual Options: Take Photo via Camera & Pick from Gallery
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val hasCameraPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasCameraPerm) {
                                try {
                                    val photoFile = File.createTempFile("goat_cam_", ".jpg", context.cacheDir)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        photoFile
                                    )
                                    cameraPhotoUri = uri
                                    cameraLauncher.launch(uri)
                                } catch (e: Exception) {
                                    validationError = "Could not start camera: ${e.message}"
                                }
                            } else {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_take_goat_photo_camera")
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Take Photo")
                    }

                    OutlinedButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("btn_pick_goat_photos")
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        val totalPhotos = existingImageUrls.size + selectedImageBytes.size
                        Text(if (totalPhotos == 0) "Gallery" else "Gallery ($totalPhotos)")
                    }
                }
            }
        },
        confirmButton = {
            val isBusy = isSubmittingLocal || isSaving
            Button(
                onClick = {
                    if (isBusy) return@Button

                    val age = ageStr.toIntOrNull() ?: 0
                    val weight = weightStr.toDoubleOrNull() ?: 0.0
                    val price = priceStr.toDoubleOrNull() ?: 0.0
                    val discount = (discountStr.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 90.0)

                    val resolvedBreed = if (isCustomBreed) customBreedName.trim() else breed.trim()
                    val isValid = GoatFormValidator.isValid(
                        name = name,
                        breed = resolvedBreed,
                        price = price,
                        weight = weight,
                        age = age
                    )
                    if (!isValid) {
                        validationError = "Please enter valid name, breed, age (>0), weight (>0), and price (>0)."
                        return@Button
                    }

                    isSubmittingLocal = true
                    val baseGoat = initialGoat ?: Goat()
                    val preservedFarmId = initialGoat?.farmId?.takeIf { it.isNotBlank() } ?: farm.id
                    val goat = baseGoat.copy(
                        name = name.trim(),
                        breed = resolvedBreed,
                        gender = gender.trim(),
                        ageMonths = age,
                        weightKg = weight,
                        price = price,
                        discountPercentage = discount,
                        finalPrice = price * (1.0 - discount / 100.0),
                        description = description.trim(),
                        imageUrl = existingImageUrls.firstOrNull() ?: "",
                        imageUrls = existingImageUrls,
                        farmId = preservedFarmId,
                        farmName = initialGoat?.farmName?.takeIf { it.isNotBlank() } ?: farm.name,
                        farmLocation = initialGoat?.farmLocation?.takeIf { it.isNotBlank() } ?: farm.location,
                        farmContact = initialGoat?.farmContact?.takeIf { it.isNotBlank() } ?: farm.contactNumber,
                        approvalStatus = initialGoat?.approvalStatus ?: (if (isSuperAdmin) ApprovalStatus.APPROVED else ApprovalStatus.PENDING)
                    )
                    onSave(goat, selectedImageBytes)
                },
                enabled = !isBusy,
                modifier = Modifier.testTag("btn_save_goat_listing")
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Saving...")
                } else {
                    Text("Save Listing")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isSubmittingLocal && !isSaving) onDismiss()
                },
                enabled = !isSubmittingLocal && !isSaving
            ) {
                Text("Cancel")
            }
        }
    )
}
