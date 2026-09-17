package com.example.ui.screens

import com.example.core.util.PriceUtils

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.core.util.FarmLocalCache
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.model.*
import com.example.ui.viewmodel.MarketplaceUiState
import com.example.util.ImageUploadHelper
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmAdminScreen(
    uiState: MarketplaceUiState,
    onAddGoat: (Goat) -> Unit,
    onEditGoat: (Goat) -> Unit = {},
    onDeleteGoat: (String) -> Unit = {},
    onConfirmBooking: (String) -> Unit,
    onRejectBooking: (String) -> Unit,
    onCompleteBooking: (String) -> Unit = {},
    onUpdateFarmProfile: (Farm) -> Unit = {},
    onUpdateFarmLogo: (String, String) -> Unit = { _, _ -> },
    onSyncRole: () -> Unit = {},
    onNavigateToLogin: () -> Unit = {},
    onNavigateToRegisterFarm: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    var goatFilter by remember { mutableStateOf("ALL") }
    var bookingFilter by remember { mutableStateOf("ALL") }

    // Dialog states
    var showAddGoatDialog by remember { mutableStateOf(false) }
    var goatToEdit by remember { mutableStateOf<Goat?>(null) }
    var goatToDelete by remember { mutableStateOf<Goat?>(null) }
    var bookingToReject by remember { mutableStateOf<Booking?>(null) }
    var showEditProfileDialog by remember { mutableStateOf(false) }
    var showPendingBlockedAlert by remember { mutableStateOf(false) }

    val currentUser = uiState.currentUser
    val isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN
    val isFarmAdmin = currentUser?.role == UserRole.FARM_ADMIN
    val isAuthorized = isSuperAdmin || isFarmAdmin

    if (!isAuthorized) {
        FarmAdminAccessDeniedView(
            currentRole = currentUser?.role?.name ?: "CUSTOMER",
            onNavigateBack = onNavigateBack ?: {},
            onNavigateToMarketplace = onNavigateBack ?: {},
            onSyncRole = onSyncRole
        )
        return
    }

    // Resolve base farm directly from real database relationships:
    // 1. For Super Admin, the farm designated as SEED_AMMAL_FARM_UUID or isAmmalOwnFarm
    // 2. For Farm Admin, strictly enforce farms.owner_id == authenticated user ID
    val resolvedBaseFarm = run {
        val user = uiState.currentUser
        val userFarmId = user?.farmId
        val userId = user?.id

        if (isSuperAdmin) {
            val ownFarm = uiState.farms.find { it.id == SEED_AMMAL_FARM_UUID || it.isAmmalOwnFarm }
            if (ownFarm != null) return@run ownFarm
            val cachedAmmal = FarmLocalCache.getCachedFarm(SEED_AMMAL_FARM_UUID)
                ?: FarmLocalCache.getAllCachedFarms().find { it.isAmmalOwnFarm }
            if (cachedAmmal != null) return@run cachedAmmal
        }

        // For Farm Admin, strictly enforce owner_id == authenticated user ID
        if (!userId.isNullOrBlank()) {
            if (!userFarmId.isNullOrBlank()) {
                val byFarmIdAndOwner = uiState.farms.find { it.id == userFarmId && it.ownerId == userId }
                if (byFarmIdAndOwner != null) return@run byFarmIdAndOwner
            }

            val byOwnerId = uiState.farms.find { it.ownerId == userId }
            if (byOwnerId != null) return@run byOwnerId

            // Offline / cached real farm fallback with owner_id verification:
            if (!userFarmId.isNullOrBlank()) {
                val cached = FarmLocalCache.getCachedFarm(userFarmId)?.takeIf { it.ownerId == userId }
                if (cached != null) return@run cached
            }
            val cachedByOwner = FarmLocalCache.getAllCachedFarms().find { it.ownerId == userId }
            if (cachedByOwner != null) return@run cachedByOwner
        }

        null
    }

    // Ensure logoUrl & bannerUrl are preserved from cache if missing
    val myFarm = resolvedBaseFarm?.let { farm ->
        val cachedLogo = FarmLocalCache.getFarmLogo(farm.id)
        val cachedBanner = FarmLocalCache.getFarmBanner(farm.id)
        var enriched = farm
        if (enriched.logoUrl.isNullOrBlank() && !cachedLogo.isNullOrBlank()) {
            enriched = enriched.copy(logoUrl = cachedLogo)
        }
        if (enriched.bannerUrl.isNullOrBlank() && !cachedBanner.isNullOrBlank()) {
            enriched = enriched.copy(bannerUrl = cachedBanner)
        }
        enriched
    }

    val isAmmalFarm = (myFarm?.isAmmalOwnFarm == true) || (isSuperAdmin && (myFarm?.id == SEED_AMMAL_FARM_UUID || myFarm?.isAmmalOwnFarm == true))

    val myFarmId = myFarm?.id ?: uiState.currentUser?.farmId ?: ""
    val farmName = myFarm?.name
    val farmVerificationStatus = if (isAmmalFarm) VerificationStatus.APPROVED else (myFarm?.verificationStatus ?: VerificationStatus.PENDING)
    val isFarmApproved = isAmmalFarm || farmVerificationStatus == VerificationStatus.APPROVED

    // Filter data strictly belonging to this farm
    val myGoats = remember(uiState.allAdminGoats, myFarmId, myFarm) {
        if (myFarmId.isBlank()) {
            emptyList()
        } else {
            uiState.allAdminGoats.filter { goat ->
                goat.farmId == myFarmId || (myFarm != null && goat.farmId == myFarm.id)
            }
        }
    }
    val myBookings = remember(uiState.allBookings, myFarmId, myFarm) {
        if (myFarmId.isBlank()) {
            emptyList()
        } else {
            uiState.allBookings.filter { it.farmId == myFarmId || (myFarm != null && it.farmId == myFarm.id) }
        }
    }
    val myNotifications = uiState.notifications.filter {
        it.targetRole == UserRole.FARM_ADMIN || (myFarmId.isNotBlank() && it.recipientUserId == myFarmId) || it.recipientUserId == uiState.currentUser?.id
    }

    // Calculated metrics
    val totalGoats = myGoats.size
    val availableGoats = myGoats.count { it.availabilityStatus == AvailabilityStatus.AVAILABLE && (it.approvalStatus == ApprovalStatus.APPROVED || isAmmalFarm) }
    val pendingListings = myGoats.count { it.approvalStatus == ApprovalStatus.PENDING_APPROVAL }
    val activeBookings = myBookings.count { it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.RESERVED }

    // Limit and Auto-Approval computations
    val goatListingLimit = myFarm?.goatListingLimit ?: (if (isAmmalFarm) 1000 else 10)
    val isLimitReached = !isAmmalFarm && !isSuperAdmin && myGoats.size >= goatListingLimit
    var showLimitReachedAlert by remember { mutableStateOf(false) }

    // Filtered Goats
    val filteredGoats = remember(myGoats, goatFilter, isAmmalFarm) {
        when (goatFilter) {
            "AVAILABLE" -> myGoats.filter { it.availabilityStatus == AvailabilityStatus.AVAILABLE && (it.approvalStatus == ApprovalStatus.APPROVED || isAmmalFarm) }
            "APPROVED" -> myGoats.filter { it.approvalStatus == ApprovalStatus.APPROVED || isAmmalFarm }
            "PENDING" -> myGoats.filter { it.approvalStatus == ApprovalStatus.PENDING_APPROVAL }
            "SOLD_RESERVED" -> myGoats.filter { it.availabilityStatus == AvailabilityStatus.RESERVED || it.availabilityStatus == AvailabilityStatus.SOLD || it.availabilityStatus == AvailabilityStatus.COMPLETED }
            else -> myGoats
        }
    }

    // Filtered Bookings
    val filteredFarmBookings = remember(myBookings, bookingFilter) {
        when (bookingFilter) {
            "PENDING" -> myBookings.filter { it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.RESERVED }
            "CONFIRMED" -> myBookings.filter { it.status == AvailabilityStatus.CONFIRMED }
            "COMPLETED" -> myBookings.filter { it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD }
            "CANCELLED" -> myBookings.filter { it.status == AvailabilityStatus.CANCELLED || it.status == AvailabilityStatus.REJECTED }
            else -> myBookings
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Column(modifier = Modifier.padding(end = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = myFarm?.name ?: "Farm Admin Hub",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isAmmalFarm) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF1B5E20)
                                ) {
                                    Text(
                                        "OWN FARM",
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = when (farmVerificationStatus) {
                                    VerificationStatus.APPROVED -> Color(0xFFE8F5E9)
                                    VerificationStatus.PENDING -> Color(0xFFFFF3E0)
                                    VerificationStatus.REJECTED -> Color(0xFFFFEBEE)
                                    VerificationStatus.SUSPENDED -> Color(0xFFFFEBEE)
                                }
                            ) {
                                Text(
                                    text = "Status: ${farmVerificationStatus.name}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (farmVerificationStatus) {
                                        VerificationStatus.APPROVED -> Color(0xFF2E7D32)
                                        VerificationStatus.PENDING -> Color(0xFFE65100)
                                        else -> Color(0xFFC62828)
                                    },
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = myFarm?.location ?: "Trichy, Tamil Nadu",
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSyncRole,
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Data",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            if (!isFarmApproved) {
                                showPendingBlockedAlert = true
                            } else if (isLimitReached) {
                                showLimitReachedAlert = true
                            } else {
                                showAddGoatDialog = true
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = if (isFarmApproved && !isLimitReached) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(
                            if (!isFarmApproved || isLimitReached) Icons.Default.Lock else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (!isFarmApproved) "Locked" else if (isLimitReached) "Limit ($totalGoats/$goatListingLimit)" else (if (isAmmalFarm) "Add Goat (Free)" else "Add Goat"),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        if (myFarm == null) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Storefront,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "My Farm could not be found.",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "No registered farm matching your account was found in the database. Please verify your farm registration or refresh your connection.",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onSyncRole,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reload")
                                }

                                OutlinedButton(
                                    onClick = onNavigateToRegisterFarm,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Register Farm")
                                }
                            }
                        }
                    }
                }
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Farm Verification Pending Alert Banner
            if (!isFarmApproved) {
                Surface(
                    color = Color(0xFFFFF3E0),
                    border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.HourglassTop,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Farm Partner Verification Pending",
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "Super Admin approval required. New goat publishing is locked until verification is completed.",
                                fontSize = 11.sp,
                                color = Color(0xFF5D4037)
                            )
                        }
                    }
                }
            }

            // Overview Metric Summary Grid
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricCard("Total", "$totalGoats", Icons.Default.Pets, Modifier.weight(1f))
                MetricCard("Available", "$availableGoats", Icons.Default.CheckCircle, Modifier.weight(1f))
                MetricCard("Pending", "$pendingListings", Icons.Default.HourglassEmpty, Modifier.weight(1f))
                MetricCard("Bookings", "${myBookings.size}", Icons.Default.ReceiptLong, Modifier.weight(1f))
            }

            // Scrollable Tab Row for Full Farm Admin Modules
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Goats ($totalGoats)") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Bookings (${myBookings.size})") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Notifications (${myNotifications.size})") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Farm Profile") }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text("Fee Receipts (${uiState.listingPayments.size})") }
                )
            }

            when (selectedTab) {
                0 -> {
                    // --- TAB 0: GOATS CATALOGUE MANAGEMENT ---
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Listing Quota & Auto-Approval Status Card (for Partner Farms)
                        if (!isAmmalFarm) {
                            val remainingSlots = (goatListingLimit - myGoats.size).coerceAtLeast(0)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isLimitReached) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                border = BorderStroke(1.dp, if (isLimitReached) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                if (isLimitReached) Icons.Default.Block else Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = if (isLimitReached) "Listing Limit Reached" else "Listing Quota",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = "${myGoats.size} / $goatListingLimit Limit",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (myGoats.size.toFloat() / goatListingLimit.toFloat()).coerceIn(0f, 1f) },
                                        color = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isLimitReached) {
                                            "Listing limit reached. Contact Super Admin to increase your listing limit."
                                        } else {
                                            "Current limit: $goatListingLimit goats • ${myGoats.size} goats listed • $remainingSlots slot${if (remainingSlots == 1) "" else "s"} remaining"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = if (isLimitReached) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (isLimitReached) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/916380898358?text=Hello%20Super%20Admin,%20I%20want%20to%20increase%20my%20goat%20listing%20limit%20for%20my%20farm."))
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {}
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("WhatsApp: +91 63808 98358", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = {
                                                    try {
                                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+916380898358"))
                                                        context.startActivity(intent)
                                                    } catch (_: Exception) {}
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(32.dp)
                                            ) {
                                                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Call: +91 63808 98358", fontSize = 11.sp)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Payment for increasing the limit is handled directly with us outside the app.",
                                            fontSize = 10.5.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        // Filter row
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tabs = listOf(
                                "ALL" to "All",
                                "AVAILABLE" to "Available",
                                "APPROVED" to "Approved",
                                "PENDING" to "Pending Review",
                                "SOLD_RESERVED" to "Sold/Reserved"
                            )
                            items(tabs) { (key, label) ->
                                val count = when (key) {
                                    "ALL" -> myGoats.size
                                    "AVAILABLE" -> myGoats.count { it.availabilityStatus == AvailabilityStatus.AVAILABLE && (it.approvalStatus == ApprovalStatus.APPROVED || isAmmalFarm) }
                                    "APPROVED" -> myGoats.count { it.approvalStatus == ApprovalStatus.APPROVED || isAmmalFarm }
                                    "PENDING" -> myGoats.count { it.approvalStatus == ApprovalStatus.PENDING_APPROVAL }
                                    "SOLD_RESERVED" -> myGoats.count { it.availabilityStatus == AvailabilityStatus.RESERVED || it.availabilityStatus == AvailabilityStatus.SOLD || it.availabilityStatus == AvailabilityStatus.COMPLETED }
                                    else -> 0
                                }
                                FilterChip(
                                    selected = goatFilter == key,
                                    onClick = { goatFilter = key },
                                    label = { Text("$label ($count)", fontSize = 11.sp) }
                                )
                            }
                        }

                        // Goat List
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (filteredGoats.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.Default.Pets,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "No goats match this filter.",
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(filteredGoats, key = { it.id }) { goat ->
                                    FarmGoatCard(
                                        goat = goat,
                                        onEditClick = { goatToEdit = goat },
                                        onDeleteClick = { goatToDelete = goat }
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // --- TAB 1: BOOKINGS MANAGEMENT ---
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Filter row
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val tabs = listOf(
                                "ALL" to "All",
                                "PENDING" to "Pending Holds",
                                "CONFIRMED" to "Confirmed",
                                "COMPLETED" to "Sold / Completed",
                                "CANCELLED" to "Cancelled/Rejected"
                            )
                            items(tabs) { (key, label) ->
                                val count = when (key) {
                                    "ALL" -> myBookings.size
                                    "PENDING" -> myBookings.count { it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.RESERVED }
                                    "CONFIRMED" -> myBookings.count { it.status == AvailabilityStatus.CONFIRMED }
                                    "COMPLETED" -> myBookings.count { it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD }
                                    "CANCELLED" -> myBookings.count { it.status == AvailabilityStatus.CANCELLED || it.status == AvailabilityStatus.REJECTED }
                                    else -> 0
                                }
                                FilterChip(
                                    selected = bookingFilter == key,
                                    onClick = { bookingFilter = key },
                                    label = { Text("$label ($count)", fontSize = 11.sp) }
                                )
                            }
                        }

                        // Booking List
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (filteredFarmBookings.isEmpty()) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 40.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(
                                                Icons.Default.ReceiptLong,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                "No customer bookings in this status.",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 13.sp
                                            )
                                        }
                                    }
                                }
                            } else {
                                items(filteredFarmBookings, key = { it.id }) { booking ->
                                    FarmBookingCard(
                                        booking = booking,
                                        onCallCustomer = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.customerPhone}"))
                                            context.startActivity(intent)
                                        },
                                        onConfirmBooking = { onConfirmBooking(booking.id) },
                                        onRejectBooking = { bookingToReject = booking },
                                        onCompleteBooking = { onCompleteBooking(booking.id) }
                                    )
                                }
                            }
                        }
                    }
                }

                2 -> {
                    // --- TAB 2: NOTIFICATIONS ---
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (myNotifications.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.NotificationsNone,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("No farm notifications yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else {
                            items(myNotifications, key = { it.id }) { notif ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (notif.isRead) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                    ),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(notif.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text("Recent", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(notif.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // --- TAB 3: FARM PROFILE & SETTINGS ---
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (myFarm != null) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column {
                                        // Banner
                                        if (myFarm.bannerUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = myFarm.bannerUrl,
                                                contentDescription = "Farm Banner",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(130.dp)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(100.dp)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Default.Agriculture, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }

                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                                    shadowElevation = 2.dp,
                                                    modifier = Modifier
                                                        .size(54.dp)
                                                        .clip(CircleShape)
                                                        .clickable { showEditProfileDialog = true }
                                                ) {
                                                    if (!myFarm.logoUrl.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = myFarm.logoUrl,
                                                            contentDescription = myFarm.name,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Agriculture,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(28.dp)
                                                            )
                                                        }
                                                    }
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(myFarm.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                                    Text("Owner: ${myFarm.ownerName}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                IconButton(onClick = { showEditProfileDialog = true }) {
                                                    Icon(Icons.Default.Edit, contentDescription = "Edit Profile", tint = MaterialTheme.colorScheme.primary)
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(myFarm.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)

                                            Spacer(modifier = Modifier.height(12.dp))
                                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                            Spacer(modifier = Modifier.height(12.dp))

                                            ProfileInfoRow(Icons.Default.LocationOn, "Location", "${myFarm.location}, ${myFarm.state}")
                                            ProfileInfoRow(Icons.Default.Phone, "Contact Phone", myFarm.contactNumber)
                                            ProfileInfoRow(Icons.Default.Email, "Email", myFarm.email)
                                            ProfileInfoRow(Icons.Default.Verified, "Verification", farmVerificationStatus.name)
                                            val farmReviews = uiState.allReviews.filter { it.farmId == myFarmId || (myFarm != null && it.farmId == myFarm.id) }
                                            val liveRating = if (farmReviews.isNotEmpty()) {
                                                val sum = farmReviews.sumOf { it.rating.toDouble() }
                                                Math.round((sum / farmReviews.size) * 10.0) / 10.0
                                            } else {
                                                myFarm.rating
                                            }
                                            val liveReviewCount = if (farmReviews.isNotEmpty()) farmReviews.size else myFarm.totalReviews
                                            val ratingText = if (liveReviewCount > 0 && liveRating > 0.0) {
                                                "${String.format("%.1f", liveRating)} ★ ($liveReviewCount customer ${if (liveReviewCount == 1) "review" else "reviews"})"
                                            } else {
                                                "No customer reviewed"
                                            }
                                            ProfileInfoRow(Icons.Default.Star, "Rating", ratingText)

                                            Spacer(modifier = Modifier.height(14.dp))
                                            Button(
                                                onClick = { showEditProfileDialog = true },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("Update Farm Profile")
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            item {
                                Text("No farm profile found for this user.", color = Color.Gray, fontSize = 13.sp)
                            }
                        }
                    }
                }

                4 -> {
                    // --- TAB 4: LISTING FEE RECEIPTS & TRANSACTIONS ---
                    FarmListingPaymentsTab(
                        payments = uiState.listingPayments,
                        isAmmalFarm = isAmmalFarm,
                        myGoats = myGoats
                    )
                }
            }
        }
    }

    // --- PENDING ALERT MODAL ---
    if (showPendingBlockedAlert) {
        AlertDialog(
            onDismissRequest = { showPendingBlockedAlert = false },
            icon = { Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = Color(0xFFED6C02)) },
            title = { Text("Farm Verification Required") },
            text = {
                Text("Your farm application is currently in PENDING review by Super Admin. You cannot publish goats to the public marketplace until your farm partner credentials and breeding facility are approved.")
            },
            confirmButton = {
                Button(onClick = { showPendingBlockedAlert = false }) {
                    Text("Understood")
                }
            }
        )
    }

    // --- LIMIT REACHED ALERT MODAL ---
    if (showLimitReachedAlert) {
        AlertDialog(
            onDismissRequest = { showLimitReachedAlert = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            icon = { Icon(Icons.Default.Block, contentDescription = null, tint = Color(0xFFC62828)) },
            title = { Text("Listing Limit Reached", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Listing limit reached. Contact Super Admin to increase your listing limit.",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Your farm currently has ${myGoats.size} / $goatListingLimit goats listed.\n\nShow contact options:\n• WhatsApp: +91 63808 98358\n• Call: +91 63808 98358\n\nPayment for increasing the limit is handled directly with us outside the app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/916380898358?text=Hello%20Super%20Admin,%20I%20want%20to%20increase%20my%20goat%20listing%20limit%20for%20my%20farm."))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("WhatsApp")
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:+916380898358"))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Call")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLimitReachedAlert = false }) {
                    Text("Close")
                }
            }
        )
    }

    // --- ADD GOAT DIALOG (WITH MULTI-PHOTO UPLOAD & SUPABASE STORAGE INTEGRATION) ---
    if (showAddGoatDialog) {
        AddEditGoatDialog(
            title = if (isAmmalFarm) "Add Goat (Ammal Farm)" else "Add New Goat Listing",
            farmId = myFarmId,
            farmName = myFarm?.name ?: "Farm Partner",
            farmLocation = myFarm?.location ?: "Tamil Nadu",
            existingGoat = null,
            isAmmalFarm = isAmmalFarm,
            listingLimit = goatListingLimit,
            onDismiss = { showAddGoatDialog = false },
            onSave = { goat ->
                onAddGoat(goat)
                goatFilter = "ALL"
                selectedTab = 0
                showAddGoatDialog = false
            }
        )
    }

    // --- EDIT GOAT DIALOG ---
    if (goatToEdit != null) {
        AddEditGoatDialog(
            title = "Edit Goat Listing",
            farmId = myFarmId,
            farmName = myFarm?.name ?: "Farm Partner",
            farmLocation = myFarm?.location ?: "Tamil Nadu",
            existingGoat = goatToEdit,
            isAmmalFarm = isAmmalFarm,
            listingLimit = goatListingLimit,
            onDismiss = { goatToEdit = null },
            onSave = { updatedGoat ->
                onEditGoat(updatedGoat)
                goatToEdit = null
            }
        )
    }

    // --- DELETE GOAT CONFIRMATION DIALOG ---
    if (goatToDelete != null) {
        val goat = goatToDelete!!
        AlertDialog(
            onDismissRequest = { goatToDelete = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove Goat Listing") },
            text = {
                Text("Are you sure you want to remove '${goat.name}' (${goat.tagNumber}) from your farm catalogue? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteGoat(goat.id)
                        goatToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove Listing")
                }
            },
            dismissButton = {
                TextButton(onClick = { goatToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- REJECT BOOKING MODAL DIALOG ---
    if (bookingToReject != null) {
        val b = bookingToReject!!
        var rejectReason by remember { mutableStateOf("Goat sold offline or in local auction") }
        val reasonOptions = listOf(
            "Goat sold offline or in local auction",
            "Health quarantine / under veterinary observation",
            "Customer pickup location unreachable",
            "Pricing or age listing discrepancy"
        )

        AlertDialog(
            onDismissRequest = { bookingToReject = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Reject Booking Request") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select reason to notify ${b.customerName}:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    reasonOptions.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { rejectReason = option }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = rejectReason == option,
                                onClick = { rejectReason = option }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(option, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRejectBooking(b.id)
                        bookingToReject = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirm Rejection")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToReject = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- EDIT FARM PROFILE DIALOG ---
    if (showEditProfileDialog && myFarm != null) {
        var name by remember { mutableStateOf(myFarm.name) }
        var phone by remember { mutableStateOf(myFarm.contactNumber) }
        var email by remember { mutableStateOf(myFarm.email) }
        var location by remember { mutableStateOf(myFarm.location) }
        var state by remember { mutableStateOf(myFarm.state) }
        var description by remember { mutableStateOf(myFarm.description) }
        val cachedLogo = FarmLocalCache.getFarmLogo(myFarm.id) ?: ""
        val cachedBanner = FarmLocalCache.getFarmBanner(myFarm.id) ?: ""
        var logoUrl by remember { mutableStateOf(if (myFarm.logoUrl.isNotBlank()) myFarm.logoUrl else cachedLogo) }
        var bannerUrl by remember { mutableStateOf(if (myFarm.bannerUrl.isNotBlank()) myFarm.bannerUrl else cachedBanner) }

        var isUploadingLogo by remember { mutableStateOf(false) }
        var isUploadingBanner by remember { mutableStateOf(false) }

        val logoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri != null) {
                isUploadingLogo = true
                coroutineScope.launch {
                    val bytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (bytes != null) {
                        val res = ImageUploadHelper.uploadFarmImage(context, bytes, myFarm.id, "logo")
                        res.onSuccess { resolvedUrl ->
                            logoUrl = resolvedUrl
                            FarmLocalCache.saveFarmLogo(myFarm.id, resolvedUrl)
                            onUpdateFarmLogo(myFarm.id, resolvedUrl)
                            Toast.makeText(context, "Logo updated successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            val local = ImageUploadHelper.saveImageLocally(context, bytes, "farm_logo")
                            logoUrl = local
                            FarmLocalCache.saveFarmLogo(myFarm.id, local)
                            onUpdateFarmLogo(myFarm.id, local)
                            Toast.makeText(context, "Logo saved locally!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not process selected image", Toast.LENGTH_SHORT).show()
                    }
                    isUploadingLogo = false
                }
            }
        }

        val logoFallbackPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                isUploadingLogo = true
                coroutineScope.launch {
                    val bytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (bytes != null) {
                        val res = ImageUploadHelper.uploadFarmImage(context, bytes, myFarm.id, "logo")
                        res.onSuccess { resolvedUrl ->
                            logoUrl = resolvedUrl
                            FarmLocalCache.saveFarmLogo(myFarm.id, resolvedUrl)
                            onUpdateFarmLogo(myFarm.id, resolvedUrl)
                            Toast.makeText(context, "Logo updated successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            val local = ImageUploadHelper.saveImageLocally(context, bytes, "farm_logo")
                            logoUrl = local
                            FarmLocalCache.saveFarmLogo(myFarm.id, local)
                            onUpdateFarmLogo(myFarm.id, local)
                            Toast.makeText(context, "Logo saved locally!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not process selected image", Toast.LENGTH_SHORT).show()
                    }
                    isUploadingLogo = false
                }
            }
        }

        val bannerPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri: Uri? ->
            if (uri != null) {
                isUploadingBanner = true
                coroutineScope.launch {
                    val bytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (bytes != null) {
                        val res = ImageUploadHelper.uploadFarmImage(context, bytes, myFarm.id, "banner")
                        res.onSuccess { resolvedUrl ->
                            bannerUrl = resolvedUrl
                            FarmLocalCache.saveFarmBanner(myFarm.id, resolvedUrl)
                            Toast.makeText(context, "Banner updated successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            val local = ImageUploadHelper.saveImageLocally(context, bytes, "farm_banner")
                            bannerUrl = local
                            FarmLocalCache.saveFarmBanner(myFarm.id, local)
                            Toast.makeText(context, "Banner saved locally!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not process selected image", Toast.LENGTH_SHORT).show()
                    }
                    isUploadingBanner = false
                }
            }
        }

        val bannerFallbackPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            if (uri != null) {
                isUploadingBanner = true
                coroutineScope.launch {
                    val bytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (bytes != null) {
                        val res = ImageUploadHelper.uploadFarmImage(context, bytes, myFarm.id, "banner")
                        res.onSuccess { resolvedUrl ->
                            bannerUrl = resolvedUrl
                            FarmLocalCache.saveFarmBanner(myFarm.id, resolvedUrl)
                            Toast.makeText(context, "Banner updated successfully!", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            val local = ImageUploadHelper.saveImageLocally(context, bytes, "farm_banner")
                            bannerUrl = local
                            FarmLocalCache.saveFarmBanner(myFarm.id, local)
                            Toast.makeText(context, "Banner saved locally!", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Could not process selected image", Toast.LENGTH_SHORT).show()
                    }
                    isUploadingBanner = false
                }
            }
        }


        Dialog(
            onDismissRequest = { showEditProfileDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.88f)
                    .padding(vertical = 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    // Pinned Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Update Farm Profile", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        IconButton(
                            onClick = { showEditProfileDialog = false },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Scrollable Form Fields
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        item {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("Farm Name") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("Contact Phone") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("Contact Email") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = location,
                                    onValueChange = { location = it },
                                    label = { Text("City / Town") },
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = state,
                                    onValueChange = { state = it },
                                    label = { Text("State") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        item {
                            OutlinedTextField(
                                value = description,
                                onValueChange = { description = it },
                                label = { Text("Farm Bio & Breeding Specialization") },
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        item {
                            Column {
                                Text("Farm Logo & Banner", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.height(6.dp))
                                
                                // Logo Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = logoUrl,
                                            contentDescription = "Logo Preview",
                                            modifier = Modifier.size(40.dp).clip(CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                logoPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            } catch (_: Exception) {
                                                logoFallbackPickerLauncher.launch("image/*")
                                            }
                                        },
                                        enabled = !isUploadingLogo,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isUploadingLogo) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pick Logo Image", fontSize = 12.sp)
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(6.dp))

                                // Banner Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (!bannerUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = bannerUrl,
                                            contentDescription = "Banner Preview",
                                            modifier = Modifier.size(width = 50.dp, height = 30.dp).clip(RoundedCornerShape(4.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                bannerPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                            } catch (_: Exception) {
                                                bannerFallbackPickerLauncher.launch("image/*")
                                            }
                                        },
                                        enabled = !isUploadingBanner,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        if (isUploadingBanner) {
                                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Pick Banner Image", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Pinned Bottom Actions
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showEditProfileDialog = false }) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val updated = myFarm.copy(
                                    name = name.ifBlank { myFarm.name },
                                    contactNumber = phone.ifBlank { myFarm.contactNumber },
                                    email = email.ifBlank { myFarm.email },
                                    location = location.ifBlank { myFarm.location },
                                    state = state.ifBlank { myFarm.state },
                                    description = description.ifBlank { myFarm.description },
                                    logoUrl = logoUrl,
                                    bannerUrl = bannerUrl
                                )
                                if (!logoUrl.isNullOrBlank()) {
                                    FarmLocalCache.saveFarmLogo(myFarm.id, logoUrl)
                                    onUpdateFarmLogo(myFarm.id, logoUrl)
                                }
                                if (!bannerUrl.isNullOrBlank()) {
                                    FarmLocalCache.saveFarmBanner(myFarm.id, bannerUrl)
                                }
                                FarmLocalCache.saveFarmProfile(updated)
                                onUpdateFarmProfile(updated)
                                showEditProfileDialog = false
                            }
                        ) {
                            Text("Save Changes")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Enhanced Farm Goat Card displaying lifecycle states and edit/delete controls.
 */
@Composable
private fun FarmGoatCard(
    goat: Goat,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // Photo Thumbnail
                if (goat.photos.isNotEmpty()) {
                    AsyncImage(
                        model = goat.photos.first(),
                        contentDescription = goat.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Details
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(goat.name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Column(horizontalAlignment = Alignment.End) {
                            if (goat.hasDiscount) {
                                Text(
                                    goat.formattedFinalPrice,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
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
                                Text(
                                    goat.formattedPrice,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Text(
                        "${goat.breed} • ${goat.gender} • ${goat.ageMonths} mos • ${goat.weightKg} kg",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tag: ${goat.tagNumber} • Purpose: ${goat.purpose.name}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // State progression badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = when (goat.approvalStatus) {
                            ApprovalStatus.APPROVED -> Color(0xFF13663C)
                            ApprovalStatus.PENDING_APPROVAL -> Color(0xFFD48B06)
                            ApprovalStatus.REJECTED -> Color(0xFFC62828)
                            else -> Color.Gray
                        }
                    ) {
                        Text(
                            text = goat.approvalStatus.name.replace("_", " "),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (goat.availabilityStatus != AvailabilityStatus.AVAILABLE) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                goat.availabilityStatus.name,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Edit & Delete Icon Actions
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = onDeleteClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (goat.approvalStatus == ApprovalStatus.PENDING_APPROVAL) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = Color(0xFFE3F2FD),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⏳ Under Super Admin Review (Farm Admins cannot self-approve).",
                        fontSize = 11.sp,
                        color = Color(0xFF1565C0),
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            val isAmmalGoat = goat.farmId == SEED_AMMAL_FARM_UUID || goat.farmName.contains("Ammal", ignoreCase = true)
            Spacer(modifier = Modifier.height(6.dp))
            Surface(
                color = if (isAmmalGoat) Color(0xFFE8F5E9) else Color(0xFFE3F2FD),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Verified,
                        contentDescription = null,
                        tint = if (isAmmalGoat) Color(0xFF2E7D32) else Color(0xFF1565C0),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isAmmalGoat) "Ammal Farm (Free Listing)" else "Partner Farm Listing (Auto-Approved within Quota)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isAmmalGoat) Color(0xFF2E7D32) else Color(0xFF1565C0)
                    )
                }
            }
        }
    }
}

/**
 * Farm Booking Card with direct call action, status badges, and confirm/reject/completed buttons.
 */
@Composable
private fun FarmBookingCard(
    booking: Booking,
    onCallCustomer: () -> Unit,
    onConfirmBooking: () -> Unit,
    onRejectBooking: () -> Unit,
    onCompleteBooking: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
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
                    Text(booking.goatName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Customer: ${booking.customerName}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Text("Contact: ${booking.customerPhone}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "₹${booking.amount.toInt()}",
                    fontWeight = FontWeight.Black,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (booking.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Customer Note: ${booking.notes}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status Badge & Action Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (booking.status) {
                        AvailabilityStatus.CONFIRMED -> Color(0xFFE8F5E9)
                        AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED -> Color(0xFFFFF3E0)
                        AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFFE3F2FD)
                        else -> Color(0xFFFFEBEE)
                    }
                ) {
                    Text(
                        text = booking.status.name.replace("_", " "),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (booking.status) {
                            AvailabilityStatus.CONFIRMED -> Color(0xFF2E7D32)
                            AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED -> Color(0xFFE65100)
                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF1565C0)
                            else -> Color(0xFFC62828)
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Call Customer Button
                OutlinedButton(
                    onClick = onCallCustomer,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Phone, contentDescription = "Call", modifier = Modifier.size(12.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Call Customer", fontSize = 10.sp)
                }
            }

            // Action Buttons based on booking status
            if (booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRejectBooking,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFC62828)),
                        border = BorderStroke(1.dp, Color(0xFFC62828)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFFC62828))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject Hold", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onConfirmBooking,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Confirm Booking", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (booking.status == AvailabilityStatus.CONFIRMED) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = onCompleteBooking,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Mark as Sold & Completed", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Reusable Add/Edit Goat Dialog supporting all mandatory creation fields,
 * multi-photo selection, compression, and Supabase Storage upload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditGoatDialog(
    title: String,
    farmId: String,
    farmName: String,
    farmLocation: String,
    existingGoat: Goat?,
    isAmmalFarm: Boolean = false,
    listingLimit: Int = 10,
    onDismiss: () -> Unit,
    onSave: (Goat) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val goatId = remember { existingGoat?.id ?: UUID.randomUUID().toString() }

    var name by remember { mutableStateOf(existingGoat?.name ?: "") }
    var tagNumber by remember { mutableStateOf(existingGoat?.tagNumber ?: "AF-${UUID.randomUUID().toString().take(4).uppercase()}") }
    var breed by remember { mutableStateOf(existingGoat?.breed ?: "Boer") }
    var gender by remember { mutableStateOf(existingGoat?.gender ?: GoatGender.MALE) }
    var age by remember { mutableStateOf(existingGoat?.ageMonths?.toString() ?: "14") }
    var weight by remember { mutableStateOf(existingGoat?.weightKg?.toString() ?: "42.0") }
    var price by remember {
        mutableStateOf(
            if (existingGoat != null) {
                if (existingGoat.price % 1.0 == 0.0) existingGoat.price.toInt().toString()
                else existingGoat.price.toString()
            } else ""
        )
    }
    var discountPercentage by remember {
        mutableStateOf(
            if (existingGoat != null && existingGoat.discountPercentage > 0.0) {
                if (existingGoat.discountPercentage % 1.0 == 0.0) existingGoat.discountPercentage.toInt().toString()
                else existingGoat.discountPercentage.toString()
            } else "0"
        )
    }

    val priceValidation = PriceUtils.validatePrice(price)
    val discountValidation = PriceUtils.validateDiscount(discountPercentage)
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

    var description by remember { mutableStateOf(existingGoat?.description ?: "Healthy breeding pedigree goat in excellent condition.") }

    var photos by remember {
        mutableStateOf<List<String>>(
            existingGoat?.photos ?: emptyList()
        )
    }

    var isUploadingImage by remember { mutableStateOf(false) }

    // Multi-source Image Picker Launchers
    val pickMultipleVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isUploadingImage = true
            coroutineScope.launch {
                var uploadedCount = 0
                val initialPhotoCount = photos.size
                for ((idx, uri) in uris.withIndex()) {
                    val compressedBytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (compressedBytes != null) {
                        val result = ImageUploadHelper.uploadGoatImage(
                            context = context,
                            bytes = compressedBytes,
                            goatId = goatId,
                            photoIndex = initialPhotoCount + idx + 1,
                            farmId = farmId
                        )
                        result.onSuccess { resolvedUrl ->
                            photos = photos + resolvedUrl
                            uploadedCount++
                        }.onFailure { err ->
                            val localUrl = ImageUploadHelper.saveImageLocally(context, compressedBytes, "goat_${goatId}")
                            photos = photos + localUrl
                            uploadedCount++
                            Log.e("FarmAdminScreen", "Upload failed, saved locally: ${err.message}", err)
                        }
                    }
                }
                isUploadingImage = false
                if (uploadedCount > 0) {
                    Toast.makeText(context, "$uploadedCount photo(s) added to listing!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not process selected image files.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val pickSingleVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            isUploadingImage = true
            coroutineScope.launch {
                val compressedBytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                if (compressedBytes != null) {
                    val result = ImageUploadHelper.uploadGoatImage(
                        context = context,
                        bytes = compressedBytes,
                        goatId = goatId,
                        photoIndex = photos.size + 1,
                        farmId = farmId
                    )
                    result.onSuccess { resolvedUrl ->
                        photos = photos + resolvedUrl
                        Toast.makeText(context, "Photo added to listing!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        val localUrl = ImageUploadHelper.saveImageLocally(context, compressedBytes, "goat_${goatId}")
                        photos = photos + localUrl
                        Toast.makeText(context, "Photo saved to listing!", Toast.LENGTH_SHORT).show()
                        Log.e("FarmAdminScreen", "Upload failed, saved locally: ${err.message}", err)
                    }
                } else {
                    Toast.makeText(context, "Could not process image.", Toast.LENGTH_SHORT).show()
                }
                isUploadingImage = false
            }
        }
    }

    val getMultipleContentsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            isUploadingImage = true
            coroutineScope.launch {
                var uploadedCount = 0
                val initialPhotoCount = photos.size
                for ((idx, uri) in uris.withIndex()) {
                    val compressedBytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (compressedBytes != null) {
                        val result = ImageUploadHelper.uploadGoatImage(
                            context = context,
                            bytes = compressedBytes,
                            goatId = goatId,
                            photoIndex = initialPhotoCount + idx + 1,
                            farmId = farmId
                        )
                        result.onSuccess { resolvedUrl ->
                            photos = photos + resolvedUrl
                            uploadedCount++
                        }.onFailure { err ->
                            val localUrl = ImageUploadHelper.saveImageLocally(context, compressedBytes, "goat_${goatId}")
                            photos = photos + localUrl
                            uploadedCount++
                            Log.e("FarmAdminScreen", "Upload failed, saved locally: ${err.message}", err)
                        }
                    }
                }
                isUploadingImage = false
                if (uploadedCount > 0) {
                    Toast.makeText(context, "$uploadedCount photo(s) added to listing!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not process selected image files.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val getContentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isUploadingImage = true
            coroutineScope.launch {
                val compressedBytes = ImageUploadHelper.compressAndResizeImage(context, uri)
                if (compressedBytes != null) {
                    val result = ImageUploadHelper.uploadGoatImage(
                        context = context,
                        bytes = compressedBytes,
                        goatId = goatId,
                        photoIndex = photos.size + 1,
                        farmId = farmId
                    )
                    result.onSuccess { resolvedUrl ->
                        photos = photos + resolvedUrl
                        Toast.makeText(context, "Photo added to listing!", Toast.LENGTH_SHORT).show()
                    }.onFailure { err ->
                        val localUrl = ImageUploadHelper.saveImageLocally(context, compressedBytes, "goat_${goatId}")
                        photos = photos + localUrl
                        Toast.makeText(context, "Photo saved to listing!", Toast.LENGTH_SHORT).show()
                        Log.e("FarmAdminScreen", "Upload failed, saved locally: ${err.message}", err)
                    }
                }
                isUploadingImage = false
            }
        }
    }

    val takeCameraPhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            isUploadingImage = true
            coroutineScope.launch {
                val compressedBytes = ImageUploadHelper.compressAndResizeBitmap(bitmap)
                val result = ImageUploadHelper.uploadGoatImage(
                    context = context,
                    bytes = compressedBytes,
                    goatId = goatId,
                    customFileName = "camera_photo_${photos.size + 1}",
                    farmId = farmId
                )
                result.onSuccess { resolvedUrl ->
                    photos = photos + resolvedUrl
                    Toast.makeText(context, "Camera photo added!", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    val localUrl = ImageUploadHelper.saveImageLocally(context, compressedBytes, "goat_${goatId}")
                    photos = photos + localUrl
                    Toast.makeText(context, "Camera photo saved!", Toast.LENGTH_SHORT).show()
                    Log.e("FarmAdminScreen", "Upload failed, saved locally: ${err.message}", err)
                }
                isUploadingImage = false
            }
        }
    }

    val launchGalleryPicker = {
        try {
            pickMultipleVisualMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {
            try {
                getMultipleContentsLauncher.launch("image/*")
            } catch (_: Exception) {
                try {
                    pickSingleVisualMediaLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    getContentLauncher.launch("image/*")
                }
            }
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
                .padding(vertical = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Pinned Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                // Name
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Goat Name") },
                        placeholder = { Text("e.g. Sultan Champion Stud") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Breed Selector
                item {
                    Column {
                        Text("Breed Selection", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        val commonBreeds = listOf("Boer", "Tellicherry (Malabari)", "Jamunapari", "Sirohi", "Salem Black", "Barbari", "Beetal", "Osmanabadi", "Kanni Aadu")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(commonBreeds) { b ->
                                FilterChip(
                                    selected = breed == b,
                                    onClick = { breed = b },
                                    label = { Text(b, fontSize = 11.sp) }
                                )
                            }
                        }
                        OutlinedTextField(
                            value = breed,
                            onValueChange = { breed = it },
                            label = { Text("Or Custom Breed") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Gender
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Gender", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                FilterChip(
                                    selected = gender == GoatGender.MALE,
                                    onClick = { gender = GoatGender.MALE },
                                    label = { Text("Male (Buck)") }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                FilterChip(
                                    selected = gender == GoatGender.FEMALE,
                                    onClick = { gender = GoatGender.FEMALE },
                                    label = { Text("Female (Doe)") }
                                )
                            }
                        }
                    }
                }

                // Age, Weight, Price
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = age,
                            onValueChange = { age = it },
                            label = { Text("Age (Mos)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = weight,
                            onValueChange = { weight = it },
                            label = { Text("Weight (Kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Price, Discount % and Live Final Price Preview
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Pricing & Discount",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Marked / Original Price Input
                            OutlinedTextField(
                                value = price,
                                onValueChange = { price = it },
                                label = { Text("Price (₹) *") },
                                placeholder = { Text("e.g. 28000") },
                                isError = price.isNotBlank() && !isPriceValid,
                                supportingText = if (price.isNotBlank() && priceValidation is PriceUtils.PriceValidationResult.Error) {
                                    { Text(priceValidation.message, color = MaterialTheme.colorScheme.error) }
                                } else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1.2f)
                            )

                            // Discount Percentage Input (0 to 100)
                            OutlinedTextField(
                                value = discountPercentage,
                                onValueChange = { discountPercentage = it },
                                label = { Text("Discount (%)") },
                                placeholder = { Text("0 - 100") },
                                isError = discountPercentage.isNotBlank() && !isDiscountValid,
                                supportingText = if (discountPercentage.isNotBlank() && discountValidation is PriceUtils.DiscountValidationResult.Error) {
                                    { Text(discountValidation.message, color = MaterialTheme.colorScheme.error) }
                                } else null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Immediate Live Price Preview Card with required 4 metrics
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isPriceValid && isDiscountValid) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                   else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = androidx.compose.foundation.BorderStroke(
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
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "Price",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = PriceUtils.formatCurrency(validPrice),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                text = "Discount",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = PriceUtils.formatDiscountPercent(validDiscount),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (validDiscount > 0.0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = "Discount Amount",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = PriceUtils.formatCurrency(calculatedSavings ?: 0.0),
                                                fontSize = 15.sp,
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
                                        Text(
                                            text = "Final Price",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = PriceUtils.formatCurrency(calculatedFinalPrice ?: validPrice),
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Enter valid price and discount to view price breakdown",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Description
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description & Pedigree Notes") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Photos Section
                item {
                    Column {
                        Text("Goat Photos (${photos.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))

                        // Selected photos row
                        if (photos.isNotEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(photos) { photoUrl ->
                                    Box(
                                        modifier = Modifier
                                            .size(70.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    ) {
                                        AsyncImage(
                                            model = photoUrl,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        IconButton(
                                            onClick = { photos = photos.filter { it != photoUrl } },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(20.dp)
                                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Upload buttons: Gallery and Camera
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { launchGalleryPicker() },
                                enabled = !isUploadingImage,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isUploadingImage) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Uploading...", fontSize = 11.sp)
                                } else {
                                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Gallery", fontSize = 11.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    try {
                                        takeCameraPhotoLauncher.launch(null)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Camera not available: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = !isUploadingImage,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Camera", fontSize = 11.sp)
                            }
                        }
                    }
                }

                // Platform Lifecycle Notice
                item {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isAmmalFarm) {
                                    "✓ Ammal Farm Official Listing: Automatically approved directly to the live marketplace."
                                } else {
                                    "✓ Partner Farm Listing: Submitted for Super Admin approval. (Default listing limit: 10 goats)."
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // Pinned Bottom Action Bar
            Spacer(modifier = Modifier.height(12.dp))
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
                    enabled = isPriceValid && isDiscountValid,
                    onClick = {
                        val finalPriceVal = (priceValidation as? PriceUtils.PriceValidationResult.Valid)?.price ?: return@Button
                        val finalDiscountVal = (discountValidation as? PriceUtils.DiscountValidationResult.Valid)?.discount ?: return@Button
                        val goat = existingGoat?.copy(
                            name = name.ifBlank { "$breed Goat" },
                            tagNumber = tagNumber.ifBlank { existingGoat.tagNumber },
                            breed = breed,
                            gender = gender,
                            ageMonths = age.toIntOrNull() ?: existingGoat.ageMonths,
                            weightKg = weight.toDoubleOrNull() ?: existingGoat.weightKg,
                            purpose = existingGoat.purpose,
                            description = description,
                            price = finalPriceVal,
                            discountPercentage = finalDiscountVal,
                            photos = photos,
                            farmId = existingGoat.farmId,
                            farmName = existingGoat.farmName,
                            farmLocation = existingGoat.farmLocation
                        ) ?: Goat(
                            id = goatId,
                            name = name.ifBlank { "$breed Goat" },
                            tagNumber = tagNumber,
                            breed = breed,
                            gender = gender,
                            ageMonths = age.toIntOrNull() ?: 12,
                            weightKg = weight.toDoubleOrNull() ?: 35.0,
                            purpose = GoatPurpose.BREEDING,
                            description = description,
                            price = finalPriceVal,
                            discountPercentage = finalDiscountVal,
                            photos = photos,
                            farmId = farmId,
                            farmName = farmName,
                            farmLocation = farmLocation,
                            availabilityStatus = AvailabilityStatus.AVAILABLE,
                            approvalStatus = if (isAmmalFarm) ApprovalStatus.APPROVED else ApprovalStatus.PENDING_APPROVAL,
                            listingFeePaid = true,
                            listingFeeAmount = 0.0
                        )
                        onSave(goat)
                    }
                ) {
                    Text(
                        if (existingGoat != null) "Update Listing"
                        else "Create Listing"
                    )
                }
            }
        }
    }
}
}

@Composable
private fun ProfileInfoRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("$label: ", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(title: String, value: String, icon: ImageVector, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.height(2.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Tab 4: Listing Fee Receipts & Transactions for Farm Admin
 */
@Composable
private fun FarmListingPaymentsTab(
    payments: List<com.example.model.ListingPayment>,
    isAmmalFarm: Boolean,
    myGoats: List<Goat> = emptyList()
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
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
                            Icon(Icons.Default.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Listing Records & Receipts", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                if (isAmmalFarm) "₹0 Fee Waiver" else "Partner Listing Quota",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isAmmalFarm) {
                            "As the Ammal Farm seed owner, your goat listings enjoy a 100% platform waiver (₹0 fee) and are automatically approved."
                        } else {
                            "Partner farms have a default listing limit of 10 goats. When the limit is reached, contact Super Admin to increase your listing limit.\n\nWhatsApp: +91 63808 98358\nCall: +91 63808 98358\n\nPayment for increasing the limit is handled directly with us outside the app."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp
                    )
                }
            }
        }

        if (payments.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ReceiptLong,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            if (isAmmalFarm) "No fees charged (Ammal Farm Owner Waiver)" else "No payment records yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Any receipts will appear here.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(payments, key = { it.id }) { payment ->
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
                                Text(payment.receiptNumber ?: "RCPT-${payment.id.take(8).uppercase()}", fontWeight = FontWeight.Black, fontSize = 14.sp)
                                Text("Goat: ${payment.goatName.ifBlank { "Tag ${payment.goatTag}" }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        Spacer(modifier = Modifier.height(10.dp))
                        Divider()
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Amount: ₹${payment.amount.toInt()}", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                            Text(
                                "Order: ${payment.orderId?.takeLast(10) ?: "Direct"}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                        if (!payment.razorpayPaymentId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Payment ID: ${payment.razorpayPaymentId}",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FarmAdminAccessDeniedView(
    currentRole: String,
    onNavigateBack: () -> Unit = {},
    onNavigateToMarketplace: () -> Unit = onNavigateBack,
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
                Text("Farm Partner Authorization Required", fontWeight = FontWeight.Black, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "You do not possess farm administrator permissions to access this management dashboard. This portal is reserved for registered farm partners.",
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
                Button(
                    onClick = onNavigateToMarketplace,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Return to Marketplace", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSyncRole,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Refresh Permissions")
                }
            }
        }
    }
}

