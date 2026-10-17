package com.ammalfarm.adusanthai.core.supabase

import android.content.Context
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

object SupabaseModule {
    @OptIn(ExperimentalSerializationApi::class)
    val jsonSerializer: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SupabaseConfig.SUPABASE_URL,
        supabaseKey = SupabaseConfig.SUPABASE_KEY
    ) {
        defaultSerializer = KotlinXSerializer(jsonSerializer)
        install(Auth)
        install(Postgrest)
        install(Realtime)
        install(Storage)
    }

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val auth: Auth get() = client.auth
    val storage: Storage get() = client.storage

    fun getApplicationContext(): Context? = appContext

    fun createCustomOkHttpClient(): OkHttpClient {
        return SupabaseConfig.createCustomOkHttpClient()
    }
}
