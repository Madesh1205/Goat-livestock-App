package com.ammalfarm.adusanthai.di

import android.content.Context
import com.ammalfarm.adusanthai.data.repository.AuthRepository
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.data.repository.SupabaseMarketplaceRepositoryImpl

class AppContainer(context: Context) {
    val authRepository = AuthRepository(context)
    val marketplaceRepository: MarketplaceRepository = SupabaseMarketplaceRepositoryImpl(context)
}
