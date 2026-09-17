package com.example.core.di

import com.example.core.coroutines.DefaultDispatcherProvider
import com.example.core.coroutines.DispatcherProvider
import com.example.core.supabase.SupabaseClient
import com.example.core.supabase.SupabaseModule
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Foundation Dependency Injection Module for Network, Moshi, Retrofit, Supabase, and Dispatchers.
 */
object NetworkModule {

    val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    val loggingInterceptor: HttpLoggingInterceptor by lazy {
        HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        SupabaseModule.createCustomOkHttpClient().newBuilder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val supabaseClient: SupabaseClient by lazy {
        SupabaseClient()
    }

    fun createRetrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val dispatchers: DispatcherProvider by lazy {
        DefaultDispatcherProvider()
    }
}
