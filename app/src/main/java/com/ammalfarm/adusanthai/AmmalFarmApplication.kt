package com.ammalfarm.adusanthai

import android.app.Application
import com.ammalfarm.adusanthai.di.AppContainer

class AmmalFarmApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
