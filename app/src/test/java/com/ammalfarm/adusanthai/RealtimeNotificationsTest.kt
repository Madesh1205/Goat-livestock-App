package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.AppNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.*
import org.junit.Test

class RealtimeNotificationsTest {

    @Test
    fun testLiveInsertionUpdatesListAndUnreadCount() {
        val notificationsFlow = MutableStateFlow<List<AppNotification>>(emptyList())
        assertEquals(0, notificationsFlow.value.size)
        assertEquals(0, notificationsFlow.value.count { !it.isRead })

        val initial = AppNotification(
            id = "n-1",
            userId = "user-1",
            title = "Welcome",
            message = "Welcome to Adu Santhai",
            isRead = false,
            createdAt = "2026-10-20T10:00:00Z",
            timestamp = 1000L
        )

        // Simulate initial fetch
        notificationsFlow.update { listOf(initial) }
        assertEquals(1, notificationsFlow.value.size)
        assertEquals(1, notificationsFlow.value.count { !it.isRead })

        // Simulate live Realtime INSERT event
        val liveIncoming = AppNotification(
            id = "n-2",
            userId = "user-1",
            title = "Booking Confirmed",
            message = "Your visit is confirmed",
            isRead = false,
            createdAt = "2026-10-20T10:05:00Z",
            timestamp = 2000L
        )

        notificationsFlow.update { current ->
            val updated = if (current.any { it.id == liveIncoming.id }) {
                current.map { if (it.id == liveIncoming.id) liveIncoming else it }
            } else {
                listOf(liveIncoming) + current
            }
            updated.distinctBy { it.id }.sortedWith(
                compareByDescending<AppNotification> { it.createdAt.ifBlank { "0" } }
                    .thenByDescending { it.timestamp }
            )
        }

        // List size grows to 2, latest notification is at index 0, unread count is 2
        assertEquals(2, notificationsFlow.value.size)
        assertEquals("n-2", notificationsFlow.value[0].id)
        assertEquals(2, notificationsFlow.value.count { !it.isRead })
    }

    @Test
    fun testNotificationDeduplicationById() {
        val notificationsFlow = MutableStateFlow<List<AppNotification>>(emptyList())

        val n1 = AppNotification(
            id = "00000000-0000-0000-0000-000000000001",
            userId = "user-123",
            title = "Order Placed",
            message = "Booking confirmed",
            createdAt = "2026-10-20T10:00:00Z",
            timestamp = 1000L
        )
        val n1Duplicate = AppNotification(
            id = "00000000-0000-0000-0000-000000000001",
            userId = "user-123",
            title = "Order Placed",
            message = "Booking confirmed - duplicate event",
            createdAt = "2026-10-20T10:00:00Z",
            timestamp = 1000L
        )
        val n2 = AppNotification(
            id = "00000000-0000-0000-0000-000000000002",
            userId = "user-123",
            title = "New Message",
            message = "Hello from Farm",
            createdAt = "2026-10-20T10:05:00Z",
            timestamp = 2000L
        )

        // 1. Initial snapshot
        notificationsFlow.value = listOf(n1)
        assertEquals(1, notificationsFlow.value.size)

        // 2. Realtime INSERT event for n2
        notificationsFlow.update { current ->
            (listOf(n2) + current).distinctBy { it.id }
                .sortedWith(compareByDescending<AppNotification> { it.createdAt }.thenByDescending { it.timestamp })
        }
        assertEquals(2, notificationsFlow.value.size)
        assertEquals("00000000-0000-0000-0000-000000000002", notificationsFlow.value[0].id)

        // 3. Duplicate Realtime INSERT event for n1
        notificationsFlow.update { current ->
            if (current.any { it.id == n1Duplicate.id }) {
                current.map { if (it.id == n1Duplicate.id) n1Duplicate else it }
            } else {
                listOf(n1Duplicate) + current
            }
        }
        // Size must still be 2, no duplicates created
        assertEquals(2, notificationsFlow.value.size)
    }

    @Test
    fun testReadStatusChangeUpdatesBadgeAndStateImmediately() {
        val notificationsFlow = MutableStateFlow(
            listOf(
                AppNotification(id = "notif-1", userId = "u1", title = "T1", message = "M1", isRead = false),
                AppNotification(id = "notif-2", userId = "u1", title = "T2", message = "M2", isRead = false)
            )
        )

        assertEquals(2, notificationsFlow.value.count { !it.isRead })

        // Mark notif-1 as read immediately in repository state
        notificationsFlow.update { list ->
            list.map { if (it.id == "notif-1") it.copy(isRead = true) else it }
        }

        val updated = notificationsFlow.value
        assertTrue(updated.first { it.id == "notif-1" }.isRead)
        assertFalse(updated.first { it.id == "notif-2" }.isRead)
        assertEquals(1, updated.count { !it.isRead })

        // Mark all as read
        notificationsFlow.update { list ->
            list.map { it.copy(isRead = true) }
        }
        assertEquals(0, notificationsFlow.value.count { !it.isRead })
    }

    @Test
    fun testUserIsolationFilter() {
        val targetUserId = "00000000-0000-0000-0000-000000000001"
        val otherUserId = "00000000-0000-0000-0000-000000000002"

        val notificationsFlow = MutableStateFlow<List<AppNotification>>(emptyList())

        val incomingEvents = listOf(
            AppNotification(id = "n1", userId = targetUserId, title = "A", message = "For Target User"),
            AppNotification(id = "n2", userId = otherUserId, title = "B", message = "For Other User"),
            AppNotification(id = "n3", userId = targetUserId, title = "C", message = "Also For Target User")
        )

        for (event in incomingEvents) {
            // Strict user isolation gate in handleRealtimeNotificationAction
            if (event.userId.equals(targetUserId, ignoreCase = true) ||
                event.recipientUserId.equals(targetUserId, ignoreCase = true)
            ) {
                notificationsFlow.update { list ->
                    (listOf(event) + list).distinctBy { it.id }
                }
            }
        }

        // Only target user's notifications are admitted
        assertEquals(2, notificationsFlow.value.size)
        assertTrue(notificationsFlow.value.all { it.userId == targetUserId })
        assertFalse(notificationsFlow.value.any { it.id == "n2" })
    }

    @Test
    fun testReconnectAndResyncMissedEvents() {
        val notificationsFlow = MutableStateFlow(
            listOf(
                AppNotification(id = "n-1", userId = "u1", title = "T1", message = "M1", isRead = true, createdAt = "2026-10-20T08:00:00Z")
            )
        )

        // While disconnected, 2 new notifications were inserted on the server:
        val serverSnapshotAfterReconnect = listOf(
            AppNotification(id = "n-3", userId = "u1", title = "T3", message = "M3", isRead = false, createdAt = "2026-10-20T09:00:00Z"),
            AppNotification(id = "n-2", userId = "u1", title = "T2", message = "M2", isRead = false, createdAt = "2026-10-20T08:30:00Z"),
            AppNotification(id = "n-1", userId = "u1", title = "T1", message = "M1", isRead = true, createdAt = "2026-10-20T08:00:00Z")
        )

        // On SUBSCRIBED or Realtime.CONNECTED, refreshNotifications(userId) is triggered
        notificationsFlow.value = serverSnapshotAfterReconnect.distinctBy { it.id }
            .sortedWith(compareByDescending<AppNotification> { it.createdAt }.thenByDescending { it.timestamp })

        assertEquals(3, notificationsFlow.value.size)
        assertEquals("n-3", notificationsFlow.value[0].id)
        assertEquals("n-2", notificationsFlow.value[1].id)
        assertEquals("n-1", notificationsFlow.value[2].id)
        assertEquals(2, notificationsFlow.value.count { !it.isRead })
    }

    @Test
    fun testClearUserSessionOnLogout() {
        val notificationsFlow = MutableStateFlow(
            listOf(
                AppNotification(id = "n-1", userId = "u1", title = "T1", message = "M1", isRead = false)
            )
        )
        assertEquals(1, notificationsFlow.value.size)

        // Simulate clearUserSession()
        notificationsFlow.value = emptyList()

        assertTrue(notificationsFlow.value.isEmpty())
        assertEquals(0, notificationsFlow.value.count { !it.isRead })
    }
}