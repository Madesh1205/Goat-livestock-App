package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import android.content.SharedPreferences

object NotificationDeliveryTracker {
    private const val PREFS_NAME = "notification_delivery_tracker_prefs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun key(userId: String, notificationId: String): String = "delivered_${userId}_$notificationId"

    fun isDelivered(context: Context, userId: String, notificationId: String): Boolean {
        if (userId.isBlank() || notificationId.isBlank()) return false
        return getPrefs(context).getBoolean(key(userId, notificationId), false)
    }

    fun markDelivered(context: Context, userId: String, notificationId: String) {
        if (userId.isBlank() || notificationId.isBlank()) return
        getPrefs(context).edit().putBoolean(key(userId, notificationId), true).apply()
    }

    fun markMultipleDelivered(context: Context, userId: String, notificationIds: List<String>) {
        if (userId.isBlank() || notificationIds.isEmpty()) return
        val editor = getPrefs(context).edit()
        notificationIds.forEach { id ->
            if (id.isNotBlank()) {
                editor.putBoolean(key(userId, id), true)
            }
        }
        editor.apply()
    }

    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        val prefs = getPrefs(context)
        val prefix = "delivered_${userId}_"
        val keysToRemove = prefs.all.keys.filter { it.startsWith(prefix) }
        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }
}
