package com.example.core.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.model.AppNotification
import com.example.model.NotificationType
import com.example.model.UserRole
import java.util.concurrent.atomic.AtomicInteger

object NotificationHelper {

    const val CHANNEL_ID_GENERAL = "ammal_channel_general"
    const val CHANNEL_ID_BOOKINGS = "ammal_channel_bookings"
    const val CHANNEL_ID_ADMIN = "ammal_channel_admin"

    const val EXTRA_DEEP_LINK_ROUTE = "extra_deep_link_route"
    const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

    private val notificationCounter = AtomicInteger(1000)

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val generalChannel = NotificationChannel(
                CHANNEL_ID_GENERAL,
                "Marketplace & Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "General livestock marketplace news, listings, and updates"
                enableVibration(true)
            }

            val bookingsChannel = NotificationChannel(
                CHANNEL_ID_BOOKINGS,
                "Bookings & Holds",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time updates regarding livestock bookings, holds, and approvals"
                enableVibration(true)
            }

            val adminChannel = NotificationChannel(
                CHANNEL_ID_ADMIN,
                "Farm & Super Admin Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Administrative alerts, listing fees, approvals, and reports"
                enableVibration(true)
            }

            notificationManager.createNotificationChannels(listOf(generalChannel, bookingsChannel, adminChannel))
        }
    }

    fun showSystemNotification(context: Context, notification: AppNotification) {
        val channelId = when (notification.type) {
            NotificationType.BOOKING_CREATED,
            NotificationType.BOOKING_CONFIRMED,
            NotificationType.BOOKING_REJECTED,
            NotificationType.BOOKING_CANCELLED,
            NotificationType.RESERVATION_EXPIRY,
            NotificationType.NEW_BOOKING,
            NotificationType.BOOKING_CANCELLATION -> CHANNEL_ID_BOOKINGS

            NotificationType.NEW_FARM_APPLICATION,
            NotificationType.NEW_LISTING_PENDING,
            NotificationType.NEW_PAYMENT_RECEIVED,
            NotificationType.NEW_REPORT_SUBMITTED,
            NotificationType.LISTING_PAYMENT_SUCCESS,
            NotificationType.LISTING_PAYMENT_FAILED,
            NotificationType.LISTING_APPROVED,
            NotificationType.LISTING_REJECTED -> CHANNEL_ID_ADMIN

            else -> CHANNEL_ID_GENERAL
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DEEP_LINK_ROUTE, notification.deepLinkRoute)
            putExtra(EXTRA_NOTIFICATION_ID, notification.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notification.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationId = notificationCounter.incrementAndGet()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(notification.title)
            .setContentText(notification.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.message))
            .setPriority(
                if (channelId == CHANNEL_ID_BOOKINGS || channelId == CHANNEL_ID_ADMIN)
                    NotificationCompat.PRIORITY_HIGH
                else
                    NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val notificationManagerCompat = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                    notificationManagerCompat.notify(notificationId, builder.build())
                }
            } else {
                notificationManagerCompat.notify(notificationId, builder.build())
            }
        } catch (_: Exception) {
            // Graceful fallback for restricted sandbox
        }
    }
}
