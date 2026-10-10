package com.ammalfarm.adusanthai.core.notification

import android.util.Log
import com.ammalfarm.adusanthai.AmmalFarmApplication
import com.ammalfarm.adusanthai.model.AppNotification
import com.ammalfarm.adusanthai.model.NotificationType
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Single FirebaseMessagingService to handle incoming FCM push messages and token refreshes.
 * Deduplicates incoming notifications across FCM, WorkManager polling, and Supabase Realtime.
 */
class AmmalFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AmmalFCMService"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val maskedToken = if (token.length > 8) "${token.take(6)}...${token.takeLast(2)}" else "***"
        Log.d(TAG, "FCM registration token refreshed (masked: $maskedToken)")
        NotificationConfig.saveSavedFcmToken(applicationContext, token)
        syncFcmTokenToSupabase(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "FCM message received from: ${remoteMessage.from}")

        try {
            val data = remoteMessage.data
            val notificationPayload = remoteMessage.notification
            val notifId = data["id"] ?: data["notification_id"] ?: UUID.randomUUID().toString()
            val title = notificationPayload?.title ?: data["title"] ?: "Adu Santhai Update"
            val body = notificationPayload?.body ?: data["message"] ?: data["body"] ?: ""
            val typeStr = data["type"] ?: data["notification_type"] ?: NotificationType.SYSTEM_ALERT.name
            val referenceId = data["reference_id"] ?: data["referenceId"] ?: ""
            val deepLinkRoute = data["deep_link_route"] ?: data["deepLinkRoute"]
            val recipientUserId = data["recipient_user_id"] ?: data["user_id"] ?: ""
            val activeUserId = NotificationConfig.activeUserId ?: recipientUserId

            if (activeUserId.isNotBlank() && notifId.isNotBlank() &&
                NotificationDeliveryTracker.isDelivered(applicationContext, activeUserId, notifId)
            ) {
                Log.d(TAG, "Notification $notifId already delivered locally; skipping duplicate FCM display.")
                return
            }

            val type = try {
                NotificationType.valueOf(typeStr)
            } catch (_: Exception) {
                NotificationType.SYSTEM_ALERT
            }

            val appNotification = AppNotification(
                id = notifId,
                recipientUserId = recipientUserId,
                title = title,
                message = body,
                type = type,
                referenceId = referenceId,
                deepLinkRoute = deepLinkRoute,
                isRead = false,
                timestamp = System.currentTimeMillis()
            )

            val displayed = NotificationHelper.showSystemNotification(applicationContext, appNotification)
            if (displayed && activeUserId.isNotBlank() && notifId.isNotBlank()) {
                NotificationDeliveryTracker.markDelivered(applicationContext, activeUserId, notifId)
            } else if (!displayed) {
                Log.w(TAG, "System notification $notifId was not displayed; it remains eligible for retry through notification sync.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming FCM message: ${e.message}", e)
        }
    }

    private fun syncFcmTokenToSupabase(token: String) {
        serviceScope.launch {
            try {
                val currentSessionUser = try {
                    com.ammalfarm.adusanthai.core.supabase.SupabaseModule.auth.currentUserOrNull()
                } catch (_: Throwable) {
                    null
                }
                if (currentSessionUser == null && NotificationConfig.activeUserId.isNullOrBlank()) {
                    Log.d(TAG, "No authenticated Supabase session on token refresh; token cached locally and will synchronize after sign-in.")
                    return@launch
                }
                val app = applicationContext as? AmmalFarmApplication ?: AmmalFarmApplication.instance
                app.container.marketplaceRepository.registerFcmDeviceToken(
                    token,
                    NotificationConfig.getDeviceId(applicationContext)
                )
                Log.d(TAG, "Triggered RPC registration for refreshed FCM device token.")
            } catch (e: Exception) {
                Log.w(TAG, "Deferred FCM token registration sync: ${e.message}")
            }
        }
    }
}
