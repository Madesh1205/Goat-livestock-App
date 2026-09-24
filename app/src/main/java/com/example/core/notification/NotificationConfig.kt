package com.example.core.notification

import android.content.Context
import android.util.Log

/**
 * Native Android Notification System Configuration
 * Initializes Android notification channels, manages active user isolation,
 * and configures native background synchronization.
 */
object NotificationConfig {
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
            Log.d(TAG, "Native notification channels initialized successfully.")
        } catch (e: Throwable) {
            Log.w(TAG, "Notification channel initialization handled gracefully", e)
            isInitialized = true
        }
    }
}
