package com.example.core.supabase

import com.example.BuildConfig
import io.github.jan.supabase.SupabaseClient as SdkSupabaseClient
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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Interface defining dependency injection contract for Supabase services.
 */
interface SupabaseProvider {
    val client: SdkSupabaseClient
    val auth: Auth
    val postgrest: Postgrest
    val storage: Storage
    val realtime: Realtime
}

/**
 * Authentication Interceptor for raw OkHttp REST requests to Supabase Postgrest/Storage endpoints.
 */
class SupabaseAuthInterceptor(
    private val anonKey: String = SupabaseClient.getAnonKey(),
    private val getAccessToken: () -> String? = { null }
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val token = getAccessToken() ?: anonKey

        val requestBuilder = originalRequest.newBuilder()
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")

        return chain.proceed(requestBuilder.build())
    }
}

/**
 * Primary Supabase Client initialization & DI Provider.
 * Reads SUPABASE_URL and SUPABASE_ANON_KEY from BuildConfig and provides initialized
 * instances of Auth, Postgrest, Storage, and Realtime.
 * 
 * Strict configuration: Fails explicitly with an IllegalStateException if credentials are missing
 * rather than falling back to an obsolete project.
 */
class SupabaseClient(
    val customUrl: String? = null,
    val customAnonKey: String? = null
) : SupabaseProvider {

    val url: String = customUrl ?: getUrl()
    val anonKey: String = customAnonKey ?: getAnonKey()

    /**
     * Lazily initialized Supabase SDK Client with Auth, Postgrest, Storage, and Realtime plugins.
     */
    override val client: SdkSupabaseClient by lazy {
        check(url.isNotBlank()) {
            "Supabase URL is not configured. Please provide SUPABASE_URL in AI Studio Secrets / .env."
        }
        check(anonKey.isNotBlank()) {
            "Supabase Anon Key is not configured. Please provide SUPABASE_ANON_KEY in AI Studio Secrets / .env."
        }
        createSupabaseClient(
            supabaseUrl = url,
            supabaseKey = anonKey
        ) {
            httpEngine = OkHttp.create {
                preconfigured = createCustomOkHttpClient()
            }
            install(Auth) {
                sessionManager = MemorySessionManager()
                alwaysAutoRefresh = true
            }
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
    }

    /**
     * Supabase Auth component for User Authentication, Sessions, and Registration.
     */
    override val auth: Auth
        get() = client.auth

    /**
     * Supabase Postgrest component for PostgreSQL database CRUD and RPC queries.
     */
    override val postgrest: Postgrest
        get() = client.postgrest

    /**
     * Supabase Storage component for File/Image uploads and CDN bucket management.
     */
    override val storage: Storage
        get() = client.storage

    /**
     * Supabase Realtime component for live broadcast channels and database change listeners.
     */
    override val realtime: Realtime
        get() = client.realtime

    val restUrl: String get() = "$url/rest/v1"
    val storageUrl: String get() = "$url/storage/v1"
    val authUrl: String get() = "$url/auth/v1"

    companion object : SupabaseProvider {
        /**
         * Resolves Supabase URL from SupabaseConfig.
         */
        fun getUrl(): String = SupabaseConfig.supabaseUrl

        /**
         * Resolves Supabase ANON_KEY from SupabaseConfig.
         */
        fun getAnonKey(): String = SupabaseConfig.supabaseAnonKey

        private val defaultInstance: SupabaseClient by lazy {
            SupabaseClient()
        }

        // Static DI accessors matching SupabaseProvider
        override val client: SdkSupabaseClient get() = defaultInstance.client
        override val auth: Auth get() = defaultInstance.auth
        override val postgrest: Postgrest get() = defaultInstance.postgrest
        override val storage: Storage get() = defaultInstance.storage
        override val realtime: Realtime get() = defaultInstance.realtime

        fun getInstance(): SupabaseClient = defaultInstance

        fun createCustomOkHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .retryOnConnectionFailure(true)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
}
