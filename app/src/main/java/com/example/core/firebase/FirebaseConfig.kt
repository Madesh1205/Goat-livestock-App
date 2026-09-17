package com.example.core.firebase

import android.content.Context
import android.util.Log
import com.example.core.notification.NotificationHelper
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Firebase Configuration and Integration
 * Handles Firebase Cloud Messaging token retrieval, channels, and lifecycle.
 */
object FirebaseConfig {
    private const val TAG = "FirebaseConfig"

    var isInitialized: Boolean = false
        private set

    var deviceToken: String? = null

    /**
     * Initializes Firebase services and FCM notification channels.
     */
    fun initialize(context: Context) {
        try {
            NotificationHelper.createNotificationChannels(context)

            val apps = try { FirebaseApp.getApps(context) } catch (_: Throwable) { emptyList() }
            if (apps.isNotEmpty()) {
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            deviceToken = task.result
                            Log.d(TAG, "FCM Token initialized: $deviceToken")
                        } else {
                            Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "FirebaseMessaging token retrieval deferred", e)
                }
            } else {
                Log.d(TAG, "No active FirebaseApp detected; local notification channels active")
            }

            isInitialized = true
            Log.d(TAG, "Notification system initialized.")
        } catch (e: Throwable) {
            Log.w(TAG, "Notification init handled gracefully", e)
            try {
                NotificationHelper.createNotificationChannels(context)
            } catch (_: Throwable) {}
            isInitialized = true
        }
    }
}
