package com.ammalfarm.adusanthai.data.repository

import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.GoatFilterCriteria
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

open class GoatRepository {
    open fun getApprovedGoats(): Flow<List<Goat>> = flowOf(emptyList())
    open fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> = flowOf(emptyList())
    open fun getGoatById(id: String): Flow<Goat?> = flowOf(null)
    open fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = flowOf(emptyList())
    open fun getAllGoatsForAdmin(): Flow<List<Goat>> = flowOf(emptyList())
    open fun getAvailableBreeds(): Flow<List<String>> = flowOf(listOf("Boer", "Malabari", "Tellicherry", "Kanni Adu"))
    open suspend fun addGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
    open suspend fun updateGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
    open suspend fun deleteGoatListing(goatId: String): Result<Unit> = Result.success(Unit)
    open suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit> = Result.success(Unit)
    open fun getGoatsCount(): Int = 0
    open fun invalidateCache() {}
}

class SupabaseGoatRepositoryImpl : GoatRepository()
