package com.ammalfarm.adusanthai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ViewMode {
    GRID,
    LIST
}

data class MarketplaceUiState(
    val currentUser: UserProfile? = null,
    val goats: List<Goat> = emptyList(),
    val farmGoats: List<Goat> = emptyList(),
    val allAdminGoats: List<Goat> = emptyList(),
    val allBreeds: List<String> = emptyList(),
    val farms: List<Farm> = emptyList(),
    val customerBookings: List<Booking> = emptyList(),
    val farmBookings: List<Booking> = emptyList(),
    val allBookings: List<Booking> = emptyList(),
    val allCustomers: List<UserProfile> = emptyList(),
    val allReports: List<PlatformReport> = emptyList(),
    val listingPayments: List<ListingPayment> = emptyList(),
    val wishlistItems: List<WishlistItem> = emptyList(),
    val wishlistGoatIds: Set<String> = emptySet(),
    val notifications: List<AppNotification> = emptyList(),
    val platformPricing: PlatformPricing = PlatformPricing(),
    val platformStats: PlatformStats = PlatformStats(),
    val filterCriteria: GoatFilterCriteria = GoatFilterCriteria(),
    val viewMode: ViewMode = ViewMode.GRID,
    val selectedGoat: Goat? = null,
    val selectedFarm: Farm? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    val approvedFarms: List<Farm> get() = farms.filter { it.verificationStatus == VerificationStatus.APPROVED }
    val searchQuery: String get() = filterCriteria.searchQuery
    val selectedBreed: String? get() = filterCriteria.breed
    val selectedFarmId: String? get() = filterCriteria.farmId
    val minPrice: Double? get() = filterCriteria.minPrice
    val maxPrice: Double? get() = filterCriteria.maxPrice
    val sortBy: SortOption get() = filterCriteria.sortBy
    val wishlistedGoats: List<Goat> get() = goats.filter { wishlistGoatIds.contains(it.id) }
}

class MarketplaceViewModel(
    private val repository: MarketplaceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarketplaceUiState())
    val uiState: StateFlow<MarketplaceUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val pricing = repository.getPlatformPricing()
                val stats = repository.getPlatformStats()
                
                // Collect goats based on current filter criteria
                repository.searchAndFilterGoats(_uiState.value.filterCriteria)
                    .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                    .collect { goatsList ->
                        _uiState.update {
                            it.copy(
                                goats = goatsList,
                                platformPricing = pricing,
                                platformStats = stats,
                                isLoading = false
                            )
                        }
                    }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load marketplace data.") }
            }
        }
    }

    fun loadGoatsForFarm(farmId: String) {
        viewModelScope.launch {
            repository.getGoatsByFarm(farmId)
                .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                .collect { list ->
                    _uiState.update { it.copy(farmGoats = list) }
                }
        }
    }

    fun updateFilter(criteria: GoatFilterCriteria) {
        _uiState.update { it.copy(filterCriteria = criteria) }
        viewModelScope.launch {
            repository.searchAndFilterGoats(criteria)
                .catch { e -> _uiState.update { it.copy(errorMessage = e.message) } }
                .collect { goatsList ->
                    _uiState.update { it.copy(goats = goatsList) }
                }
        }
    }

    fun addGoatListing(goat: Goat, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.addGoatListing(goat)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update { it.copy(successMessage = "Goat listed successfully!") }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to add goat listing.") }
            }
        }
    }

    fun deleteGoatListing(goatId: String, farmId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.deleteGoatListing(goatId)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update { it.copy(successMessage = "Listing deleted successfully.") }
                loadGoatsForFarm(farmId)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Failed to delete listing.") }
            }
        }
    }

    fun toggleWishlist(goatId: String) {
        viewModelScope.launch {
            val currentWishlist = _uiState.value.wishlistGoatIds.toMutableSet()
            if (currentWishlist.contains(goatId)) {
                currentWishlist.remove(goatId)
            } else {
                currentWishlist.add(goatId)
            }
            _uiState.update { it.copy(wishlistGoatIds = currentWishlist) }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }

    companion object {
        fun provideFactory(repository: MarketplaceRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return MarketplaceViewModel(repository) as T
                }
            }
    }
}
