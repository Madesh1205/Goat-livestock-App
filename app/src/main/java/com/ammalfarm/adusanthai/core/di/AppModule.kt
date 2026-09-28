package com.ammalfarm.adusanthai.core.di

import android.content.Context
import com.ammalfarm.adusanthai.core.coroutines.DefaultDispatcherProvider
import com.ammalfarm.adusanthai.core.coroutines.DispatcherProvider
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import io.github.jan.supabase.SupabaseClient

/**
 * AppModule providing core application singletons and dependencies.
 */
class AppModule(private val context: Context) {
    val dispatchers: DispatcherProvider = DefaultDispatcherProvider()
    val supabaseSdkClient: SupabaseClient by lazy { SupabaseModule.client }
    val okHttpClient = NetworkModule.okHttpClient
    val moshi = NetworkModule.moshi
}
