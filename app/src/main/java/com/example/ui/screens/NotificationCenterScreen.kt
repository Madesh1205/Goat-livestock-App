package com.example.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppNotification
import com.example.model.NotificationType
import com.example.model.UserRole
import com.example.ui.viewmodel.MarketplaceUiState

enum class NotificationFilterTab {
    ALL,
    UNREAD,
    BOOKINGS,
    APPROVALS,
    PAYMENTS,
    SYSTEM
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationCenterScreen(
    uiState: MarketplaceUiState,
    onBackClick: () -> Unit,
    onMarkAsRead: (String) -> Unit,
    onMarkAllAsRead: () -> Unit,
    onDeleteNotification: (String) -> Unit,
    onClearAllNotifications: () -> Unit,
    onNavigateToRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNotificationClick: ((AppNotification) -> Unit)? = null
) {
    var selectedFilter by remember { mutableStateOf(NotificationFilterTab.ALL) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val currentRole = uiState.currentUser?.role ?: UserRole.CUSTOMER
    val isCustomer = currentRole == UserRole.CUSTOMER
    val allNotifications = uiState.notifications

    val displayedNotifications = remember(allNotifications, selectedFilter, isCustomer) {
        when (selectedFilter) {
            NotificationFilterTab.ALL -> allNotifications
            NotificationFilterTab.UNREAD -> allNotifications.filter { !it.isRead }
            NotificationFilterTab.BOOKINGS -> allNotifications.filter {
                it.type in listOf(
                    NotificationType.BOOKING_CREATED,
                    NotificationType.BOOKING_CONFIRMED,
                    NotificationType.BOOKING_REJECTED,
                    NotificationType.BOOKING_CANCELLED,
                    NotificationType.RESERVATION_EXPIRY,
                    NotificationType.NEW_BOOKING,
                    NotificationType.BOOKING_CANCELLATION
                )
            }
            NotificationFilterTab.APPROVALS -> {
                if (isCustomer) emptyList() else allNotifications.filter {
                    it.type in listOf(
                        NotificationType.NEW_FARM_APPLICATION,
                        NotificationType.NEW_LISTING_PENDING,
                        NotificationType.LISTING_APPROVED,
                        NotificationType.LISTING_REJECTED,
                        NotificationType.NEW_REPORT_SUBMITTED
                    )
                }
            }
            NotificationFilterTab.PAYMENTS -> allNotifications.filter {
                it.type in listOf(
                    NotificationType.LISTING_PAYMENT_SUCCESS,
                    NotificationType.LISTING_PAYMENT_FAILED,
                    NotificationType.NEW_PAYMENT_RECEIVED
                )
            }
            NotificationFilterTab.SYSTEM -> allNotifications.filter {
                it.type in listOf(
                    NotificationType.LISTING_UPDATE,
                    NotificationType.SYSTEM_ALERT
                )
            }
        }
    }

    val unreadCount = remember(allNotifications) {
        allNotifications.count { !it.isRead }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Notification Center",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        if (unreadCount > 0) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Badge(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ) {
                                Text("$unreadCount")
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("notification_back_btn")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // More Menu
                    IconButton(onClick = { showMoreMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options")
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Mark all as read") },
                            leadingIcon = { Icon(Icons.Default.DoneAll, contentDescription = null) },
                            onClick = {
                                onMarkAllAsRead()
                                showMoreMenu = false
                            },
                            modifier = Modifier.testTag("mark_all_read_btn")
                        )
                        DropdownMenuItem(
                            text = { Text("Clear all notifications") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
                            onClick = {
                                onClearAllNotifications()
                                showMoreMenu = false
                            },
                            modifier = Modifier.testTag("clear_all_notifs_btn")
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Filter Tabs Row
            val availableTabs = remember(isCustomer) {
                if (isCustomer) {
                    listOf(
                        NotificationFilterTab.ALL,
                        NotificationFilterTab.UNREAD,
                        NotificationFilterTab.BOOKINGS,
                        NotificationFilterTab.PAYMENTS,
                        NotificationFilterTab.SYSTEM
                    )
                } else {
                    NotificationFilterTab.values().toList()
                }
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(availableTabs) { tab ->
                    val isSelected = selectedFilter == tab
                    val label = when (tab) {
                        NotificationFilterTab.ALL -> "All (${allNotifications.size})"
                        NotificationFilterTab.UNREAD -> "Unread ($unreadCount)"
                        NotificationFilterTab.BOOKINGS -> "Bookings"
                        NotificationFilterTab.APPROVALS -> "Approvals"
                        NotificationFilterTab.PAYMENTS -> "Payments"
                        NotificationFilterTab.SYSTEM -> "System"
                    }
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = tab },
                        label = { Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Notifications List
            if (displayedNotifications.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.NotificationsNone,
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Text(
                            text = if (selectedFilter == NotificationFilterTab.UNREAD) "No unread notifications" else "No notifications yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "You're all caught up! Live farm updates & booking alerts will appear here.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(displayedNotifications, key = { it.id }) { notification ->
                        NotificationItemCard(
                            notification = notification,
                            isCustomer = isCustomer,
                            onItemClick = {
                                onMarkAsRead(notification.id)
                                if (onNotificationClick != null) {
                                    onNotificationClick(notification)
                                } else {
                                    val targetRoute = notification.deepLinkRoute
                                        ?: com.example.util.DeepLinkUtils.resolveDeepLinkRoute(notification.type, notification.referenceId)
                                    if (!isCustomer || (targetRoute != "farm_dashboard" && targetRoute != "super_admin_dashboard")) {
                                        onNavigateToRoute(targetRoute)
                                    }
                                }
                            },
                            onMarkAsRead = { onMarkAsRead(notification.id) },
                            onDelete = { onDeleteNotification(notification.id) },
                            onDeepLinkClick = { route ->
                                onMarkAsRead(notification.id)
                                if (onNotificationClick != null) {
                                    onNotificationClick(notification)
                                } else {
                                    if (!isCustomer || (route != "farm_dashboard" && route != "super_admin_dashboard")) {
                                        onNavigateToRoute(route)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationItemCard(
    notification: AppNotification,
    isCustomer: Boolean = false,
    onItemClick: () -> Unit,
    onMarkAsRead: () -> Unit,
    onDelete: () -> Unit,
    onDeepLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUnread = !notification.isRead
    val (icon, iconBg, iconTint) = getNotificationVisuals(notification.type)
    val formattedTime = remember(notification.timestamp) {
        val now = System.currentTimeMillis()
        if (now - notification.timestamp < 60000L) {
            "Just now"
        } else {
            DateUtils.getRelativeTimeSpanString(
                notification.timestamp,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isUnread) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            1.dp,
            if (isUnread) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isUnread) 2.dp else 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onItemClick() }
            .testTag("notif_card_${notification.id}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Category Icon Avatar
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = iconBg,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Title, Message, Timestamps
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = notification.title,
                            fontWeight = if (isUnread) FontWeight.Bold else FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = formattedTime,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            if (isUnread) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    }

                    Text(
                        text = notification.message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    // Tags & Deep Link Button
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Spacer(modifier = Modifier.width(1.dp))

                        // Deep Link Button if available (exclude admin portals for CUSTOMER)
                        val isRouteAllowedForCustomer = !isCustomer || (
                            notification.deepLinkRoute != "farm_dashboard" &&
                            notification.deepLinkRoute != "super_admin_dashboard"
                        )
                        if (!notification.deepLinkRoute.isNullOrBlank() && isRouteAllowedForCustomer) {
                            TextButton(
                                onClick = { onDeepLinkClick(notification.deepLinkRoute) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text(
                                    text = getDeepLinkLabel(notification.deepLinkRoute),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun getNotificationVisuals(type: NotificationType): Triple<ImageVector, Color, Color> {
    return when (type) {
        NotificationType.BOOKING_CONFIRMED,
        NotificationType.LISTING_APPROVED -> Triple(
            Icons.Default.CheckCircle,
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32)
        )
        NotificationType.BOOKING_CREATED,
        NotificationType.NEW_BOOKING -> Triple(
            Icons.Default.ReceiptLong,
            Color(0xFFE3F2FD),
            Color(0xFF1565C0)
        )
        NotificationType.BOOKING_REJECTED,
        NotificationType.LISTING_REJECTED,
        NotificationType.LISTING_PAYMENT_FAILED -> Triple(
            Icons.Default.Cancel,
            Color(0xFFFFEBEE),
            Color(0xFFC62828)
        )
        NotificationType.BOOKING_CANCELLED,
        NotificationType.BOOKING_CANCELLATION -> Triple(
            Icons.Default.Block,
            Color(0xFFFFF3E0),
            Color(0xFFE65100)
        )
        NotificationType.RESERVATION_EXPIRY -> Triple(
            Icons.Default.HourglassBottom,
            Color(0xFFFFF8E1),
            Color(0xFFF57F17)
        )
        NotificationType.LISTING_PAYMENT_SUCCESS,
        NotificationType.NEW_PAYMENT_RECEIVED -> Triple(
            Icons.Default.Payments,
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32)
        )
        NotificationType.NEW_FARM_APPLICATION,
        NotificationType.NEW_LISTING_PENDING -> Triple(
            Icons.Default.FactCheck,
            Color(0xFFEDE7F6),
            Color(0xFF512DA8)
        )
        NotificationType.NEW_REPORT_SUBMITTED -> Triple(
            Icons.Default.ReportProblem,
            Color(0xFFFFEBEE),
            Color(0xFFC62828)
        )
        NotificationType.LISTING_UPDATE -> Triple(
            Icons.Default.Update,
            Color(0xFFE0F7FA),
            Color(0xFF00838F)
        )
        NotificationType.SYSTEM_ALERT -> Triple(
            Icons.Default.Campaign,
            Color(0xFFECEFF1),
            Color(0xFF37474F)
        )
    }
}

private fun getDeepLinkLabel(route: String): String {
    return when (route) {
        "my_bookings" -> "View Booking"
        "farm_dashboard" -> "Farm Portal"
        "super_admin_dashboard" -> "Admin Review"
        "marketplace" -> "Marketplace"
        "goat_detail" -> "View Goat"
        else -> "Open"
    }
}
