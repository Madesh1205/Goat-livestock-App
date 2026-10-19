package com.ammalfarm.adusanthai.core.notification

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Service that handles Firebase Cloud Messaging push notifications.
 *
 * Handles:
 * 1. Token generation and rotation via [onNewToken].
 * 2. Downstream message delivery via [onMessageReceived], supporting both:
 *    - Notification-plus-data messages (system displays notification tray when in background;
 *      in foreground onMessageReceived handles custom display).
 *    - Data-only high-priority messages (always handled in onMessageReceived regardless of app state).
 * 3. Deduplication prevention using [NotificationDeliveryTracker].
 * 4. User recipient validation so private notifications are never shown across account switches.
 * 5. Channel selection based on event link_type.
 *
 * Android System Lifecycle Considerations:
 * - App Backgrounded / Screen Off: High-priority FCM messages wake the network and display
 *   notifications immediately through notification channels.
 * - App Swiped from Recents: Firebase Cloud Messaging continues delivering notifications via
 *   Google Play Services.
 * - Force Stop: If the user explicitly selects "Force Stop" in Android App Info settings, the OS
 *   marks the app as STOPPED (FLAG_EXCLUDE_STOPPED_PACKAGES). Android does not allow any broadcast
 *   or push message to start a stopped app until the user explicitly opens it again.
 * - OEM Battery Optimizations: Devices with aggressive battery management (e.g. Doze mode, Samsung
 *   App Sleep) may delay normal-priority messages; all critical booking and moderation notifications
 *   are dispatched with FCM priority "HIGH".
 */
class AduSanthaiFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "AduSanthaiFCM"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM registration token received: ${token.take(6)}...${token.takeLast(4)}")
        NotificationConfig.saveSavedFcmToken(applicationContext, token)

        val activeUid = NotificationConfig.getActiveUserId(applicationContext)
        if (!activeUid.isNullOrBlank()) {
            serviceScope.launch {
                NotificationConfig.syncTokenToSupabase(applicationContext, activeUid, token)
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val data = remoteMessage.data
        val notification = remoteMessage.notification

        // Extract payload with safe fallbacks
        val title = notification?.title
            ?: data["title"]
            ?: "Adu Santhai"

        val body = notification?.body
            ?: data["message"]
            ?: data["body"]
            ?: ""

        if (body.isBlank()) {
            Log.d(TAG, "FCM message with blank body received, skipping tray display")
            return
        }

        val notificationId = data["notification_id"]
            ?: data["id"]
            ?: ""

        val linkType = data["link_type"]
            ?: ""

        val linkId = data["reference_id"]
            ?: data["link_id"]
            ?: ""

        val recipientUserId = data["recipient_user_id"]
            ?: data["user_id"]
            ?: ""

        val currentUserId = NotificationConfig.getActiveUserId(applicationContext)

        // Strict recipient isolation: never display another user's push notification
        if (recipientUserId.isNotBlank() && !currentUserId.isNullOrBlank() && recipientUserId != currentUserId) {
            Log.w(TAG, "Ignoring notification meant for recipient $recipientUserId (current: $currentUserId)")
            return
        }

        // Deduplication guard: do not re-show already delivered notification
        val trackingUser = if (!currentUserId.isNullOrBlank()) currentUserId else "device_local"
        if (notificationId.isNotBlank() && NotificationDeliveryTracker.isDelivered(applicationContext, trackingUser, notificationId)) {
            Log.d(TAG, "Duplicate notification $notificationId already displayed on this device, skipping")
            return
        }

        if (notificationId.isNotBlank()) {
            NotificationDeliveryTracker.markDelivered(applicationContext, trackingUser, notificationId)
        }

        // Select the appropriate notification channel
        val channelId = NotificationHelper.selectChannelForLinkType(linkType)

        // Display the notification in Android notification shade
        NotificationHelper.showNotification(
            context = applicationContext,
            title = title,
            message = body,
            channelId = channelId,
            notificationId = notificationId,
            linkType = linkType,
            linkId = linkId,
            recipientUserId = recipientUserId
        )

        // Trigger in-app state refresh if user is currently active
        if (!currentUserId.isNullOrBlank()) {
            serviceScope.launch {
                try {
                    val app = applicationContext as? com.ammalfarm.adusanthai.AmmalFarmApplication
                    app?.container?.marketplaceRepository?.refreshNotifications(currentUserId)
                } catch (e: Exception) {
                    Log.w(TAG, "Notice refreshing in-app notifications on FCM receive: ${e.message}")
                }
            }
        }
    }
}
