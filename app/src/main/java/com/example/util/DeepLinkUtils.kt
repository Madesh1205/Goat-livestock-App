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

    /**
     * Normalizes a database `link_type` string, reference ID, and title/body text
     * into a strongly typed [NotificationType].
     */
    fun normalizeNotificationType(
        linkType: String?,
        referenceId: String? = null,
        title: String? = null,
        body: String? = null
    ): NotificationType {
        if (!linkType.isNullOrBlank()) {
            val clean = linkType.trim().uppercase()

            // 1. Direct match with enum names
            try {
                return NotificationType.valueOf(clean)
            } catch (_: IllegalArgumentException) {}

            // 2. Database alias mapping
            when {
                clean == "BOOKING" || clean == "BOOKINGS" || clean == "MY_BOOKINGS" || clean == "BOOKING_HOLD" || clean == "HOLD" || clean == "ORDER" -> {
                    val combinedText = "${title.orEmpty()} ${body.orEmpty()}".lowercase()
                    return when {
                        combinedText.contains("cancel") || combinedText.contains("cancelled") -> NotificationType.BOOKING_CANCELLED
                        combinedText.contains("reject") || combinedText.contains("rejected") -> NotificationType.BOOKING_REJECTED
                        combinedText.contains("expire") || combinedText.contains("expired") -> NotificationType.RESERVATION_EXPIRY
                        combinedText.contains("new booking") || combinedText.contains("incoming") -> NotificationType.NEW_BOOKING
                        else -> NotificationType.BOOKING_CONFIRMED
                    }
                }
                clean == "GOAT" || clean == "GOATS" || clean == "GOAT_DETAIL" || clean == "LISTING" || clean == "LISTINGS" || clean == "MARKETPLACE" || clean == "LIVESTOCK" -> {
                    return NotificationType.LISTING_UPDATE
                }
                clean == "FARM" || clean == "FARMS" || clean == "FARM_DASHBOARD" || clean == "FARM_PORTAL" || clean == "FARM_BOOKINGS" -> {
                    return NotificationType.NEW_BOOKING
                }
                clean == "PAYMENT" || clean == "PAYMENTS" || clean == "TRANSACTION" -> {
                    val combinedText = "${title.orEmpty()} ${body.orEmpty()}".lowercase()
                    return if (combinedText.contains("fail") || combinedText.contains("declined")) {
                        NotificationType.LISTING_PAYMENT_FAILED
                    } else {
                        NotificationType.LISTING_PAYMENT_SUCCESS
                    }
                }
                clean == "REPORT" || clean == "REPORTS" || clean == "SAFETY_REPORT" -> {
                    return NotificationType.NEW_REPORT_SUBMITTED
                }
                clean == "ADMIN" || clean == "SUPER_ADMIN" || clean == "VERIFICATION" -> {
                    return NotificationType.NEW_FARM_APPLICATION
                }
                clean.contains("BOOKING") && clean.contains("CONFIRM") -> return NotificationType.BOOKING_CONFIRMED
                clean.contains("BOOKING") && clean.contains("CANCEL") -> return NotificationType.BOOKING_CANCELLED
                clean.contains("BOOKING") && clean.contains("REJECT") -> return NotificationType.BOOKING_REJECTED
                clean.contains("BOOKING") && clean.contains("NEW") -> return NotificationType.NEW_BOOKING
                clean.contains("LISTING") && clean.contains("APPROV") -> return NotificationType.LISTING_APPROVED
                clean.contains("LISTING") && clean.contains("REJECT") -> return NotificationType.LISTING_REJECTED
                clean.contains("LISTING") -> return NotificationType.LISTING_UPDATE
            }
        }

        // 3. Heuristics from referenceId prefix
        if (!referenceId.isNullOrBlank()) {
            val ref = referenceId.trim().lowercase()
            when {
                ref.startsWith("goat") -> return NotificationType.LISTING_UPDATE
                ref.startsWith("farm") -> return NotificationType.NEW_BOOKING
                ref.startsWith("booking") -> return NotificationType.BOOKING_CONFIRMED
            }
        }

        // 4. Heuristics from notification text
        val combined = "${title.orEmpty()} ${body.orEmpty()}".lowercase()
        if (combined.contains("booking") || combined.contains("reserve") || combined.contains("hold")) {
            return if (combined.contains("cancel")) NotificationType.BOOKING_CANCELLED else NotificationType.BOOKING_CONFIRMED
        }
        if (combined.contains("goat") || combined.contains("livestock") || combined.contains("listing")) {
            return NotificationType.LISTING_UPDATE
        }

        return NotificationType.SYSTEM_ALERT
    }

    /**
     * Resolves the navigation route for a notification based on its type and reference ID.
     */
    fun resolveDeepLinkRoute(type: NotificationType, referenceId: String?): String {
        return when (type) {
            NotificationType.BOOKING_CREATED,
            NotificationType.BOOKING_CONFIRMED,
            NotificationType.BOOKING_REJECTED,
            NotificationType.BOOKING_CANCELLED -> "my_bookings"

            NotificationType.RESERVATION_EXPIRY -> {
                if (!referenceId.isNullOrBlank() && !referenceId.startsWith("booking-", ignoreCase = true)) {
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
            NotificationType.LISTING_REJECTED,
            NotificationType.FARM_APPROVED,
            NotificationType.FARM_APPROVAL_PAYMENT_CONFIRMED,
            NotificationType.LISTING_QUOTA_INCREASED,
            NotificationType.RECEIPT_AVAILABLE -> "farm_dashboard"

            NotificationType.NEW_FARM_APPLICATION,
            NotificationType.NEW_LISTING_PENDING,
            NotificationType.NEW_PAYMENT_RECEIVED,
            NotificationType.NEW_REPORT_SUBMITTED -> "super_admin_dashboard"

            NotificationType.SYSTEM_ALERT -> {
                if (!referenceId.isNullOrBlank()) {
                    val ref = referenceId.trim().lowercase()
                    when {
                        ref.startsWith("goat") -> "goat_detail"
                        ref.startsWith("farm") -> "farm_detail"
                        ref.startsWith("booking") -> "my_bookings"
                        else -> "notifications"
                    }
                } else {
                    "notifications"
                }
            }
        }
    }
}
