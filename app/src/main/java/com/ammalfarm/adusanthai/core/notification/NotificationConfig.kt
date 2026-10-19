package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.google.firebase.messaging.FirebaseMessaging
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object NotificationConfig {
    private const val TAG = "NotificationConfig"
    private const val PREFS_NAME = "notification_config_prefs"
    private const val KEY_FCM_TOKEN = "saved_fcm_token"
    private const val KEY_ACTIVE_USER_ID = "active_user_id"

    private val configScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var isInitialized: Boolean = false
        private set

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun initialize(context: Context) {
        NotificationHelper.createNotificationChannels(context)
        isInitialized = true
    }

    fun getActiveUserId(context: Context): String? {
        return getPrefs(context).getString(KEY_ACTIVE_USER_ID, null)
    }

    fun setActiveUser(context: Context, userId: String?) {
        val editor = getPrefs(context).edit()
        if (userId.isNullOrBlank()) {
            editor.remove(KEY_ACTIVE_USER_ID)
        } else {
            editor.putString(KEY_ACTIVE_USER_ID, userId)
        }
        editor.apply()

        if (!userId.isNullOrBlank()) {
            fetchAndSyncFcmToken(context, userId)
        }
    }

    fun saveSavedFcmToken(context: Context, token: String) {
        if (token.isNotBlank()) {
            getPrefs(context).edit().putString(KEY_FCM_TOKEN, token.trim()).apply()
        }
    }

    fun getSavedFcmToken(context: Context): String? {
        return getPrefs(context).getString(KEY_FCM_TOKEN, null)
    }

    fun clearSavedFcmToken(context: Context) {
        getPrefs(context).edit().remove(KEY_FCM_TOKEN).apply()
    }

    /**
     * Asynchronously fetches the current FCM registration token and synchronizes it to Supabase.
     */
    fun fetchAndSyncFcmToken(context: Context, userId: String? = getActiveUserId(context)) {
        val targetUid = userId ?: getActiveUserId(context) ?: return
        configScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                if (!token.isNullOrBlank()) {
                    saveSavedFcmToken(context, token)
                    syncTokenToSupabase(context, targetUid, token)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Notice fetching Firebase FCM token: ${e.message}")
            }
        }
    }

    /**
     * Synchronizes a device token against the authenticated user in Supabase public.fcm_device_tokens.
     * Uses secure upsert logic.
     */
    suspend fun syncTokenToSupabase(context: Context, userId: String, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured || userId.isBlank() || token.isBlank()) {
            return@withContext Result.success(Unit)
        }

        try {
            saveSavedFcmToken(context, token)

            // Try RPC register_fcm_device_token first
            val rpcResult = try {
                SupabaseModule.client.postgrest.rpc(
                    "register_fcm_device_token",
                    buildJsonObject {
                        put("p_token", token.trim())
                        put("p_device_type", "android")
                    }
                )
                true
            } catch (rpcEx: Exception) {
                Log.d(TAG, "RPC register_fcm_device_token fallback: ${rpcEx.message}")
                false
            }

            // Fallback to delete + insert if RPC is not present
            if (!rpcResult) {
                try {
                    SupabaseModule.client.postgrest["fcm_device_tokens"].delete {
                        filter {
                            eq("token", token.trim())
                        }
                    }
                } catch (_: Exception) {}

                val payload = buildJsonObject {
                    put("user_id", userId.trim())
                    put("token", token.trim())
                    put("device_type", "android")
                }
                SupabaseModule.client.postgrest["fcm_device_tokens"].insert(payload)
            }

            Log.d(TAG, "FCM device token synchronized successfully for user ${userId.take(8)}...")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Notice synchronizing FCM token to Supabase: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Remove the current device token associated with the user upon logout,
     * without deleting or affecting tokens belonging to other users or other devices.
     */
    suspend fun removeTokenOnLogout(context: Context, userId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val token = getSavedFcmToken(context)
        if (!SupabaseConfig.isConfigured || userId.isBlank() || token.isNullOrBlank()) {
            setActiveUser(context, null)
            NotificationDeliveryTracker.clearForUser(context, userId)
            return@withContext Result.success(Unit)
        }

        try {
            // Try RPC remove_fcm_device_token first
            val rpcResult = try {
                SupabaseModule.client.postgrest.rpc(
                    "remove_fcm_device_token",
                    buildJsonObject {
                        put("p_token", token.trim())
                    }
                )
                true
            } catch (_: Exception) {
                false
            }

            if (!rpcResult) {
                SupabaseModule.client.postgrest["fcm_device_tokens"].delete {
                    filter {
                        eq("token", token.trim())
                        eq("user_id", userId.trim())
                    }
                }
            }
            Log.d(TAG, "FCM device token removed on logout for user ${userId.take(8)}...")
        } catch (e: Exception) {
            Log.w(TAG, "Notice removing FCM device token on logout: ${e.message}")
        } finally {
            setActiveUser(context, null)
            NotificationDeliveryTracker.clearForUser(context, userId)
        }

        Result.success(Unit)
    }
}
