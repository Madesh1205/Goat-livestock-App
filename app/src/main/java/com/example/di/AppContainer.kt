package com.example.di

import android.content.Context
import com.example.core.supabase.SupabaseClient
import com.example.core.supabase.SupabaseProvider
import com.example.core.util.PolicyConsentManager
import com.example.data.repository.*
import com.example.ui.theme.ThemeManager

interface AppContainer {
    val supabaseProvider: SupabaseProvider
    val goatRepository: GoatRepository
    val farmRepository: FarmRepository
    val bookingRepository: BookingRepository
    val authRepository: AuthRepository
    val marketplaceRepository: MarketplaceRepository
    val themeManager: ThemeManager
    val policyConsentManager: PolicyConsentManager
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val supabaseProvider: SupabaseProvider by lazy {
        SupabaseClient.getInstance()
    }

    override val goatRepository: GoatRepository by lazy {
        SupabaseGoatRepositoryImpl()
    }

    override val farmRepository: FarmRepository by lazy {
        SupabaseFarmRepositoryImpl()
    }

    override val bookingRepository: BookingRepository by lazy {
        SupabaseBookingRepositoryImpl()
    }

    override val authRepository: AuthRepository by lazy {
        AuthRepositoryImpl(context)
    }

    override val marketplaceRepository: MarketplaceRepository by lazy {
        SupabaseMarketplaceRepositoryImpl(
            goatRepository = goatRepository,
            farmRepository = farmRepository,
            bookingRepository = bookingRepository,
            authRepository = authRepository
        )
    }

    override val themeManager: ThemeManager by lazy {
        ThemeManager(context)
    }

    override val policyConsentManager: PolicyConsentManager by lazy {
        PolicyConsentManager(context)
    }
}
