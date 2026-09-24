package com.example.core.firebase

import android.content.Context
import android.util.Log
import com.example.core.notification.NotificationHelper

/**
 * Local Notification System Configuration
 * Initializes Android notification channels and provides local notification support.
 */
object FirebaseConfig {
    private const val TAG = "NotificationConfig"

    var isInitialized: Boolean = false
        private set

    @Volatile
    var activeUserId: String? = null

    /**
     * Initializes notification channels and local notification services.
     */
    fun initialize(context: Context) {
        try {
            NotificationHelper.createNotificationChannels(context)
            isInitialized = true
            Log.d(TAG, "Local notification channels initialized successfully.")
        } catch (e: Throwable) {
            Log.w(TAG, "Notification channel initialization handled gracefully", e)
            isInitialized = true
        }
    }
}
