package com.example.data.repository

import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.core.util.PriceUtils
import com.example.data.dto.FarmDto
import com.example.data.dto.GoatDto
import com.example.data.dto.GoatImageDto
import com.example.data.dto.ProfileDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.data.dto.currentIsoTimestamp
import com.example.data.dto.ensureValidUuid
import com.example.model.*
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
import java.util.UUID

interface GoatRepository {
    fun getApprovedGoats(): Flow<List<Goat>>
    fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>>
    fun getGoatById(id: String): Flow<Goat?>
    fun getGoatsByFarm(farmId: String): Flow<List<Goat>>
    fun getAllGoatsForAdmin(): Flow<List<Goat>>
    suspend fun addGoatListing(goat: Goat): Result<Goat>
    suspend fun updateGoatListing(goat: Goat): Result<Goat>
    suspend fun deleteGoatListing(goatId: String): Result<Unit>
    suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit>
    fun getAvailableBreeds(): Flow<List<String>>
    suspend fun getGoatsCount(): Result<Int>
    fun invalidateCache()
}

class SupabaseGoatRepositoryImpl : GoatRepository {

    private val TAG = "SupabaseGoatRepo"

    @Volatile
    private var cachedRawGoats: List<Goat>? = null
    private var lastFetchTime: Long = 0L
    private val CACHE_TTL_MS = 10_000L // 10 seconds cache for rapid filter/search interactions
    private val localAddedGoats = java.util.concurrent.ConcurrentHashMap<String, Goat>()

    override fun invalidateCache() {
        cachedRawGoats = null
        lastFetchTime = 0L
    }

    override fun getApprovedGoats(): Flow<List<Goat>> = flow {
        val goats = try {
            fetchGoatsFromSupabase(onlyApproved = true, onlyAvailable = true)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote approved goats query unavailable: ${e.message}")
            localAddedGoats.values.filter {
                (it.approvalStatus == ApprovalStatus.APPROVED) &&
                        (it.availabilityStatus == AvailabilityStatus.AVAILABLE || it.availabilityStatus == AvailabilityStatus.BOOKING_PENDING)
            }
        }
        emit(goats)
    }.flowOn(Dispatchers.IO)

    override fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> = flow {
        val allGoats = try {
            fetchGoatsFromSupabase(onlyApproved = true, onlyAvailable = false)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: remote goats search query unavailable: ${e.message}")
            localAddedGoats.values.filter { it.approvalStatus == ApprovalStatus.APPROVED }
        }

        var filtered = allGoats.filter { goat ->
            goat.approvalStatus == ApprovalStatus.APPROVED && criteria.matches(goat)
        }

        // Apply Sorting
        filtered = when (criteria.sortBy) {
            SortOption.RELEVANCE -> filtered.sortedByDescending { it.isFeatured }
            SortOption.PRICE_LOW_HIGH -> filtered.sortedBy { it.finalPrice }
            SortOption.PRICE_HIGH_LOW -> filtered.sortedByDescending { it.finalPrice }
            SortOption.AGE_YOUNGEST -> filtered.sortedBy { it.ageMonths }
            SortOption.AGE_OLDEST -> filtered.sortedByDescending { it.ageMonths }
            SortOption.WEIGHT_HEAVIEST -> filtered.sortedByDescending { it.weightKg }
            SortOption.WEIGHT_LIGHTEST -> filtered.sortedBy { it.weightKg }
            SortOption.NEWEST -> filtered.sortedByDescending { it.createdAt }
        }

        emit(filtered)
    }.flowOn(Dispatchers.IO)

    override fun getGoatById(id: String): Flow<Goat?> = flow {
        val validId = ensureValidUuid(id)
        val localGoat = localAddedGoats[id] ?: localAddedGoats[validId]

        if (!SupabaseConfig.isConfigured) {
            emit(localGoat)
            return@flow
        }

        try {
            val goatDto = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    filter {
                        eq("id", validId)
                    }
                }.decodeSingleOrNull<GoatDto>()

            if (goatDto != null) {
                val farmMap = fetchFarmsMap()
                val photosMap = fetchGoatPhotosMap(listOf(goatDto.id))
                val farm = farmMap[goatDto.farmId]
                emit(goatDto.toDomain(
                    resolvedFarmName = farm?.name,
                    resolvedFarmLocation = farm?.location,
                    resolvedPhotos = photosMap[goatDto.id]
                ))
            } else {
                emit(localGoat)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice: could not fetch remote goat by id $id (${e.message})")
            emit(localGoat)
        }
    }.flowOn(Dispatchers.IO)

    override fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = flow {
        if (farmId.isBlank()) {
            emit(emptyList())
            return@flow
        }
        val validFarmId = try { ensureValidUuid(farmId.trim()) } catch (_: Exception) { farmId.trim() }

        if (!SupabaseConfig.isConfigured) {
            val localFarmGoats = localAddedGoats.values.filter {
                it.farmId == farmId || it.farmId == validFarmId
            }
            emit(localFarmGoats)
            return@flow
        }

        try {
            val goatDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select {
                    filter {
                        eq("farm_id", validFarmId)
                    }
                }.decodeList<GoatDto>()

            val farmMap = fetchFarmsMap()
            val photosMap = fetchGoatPhotosMap(goatDtos.map { it.id })

            val goats = goatDtos.map { dto ->
                val farm = farmMap[dto.farmId]
                dto.toDomain(
                    resolvedFarmName = farm?.name,
                    resolvedFarmLocation = farm?.location,
                    resolvedPhotos = photosMap[dto.id]
                )
            }
            // Emit strictly the real goats from Supabase for this exact farm UUID.
            // Do NOT fallback to sample/fake/cached goats when Supabase returns empty.
            emit(goats)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: could not fetch remote goats for farm $farmId (${e.message})")
            throw e
        }
    }.flowOn(Dispatchers.IO)

    override fun getAllGoatsForAdmin(): Flow<List<Goat>> = flow {
        val goats = fetchGoatsFromSupabase(onlyApproved = false, onlyAvailable = false)
        emit(goats)
    }.flowOn(Dispatchers.IO)

    private suspend fun ensureUserFarmForGoat(
        requestedFarmId: String,
        farmName: String?,
        farmLocation: String?
    ): String {
        val authUser = SupabaseModule.auth.currentUserOrNull()
            ?: throw IllegalStateException("User must be authenticated to add a goat listing")
        val userId = authUser.id
        val userEmail = authUser.email ?: ""
        val profile = try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                .select { filter { eq("id", userId) } }
                .decodeSingleOrNull<ProfileDto>()
        } catch (_: Exception) { null }
        val isSuperAdmin = profile?.role == "SUPER_ADMIN"
        val isCustomer = profile?.role == "CUSTOMER"

        if (isCustomer && !isSuperAdmin) {
            throw SecurityException("Customers are not authorized to add goat listings")
        }

        // For Super Admin: strictly resolve Super Admin's owned farm (Ammal Farm)
        if (isSuperAdmin) {
            if (requestedFarmId.isNotBlank()) {
                val validRequestedFarmId = try { ensureValidUuid(requestedFarmId) } catch (_: Exception) { null }
                if (validRequestedFarmId != null) {
                    val reqFarm = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", validRequestedFarmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } catch (_: Exception) { null }
                    if (reqFarm != null && (reqFarm.ownerId == userId || reqFarm.isAmmalOwnFarm || reqFarm.id == SEED_AMMAL_FARM_UUID)) {
                        return reqFarm.id
                    }
                }
            }

            val ammalFarms = try {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                    .select {
                        filter {
                            or {
                                eq("id", SEED_AMMAL_FARM_UUID)
                                eq("is_ammal_own_farm", true)
                                eq("owner_id", userId)
                            }
                        }
                    }.decodeList<FarmDto>()
            } catch (_: Exception) { emptyList() }

            if (ammalFarms.isNotEmpty()) {
                return ammalFarms.first().id
            }
            return SEED_AMMAL_FARM_UUID
        }

        // For Farm Admin: Query farms owned by current user (owner_id == currentUser.id)
        val ownedFarms = try {
            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select {
                    filter {
                        eq("owner_id", userId)
                    }
                }.decodeList<FarmDto>()
        } catch (e: Exception) {
            Log.w(TAG, "Error querying owned farms: ${e.message}")
            emptyList()
        }

        val ownedFarmIds = ownedFarms.map { it.id }.toSet()

        if (requestedFarmId.isNotBlank()) {
            val validRequestedFarmId = try { ensureValidUuid(requestedFarmId) } catch (_: Exception) { null }
            if (validRequestedFarmId != null) {
                if (ownedFarmIds.contains(validRequestedFarmId)) {
                    return validRequestedFarmId
                }
                val specificFarm = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("id", validRequestedFarmId) } }
                        .decodeSingleOrNull<FarmDto>()
                } catch (_: Exception) { null }
                if (specificFarm != null && specificFarm.ownerId == userId) {
                    return specificFarm.id
                }
                throw SecurityException("Farm Admin cannot add goats to another farm (requested: $requestedFarmId)")
            }
        }

        if (ownedFarms.isNotEmpty()) {
            return ownedFarms.first().id
        }

        // Check user profile's farm_id
        if (!profile?.farmId.isNullOrBlank()) {
            val profileFarmId = try { ensureValidUuid(profile.farmId) } catch (_: Exception) { null }
            if (profileFarmId != null) {
                val farmByProfile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("id", profileFarmId) } }
                        .decodeSingleOrNull<FarmDto>()
                } catch (_: Exception) { null }
                if (farmByProfile != null && farmByProfile.ownerId == userId) {
                    return farmByProfile.id
                }
            }
        }

        // No fake farm fallback! Throw an exception so the missing farm is surfaced properly.
        throw IllegalStateException("No registered farm found for user $userEmail. Please complete farm registration in Supabase.")
    }

    override suspend fun addGoatListing(goat: Goat): Result<Goat> = withContext(Dispatchers.IO) {
        try {
            // Validate inputs
            if (goat.breed.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Goat breed is required"))
            }
            val priceVal = PriceUtils.validatePrice(goat.price.toString())
            if (priceVal is PriceUtils.PriceValidationResult.Error) {
                return@withContext Result.failure(IllegalArgumentException(priceVal.message))
            }
            val discountVal = PriceUtils.validateDiscount(goat.discountPercentage.toString())
            if (discountVal is PriceUtils.DiscountValidationResult.Error) {
                return@withContext Result.failure(IllegalArgumentException(discountVal.message))
            }

            val generatedId = if (goat.id.isBlank() || goat.id.startsWith("goat-")) {
                UUID.randomUUID().toString()
            } else {
                ensureValidUuid(goat.id)
            }

            val resolvedFarmId = if (!SupabaseConfig.isConfigured) {
                goat.farmId.ifBlank { SEED_AMMAL_FARM_UUID }
            } else {
                ensureUserFarmForGoat(goat.farmId, goat.farmName, goat.farmLocation)
            }

            val isAmmal = resolvedFarmId == SEED_AMMAL_FARM_UUID ||
                (goat.farmName.contains("Ammal", ignoreCase = true) && !goat.farmName.contains("Partner", ignoreCase = true))

            val cleanGoat = goat.copy(
                id = generatedId,
                farmId = resolvedFarmId,
                tagNumber = goat.tagNumber.ifBlank { "AF-${UUID.randomUUID().toString().take(6).uppercase()}" },
                approvalStatus = if (isAmmal) ApprovalStatus.APPROVED else ApprovalStatus.PENDING_APPROVAL,
                availabilityStatus = AvailabilityStatus.AVAILABLE,
                listingFeePaid = isAmmal,
                listingFeeAmount = if (isAmmal) 0.0 else 100.0
            )

            if (SupabaseConfig.isConfigured) {
                val goatDto = GoatDto.fromDomain(cleanGoat)
                var insertSuccess = false
                var lastInsertException: Exception? = null

                try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].insert(goatDto)
                    insertSuccess = true
                    Log.i(TAG, "Successfully inserted goat ${cleanGoat.name} (${cleanGoat.id}) into Supabase database")
                } catch (insertErr: Exception) {
                    lastInsertException = insertErr
                    val errLower = (insertErr.message ?: "").lowercase()
                    val isDuplicateTag = errLower.contains("uq_farm_goat_tag") ||
                            errLower.contains("duplicate") ||
                            errLower.contains("23505") ||
                            errLower.contains("tag_number")

                    if (isDuplicateTag) {
                        Log.w(TAG, "Tag collision detected, retrying insert with unique tag")
                        try {
                            val retryDto = goatDto.copy(
                                tagNumber = "AF-${UUID.randomUUID().toString().take(6).uppercase()}"
                            )
                            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].insert(retryDto)
                            insertSuccess = true
                        } catch (retryErr: Exception) {
                            lastInsertException = retryErr
                            Log.w(TAG, "Retry insert failed: ${retryErr.message}")
                        }
                    } else {
                        Log.e(TAG, "Goat insert failed: ${insertErr.message}")
                    }
                }

                if (!insertSuccess) {
                    throw lastInsertException ?: RuntimeException("Failed to insert goat record into database")
                }

                // Save photos in goat_images table if any
                if (goat.photos.isNotEmpty()) {
                    val nowIso = currentIsoTimestamp()
                    val imageDtos = goat.photos.mapIndexed { index, pathOrUrl ->
                        val cleanStoragePath = SupabaseConfig.extractStoragePath(pathOrUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        GoatImageDto(
                            id = UUID.randomUUID().toString(),
                            goatId = generatedId,
                            imageUrl = cleanStoragePath.ifBlank { pathOrUrl },
                            displayOrder = index,
                            isPrimary = index == 0,
                            createdAt = nowIso
                        )
                    }
                    try {
                        SupabaseModule.client.postgrest["goat_images"].insert(imageDtos)
                    } catch (imgErr: Exception) {
                        Log.w(TAG, "Could not insert goat_images records: ${imgErr.message}")
                    }
                }
            }

            // Only update local state after server confirmation!
            localAddedGoats[cleanGoat.id] = cleanGoat
            invalidateCache()
            Result.success(cleanGoat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add goat listing: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateGoatListing(goat: Goat): Result<Goat> = withContext(Dispatchers.IO) {
        try {
            val validId = ensureValidUuid(goat.id)

            // Price and discount validation
            val priceVal = PriceUtils.validatePrice(goat.price.toString())
            if (priceVal is PriceUtils.PriceValidationResult.Error) {
                return@withContext Result.failure(IllegalArgumentException(priceVal.message))
            }
            val discountVal = PriceUtils.validateDiscount(goat.discountPercentage.toString())
            if (discountVal is PriceUtils.DiscountValidationResult.Error) {
                return@withContext Result.failure(IllegalArgumentException(discountVal.message))
            }

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required to update goat listing"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"
                val isCustomer = profile?.role == "CUSTOMER"

                if (isCustomer && !isSuperAdmin) {
                    return@withContext Result.failure(SecurityException("Customers cannot edit goat listings"))
                }

                val existingDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select { filter { eq("id", validId) } }
                        .decodeSingleOrNull<GoatDto>()
                } catch (_: Exception) { null }

                if (existingDto != null && !isSuperAdmin) {
                    val farmDto = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", existingDto.farmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } catch (_: Exception) { null }

                    if (farmDto != null && farmDto.ownerId != authUser.id) {
                        return@withContext Result.failure(SecurityException("Farm Admin cannot edit another farm's goat"))
                    }
                }
            }

            val validFarmId = ensureValidUuid(goat.farmId)
            val cleanGoat = goat.copy(id = validId, farmId = validFarmId)
            val dto = GoatDto.fromDomain(cleanGoat)

            if (SupabaseConfig.isConfigured) {
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(dto) {
                    filter {
                        eq("id", validId)
                    }
                }

                // Sync photos in goat_images and delete removed storage files
                try {
                    val existingImages = SupabaseModule.client.postgrest["goat_images"]
                        .select {
                            filter {
                                eq("goat_id", validId)
                            }
                        }.decodeList<GoatImageDto>()

                    val currentPaths = goat.photos.map { SupabaseConfig.extractStoragePath(it, SupabaseConfig.BUCKET_GOAT_IMAGES) }.toSet()
                    val removedFiles = existingImages.filter { img ->
                        val imgPath = SupabaseConfig.extractStoragePath(img.imageUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        imgPath.isNotBlank() && !currentPaths.contains(imgPath)
                    }.map { it.imageUrl }

                    if (removedFiles.isNotEmpty()) {
                        com.example.util.ImageUploadHelper.deleteStorageFiles(removedFiles)
                    }

                    SupabaseModule.client.postgrest["goat_images"].delete {
                        filter {
                            eq("goat_id", validId)
                        }
                    }

                    if (goat.photos.isNotEmpty()) {
                        val nowIso = currentIsoTimestamp()
                        val newImageDtos = goat.photos.mapIndexed { index, pathOrUrl ->
                            val cleanStoragePath = SupabaseConfig.extractStoragePath(pathOrUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                            GoatImageDto(
                                id = UUID.randomUUID().toString(),
                                goatId = validId,
                                imageUrl = cleanStoragePath.ifBlank { pathOrUrl },
                                displayOrder = index,
                                isPrimary = index == 0,
                                createdAt = nowIso
                            )
                        }
                        SupabaseModule.client.postgrest["goat_images"].insert(newImageDtos)
                    }
                } catch (syncErr: Exception) {
                    Log.w(TAG, "Failed to sync goat_images on update: ${syncErr.message}")
                }
            }

            // Only update local cache after successful server operation!
            localAddedGoats[cleanGoat.id] = cleanGoat
            invalidateCache()
            Result.success(cleanGoat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update goat listing: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteGoatListing(goatId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validId = ensureValidUuid(goatId)

            if (SupabaseConfig.isConfigured) {
                val authUser = SupabaseModule.auth.currentUserOrNull()
                    ?: return@withContext Result.failure(IllegalStateException("Authentication required to delete goat listing"))
                val profile = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PROFILES]
                        .select { filter { eq("id", authUser.id) } }
                        .decodeSingleOrNull<ProfileDto>()
                } catch (_: Exception) { null }
                val isSuperAdmin = profile?.role == "SUPER_ADMIN"
                val isCustomer = profile?.role == "CUSTOMER"

                if (isCustomer && !isSuperAdmin) {
                    return@withContext Result.failure(SecurityException("Customers cannot delete goat listings"))
                }

                val existingDto = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .select { filter { eq("id", validId) } }
                        .decodeSingleOrNull<GoatDto>()
                } catch (_: Exception) { null }

                if (existingDto != null && !isSuperAdmin) {
                    val farmDto = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                            .select { filter { eq("id", existingDto.farmId) } }
                            .decodeSingleOrNull<FarmDto>()
                    } catch (_: Exception) { null }

                    if (farmDto != null && farmDto.ownerId != authUser.id) {
                        return@withContext Result.failure(SecurityException("Farm Admin cannot delete another farm's goat"))
                    }
                }

                // Delete goat record from Supabase first
                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].delete {
                    filter {
                        eq("id", validId)
                    }
                }

                // Clean up associated images only if delete succeeded
                try {
                    val existingImages = SupabaseModule.client.postgrest["goat_images"]
                        .select {
                            filter {
                                eq("goat_id", validId)
                            }
                        }.decodeList<GoatImageDto>()

                    val filesToDelete = existingImages.map { it.imageUrl }
                    if (filesToDelete.isNotEmpty()) {
                        com.example.util.ImageUploadHelper.deleteStorageFiles(filesToDelete)
                    }

                    SupabaseModule.client.postgrest["goat_images"].delete {
                        filter {
                            eq("goat_id", validId)
                        }
                    }
                } catch (imgCleanupErr: Exception) {
                    Log.w(TAG, "Error during goat image cleanup: ${imgCleanupErr.message}")
                }
            }

            // Only remove from local state AFTER server confirmation!
            localAddedGoats.remove(validId)
            localAddedGoats.remove(goatId)
            invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete goat listing: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun updateGoatApprovalStatus(
        goatId: String,
        status: ApprovalStatus
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val validId = ensureValidUuid(goatId)
            val isApproved = status == ApprovalStatus.APPROVED
            val dbStatus = if (status == ApprovalStatus.REJECTED || status == ApprovalStatus.SUSPENDED) "INACTIVE" else "AVAILABLE"
            
            val localGoat = localAddedGoats[validId] ?: localAddedGoats[goatId]

            if (localGoat != null) {
                localAddedGoats[validId] = localGoat.copy(
                    approvalStatus = status,
                    availabilityStatus = if (isApproved) AvailabilityStatus.AVAILABLE else localGoat.availabilityStatus
                )
            }

            val updatePayload = buildJsonObject {
                put("is_approved_by_admin", isApproved)
                put("status", dbStatus)
            }

            SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(updatePayload) {
                filter {
                    eq("id", validId)
                }
            }
            invalidateCache()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update goat approval: ${e.message}", e)
            Result.failure(e)
        }
    }

    override fun getAvailableBreeds(): Flow<List<String>> = flow {
        if (!SupabaseConfig.isConfigured) {
            emit(emptyList())
            return@flow
        }
        try {
            val goatDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select(Columns.list("breed_name"))
                .decodeList<GoatDto>()

            val remoteBreeds = goatDtos.map { it.breedName }.filter { it.isNotBlank() }
            val localBreeds = localAddedGoats.values.map { it.breed }.filter { it.isNotBlank() }
            val breeds = (remoteBreeds + localBreeds).distinct().sorted()

            emit(breeds)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Notice: fetching breeds from Supabase unavailable: ${e.message}")
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getGoatsCount(): Result<Int> = withContext(Dispatchers.IO) {
        if (!SupabaseConfig.isConfigured) {
            return@withContext Result.success(localAddedGoats.size)
        }
        try {
            val goatDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select(Columns.list("id"))
                .decodeList<GoatDto>()
            val allIds = (goatDtos.map { it.id } + localAddedGoats.keys).distinct()
            Result.success(allIds.size)
        } catch (e: Exception) {
            Log.w(TAG, "Goat count query unavailable: ${e.message}")
            Result.success(localAddedGoats.size)
        }
    }

    private suspend fun fetchGoatsFromSupabase(
        onlyApproved: Boolean,
        onlyAvailable: Boolean,
        forceRefresh: Boolean = false
    ): List<Goat> {
        if (!SupabaseConfig.isConfigured) {
            var domainGoats = localAddedGoats.values.toList()
            if (onlyApproved) {
                domainGoats = domainGoats.filter { it.approvalStatus == ApprovalStatus.APPROVED }
            }
            if (onlyAvailable) {
                domainGoats = domainGoats.filter {
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE ||
                            it.availabilityStatus == AvailabilityStatus.BOOKING_PENDING
                }
            }
            return domainGoats
        }

        val now = System.currentTimeMillis()
        val cached = cachedRawGoats
        if (!forceRefresh && cached != null && (now - lastFetchTime < CACHE_TTL_MS)) {
            var domainGoats = cached
            if (onlyApproved) {
                domainGoats = domainGoats.filter { it.approvalStatus == ApprovalStatus.APPROVED }
            }
            if (onlyAvailable) {
                domainGoats = domainGoats.filter {
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE ||
                            it.availabilityStatus == AvailabilityStatus.BOOKING_PENDING
                }
            }
            return domainGoats
        }

        try {
            val goatDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                .select()
                .decodeList<GoatDto>()

            val farmMap = fetchFarmsMap()
            val photosMap = fetchGoatPhotosMap(goatDtos.map { it.id })

            val rawRemoteGoats = goatDtos.map { dto ->
                val farm = farmMap[dto.farmId]
                dto.toDomain(
                    resolvedFarmName = farm?.name,
                    resolvedFarmLocation = farm?.location,
                    resolvedPhotos = photosMap[dto.id]
                )
            }

            // Merge remote goats with any locally added/updated goats
            val mergedMap = mutableMapOf<String, Goat>()
            for (g in rawRemoteGoats) {
                mergedMap[g.id] = g
            }
            for ((id, g) in localAddedGoats) {
                mergedMap[id] = g
            }

            val rawDomainGoats = mergedMap.values.toList()

            cachedRawGoats = rawDomainGoats
            lastFetchTime = now
            var domainGoats = rawDomainGoats

            if (onlyApproved) {
                domainGoats = domainGoats.filter { it.approvalStatus == ApprovalStatus.APPROVED }
            }
            if (onlyAvailable) {
                domainGoats = domainGoats.filter {
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE ||
                            it.availabilityStatus == AvailabilityStatus.BOOKING_PENDING
                }
            }

            return domainGoats
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Supabase goat query failed: ${e.message}", e)
            throw e
        }
    }

    private suspend fun fetchFarmsMap(): Map<String, Farm> {
        if (!SupabaseConfig.isConfigured) {
            return emptyMap()
        }
        return try {
            val farmDtos = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                .select()
                .decodeList<FarmDto>()
            farmDtos.associate { it.id to it.toDomain() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Could not fetch remote farms map: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun fetchGoatPhotosMap(goatIds: List<String>): Map<String, List<String>> {
        return com.example.core.util.GoatImageResolver.fetchGoatPhotosMap(goatIds)
    }
}
