package com.ammalfarm.adusanthai

import android.app.Application
import android.util.Log
import com.ammalfarm.adusanthai.core.di.AppModule
import com.ammalfarm.adusanthai.core.notification.NotificationConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.core.util.NetworkConnectivityObserver
import com.ammalfarm.adusanthai.di.AppContainer
import com.ammalfarm.adusanthai.di.DefaultAppContainer
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid

class AmmalFarmApplication : Application() {
    companion object {
        lateinit var instance: AmmalFarmApplication
            private set
    }

    lateinit var container: AppContainer
        private set

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSentry()
        setupCrashHandler()
        NetworkConnectivityObserver.initialize(this)
        SupabaseModule.initialize(this)
        appModule = AppModule(this)
        container = DefaultAppContainer(this)
        NotificationConfig.initialize(this)
    }

    private fun initSentry() {
        try {
            SentryAndroid.init(this) { options ->
                options.dsn = "https://c2494963998190de3d5a5baaaeb25462@o4512150876389376.ingest.us.sentry.io/4512150880911360"
                // Disable performance tracing initially for production unless required
                options.tracesSampleRate = 0.0
                options.isDebug = false
                options.environment = if (BuildConfig.DEBUG) "development" else "production"
                options.release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                options.isAnrEnabled = true
                options.isEnableAutoSessionTracking = true
            }
            Log.d("AmmalFarm", "Sentry initialized successfully.")
        } catch (e: Exception) {
            Log.w("AmmalFarm", "Failed to initialize Sentry: ${e.message}")
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Sentry.captureException(throwable)
                Log.e("AmmalFarm", "Uncaught exception in thread ${thread.name}: ${throwable.localizedMessage}", throwable)
            } catch (_: Exception) {
                // Safeguard against logging failures during shutdown
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}

