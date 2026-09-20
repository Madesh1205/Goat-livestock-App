package com.example.data.repository

import android.util.Log
import com.example.core.supabase.SupabaseConfig
import com.example.core.supabase.SupabaseModule
import com.example.core.util.PriceUtils
import com.example.data.dto.BookingDto
import com.example.data.dto.FarmDto
import com.example.data.dto.GoatDeletionResponse
import com.example.data.dto.GoatDto
import com.example.data.dto.GoatImageDto
import com.example.data.dto.ProfileDto
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.data.dto.currentIsoTimestamp
import com.example.data.dto.ensureValidUuid
import com.example.data.dto.parseIsoTimestamp
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
import kotlinx.serialization.json.putJsonArray
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
                    resolvedPhotos = photosMap[goatDto.id],
                    resolvedFarmCode = farm?.farmCode
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
                    resolvedPhotos = photosMap[dto.id],
                    resolvedFarmCode = farm?.farmCode
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
                if (!profile?.farmId.isNullOrBlank() && (profile.farmId == requestedFarmId || profile.farmId == validRequestedFarmId)) {
                    return validRequestedFarmId
                }
                val specificFarm = try {
                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_FARMS]
                        .select { filter { eq("id", validRequestedFarmId) } }
                        .decodeSingleOrNull<FarmDto>()
                } catch (_: Exception) { null }
                if (specificFarm != null && (specificFarm.ownerId == userId || specificFarm.id == profile?.farmId)) {
                    return specificFarm.id
                }
                if (ownedFarms.isNotEmpty()) {
                    return ownedFarms.first().id
                }
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
        val newlyUploadedStoragePaths = mutableListOf<String>()
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

            val farmMap = fetchFarmsMap()
            val targetFarmCode = farmMap[resolvedFarmId]?.farmCode ?: "FARM-001"

            val initialGoat = goat.copy(
                id = generatedId,
                farmId = resolvedFarmId,
                farmCode = targetFarmCode,
                approvalStatus = if (isAmmal) ApprovalStatus.APPROVED else ApprovalStatus.PENDING_APPROVAL,
                availabilityStatus = AvailabilityStatus.AVAILABLE,
                listingFeePaid = isAmmal,
                listingFeeAmount = if (isAmmal) 0.0 else 100.0,
                photos = emptyList()
            )

            var serverGoatCode: String? = null

            if (SupabaseConfig.isConfigured) {
                val goatDto = GoatDto.fromDomain(initialGoat)
                var insertSuccess = false
                var lastInsertException: Exception? = null

                try {
                    val inserted = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                        .insert(goatDto) { select() }
                        .decodeSingle<GoatDto>()
                    insertSuccess = true
                    serverGoatCode = inserted.goatCode?.takeIf { it.isNotBlank() }
                    Log.i(TAG, "Successfully inserted goat ${initialGoat.name} (${initialGoat.id}), assigned goat_code: $serverGoatCode")
                } catch (insertErr: Exception) {
                    lastInsertException = insertErr
                    Log.e(TAG, "Goat insert failed: ${insertErr.message}")
                }

                if (!insertSuccess) {
                    throw lastInsertException ?: RuntimeException("Failed to insert goat record into database")
                }

                if (serverGoatCode.isNullOrBlank()) {
                    try {
                        val fetched = SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS]
                            .select { filter { eq("id", generatedId) } }
                            .decodeSingleOrNull<GoatDto>()
                        serverGoatCode = fetched?.goatCode?.takeIf { it.isNotBlank() }
                    } catch (_: Exception) {}
                }
            }

            if (serverGoatCode.isNullOrBlank()) {
                serverGoatCode = "GOAT-" + generatedId.take(6).uppercase()
            }

            // Process photos using the authoritative server-assigned goat_code
            val finalPhotos = mutableListOf<String>()
            for ((index, photoStr) in goat.photos.withIndex()) {
                if (com.example.util.ImageUploadHelper.isLocalUri(photoStr)) {
                    val context = try { com.example.AmmalFarmApplication.instance.applicationContext } catch (_: Exception) { null }
                    if (context != null) {
                        val uri = android.net.Uri.parse(photoStr)
                        val bytes = com.example.util.ImageUploadHelper.compressAndResizeImage(context, uri)
                            ?: throw IllegalArgumentException("Could not read image from local URI: $photoStr")

                        val uploadResult = com.example.util.ImageUploadHelper.uploadGoatImage(
                            context = context,
                            bytes = bytes,
                            goatId = generatedId,
                            photoIndex = index + 1,
                            farmId = resolvedFarmId,
                            farmCode = targetFarmCode,
                            goatCode = serverGoatCode
                        )

                        val uploadedUrl = uploadResult.getOrElse { err ->
                            Log.w(TAG, "Warning: Image upload failed for index $index: ${err.message}")
                            photoStr
                        }

                        if (!com.example.util.ImageUploadHelper.isLocalUri(uploadedUrl)) {
                            val storagePath = SupabaseConfig.extractStoragePath(uploadedUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                            if (storagePath.isNotBlank()) {
                                newlyUploadedStoragePaths.add(storagePath)
                            }
                            finalPhotos.add(uploadedUrl)
                        } else {
                            finalPhotos.add(uploadedUrl)
                        }
                    } else {
                        finalPhotos.add(photoStr)
                    }
                } else {
                    finalPhotos.add(photoStr)
                }
            }

            val cleanGoat = initialGoat.copy(
                goatCode = serverGoatCode ?: "",
                photos = finalPhotos
            )

            // Save photos in goat_images table if any
            if (SupabaseConfig.isConfigured && cleanGoat.photos.isNotEmpty()) {
                val nowIso = currentIsoTimestamp()
                val imageDtos = cleanGoat.photos.mapIndexed { index, pathOrUrl ->
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

            // Only update local state after server confirmation!
            localAddedGoats[cleanGoat.id] = cleanGoat
            invalidateCache()
            Result.success(cleanGoat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add goat listing: ${e.message}", e)
            if (newlyUploadedStoragePaths.isNotEmpty()) {
                com.example.util.ImageUploadHelper.deleteStorageFiles(newlyUploadedStoragePaths)
            }
            Result.failure(e)
        }
    }

    override suspend fun updateGoatListing(goat: Goat): Result<Goat> = withContext(Dispatchers.IO) {
        val newlyUploadedStoragePaths = mutableListOf<String>()
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

            var existingDto: GoatDto? = null

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

                existingDto = try {
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

            // Process replacement / new local images
            val finalPhotos = mutableListOf<String>()
            for ((index, photoStr) in goat.photos.withIndex()) {
                if (com.example.util.ImageUploadHelper.isLocalUri(photoStr)) {
                    val context = try { com.example.AmmalFarmApplication.instance.applicationContext } catch (_: Exception) { null }
                    if (context != null) {
                        val uri = android.net.Uri.parse(photoStr)
                        val bytes = com.example.util.ImageUploadHelper.compressAndResizeImage(context, uri)
                            ?: throw IllegalArgumentException("Could not read image from local URI: $photoStr")

                        val farmMap = fetchFarmsMap()
                        val effectiveFarmCode = goat.farmCode.ifBlank { farmMap[goat.farmId]?.farmCode ?: "FARM-001" }
                        val effectiveGoatCode = goat.goatCode.ifBlank { existingDto?.goatCode }
                        val uploadResult = com.example.util.ImageUploadHelper.uploadGoatImage(
                            context = context,
                            bytes = bytes,
                            goatId = validId,
                            photoIndex = index + 1,
                            farmId = goat.farmId,
                            farmCode = effectiveFarmCode,
                            goatCode = effectiveGoatCode
                        )

                        val uploadedUrl = uploadResult.getOrElse { err ->
                            throw RuntimeException("Failed to upload replacement image ${index + 1}: ${err.message}", err)
                        }

                        if (com.example.util.ImageUploadHelper.isLocalUri(uploadedUrl)) {
                            throw RuntimeException("Upload returned local URI, remote storage upload failed")
                        }

                        val storagePath = SupabaseConfig.extractStoragePath(uploadedUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                        if (storagePath.isNotBlank()) {
                            newlyUploadedStoragePaths.add(storagePath)
                        }
                        finalPhotos.add(uploadedUrl)
                    } else {
                        throw IllegalStateException("Context not available to upload local URI $photoStr")
                    }
                } else {
                    finalPhotos.add(photoStr)
                }
            }

            val validFarmId = ensureValidUuid(goat.farmId)
            val cleanGoat = goat.copy(id = validId, farmId = validFarmId, photos = finalPhotos)
            val dto = GoatDto.fromDomain(cleanGoat)

            if (SupabaseConfig.isConfigured) {
                // Fetch existing images before update
                val existingImages = try {
                    SupabaseModule.client.postgrest["goat_images"]
                        .select {
                            filter {
                                eq("goat_id", validId)
                            }
                        }.decodeList<GoatImageDto>()
                } catch (_: Exception) { emptyList() }

                SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].update(dto) {
                    filter {
                        eq("id", validId)
                    }
                }

                // Delete old goat_images DB records
                SupabaseModule.client.postgrest["goat_images"].delete {
                    filter {
                        eq("goat_id", validId)
                    }
                }

                if (cleanGoat.photos.isNotEmpty()) {
                    val nowIso = currentIsoTimestamp()
                    val newImageDtos = cleanGoat.photos.mapIndexed { index, pathOrUrl ->
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

                // ONLY AFTER new uploads & DB update succeed: delete removed old Storage files
                val currentStoragePaths = cleanGoat.photos.map { SupabaseConfig.extractStoragePath(it, SupabaseConfig.BUCKET_GOAT_IMAGES) }.toSet()
                val removedFiles = existingImages.filter { img ->
                    val imgPath = SupabaseConfig.extractStoragePath(img.imageUrl, SupabaseConfig.BUCKET_GOAT_IMAGES)
                    imgPath.isNotBlank() && !currentStoragePaths.contains(imgPath) && !com.example.util.ImageUploadHelper.isProtectedStoragePath(imgPath)
                }.map { it.imageUrl }

                if (removedFiles.isNotEmpty()) {
                    com.example.util.ImageUploadHelper.deleteStorageFiles(removedFiles)
                }
            }

            // Only update local cache after successful server operation!
            localAddedGoats[cleanGoat.id] = cleanGoat
            invalidateCache()
            Result.success(cleanGoat)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update goat listing, rolling back new partial uploads: ${e.message}", e)
            if (newlyUploadedStoragePaths.isNotEmpty()) {
                com.example.util.ImageUploadHelper.deleteStorageFiles(newlyUploadedStoragePaths)
            }
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

                // 1. Attempt server-authoritative deletion RPC
                var rpcExecuted = false
                var storagePathsToDelete = emptyList<String>()

                try {
                    val rpcResponse = SupabaseModule.client.postgrest.rpc(
                        function = "delete_goat_listing_secure",
                        parameters = buildJsonObject {
                            put("p_goat_id", validId)
                        }
                    ).decodeSingleOrNull<GoatDeletionResponse>()

                    if (rpcResponse != null && rpcResponse.success) {
                        rpcExecuted = true
                        storagePathsToDelete = rpcResponse.deletedImages
                        Log.i(TAG, "delete_goat_listing_secure RPC succeeded. ${storagePathsToDelete.size} images to clean up. Historical bookings preserved: ${rpcResponse.historicalBookingsPreserved}")
                    }
                } catch (rpcErr: Exception) {
                    val errMsg = rpcErr.message ?: ""
                    if (errMsg.contains("Unauthorized", ignoreCase = true) ||
                        errMsg.contains("Caller profile not found", ignoreCase = true)) {
                        return@withContext Result.failure(rpcErr)
                    }
                    Log.w(TAG, "delete_goat_listing_secure RPC unavailable or failed, falling back to direct secure flow: ${rpcErr.message}")
                }

                // 2. Direct fallback flow if RPC was not available
                if (!rpcExecuted) {
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

                    // Auto-expire or cancel booking holds and unlink foreign keys
                    val allBookings = try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_BOOKINGS]
                            .select {
                                filter {
                                    eq("goat_id", validId)
                                }
                            }.decodeList<BookingDto>()
                    } catch (_: Exception) { emptyList() }

                    for (b in allBookings) {
                        if (b.status in listOf("PENDING", "RESERVED", "CONFIRMED")) {
                            try {
                                SupabaseModule.client.postgrest.rpc(
                                    function = "cancel_booking",
                                    parameters = buildJsonObject {
                                        put("p_booking_id", b.id)
                                        put("p_reason", "Goat listing deleted by farm owner")
                                    }
                                )
                            } catch (_: Exception) {}
                        }
                    }

                    // Unlink listing payments if any
                    try {
                        SupabaseModule.client.postgrest[SupabaseConfig.TABLE_PAYMENTS].update(
                            buildJsonObject { put("goat_id", null as String?) }
                        ) {
                            filter { eq("goat_id", validId) }
                        }
                    } catch (_: Exception) {}

                    // Retrieve image URLs BEFORE deleting goat records
                    val existingImages = try {
                        SupabaseModule.client.postgrest["goat_images"]
                            .select {
                                filter {
                                    eq("goat_id", validId)
                                }
                            }.decodeList<GoatImageDto>()
                    } catch (_: Exception) { emptyList() }

                    storagePathsToDelete = existingImages.map { it.imageUrl }

                    // Delete database records
                    try {
                        SupabaseModule.client.postgrest["wishlist"].delete {
                            filter { eq("goat_id", validId) }
                        }
                    } catch (_: Exception) {}

                    try {
                        SupabaseModule.client.postgrest["reports"].delete {
                            filter { eq("goat_id", validId) }
                        }
                    } catch (_: Exception) {}

                    try {
                        SupabaseModule.client.postgrest["goat_images"].delete {
                            filter { eq("goat_id", validId) }
                        }
                    } catch (_: Exception) {}

                    SupabaseModule.client.postgrest[SupabaseConfig.TABLE_GOATS].delete {
                        filter {
                            eq("id", validId)
                        }
                    }
                }

                // 3. Storage Image Cleanup: delete actual stored paths from Supabase Storage
                if (storagePathsToDelete.isNotEmpty()) {
                    val cleanupResult = com.example.util.ImageUploadHelper.deleteStorageFiles(storagePathsToDelete)
                    if (cleanupResult.isSuccess) {
                        try {
                            SupabaseModule.client.postgrest.rpc(
                                function = "complete_storage_cleanup",
                                parameters = buildJsonObject {
                                    put("p_goat_id", validId)
                                    putJsonArray("p_completed_paths") {
                                        storagePathsToDelete.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                                    }
                                }
                            )
                        } catch (_: Exception) {}
                    } else {
                        Log.w(TAG, "Storage cleanup reported error: ${cleanupResult.exceptionOrNull()?.message}")
                    }
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
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE
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
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE
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
                    resolvedPhotos = photosMap[dto.id],
                    resolvedFarmCode = farm?.farmCode
                )
            }

            // Remote goats from Supabase are authoritative
            val mergedMap = mutableMapOf<String, Goat>()
            for ((id, g) in localAddedGoats) {
                mergedMap[id] = g
            }
            for (g in rawRemoteGoats) {
                mergedMap[g.id] = g // Authoritative database record overrides local cache
                localAddedGoats[g.id] = g // Keep local in sync
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
                    it.availabilityStatus == AvailabilityStatus.AVAILABLE
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
