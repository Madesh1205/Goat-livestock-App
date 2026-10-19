package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.Booking
import com.ammalfarm.adusanthai.model.BookingStatus
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingsScreen(
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateBack: () -> Unit,
    onNavigateToGoatDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val myBookings = remember(uiState?.bookings, currentUser?.id) {
        if (currentUser == null) emptyList()
        else uiState?.bookings?.filter {
            it.customerId.equals(currentUser.id, ignoreCase = true) ||
                it.customerId.isBlank()
        }.orEmpty()
    }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentUser?.id) {
        if (currentUser != null) {
            marketplaceViewModel?.loadUserSpecificData(currentUser)
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("My Bookings (${myBookings.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { marketplaceViewModel?.loadUserSpecificData(currentUser) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (myBookings.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.EventNote,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "No Active Bookings Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reserve a goat from the marketplace to schedule a farm visit and hold your livestock.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onNavigateBack) {
                        Text("Browse Marketplace")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(myBookings, key = { it.id }) { booking ->
                    CustomerBookingCard(
                        booking = booking,
                        onViewGoat = {
                            if (booking.goatId.isNotBlank()) onNavigateToGoatDetail(booking.goatId)
                        },
                        onCancelBooking = {
                            marketplaceViewModel?.updateBookingStatus(
                                bookingId = booking.id,
                                newStatus = BookingStatus.CANCELLED,
                                reason = "Cancelled by customer"
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun CustomerBookingCard(
    booking: Booking,
    onViewGoat: () -> Unit,
    onCancelBooking: () -> Unit
) {
    val context = LocalContext.current
    val (statusColor, statusLabel) = when (booking.status) {
        BookingStatus.PENDING -> MaterialTheme.colorScheme.tertiary to "PENDING APPROVAL"
        BookingStatus.CONFIRMED, BookingStatus.APPROVED -> MaterialTheme.colorScheme.primary to "CONFIRMED"
        BookingStatus.COMPLETED -> MaterialTheme.colorScheme.secondary to "COMPLETED"
        BookingStatus.REJECTED -> MaterialTheme.colorScheme.error to "REJECTED"
        BookingStatus.CANCELLED -> MaterialTheme.colorScheme.outline to "CANCELLED"
        BookingStatus.EXPIRED -> MaterialTheme.colorScheme.outline to "EXPIRED"
    }

    ElevatedCard(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onViewGoat)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ref: ${booking.bookingCode.ifBlank { booking.id.take(8).uppercase() }}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    color = statusColor.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

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
                    if (booking.goatImageUrl.isNotBlank()) {
                        AsyncImage(
                            model = booking.goatImageUrl,
                            contentDescription = booking.goatName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Pets, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = booking.goatName.ifBlank { "Goat Listing" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (booking.goatBreed.isNotBlank()) {
                        Text(
                            text = booking.goatBreed,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = "Farm: ${booking.farmName.ifBlank { "Ammal Farm" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (booking.preferredVisitDate.isNotBlank()) {
                        Text(
                            text = "Visit: ${booking.preferredVisitDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = PriceUtils.formatINR(booking.goatPrice),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (booking.advanceAmount > 0.0) {
                        Text(
                            text = "Hold: ${PriceUtils.formatINR(booking.advanceAmount)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (booking.notes.isNotBlank()) {
                Text(
                    text = "Notes: ${booking.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (booking.farmPhone.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${booking.farmPhone}"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Call Farm")
                    }
                }

                if (booking.status == BookingStatus.PENDING || booking.status == BookingStatus.CONFIRMED) {
                    OutlinedButton(
                        onClick = onCancelBooking,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cancel Hold")
                    }
                }
            }
        }
    }
}
