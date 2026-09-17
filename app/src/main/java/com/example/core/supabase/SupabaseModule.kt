package com.example.core.supabase

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Supabase Kotlin SDK Client Module
 * Provides initialized Supabase client with Auth, Postgrest, Storage, and Realtime plugins
 * initialized with environment URL and Anon Key.
 */
object SupabaseModule {

    @Volatile
    private var applicationContext: Context? = null

    fun initialize(context: Context) {
        applicationContext = context.applicationContext
    }

    fun getApplicationContext(): Context? = applicationContext

    fun createCustomOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.supabaseUrl,
            supabaseKey = SupabaseConfig.supabaseAnonKey
        ) {
            httpEngine = OkHttp.create {
                preconfigured = createCustomOkHttpClient()
            }
            defaultSerializer = KotlinXSerializer(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    isLenient = true
                    coerceInputValues = true
                }
            )
            install(Auth) {
                val ctx = applicationContext
                sessionManager = if (ctx != null) {
                    AndroidSessionManager(ctx)
                } else {
                    MemorySessionManager()
                }
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
            }
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
    }

    /**
     * Auth Service Accessor (Sign in, Sign up, OTP, Sessions)
     */
    val auth: Auth get() = client.auth

    /**
     * Postgrest Database Service Accessor (CRUD, queries, RPCs)
     */
    val postgrest: Postgrest get() = client.postgrest

    /**
     * Storage Buckets Service Accessor (Images, docs, uploads)
     */
    val storage: Storage get() = client.storage

    /**
     * Realtime Streaming Service Accessor (Live database channels)
     */
    val realtime: Realtime get() = client.realtime
}


