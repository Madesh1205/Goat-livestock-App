package com.ammalfarm.adusanthai.data.repository

import android.util.Log
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.core.supabase.SupabaseModule
import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.ProfileDto
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.core.util.FarmLocalCache
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.VerificationStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
}

class SupabaseFarmRepositoryImpl : FarmRepository {

    private val TAG = "SupabaseFarmRepo"
    private val localFarmLimits = ConcurrentHashMap<String, Int>()

    override fun getAllApprovedFarms(): Flow<List<Farm>> = flow {
        val cached = FarmLocalCache.getAllCachedFarms().filter { it.verificationStatus == VerificationStatus.APPROVED }
        if (!SupabaseConfig.isConfigured) {
            emit(cached)
            return@flow
        }
        try {
            val allFarms = fetchFarmsFromSupabase()
            val approved = allFarms.filter { it.verificationStatus == VerificationStatus.APPROVED }
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

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"

                if (!isSuperAdmin) {
                    return@withContext Result.failure(SecurityException("Only Super Admin can update farm verification status"))
                }

                val updatePayload = buildJsonObject {
                    put("status", status.name)
                    if (status == VerificationStatus.APPROVED) {
                        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                            timeZone = TimeZone.getTimeZone("UTC")
                        }
                        put("verified_at", isoFormat.format(Date()))
                    }
                }

                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(updatePayload) {
                    filter {
                        eq("id", validFarmId)
                    }
                }

                if (status == VerificationStatus.APPROVED) {
                    val currentLimit = localFarmLimits[validFarmId] ?: localFarmLimits[farmId] ?: 0
                    if (currentLimit <= 0) {
                        localFarmLimits[validFarmId] = 2
                        localFarmLimits[farmId] = 2
                        try {
                            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(buildJsonObject {
                                put("goat_listing_limit", 2)
                            }) {
                                filter { eq("id", validFarmId) }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
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

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"

                if (!isSuperAdmin) {
                    return@withContext Result.failure(SecurityException("Only Super Admin can update farm listing limit"))
                }

                val updatePayload = buildJsonObject {
                    put("goat_listing_limit", limit)
                }

                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS].update(updatePayload) {
                        filter {
                            eq("id", validFarmId)
                        }
                    }
                } catch (postgrestErr: Exception) {
                    Log.w(TAG, "Supabase goat_listing_limit column update notice: ${postgrestErr.message}")
                }
            }

            localFarmLimits[validFarmId] = limit
            localFarmLimits[farmId] = limit
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

            val mappedFarms = farmDtos.map { dto ->
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

            FarmLocalCache.saveAllFarms(mappedFarms)
            mappedFarms
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Supabase farms query failed: ${e.message}", e)
            throw e
        }
    }
}
