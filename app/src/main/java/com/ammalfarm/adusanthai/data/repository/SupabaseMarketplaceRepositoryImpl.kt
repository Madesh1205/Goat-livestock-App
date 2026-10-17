package com.ammalfarm.adusanthai.data.repository

import android.content.Context

/**
 * Concrete Supabase-backed MarketplaceRepository implementation.
 * Delegates all operations to the shared base implementation in MarketplaceRepository.kt.
 */
class SupabaseMarketplaceRepository(
    context: Context? = null
) : SupabaseMarketplaceRepositoryImpl(context)
