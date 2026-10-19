package com.ammalfarm.adusanthai.di

import android.content.Context
import com.ammalfarm.adusanthai.data.repository.AuthRepository
import com.ammalfarm.adusanthai.data.repository.AuthRepositoryImpl
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.data.repository.SupabaseMarketplaceRepositoryImpl
import com.ammalfarm.adusanthai.ui.theme.ThemeManager

class AppContainer(context: Context) {
    val authRepository: AuthRepository = AuthRepositoryImpl(context)
    val marketplaceRepository: MarketplaceRepository = SupabaseMarketplaceRepositoryImpl(context)
    val themeManager: ThemeManager = ThemeManager(context)
}
