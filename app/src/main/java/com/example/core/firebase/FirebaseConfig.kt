package com.example.core.firebase

import android.content.Context
import android.util.Log
import com.example.core.notification.NotificationHelper
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.data.dto.currentIsoTimestamp
import com.example.data.dto.ensureValidUuid
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Firebase Configuration and Integration
 * Handles Firebase Cloud Messaging token retrieval, channels, and user token lifecycle.
 */
object FirebaseConfig {
    private const val TAG = "FirebaseConfig"

    var isInitialized: Boolean = false
        private set

    @Volatile
    var deviceToken: String? = null

    @Volatile
    var activeUserId: String? = null

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
                            val currentUserId = activeUserId
                            val token = deviceToken
                            if (!currentUserId.isNullOrBlank() && !token.isNullOrBlank()) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    registerTokenForUser(currentUserId, token)
                                }
                            }
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

    suspend fun registerTokenForUser(userId: String, token: String) {
        if (token.isBlank() || userId.isBlank()) return
        val validUserId = try { ensureValidUuid(userId) } catch (_: Exception) { return }

        activeUserId = validUserId
        deviceToken = token

        if (!SupabaseConfig.isConfigured) return

        try {
            // 1. Remove stale token from any other profile to avoid misdirection
            try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                    .update(buildJsonObject { put("fcm_token", null as String?) }) {
                        filter {
                            eq("fcm_token", token)
                            neq("id", validUserId)
                        }
                    }
            } catch (e: Exception) {
                Log.d(TAG, "Notice clearing duplicate FCM tokens: ${e.message}")
            }

            // 2. Associate token with active user profile
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .update(buildJsonObject { put("fcm_token", token) }) {
                    filter {
                        eq("id", validUserId)
                    }
                }

            // 3. Upsert into user_fcm_tokens table if present
            try {
                SupabaseModule.client.postgrest["user_fcm_tokens"]
                    .upsert(buildJsonObject {
                        put("user_id", validUserId)
                        put("fcm_token", token)
                        put("updated_at", currentIsoTimestamp())
                    })
            } catch (_: Exception) {}

            Log.i(TAG, "Registered FCM token for user $validUserId")
        } catch (e: Exception) {
            Log.w(TAG, "Notice registering FCM token: ${e.message}")
        }
    }

    suspend fun unregisterTokenForUser(userId: String) {
        if (userId.isBlank()) return
        val validUserId = try { ensureValidUuid(userId) } catch (_: Exception) { return }

        val tokenToClear = deviceToken

        if (activeUserId == validUserId) {
            activeUserId = null
        }

        if (!SupabaseConfig.isConfigured) return

        try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .update(buildJsonObject { put("fcm_token", null as String?) }) {
                    filter {
                        eq("id", validUserId)
                    }
                }

            if (!tokenToClear.isNullOrBlank()) {
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .update(buildJsonObject { put("fcm_token", null as String?) }) {
                            filter {
                                eq("fcm_token", tokenToClear)
                            }
                        }
                } catch (_: Exception) {}
            }

            try {
                SupabaseModule.client.postgrest["user_fcm_tokens"]
                    .delete {
                        filter {
                            eq("user_id", validUserId)
                        }
                    }
            } catch (_: Exception) {}

            Log.i(TAG, "Unregistered FCM token for user $validUserId")
        } catch (e: Exception) {
            Log.w(TAG, "Notice unregistering FCM token: ${e.message}")
        }
    }
}

