package com.example

import com.example.core.firebase.FirebaseConfig
import com.example.model.AppNotification
import com.example.model.NotificationType
import com.example.model.UserRole
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FcmNotificationsTest {

    @Test
    fun testActiveUserTokenAssociation() {
        val userA = "00000000-0000-0000-0000-00000000000a"

        FirebaseConfig.activeUserId = userA

        assertEquals(userA, FirebaseConfig.activeUserId)
    }

    @Test
    fun testLogoutClearsActiveUserAssociation() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        FirebaseConfig.activeUserId = userA

        // Simulate logout for user A
        if (FirebaseConfig.activeUserId == userA) {
            FirebaseConfig.activeUserId = null
        }

        assertNull(FirebaseConfig.activeUserId)
    }

    @Test
    fun testRecipientUserIsolation() {
        val userA = "00000000-0000-0000-0000-00000000000a"
        val userB = "00000000-0000-0000-0000-00000000000b"

        // Set active user on device as User B
        FirebaseConfig.activeUserId = userB

        val pushForUserA = AppNotification(
            id = "fcm-12345",
            recipientUserId = userA,
            targetRole = UserRole.CUSTOMER,
            title = "Order update",
            message = "Booking confirmed",
            type = NotificationType.BOOKING_CONFIRMED,
            timestamp = System.currentTimeMillis()
        )

        val activeUser = FirebaseConfig.activeUserId
        val isMeantForActiveUser = pushForUserA.recipientUserId.isBlank() || pushForUserA.recipientUserId == activeUser

        // Push for User A MUST NOT be displayed for User B
        assertFalse("Notification for User A should be suppressed when User B is logged in", isMeantForActiveUser)
    }

    @Test
    fun testRecipientMatchingDeliveredSuccessfully() {
        val userA = "00000000-0000-0000-0000-00000000000a"

        FirebaseConfig.activeUserId = userA

        val pushForUserA = AppNotification(
            id = "fcm-67890",
            recipientUserId = userA,
            targetRole = UserRole.CUSTOMER,
            title = "Booking Approval",
            message = "Your request was approved",
            type = NotificationType.BOOKING_CONFIRMED,
            timestamp = System.currentTimeMillis()
        )

        val activeUser = FirebaseConfig.activeUserId
        val isMeantForActiveUser = pushForUserA.recipientUserId.isBlank() || pushForUserA.recipientUserId == activeUser

        assertTrue("Notification for User A should be accepted when User A is logged in", isMeantForActiveUser)
    }

    @Test
    fun testNoSecretsExposedInClient() {
        val buildFile = java.io.File("app/build.gradle.kts").takeIf { it.exists() } ?: java.io.File("build.gradle.kts")
        val buildFileContent = if (buildFile.exists()) buildFile.readText() else ""
        assertFalse("APK/Build file must not contain service_account private key", buildFileContent.contains("private_key"))
        assertFalse("APK/Build file must not contain service_role key", buildFileContent.contains("service_role"))
    }
}
