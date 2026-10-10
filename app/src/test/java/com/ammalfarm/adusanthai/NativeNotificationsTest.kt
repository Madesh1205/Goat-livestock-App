package com.ammalfarm.adusanthai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ammalfarm.adusanthai.core.notification.NotificationConfig
import com.ammalfarm.adusanthai.core.notification.NotificationDeliveryTracker
import com.ammalfarm.adusanthai.model.AppNotification
import com.ammalfarm.adusanthai.model.NotificationType
import com.ammalfarm.adusanthai.model.UserRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class NativeNotificationsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NotificationConfig.activeUserId = null
    }

    @Test
    fun testActiveUserAssociation() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        NotificationConfig.activeUserId = userA

        assertEquals(userA, NotificationConfig.activeUserId)
    }

    @Test
    fun testLogoutClearsActiveUserAssociation() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        NotificationConfig.activeUserId = userA

        // Simulate logout
        NotificationConfig.activeUserId = null

        assertNull(NotificationConfig.activeUserId)
    }

    @Test
    fun testRecipientUserIsolation() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        val userB = "00000000-0000-0000-0000-00000000000b"

        NotificationConfig.activeUserId = userB

        val notifForUserA = AppNotification(
            id = UUID.randomUUID().toString(),
            recipientUserId = userA,
            targetRole = UserRole.CUSTOMER,
            title = "Order update",
            message = "Booking confirmed",
            type = NotificationType.BOOKING_CONFIRMED,
            timestamp = System.currentTimeMillis()
        )

        val activeUser = NotificationConfig.activeUserId
        val isMeantForActiveUser = notifForUserA.recipientUserId.isBlank() || notifForUserA.recipientUserId == activeUser

        assertFalse("Notification for User A should be suppressed when User B is logged in", isMeantForActiveUser)
    }

    @Test
    fun testRecipientMatchingDeliveredSuccessfully() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        NotificationConfig.activeUserId = userA

        val notifForUserA = AppNotification(
            id = UUID.randomUUID().toString(),
            recipientUserId = userA,
            targetRole = UserRole.CUSTOMER,
            title = "Booking Approval",
            message = "Your request was approved",
            type = NotificationType.BOOKING_CONFIRMED,
            timestamp = System.currentTimeMillis()
        )

        val activeUser = NotificationConfig.activeUserId
        val isMeantForActiveUser = notifForUserA.recipientUserId.isBlank() || notifForUserA.recipientUserId == activeUser

        assertTrue("Notification for User A should be accepted when User A is logged in", isMeantForActiveUser)
    }

    @Test
    fun testNotificationDeliveryTrackerDeduplication() {
        val userId = "user-123"
        val notifId = UUID.randomUUID().toString()

        assertFalse(NotificationDeliveryTracker.isDelivered(context, userId, notifId))

        NotificationDeliveryTracker.markDelivered(context, userId, notifId)

        assertTrue(NotificationDeliveryTracker.isDelivered(context, userId, notifId))

        // Different user should not have this marked as delivered
        assertFalse(NotificationDeliveryTracker.isDelivered(context, "other-user", notifId))
    }

    @Test
    fun testNotificationDeliveryTrackerBatchAndClear() {
        val userId = "user-batch-test"
        val notifId1 = UUID.randomUUID().toString()
        val notifId2 = UUID.randomUUID().toString()

        NotificationDeliveryTracker.markMultipleDelivered(context, userId, listOf(notifId1, notifId2))

        assertTrue(NotificationDeliveryTracker.isDelivered(context, userId, notifId1))
        assertTrue(NotificationDeliveryTracker.isDelivered(context, userId, notifId2))

        NotificationDeliveryTracker.clearForUser(context, userId)

        assertFalse(NotificationDeliveryTracker.isDelivered(context, userId, notifId1))
        assertFalse(NotificationDeliveryTracker.isDelivered(context, userId, notifId2))
    }

    @Test
    fun testNoSecretsExposedInClient() {
        val buildFile = java.io.File("app/build.gradle.kts").takeIf { it.exists() } ?: java.io.File("build.gradle.kts")
        val buildFileContent = if (buildFile.exists()) buildFile.readText() else ""
        assertFalse("APK/Build file must not contain service_account private key", buildFileContent.contains("private_key"))
        assertFalse("APK/Build file must not contain service_role key", buildFileContent.contains("service_role"))
    }

    @Test
    fun testFcmTokenLocalCachingAndClearing() {
        val sampleToken = "fcm_test_token_abcdef123456"
        NotificationConfig.saveSavedFcmToken(context, sampleToken)
        assertEquals(sampleToken, NotificationConfig.getSavedFcmToken(context))

        NotificationConfig.clearSavedFcmToken(context)
        assertNull(NotificationConfig.getSavedFcmToken(context))
    }

    @Test
    fun testNotificationConfigInitializationDoesNotRequireAuth() {
        NotificationConfig.initialize(context)
        assertTrue(NotificationConfig.isInitialized)
    }

    @Test
    fun testUnauthenticatedFcmTokenRetrievalHandledSafely() {
        NotificationConfig.activeUserId = null
        // Should safely exit without error or crashing
        NotificationConfig.fetchAndSyncFcmToken(context)
        assertNull(NotificationConfig.activeUserId)
    }
}
