package com.ammalfarm.adusanthai.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ammalfarm.adusanthai.core.supabase.SupabaseConfig
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.data.dto.ensureValidUuid
import com.ammalfarm.adusanthai.data.repository.MarketplaceRepository
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.util.NotificationDeepLinkPayload
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
    val platformPricing: PlatformPricing = PlatformPricing(),
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
    val successMessage: String? = null,
    val selectedBookingIdForDetail: String? = null
) {
    // Backward compatibility helpers
    val approvedFarms: List<Farm> get() = farms.filter { it.verificationStatus == VerificationStatus.APPROVED }
    val searchQuery: String get() = filterCriteria.searchQuery
    val selectedBreed: String? get() = filterCriteria.breed
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

    private var activeSubscribedUserId: String? = null
    private var notificationsJob: Job? = null

    private fun startNotificationObserver(user: UserProfile) {
        val currentJob = notificationsJob
        if (activeSubscribedUserId == user.id && currentJob != null && currentJob.isActive) {
            return
        }
        activeSubscribedUserId = user.id
        currentJob?.cancel()
        notificationsJob = viewModelScope.launch {
            repository.getNotificationsForUser(user.id)
                .catch { e -> handleNetworkOrSyncError(e, "getNotificationsForUser") }
                .collect { list ->
                    _uiState.update { it.copy(notifications = list) }
                }
        }
    }

    private fun stopNotificationObserver() {
        activeSubscribedUserId = null
        notificationsJob?.cancel()
        notificationsJob = null
    }

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
                .mapNotNull { user -> user?.id?.takeIf { it.isNotBlank() } }
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

        viewModelScope.launch {
            repository.getPlatformPricing()
                .catch { e -> Log.w("MarketplaceVM", "Error observing pricing: ${e.message}") }
                .collect { pricing ->
                    _uiState.update { s -> s.copy(platformPricing = pricing) }
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
        if (e is kotlinx.coroutines.CancellationException) return
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
                    val current = _uiState.value.currentUser
                    if (current != null && (current.role == UserRole.FARM_ADMIN || current.role == UserRole.SUPER_ADMIN)) {
                        if (_uiState.value.farmBookings.isEmpty() || _uiState.value.allAdminGoats.isEmpty()) {
                            loadRoleScopedData(current)
                        }
                    }
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
            stopNotificationObserver()
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

        startNotificationObserver(user)

        val isSuperAdmin = user.role == UserRole.SUPER_ADMIN
        val isFarmAdmin = user.role == UserRole.FARM_ADMIN

        when {
            isSuperAdmin -> {
                loadSuperAdminData(user)
                loadCustomerData(user)
            }
            isFarmAdmin -> {
                loadFarmAdminData(user)
                loadCustomerData(user)
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
                    _uiState.update { state ->
                        val existingGoatIds = state.allBookings.mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
                        val missingReserved = list.filter { g ->
                            (g.availabilityStatus == AvailabilityStatus.RESERVED || g.availabilityStatus == AvailabilityStatus.BOOKING_PENDING) &&
                            !existingGoatIds.contains(g.id)
                        }.map { g ->
                            val farm = state.farms.find { it.id == g.farmId }
                            Booking(
                                id = "res-" + g.id,
                                goatId = g.id,
                                goatCode = g.goatCode,
                                farmId = g.farmId,
                                customerId = "",
                                customerName = "Customer Reservation / Hold",
                                customerPhone = "Contact Farm Admin",
                                goatName = g.name,
                                goatBreed = g.breed,
                                goatPhoto = g.photos.firstOrNull() ?: "",
                                farmName = g.farmName.ifBlank { farm?.name ?: "Ammal Farm" },
                                amount = g.finalPrice,
                                status = g.availabilityStatus,
                                bookingDate = g.createdAt,
                                reservationExpiryDate = g.createdAt + (24 * 3600 * 1000L),
                                notes = "Active reservation hold on ${g.name}"
                            )
                        }
                        val combinedBookings = (state.allBookings + missingReserved).distinctBy { it.id }
                        state.copy(allAdminGoats = list, allBookings = combinedBookings)
                    }
                }
        }

        viewModelScope.launch {
            repository.getAllBookings()
                .catch { e -> handleNetworkOrSyncError(e, "getAllBookings") }
                .collect { list ->
                    _uiState.update { state ->
                        val existingGoatIds = list.mapNotNull { it.goatId.takeIf { id -> id.isNotBlank() } }.toSet()
                        val allGoats = (state.allAdminGoats + state.goats).distinctBy { it.id }
                        val missingReserved = allGoats.filter { g ->
                            (g.availabilityStatus == AvailabilityStatus.RESERVED || g.availabilityStatus == AvailabilityStatus.BOOKING_PENDING) &&
                            !existingGoatIds.contains(g.id)
                        }.map { g ->
                            val farm = state.farms.find { it.id == g.farmId }
                            Booking(
                                id = "res-" + g.id,
                                goatId = g.id,
                                goatCode = g.goatCode,
                                farmId = g.farmId,
                                customerId = "",
                                customerName = "Customer Reservation / Hold",
                                customerPhone = "Contact Farm Admin",
                                goatName = g.name,
                                goatBreed = g.breed,
                                goatPhoto = g.photos.firstOrNull() ?: "",
                                farmName = g.farmName.ifBlank { farm?.name ?: "Ammal Farm" },
                                amount = g.finalPrice,
                                status = g.availabilityStatus,
                                bookingDate = g.createdAt,
                                reservationExpiryDate = g.createdAt + (24 * 3600 * 1000L),
                                notes = "Active reservation hold on ${g.name}"
                            )
                        }
                        val combinedBookings = (list + missingReserved).distinctBy { it.id }
                        state.copy(allBookings = combinedBookings)
                    }
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
    }

    private fun loadFarmAdminData(user: UserProfile) {
        val resolvedFarmId = user.farmId?.takeIf { it.isNotBlank() }
            ?: _uiState.value.farms.find { it.ownerId == user.id }?.id
            ?: com.ammalfarm.adusanthai.core.util.FarmLocalCache.getAllCachedFarms().find { it.ownerId == user.id }?.id
            ?: (if (user.role == UserRole.SUPER_ADMIN) SEED_AMMAL_FARM_UUID else "")

        viewModelScope.launch {
            if (resolvedFarmId.isNotBlank()) {
                repository.getGoatsByFarm(resolvedFarmId)
                    .catch { e -> handleNetworkOrSyncError(e, "getGoatsByFarm") }
                    .collect { list ->
                        _uiState.update { it.copy(allAdminGoats = list) }
                    }
            } else {
                repository.getAllGoatsForAdmin()
                    .catch { e -> handleNetworkOrSyncError(e, "getAllGoatsForAdmin") }
                    .collect { list ->
                        _uiState.update { it.copy(allAdminGoats = list) }
                    }
            }
        }

        viewModelScope.launch {
            repository.getFarmBookings(resolvedFarmId)
                .catch { e -> handleNetworkOrSyncError(e, "getFarmBookings") }
                .collect { list ->
                    _uiState.update { current ->
                        current.copy(
                            farmBookings = list,
                            allBookings = (list + current.allBookings).distinctBy { it.id }
                        )
                    }
                }
        }

        viewModelScope.launch {
            repository.getAllBookings()
                .catch { e -> handleNetworkOrSyncError(e, "getAllBookings") }
                .collect { list ->
                    _uiState.update { current ->
                        current.copy(
                            allBookings = (current.allBookings + list).distinctBy { it.id }
                        )
                    }
                }
        }
    }

    private fun loadCustomerData(user: UserProfile) {
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
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
            // 1. Refresh public marketplace data (approved farms, available breeds)
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

    fun findGoat(goatId: String): Goat? {
        if (goatId.isBlank()) return null
        val cleanId = goatId.trim()
        return _uiState.value.goats.find { 
            it.id.equals(cleanId, ignoreCase = true) ||
            it.goatCode.equals(cleanId, ignoreCase = true) ||
            it.name.equals(cleanId, ignoreCase = true)
        } ?: _uiState.value.allAdminGoats.find {
            it.id.equals(cleanId, ignoreCase = true) ||
            it.goatCode.equals(cleanId, ignoreCase = true) ||
            it.name.equals(cleanId, ignoreCase = true)
        }
    }

    fun selectGoatById(goatId: String): Goat? {
        if (goatId.isBlank()) return null
        val goat = findGoat(goatId)
        if (goat != null) {
            _uiState.update { it.copy(selectedGoat = goat) }
        }
        return goat
    }

    suspend fun selectOrFetchGoatById(goatId: String): Goat? {
        if (goatId.isBlank()) return null
        val inMemory = selectGoatById(goatId)
        if (inMemory != null) return inMemory

        val cleanId = goatId.trim()
        return try {
            val fetched = repository.getGoatById(cleanId).firstOrNull()
            if (fetched != null) {
                _uiState.update { it.copy(selectedGoat = fetched) }
            }
            fetched
        } catch (_: Exception) {
            null
        }
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
        val cleanId = farmId.trim()
        val farm = _uiState.value.farms.find { 
            it.id.equals(cleanId, ignoreCase = true) ||
            it.name.equals(cleanId, ignoreCase = true)
        }
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

    suspend fun selectOrFetchFarmById(farmId: String): Farm? {
        if (farmId.isBlank()) return null
        val inMemory = selectFarmById(farmId)
        if (inMemory != null) return inMemory

        val cleanId = farmId.trim()
        return try {
            val fetched = repository.getFarmById(cleanId).firstOrNull()
            if (fetched != null) {
                selectFarm(fetched)
            }
            fetched
        } catch (_: Exception) {
            null
        }
    }

    fun loadFarmPublicGoats(farmId: String) {
        if (farmId.isBlank()) return
        val validFarmId = try { ensureValidUuid(farmId.trim()) } catch (_: Exception) { farmId.trim() }
        viewModelScope.launch {
            _uiState.update { it.copy(isSelectedFarmLoading = true, selectedFarmError = null) }
            repository.getGoatsByFarm(validFarmId)
                .catch { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
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
        val currentUser = _uiState.value.currentUser
        val targetGoat = _uiState.value.goats.find { it.id == goatId } ?: _uiState.value.selectedGoat?.takeIf { it.id == goatId }
        val targetFarm = _uiState.value.farms.find { it.id == targetGoat?.farmId }
        if (currentUser?.role == UserRole.FARM_ADMIN) {
            val userFarm = _uiState.value.farms.find {
                (!currentUser.id.isNullOrBlank() && it.ownerId == currentUser.id) ||
                (!currentUser.farmId.isNullOrBlank() && it.id == currentUser.farmId)
            }
            val userFarmId = currentUser.farmId?.takeIf { it.isNotBlank() } ?: userFarm?.id?.takeIf { it.isNotBlank() }
            val isOwnFarm = (targetGoat != null && !userFarmId.isNullOrBlank() && targetGoat.farmId.isNotBlank() && targetGoat.farmId == userFarmId) ||
                    (targetFarm != null && !targetFarm.ownerId.isNullOrBlank() && !currentUser.id.isNullOrBlank() && targetFarm.ownerId == currentUser.id)
            if (isOwnFarm) {
                _uiState.update { it.copy(errorMessage = "You cannot book goats listed by your own farm.") }
                return
            }
        }

        viewModelScope.launch {
            val opKey = "create_booking_$goatId"
            if (!inFlightOperations.add(opKey)) return@launch
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val result = repository.createBooking(goatId, notes)
                _uiState.update { it.copy(isLoading = false) }
                result.onSuccess { newBooking ->
                    val enrichedBooking = if (newBooking.goatPhoto.isBlank()) {
                        val fallbackPhoto = _uiState.value.goats.find { it.id == goatId }?.photos?.firstOrNull()
                            ?: com.ammalfarm.adusanthai.core.util.GoatImageResolver.getCachedPhoto(goatId)
                            ?: ""
                        if (fallbackPhoto.isNotBlank()) newBooking.copy(goatPhoto = fallbackPhoto) else newBooking
                    } else newBooking
                    _uiState.update { state ->
                        val isForFarm = !state.currentUser?.farmId.isNullOrBlank() && state.currentUser.farmId == enrichedBooking.farmId
                        val updatedFarmBookings = if (isForFarm) {
                            listOf(enrichedBooking) + state.farmBookings.filter { it.id != enrichedBooking.id }
                        } else state.farmBookings

                        state.copy(
                            customerBookings = listOf(enrichedBooking) + state.customerBookings.filter { it.id != enrichedBooking.id },
                            allBookings = listOf(enrichedBooking) + state.allBookings.filter { it.id != enrichedBooking.id },
                            farmBookings = updatedFarmBookings,
                            goats = state.goats.map { if (it.id == goatId) it.copy(availabilityStatus = AvailabilityStatus.RESERVED) else it },
                            allAdminGoats = state.allAdminGoats.map { if (it.id == goatId) it.copy(availabilityStatus = AvailabilityStatus.RESERVED) else it },
                            selectedGoat = if (state.selectedGoat?.id == goatId) state.selectedGoat.copy(availabilityStatus = AvailabilityStatus.RESERVED) else state.selectedGoat,
                            successMessage = "Goat reserved successfully!"
                        )
                    }
                    repository.invalidateCache()
                    _refreshTrigger.tryEmit(Unit)
                    loadAllPlatformData()
                    onSuccess()
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.forBooking(err)) }
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun confirmBooking(bookingId: String, onComplete: ((Boolean) -> Unit)? = null) {
        updateBookingStatus(bookingId, AvailabilityStatus.CONFIRMED, null, onComplete)
    }

    fun cancelBooking(bookingId: String, reason: String = "Cancelled by user", onComplete: ((Boolean) -> Unit)? = null) {
        updateBookingStatus(bookingId, AvailabilityStatus.CANCELLED, reason, onComplete)
    }

    fun completeBooking(bookingId: String, onComplete: ((Boolean) -> Unit)? = null) {
        updateBookingStatus(bookingId, AvailabilityStatus.COMPLETED, null, onComplete)
    }

    fun updateBookingStatus(
        bookingId: String,
        status: AvailabilityStatus,
        reason: String? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val opKey = "update_booking_${bookingId}_${status.name}"
            if (!inFlightOperations.add(opKey)) return@launch
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val result = repository.updateBookingStatus(bookingId, status, reason)
                _uiState.update { it.copy(isLoading = false) }
                result.onSuccess {
                    val actionMessage = when (status) {
                        AvailabilityStatus.CONFIRMED -> "Booking confirmed successfully."
                        AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED -> "Booking cancelled successfully."
                        AvailabilityStatus.COMPLETED -> "Booking marked as completed."
                        else -> "Booking status updated to ${status.name}."
                    }
                    _uiState.update { state ->
                        val updatedGoatStatus = when (status) {
                            AvailabilityStatus.CONFIRMED -> AvailabilityStatus.CONFIRMED
                            AvailabilityStatus.COMPLETED -> AvailabilityStatus.COMPLETED
                            AvailabilityStatus.CANCELLED, AvailabilityStatus.REJECTED -> AvailabilityStatus.AVAILABLE
                            AvailabilityStatus.RESERVED -> AvailabilityStatus.RESERVED
                            else -> AvailabilityStatus.AVAILABLE
                        }
                        val targetBooking = state.allBookings.find { it.id == bookingId }
                            ?: state.customerBookings.find { it.id == bookingId }
                            ?: state.farmBookings.find { it.id == bookingId }
                        val targetGoatId = targetBooking?.goatId ?: if (bookingId.startsWith("res-")) bookingId.removePrefix("res-") else null

                        state.copy(
                            allBookings = state.allBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                            customerBookings = state.customerBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                            farmBookings = state.farmBookings.map { if (it.id == bookingId) it.copy(status = status) else it },
                            goats = state.goats.map { if (targetGoatId != null && it.id == targetGoatId) it.copy(availabilityStatus = updatedGoatStatus) else it },
                            allAdminGoats = state.allAdminGoats.map { if (targetGoatId != null && it.id == targetGoatId) it.copy(availabilityStatus = updatedGoatStatus) else it },
                            selectedGoat = if (targetGoatId != null && state.selectedGoat?.id == targetGoatId) state.selectedGoat.copy(availabilityStatus = updatedGoatStatus) else state.selectedGoat,
                            successMessage = actionMessage
                        )
                    }
                    repository.invalidateCache()
                    _refreshTrigger.tryEmit(Unit)
                    loadAllPlatformData()
                    onComplete?.invoke(true)
                }.onFailure { err ->
                    _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update booking status")) }
                    repository.invalidateCache()
                    _refreshTrigger.tryEmit(Unit)
                    loadAllPlatformData()
                    onComplete?.invoke(false)
                }
            } finally {
                inFlightOperations.remove(opKey)
            }
        }
    }

    fun selectBookingForDetail(bookingId: String?) {
        _uiState.update { it.copy(selectedBookingIdForDetail = bookingId) }
    }

    fun clearSelectedBookingDetail() {
        _uiState.update { it.copy(selectedBookingIdForDetail = null) }
    }

    fun addGoatListing(goat: Goat, onSuccess: () -> Unit) {
        viewModelScope.launch {
            val opKey = "add_goat_${goat.id}_${goat.name}"
            if (!inFlightOperations.add(opKey)) return@launch

            try {
                val state = _uiState.value
                val farm = state.farms.find { it.id == goat.farmId }
                    ?: (if (state.currentUser?.farmId == goat.farmId) state.selectedFarm else null)

                val isAmmal = farm?.isAmmalOwnFarm == true || farm?.id == SEED_AMMAL_FARM_UUID
                val isSuperAdmin = state.currentUser?.role == UserRole.SUPER_ADMIN
                val limit = farm?.goatListingLimit ?: (if (isAmmal) 1000 else 2)

                // 1. Partner farm must be approved before adding goats
                if (!isAmmal && !isSuperAdmin && farm?.verificationStatus != VerificationStatus.APPROVED) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Your partner farm must be approved by Super Admin before you can add goats.\nPlease complete the initial approval fee payment."
                        )
                    }
                    return@launch
                }

                // 2. Count consumed slots (sold & deleted goats permanently consume their slot)
                val farmGoatsCount = state.allAdminGoats.count {
                    it.farmId == goat.farmId || (farm != null && it.farmId == farm.id)
                }
                val consumedSlots = com.ammalfarm.adusanthai.core.util.FarmLocalCache.getConsumedListingSlots(goat.farmId, farmGoatsCount)

                if (!isAmmal && !isSuperAdmin && consumedSlots >= limit) {
                    val slotPrice = state.platformPricing.additionalSlotPrice.toInt()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Your goat listing quota has been fully consumed ($consumedSlots / $limit slots).\nContact Super Admin to purchase additional listing slots (₹$slotPrice/slot)."
                        )
                    }
                    return@launch
                }

                // Goat listings are auto-approved live when quota is available (Rule 13)
                val targetGoat = goat.copy(
                    approvalStatus = ApprovalStatus.APPROVED,
                    availabilityStatus = AvailabilityStatus.AVAILABLE,
                    listingFeePaid = true,
                    listingFeeAmount = 0.0
                )

                _uiState.update { it.copy(isLoading = true) }
                val result = repository.addGoatListing(targetGoat)
                _uiState.update { it.copy(isLoading = false) }

                result.onSuccess { addedGoat ->
                    // Permanently record slot consumption
                    com.ammalfarm.adusanthai.core.util.FarmLocalCache.recordListingSlotConsumed(goat.farmId)

                    _uiState.update { s ->
                        val updatedAdminGoats = listOf(addedGoat) + s.allAdminGoats.filterNot { it.id == addedGoat.id }
                        val updatedMarketplace = if (addedGoat.approvalStatus == ApprovalStatus.APPROVED) {
                            listOf(addedGoat) + s.goats.filterNot { it.id == addedGoat.id }
                        } else s.goats
                        val msg = if (isAmmal) {
                            "Ammal Farm goat auto-approved & published live!"
                        } else {
                            "Goat listing auto-approved & published live to marketplace!"
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

    fun approveFarmWithPayment(
        farmId: String,
        amount: Double,
        paymentRef: String,
        notes: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.recordFarmApprovalPayment(
                farmId = farmId,
                amount = amount,
                paymentRef = paymentRef,
                receiptNumber = null,
                notes = notes
            )
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess { payment ->
                _uiState.update { state ->
                    val updatedFarms = state.farms.map {
                        if (it.id == farmId) it.copy(
                            verificationStatus = VerificationStatus.APPROVED,
                            goatListingLimit = maxOf(it.goatListingLimit, 2)
                        ) else it
                    }
                    val updatedPayments = (listOf(payment) + state.listingPayments).distinctBy { it.id }
                    state.copy(
                        farms = updatedFarms,
                        listingPayments = updatedPayments,
                        successMessage = "Farm approved! Initial 2 free slots activated. Receipt #${payment.receiptNumber} recorded."
                    )
                }

                // Dispatch notification for farm approval and payment
                val targetFarm = _uiState.value.farms.find { it.id == farmId }
                val farmOwnerId = targetFarm?.ownerId ?: ""
                val notification = AppNotification(
                    id = UUID.randomUUID().toString(),
                    recipientUserId = farmOwnerId,
                    targetRole = UserRole.FARM_ADMIN,
                    title = "Partner Farm Approved!",
                    message = "Your farm '${targetFarm?.name ?: "Farm"}' has been approved with 2 free listing slots. Receipt #${payment.receiptNumber} is available.",
                    type = NotificationType.FARM_APPROVED,
                    referenceId = farmId,
                    deepLinkRoute = "farm_admin"
                )
                repository.sendNotification(notification)
                com.ammalfarm.adusanthai.core.supabase.SupabaseModule.getApplicationContext()?.let { ctx ->
                    com.ammalfarm.adusanthai.core.notification.NotificationHelper.showSystemNotification(ctx, notification)
                }

                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to record approval payment. Please try again.")) }
            }
        }
    }

    fun increaseFarmListingQuota(
        farmId: String,
        slotsToAdd: Int,
        amount: Double,
        paymentRef: String,
        notes: String,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.recordQuotaIncreasePayment(
                farmId = farmId,
                slotsToAdd = slotsToAdd,
                amount = amount,
                paymentRef = paymentRef,
                receiptNumber = null,
                notes = notes
            )
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess { payment ->
                val targetFarm = _uiState.value.farms.find { it.id == farmId }
                val newLimit = (targetFarm?.goatListingLimit ?: 0) + slotsToAdd
                _uiState.update { state ->
                    val updatedFarms = state.farms.map {
                        if (it.id == farmId) it.copy(goatListingLimit = newLimit) else it
                    }
                    val updatedPayments = (listOf(payment) + state.listingPayments).distinctBy { it.id }
                    state.copy(
                        farms = updatedFarms,
                        listingPayments = updatedPayments,
                        successMessage = "Listing quota increased to $newLimit slots (+$slotsToAdd). Receipt #${payment.receiptNumber} recorded."
                    )
                }

                // Dispatch notification for listing quota increase
                val farmOwnerId = targetFarm?.ownerId ?: ""
                val notification = AppNotification(
                    id = UUID.randomUUID().toString(),
                    recipientUserId = farmOwnerId,
                    targetRole = UserRole.FARM_ADMIN,
                    title = "Listing Quota Increased!",
                    message = "Your farm listing limit is now $newLimit goats (+$slotsToAdd slots added). Receipt #${payment.receiptNumber} is available in Fee Receipts.",
                    type = NotificationType.LISTING_QUOTA_INCREASED,
                    referenceId = farmId,
                    deepLinkRoute = "farm_admin"
                )
                repository.sendNotification(notification)
                com.ammalfarm.adusanthai.core.supabase.SupabaseModule.getApplicationContext()?.let { ctx ->
                    com.ammalfarm.adusanthai.core.notification.NotificationHelper.showSystemNotification(ctx, notification)
                }

                loadAllPlatformData()
                _refreshTrigger.tryEmit(Unit)
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to increase quota. Please try again.")) }
            }
        }
    }

    fun updatePlatformPricing(
        approvalPrice: Double,
        slotPrice: Double,
        onSuccess: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val result = repository.updatePlatformPricing(approvalPrice, slotPrice)
            _uiState.update { it.copy(isLoading = false) }

            result.onSuccess { pricing ->
                _uiState.update {
                    it.copy(
                        platformPricing = pricing,
                        successMessage = "Platform pricing updated: Farm Approval = ₹${pricing.farmApprovalPrice.toInt()}, Additional Slot = ₹${pricing.additionalSlotPrice.toInt()}."
                    )
                }
                onSuccess()
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = UserFriendlyErrorMapper.toUserMessage(err, "Failed to update pricing.")) }
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

    // --- DEEP LINK NAVIGATION ---
    private val _handledDeepLinkKeys = mutableSetOf<String>()
    private val _pendingDeepLink = MutableStateFlow<NotificationDeepLinkPayload?>(null)
    val pendingDeepLink: StateFlow<NotificationDeepLinkPayload?> = _pendingDeepLink.asStateFlow()

    fun queueNotificationDeepLink(payload: NotificationDeepLinkPayload) {
        if (payload.intentKey.isBlank() || _handledDeepLinkKeys.contains(payload.intentKey)) {
            return
        }
        val currentUserId = _uiState.value.currentUser?.id ?: com.ammalfarm.adusanthai.core.notification.NotificationConfig.activeUserId
        if (!payload.recipientUserId.isNullOrBlank() && currentUserId != null && payload.recipientUserId != currentUserId) {
            // Suppress deep link intended for another user account
            return
        }
        _handledDeepLinkKeys.add(payload.intentKey)

        payload.notificationId?.takeIf { it.isNotBlank() }?.let { notifId ->
            markNotificationAsRead(notifId)
        }

        _pendingDeepLink.value = payload
    }

    fun clearPendingDeepLink() {
        _pendingDeepLink.value = null
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
