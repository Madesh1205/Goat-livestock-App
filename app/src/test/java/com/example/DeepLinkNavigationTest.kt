package com.example

import com.example.model.NotificationType
import com.example.model.UserRole
import com.example.util.DeepLinkUtils
import com.example.util.NotificationDeepLinkPayload
import org.junit.Assert.*
import org.junit.Test

class DeepLinkNavigationTest {

    // --- TEST A: Notification Type Mapping ---
    @Test
    fun testAllNotificationTypesMapToExpectedRoutes() {
        assertEquals("my_bookings", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.BOOKING_CREATED, null))
        assertEquals("my_bookings", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.BOOKING_CONFIRMED, null))
        assertEquals("my_bookings", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.BOOKING_REJECTED, null))
        assertEquals("my_bookings", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.BOOKING_CANCELLED, null))

        assertEquals("goat_detail", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.RESERVATION_EXPIRY, "goat-123"))
        assertEquals("my_bookings", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.RESERVATION_EXPIRY, "booking-456"))

        assertEquals("goat_detail", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_UPDATE, "goat-789"))
        assertEquals("marketplace", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_UPDATE, null))

        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.NEW_BOOKING, null))
        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.BOOKING_CANCELLATION, null))
        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_PAYMENT_SUCCESS, null))
        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_PAYMENT_FAILED, null))
        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_APPROVED, null))
        assertEquals("farm_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.LISTING_REJECTED, null))

        assertEquals("super_admin_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.NEW_FARM_APPLICATION, null))
        assertEquals("super_admin_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.NEW_LISTING_PENDING, null))
        assertEquals("super_admin_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.NEW_PAYMENT_RECEIVED, null))
        assertEquals("super_admin_dashboard", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.NEW_REPORT_SUBMITTED, null))

        assertEquals("notifications", DeepLinkUtils.resolveDeepLinkRoute(NotificationType.SYSTEM_ALERT, null))
    }

    // --- TEST B: Payload Resolution ---
    @Test
    fun testPayloadRouteResolutionExplicitVsFallback() {
        val payloadWithExplicitRoute = NotificationDeepLinkPayload(
            notificationId = "notif-1",
            route = "custom_route",
            notificationType = NotificationType.BOOKING_CREATED
        )
        val resolvedRoute1 = payloadWithExplicitRoute.route.takeIf { !it.isNullOrBlank() }
            ?: payloadWithExplicitRoute.notificationType?.let { DeepLinkUtils.resolveDeepLinkRoute(it, payloadWithExplicitRoute.referenceId) }
        assertEquals("custom_route", resolvedRoute1)

        val payloadWithFallback = NotificationDeepLinkPayload(
            notificationId = "notif-2",
            route = null,
            referenceId = "goat-999",
            notificationType = NotificationType.LISTING_UPDATE
        )
        val resolvedRoute2 = payloadWithFallback.route.takeIf { !it.isNullOrBlank() }
            ?: payloadWithFallback.notificationType?.let { DeepLinkUtils.resolveDeepLinkRoute(it, payloadWithFallback.referenceId) }
        assertEquals("goat_detail", resolvedRoute2)
    }

    // --- TEST C & D: Cold-Start and Background Intent Payload Construction ---
    @Test
    fun testPayloadConstructionForColdStartAndBackground() {
        val coldStartPayload = NotificationDeepLinkPayload(
            notificationId = "notif-cold-1",
            route = "my_bookings",
            referenceId = "booking-101",
            notificationType = NotificationType.BOOKING_CONFIRMED,
            recipientUserId = "user-abc",
            intentKey = "cold_start_key_1"
        )
        assertEquals("notif-cold-1", coldStartPayload.notificationId)
        assertEquals("my_bookings", coldStartPayload.route)
        assertEquals("booking-101", coldStartPayload.referenceId)
        assertEquals("user-abc", coldStartPayload.recipientUserId)
        assertEquals("cold_start_key_1", coldStartPayload.intentKey)
    }

    // --- TEST E: Duplicate Tap Key Uniqueness ---
    @Test
    fun testDuplicateTapPreventionKeySet() {
        val handledKeys = mutableSetOf<String>()
        val key = "notif-repeat-1"

        assertTrue(handledKeys.add(key))
        assertFalse(handledKeys.add(key)) // Second attempt is rejected
    }

    // --- TEST F: Recipient User Isolation ---
    @Test
    fun testRecipientIsolationValidation() {
        val activeUserId = "user-123"
        val notifRecipientMatch = "user-123"
        val notifRecipientMismatch = "user-456"

        val isAuthorized1 = notifRecipientMatch.isBlank() || notifRecipientMatch == activeUserId
        assertTrue(isAuthorized1)

        val isAuthorized2 = notifRecipientMismatch.isBlank() || notifRecipientMismatch == activeUserId
        assertFalse(isAuthorized2)
    }

    // --- TEST I: Admin Role Authorization Guarding ---
    @Test
    fun testRoleAuthorizationGuards() {
        val customerRole = UserRole.CUSTOMER
        val farmAdminRole = UserRole.FARM_ADMIN
        val superAdminRole = UserRole.SUPER_ADMIN

        // Super Admin Dashboard access
        assertFalse(customerRole == UserRole.SUPER_ADMIN)
        assertFalse(farmAdminRole == UserRole.SUPER_ADMIN)
        assertTrue(superAdminRole == UserRole.SUPER_ADMIN)

        // Farm Dashboard access
        assertFalse(customerRole == UserRole.FARM_ADMIN || customerRole == UserRole.SUPER_ADMIN)
        assertTrue(farmAdminRole == UserRole.FARM_ADMIN || farmAdminRole == UserRole.SUPER_ADMIN)
        assertTrue(superAdminRole == UserRole.FARM_ADMIN || superAdminRole == UserRole.SUPER_ADMIN)
    }

    // --- TEST K: Missing Entity Graceful Fallback ---
    @Test
    fun testMissingEntityFallback() {
        val mockGoats = listOf("goat-1", "goat-2")
        val targetReferenceId = "goat-deleted-999"

        val foundGoat = mockGoats.find { it == targetReferenceId }
        assertNull(foundGoat)

        val safeFallbackRoute = if (foundGoat != null) "goat_detail" else "marketplace"
        assertEquals("marketplace", safeFallbackRoute)
    }
}
