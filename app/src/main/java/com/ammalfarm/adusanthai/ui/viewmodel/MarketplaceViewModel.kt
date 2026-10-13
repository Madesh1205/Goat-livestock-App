package com.ammalfarm.adusanthai.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ViewMode {
    GRID,
    LIST
}

data class MarketplaceUiState(
    val currentUser: UserProfile? = null,
    val goats: List<Goat> = emptyList(),
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
            _uiState.update { it.copy(isLoading = true) }
            try {
                val pricing = repository.getPlatformPricing()
                val stats = repository.getPlatformStats()
                _uiState.update {
                    it.copy(
                        platformPricing = pricing,
                        platformStats = stats,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun updateFilter(criteria: GoatFilterCriteria) {
        _uiState.update { it.copy(filterCriteria = criteria) }
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
