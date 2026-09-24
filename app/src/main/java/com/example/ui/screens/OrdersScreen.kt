package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.model.*
import com.example.ui.viewmodel.MarketplaceUiState
import com.example.core.util.GoatImageResolver
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

enum class OrderStatusFilter(val label: String) {
    ALL("All Orders"),
    RESERVED("Reserved"),
    CONFIRMED("Confirmed"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    uiState: MarketplaceUiState,
    onNavigateBack: () -> Unit,
    onConfirmBooking: (String) -> Unit,
    onRejectBooking: (String, String) -> Unit,
    onCancelBooking: (String, String) -> Unit,
    onCompleteBooking: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissSelectedBooking: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUser = uiState.currentUser
    val role = currentUser?.role ?: UserRole.CUSTOMER
    val isFarmAdmin = role == UserRole.FARM_ADMIN
    val isSuperAdmin = role == UserRole.SUPER_ADMIN

    // Resolve Farm Admin's farm
    val myFarm = remember(uiState.farms, currentUser) {
        val userFarmId = currentUser?.farmId.orEmpty()
        uiState.farms.firstOrNull { it.id == userFarmId }
            ?: uiState.farms.firstOrNull { it.ownerId == currentUser?.id }
            ?: uiState.farms.firstOrNull { it.isAmmalOwnFarm }
    }
    val myFarmId = myFarm?.id.orEmpty()

    // 1. Resolve Farm Orders (incoming bookings for this farm)
    val myAdminGoats = remember(uiState.allAdminGoats, myFarmId, myFarm) {
        if (myFarmId.isBlank()) emptyList()
        else uiState.allAdminGoats.filter { it.farmId == myFarmId || (myFarm != null && it.farmId == myFarm.id) }
    }
    val myGoatIds = remember(myAdminGoats) { myAdminGoats.map { it.id }.toSet() }

    val resolvedFarmOrders = remember(uiState.farmBookings, uiState.allBookings, myFarmId, myFarm, myAdminGoats, myGoatIds) {
        val baseList = (uiState.farmBookings + uiState.allBookings).distinctBy { it.id }
        val matched = if (myFarmId.isNotBlank() || myGoatIds.isNotEmpty()) {
            baseList.filter { booking ->
                (myFarmId.isNotBlank() && booking.farmId == myFarmId) ||
                (myFarm != null && booking.farmId == myFarm.id) ||
                (booking.goatId.isNotBlank() && myGoatIds.contains(booking.goatId)) ||
                (myFarm != null && (myFarm.isAmmalOwnFarm || myFarm.id == com.example.data.dto.SEED_AMMAL_FARM_UUID) &&
                    (booking.farmId == com.example.data.dto.SEED_AMMAL_FARM_UUID || booking.farmName.contains("Ammal", ignoreCase = true)))
            }
        } else {
            uiState.farmBookings
        }

        // Synthesize fallback booking views for reserved goats lacking explicit booking records
        val existingGoatIds = matched.mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
        val missingGoats = myAdminGoats.filter { goat ->
            (goat.availabilityStatus == AvailabilityStatus.RESERVED || goat.availabilityStatus == AvailabilityStatus.BOOKING_PENDING) &&
            !existingGoatIds.contains(goat.id)
        }
        val synthetic = missingGoats.map { goat ->
            Booking(
                id = "res-" + goat.id,
                goatId = goat.id,
                goatCode = goat.goatCode,
                farmId = goat.farmId.ifBlank { myFarmId },
                customerId = "",
                customerName = "Customer Reservation / Hold",
                customerPhone = "Contact Farm Admin",
                goatName = goat.name,
                goatBreed = goat.breed,
                goatPhoto = goat.photos.firstOrNull() ?: "",
                farmName = goat.farmName.ifBlank { myFarm?.name ?: "Farm" },
                amount = goat.finalPrice,
                status = AvailabilityStatus.RESERVED,
                bookingDate = System.currentTimeMillis(),
                reservationExpiryDate = System.currentTimeMillis() + (24 * 3600 * 1000L),
                notes = "24-hour reservation hold"
            )
        }
        (matched + synthetic).distinctBy { it.id }.sortedByDescending { it.bookingDate }
    }

    // 2. Resolve Customer Orders (goats personally booked by this user)
    val resolvedCustomerOrders = remember(uiState.customerBookings) {
        uiState.customerBookings.distinctBy { it.id }.sortedByDescending { it.bookingDate }
    }

    // 3. Resolve Super Admin Platform Orders
    val resolvedPlatformOrders = remember(uiState.allBookings, uiState.farmBookings) {
        (uiState.allBookings + uiState.farmBookings).distinctBy { it.id }.sortedByDescending { it.bookingDate }
    }

    // Tab Selection for Dual-role screens
    // 0: Farm Orders / Platform Orders, 1: My Orders
    var selectedTab by remember { mutableIntStateOf(0) }

    // Search and Status Filter
    var searchQuery by remember { mutableStateOf("") }
    var selectedStatusFilter by remember { mutableStateOf(OrderStatusFilter.ALL) }

    // Selected order for detail dialog
    var selectedOrderForDetail by remember { mutableStateOf<Booking?>(null) }

    // Rejection / Cancellation Dialog state
    var bookingToCancel by remember { mutableStateOf<Booking?>(null) }
    var cancelReason by remember { mutableStateOf("") }
    var isRejectAction by remember { mutableStateOf(false) }

    // Auto-open target booking if deep linked / notification clicked
    LaunchedEffect(uiState.selectedBookingIdForDetail, resolvedFarmOrders, resolvedCustomerOrders, resolvedPlatformOrders) {
        val targetId = uiState.selectedBookingIdForDetail
        if (!targetId.isNullOrBlank()) {
            val farmMatch = resolvedFarmOrders.find { it.id == targetId || it.goatId == targetId || it.id.endsWith(targetId) }
            val customerMatch = resolvedCustomerOrders.find { it.id == targetId || it.goatId == targetId || it.id.endsWith(targetId) }
            val platformMatch = resolvedPlatformOrders.find { it.id == targetId || it.goatId == targetId || it.id.endsWith(targetId) }

            if (isFarmAdmin && farmMatch != null) {
                selectedTab = 0
                selectedOrderForDetail = farmMatch
            } else if (customerMatch != null) {
                if (isFarmAdmin || isSuperAdmin) selectedTab = 1
                selectedOrderForDetail = customerMatch
            } else if (isSuperAdmin && platformMatch != null) {
                selectedTab = 0
                selectedOrderForDetail = platformMatch
            } else if (farmMatch != null) {
                selectedOrderForDetail = farmMatch
            }
        }
    }

    // Determine current active orders list based on role & tab
    val currentOrdersList = when {
        isFarmAdmin -> if (selectedTab == 0) resolvedFarmOrders else resolvedCustomerOrders
        isSuperAdmin -> if (selectedTab == 0) resolvedPlatformOrders else resolvedCustomerOrders
        else -> resolvedCustomerOrders
    }

    // Filter by search & status
    val filteredOrders = remember(currentOrdersList, searchQuery, selectedStatusFilter) {
        currentOrdersList.filter { booking ->
            val matchesSearch = searchQuery.isBlank() ||
                booking.goatName.contains(searchQuery, ignoreCase = true) ||
                booking.goatBreed.contains(searchQuery, ignoreCase = true) ||
                booking.goatCode.contains(searchQuery, ignoreCase = true) ||
                booking.farmName.contains(searchQuery, ignoreCase = true) ||
                booking.customerName.contains(searchQuery, ignoreCase = true) ||
                booking.customerPhone.contains(searchQuery, ignoreCase = true) ||
                booking.id.contains(searchQuery, ignoreCase = true) ||
                booking.bookingCode.contains(searchQuery, ignoreCase = true)

            val matchesStatus = when (selectedStatusFilter) {
                OrderStatusFilter.ALL -> true
                OrderStatusFilter.RESERVED -> (booking.status == AvailabilityStatus.RESERVED || booking.status == AvailabilityStatus.BOOKING_PENDING) && !booking.isHoldExpired
                OrderStatusFilter.CONFIRMED -> booking.status == AvailabilityStatus.CONFIRMED
                OrderStatusFilter.COMPLETED -> booking.status == AvailabilityStatus.COMPLETED
                OrderStatusFilter.CANCELLED -> booking.status == AvailabilityStatus.CANCELLED || booking.status == AvailabilityStatus.REJECTED
                OrderStatusFilter.EXPIRED -> booking.isHoldExpired
            }
            matchesSearch && matchesStatus
        }
    }

    Scaffold(
        modifier = modifier.testTag("orders_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Orders",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = when {
                                isFarmAdmin -> "Manage farm orders received & personal bookings"
                                isSuperAdmin -> "Platform livestock orders & reservations"
                                else -> "Track your goat reservations & orders"
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("orders_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("orders_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Role-Aware Dual Tabs for Farm Admin and Super Admin
            if (isFarmAdmin || isSuperAdmin) {
                val tab0Label = if (isFarmAdmin) "Farm Orders (${resolvedFarmOrders.size})" else "All Orders (${resolvedPlatformOrders.size})"
                val tab1Label = "My Orders (${resolvedCustomerOrders.size})"

                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("orders_primary_tab_row")
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier.testTag("orders_tab_farm"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isFarmAdmin) Icons.Default.Agriculture else Icons.Default.AdminPanelSettings,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tab0Label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier.testTag("orders_tab_my"),
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.ShoppingBag,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tab1Label, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    )
                }

                // Subtitle explaining active tab scope
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isFarmAdmin) {
                                if (selectedTab == 0) "Customers who booked goats from your farm"
                                else "Goats you personally booked from other partner farms"
                            } else {
                                if (selectedTab == 0) "All platform booking records and holds across farms"
                                else "Your personal livestock purchases"
                            },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("orders_search_input"),
                placeholder = {
                    Text(
                        text = if (isFarmAdmin && selectedTab == 0) "Search by goat, order ID, or customer..."
                               else "Search by goat, order ID, or farm...",
                        fontSize = 13.sp
                    )
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Status Filter Chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(OrderStatusFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedStatusFilter == filter,
                        onClick = { selectedStatusFilter = filter },
                        label = { Text(filter.label, fontSize = 12.sp) },
                        modifier = Modifier.testTag("orders_filter_${filter.name.lowercase()}"),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Orders List / Empty State
            if (filteredOrders.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ReceiptLong,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotBlank() || selectedStatusFilter != OrderStatusFilter.ALL) {
                                "No orders match your filter"
                            } else if (isFarmAdmin && selectedTab == 0) {
                                "No farm orders received yet"
                            } else if (isFarmAdmin && selectedTab == 1) {
                                "You haven't booked any goats from other farms"
                            } else {
                                "You have no orders yet"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                "Try searching with a different keyword or reset filters"
                            } else if (isFarmAdmin && selectedTab == 0) {
                                "When customers book goats listed under your farm, their orders and holds will appear here."
                            } else {
                                "Explore the marketplace to reserve and book verified livestock."
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val isManagingFarmOrders = (isFarmAdmin || isSuperAdmin) && selectedTab == 0

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize().testTag("orders_list")
                ) {
                    items(filteredOrders, key = { it.id }) { booking ->
                        OrderCard(
                            booking = booking,
                            isFarmOrder = isManagingFarmOrders,
                            isCustomerView = !isManagingFarmOrders,
                            onCardClick = { selectedOrderForDetail = booking },
                            onConfirmClick = { onConfirmBooking(booking.id) },
                            onRejectClick = {
                                bookingToCancel = booking
                                isRejectAction = true
                                cancelReason = ""
                            },
                            onCancelClick = {
                                bookingToCancel = booking
                                isRejectAction = false
                                cancelReason = ""
                            },
                            onCompleteClick = { onCompleteBooking(booking.id) },
                            onCallCustomer = {
                                if (booking.customerPhone.isNotBlank() && booking.customerPhone != "Contact Farm Admin") {
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.customerPhone}"))
                                    context.startActivity(dialIntent)
                                } else {
                                    Toast.makeText(context, "No customer phone provided", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 1. Unified Order Detail Dialog
    if (selectedOrderForDetail != null) {
        val order = selectedOrderForDetail!!
        val isFarmOrder = (isFarmAdmin || isSuperAdmin) && selectedTab == 0

        OrderDetailDialog(
            booking = order,
            isFarmOrder = isFarmOrder,
            onDismissRequest = {
                selectedOrderForDetail = null
                onDismissSelectedBooking()
            },
            onConfirm = {
                onConfirmBooking(order.id)
                selectedOrderForDetail = null
                onDismissSelectedBooking()
            },
            onReject = {
                selectedOrderForDetail = null
                bookingToCancel = order
                isRejectAction = true
                cancelReason = ""
            },
            onCancel = {
                selectedOrderForDetail = null
                bookingToCancel = order
                isRejectAction = false
                cancelReason = ""
            },
            onComplete = {
                onCompleteBooking(order.id)
                selectedOrderForDetail = null
                onDismissSelectedBooking()
            }
        )
    }

    // 2. Cancellation / Rejection Confirmation Dialog
    if (bookingToCancel != null) {
        val b = bookingToCancel!!
        AlertDialog(
            onDismissRequest = { bookingToCancel = null },
            icon = {
                Icon(
                    imageVector = if (isRejectAction) Icons.Default.Cancel else Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = if (isRejectAction) "Reject Booking Request" else "Cancel Order",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (isRejectAction) {
                            "Are you sure you want to reject the booking for ${b.goatName}? This will cancel the hold and release the goat back to available status."
                        } else {
                            "Are you sure you want to cancel the order for ${b.goatName}? The booking will be marked as cancelled."
                        },
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = cancelReason,
                        onValueChange = { cancelReason = it },
                        label = { Text("Reason (Optional)") },
                        placeholder = { Text("e.g. Buyer request, out of stock...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reason = cancelReason.trim().ifBlank { if (isRejectAction) "Rejected by farm admin" else "Cancelled by user" }
                        if (isRejectAction) {
                            onRejectBooking(b.id, reason)
                        } else {
                            onCancelBooking(b.id, reason)
                        }
                        bookingToCancel = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_cancel_button")
                ) {
                    Text(if (isRejectAction) "Reject Booking" else "Confirm Cancellation")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { bookingToCancel = null }) {
                    Text("Go Back")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------------
// Order Card Component
// ---------------------------------------------------------------------------------
@Composable
private fun OrderCard(
    booking: Booking,
    isFarmOrder: Boolean,
    isCustomerView: Boolean,
    onCardClick: () -> Unit,
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit,
    onCancelClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onCallCustomer: () -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val isHoldActive = (booking.status == AvailabilityStatus.RESERVED || booking.status == AvailabilityStatus.BOOKING_PENDING)
    val isExpired = booking.isHoldExpired

    val (statusBg, statusText, statusLabel, statusIcon) = getOrderStatusStyle(booking.status, isExpired)

    val resolvedPhoto = remember(booking.goatPhoto, booking.goatId) {
        booking.goatPhoto.ifBlank { GoatImageResolver.getCachedPhoto(booking.goatId).orEmpty() }
    }

    val formattedPrice = remember(booking.amount) {
        try {
            NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(booking.amount)
        } catch (_: Exception) {
            "₹${booking.amount.toLong()}"
        }
    }

    val formattedDate = remember(booking.bookingDate) {
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
        sdf.format(Date(booking.bookingDate))
    }

    val remainingHoldText = remember(booking.reservationExpiryDate, now) {
        if (booking.reservationExpiryDate != null && isHoldActive && !isExpired) {
            val diffMs = booking.reservationExpiryDate!! - now
            val hours = diffMs / (1000 * 60 * 60)
            val mins = (diffMs % (1000 * 60 * 60)) / (1000 * 60)
            "${hours}h ${mins}m left"
        } else null
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("order_card_${booking.id}")
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // Header Row: Status Badge and Expiry / Hold indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBg,
                    modifier = Modifier.testTag("status_badge_${booking.id}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusText,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = statusText
                        )
                    }
                }

                // Reservation countdown or Booking ID
                if (remainingHoldText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.HourglassTop,
                                contentDescription = null,
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = remainingHoldText,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE65100)
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (booking.bookingCode.isNotBlank()) "#${booking.bookingCode}" else "#${booking.id.take(8).uppercase()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Body: Goat Photo + Core Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Goat Image
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (resolvedPhoto.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(resolvedPhoto)
                                .crossfade(true)
                                .build(),
                            contentDescription = booking.goatName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp).align(Alignment.Center)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Goat Info & Pricing
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = booking.goatName.ifBlank { "Registered Livestock" },
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (booking.goatCode.isNotBlank()) {
                            Text(
                                text = "[${booking.goatCode}] ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = booking.goatBreed.ifBlank { "Standard Breed" },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = booking.farmName.ifBlank { "Ammal Farm" },
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Text(
                        text = formattedPrice,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Customer info strip for Farm Orders
            if (isFarmOrder) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

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
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = booking.customerName.ifBlank { "Customer" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (booking.customerPhone.isNotBlank() && booking.customerPhone != "Contact Farm Admin") {
                        IconButton(
                            onClick = onCallCustomer,
                            modifier = Modifier.size(28.dp).testTag("call_customer_btn_${booking.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Call Customer",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Booking Date Stamp
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Booked on $formattedDate",
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            // Role-Specific Quick Action Buttons
            if (isFarmOrder) {
                // Farm Admin actions
                when (booking.status) {
                    AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING -> {
                        if (!isExpired) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onRejectClick,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                    modifier = Modifier.weight(1f).height(36.dp).testTag("reject_order_${booking.id}")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reject", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = onConfirmClick,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1.3f).height(36.dp).testTag("confirm_order_${booking.id}")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Confirm", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    AvailabilityStatus.CONFIRMED -> {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancelClick,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
                                modifier = Modifier.weight(1f).height(36.dp).testTag("cancel_order_${booking.id}")
                            ) {
                                Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onCompleteClick,
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                modifier = Modifier.weight(1.3f).height(36.dp).testTag("complete_order_${booking.id}")
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Complete Order", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    else -> {}
                }
            } else if (isCustomerView) {
                // Customer actions: can cancel active reservation hold
                if (isHoldActive && !isExpired) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onCancelClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth().height(36.dp).testTag("customer_cancel_order_${booking.id}")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Cancel Reservation", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Unified Order Detail Dialog
// ---------------------------------------------------------------------------------
@Composable
private fun OrderDetailDialog(
    booking: Booking,
    isFarmOrder: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val isHoldActive = (booking.status == AvailabilityStatus.RESERVED || booking.status == AvailabilityStatus.BOOKING_PENDING)
    val isExpired = booking.isHoldExpired
    val (statusBg, statusText, statusLabel, statusIcon) = getOrderStatusStyle(booking.status, isExpired)

    val resolvedPhoto = remember(booking.goatPhoto, booking.goatId) {
        booking.goatPhoto.ifBlank { GoatImageResolver.getCachedPhoto(booking.goatId).orEmpty() }
    }

    val formattedPrice = remember(booking.amount) {
        try {
            NumberFormat.getCurrencyInstance(Locale("en", "IN")).format(booking.amount)
        } catch (_: Exception) {
            "₹${booking.amount.toLong()}"
        }
    }

    val formattedDate = remember(booking.bookingDate) {
        val sdf = SimpleDateFormat("EEEE, dd MMM yyyy, hh:mm a", Locale.getDefault())
        sdf.format(Date(booking.bookingDate))
    }

    val expiryFormatted = remember(booking.reservationExpiryDate) {
        if (booking.reservationExpiryDate != null) {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            sdf.format(Date(booking.reservationExpiryDate!!))
        } else null
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .testTag("order_detail_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Top Bar: Title & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Order Details",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Goat Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (resolvedPhoto.isNotBlank()) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(resolvedPhoto)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = booking.goatName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(36.dp).align(Alignment.Center)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = booking.goatName.ifBlank { "Registered Livestock" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (booking.goatCode.isNotBlank()) {
                            Text(
                                text = "Goat ID: ${booking.goatCode}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = "Breed: ${booking.goatBreed.ifBlank { "Standard Breed" }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Farm: ${booking.farmName.ifBlank { "Ammal Farm" }}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Status Strip
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusText,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Current Status: $statusLabel",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = statusText
                            )
                            if (expiryFormatted != null && isHoldActive) {
                                Text(
                                    text = if (isExpired) "Reservation expired on $expiryFormatted"
                                           else "Reservation holds until $expiryFormatted",
                                    fontSize = 11.5.sp,
                                    color = statusText.copy(alpha = 0.9f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Order Meta Details Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Order ID with 1-tap copy
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Order / Booking ID", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("Booking ID", booking.id))
                                    Toast.makeText(context, "Order ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(
                                    text = if (booking.bookingCode.isNotBlank()) "#${booking.bookingCode}" else "#${booking.id.take(12)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Order ID",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Price
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Total Price", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formattedPrice, fontSize = 14.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Date
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Booking Date", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(formattedDate, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                // Customer Information Card (only if viewing farm orders)
                if (isFarmOrder) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Customer Information",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Customer Name", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(booking.customerName.ifBlank { "Customer" }, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            if (booking.customerPhone.isNotBlank() && booking.customerPhone != "Contact Farm Admin") {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Phone", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(booking.customerPhone, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(
                                            onClick = {
                                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.customerPhone}"))
                                                context.startActivity(dialIntent)
                                            },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }

                            if (booking.notes.isNotBlank()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Column {
                                    Text("Notes / Requests", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(booking.notes, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }

                // Relevant Actions based on role & status
                Spacer(modifier = Modifier.height(18.dp))

                if (isFarmOrder) {
                    when (booking.status) {
                        AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING -> {
                            if (!isExpired) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = onReject,
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                                        modifier = Modifier.weight(1f).height(44.dp)
                                    ) {
                                        Text("Reject", fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = onConfirm,
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1.3f).height(44.dp)
                                    ) {
                                        Text("Confirm Order", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        AvailabilityStatus.CONFIRMED -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onCancel,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                                    modifier = Modifier.weight(1f).height(44.dp)
                                ) {
                                    Text("Cancel Order", fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = onComplete,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.weight(1.3f).height(44.dp)
                                ) {
                                    Text("Complete Order", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        else -> {}
                    }
                } else {
                    // Customer view: Cancel active hold
                    if (isHoldActive && !isExpired) {
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                            modifier = Modifier.fillMaxWidth().height(44.dp)
                        ) {
                            Text("Cancel Reservation", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// Status Styling Helper
// ---------------------------------------------------------------------------------
fun getOrderStatusStyle(status: AvailabilityStatus, isExpired: Boolean = false): Tuple4<Color, Color, String, ImageVector> {
    if (isExpired) {
        return Tuple4(
            Color(0xFFECEFF1),
            Color(0xFF455A64),
            "Hold Expired",
            Icons.Default.TimerOff
        )
    }
    return when (status) {
        AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING -> Tuple4(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Reserved (Hold)",
            Icons.Default.HourglassTop
        )
        AvailabilityStatus.CONFIRMED -> Tuple4(
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            "Confirmed",
            Icons.Default.CheckCircle
        )
        AvailabilityStatus.COMPLETED -> Tuple4(
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            "Completed",
            Icons.Default.TaskAlt
        )
        AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED -> Tuple4(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            if (status == AvailabilityStatus.REJECTED) "Rejected" else "Cancelled",
            Icons.Default.Cancel
        )
        else -> Tuple4(
            Color(0xFFF5F5F5),
            Color(0xFF616161),
            status.name,
            Icons.Default.Info
        )
    }
}

data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
