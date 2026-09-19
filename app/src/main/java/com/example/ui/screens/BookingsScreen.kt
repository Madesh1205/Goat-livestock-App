package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
import com.example.model.Goat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class BookingFilterTab(val label: String) {
    ALL("All Bookings"),
    ACTIVE("Active Holds"),
    CONFIRMED("Confirmed"),
    COMPLETED("Completed / Sold"),
    CANCELLED("Cancelled / Rejected")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingsScreen(
    bookings: List<Booking>,
    goats: List<Goat> = emptyList(),
    onCancelBooking: (String) -> Unit,
    onNavigateToMarketplace: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Cache photos of existing marketplace goats in memory
    LaunchedEffect(goats) {
        if (goats.isNotEmpty()) {
            for (g in goats) {
                val p = g.photos.firstOrNull { it.isNotBlank() }
                if (p != null) {
                    GoatImageResolver.cachePhoto(g.id, p)
                }
            }
        }
    }

    var selectedFilter by remember { mutableStateOf(BookingFilterTab.ALL) }
    var selectedBookingForDetail by remember { mutableStateOf<Booking?>(null) }
    var bookingToCancel by remember { mutableStateOf<Booking?>(null) }

    // Filter list based on active tab
    val filteredBookings = remember(bookings, selectedFilter) {
        when (selectedFilter) {
            BookingFilterTab.ALL -> bookings
            BookingFilterTab.ACTIVE -> bookings.filter {
                it.status == AvailabilityStatus.BOOKING_PENDING ||
                it.status == AvailabilityStatus.RESERVED
            }
            BookingFilterTab.CONFIRMED -> bookings.filter {
                it.status == AvailabilityStatus.CONFIRMED
            }
            BookingFilterTab.COMPLETED -> bookings.filter {
                it.status == AvailabilityStatus.COMPLETED ||
                it.status == AvailabilityStatus.SOLD
            }
            BookingFilterTab.CANCELLED -> bookings.filter {
                it.status == AvailabilityStatus.CANCELLED ||
                it.status == AvailabilityStatus.REJECTED
            }
        }
    }

    val activeCount = bookings.count {
        it.status == AvailabilityStatus.BOOKING_PENDING ||
        it.status == AvailabilityStatus.RESERVED ||
        it.status == AvailabilityStatus.CONFIRMED
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "My Livestock Bookings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (activeCount > 0) "$activeCount active reservation(s) held" else "Manage holds & purchases",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Filter Pills Bar
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(BookingFilterTab.values()) { tab ->
                    val isSelected = selectedFilter == tab
                    val tabCount = when (tab) {
                        BookingFilterTab.ALL -> bookings.size
                        BookingFilterTab.ACTIVE -> bookings.count { it.status == AvailabilityStatus.BOOKING_PENDING || it.status == AvailabilityStatus.RESERVED }
                        BookingFilterTab.CONFIRMED -> bookings.count { it.status == AvailabilityStatus.CONFIRMED }
                        BookingFilterTab.COMPLETED -> bookings.count { it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD }
                        BookingFilterTab.CANCELLED -> bookings.count { it.status == AvailabilityStatus.CANCELLED || it.status == AvailabilityStatus.REJECTED }
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
                        )
                    )
                }
            }

            // Summary Banner
            if (activeCount > 0 && selectedFilter == BookingFilterTab.ALL) {
                Surface(
                    color = Color(0xFFFFF8E1),
                    border = BorderStroke(1.dp, Color(0xFFFFE082)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTimeFilled,
                            contentDescription = null,
                            tint = Color(0xFFF57C00),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "24-Hour exclusive hold guarantees no other customer can book your selected goats.",
                            fontSize = 12.sp,
                            color = Color(0xFF5D4037),
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            if (filteredBookings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
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
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = if (selectedFilter == BookingFilterTab.ALL) "No bookings placed yet" else "No bookings in ${selectedFilter.label}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Browse our livestock marketplace to reserve champion Boer, Tellicherry, Jamunapari or Sirohi stock.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                        BookingCard(
                            booking = booking,
                            onCardClick = { selectedBookingForDetail = booking },
                            onCancelClick = { bookingToCancel = booking }
                        )
                    }
                }
            }
        }
    }

    // --- CANCEL RESERVATION CONFIRMATION DIALOG ---
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
                    text = "Cancel 24h Hold?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Are you sure you want to cancel your reservation for ${b.goatName}? The hold will be released, allowing other marketplace buyers to reserve this goat immediately.",
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
                        Toast.makeText(context, "Reservation cancelled. Goat released to marketplace.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("confirm_cancel_button")
                ) {
                    Text("Yes, Cancel Hold", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { bookingToCancel = null }) {
                    Text("Keep Reservation")
                }
            }
        )
    }

    // --- BOOKING DETAIL MODAL SHEET ---
    if (selectedBookingForDetail != null) {
        val b = selectedBookingForDetail!!
        ModalBottomSheet(
            onDismissRequest = { selectedBookingForDetail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            BookingDetailContent(
                booking = b,
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
fun BookingCard(
    booking: Booking,
    onCardClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    val context = LocalContext.current
    val isCancellable = booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED
    val isCompleted = booking.status == AvailabilityStatus.COMPLETED || booking.status == AvailabilityStatus.SOLD

    val (statusBg, statusText, statusLabel) = getBookingStatusStyle(booking.status)

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
            .testTag("booking_card_${booking.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Goat Photo, Name, Breed, Price
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
                            .size(68.dp)
                            .clip(RoundedCornerShape(12.dp)),
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
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pets,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Pets,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp)
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Breed: ${booking.goatBreed}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Agriculture,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = booking.farmName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = booking.formattedAmount,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "24h Hold",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Badge & Expiry Row
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = statusBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusText)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = statusText
                        )
                    }

                    if (booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) {
                        val hoursRemaining = remember(booking.reservationExpiryDate) {
                            val diff = booking.reservationExpiryDate - System.currentTimeMillis()
                            if (diff > 0) (diff / (1000 * 3600)).toInt() else 0
                        }
                        Text(
                            text = "${hoursRemaining}h remaining",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusText
                        )
                    } else {
                        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                        Text(
                            text = dateFormat.format(Date(booking.bookingDate)),
                            fontSize = 11.sp,
                            color = statusText
                        )
                    }
                }
            }

            // Buyer Notes preview if present
            if (booking.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Notes,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = booking.notes,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons Row
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // View Details Button
                TextButton(
                    onClick = onCardClick,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("View Details & Stepper", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Contact Farm Breeder Button (for active/confirmed bookings)
                    val farmContact = FarmLocalCache.getCachedFarm(booking.farmId)?.contactNumber?.trim()?.takeIf { it.isNotBlank() }
                    if ((booking.status == AvailabilityStatus.CONFIRMED || booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) && !farmContact.isNullOrBlank()) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$farmContact"))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Call", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Call Farm", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Cancel button
                    if (isCancellable) {
                        OutlinedButton(
                            onClick = onCancelClick,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cancel Hold", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookingDetailContent(
    booking: Booking,
    onCancelClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val dateFormat = SimpleDateFormat("dd MMMM yyyy, hh:mm a", Locale.getDefault())

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Booking #${booking.id}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Placed on ${dateFormat.format(Date(booking.bookingDate))}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // STATUS STEPPER PROGRESS BAR
        BookingStatusStepper(currentStatus = booking.status)

        Spacer(modifier = Modifier.height(16.dp))

        // LIVESTOCK DETAILS CARD
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
                                modifier = Modifier.fillMaxSize(),
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
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Total Price: ${booking.formattedAmount}",
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // FARM / BREEDER CONTACT CARD
        val cachedFarm = FarmLocalCache.getCachedFarm(booking.farmId)
        val farmLocation = cachedFarm?.location?.trim()?.takeIf { it.isNotBlank() }
        val farmContact = cachedFarm?.contactNumber?.trim()?.takeIf { it.isNotBlank() }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Breeder & Pickup Logistics",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Agriculture, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(booking.farmName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }

                if (!farmLocation.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(farmLocation, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (!farmContact.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$farmContact"))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Call Farm ($farmContact)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        if (booking.notes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("Your Pickup / Booking Instructions:", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(booking.notes, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Cancel Reservation button inside detail modal if pending/reserved
        if (booking.status == AvailabilityStatus.BOOKING_PENDING || booking.status == AvailabilityStatus.RESERVED) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = onCancelClick,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Release & Cancel 24h Hold", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
fun BookingStatusStepper(currentStatus: AvailabilityStatus) {
    val steps = listOf(
        "Booking Created",
        "Phone Contact",
        "Confirmed",
        "Completed"
    )

    val currentStepIndex = when (currentStatus) {
        AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.RESERVED -> 1
        AvailabilityStatus.CONFIRMED -> 2
        AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> 3
        AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED -> -1
        else -> 0
    }

    if (currentStepIndex == -1) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "This reservation has been ${currentStatus.name.lowercase()}.",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            steps.forEachIndexed { index, label ->
                val isCompleted = index < currentStepIndex
                val isCurrent = index == currentStepIndex

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> Color(0xFF2E7D32)
                                    isCurrent -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isCompleted) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        } else {
                            Text(
                                text = "${index + 1}",
                                color = if (isCurrent) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = label,
                        fontSize = 10.sp,
                        fontWeight = if (isCurrent || isCompleted) FontWeight.Bold else FontWeight.Normal,
                        color = if (isCurrent || isCompleted) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                if (index < steps.size - 1) {
                    Box(
                        modifier = Modifier
                            .height(2.dp)
                            .weight(0.5f)
                            .background(if (index < currentStepIndex) Color(0xFF2E7D32) else MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
    }
}

fun getBookingStatusStyle(status: AvailabilityStatus): Triple<Color, Color, String> {
    return when (status) {
        AvailabilityStatus.CONFIRMED -> Triple(
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            "Confirmed by Farm"
        )
        AvailabilityStatus.BOOKING_PENDING -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "Hold Active (Pending Farm Review)"
        )
        AvailabilityStatus.RESERVED -> Triple(
            Color(0xFFFFF3E0),
            Color(0xFFE65100),
            "24-Hour Reserved"
        )
        AvailabilityStatus.COMPLETED -> Triple(
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            "Completed & Sold"
        )
        AvailabilityStatus.SOLD -> Triple(
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            "Sold"
        )
        AvailabilityStatus.CANCELLED -> Triple(
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            "Cancelled by Customer"
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
