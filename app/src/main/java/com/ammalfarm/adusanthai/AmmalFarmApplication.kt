package com.ammalfarm.adusanthai

import android.app.Application
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.di.AppContainer

class AmmalFarmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        SupabaseModule.init(this)
        com.ammalfarm.adusanthai.core.notification.NotificationConfig.initialize(this)
        container = AppContainer(this)
    }
}
