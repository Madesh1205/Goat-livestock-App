package com.example.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.supabase.SupabaseConfig
import com.example.data.dto.SEED_AMMAL_FARM_UUID
import com.example.data.dto.ensureValidUuid
import com.example.data.repository.MarketplaceRepository
import com.example.model.*
import com.example.util.UserFriendlyErrorMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    val platformStats: PlatformStats = PlatformStats(),
    val filterCriteria: GoatFilterCriteria = GoatFilterCriteria(),
    val viewMode: ViewMode = ViewMode.GRID,
    val selectedGoat: Goat? = null,
    val selectedFarm: Farm? = null,
    val selectedFarmGoats: List<Goat> = emptyList(),
    val selectedFarmTotalListed: Int = 0,
    val isSelectedFarmLoading: Boolean = false,
    val selectedFarmError: String? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isRetrying: Boolean = false,
    val networkError: String? = null,
    val errorMessage: String? = null,
    val successMessage: String? = null
) {
    // Backward compatibility helpers
    val approvedFarms: List<Farm> get() = farms.filter { it.verificationStatus == VerificationStatus.APPROVED }
    val searchQuery: String get() = filterCriteria.searchQuery
    val selectedBreed: String? get() = filterCriteria.breed
    val selectedPurpose: GoatPurpose? get() = filterCriteria.purpose
    val selectedFarmId: String? get() = filterCriteria.farmId
    val minPrice: Double? get() = filterCriteria.minPrice
    val maxPrice: Double? get() = filterCriteria.maxPrice
    val sortBy: SortOption get() = filterCriteria.sortBy
    val wishlistedGoats: List<Goat> get() {
        val goatMap = goats.associateBy { it.id }
        return wishlistItems.mapNotNull { item ->
            item.goat ?: goatMap[item.goatId] ?: allAdminGoats.find { it.id == item.goatId }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MarketplaceViewModel(
    private val repository: MarketplaceRepository
) : ViewModel() {

    private val _filterCriteria = MutableStateFlow(GoatFilterCriteria())
    val filterCriteria: StateFlow<GoatFilterCriteria> = _filterCriteria.asStateFlow()

    private val _refreshTrigger = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1).apply { tryEmit(Unit) }

    private val _uiState = MutableStateFlow(MarketplaceUiState(isLoading = true))
    val uiState: StateFlow<MarketplaceUiState> = _uiState.asStateFlow()

    private val inFlightOperations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        observeData()
        observeFilteredGoats()
    }

    private fun observeData() {
        viewModelScope.launch {
            repository.getCurrentUser()
                .catch { e -> Log.w("MarketplaceVM", "Error in getCurrentUser: ${e.message}") }
                .collect { user ->
                    val prevUser = _uiState.value.currentUser
                    _uiState.update { s ->
                        if (user == null) {
                            s.copy(
                                currentUser = null,
                                customerBookings = emptyList(),
                                farmBookings = emptyList(),
                                wishlistItems = emptyList(),
                                wishlistGoatIds = emptySet(),
                                notifications = emptyList(),
                                allAdminGoats = emptyList(),
                                allBookings = emptyList(),
                                allCustomers = emptyList(),
                                allReports = emptyList(),
                                platformStats = PlatformStats()
                            )
                        } else {
                            s.copy(currentUser = user)
                        }
                    }
                    if (user != prevUser) {
                        loadRoleScopedData(user)
                    }
                }
        }

        viewModelScope.launch {
            repository.getCurrentUser()
                .mapNotNull { user -> if (user?.role == UserRole.CUSTOMER) user.id else null }
                .distinctUntilChanged()
                .flatMapLatest { customerId ->
                    repository.getCustomerBookings(customerId)
                        .catch { e -> handleNetworkOrSyncError(e, "getCustomerBookings") }
                }
                .collect { bookings ->
                    _uiState.update { s -> s.copy(customerBookings = bookings) }
                }
        }

        viewModelScope.launch {
            repository.getCurrentUser()
                .mapNotNull { user -> if (user?.role == UserRole.FARM_ADMIN) (user.farmId ?: user.id) else null }
                .distinctUntilChanged()
                .flatMapLatest { farmId ->
                    repository.getFarmBookings(farmId)
                        .catch { e -> handleNetworkOrSyncError(e, "getFarmBookings") }
                }
                .collect { bookings ->
                    _uiState.update { s -> s.copy(farmBookings = bookings, allBookings = bookings) }
                }
        }

        viewModelScope.launch {
            repository.getCurrentUser()
                .distinctUntilChangedBy { it?.id }
                .flatMapLatest { user ->
                    if (user == null) {
                        flowOf(emptyList())
                    } else {
                        repository.getWishlistForUser(user.id)
                            .catch { e -> handleNetworkOrSyncError(e, "getWishlistForUser") }
                    }
                }
                .collect { items ->
                    _uiState.update { s ->
                        s.copy(
                            wishlistItems = items,
                            wishlistGoatIds = items.map { it.goatId }.toSet()
                        )
                    }
                }
        }

        viewModelScope.launch {
            repository.getCurrentUser()
                .distinctUntilChangedBy { it?.id to it?.role }
                .flatMapLatest { user ->
                    when (user?.role) {
                        UserRole.SUPER_ADMIN -> {
                            repository.getAllListingPayments()
                                .catch { e -> handleNetworkOrSyncError(e, "getAllListingPayments") }
                        }
                        UserRole.FARM_ADMIN -> {
                            val farmId = user.farmId ?: user.id
                            repository.getListingPaymentsForFarm(farmId)
                                .catch { e -> handleNetworkOrSyncError(e, "getListingPaymentsForFarm") }
                        }
                        else -> flowOf(emptyList())
                    }
                }
                .collect { payments ->
                    _uiState.update { s -> s.copy(listingPayments = payments) }
                }
        }

        // Initially load public marketplace data accessible to everyone
        loadPublicMarketplaceData()
    }

    private fun isAuthOrPermissionError(e: Throwable): Boolean {
        val fullMessage = buildString {
            append(e.message ?: "")
            append(" ")
            append(e.cause?.message ?: "")
            append(" ")
            append(e.javaClass.name)
        }
        return fullMessage.contains("permission denied", ignoreCase = true) ||
                fullMessage.contains("row-level security", ignoreCase = true) ||
                fullMessage.contains("insufficient_privilege", ignoreCase = true) ||
                fullMessage.contains("42501", ignoreCase = true) ||
                fullMessage.contains("PGRST301", ignoreCase = true) ||
                fullMessage.contains("unauthorized", ignoreCase = true) ||
                fullMessage.contains("forbidden", ignoreCase = true) ||
                fullMessage.contains("401", ignoreCase = true) ||
                fullMessage.contains("403", ignoreCase = true) ||
                fullMessage.contains("access denied", ignoreCase = true)
    }

    private fun isRealNetworkOrServerError(e: Throwable): Boolean {
        if (isAuthOrPermissionError(e)) return false
        val fullMessage = buildString {
            append(e.message ?: "")
            append(" ")
            append(e.cause?.message ?: "")
            append(" ")
            append(e.javaClass.name)
        }
        return e is java.net.UnknownHostException ||
                e is java.net.SocketTimeoutException ||
                e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException ||
                e is java.io.IOException ||
                fullMessage.contains("resolve host", ignoreCase = true) ||
                fullMessage.contains("connect", ignoreCase = true) ||
                fullMessage.contains("timeout", ignoreCase = true) ||
                fullMessage.contains("network", ignoreCase = true) ||
                fullMessage.contains("offline", ignoreCase = true) ||
                fullMessage.contains("unreachable", ignoreCase = true) ||
                fullMessage.contains("500", ignoreCase = true) ||
                fullMessage.contains("502", ignoreCase = true) ||
                fullMessage.contains("503", ignoreCase = true) ||
                fullMessage.contains("504", ignoreCase = true)
    }

    private fun handleNetworkOrSyncError(e: Throwable, tag: String) {
        if (isAuthOrPermissionError(e)) {
            Log.w("MarketplaceVM", "Access restricted for $tag (expected per role permissions): ${e.message}")
            return
        }

        val message = e.message ?: ""
        if (!SupabaseConfig.isConfigured || message.contains("Database not connected", ignoreCase = true)) {
            Log.w("MarketplaceVM", "Notice: database not connected or unconfigured for $tag: $message")
            return
        }

        Log.w("MarketplaceVM", "Background data sync notice for $tag: ${e.message}")
    }

    fun loadPublicMarketplaceData() {
        viewModelScope.launch {
            repository.getAllFarms()
                .catch { e -> handleNetworkOrSyncError(e, "getAllFarms") }
                .collect { list ->
                    _uiState.update { it.copy(farms = list) }
                }
        }

        viewModelScope.launch {
            repository.getAvailableBreeds()
                .catch { e -> handleNetworkOrSyncError(e, "getAvailableBreeds") }
                .collect { list ->
                    _uiState.update { it.copy(allBreeds = list) }
                }
        }
    }

    fun loadRoleScopedData(user: UserProfile?) {
        if (user == null) {
            _uiState.update {
                it.copy(
                    allAdminGoats = emptyList(),
                    allBookings = emptyList(),
                    allCustomers = emptyList(),
                    allReports = emptyList(),
                    platformStats = PlatformStats(),
                    notifications = emptyList()
                )
            }
            return
        }

        val isSuperAdmin = user.role == UserRole.SUPER_ADMIN
        val isFarmAdmin = user.role == UserRole.FARM_ADMIN

        when {
            isSuperAdmin -> {
                loadSuperAdminData(user)
            }
            isFarmAdmin -> {
                loadFarmAdminData(user)
            }
            else -> {
                loadCustomerData(user)
            }
        }
    }

    private fun loadSuperAdminData(user: UserProfile) {
        viewModelScope.launch {
            repository.getAllGoatsForAdmin()
                .catch { e -> handleNetworkOrSyncError(e, "getAllGoatsForAdmin") }
                .collect { list ->
                    _uiState.update { it.copy(allAdminGoats = list) }
                }
        }

        viewModelScope.launch {
            repository.getAllBookings()
                .catch { e -> handleNetworkOrSyncError(e, "getAllBookings") }
                .collect { list ->
                    _uiState.update { it.copy(allBookings = list) }
                }
        }

        viewModelScope.launch {
            repository.getAllCustomers()
                .catch { e -> handleNetworkOrSyncError(e, "getAllCustomers") }
                .collect { list ->
                    _uiState.update { it.copy(allCustomers = list) }
                }
        }

        viewModelScope.launch {
            repository.getAllReports()
                .catch { e -> handleNetworkOrSyncError(e, "getAllReports") }
                .collect { list ->
                    _uiState.update { it.copy(allReports = list) }
                }
        }

        viewModelScope.launch {
            repository.getPlatformStats()
                .catch { e -> handleNetworkOrSyncError(e, "getPlatformStats") }
                .collect { stats ->
                    _uiState.update { it.copy(platformStats = stats) }
                }
        }

        viewModelScope.launch {
            repository.getNotificationsForUser(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getNotificationsForUser") }
                .collect { list ->
                    _uiState.update { it.copy(notifications = list) }
                }
        }
    }

    private fun loadFarmAdminData(user: UserProfile) {
        val farmId = user.farmId ?: user.id
        viewModelScope.launch {
            repository.getGoatsByFarm(farmId)
                .catch { e -> handleNetworkOrSyncError(e, "getGoatsByFarm") }
                .collect { list ->
                    _uiState.update { it.copy(allAdminGoats = list) }
                }
        }

        viewModelScope.launch {
            repository.getFarmBookings(farmId)
                .catch { e -> handleNetworkOrSyncError(e, "getFarmBookings") }
                .collect { list ->
                    _uiState.update { it.copy(farmBookings = list, allBookings = list) }
                }
        }

        viewModelScope.launch {
            repository.getNotificationsForUser(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getNotificationsForUser") }
                .collect { list ->
                    _uiState.update { it.copy(notifications = list) }
                }
        }
    }

    private fun loadCustomerData(user: UserProfile) {
        viewModelScope.launch {
            repository.getNotificationsForUser(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getNotificationsForUser") }
                .collect { list ->
                    _uiState.update { it.copy(notifications = list) }
                }
        }

        viewModelScope.launch {
            repository.getCustomerBookings(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getCustomerBookings") }
                .collect { list ->
                    _uiState.update { it.copy(customerBookings = list) }
                }
        }

        viewModelScope.launch {
            repository.getWishlistForUser(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getWishlistForUser") }
                .collect { list ->
                    _uiState.update {
                        it.copy(
                            wishlistItems = list,
                            wishlistGoatIds = list.map { w -> w.goatId }.toSet()
                        )
                    }
                }
        }
    }

    fun loadAllPlatformData() {
        loadPublicMarketplaceData()
        loadRoleScopedData(_uiState.value.currentUser)
    }

    private fun observeFilteredGoats() {
        viewModelScope.launch {
            combine(_filterCriteria.debounce(100L), _refreshTrigger) { criteria, _ -> criteria }
                .flatMapLatest { criteria ->
                    _uiState.update { it.copy(filterCriteria = criteria, isLoading = true) }
                    repository.searchAndFilterGoats(criteria)
                }
                .catch { e ->
                    val msg = e.message ?: ""
                    if (!SupabaseConfig.isConfigured || msg.contains("Database not connected", ignoreCase = true)) {
                        Log.w("MarketplaceVM", "Notice: database not connected or unconfigured in searchAndFilterGoats: $msg")
                    } else {
                        Log.e("MarketplaceVM", "Error in searchAndFilterGoats: ${e.message}", e)
                    }
                    _uiState.update { current ->
                        current.copy(
                            goats = if (current.goats.isNotEmpty()) current.goats else emptyList(),
                            isLoading = false,
                            isRefreshing = false,
                            networkError = if (current.goats.isEmpty() && SupabaseConfig.isConfigured) {
                                UserFriendlyErrorMapper.toUserMessage(e, "Unable to load goats. Tap retry to reconnect.")
                            } else null
                        )
                    }
                }
                .collect { filteredList ->
                    _uiState.update {
                        it.copy(
                            goats = filteredList,
                            isLoading = false,
                            isRefreshing = false,
                            networkError = null
                        )
                    }
                }
        }
    }

    fun refreshMarketplace() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, networkError = null) }
            repository.invalidateCache()
            // 1. Refresh public marketplace data (approved farms, reviews, available breeds)
            loadPublicMarketplaceData()
            // 2. Refresh role-authorized data
            val currentUser = _uiState.value.currentUser
            if (currentUser != null) {
                loadRoleScopedData(currentUser)
            }
            // 3. Trigger goat search/filter query
            _refreshTrigger.tryEmit(Unit)
            delay(300)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun setUser(user: UserProfile?) {
        val prevUser = _uiState.value.currentUser
        _uiState.update { it.copy(currentUser = user) }
        if (user != prevUser) {
            loadRoleScopedData(user)
        }
    }

    // --- Search and Filter Updates ---
    fun setSearchQuery(query: String) {
        val updated = _filterCriteria.updateAndGet { it.copy(searchQuery = query) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun updateFilterCriteria(criteria: GoatFilterCriteria) {
        _filterCriteria.value = criteria
        _uiState.update { it.copy(filterCriteria = criteria) }
    }

    fun selectBreed(breed: String?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(breed = if (it.breed == breed || breed.equals("All", ignoreCase = true)) null else breed)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun selectPurpose(purpose: GoatPurpose?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(purpose = if (it.purpose == purpose) null else purpose)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun selectFarmFilter(farmId: String?, farmName: String? = null) {
        val updated = _filterCriteria.updateAndGet {
            if (it.farmId == farmId) {
                it.copy(farmId = null, farmName = null)
            } else {
                it.copy(farmId = farmId, farmName = farmName)
            }
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun selectGender(gender: GoatGender?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(gender = if (it.gender == gender) null else gender)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setAgeRange(minMonths: Int?, maxMonths: Int?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(minAgeMonths = minMonths, maxAgeMonths = maxMonths)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setWeightRange(minKg: Double?, maxKg: Double?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(minWeightKg = minKg, maxWeightKg = maxKg)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setPriceRange(minPrice: Double?, maxPrice: Double?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(minPrice = minPrice, maxPrice = maxPrice)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun location(location: String?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(location = if (it.location == location) null else location)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setLocation(location: String?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(location = if (it.location == location) null else location)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setAvailability(status: AvailabilityStatus?) {
        val updated = _filterCriteria.updateAndGet {
            it.copy(availability = if (it.availability == status) null else status)
        }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun setSortOption(sort: SortOption) {
        val updated = _filterCriteria.updateAndGet { it.copy(sortBy = sort) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun clearFilters() {
        val reset = GoatFilterCriteria(
            searchQuery = "",
            sortBy = SortOption.RELEVANCE
        )
        _filterCriteria.value = reset
        _uiState.update { it.copy(filterCriteria = reset) }
    }

    fun removeBreedFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(breed = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeFarmFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(farmId = null, farmName = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeGenderFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(gender = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeAgeFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(minAgeMonths = null, maxAgeMonths = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeWeightFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(minWeightKg = null, maxWeightKg = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removePriceFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(minPrice = null, maxPrice = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removePurposeFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(purpose = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeLocationFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(location = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeAvailabilityFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(availability = null) }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun removeSearchFilter() {
        val updated = _filterCriteria.updateAndGet { it.copy(searchQuery = "") }
        _uiState.update { it.copy(filterCriteria = updated) }
    }

    fun toggleViewMode() {
        _uiState.update {
            it.copy(viewMode = if (it.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID)
        }
    }

    fun selectGoat(goat: Goat?) {
        _uiState.update { it.copy(selectedGoat = goat) }
    }

    fun selectFarm(farm: Farm?) {
        _uiState.update {
            it.copy(
                selectedFarm = farm,
                selectedFarmGoats = emptyList(),
                selectedFarmTotalListed = 0,
                isSelectedFarmLoading = farm != null,
                selectedFarmError = null
            )
        }
        if (farm != null) {
            loadFarmPublicGoats(farm.id)
        }
    }

    fun selectFarmById(farmId: String): Farm? {
        val farm = _uiState.value.farms.find { it.id == farmId }
        _uiState.update {
            it.copy(
                selectedFarm = farm,
                selectedFarmGoats = emptyList(),
                selectedFarmTotalListed = 0,
                isSelectedFarmLoading = farmId.isNotBlank(),
                selectedFarmError = null
            )
        }
        if (farmId.isNotBlank()) {
            loadFarmPublicGoats(farmId)
        }
        return farm
    }

    fun loadFarmPublicGoats(farmId: String) {
        if (farmId.isBlank()) return
        val validFarmId = try { ensureValidUuid(farmId.trim()) } catch (_: Exception) { farmId.trim() }
        viewModelScope.launch {
            _uiState.update { it.copy(isSelectedFarmLoading = true, selectedFarmError = null) }
            repository.getGoatsByFarm(validFarmId)
                .catch { e ->
                    Log.e("MarketplaceVM", "Notice: remote farm goats query failed for $validFarmId: ${e.message}", e)
                    _uiState.update {
                        it.copy(
                            selectedFarmGoats = emptyList(),
                            selectedFarmTotalListed = 0,
                            isSelectedFarmLoading = false,
                            selectedFarmError = "Unable to load goats for this farm. Please check your network connection and retry."
                        )
                    }
                }
                .collect { farmGoatsFromDb ->
                    // Filter strictly to the verified farm UUID and available/approved status
                    val eligiblePublicGoats = farmGoatsFromDb.filter {
                        (it.farmId == validFarmId || it.farmId == farmId.trim()) &&
                        it.approvalStatus == ApprovalStatus.APPROVED &&
                        it.availabilityStatus == AvailabilityStatus.AVAILABLE
                    }
                    _uiState.update {
                        it.copy(
                            selectedFarmGoats = eligiblePublicGoats,
                            selectedFarmTotalListed = eligiblePublicGoats.size,
                            isSelectedFarmLoading = false,
                            selectedFarmError = null
                        )
                    }
                }
        }
    }

    fun getApprovedGoatsForFarm(farmId: String): List<Goat> {
        val directGoats = _uiState.value.selectedFarmGoats.filter { it.farmId == farmId }
        if (directGoats.isNotEmpty()) return directGoats
        return _uiState.value.goats.filter {
            it.farmId == farmId &&
            it.approvalStatus == ApprovalStatus.APPROVED &&
            it.availabilityStatus == AvailabilityStatus.AVAILABLE
        }
    }

    fun createBooking(goatId: String, notes: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.createBooking(goatId, notes)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { newBooking ->
                val enrichedBooking = if (newBooking.goatPhoto.isBlank()) {
                    val fallbackPhoto = _uiState.value.goats.find { it.id == goatId }?.photos?.firstOrNull()
                        ?: com.example.core.util.GoatImageResolver.getCachedPhoto(goatId)
                        ?: ""
                    if (fallbackPhoto.isNotBlank()) newBooking.copy(goatPhoto = fallbackPhoto) else newBooking
                } else newBooking
                _uiState.update { state ->
                    state.copy(
                        customerBookings = listOf(enrichedBooking) + state.customerBookings,
                        allBookings = listOf(enrichedBooking) + state.allBookings,
                        successMessage = "Goat reserved successfully!"
                    )
                }
                loadAllPlatformData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forBooking(err)) }
            }
        }
    }

    fun updateBookingStatus(bookingId: String, status: AvailabilityStatus) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val result = repository.updateBookingStatus(bookingId, status)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allBookings = state.allBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                        customerBookings = state.customerBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                        farmBookings = state.farmBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                        successMessage = "Booking status updated to ${status.name}"
                    )
                }
                loadAllPlatformData()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update booking status")) }
                loadAllPlatformData()
            }
        }
    }

    fun addGoatListing(goat: Goat, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val opKey = "add_goat_${goat.tagNumber}_${goat.name}"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                val state = _uiState.value
                val farm = state.farms.find { it.id == goat.farmId }
                    ?: (if (state.currentUser?.farmId == goat.farmId) state.selectedFarm else null)

                val isAmmal = farm?.isAmmalOwnFarm == true || farm?.id == SEED_AMMAL_FARM_UUID
                val isSuperAdmin = state.currentUser?.role == UserRole.SUPER_ADMIN
                val limit = farm?.goatListingLimit ?: (if (isAmmal) 1000 else 10)

                // Count existing goats listed by this farm
                val farmGoatsCount = state.allAdminGoats.count {
                    it.farmId == goat.farmId || (farm != null && it.farmId == farm.id)
                }

                if (!isAmmal && !isSuperAdmin && farmGoatsCount >= limit) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Listing limit reached. Contact Super Admin to increase your listing limit."
                        )
                    }
                    return@launch
                }

                // Ammal Farm listings: auto-approved live.
                // Partner Farm listings: Submitted directly for Super Admin approval (Default limit: 10).
                val targetGoat = goat.copy(
                    approvalStatus = if (isAmmal) ApprovalStatus.APPROVED else ApprovalStatus.PENDING_APPROVAL,
                    availabilityStatus = AvailabilityStatus.AVAILABLE,
                    listingFeePaid = true,
                    listingFeeAmount = 0.0
                )

                _uiState.update { it.copy(isLoading = true) }
                val result = repository.addGoatListing(targetGoat)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess { addedGoat ->
                    _uiState.update { s ->
                        val updatedAdminGoats = listOf(addedGoat) + s.allAdminGoats.filterNot { it.id == addedGoat.id }
                        val updatedMarketplace = if (addedGoat.approvalStatus == ApprovalStatus.APPROVED) {
                            listOf(addedGoat) + s.goats.filterNot { it.id == addedGoat.id }
                        } else s.goats
                        val msg = if (isAmmal) {
                            "Ammal Farm goat auto-approved & published live!"
                        } else {
                            "Listing submitted successfully for Super Admin approval!"
                        }
                        s.copy(
                            allAdminGoats = updatedAdminGoats,
                            goats = updatedMarketplace,
                            successMessage = msg
                        )
                    }
                    loadAllPlatformData()
                    _refreshTrigger.tryEmit(Unit)
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forGoatListing(err)) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun initiateListingPayment(goatId: String, onResult: (ListingPaymentInitiation?) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.initiateListingPayment(goatId)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { initiation ->
                onResult(initiation)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forPayment(err)) }
                onResult(null)
            }
        }
    }

    fun verifyListingPayment(
        goatId: String,
        orderId: String,
        paymentId: String,
        signature: String,
        amount: Double = 100.0,
        onComplete: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.verifyListingPayment(goatId, orderId, paymentId, signature, amount)
            _uiState.update { it.copy(isLoading = false) }
            result.onSuccess { verification ->
                _uiState.update { s ->
                    val updatedAdminGoats = s.allAdminGoats.map { g ->
                        if (g.id == goatId) {
                            g.copy(
                                listingFeePaid = true,
                                listingFeeAmount = amount,
                                approvalStatus = verification.approvalStatus
                            )
                        } else g
                    }
                    s.copy(
                        allAdminGoats = updatedAdminGoats,
                        successMessage = verification.message
                    )
                }
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
                onComplete(true, verification.message)
            }.onFailure { err ->
                val msg = UserFriendlyErrorMapper.forPayment(err)
                _uiState.update { it.copy(errorMessage = msg) }
                onComplete(false, msg)
            }
        }
    }

    fun recordPaymentFailure(goatId: String, orderId: String?, error: String) {
        viewModelScope.launch {
            repository.recordPaymentFailure(goatId, orderId, error)
        }
    }

    fun recordPaymentCancellation(goatId: String, orderId: String?) {
        viewModelScope.launch {
            repository.recordPaymentCancellation(goatId, orderId)
        }
    }

    fun updateGoatListing(goat: Goat, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val opKey = "update_goat_${goat.id}"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                _uiState.update { it.copy(isLoading = true) }
                val result = repository.updateGoatListing(goat)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess { updatedGoat ->
                    _uiState.update { state ->
                        state.copy(
                            allAdminGoats = state.allAdminGoats.map { if (it.id == updatedGoat.id) updatedGoat else it },
                            goats = state.goats.map { if (it.id == updatedGoat.id) updatedGoat else it },
                            successMessage = "Goat listing updated successfully!"
                        )
                    }
                    loadAllPlatformData()
                    _refreshTrigger.tryEmit(Unit)
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forGoatListing(err)) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun deleteGoatListing(goatId: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val opKey = "delete_goat_$goatId"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                _uiState.update { it.copy(isLoading = true) }
                val result = repository.deleteGoatListing(goatId)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess {
                    _uiState.update { state ->
                        state.copy(
                            allAdminGoats = state.allAdminGoats.filterNot { it.id == goatId },
                            goats = state.goats.filterNot { it.id == goatId },
                            successMessage = "Goat listing removed from your farm."
                        )
                    }
                    loadAllPlatformData()
                    _refreshTrigger.tryEmit(Unit)
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to remove goat listing. Please try again.")) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun updateFarmLogo(farmId: String, logoUrl: String, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val opKey = "update_logo_$farmId"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                _uiState.update { it.copy(isLoading = true) }
                val result = repository.updateFarmLogo(farmId, logoUrl)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess { resolvedUrl ->
                    _uiState.update { state ->
                        val updatedFarms = state.farms.map { farm ->
                            if (farm.id == farmId || farm.id == ensureValidUuid(farmId)) {
                                farm.copy(logoUrl = resolvedUrl)
                            } else farm
                        }
                        val updatedSelected = if (state.selectedFarm?.id == farmId || state.selectedFarm?.id == ensureValidUuid(farmId)) {
                            state.selectedFarm?.copy(logoUrl = resolvedUrl)
                        } else state.selectedFarm
                        state.copy(
                            farms = updatedFarms,
                            selectedFarm = updatedSelected,
                            successMessage = "Farm logo saved successfully!"
                        )
                    }
                    loadAllPlatformData()
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update farm logo. Please try again.")) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun updateFarmProfile(farm: Farm, onSuccess: () -> Unit = {}) {
        viewModelScope.launch {
            val opKey = "update_farm_${farm.id}"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                _uiState.update { it.copy(isLoading = true) }
                val result = repository.updateFarmProfile(farm)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess { updatedFarm ->
                    _uiState.update { state ->
                        state.copy(
                            farms = state.farms.map { if (it.id == updatedFarm.id) updatedFarm else it },
                            selectedFarm = if (state.selectedFarm?.id == updatedFarm.id) updatedFarm else state.selectedFarm,
                            successMessage = "Farm profile updated successfully!"
                        )
                    }
                    loadAllPlatformData()
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update farm profile. Please try again.")) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun approveGoatListing(goatId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.updateGoatApprovalStatus(goatId, ApprovalStatus.APPROVED)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allAdminGoats = state.allAdminGoats.map {
                            if (it.id == goatId) it.copy(approvalStatus = ApprovalStatus.APPROVED, availabilityStatus = AvailabilityStatus.AVAILABLE) else it
                        },
                        successMessage = "Goat listing approved and published live!"
                    )
                }
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to approve goat listing. Please try again.")) }
            }
        }
    }

    fun rejectGoatListing(goatId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.updateGoatApprovalStatus(goatId, ApprovalStatus.REJECTED)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allAdminGoats = state.allAdminGoats.map {
                            if (it.id == goatId) it.copy(approvalStatus = ApprovalStatus.REJECTED) else it
                        },
                        goats = state.goats.filterNot { it.id == goatId },
                        successMessage = "Goat listing rejected."
                    )
                }
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to reject goat listing. Please try again.")) }
            }
        }
    }

    fun suspendGoatListing(goatId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.suspendGoatListing(goatId)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allAdminGoats = state.allAdminGoats.map {
                            if (it.id == goatId) it.copy(approvalStatus = ApprovalStatus.SUSPENDED) else it
                        },
                        goats = state.goats.filterNot { it.id == goatId },
                        successMessage = "Goat listing suspended."
                    )
                }
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to suspend goat listing. Please try again.")) }
            }
        }
    }

    fun restoreGoatListing(goatId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.restoreGoatListing(goatId)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allAdminGoats = state.allAdminGoats.map {
                            if (it.id == goatId) it.copy(approvalStatus = ApprovalStatus.APPROVED, availabilityStatus = AvailabilityStatus.AVAILABLE) else it
                        },
                        successMessage = "Goat listing restored to approved status."
                    )
                }
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to restore goat listing. Please try again.")) }
            }
        }
    }

    fun updateGoat(goat: Goat) {
        updateGoatListing(goat)
    }

    fun updateFarmVerification(farmId: String, status: VerificationStatus) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.updateFarmVerification(farmId, status)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        farms = state.farms.map {
                            if (it.id == farmId) it.copy(verificationStatus = status) else it
                        },
                        successMessage = when (status) {
                            VerificationStatus.APPROVED -> "Farm verification APPROVED! Breeder badge active."
                            VerificationStatus.REJECTED -> "Farm registration rejected."
                            VerificationStatus.SUSPENDED -> "Farm suspended. Breeder listings restricted."
                            VerificationStatus.PENDING -> "Farm reset to pending review."
                        }
                    )
                }
                loadAllPlatformData()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forFarmModeration(err)) }
            }
        }
    }

    fun updateFarmListingLimit(farmId: String, limit: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.updateFarmListingLimit(farmId, limit)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess {
                _uiState.update { state ->
                    val updatedFarms = state.farms.map {
                        if (it.id == farmId) it.copy(goatListingLimit = limit) else it
                    }
                    val updatedSelectedFarm = if (state.selectedFarm?.id == farmId) {
                        state.selectedFarm.copy(goatListingLimit = limit)
                    } else state.selectedFarm
                    state.copy(
                        farms = updatedFarms,
                        selectedFarm = updatedSelectedFarm,
                        successMessage = "Goat listing limit updated to $limit for farm."
                    )
                }
                loadAllPlatformData()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update farm listing limit. Please try again.")) }
            }
        }
    }

    fun updateUserSuspension(userId: String, isSuspended: Boolean) {
        viewModelScope.launch {
            // Optimistic update
            _uiState.update { state ->
                state.copy(
                    allCustomers = state.allCustomers.map {
                        if (it.id == userId) it.copy(isSuspended = isSuspended) else it
                    },
                    successMessage = if (isSuspended) "Customer account suspended." else "Customer account reactivated."
                )
            }
            repository.updateUserSuspension(userId, isSuspended)
            loadAllPlatformData()
        }
    }

    fun updateReportStatus(reportId: String, status: ReportStatus, resolutionNotes: String? = null) {
        viewModelScope.launch {
            val result = repository.updateReportStatus(reportId, status, resolutionNotes)
            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allReports = state.allReports.map {
                            if (it.id == reportId) it.copy(status = status, resolutionNotes = resolutionNotes ?: it.resolutionNotes) else it
                        },
                        successMessage = "Report status updated to ${status.displayName}"
                    )
                }
                loadAllPlatformData()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update report status.")) }
            }
        }
    }

    fun submitReport(
        targetType: String,
        targetId: String,
        targetTitle: String,
        reason: ReportReason,
        description: String,
        evidencePhotoUrl: String? = null,
        evidencePhotos: List<String> = emptyList(),
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            val effectivePhotos = if (evidencePhotos.isNotEmpty()) evidencePhotos else (evidencePhotoUrl?.let { listOf(it) } ?: emptyList())
            val report = PlatformReport(
                id = "rep-${UUID.randomUUID().toString().take(6)}",
                reporterId = user?.id ?: "usr-guest",
                reporterName = user?.name ?: "Customer",
                reporterEmail = user?.email ?: "customer@ammalfarm.com",
                targetType = targetType,
                targetId = targetId,
                targetTitle = targetTitle,
                reason = reason,
                description = description,
                evidencePhotoUrl = evidencePhotoUrl ?: effectivePhotos.firstOrNull(),
                evidencePhotos = effectivePhotos,
                status = ReportStatus.NEW,
                createdAt = System.currentTimeMillis()
            )
            val result = repository.submitReport(report)
            result.onSuccess { newReport ->
                _uiState.update { state ->
                    state.copy(
                        allReports = listOf(newReport) + state.allReports,
                        successMessage = "Report submitted to marketplace safety team for review."
                    )
                }
                loadAllPlatformData()
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to submit report. Please try again.")) }
            }
        }
    }

    fun resolveReportWithAction(
        reportId: String,
        removeListingId: String?,
        suspendFarmId: String?,
        resolutionNotes: String
    ) {
        viewModelScope.launch {
            val result = repository.resolveReportWithAction(reportId, removeListingId, suspendFarmId, resolutionNotes)
            result.onSuccess {
                _uiState.update { state ->
                    state.copy(
                        allReports = state.allReports.map {
                            if (it.id == reportId) it.copy(status = ReportStatus.RESOLVED, resolutionNotes = resolutionNotes) else it
                        },
                        allAdminGoats = if (!removeListingId.isNullOrBlank()) state.allAdminGoats.filterNot { it.id == removeListingId } else state.allAdminGoats,
                        goats = if (!removeListingId.isNullOrBlank()) state.goats.filterNot { it.id == removeListingId } else state.goats,
                        farms = if (!suspendFarmId.isNullOrBlank()) state.farms.map {
                            if (it.id == suspendFarmId) it.copy(verificationStatus = VerificationStatus.SUSPENDED) else it
                        } else state.farms,
                        successMessage = "Report resolved with administrative actions applied."
                    )
                }
                loadAllPlatformData()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to resolve report.")) }
            }
        }
    }

    // --- NOTIFICATION ACTIONS ---
    fun markNotificationAsRead(notificationId: String) {
        viewModelScope.launch {
            val result = repository.markNotificationAsRead(notificationId)
            result.onSuccess {
                _uiState.update { s ->
                    s.copy(
                        notifications = s.notifications.map {
                            if (it.id == notificationId) it.copy(isRead = true) else it
                        }
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to mark notification as read.")) }
            }
        }
    }

    fun markAllNotificationsAsRead() {
        val user = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            val result = repository.markAllNotificationsAsRead(user.id, user.role)
            result.onSuccess {
                _uiState.update { s ->
                    s.copy(
                        notifications = s.notifications.map { it.copy(isRead = true) },
                        successMessage = "All notifications marked as read."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to mark all notifications as read.")) }
            }
        }
    }

    fun deleteNotification(notificationId: String) {
        viewModelScope.launch {
            val result = repository.deleteNotification(notificationId)
            result.onSuccess {
                _uiState.update { s ->
                    s.copy(
                        notifications = s.notifications.filterNot { it.id == notificationId },
                        successMessage = "Notification removed."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to delete notification.")) }
            }
        }
    }

    fun clearAllNotifications() {
        val user = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            val result = repository.clearAllNotifications(user.id, user.role)
            result.onSuccess {
                _uiState.update { s ->
                    s.copy(
                        notifications = emptyList(),
                        successMessage = "Notifications cleared."
                    )
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to clear notifications.")) }
            }
        }
    }

    fun triggerSampleNotification(type: NotificationType) {
        viewModelScope.launch {
            repository.triggerSampleNotification(type)
            _uiState.update { it.copy(successMessage = "Notification created: ${type.name}") }
        }
    }

    // Concurrency protection: sequential lock per goatId to handle rapid repeated taps safely
    private val goatToggleMutexes = ConcurrentHashMap<String, Mutex>()

    // ==========================================
    // Wishlist Actions
    // ==========================================
    fun isWishlisted(goatId: String): Boolean {
        return _uiState.value.wishlistGoatIds.contains(goatId)
    }

    fun toggleWishlist(goat: Goat, onGuestLoginRequired: () -> Unit = {}) {
        val user = _uiState.value.currentUser
        if (user == null) {
            onGuestLoginRequired()
            return
        }

        val goatId = goat.id
        val previousWishlistGoatIds = _uiState.value.wishlistGoatIds
        val previousWishlistItems = _uiState.value.wishlistItems
        val currentlyWishlisted = previousWishlistGoatIds.contains(goatId)

        // Optimistic UI state update
        val updatedIds = if (currentlyWishlisted) {
            previousWishlistGoatIds - goatId
        } else {
            previousWishlistGoatIds + goatId
        }

        val updatedItems = if (currentlyWishlisted) {
            previousWishlistItems.filter { it.goatId != goatId }
        } else {
            previousWishlistItems + WishlistItem(
                id = java.util.UUID.randomUUID().toString(),
                userId = user.id,
                goatId = goatId,
                goat = goat
            )
        }

        _uiState.update {
            it.copy(
                wishlistGoatIds = updatedIds,
                wishlistItems = updatedItems,
                successMessage = if (currentlyWishlisted) "Removed ${goat.name} from Wishlist" else "Saved ${goat.name} to Wishlist ❤️"
            )
        }

        viewModelScope.launch {
            val mutex = goatToggleMutexes.computeIfAbsent(goatId) { Mutex() }
            mutex.withLock {
                try {
                    val result = repository.toggleWishlist(user.id, goatId)
                    if (result.isFailure) {
                        Log.e("MarketplaceVM", "Failed to sync wishlist with backend for goat $goatId: ${result.exceptionOrNull()?.message}")
                        // Roll back UI state on failure
                        _uiState.update { s ->
                            s.copy(
                                wishlistGoatIds = previousWishlistGoatIds,
                                wishlistItems = previousWishlistItems,
                                errorMessage = "Failed to update wishlist. Rolled back."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MarketplaceVM", "Exception syncing wishlist with backend for goat $goatId: ${e.message}")
                    // Roll back UI state on exception
                    _uiState.update { s ->
                        s.copy(
                            wishlistGoatIds = previousWishlistGoatIds,
                            wishlistItems = previousWishlistItems,
                            errorMessage = "Failed to update wishlist. Rolled back."
                        )
                    }
                }
            }
        }
    }

    fun removeFromWishlist(goatId: String) {
        val user = _uiState.value.currentUser ?: return
        val previousWishlistGoatIds = _uiState.value.wishlistGoatIds
        val previousWishlistItems = _uiState.value.wishlistItems
        val goatName = previousWishlistItems.find { it.goatId == goatId }?.goat?.name ?: "Listing"

        _uiState.update {
            it.copy(
                wishlistGoatIds = it.wishlistGoatIds - goatId,
                wishlistItems = it.wishlistItems.filter { item -> item.goatId != goatId },
                successMessage = "Removed $goatName from Wishlist"
            )
        }

        viewModelScope.launch {
            val mutex = goatToggleMutexes.computeIfAbsent(goatId) { Mutex() }
            mutex.withLock {
                try {
                    val result = repository.removeFromWishlist(user.id, goatId)
                    if (result.isFailure) {
                        Log.e("MarketplaceVM", "Failed to remove from backend wishlist: ${result.exceptionOrNull()?.message}")
                        // Roll back UI state on failure
                        _uiState.update { s ->
                            s.copy(
                                wishlistGoatIds = previousWishlistGoatIds,
                                wishlistItems = previousWishlistItems,
                                errorMessage = "Failed to remove item from wishlist."
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MarketplaceVM", "Exception removing from backend wishlist: ${e.message}")
                    // Roll back UI state on exception
                    _uiState.update { s ->
                        s.copy(
                            wishlistGoatIds = previousWishlistGoatIds,
                            wishlistItems = previousWishlistItems,
                            errorMessage = "Failed to remove item from wishlist."
                        )
                    }
                }
            }
        }
    }

    fun refreshWishlist() {
        val user = _uiState.value.currentUser ?: return
        viewModelScope.launch {
            repository.getWishlistForUser(user.id).collect { items ->
                _uiState.update {
                    it.copy(
                        wishlistItems = items,
                        wishlistGoatIds = items.map { item -> item.goatId }.toSet()
                    )
                }
            }
        }
    }

    fun retryNetworkCall() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRetrying = true, isLoading = true, networkError = null) }
            try {
                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
                delay(600)
                _uiState.update { it.copy(isRetrying = false, isLoading = false) }
            } catch (e: Exception) {
                Log.e("MarketplaceVM", "Network retry failed: ${e.message}", e)
                handleNetworkOrSyncError(e, "retryNetworkCall")
                _uiState.update { it.copy(isRetrying = false, isLoading = false) }
            }
        }
    }

    fun clearNetworkError() {
        _uiState.update { it.copy(networkError = null) }
    }

    fun triggerNetworkError(message: String) {
        _uiState.update { it.copy(networkError = message) }
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
