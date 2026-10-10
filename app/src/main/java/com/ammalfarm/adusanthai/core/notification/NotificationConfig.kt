package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.ammalfarm.adusanthai.AmmalFarmApplication
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Android Notification System Configuration
 * Initializes Android notification channels, manages active user isolation,
 * and configures secure FCM device token registration and unregistration via RPC.
 */
object NotificationConfig {
    private const val TAG = "NotificationConfig"
    private const val PREFS_NAME = "fcm_token_prefs"
    private const val KEY_SAVED_TOKEN = "saved_fcm_token"

    private val isFetchingToken = AtomicBoolean(false)

    var isInitialized: Boolean = false
        private set

    @Volatile
    var activeUserId: String? = null

    /**
     * Initializes notification channels and local notification services.
     * Does NOT fetch FCM tokens until user is authenticated.
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

    /**
     * Obtains a stable, non-sensitive device identifier if available.
     */
    fun getDeviceId(context: Context): String? {
        return try {
            val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            if (!id.isNullOrBlank() && id != "9774d56d682e549c") id else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Saves the latest FCM registration token locally for token refresh & logout unregistration.
     */
    fun saveSavedFcmToken(context: Context, token: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SAVED_TOKEN, token)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache FCM token locally: ${e.message}")
        }
    }

    /**
     * Retrieves the locally cached FCM registration token.
     */
    fun getSavedFcmToken(context: Context): String? {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SAVED_TOKEN, null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clears the locally cached FCM registration token (e.g. after logout unregistration).
     */
    fun clearSavedFcmToken(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_SAVED_TOKEN)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cached FCM token: ${e.message}")
        }
    }

    /**
     * Retrieves current FCM registration token and registers it to Supabase via RPC if user is authenticated.
     * Avoids concurrent or repeated token fetching and syncs cached tokens immediately upon sign-in.
     */
    fun fetchAndSyncFcmToken(context: Context) {
        try {
            val currentSessionUser = try {
                SupabaseModule.auth.currentUserOrNull()
            } catch (_: Throwable) {
                null
            }
            val effectiveUserId = currentSessionUser?.id ?: activeUserId
            if (effectiveUserId.isNullOrBlank()) {
                Log.d(TAG, "No authenticated Supabase user session; deferring FCM device token registration.")
                return
            }

            // Immediately synchronize any previously cached token for the authenticated user
            val cachedToken = getSavedFcmToken(context)
            if (!cachedToken.isNullOrBlank()) {
                val maskedCached = if (cachedToken.length > 8) "${cachedToken.take(6)}...${cachedToken.takeLast(2)}" else "***"
                Log.d(TAG, "Syncing cached FCM device token (masked: $maskedCached) to Supabase")
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val app = context.applicationContext as? AmmalFarmApplication ?: AmmalFarmApplication.instance
                        val deviceId = getDeviceId(context)
                        app.container.marketplaceRepository.registerFcmDeviceToken(cachedToken, deviceId)
                    } catch (e: Exception) {
                        Log.w(TAG, "FCM cached token registration error: ${e.message}")
                    }
                }
            }

            // Prevent repeated concurrent token retrieval attempts
            if (!isFetchingToken.compareAndSet(false, true)) {
                Log.d(TAG, "FCM token retrieval already in progress; skipping duplicate request.")
                return
            }

            val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
            if (availability != ConnectionResult.SUCCESS) {
                Log.w(TAG, "Google Play Services unavailable for FCM (availability code: $availability). Skipping token fetch.")
                isFetchingToken.set(false)
                return
            }

            if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
                Log.w(TAG, "FirebaseApp is not initialized. Skipping FCM token fetch.")
                isFetchingToken.set(false)
                return
            }

            val firebaseMessaging = FirebaseMessaging.getInstance()
            firebaseMessaging.token.addOnCompleteListener { task ->
                try {
                    if (!task.isSuccessful) {
                        val ex = task.exception
                        val cause = ex?.cause
                        val exName = ex?.javaClass?.simpleName ?: "Exception"
                        val causeName = cause?.javaClass?.simpleName
                        val causeMsg = cause?.message
                        val detail = if (cause != null) " (Cause: [$causeName] $causeMsg)" else ""
                        Log.w(TAG, "Fetching FCM registration token handled gracefully [$exName]: ${ex?.message}$detail")
                        return@addOnCompleteListener
                    }

                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        saveSavedFcmToken(context, token)
                        val maskedToken = if (token.length > 8) "${token.take(6)}...${token.takeLast(2)}" else "***"
                        Log.d(TAG, "Retrieved current FCM registration token (masked: $maskedToken)")
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val app = context.applicationContext as? AmmalFarmApplication ?: AmmalFarmApplication.instance
                                val deviceId = getDeviceId(context)
                                app.container.marketplaceRepository.registerFcmDeviceToken(token, deviceId)
                            } catch (e: Exception) {
                                Log.w(TAG, "FCM token registration error: ${e.message}")
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Unexpected error in FCM token completion handler: ${e.message}")
                } finally {
                    isFetchingToken.set(false)
                }
            }
        } catch (e: Throwable) {
            isFetchingToken.set(false)
            Log.w(TAG, "FCM messaging token retrieval unavailable: ${e.message}")
        }
    }

    /**
     * Called during logout to unregister the FCM token for the device before signing out.
     */
    suspend fun unregisterFcmTokenOnLogout(context: Context) {
        try {
            val token = getSavedFcmToken(context)
            if (!token.isNullOrBlank()) {
                val app = context.applicationContext as? AmmalFarmApplication ?: AmmalFarmApplication.instance
                app.container.marketplaceRepository.unregisterFcmDeviceToken(token)
                clearSavedFcmToken(context)
                Log.d(TAG, "Unregistered FCM token on user logout")
            } else {
                Log.d(TAG, "No cached FCM token to unregister on logout")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister FCM token on logout: ${e.message}")
        }
    }
}
