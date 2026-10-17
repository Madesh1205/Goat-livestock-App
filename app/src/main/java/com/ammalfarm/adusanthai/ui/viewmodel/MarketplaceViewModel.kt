package com.ammalfarm.adusanthai.ui.viewmodel

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
    val selectedPurpose: String = "All",
    val selectedDistrict: String = "All",
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
    val availableBreeds: List<String> = listOf("All", "Tellicherry", "Boer", "Kanni Adu", "Kodi Adu", "Salem Black", "Jamunapari", "Sirohi", "Osmanabadi")
) {
    val activeFilterCount: Int
        get() = listOf(
            selectedBreed != "All",
            selectedGender != "All",
            selectedPurpose != "All",
            selectedDistrict != "All",
            onlyAvailable
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
                    val dynamicBreeds = (listOf("All") + goats.map { it.breed }.filter { it.isNotBlank() } +
                            listOf("Tellicherry", "Boer", "Kanni Adu", "Kodi Adu", "Salem Black", "Jamunapari", "Sirohi"))
                        .distinct()
                    val updated = state.copy(
                        isLoading = loading,
                        allGoats = goats,
                        farms = farms,
                        bookings = bookings,
                        wishlistedIds = wishlists,
                        availableBreeds = dynamicBreeds
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
        val verifiedFarmIds = state.farms
            .filter { it.verificationStatus.isApprovedOrVerified || it.isAmmalOwnFarm }
            .map { it.id }
            .toSet()

        // Public Marketplace eligibility rules:
        // Only show listings that satisfy existing approval, availability, and visibility rules.
        val eligible = state.allGoats.filter { goat ->
            val isApproved = goat.approvalStatus == ApprovalStatus.APPROVED
            val isActive = goat.isActive && goat.availabilityStatus != AvailabilityStatus.UNAVAILABLE
            val farmOk = verifiedFarmIds.isEmpty() || goat.farmId.isBlank() || verifiedFarmIds.contains(goat.farmId)
            isApproved && isActive && farmOk
        }

        val query = state.searchQuery.trim().lowercase()
        val filtered = eligible.filter { goat ->
            val matchesQuery = if (query.isBlank()) true else {
                goat.name.lowercase().contains(query) ||
                goat.breed.lowercase().contains(query) ||
                goat.farmName.lowercase().contains(query) ||
                goat.farmLocation.lowercase().contains(query) ||
                goat.goatCode.lowercase().contains(query) ||
                goat.tagId.lowercase().contains(query) ||
                goat.purpose.lowercase().contains(query)
            }
            val matchesBreed = state.selectedBreed == "All" || goat.breed.equals(state.selectedBreed, ignoreCase = true)
            val matchesGender = state.selectedGender == "All" || goat.gender.equals(state.selectedGender, ignoreCase = true)
            val matchesPurpose = state.selectedPurpose == "All" || goat.purpose.equals(state.selectedPurpose, ignoreCase = true)
            val matchesDistrict = state.selectedDistrict == "All" || goat.farmLocation.contains(state.selectedDistrict, ignoreCase = true)
            val matchesAvailability = !state.onlyAvailable || goat.availabilityStatus == AvailabilityStatus.AVAILABLE

            matchesQuery && matchesBreed && matchesGender && matchesPurpose && matchesDistrict && matchesAvailability
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
        if (user == null) return
        viewModelScope.launch {
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

    fun selectBreed(breed: String) {
        _uiState.update { state ->
            val next = state.copy(selectedBreed = breed)
            next.copy(filteredGoats = applyCustomerFilters(next))
        }
    }

    fun updateFilters(
        breed: String = _uiState.value.selectedBreed,
        gender: String = _uiState.value.selectedGender,
        purpose: String = _uiState.value.selectedPurpose,
        district: String = _uiState.value.selectedDistrict,
        onlyAvailable: Boolean = _uiState.value.onlyAvailable,
        sortOption: SortOption = _uiState.value.sortOption
    ) {
        _uiState.update { state ->
            val next = state.copy(
                selectedBreed = breed,
                selectedGender = gender,
                selectedPurpose = purpose,
                selectedDistrict = district,
                onlyAvailable = onlyAvailable,
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
                selectedPurpose = "All",
                selectedDistrict = "All",
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
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, actionErrorMessage = null, actionSuccessMessage = null) }
            val result = repository.addOrUpdateGoat(goat, imageBytesList, isSuperAdmin)
            result.onSuccess {
                val msg = if (isSuperAdmin) "Goat listing published to marketplace."
                else "Goat listing saved! Pending verification by Super Admin."
                _uiState.update { it.copy(isLoading = false, actionSuccessMessage = msg) }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(isLoading = false, actionErrorMessage = err.message) }
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

    fun updateFarmProfile(farm: Farm) {
        viewModelScope.launch {
            val result = repository.updateFarmProfile(farm)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Farm profile updated successfully.") }
            }.onFailure { err ->
                _uiState.update { it.copy(actionErrorMessage = err.message) }
            }
        }
    }

    fun increaseFarmSlots(farmId: String, slots: Int) {
        viewModelScope.launch {
            val result = repository.increaseFarmListingLimit(farmId, slots)
            result.onSuccess {
                _uiState.update { it.copy(actionSuccessMessage = "Added $slots listing slots to farm.") }
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
