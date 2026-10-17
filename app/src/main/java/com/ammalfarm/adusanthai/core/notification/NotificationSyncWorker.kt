package com.ammalfarm.adusanthai.core.notification

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        NotificationConfig.fetchAndSyncFcmToken(applicationContext)
        return Result.success()
    }
}
