package com.example.util

import com.example.model.NotificationType

data class NotificationDeepLinkPayload(
    val notificationId: String? = null,
    val route: String? = null,
    val referenceId: String? = null,
    val notificationType: NotificationType? = null,
    val recipientUserId: String? = null,
    val intentKey: String = ""
)

object DeepLinkUtils {

    fun resolveDeepLinkRoute(type: NotificationType, referenceId: String?): String {
        return when (type) {
            NotificationType.BOOKING_CREATED,
            NotificationType.BOOKING_CONFIRMED,
            NotificationType.BOOKING_REJECTED,
            NotificationType.BOOKING_CANCELLED -> "my_bookings"

            NotificationType.RESERVATION_EXPIRY -> {
                if (!referenceId.isNullOrBlank() && !referenceId.startsWith("booking-")) {
                    "goat_detail"
                } else {
                    "my_bookings"
                }
            }

            NotificationType.LISTING_UPDATE -> {
                if (!referenceId.isNullOrBlank()) "goat_detail" else "marketplace"
            }

            NotificationType.NEW_BOOKING,
            NotificationType.BOOKING_CANCELLATION,
            NotificationType.LISTING_PAYMENT_SUCCESS,
            NotificationType.LISTING_PAYMENT_FAILED,
            NotificationType.LISTING_APPROVED,
            NotificationType.LISTING_REJECTED -> "farm_dashboard"

            NotificationType.NEW_FARM_APPLICATION,
            NotificationType.NEW_LISTING_PENDING,
            NotificationType.NEW_PAYMENT_RECEIVED,
            NotificationType.NEW_REPORT_SUBMITTED -> "super_admin_dashboard"

            NotificationType.SYSTEM_ALERT -> "notifications"
        }
    }
}
