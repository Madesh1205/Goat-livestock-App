package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import android.content.SharedPreferences

object NotificationConfig {
    private const val PREFS_NAME = "notification_config_prefs"
    private const val KEY_FCM_TOKEN = "saved_fcm_token"

    var activeUserId: String? = null
    var isInitialized: Boolean = false
        private set

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun initialize(context: Context) {
        isInitialized = true
    }

    fun saveSavedFcmToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    fun getSavedFcmToken(context: Context): String? {
        return getPrefs(context).getString(KEY_FCM_TOKEN, null)
    }

    fun clearSavedFcmToken(context: Context) {
        getPrefs(context).edit().remove(KEY_FCM_TOKEN).apply()
    }

    fun fetchAndSyncFcmToken(context: Context) {
        // Safe no-op when Firebase FCM is not actively configured or unauthenticated
        val userId = activeUserId ?: return
        // Token sync would occur here if external push notification service is attached
    }
}
