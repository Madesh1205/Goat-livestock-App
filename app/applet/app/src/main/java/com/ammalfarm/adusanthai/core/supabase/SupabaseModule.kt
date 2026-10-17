package com.ammalfarm.adusanthai.core.supabase

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import okhttp3.OkHttpClient

object SupabaseModule {
    private var appContext: Context? = null

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.SUPABASE_URL,
            supabaseKey = SupabaseConfig.SUPABASE_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
            install(Storage)
        }
    }

    val auth: Auth get() = client.auth
    val storage: Storage get() = client.storage

    fun setApplicationContext(context: Context) {
        appContext = context.applicationContext
    }

    fun getApplicationContext(): Context? = appContext

    fun createCustomOkHttpClient(): OkHttpClient {
        return SupabaseConfig.createCustomOkHttpClient()
    }
}
