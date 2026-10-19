package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.BookingStatus
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val completedOrders = remember(uiState?.bookings, currentUser?.id) {
        if (currentUser == null) emptyList()
        else uiState?.bookings?.filter {
            it.customerId == currentUser.id &&
            (it.status == BookingStatus.COMPLETED || it.status == BookingStatus.CONFIRMED || it.status == BookingStatus.APPROVED)
        }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Orders & Receipts", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (completedOrders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ReceiptLong,
                        contentDescription = null,
                        modifier = Modifier.size(52.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "No Completed Orders Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Confirmed and completed livestock purchase receipts will appear here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(completedOrders, key = { it.id }) { order ->
                    Card(
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Text(
                                        text = "Order #${order.bookingCode.ifBlank { order.id.take(8).uppercase() }}",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(
                                    text = order.status.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${order.goatName.ifBlank { "Goat" }} (${order.goatBreed})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Seller Farm: ${order.farmName.ifBlank { "Ammal Farm" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Total Amount", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = PriceUtils.formatINR(order.goatPrice),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

enum class FarmBookingFilterTab(val title: String) {
    ALL("All"),
    PENDING("Pending"),
    CONFIRMED("Confirmed"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled")
}

data class OrderStatusStyle(
    val containerColor: Color = Color(0xFFF1F5F9),
    val contentColor: Color = Color(0xFF334155),
    val label: String,
    val icon: Any? = null
)

fun getOrderStatusStyle(status: BookingStatus, isHoldExpired: Boolean = false): OrderStatusStyle {
    val label = if (isHoldExpired) {
        "Hold Expired"
    } else {
        when (status) {
            BookingStatus.RESERVED -> "Reserved (Hold)"
            BookingStatus.CONFIRMED, BookingStatus.APPROVED -> "Confirmed"
            BookingStatus.COMPLETED, BookingStatus.SOLD -> "Completed"
            BookingStatus.CANCELLED -> "Cancelled"
            BookingStatus.REJECTED -> "Rejected"
            BookingStatus.EXPIRED -> "Hold Expired"
            else -> "Pending"
        }
    }
    return OrderStatusStyle(label = label)
}

data class FarmBookingStatusStyle(
    val containerColor: Color = Color(0xFFF1F5F9),
    val contentColor: Color = Color(0xFF334155),
    val label: String
)

fun getFarmAdminBookingStatusStyle(status: BookingStatus, isExpired: Boolean = false): FarmBookingStatusStyle {
    val label = if (isExpired) {
        "Hold Expired"
    } else {
        when (status) {
            BookingStatus.CONFIRMED, BookingStatus.APPROVED -> "Confirmed"
            BookingStatus.COMPLETED, BookingStatus.SOLD -> "Completed"
            BookingStatus.CANCELLED -> "Cancelled"
            BookingStatus.REJECTED -> "Rejected"
            else -> "Pending"
        }
    }
    return FarmBookingStatusStyle(label = label)
}
