package com.example

import android.app.Application
import android.util.Log
import com.example.core.di.AppModule
import com.example.core.firebase.FirebaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.di.AppContainer
import com.example.di.DefaultAppContainer

class AmmalFarmApplication : Application() {
    lateinit var container: AppContainer
        private set

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        SupabaseModule.initialize(this)
        appModule = AppModule(this)
        container = DefaultAppContainer(this)
        FirebaseConfig.initialize(this)
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("AmmalFarm", "Uncaught exception in thread ${thread.name}: ${throwable.localizedMessage}", throwable)
            } catch (_: Exception) {
                // Safeguard against logging failures during shutdown
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}

