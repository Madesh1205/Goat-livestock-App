package com.example.core.firebase

import android.util.Log
import com.example.core.notification.NotificationHelper
import com.example.model.AppNotification
import com.example.model.NotificationType
import com.example.model.UserRole
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class AmmalFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private val TAG = "AmmalFCMService"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New Firebase Messaging Token registered: $token")
        FirebaseConfig.deviceToken = token
        val userId = FirebaseConfig.activeUserId
        if (!userId.isNullOrBlank()) {
            serviceScope.launch {
                FirebaseConfig.registerTokenForUser(userId, token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        // 1. Extract data payload or notification payload
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "Adu Santhai Notification"
        val message = data["message"] ?: data["body"] ?: remoteMessage.notification?.body ?: ""
        val typeStr = data["type"] ?: "SYSTEM_ALERT"
        val roleStr = data["target_role"] ?: "CUSTOMER"
        val referenceId = data["reference_id"]
        val recipientUserId = data["recipient_user_id"] ?: ""

        val type = try {
            NotificationType.valueOf(typeStr.uppercase())
        } catch (_: Exception) {
            NotificationType.SYSTEM_ALERT
        }

        val route = data["deep_link_route"]?.takeIf { it.isNotBlank() }
            ?: com.example.util.DeepLinkUtils.resolveDeepLinkRoute(type, referenceId)

        val targetRole = try {
            UserRole.valueOf(roleStr.uppercase())
        } catch (_: Exception) {
            UserRole.CUSTOMER
        }

        // 2. Recipient isolation check
        val activeUserId = FirebaseConfig.activeUserId
        if (recipientUserId.isNotBlank() && !activeUserId.isNullOrBlank() && activeUserId != recipientUserId) {
            Log.w(TAG, "Suppressing FCM notification meant for $recipientUserId (active user is $activeUserId)")
            return
        }

        val notificationId = data["id"] ?: "fcm-${UUID.randomUUID().toString().take(8)}"

        val appNotification = AppNotification(
            id = notificationId,
            recipientUserId = recipientUserId.ifBlank { activeUserId ?: "" },
            targetRole = targetRole,
            title = title,
            message = message,
            type = type,
            timestamp = System.currentTimeMillis(),
            isRead = false,
            referenceId = referenceId,
            deepLinkRoute = route
        )

        // 3. Show native system notification tray banner (works in background, closed app, and foreground)
        NotificationHelper.showSystemNotification(applicationContext, appNotification)

        // Note: Stage 8A Supabase Realtime subscription handles in-app state flow and deduplication.
        // We do NOT write to repo or database here to avoid duplicate entries or duplicate UI items.
    }
}

