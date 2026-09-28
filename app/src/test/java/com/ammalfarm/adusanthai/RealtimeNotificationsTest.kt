package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.AppNotification
import com.ammalfarm.adusanthai.model.NotificationType
import com.ammalfarm.adusanthai.model.UserRole
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class RealtimeNotificationsTest {

    @Test
    fun testNotificationDeduplicationById() {
        val map = ConcurrentHashMap<String, AppNotification>()
        
        val n1 = AppNotification(
            id = "00000000-0000-0000-0000-000000000001",
            recipientUserId = "user-123",
            title = "Order Placed",
            message = "Booking confirmed",
            timestamp = 1000L
        )
        val n1Duplicate = AppNotification(
            id = "00000000-0000-0000-0000-000000000001",
            recipientUserId = "user-123",
            title = "Order Placed",
            message = "Booking confirmed - duplicate event",
            timestamp = 1000L
        )
        val n2 = AppNotification(
            id = "00000000-0000-0000-0000-000000000002",
            recipientUserId = "user-123",
            title = "New Message",
            message = "Hello from Farm",
            timestamp = 2000L
        )

        // 1. Initial snapshot
        map[n1.id] = n1
        var list = map.values.sortedByDescending { it.timestamp }
        assertEquals(1, list.size)

        // 2. Realtime INSERT event for n2
        if (!map.containsKey(n2.id)) {
            map[n2.id] = n2
            list = map.values.sortedByDescending { it.timestamp }
        }
        assertEquals(2, list.size)
        assertEquals("00000000-0000-0000-0000-000000000002", list[0].id) // latest first

        // 3. Realtime INSERT event duplicate for n1
        if (!map.containsKey(n1Duplicate.id)) {
            map[n1Duplicate.id] = n1Duplicate
            list = map.values.sortedByDescending { it.timestamp }
        }
        // Size must still be 2, no duplicates created
        assertEquals(2, list.size)
    }

    @Test
    fun testUnreadCountComputation() {
        val notifications = listOf(
            AppNotification(
                id = "1",
                recipientUserId = "u1",
                title = "Title 1",
                message = "Message 1",
                isRead = false
            ),
            AppNotification(
                id = "2",
                recipientUserId = "u1",
                title = "Title 2",
                message = "Message 2",
                isRead = true
            ),
            AppNotification(
                id = "3",
                recipientUserId = "u1",
                title = "Title 3",
                message = "Message 3",
                isRead = false
            )
        )

        val unreadCount = notifications.count { !it.isRead }
        assertEquals(2, unreadCount)
    }

    @Test
    fun testUserIsolationFilter() {
        val targetUserId = "00000000-0000-0000-0000-000000000001"
        val otherUserId = "00000000-0000-0000-0000-000000000002"

        val allNotifs = listOf(
            AppNotification(id = "n1", recipientUserId = targetUserId, title = "A", message = "A"),
            AppNotification(id = "n2", recipientUserId = otherUserId, title = "B", message = "B")
        )

        val filtered = allNotifs.filter { it.recipientUserId == targetUserId }
        assertEquals(1, filtered.size)
        assertEquals("n1", filtered[0].id)
    }
}
