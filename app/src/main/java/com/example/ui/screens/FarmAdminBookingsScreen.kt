package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.core.util.FarmLocalCache
import com.example.core.util.GoatImageResolver
import com.example.model.AvailabilityStatus
import com.example.model.Booking
import com.example.model.UserRole
import com.example.ui.viewmodel.MarketplaceUiState
import java.text.SimpleDateFormat
import java.util.*

enum class FarmBookingFilterTab(val label: String) {
    ALL("All"),
    PENDING("Pending"),
    RESERVED("Reserved"),
    CONFIRMED("Confirmed"),
    CANCELLED("Cancelled"),
    EXPIRED("Expired"),
    COMPLETED("Completed")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmAdminBookingsScreen(
    uiState: MarketplaceUiState,
    onNavigateBack: () -> Unit,
    onConfirmBooking: (String) -> Unit,
    onRejectBooking: (String) -> Unit,
    onCompleteBooking: (String) -> Unit,
    onCancelBooking: (String) -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentUser = uiState.currentUser
    val isSuperAdmin = currentUser?.role == UserRole.SUPER_ADMIN
    val userFarmId = currentUser?.farmId

    // Identify this admin's farm
    val resolvedFarm = remember(uiState.farms, currentUser, userFarmId) {
        if (!userFarmId.isNullOrBlank()) {
            uiState.farms.find { it.id == userFarmId }
                ?: FarmLocalCache.getCachedFarm(userFarmId)
        } else if (currentUser != null) {
            uiState.farms.find { it.ownerId == currentUser.id }
                ?: FarmLocalCache.getAllCachedFarms().find { it.ownerId == currentUser.id }
        } else {
            null
        }
    }

    val myFarmId = resolvedFarm?.id ?: userFarmId ?: ""
    val farmName = resolvedFarm?.name ?: "My Farm"

    // Load only bookings strictly belonging to this farm
    val myGoats = remember(uiState.allAdminGoats, myFarmId, resolvedFarm) {
        if (myFarmId.isBlank()) {
            emptyList()
        } else {
            uiState.allAdminGoats.filter { goat ->
                goat.farmId == myFarmId || (resolvedFarm != null && goat.farmId == resolvedFarm.id)
            }
        }
    }
    val myGoatIds = remember(myGoats) { myGoats.map { it.id }.toSet() }

    val farmBookings = remember(uiState.farmBookings, uiState.allBookings, myFarmId, resolvedFarm, myGoats, myGoatIds) {
        val baseList = (uiState.farmBookings + uiState.allBookings).distinctBy { it.id }
        val matched = if (myFarmId.isNotBlank() || myGoatIds.isNotEmpty()) {
            baseList.filter { booking ->
                (myFarmId.isNotBlank() && booking.farmId == myFarmId) ||
                (resolvedFarm != null && booking.farmId == resolvedFarm.id) ||
                (booking.goatId.isNotBlank() && myGoatIds.contains(booking.goatId)) ||
                (resolvedFarm != null && (resolvedFarm.isAmmalOwnFarm || resolvedFarm.id == com.example.data.dto.SEED_AMMAL_FARM_UUID) && (booking.farmId == com.example.data.dto.SEED_AMMAL_FARM_UUID || booking.farmName.contains("Ammal", ignoreCase = true)))
            }
        } else {
            baseList
        }

        // Synthesize fallback booking views for any reserved goats lacking explicit booking records
        val existingBookingGoatIds = matched.mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
        val missingReservedGoats = myGoats.filter { goat ->
            (goat.availabilityStatus == AvailabilityStatus.RESERVED || goat.availabilityStatus == AvailabilityStatus.BOOKING_PENDING) &&
            !existingBookingGoatIds.contains(goat.id)
        }
        val syntheticBookings = missingReservedGoats.map { goat ->
            Booking(
                id = "res-" + goat.id,
                goatId = goat.id,
                goatCode = goat.goatCode,
                farmId = goat.farmId.ifBlank { myFarmId },
                customerId = "",
                customerName = "Customer Reservation",
                customerPhone = "Contact Farm Admin",
                goatName = goat.name,
                goatBreed = goat.breed,
                goatPhoto = goat.photos.firstOrNull() ?: "",
                farmName = goat.farmName.ifBlank { farmName },
                amount = goat.finalPrice,
                status = AvailabilityStatus.RESERVED,
                bookingDate = goat.createdAt,
                reservationExpiryDate = goat.createdAt + (24 * 3600 * 1000L),
                notes = "Active reservation hold on ${goat.name}"
            )
        }
        (matched + syntheticBookings).distinctBy { it.id }
    }

    var selectedFilter by remember { mutableStateOf(FarmBookingFilterTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedBookingForDetail by remember { mutableStateOf<Booking?>(null) }
    var bookingToReject by remember { mutableStateOf<Booking?>(null) }
    var bookingToCancel by remember { mutableStateOf<Booking?>(null) }
    var bookingToConfirm by remember { mutableStateOf<Booking?>(null) }
    var bookingToComplete by remember { mutableStateOf<Booking?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Counts for status filter pills
    val allCount = farmBookings.size
    val pendingCount = farmBookings.count { (it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.RESERVED) && !it.isHoldExpired }
    val reservedCount = farmBookings.count { (it.status == AvailabilityStatus.RESERVED || it.status == AvailabilityStatus.BOOKING_PENDING) && !it.isHoldExpired }
    val confirmedCount = farmBookings.count { it.status == AvailabilityStatus.CONFIRMED }
    val cancelledCount = farmBookings.count { it.status == AvailabilityStatus.CANCELLED || it.status == AvailabilityStatus.REJECTED }
    val expiredCount = farmBookings.count { it.isHoldExpired }
    val completedCount = farmBookings.count { it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD }

    // Filtered and Searched Bookings
    val filteredBookings = remember(farmBookings, selectedFilter, searchQuery) {
        val query = searchQuery.trim().lowercase()
        farmBookings.filter { booking ->
            val matchesStatus = when (selectedFilter) {
                FarmBookingFilterTab.ALL -> true
                FarmBookingFilterTab.PENDING -> (booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) && !booking.isHoldExpired
                FarmBookingFilterTab.RESERVED -> (booking.status == AvailabilityStatus.RESERVED || booking.status == AvailabilityStatus.BOOKING_PENDING) && !booking.isHoldExpired
                FarmBookingFilterTab.CONFIRMED -> booking.status == AvailabilityStatus.CONFIRMED
                FarmBookingFilterTab.CANCELLED -> booking.status == AvailabilityStatus.CANCELLED || booking.status == AvailabilityStatus.REJECTED
                FarmBookingFilterTab.EXPIRED -> booking.isHoldExpired
                FarmBookingFilterTab.COMPLETED -> booking.status == AvailabilityStatus.COMPLETED || booking.status == AvailabilityStatus.SOLD
            }

            val matchesSearch = if (query.isEmpty()) {
                true
            } else {
                booking.goatName.lowercase().contains(query) ||
                booking.goatCode.lowercase().contains(query) ||
                booking.displayGoatCode.lowercase().contains(query) ||
                booking.goatId.lowercase().contains(query) ||
                booking.customerName.lowercase().contains(query) ||
                booking.customerPhone.lowercase().contains(query) ||
                booking.bookingCode.lowercase().contains(query) ||
                booking.displayBookingCode.lowercase().contains(query) ||
                booking.id.lowercase().contains(query)
            }

            matchesStatus && matchesSearch
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Farm Bookings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$farmName • ${farmBookings.size} Total Orders",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("farm_bookings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            isRefreshing = true
                            onRefresh()
                            Toast.makeText(context, "Refreshing bookings...", Toast.LENGTH_SHORT).show()
                            isRefreshing = false
                        },
                        modifier = Modifier.testTag("refresh_farm_bookings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Bookings",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Metrics Summary Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BookingMetricCard(
                    title = "Pending Holds",
                    count = pendingCount + reservedCount,
                    icon = Icons.Default.HourglassTop,
                    containerColor = Color(0xFFFFF3E0),
                    contentColor = Color(0xFFE65100),
                    modifier = Modifier.weight(1f)
                )
                BookingMetricCard(
                    title = "Confirmed",
                    count = confirmedCount,
                    icon = Icons.Default.CheckCircle,
                    containerColor = Color(0xFFE8F5E9),
                    contentColor = Color(0xFF2E7D32),
                    modifier = Modifier.weight(1f)
                )
                BookingMetricCard(
                    title = "Completed",
                    count = completedCount,
                    icon = Icons.Default.DoneAll,
                    containerColor = Color(0xFFE3F2FD),
                    contentColor = Color(0xFF1565C0),
                    modifier = Modifier.weight(1f)
                )
            }

            // Search Bar (Requirement 6: Goat name, GOAT-XXX, Customer name, Booking code)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            "Search by Goat, GOAT-XXX, Buyer, or Booking ID...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("farm_booking_search_input")
                )
            }

            // Status Filter Chips (Requirement 5: All, Pending, Reserved, Confirmed, Cancelled, Expired, Completed)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(FarmBookingFilterTab.values()) { tab ->
                    val isSelected = selectedFilter == tab
                    val tabCount = when (tab) {
                        FarmBookingFilterTab.ALL -> allCount
                        FarmBookingFilterTab.PENDING -> pendingCount
                        FarmBookingFilterTab.RESERVED -> reservedCount
                        FarmBookingFilterTab.CONFIRMED -> confirmedCount
                        FarmBookingFilterTab.CANCELLED -> cancelledCount
                        FarmBookingFilterTab.EXPIRED -> expiredCount
                        FarmBookingFilterTab.COMPLETED -> completedCount
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = tab },
                        label = {
                            Text(
                                text = "${tab.label} ($tabCount)",
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                        ),
                        modifier = Modifier.testTag("farm_booking_filter_${tab.name.lowercase()}")
                    )
                }
            }

            // Error / Offline notification if any
            if (uiState.errorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Loading / Empty / Content States
            if (uiState.isLoading && farmBookings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Loading farm bookings...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (filteredBookings.isEmpty()) {
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
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.ReceiptLong,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "No bookings match \"$searchQuery\""
                            } else if (selectedFilter != FarmBookingFilterTab.ALL) {
                                "No ${selectedFilter.label} bookings found"
                            } else {
                                "No customer bookings yet"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) {
                                "Try searching with a different buyer name, goat code, or booking reference."
                            } else {
                                "When customers place 24-hour reservation holds on your livestock listings, they will appear here in real-time."
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredBookings, key = { it.id }) { booking ->
                        FarmAdminBookingCard(
                            booking = booking,
                            onCardClick = { selectedBookingForDetail = booking },
                            onCallBuyer = {
                                if (booking.customerPhone.isNotBlank()) {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.customerPhone}"))
                                    context.startActivity(intent)
                                } else {
                                    Toast.makeText(context, "Buyer phone number unavailable", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onConfirmClick = { bookingToConfirm = booking },
                            onRejectClick = { bookingToReject = booking },
                            onCompleteClick = { bookingToComplete = booking },
                            onCancelClick = { bookingToCancel = booking }
                        )
                    }
                }
            }
        }
    }

    // --- CONFIRM BOOKING DIALOG ---
    if (bookingToConfirm != null) {
        val b = bookingToConfirm!!
        AlertDialog(
            onDismissRequest = { bookingToConfirm = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Confirm Customer Booking?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Confirm order ${b.displayBookingCode} for ${b.customerName} reserving ${b.goatName} (${b.displayGoatCode}). This verifies phone contact and confirms animal preparation.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirmBooking(b.id)
                        bookingToConfirm = null
                        Toast.makeText(context, "Booking ${b.displayBookingCode} confirmed successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("dialog_confirm_booking_button")
                ) {
                    Text("Yes, Confirm Booking", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- COMPLETE / FULFILL BOOKING DIALOG ---
    if (bookingToComplete != null) {
        val b = bookingToComplete!!
        AlertDialog(
            onDismissRequest = { bookingToComplete = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.DoneAll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Mark Booking as Completed?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Has the buyer (${b.customerName}) picked up ${b.goatName} and finalized payment of ${b.formattedAmount}? Marking as completed finalizes the transaction and marks the goat as SOLD.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCompleteBooking(b.id)
                        bookingToComplete = null
                        Toast.makeText(context, "Booking ${b.displayBookingCode} marked as COMPLETED!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("dialog_complete_booking_button")
                ) {
                    Text("Mark as Completed", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToComplete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- REJECT BOOKING DIALOG ---
    if (bookingToReject != null) {
        val b = bookingToReject!!
        AlertDialog(
            onDismissRequest = { bookingToReject = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Reject Customer Booking?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to reject booking ${b.displayBookingCode} from ${b.customerName}? The hold on ${b.goatName} will be released and made available to other marketplace buyers.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRejectBooking(b.id)
                        bookingToReject = null
                        Toast.makeText(context, "Booking ${b.displayBookingCode} rejected.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("dialog_reject_booking_button")
                ) {
                    Text("Reject Hold", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToReject = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- CANCEL BOOKING DIALOG ---
    if (bookingToCancel != null) {
        val b = bookingToCancel!!
        AlertDialog(
            onDismissRequest = { bookingToCancel = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.Cancel,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Cancel Booking Order?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to cancel confirmed booking ${b.displayBookingCode}? The goat will be released back to the marketplace.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onCancelBooking(b.id)
                        bookingToCancel = null
                        Toast.makeText(context, "Booking ${b.displayBookingCode} cancelled.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("dialog_cancel_booking_button")
                ) {
                    Text("Yes, Cancel Order", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToCancel = null }) {
                    Text("Keep Order")
                }
            }
        )
    }

    // --- BOOKING DETAIL MODAL SHEET (Requirement 7) ---
    if (selectedBookingForDetail != null) {
        val b = selectedBookingForDetail!!
        ModalBottomSheet(
            onDismissRequest = { selectedBookingForDetail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            FarmAdminBookingDetailContent(
                booking = b,
                onCallBuyer = {
                    if (b.customerPhone.isNotBlank()) {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${b.customerPhone}"))
                        context.startActivity(intent)
                    } else {
                        Toast.makeText(context, "Buyer phone number unavailable", Toast.LENGTH_SHORT).show()
                    }
                },
                onConfirmClick = {
                    val target = b
                    selectedBookingForDetail = null
                    bookingToConfirm = target
                },
                onRejectClick = {
                    val target = b
                    selectedBookingForDetail = null
                    bookingToReject = target
                },
                onCompleteClick = {
                    val target = b
                    selectedBookingForDetail = null
                    bookingToComplete = target
                },
                onCancelClick = {
                    val target = b
                    selectedBookingForDetail = null
                    bookingToCancel = target
                },
                onDismiss = { selectedBookingForDetail = null }
            )
        }
    }
}

@Composable
private fun BookingMetricCard(
    title: String,
    count: Int,
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = count.toString(),
                    fontWeight = FontWeight.Black,
                    fontSize = 16.sp,
                    color = contentColor
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun FarmAdminBookingCard(
    booking: Booking,
    onCardClick: () -> Unit,
    onCallBuyer: () -> Unit,
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val isPendingOrReserved = booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED
    val isConfirmed = booking.status == AvailabilityStatus.CONFIRMED
    val isCompleted = booking.status == AvailabilityStatus.COMPLETED || booking.status == AvailabilityStatus.SOLD
    val isHoldExpired = booking.isHoldExpired

    val (statusBg, statusText, statusLabel) = getFarmAdminBookingStatusStyle(booking.status, isHoldExpired)

    val initialPhoto = remember(booking.id, booking.goatPhoto) {
        booking.goatPhoto.ifBlank {
            GoatImageResolver.getCachedPhoto(booking.goatId) ?: ""
        }
    }
    var resolvedPhotoUrl by remember(booking.id, booking.goatId, initialPhoto) {
        mutableStateOf(initialPhoto)
    }

    LaunchedEffect(booking.id, booking.goatId) {
        if (resolvedPhotoUrl.isBlank() && booking.goatId.isNotBlank()) {
            val photo = GoatImageResolver.resolvePrimaryPhoto(booking.goatId)
            if (photo.isNotBlank()) {
                resolvedPhotoUrl = photo
            }
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardClick() }
            .testTag("farm_booking_card_${booking.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Booking Code & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = booking.displayBookingCode,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (booking.displayGoatCode.isNotBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = booking.displayGoatCode,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusBg
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusText)
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
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Main Details Row: Goat Photo, Goat Name, Customer, Price
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                if (resolvedPhotoUrl.isNotBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(resolvedPhotoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = booking.goatName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pets,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = booking.goatName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Breed: ${booking.goatBreed}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = booking.customerName,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (booking.customerPhone.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = booking.customerPhone,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = booking.formattedAmount,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (isPendingOrReserved && !isHoldExpired) {
                        val hoursRemaining = remember(booking.reservationExpiryDate) {
                            val diff = booking.reservationExpiryDate - System.currentTimeMillis()
                            if (diff > 0) (diff / (1000 * 3600)).toInt() else 0
                        }
                        Text(
                            text = "${hoursRemaining}h hold left",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100)
                        )
                    } else {
                        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        Text(
                            text = dateFormat.format(Date(booking.bookingDate)),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Customer Notes if present
            if (booking.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notes,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Buyer Note: ${booking.notes}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Action Buttons Row (Requirement 8)
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Call Buyer Button
                if (booking.customerPhone.isNotBlank()) {
                    OutlinedButton(
                        onClick = onCallBuyer,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Phone,
                            contentDescription = "Call",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Call Buyer", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(
                        onClick = onCardClick,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Details", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Dynamic Actions based on booking status
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isPendingOrReserved && !isHoldExpired) {
                        OutlinedButton(
                            onClick = onRejectClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Reject", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onConfirmClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Confirm", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else if (isConfirmed) {
                        OutlinedButton(
                            onClick = onCancelClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                        Button(
                            onClick = onCompleteClick,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Mark Sold", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        TextButton(
                            onClick = onCardClick,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("View Details", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FarmAdminBookingDetailContent(
    booking: Booking,
    onCallBuyer: () -> Unit,
    onConfirmClick: () -> Unit,
    onRejectClick: () -> Unit,
    onCompleteClick: () -> Unit,
    onCancelClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault())
    val isPendingOrReserved = booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED
    val isConfirmed = booking.status == AvailabilityStatus.CONFIRMED
    val isHoldExpired = booking.isHoldExpired

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Booking Order ${booking.displayBookingCode}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Placed: ${dateFormat.format(Date(booking.bookingDate))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Progress Stepper
        BookingStatusStepper(currentStatus = booking.status)

        Spacer(modifier = Modifier.height(16.dp))

        // GOAT LISTING SUMMARY CARD
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            val initialDetailPhoto = remember(booking.id, booking.goatPhoto) {
                booking.goatPhoto.ifBlank {
                    GoatImageResolver.getCachedPhoto(booking.goatId) ?: ""
                }
            }
            var detailPhotoUrl by remember(booking.id, booking.goatId, initialDetailPhoto) {
                mutableStateOf(initialDetailPhoto)
            }

            LaunchedEffect(booking.id, booking.goatId) {
                if (detailPhotoUrl.isBlank() && booking.goatId.isNotBlank()) {
                    val photo = GoatImageResolver.resolvePrimaryPhoto(booking.goatId)
                    if (photo.isNotBlank()) {
                        detailPhotoUrl = photo
                    }
                }
            }

            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (detailPhotoUrl.isNotBlank()) {
                    SubcomposeAsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(detailPhotoUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = booking.goatName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(70.dp)
                            .clip(RoundedCornerShape(10.dp)),
                        loading = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                )
                            }
                        },
                        error = {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pets,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(booking.goatName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Breed: ${booking.goatBreed}", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    if (booking.displayGoatCode.isNotBlank()) {
                        Text("Code: ${booking.displayGoatCode}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Booking Price: ${booking.formattedAmount}",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // BUYER / CUSTOMER INFORMATION CARD
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Customer Information",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(booking.customerName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }

                if (booking.customerPhone.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(booking.customerPhone, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        IconButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Customer Phone", booking.customerPhone)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Phone number copied", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Phone", modifier = Modifier.size(15.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = onCallBuyer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Call Customer (${booking.customerPhone})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Special Instructions / Customer Notes
        if (booking.notes.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Customer Order Note:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(booking.notes, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Action Buttons inside detail view
        if (isPendingOrReserved && !isHoldExpired) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onRejectClick,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reject Hold", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirmClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Confirm Order", fontWeight = FontWeight.Bold)
                }
            }
        } else if (isConfirmed) {
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCancelClick,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel Order", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onCompleteClick,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Mark Completed", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

fun getFarmAdminBookingStatusStyle(status: AvailabilityStatus, isExpired: Boolean): Triple<Color, Color, String> {
    if (isExpired) {
        return Triple(
            Color(0xFFEDE7F6),
            Color(0xFF512DA8),
            "Hold Expired"
        )
    }
    return when (status) {
        AvailabilityStatus.CONFIRMED -> Triple(
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            "Confirmed"
        )
        AvailabilityStatus.BOOKING_PENDING -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Pending Hold"
        )
        AvailabilityStatus.RESERVED -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Reserved (24h Hold)"
        )
        AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Triple(
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            "Completed / Sold"
        )
        AvailabilityStatus.CANCELLED -> Triple(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            "Cancelled"
        )
        AvailabilityStatus.REJECTED -> Triple(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            "Rejected by Farm"
        )
        else -> Triple(
            Color(0xFFF5F5F5),
            Color(0xFF616161),
            status.name
        )
    }
}
