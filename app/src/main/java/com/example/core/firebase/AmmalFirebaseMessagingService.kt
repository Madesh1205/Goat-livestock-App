package com.example.core.firebase

import android.util.Log
import com.example.AmmalFarmApplication
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
        // Store or synchronize token with backend/Supabase profiles
        FirebaseConfig.deviceToken = token
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "From: ${remoteMessage.from}")

        // 1. Extract data payload or notification payload
        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "Ammal Farm Notification"
        val message = data["message"] ?: data["body"] ?: remoteMessage.notification?.body ?: ""
        val typeStr = data["type"] ?: "SYSTEM_ALERT"
        val roleStr = data["target_role"] ?: "CUSTOMER"
        val route = data["deep_link_route"]
        val referenceId = data["reference_id"]
        val recipientUserId = data["recipient_user_id"] ?: ""

        val type = try {
            NotificationType.valueOf(typeStr.uppercase())
        } catch (_: Exception) {
            NotificationType.SYSTEM_ALERT
        }

        val targetRole = try {
            UserRole.valueOf(roleStr.uppercase())
        } catch (_: Exception) {
            UserRole.CUSTOMER
        }

        val appNotification = AppNotification(
            id = "fcm-${UUID.randomUUID().toString().take(8)}",
            recipientUserId = recipientUserId,
            targetRole = targetRole,
            title = title,
            message = message,
            type = type,
            timestamp = System.currentTimeMillis(),
            isRead = false,
            referenceId = referenceId,
            deepLinkRoute = route
        )

        // 2. Show native system notification tray banner
        NotificationHelper.showSystemNotification(applicationContext, appNotification)

        // 3. Persist into local In-App Notification Center / Supabase repository so user can view history
        try {
            val app = applicationContext as? AmmalFarmApplication
            app?.container?.marketplaceRepository?.let { repo ->
                serviceScope.launch {
                    try {
                        repo.sendNotification(appNotification)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist FCM notification into repo", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error recording notification to repository", e)
        }
    }
}
