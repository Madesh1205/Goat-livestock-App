package com.example.core.notification

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Tracks delivered notification IDs locally using SharedPreferences to prevent duplicate
 * system notifications across Realtime, polling, WorkManager sync, and activity lifecycle events.
 */
object NotificationDeliveryTracker {

    private const val TAG = "NotificationDeliveryTracker"
    private const val PREFS_NAME = "ammal_delivered_notifications_prefs"
    private const val KEY_PREFIX_DELIVERED = "delivered_ids_"
    private const val MAX_TRACKED_PER_USER = 200

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks whether a notification has already been displayed locally as a system notification.
     */
    @Synchronized
    fun isDelivered(context: Context, userId: String, notificationId: String): Boolean {
        if (userId.isBlank() || notificationId.isBlank()) return false
        val prefs = getPrefs(context)
        val key = "$KEY_PREFIX_DELIVERED$userId"
        val deliveredSet = prefs.getStringSet(key, emptySet()) ?: emptySet()
        return deliveredSet.contains(notificationId)
    }

    /**
     * Marks a notification as delivered locally.
     */
    @Synchronized
    fun markDelivered(context: Context, userId: String, notificationId: String) {
        if (userId.isBlank() || notificationId.isBlank()) return
        try {
            val prefs = getPrefs(context)
            val key = "$KEY_PREFIX_DELIVERED$userId"
            val currentSet = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            
            // Keep set size bounded to prevent unlimited growth
            if (currentSet.size >= MAX_TRACKED_PER_USER) {
                val toRemove = currentSet.take(currentSet.size - MAX_TRACKED_PER_USER + 20)
                currentSet.removeAll(toRemove.toSet())
            }
            
            currentSet.add(notificationId)
            prefs.edit().putStringSet(key, currentSet).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark notification delivered: ${e.message}")
        }
    }

    /**
     * Marks a batch of notification IDs as delivered locally.
     */
    @Synchronized
    fun markMultipleDelivered(context: Context, userId: String, notificationIds: Collection<String>) {
        if (userId.isBlank() || notificationIds.isEmpty()) return
        try {
            val prefs = getPrefs(context)
            val key = "$KEY_PREFIX_DELIVERED$userId"
            val currentSet = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
            
            currentSet.addAll(notificationIds)
            if (currentSet.size > MAX_TRACKED_PER_USER) {
                val excess = currentSet.size - MAX_TRACKED_PER_USER
                val toRemove = currentSet.take(excess)
                currentSet.removeAll(toRemove.toSet())
            }
            
            prefs.edit().putStringSet(key, currentSet).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark multiple notifications delivered: ${e.message}")
        }
    }

    /**
     * Clears tracked notification delivery history for a user (e.g. on account reset).
     */
    @Synchronized
    fun clearForUser(context: Context, userId: String) {
        if (userId.isBlank()) return
        try {
            val prefs = getPrefs(context)
            val key = "$KEY_PREFIX_DELIVERED$userId"
            prefs.edit().remove(key).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear delivered notifications for user: ${e.message}")
        }
    }
}
