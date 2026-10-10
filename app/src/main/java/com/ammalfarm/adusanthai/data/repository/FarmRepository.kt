package com.ammalfarm.adusanthai.data.repository

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.VerificationStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

interface FarmRepository {
    fun getAllApprovedFarms(): Flow<List<Farm>>
    fun getAllFarmsForAdmin(): Flow<List<Farm>>
    fun getFarmById(farmId: String): Flow<Farm?>
    suspend fun registerFarm(farm: Farm): Result<Farm>
    suspend fun updateFarmProfile(farm: Farm): Result<Farm>
    suspend fun updateFarmLogo(farmId: String, logoUrl: String): Result<String>
    suspend fun updateFarmVerification(farmId: String, status: VerificationStatus): Result<Unit>
    suspend fun updateFarmListingLimit(farmId: String, limit: Int): Result<Unit>
    suspend fun getFarmsCount(): Result<Int>
    suspend fun deleteFarm(farmId: String): Result<Unit>
}

class SupabaseFarmRepositoryImpl : FarmRepository {

    private val TAG = "SupabaseFarmRepo"
    private val localFarmLimits = ConcurrentHashMap<String, Int>()

    override fun getAllApprovedFarms(): Flow<List<Farm>> = flow {
        val cached = FarmLocalCache.getAllCachedFarms().filter { 
            it.verificationStatus == VerificationStatus.APPROVED && (it.isAmmalOwnFarm || it.ownerId.isNotBlank())
        }
        if (!SupabaseConfig.isConfigured) {
            emit(cached)
            return@flow
        }
        try {
            val allFarms = fetchFarmsFromSupabase()
            val approved = allFarms.filter { 
                it.verificationStatus == VerificationStatus.APPROVED && (it.isAmmalOwnFarm || it.ownerId.isNotBlank())
            }
            emit(approved)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote approved farms query unavailable: ${e.message}")
            emit(cached)
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllFarmsForAdmin(): Flow<List<Farm>> = flow {
        val cached = FarmLocalCache.getAllCachedFarms()
        if (!SupabaseConfig.isConfigured) {
            emit(cached)
            return@flow
        }
        try {
            val allFarms = fetchFarmsFromSupabase()
            emit(allFarms)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote admin farms query unavailable: ${e.message}")
            emit(cached)
        }
    }.flowOn(Dispatchers.IO)

    override fun getFarmById(farmId: String): Flow<Farm?> = flow {
        if (farmId.isBlank() || !FarmLocalCache.isValidUuid(farmId)) {
            emit(null)
            return@flow
        }
        val validFarmId = farmId.trim()
        val localFarm = FarmLocalCache.getCachedFarm(validFarmId)

        if (!SupabaseConfig.isConfigured) {
            emit(localFarm)
            return@flow
        }

        try {
            val farmDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select {
                    filter {
                        eq("id", validFarmId)
                    }
                }.decodeSingleOrNull<FarmDto>()

            val domain = farmDto?.toDomain()
            if (domain != null) {
                val cachedLogo = FarmLocalCache.getFarmLogo(domain.id)
                val finalFarm = if (domain.logoUrl.isNullOrBlank() && !cachedLogo.isNullOrBlank()) {
                    domain.copy(logoUrl = cachedLogo)
                } else domain
                FarmLocalCache.saveFarmProfile(finalFarm)
                emit(finalFarm)
            } else {
                // If remote query succeeds and farm is not found, purge stale cache and emit null
                FarmLocalCache.removeCachedFarm(validFarmId)
                emit(null)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to get remote farm by id $farmId: ${e.message}")
            // Only on network/connection failure, fallback to verified local cache
            emit(localFarm)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun registerFarm(farm: Farm): Result<Farm> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farm.id)
            val validOwnerId = ensureValidUuid(farm.ownerId)

            val cleanFarm = farm.copy(
                id = validFarmId,
                ownerId = validOwnerId,
                verificationStatus = VerificationStatus.PENDING,
                goatListingLimit = 0
            )
            localFarmLimits[validFarmId] = 0
            localFarmLimits[farm.id] = 0
            FarmLocalCache.saveFarmProfile(cleanFarm)

            if (SupabaseConfig.isConfigured) {
                try {
                    val farmDto = FarmDto.fromDomain(cleanFarm)
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].insert(farmDto)
                } catch (remoteErr: Exception) {
                    Log.w(TAG, "Notice: remote farm insert deferred: ${remoteErr.message}")
                }
            }
            Result.success(cleanFarm)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register farm: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFarmLogo(farmId: String, logoUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val resolvedUrl = SupabaseConfig.resolveStorageUrl(logoUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
            val validFarmId = ensureValidUuid(farmId)

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required to update farm logo"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"

                if (!isSuperAdmin) {
                    val existingFarm = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", validFarmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } catch (_: Exception) { null }

                    if (existingFarm != null && existingFarm.ownerId != authUser.id) {
                        return@withContext Result.failure(SecurityException("Unauthorized: Farm Admin cannot update another farm's logo"))
                    }
                }

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                val updatePayload = buildJsonObject {
                    put("logo_url", resolvedUrl)
                    put("updated_at", nowIso)
                }

                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(updatePayload) {
                    filter {
                        eq("id", validFarmId)
                    }
                }
            }

            // Immediately persist in local cache upon success
            FarmLocalCache.saveFarmLogo(farmId, resolvedUrl)
            FarmLocalCache.saveFarmLogo(validFarmId, resolvedUrl)

            Result.success(resolvedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update farm logo remotely: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFarmProfile(farm: Farm): Result<Farm> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farm.id)
            val validOwnerId = ensureValidUuid(farm.ownerId)
            val cleanFarm = farm.copy(id = validFarmId, ownerId = validOwnerId)

            if (cleanFarm.name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Farm name cannot be empty"))
            }

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required to update farm profile"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"

                if (!isSuperAdmin) {
                    val existingFarm = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", validFarmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } catch (_: Exception) { null }

                    if (existingFarm != null && existingFarm.ownerId != authUser.id) {
                        return@withContext Result.failure(SecurityException("Unauthorized: Farm Admin cannot update another farm's profile"))
                    }
                }

                val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date())

                // Only update editable profile fields
                val district = if (cleanFarm.location.contains(",")) cleanFarm.location.substringBefore(",").trim() else cleanFarm.location.trim()
                val updatePayload = buildJsonObject {
                    put("name", cleanFarm.name.trim())
                    put("description", cleanFarm.description.trim())
                    put("location_district", district)
                    put("location_state", cleanFarm.state.trim().ifBlank { "Tamil Nadu" })
                    put("contact_phone", cleanFarm.contactNumber.trim())
                    put("contact_email", cleanFarm.email.trim())
                    put("updated_at", nowIso)
                    if (!cleanFarm.logoUrl.isNullOrBlank()) {
                        put("logo_url", cleanFarm.logoUrl)
                    }
                    if (!cleanFarm.bannerUrl.isNullOrBlank()) {
                        put("banner_url", cleanFarm.bannerUrl)
                    }
                }

                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(updatePayload) {
                    filter {
                        eq("id", validFarmId)
                    }
                }
            }

            // Cache locally after server confirmation
            FarmLocalCache.saveFarmProfile(cleanFarm)
            if (!cleanFarm.logoUrl.isNullOrBlank()) {
                FarmLocalCache.saveFarmLogo(farm.id, cleanFarm.logoUrl)
                FarmLocalCache.saveFarmLogo(validFarmId, cleanFarm.logoUrl)
            }
            if (!cleanFarm.bannerUrl.isNullOrBlank()) {
                FarmLocalCache.saveFarmBanner(farm.id, cleanFarm.bannerUrl)
                FarmLocalCache.saveFarmBanner(validFarmId, cleanFarm.bannerUrl)
            }

            Result.success(cleanFarm)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update farm profile: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFarmVerification(
        farmId: String,
        status: VerificationStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farmId)
            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.failure(
                    IllegalStateException("Supabase is not configured; farm status was not changed.")
                )
            }
            if (SupabaseModule.auth.currentUserOrNull() == null) {
                return@withContext Result.failure(IllegalStateException("Authentication required"))
            }

            // The database RPC checks the caller's authoritative profiles.role, locks the farm,
            // and raises an error unless exactly one existing farm row is updated.
            val parameters = buildJsonObject {
                put("p_farm_id", validFarmId)
                put("p_status", status.name)
            }
            SupabaseModule.client.postgrest.rpc(
                function = "admin_update_farm_verification",
                parameters = parameters
            )

            Log.i(TAG, "Farm $validFarmId verification set to ${status.name} by secure RPC")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update farm verification: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateFarmListingLimit(
        farmId: String,
        limit: Int
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farmId)
            if (limit < 0) {
                return@withContext Result.failure(IllegalArgumentException("Listing limit cannot be negative"))
            }
            if (!SupabaseConfig.isConfigured) {
                return@withContext Result.failure(
                    IllegalStateException("Supabase is not configured; farm listing limit was not changed.")
                )
            }
            if (SupabaseModule.auth.currentUserOrNull() == null) {
                return@withContext Result.failure(IllegalStateException("Authentication required"))
            }

            // The database RPC validates Super Admin permission and rejects limits below consumed slots.
            val parameters = buildJsonObject {
                put("p_farm_id", validFarmId)
                put("p_limit", limit)
            }
            SupabaseModule.client.postgrest.rpc(
                function = "admin_update_farm_listing_limit",
                parameters = parameters
            )

            localFarmLimits[validFarmId] = limit
            localFarmLimits[farmId] = limit
            Log.i(TAG, "Farm $validFarmId listing limit set to $limit by secure RPC")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update farm listing limit: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun getFarmsCount(): Result<Int> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(FarmLocalCache.getAllCachedFarms().size)
        }
        try {
            val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select(Columns.list("id"))
                .decodeList<FarmDto>()
            Result.success(farmDtos.size)
        } catch (e: Exception) {
            Log.w(TAG, "Farm count remote query unavailable: ${e.message}")
            Result.success(FarmLocalCache.getAllCachedFarms().size)
        }
    }

    private suspend fun fetchFarmsFromSupabase(): List<Farm> {
        if (!SupabaseConfig.isConfigured) {
            return FarmLocalCache.getAllCachedFarms()
        }
        return try {
            val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select()
                .decodeList<FarmDto>()

            // Count goats per farm for accuracy
            val goatsCountMap = try {
                val goatDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                    .select(Columns.list("farm_id"))
                    .decodeList<GoatDto>()
                goatDtos.groupBy { it.farmId }.mapValues { it.value.size }
            } catch (_: Exception) {
                emptyMap()
            }

            val mappedFarms = farmDtos.mapNotNull { dto ->
                // If a non-Ammal farm has no owner, its owner account was deleted.
                // Auto-purge it from Supabase and do not return it to admins.
                val isAmmal = dto.isAmmalOwnFarm == true || dto.id == SEED_AMMAL_FARM_UUID || dto.id == "00000000-0000-0000-0000-000000000001"
                if (dto.ownerId.isNullOrBlank() && !isAmmal) {
                    Log.i(TAG, "Detected orphaned farm with deleted owner account: ${dto.id} (${dto.name}). Purging...")
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            deleteFarm(dto.id)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed auto-purge of farm ${dto.id}: ${e.message}")
                        }
                    }
                    return@mapNotNull null
                }

                val goatsCount = goatsCountMap[dto.id] ?: 0
                val customLimit = localFarmLimits[dto.id] ?: localFarmLimits[ensureValidUuid(dto.id)]
                val domain = dto.toDomain(totalGoats = goatsCount, customLimit = customLimit)

                // Sync logo and banner with local cache
                val effectiveLogo = if (!domain.logoUrl.isNullOrBlank()) {
                    FarmLocalCache.saveFarmLogo(domain.id, domain.logoUrl)
                    domain.logoUrl
                } else {
                    FarmLocalCache.getFarmLogo(domain.id) ?: ""
                }

                val effectiveBanner = if (!domain.bannerUrl.isNullOrBlank()) {
                    FarmLocalCache.saveFarmBanner(domain.id, domain.bannerUrl)
                    domain.bannerUrl
                } else {
                    FarmLocalCache.getFarmBanner(domain.id) ?: ""
                }

                domain.copy(logoUrl = effectiveLogo, bannerUrl = effectiveBanner)
            }

            val sortedFarms = mappedFarms.sortedWith(compareByDescending<Farm> { it.isAmmalOwnFarm }.thenBy { it.createdAt }.thenBy { it.name })
            val sequentialFarms = sortedFarms.mapIndexed { index, farm ->
                val code = if (farm.isAmmalOwnFarm) "FARM-001"
                           else String.format(java.util.Locale.US, "FARM-%03d", index + 1)
                farm.copy(farmCode = code)
            }

            FarmLocalCache.saveAllFarms(sequentialFarms)
            sequentialFarms
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Supabase farms query failed: ${e.message}", e)
            throw e
        }
    }

    override suspend fun deleteFarm(farmId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validFarmId = ensureValidUuid(farmId)
            if (validFarmId == SEED_AMMAL_FARM_UUID || validFarmId == "00000000-0000-0000-0000-000000000001") {
                return@withContext Result.failure(IllegalStateException("The central Ammal Farm cannot be deleted."))
            }

            if (SupabaseConfig.isConfigured) {
                // 1. Delete goats belonging to this farm
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].delete {
                        filter { eq("farm_id", validFarmId) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: goats cleanup for farm $validFarmId: ${e.message}")
                }

                // 2. Delete bookings from this farm
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS].delete {
                        filter { eq("farm_id", validFarmId) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: bookings deletion for farm $validFarmId: ${e.message}")
                }

                // 3. Delete payments from this farm
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS].delete {
                        filter { eq("farm_id", validFarmId) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: payments deletion for farm $validFarmId: ${e.message}")
                }

                // 4. Delete reviews for this farm
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_REVIEWS].delete {
                        filter { eq("farm_id", validFarmId) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Notice: reviews deletion for farm $validFarmId: ${e.message}")
                }

                // 4. Delete the farm record from Supabase
                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].delete {
                        filter { eq("id", validFarmId) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "PostgREST delete on farms failed: ${e.message}, trying direct HTTP DELETE...")
                    try {
                        val token = SupabaseModule.auth.currentAccessTokenOrNull() ?: SupabaseConfig.supabaseAnonKey
                        val url = java.net.URL("${SupabaseConfig.supabaseUrl}/rest/v1/farms?id=eq.$validFarmId")
                        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                            requestMethod = "DELETE"
                            connectTimeout = 8000
                            readTimeout = 8000
                            setRequestProperty("apikey", SupabaseConfig.supabaseAnonKey)
                            setRequestProperty("Authorization", "Bearer $token")
                        }
                        val code = conn.responseCode
                        Log.d(TAG, "Direct HTTP DELETE on farm $validFarmId: $code")
                    } catch (httpEx: Exception) {
                        Log.w(TAG, "Direct HTTP DELETE failed: ${httpEx.message}")
                    }
                }
            }

            localFarmLimits.remove(validFarmId)
            localFarmLimits.remove(farmId)
            FarmLocalCache.deleteFarm(validFarmId)
            FarmLocalCache.removeCachedFarm(validFarmId)
            FarmLocalCache.removeCachedFarm(farmId)
            Result.success(Unit)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to delete farm $farmId: ${e.message}", e)
            Result.failure(e)
        }
    }
}
