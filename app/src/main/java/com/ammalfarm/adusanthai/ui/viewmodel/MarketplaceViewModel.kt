package com.ammalfarm.adusanthai.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ammalfarm.adusanthai.core.util.PlatformPricingManager
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class SortOption(val label: String) {
    NEWEST("Newest First"),
    PRICE_LOW_HIGH("Price: Low to High"),
    PRICE_HIGH_LOW("Price: High to Low"),
    WEIGHT_HIGH_LOW("Weight: High to Low"),
    AGE_LOW_HIGH("Age: Youngest First")
}

data class MarketplaceUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val actionSuccessMessage: String? = null,
    val actionErrorMessage: String? = null,
    val searchQuery: String = "",
    val selectedBreed: String = "All",
    val selectedGender: String = "All",
    val selectedDistrict: String = "All",
    val selectedFarm: String = "All",
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minAgeMonths: Int? = null,
    val maxAgeMonths: Int? = null,
    val minWeightKg: Double? = null,
    val maxWeightKg: Double? = null,
    val availabilityFilter: String = "All",
    val onlyAvailable: Boolean = false,
    val sortOption: SortOption = SortOption.NEWEST,
    val allGoats: List<Goat> = emptyList(),
    val filteredGoats: List<Goat> = emptyList(),
    val farms: List<Farm> = emptyList(),
    val bookings: List<Booking> = emptyList(),
    val wishlistedIds: Set<String> = emptySet(),
    val notifications: List<AppNotification> = emptyList(),
    val allUsers: List<UserProfile> = emptyList(),
    val reports: List<ListingReport> = emptyList(),
    val farmReviews: Map<String, List<FarmReview>> = emptyMap(),
    val availableBreeds: List<String> = listOf("All"),
    val availableFarms: List<String> = listOf("All"),
    val availableLocations: List<String> = listOf("All")
) {
    val activeFilterCount: Int
        get() = listOf(
            selectedBreed != "All",
            selectedGender != "All",
            selectedDistrict != "All",
            selectedFarm != "All",
            minPrice != null,
            maxPrice != null,
            minAgeMonths != null,
            maxAgeMonths != null,
            minWeightKg != null,
            maxWeightKg != null,
            onlyAvailable || availabilityFilter != "All"
        ).count { it }

    val platformStats: PlatformStats
        get() = PlatformStats(
            totalFarms = farms.size,
            verifiedFarms = farms.count { it.verificationStatus.isApprovedOrVerified || it.isAmmalOwnFarm },
            pendingFarms = farms.count { it.verificationStatus == VerificationStatus.PENDING && !it.isAmmalOwnFarm },
            totalGoats = allGoats.size,
            approvedGoats = allGoats.count { it.approvalStatus == ApprovalStatus.APPROVED },
            pendingGoats = allGoats.count { it.approvalStatus == ApprovalStatus.PENDING },
            availableGoats = allGoats.count { it.availabilityStatus == AvailabilityStatus.AVAILABLE && it.isActive },
            reservedGoats = allGoats.count { it.availabilityStatus == AvailabilityStatus.RESERVED },
            soldGoats = allGoats.count { it.availabilityStatus == AvailabilityStatus.SOLD },
            totalBookings = bookings.size,
            pendingBookings = bookings.count { it.status == BookingStatus.PENDING },
            confirmedBookings = bookings.count { it.status == BookingStatus.CONFIRMED || it.status == BookingStatus.APPROVED },
            completedBookings = bookings.count { it.status == BookingStatus.COMPLETED },
            totalUsers = allUsers.size,
            openReports = reports.count { it.status.equals("OPEN", ignoreCase = true) }
        )

    val goats: List<Goat>
        get() = filteredGoats

    val approvedFarms: List<Farm>
        get() = farms.filter { it.verificationStatus == VerificationStatus.APPROVED || it.verificationStatus == VerificationStatus.VERIFIED || it.isAmmalOwnFarm }

    val filterCriteria: MarketplaceUiState
        get() = this
}

class MarketplaceViewModel(
    private val repository: MarketplaceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState: StateFlow<MarketplaceUiState> = _uiState.asStateFlow()

    init {
        observeRepository()
        refreshMarketplace()
    }

    private fun observeRepository() {
        viewModelScope.launch {
            combine(
                repository.goats,
                repository.farms,
                repository.bookings,
                repository.wishlistedGoatIds,
                repository.isLoading
            ) { goats, farms, bookings, wishlists, loading ->
                 Quintuple(goats, farms, bookings, wishlists, loading)
            }.collect { (goats, farms, bookings, wishlists, loading) ->
                _uiState.update { state ->
                    val eligibleGoats = goats.filter { goat ->
                        goat.approvalStatus == ApprovalStatus.APPROVED &&
                        goat.isActive &&
                        goat.availabilityStatus != AvailabilityStatus.UNAVAILABLE
                    }
                    val farmsById = farms.associateBy { it.id }

                    val dynamicBreeds = if (eligibleGoats.isNotEmpty()) {
                        (listOf("All") + eligibleGoats.map { it.breed.trim() }.filter { it.isNotBlank() }.distinct().sorted())
                    } else {
                        state.availableBreeds.ifEmpty { listOf("All") }
                    }

                    val activeFarms = eligibleGoats.mapNotNull { g ->
                        val f = farmsById[g.farmId]
                        g.farmName.trim().ifBlank { f?.name?.trim().orEmpty() }.takeIf { it.isNotBlank() }
                    }.distinct().sorted()
                    val dynamicFarms = listOf("All") + activeFarms

                    val activeDistricts = eligibleGoats.flatMap { g ->
                        val f = farmsById[g.farmId]
                        listOfNotNull(
                            f?.district?.trim()?.takeIf { it.isNotBlank() },
                            g.farmLocation.substringBefore(",").trim().takeIf { it.isNotBlank() },
                            f?.location?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
                        )
                    }.filter { it.isNotBlank() }.distinct().sorted()
                    val dynamicLocations = listOf("All") + activeDistricts

                    val nextBreed = if (dynamicBreeds.contains(state.selectedBreed)) state.selectedBreed else "All"
                    val nextDistrict = if (dynamicLocations.contains(state.selectedDistrict)) state.selectedDistrict else "All"
                    val nextFarm = if (dynamicFarms.contains(state.selectedFarm)) state.selectedFarm else "All"

                    val updated = state.copy(
                        isLoading = loading,
                        allGoats = goats,
                        farms = farms,
                        bookings = bookings,
                        wishlistedIds = wishlists,
                        selectedBreed = nextBreed,
                        selectedDistrict = nextDistrict,
                        selectedFarm = nextFarm,
                        availableBreeds = dynamicBreeds,
                        availableFarms = dynamicFarms,
                        availableLocations = dynamicLocations
                    )
                    updated.copy(filteredGoats = applyCustomerFilters(updated))
                }
            }
        }

        viewModelScope.launch {
            repository.errorMessage.collect { err ->
                _uiState.update { it.copy(errorMessage = err) }
            }
        }

        viewModelScope.launch {
            repository.breeds.collect { _ ->
                _uiState.update { state ->
                    val eligibleGoats = state.allGoats.filter { goat ->
                        goat.approvalStatus == ApprovalStatus.APPROVED &&
                        goat.isActive &&
                        goat.availabilityStatus != AvailabilityStatus.UNAVAILABLE
                    }
                    val existingBreeds = eligibleGoats.map { it.breed.trim() }.filter { it.isNotBlank() }.distinct().sorted()
                    val dynamicBreeds = if (existingBreeds.isNotEmpty()) listOf("All") + existingBreeds else listOf("All")
                    val next = state.copy(availableBreeds = dynamicBreeds)
                    next.copy(filteredGoats = applyCustomerFilters(next))
                }
            }
        }

        viewModelScope.launch {
            repository.notifications.collect { list ->
                _uiState.update { it.copy(notifications = list) }
            }
        }

        viewModelScope.launch {
            repository.allUsers.collect { list ->
                _uiState.update { it.copy(allUsers = list) }
            }
        }

        viewModelScope.launch {
            repository.reports.collect { list ->
                _uiState.update { it.copy(reports = list) }
            }
        }

        viewModelScope.launch {
            repository.farmReviews.collect { map ->
                _uiState.update { it.copy(farmReviews = map) }
            }
        }
    }

    private fun applyCustomerFilters(state: MarketplaceUiState): List<Goat> {
        val farmsById = state.farms.associateBy { it.id }
        val verifiedFarmIds = state.farms
            .filter { it.verificationStatus.isApprovedOrVerified || it.isAmmalOwnFarm }
            .map { it.id }
            .toSet()

        // Public Marketplace eligibility rules:
        // Only show listings that satisfy existing approval, availability, and farm verification rules.
        val eligible = state.allGoats.filter { goat ->
            val isApproved = goat.approvalStatus == ApprovalStatus.APPROVED
            val isActive = goat.isActive && goat.availabilityStatus != AvailabilityStatus.UNAVAILABLE
            val farmOk = if (state.farms.isNotEmpty()) {
                verifiedFarmIds.contains(goat.farmId) || goat.farmId == com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
            } else {
                goat.isVerified || goat.farmId.isBlank() || goat.farmId == com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
            }
            isApproved && isActive && farmOk
        }

        val query = state.searchQuery.trim().lowercase()
        val filtered = eligible.filter { goat ->
            val linkedFarm = farmsById[goat.farmId]
            val effectiveFarmName = goat.farmName.ifBlank { linkedFarm?.name ?: "" }
            val effectiveFarmLocation = buildString {
                append(goat.farmLocation)
                if (linkedFarm != null) {
                    if (isNotEmpty()) append(" ")
                    append(linkedFarm.district).append(" ").append(linkedFarm.location)
                }
            }.trim()

            val matchesQuery = if (query.isBlank()) true else {
                goat.name.lowercase().contains(query) ||
                goat.breed.lowercase().contains(query) ||
                effectiveFarmName.lowercase().contains(query) ||
                effectiveFarmLocation.lowercase().contains(query) ||
                goat.description.lowercase().contains(query)
            }
            val matchesBreed = state.selectedBreed == "All" || goat.breed.equals(state.selectedBreed, ignoreCase = true)
            val matchesGender = state.selectedGender == "All" || goat.gender.equals(state.selectedGender, ignoreCase = true)
            val matchesDistrict = state.selectedDistrict == "All" ||
                effectiveFarmLocation.contains(state.selectedDistrict, ignoreCase = true) ||
                linkedFarm?.district?.equals(state.selectedDistrict, ignoreCase = true) == true
            val matchesFarm = state.selectedFarm == "All" ||
                effectiveFarmName.equals(state.selectedFarm, ignoreCase = true) ||
                goat.farmId.equals(state.selectedFarm, ignoreCase = true)

            val price = goat.effectivePrice
            val matchesMinPrice = state.minPrice == null || price >= state.minPrice
            val matchesMaxPrice = state.maxPrice == null || price <= state.maxPrice

            val matchesMinAge = state.minAgeMonths == null || goat.ageMonths >= state.minAgeMonths
            val matchesMaxAge = state.maxAgeMonths == null || goat.ageMonths <= state.maxAgeMonths

            val matchesMinWeight = state.minWeightKg == null || goat.weightKg >= state.minWeightKg
            val matchesMaxWeight = state.maxWeightKg == null || goat.weightKg <= state.maxWeightKg

            val matchesAvailability = when {
                state.availabilityFilter != "All" ->
                    goat.availabilityStatus.name.equals(state.availabilityFilter, ignoreCase = true)
                state.onlyAvailable ->
                    goat.availabilityStatus == AvailabilityStatus.AVAILABLE
                else -> true
            }

            matchesQuery &&
                matchesBreed &&
                matchesGender &&
                matchesDistrict &&
                matchesFarm &&
                matchesMinPrice &&
                matchesMaxPrice &&
                matchesMinAge &&
                matchesMaxAge &&
                matchesMinWeight &&
                matchesMaxWeight &&
                matchesAvailability
        }

        return when (state.sortOption) {
            SortOption.NEWEST -> filtered
            SortOption.PRICE_LOW_HIGH -> filtered.sortedBy { it.effectivePrice }
            SortOption.PRICE_HIGH_LOW -> filtered.sortedByDescending { it.effectivePrice }
            SortOption.WEIGHT_HIGH_LOW -> filtered.sortedByDescending { it.weightKg }
            SortOption.AGE_LOW_HIGH -> filtered.sortedBy { it.ageMonths }
        }
    }

    fun refreshMarketplace() {
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            repository.refreshAll()
        }
    }

    fun refreshAdminData() {
        viewModelScope.launch {
            repository.refreshAll()
            repository.refreshAllUsers()
            repository.refreshReports()
            repository.refreshBookings(isSuperAdmin = true)
        }
    }

    fun loadUserSpecificData(user: UserProfile?) {
        if (user == null) {
            repository.stopNotificationSubscription()
            return
        }
        repository.startNotificationSubscription(user.id)
        viewModelScope.launch {
            repository.refreshAll()
            repository.refreshWishlist(user.id)
            repository.refreshNotifications(user.id)
            repository.refreshBookings(
                userId = user.id,
                farmId = user.farmId,
                isSuperAdmin = user.role == UserRole.SUPER_ADMIN
            )
            if (user.role == UserRole.SUPER_ADMIN) {
                repository.refreshAllUsers()
                repository.refreshReports()
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { state ->
            val next = state.copy(searchQuery = query)
            next.copy(filteredGoats = applyCustomerFilters(next))
        }
    }

    fun setSearchQuery(query: String) = updateSearchQuery(query)
    fun removeSearchFilter() = updateSearchQuery("")

    fun selectBreed(breed: String) {
        _uiState.update { state ->
            val next = state.copy(selectedBreed = breed)
            next.copy(filteredGoats = applyCustomerFilters(next))
        }
    }

    fun updateFilters(
        breed: String = _uiState.value.selectedBreed,
        gender: String = _uiState.value.selectedGender,
        district: String = _uiState.value.selectedDistrict,
        farm: String = _uiState.value.selectedFarm,
        minPrice: Double? = _uiState.value.minPrice,
        maxPrice: Double? = _uiState.value.maxPrice,
        minAgeMonths: Int? = _uiState.value.minAgeMonths,
        maxAgeMonths: Int? = _uiState.value.maxAgeMonths,
        minWeightKg: Double? = _uiState.value.minWeightKg,
        maxWeightKg: Double? = _uiState.value.maxWeightKg,
        availabilityFilter: String = _uiState.value.availabilityFilter,
        onlyAvailable: Boolean = _uiState.value.onlyAvailable,
        sortOption: SortOption = _uiState.value.sortOption
    ) {
        _uiState.update { state ->
            val normalizedAvailFilter = if (onlyAvailable && availabilityFilter == "All") {
                "AVAILABLE"
            } else if (!onlyAvailable && availabilityFilter == "AVAILABLE") {
                "All"
            } else {
                availabilityFilter
            }
            val normalizedOnlyAvail = normalizedAvailFilter.equals("AVAILABLE", ignoreCase = true) || onlyAvailable
            val next = state.copy(
                selectedBreed = breed,
                selectedGender = gender,
                selectedDistrict = district,
                selectedFarm = farm,
                minPrice = minPrice,
                maxPrice = maxPrice,
                minAgeMonths = minAgeMonths,
                maxAgeMonths = maxAgeMonths,
                minWeightKg = minWeightKg,
                maxWeightKg = maxWeightKg,
                availabilityFilter = normalizedAvailFilter,
                onlyAvailable = normalizedOnlyAvail,
                sortOption = sortOption
            )
            next.copy(filteredGoats = applyCustomerFilters(next))
        }
    }

    fun clearFilters() {
        _uiState.update { state ->
            val next = state.copy(
                searchQuery = "",
                selectedBreed = "All",
                selectedGender = "All",
                selectedDistrict = "All",
                selectedFarm = "All",
                minPrice = null,
                maxPrice = null,
                minAgeMonths = null,
                maxAgeMonths = null,
                minWeightKg = null,
                maxWeightKg = null,
                availabilityFilter = "All",
                onlyAvailable = false,
                sortOption = SortOption.NEWEST
            )
            next.copy(filteredGoats = applyCustomerFilters(next))
        }
    }

    fun toggleWishlist(userId: String, goatId: String) {
        viewModelScope.launch {
            val res = repository.toggleWishlist(userId, goatId)
            if (res.isFailure) {
                _uiState.update { it.copy(actionErrorMessage = res.exceptionOrNull()?.message) }
            }
        }
    }

    fun createBooking(
        goat: Goat,
        customer: UserProfile,
        preferredVisitDate: String,
        advanceAmount: Double,
        notes: String,
        onSuccess: (Booking) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, actionErrorMessage = null, actionSuccessMessage = null) }
            val result = repository.createBooking(goat, customer, preferredVisitDate, advanceAmount, notes)
            result.onSuccess { booking ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        actionSuccessMessage = "Booking confirmed! Reference: ${booking.bookingCode.ifBlank { booking.id.take(8) }}"
                    )
                }
                onSuccess(booking)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        actionErrorMessage = err.message ?: "Unable to create booking."
                    )
                }
            }
        }
    }

    fun updateBookingStatus(bookingId: String, newStatus: BookingStatus, reason: String = "") {
        viewModelScope.launch {
            val result = repository.updateBookingStatus(bookingId, newStatus, reason)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Booking status updated to ${newStatus.name}.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun saveGoat(
        goat: Goat,
        imageBytesList: List<ByteArray> = emptyList(),
        isSuperAdmin: Boolean = false,
        actorUserId: String? = null,
        actorFarmId: String? = null,
        onSuccess: () -> Unit = {}
    ) {
        if (_uiState.value.isLoading) {
            Log.w("MarketplaceViewModel", "saveGoat ignored: another save operation is already in progress.")
            return
        }
        viewModelScope.launch {
            val isEditing = goat.id.isNotBlank() && _uiState.value.allGoats.any { it.id.equals(goat.id, ignoreCase = true) }
            _uiState.update { it.copy(isLoading = true, actionErrorMessage = null, actionSuccessMessage = null) }
            val result = repository.addOrUpdateGoat(
                goat = goat,
                imageBytesList = imageBytesList,
                isSuperAdmin = isSuperAdmin,
                actorUserId = actorUserId,
                actorFarmId = actorFarmId
            )
            result.onSuccess { savedGoat ->
                val msg = when {
                    isEditing -> "Goat listing updated successfully."
                    isSuperAdmin || savedGoat.approvalStatus == ApprovalStatus.APPROVED -> "Goat listing published to marketplace."
                    else -> "Goat listing saved! Pending verification by Super Admin."
                }
                _uiState.update { it.copy(isLoading = false, actionSuccessMessage = msg) }
                onSuccess()
            }.onFailure { err ->
                Log.e(
                    "MarketplaceViewModel",
                    "saveGoat failed (farmId=${goat.farmId}, goatId=${goat.id}, cause=${err.cause?.message ?: err.message})",
                    err
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        actionErrorMessage = err.message ?: "Unable to save goat listing. Please try again."
                    )
                }
            }
        }
    }

    fun deleteGoat(goatId: String) {
        viewModelScope.launch {
            val result = repository.deleteGoat(goatId)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Goat listing removed.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun moderateGoatListing(goatId: String, status: ApprovalStatus, rejectionReason: String? = null) {
        viewModelScope.launch {
            val result = repository.updateGoatApproval(goatId, status, rejectionReason)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Goat listing ${status.name.lowercase()}.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun updateGoatAvailability(goatId: String, status: AvailabilityStatus) {
        viewModelScope.launch {
            val result = repository.updateGoatAvailability(goatId, status)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Goat marked as ${status.name}.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun moderateFarm(farmId: String, status: VerificationStatus, reason: String = "") {
        viewModelScope.launch {
            val result = repository.updateFarmVerification(farmId, status, reason)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Farm status updated to ${status.name}.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun updateFarmProfile(
        farm: Farm,
        logoBytes: ByteArray? = null,
        bannerBytes: ByteArray? = null,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, actionErrorMessage = null, actionSuccessMessage = null) }
            val result = repository.updateFarmProfile(farm, logoBytes, bannerBytes)
            result.onSuccess {
                _uiState.update { it.copy(isLoading = false, actionSuccessMessage = "Farm profile updated successfully.") }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, actionErrorMessage = err.message) }
            }
        }
    }

    fun increaseFarmSlots(farmId: String, slots: Int, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.increaseFarmListingLimit(farmId, slots)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Added $slots listing slots to farm.") }
                refreshAdminData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun updateFarmSlots(farmId: String, newTotalSlots: Int, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.updateFarmListingLimit(farmId, newTotalSlots)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Farm listing limit updated to $newTotalSlots slots.") }
                refreshAdminData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun deleteUserAccountByAdmin(userId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.deleteUserAccountByAdmin(userId)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "User account deleted. Farm suspended and records preserved.") }
                refreshAdminData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun loadFarmReviews(farmId: String) {
        viewModelScope.launch {
            repository.loadFarmReviews(farmId)
        }
    }

    fun submitFarmReview(farmId: String, customer: UserProfile, rating: Int, comment: String) {
        viewModelScope.launch {
            val res = repository.submitFarmReview(farmId, customer, rating, comment)
            res.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Thank you for reviewing this farm!") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun submitReport(goatId: String, reporterId: String, reason: String) {
        viewModelScope.launch {
            val res = repository.submitListingReport(goatId, reporterId, reason)
            res.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Listing report submitted to Super Admin.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun resolveReport(reportId: String, status: String = "RESOLVED") {
        viewModelScope.launch {
            val res = repository.resolveReport(reportId, status)
            res.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Report marked as $status.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun toggleUserSuspension(userId: String, suspend: Boolean) {
        viewModelScope.launch {
            val res = repository.toggleUserSuspension(userId, suspend)
            res.onSuccess {
                _uiState.update {
                    it.copy(actionSuccessMessage = if (suspend) "User account suspended." else "User account reactivated.")
                }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun updatePlatformPricing(approvalFee: Double, packFee: Double, packSize: Int) {
        viewModelScope.launch {
            val res = repository.updatePlatformPricing(approvalFee, packFee, packSize)
            res.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Platform pricing settings updated.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            repository.markNotificationRead(notificationId)
        }
    }

    fun refreshNotifications(userId: String) {
        if (userId.isNotBlank()) {
            viewModelScope.launch {
                repository.refreshNotifications(userId)
            }
        }
    }

    fun startNotificationSubscription(userId: String) {
        if (userId.isNotBlank()) {
            repository.startNotificationSubscription(userId)
        }
    }

    fun stopNotificationSubscription() {
        repository.stopNotificationSubscription()
    }

    fun clearUserSession() {
        repository.clearUserSession()
    }

    fun markAllNotificationsRead(userId: String) {
        if (userId.isNotBlank()) {
            viewModelScope.launch {
                repository.markAllNotificationsRead(userId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopNotificationSubscription()
    }

    fun clearActionMessages() {
        _uiState.update { it.copy(actionSuccessMessage = null, actionErrorMessage = null) }
    }

    private data class Quintuple<A, B, C, D, E>(
        val first: A,
        val second: B,
        val third: C,
        val fourth: D,
        val fifth: E
    )
}
