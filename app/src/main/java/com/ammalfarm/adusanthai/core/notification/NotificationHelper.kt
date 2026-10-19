package com.ammalfarm.adusanthai.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ammalfarm.adusanthai.MainActivity
import com.ammalfarm.adusanthai.R

object NotificationHelper {
    const val CHANNEL_BOOKINGS = "ammal_channel_bookings"
    const val CHANNEL_ADMIN = "ammal_channel_admin"
    const val CHANNEL_GENERAL = "ammal_channel_general"
    private const val LEGACY_CHANNEL_ID = "adusanthai_general"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            // Channel 1: Bookings & Orders (High Importance)
            val bookingsChannel = NotificationChannel(
                CHANNEL_BOOKINGS,
                "Bookings & Orders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Updates on livestock reservations, confirmations, holds, and cancellations."
                enableVibration(true)
                setShowBadge(true)
            }

            // Channel 2: Farm & Admin Updates (High Importance)
            val adminChannel = NotificationChannel(
                CHANNEL_ADMIN,
                "Farm & Admin Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Farm approval statuses, goat listing moderations, and compliance alerts."
                enableVibration(true)
                setShowBadge(true)
            }

            // Channel 3: General Notices (Default Importance)
            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "General Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Marketplace announcements, general notifications, and tips."
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(bookingsChannel, adminChannel, generalChannel))
        }
    }

    /**
     * Map notification link_type to the appropriate notification channel.
     */
    fun selectChannelForLinkType(linkType: String?): String {
        val type = linkType?.trim()?.uppercase().orEmpty()
        return when {
            type.contains("BOOKING") || type.contains("HOLD") || type.contains("RESERVATION") -> CHANNEL_BOOKINGS
            type.contains("ADMIN") || type.contains("FARM") || type.contains("GOAT") ||
            type.contains("MODERATION") || type.contains("REPORT") || type.contains("PAYMENT") -> CHANNEL_ADMIN
            else -> CHANNEL_GENERAL
        }
    }

    /**
     * Display a system notification tray alert with backstack intent.
     */
    fun showNotification(
        context: Context,
        title: String,
        message: String,
        channelId: String = CHANNEL_GENERAL,
        notificationId: String? = null,
        linkType: String? = null,
        linkId: String? = null,
        recipientUserId: String? = null,
        overrideNumericId: Int? = null
    ) {
        createNotificationChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_notification_id", notificationId)
            putExtra("extra_link_type", linkType)
            putExtra("extra_link_id", linkId)
            putExtra("extra_recipient_user_id", recipientUserId)
        }

        val requestCode = if (!notificationId.isNullOrBlank()) {
            notificationId.hashCode()
        } else {
            System.currentTimeMillis().toInt()
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isHighPriority = channelId == CHANNEL_BOOKINGS || channelId == CHANNEL_ADMIN

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_small)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(if (isHighPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val numericId = overrideNumericId ?: requestCode
        notificationManager?.notify(numericId, builder.build())
    }

    /**
     * Legacy backward-compatible signature for existing callers.
     */
    fun showNotification(context: Context, title: String, message: String, notificationId: Int) {
        showNotification(
            context = context,
            title = title,
            message = message,
            channelId = CHANNEL_GENERAL,
            overrideNumericId = notificationId
        )
    }
}
