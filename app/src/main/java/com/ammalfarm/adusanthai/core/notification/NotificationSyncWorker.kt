package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.dto.NotificationDto
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic native notification synchronization.
 * Serves as a reliable native background fallback when the app is backgrounded,
 * polling Supabase for unread notifications and displaying them in the Android notification drawer
 * via NotificationHelper while strictly preventing duplicates.
 */
class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "NotificationSyncWorker"
        const val PERIODIC_WORK_NAME = "ammal_periodic_notification_sync"
        const val ONE_TIME_WORK_NAME = "ammal_immediate_notification_sync"

        /**
         * Schedules periodic notification sync using WorkManager (runs every 15 minutes with network constraint).
         */
        fun enqueuePeriodicSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = PeriodicWorkRequestBuilder<NotificationSyncWorker>(
                    15, TimeUnit.MINUTES,
                    5, TimeUnit.MINUTES
                )
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    syncRequest
                )
                Log.d(TAG, "Periodic notification sync enqueued with WorkManager.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enqueue periodic notification sync: ${e.message}")
            }
        }

        /**
         * Triggers an immediate one-time sync (e.g. on app resume or background transition).
         */
        fun triggerImmediateSync(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val syncRequest = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONE_TIME_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    syncRequest
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger immediate notification sync: ${e.message}")
            }
        }

        /**
         * Cancels periodic background notification sync (e.g. on user logout).
         */
        fun cancelSync(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(ONE_TIME_WORK_NAME)
                Log.d(TAG, "Periodic notification sync cancelled.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification sync: ${e.message}")
            }
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val currentUserId = NotificationConfig.activeUserId
        if (currentUserId.isNullOrBlank()) {
            Log.d(TAG, "No active user logged in; skipping background notification sync.")
            return@withContext Result.success()
        }

        if (!SupabaseConfig.isConfigured) {
            Log.d(TAG, "Supabase is not configured; skipping sync.")
            return@withContext Result.success()
        }

        try {
            Log.d(TAG, "Starting background notification sync for user: $currentUserId")

            // Query unread notifications for current user from Supabase
            val dtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_NOTIFICATIONS]
                .select {
                    filter {
                        eq("user_id", currentUserId)
                        eq("is_read", false)
                    }
                }.decodeList<NotificationDto>()

            var newNotificationsShown = 0

            for (dto in dtos) {
                val notification = dto.toDomain()
                
                // Security check: ensure recipient user matches
                if (notification.recipientUserId.isNotBlank() && notification.recipientUserId != currentUserId) {
                    continue
                }

                // Check local delivery status to avoid duplicates
                if (!NotificationDeliveryTracker.isDelivered(applicationContext, currentUserId, notification.id)) {
                    NotificationHelper.showSystemNotification(applicationContext, notification)
                    NotificationDeliveryTracker.markDelivered(applicationContext, currentUserId, notification.id)
                    newNotificationsShown++
                }
            }

            Log.d(TAG, "Background sync completed successfully. Showed $newNotificationsShown new notifications.")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val isTransient = msg.contains("resolve host", ignoreCase = true) ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("SocketException", ignoreCase = true) ||
                    msg.contains("ConnectException", ignoreCase = true)

            if (isTransient) {
                Log.w(TAG, "Transient network issue during notification sync; will retry: $msg")
                Result.retry()
            } else {
                Log.e(TAG, "Error syncing notifications in background: ${e.message}", e)
                Result.failure()
            }
        }
    }
}
