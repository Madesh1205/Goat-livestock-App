package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.ui.components.ActiveFilterChips
import com.ammalfarm.adusanthai.ui.components.AduSanthaiLogo
import com.ammalfarm.adusanthai.ui.components.GoatCard
import com.ammalfarm.adusanthai.ui.components.GoatFilterBottomSheet
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel
import com.ammalfarm.adusanthai.ui.viewmodel.SortOption

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceHomeScreen(
    marketplaceViewModel: MarketplaceViewModel,
    currentUser: UserProfile?,
    onNavigateToGoatDetail: (String) -> Unit,
    onNavigateToFarmDetail: (String) -> Unit,
    onNavigateToWishlist: () -> Unit,
    onNavigateToBookings: () -> Unit,
    onNavigateToOrders: () -> Unit,
    onNavigateToNotifications: () -> Unit,
    onNavigateToProfile: () -> Unit,
    onNavigateToAdminDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState by marketplaceViewModel.uiState.collectAsStateWithLifecycle()
    var showFilterSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentUser?.id) {
        if (currentUser != null) {
            marketplaceViewModel.loadUserSpecificData(currentUser)
        }
    }

    LaunchedEffect(uiState.actionSuccessMessage, uiState.actionErrorMessage) {
        uiState.actionSuccessMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
        uiState.actionErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
    }

    val unreadNotifications = remember(uiState.notifications) {
        uiState.notifications.count { !it.isRead }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header Row: Logo + App Title + Action Icons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AduSanthaiLogo(modifier = Modifier.size(44.dp))
                            Column {
                                Text(
                                    text = "Adu Santhai",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Verified Goat & Livestock Marketplace",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (currentUser?.role == UserRole.SUPER_ADMIN || currentUser?.role == UserRole.FARM_ADMIN) {
                                FilledTonalIconButton(
                                    onClick = onNavigateToAdminDashboard,
                                    modifier = Modifier.testTag("btn_admin_console")
                                ) {
                                    Icon(
                                        imageVector = if (currentUser.role == UserRole.SUPER_ADMIN) Icons.Default.AdminPanelSettings else Icons.Default.Agriculture,
                                        contentDescription = "Admin Dashboard"
                                    )
                                }
                            }

                            IconButton(
                                onClick = onNavigateToNotifications,
                                modifier = Modifier.testTag("btn_notifications")
                            ) {
                                BadgedBox(
                                    badge = {
                                        if (unreadNotifications > 0) {
                                            Badge { Text(unreadNotifications.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Notifications,
                                        contentDescription = "Notifications"
                                    )
                                }
                            }

                            IconButton(
                                onClick = { marketplaceViewModel.refreshMarketplace() },
                                modifier = Modifier.testTag("btn_refresh_marketplace")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh Listings"
                                )
                            }
                        }
                    }

                    // Search Bar
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { marketplaceViewModel.updateSearchQuery(it) },
                        placeholder = { Text("Search breed, farm, district, location...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (uiState.searchQuery.isNotBlank()) {
                                IconButton(onClick = { marketplaceViewModel.updateSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("search_marketplace_input")
                    )

                    // Requirement 2: Location Selector directly below the search bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = "Location",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Location",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            uiState.availableLocations.forEach { loc ->
                                val isSelected = (loc == "All" && uiState.selectedDistrict == "All") ||
                                    loc.equals(uiState.selectedDistrict, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { marketplaceViewModel.updateFilters(district = loc) },
                                    label = {
                                        Text(
                                            text = if (loc == "All") "All Locations" else loc,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                    modifier = Modifier.testTag("chip_location_${loc.replace(" ", "_")}")
                                )
                            }
                        }
                    }

                    // Requirement 4: Separate Sort and Filter controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Filter Control with Funnel Icon
                        OutlinedButton(
                            onClick = { showFilterSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (uiState.activeFilterCount > 0) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.surface
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("btn_open_filters")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (uiState.activeFilterCount > 0) {
                                        Badge { Text(uiState.activeFilterCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterList, // Funnel icon for Filter
                                    contentDescription = "Filter"
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Filter", fontWeight = FontWeight.Bold)
                        }

                        // Sort Control with Up/Down Arrows Icon
                        var showSortMenu by remember { mutableStateOf(false) }

                        Box(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { showSortMenu = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("btn_open_sort")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SwapVert, // Up/down arrows icon for Sort
                                    contentDescription = "Sort"
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Sort: ${uiState.sortOption.label.substringBefore(":")}",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                                modifier = Modifier.testTag("menu_sort_options")
                            ) {
                                Text(
                                    text = "Sort Goats By",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                HorizontalDivider()
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = option.label,
                                                    fontWeight = if (uiState.sortOption == option) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (uiState.sortOption == option) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                                if (uiState.sortOption == option) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            marketplaceViewModel.updateFilters(sortOption = option)
                                            showSortMenu = false
                                        },
                                        modifier = Modifier.testTag("sort_item_${option.name}")
                                    )
                                }
                            }
                        }
                    }

                    // Requirement 3: Dynamic Breed Category Chips
                    if (uiState.availableBreeds.size > 1) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            uiState.availableBreeds.forEach { breed ->
                                val isSelected = uiState.selectedBreed.equals(breed, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { marketplaceViewModel.selectBreed(breed) },
                                    label = {
                                        Text(
                                            text = breed,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null,
                                    modifier = Modifier.testTag("chip_breed_${breed.replace(" ", "_")}")
                                )
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Default.Storefront, contentDescription = "Marketplace") },
                    label = { Text("Market") },
                    modifier = Modifier.testTag("nav_marketplace")
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToWishlist,
                    icon = {
                        BadgedBox(
                            badge = {
                                if (uiState.wishlistedIds.isNotEmpty()) {
                                    Badge { Text(uiState.wishlistedIds.size.toString()) }
                                }
                            }
                        ) {
                            Icon(Icons.Outlined.FavoriteBorder, contentDescription = "Wishlist")
                        }
                    },
                    label = { Text("Wishlist") },
                    modifier = Modifier.testTag("nav_wishlist")
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToBookings,
                    icon = { Icon(Icons.Outlined.EventNote, contentDescription = "My Bookings") },
                    label = { Text("Bookings") },
                    modifier = Modifier.testTag("nav_bookings")
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToOrders,
                    icon = { Icon(Icons.Outlined.ReceiptLong, contentDescription = "My Orders") },
                    label = { Text("Orders") },
                    modifier = Modifier.testTag("nav_orders")
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToProfile,
                    icon = { Icon(Icons.Outlined.Person, contentDescription = "Profile") },
                    label = { Text("Profile") },
                    modifier = Modifier.testTag("nav_profile")
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ActiveFilterChips(
                selectedBreed = uiState.selectedBreed,
                selectedGender = uiState.selectedGender,
                selectedDistrict = uiState.selectedDistrict,
                selectedFarm = uiState.selectedFarm,
                selectedAvailability = uiState.availabilityFilter,
                minPriceInput = uiState.minPrice?.toInt()?.toString().orEmpty(),
                maxPriceInput = uiState.maxPrice?.toInt()?.toString().orEmpty(),
                minAgeInput = uiState.minAgeMonths?.toString().orEmpty(),
                maxAgeInput = uiState.maxAgeMonths?.toString().orEmpty(),
                minWeightInput = uiState.minWeightKg?.toString().orEmpty(),
                maxWeightInput = uiState.maxWeightKg?.toString().orEmpty(),
                onlyAvailable = uiState.onlyAvailable,
                sortOption = uiState.sortOption,
                onRemoveBreed = { marketplaceViewModel.updateFilters(breed = "All") },
                onRemoveGender = { marketplaceViewModel.updateFilters(gender = "All") },
                onRemoveDistrict = { marketplaceViewModel.updateFilters(district = "All") },
                onRemoveFarm = { marketplaceViewModel.updateFilters(farm = "All") },
                onRemovePrice = { marketplaceViewModel.updateFilters(minPrice = null, maxPrice = null) },
                onRemoveAge = { marketplaceViewModel.updateFilters(minAgeMonths = null, maxAgeMonths = null) },
                onRemoveWeight = { marketplaceViewModel.updateFilters(minWeightKg = null, maxWeightKg = null) },
                onRemoveAvailable = { marketplaceViewModel.updateFilters(onlyAvailable = false, availabilityFilter = "All") },
                onClearAll = { marketplaceViewModel.clearFilters() }
            )

            if (uiState.isLoading && uiState.filteredGoats.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("marketplace_refresh_indicator")
                )
            }

            if (uiState.errorMessage != null && uiState.filteredGoats.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "Sync error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = { marketplaceViewModel.refreshMarketplace() },
                            modifier = Modifier.testTag("btn_retry_inline_marketplace")
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            when {
                uiState.isLoading && uiState.filteredGoats.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Loading verified livestock from Supabase...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                uiState.errorMessage != null && uiState.filteredGoats.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "Unable to Load Marketplace",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = uiState.errorMessage ?: "Please check your internet connection.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Button(
                                    onClick = { marketplaceViewModel.refreshMarketplace() },
                                    modifier = Modifier.testTag("btn_retry_marketplace")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }

                uiState.filteredGoats.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Pets,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = "No Matching Goat Listings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (uiState.activeFilterCount > 0 || uiState.searchQuery.isNotBlank())
                                    "No goats matched your current search or filter criteria. Try resetting filters to view all verified listings."
                                else
                                    "Approved goat listings from verified farms will appear here automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (uiState.activeFilterCount > 0 || uiState.searchQuery.isNotBlank()) {
                                Button(
                                    onClick = { marketplaceViewModel.clearFilters() },
                                    modifier = Modifier.testTag("btn_reset_filters_empty_state")
                                ) {
                                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Reset Filters")
                                }
                            } else {
                                OutlinedButton(onClick = { marketplaceViewModel.refreshMarketplace() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Refresh")
                                }
                            }
                        }
                    }
                }

                else -> {
                    val farmsById = remember(uiState.farms) {
                        uiState.farms.associateBy { it.id }
                    }
                    val verifiedFarms = remember(uiState.farms) {
                        uiState.farms.filter { it.verificationStatus.isApprovedOrVerified || it.isAmmalOwnFarm }
                    }

                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = when {
                            maxWidth < 310.dp -> 1
                            maxWidth < 720.dp -> 2
                            else -> 3
                        }
                        val chunkedGoats = remember(uiState.filteredGoats, columns) {
                            uiState.filteredGoats.chunked(columns)
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("marketplace_grid_list"),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Verified Partner Farms Showcase Row (with Farm Banner & Logo per Requirement 4)
                            if (verifiedFarms.isNotEmpty()) {
                                item(key = "verified_farms_strip") {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = "Verified Farms (${verifiedFarms.size})",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            verifiedFarms.forEach { farm ->
                                                Surface(
                                                    onClick = { onNavigateToFarmDetail(farm.id) },
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                                    modifier = Modifier.width(210.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        Surface(
                                                            shape = CircleShape,
                                                            color = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(36.dp)
                                                        ) {
                                                            if (farm.logoUrl.isNotBlank()) {
                                                                coil.compose.AsyncImage(
                                                                    model = farm.logoUrl,
                                                                    contentDescription = farm.name,
                                                                    modifier = Modifier.fillMaxSize(),
                                                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                                                )
                                                            } else {
                                                                Box(contentAlignment = Alignment.Center) {
                                                                    Icon(
                                                                        imageVector = Icons.Default.Agriculture,
                                                                        contentDescription = null,
                                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                                        modifier = Modifier.size(18.dp)
                                                                    )
                                                                }
                                                            }
                                                        }
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(
                                                                text = farm.name,
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                            Text(
                                                                text = farm.location.ifBlank { farm.district.ifBlank { "Tamil Nadu" } },
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            item(key = "results_summary_header") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${uiState.filteredGoats.size} Verified Listing${if (uiState.filteredGoats.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = uiState.sortOption.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            items(
                                items = chunkedGoats,
                                key = { row -> row.joinToString("_") { it.id } }
                            ) { rowGoats ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowGoats.forEach { goat ->
                                        val linkedFarm = farmsById[goat.farmId]
                                        GoatCard(
                                            goat = goat.copy(
                                                farmName = goat.farmName.ifBlank { linkedFarm?.name.orEmpty() },
                                                farmLocation = goat.farmLocation.ifBlank { linkedFarm?.location.orEmpty() }
                                            ),
                                            onClick = { onNavigateToGoatDetail(goat.id) },
                                            isWishlisted = uiState.wishlistedIds.contains(goat.id),
                                            onToggleWishlist = {
                                                if (currentUser != null) {
                                                    marketplaceViewModel.toggleWishlist(currentUser.id, goat.id)
                                                }
                                            },
                                            farmLogoUrl = linkedFarm?.logoUrl.orEmpty(),
                                            isCompactGrid = columns >= 2,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    if (rowGoats.size < columns) {
                                        repeat(columns - rowGoats.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        GoatFilterBottomSheet(
            selectedBreed = uiState.selectedBreed,
            selectedGender = uiState.selectedGender,
            selectedDistrict = uiState.selectedDistrict,
            selectedFarm = uiState.selectedFarm,
            selectedAvailability = uiState.availabilityFilter,
            minPriceInput = uiState.minPrice?.toInt()?.toString().orEmpty(),
            maxPriceInput = uiState.maxPrice?.toInt()?.toString().orEmpty(),
            minAgeInput = uiState.minAgeMonths?.toString().orEmpty(),
            maxAgeInput = uiState.maxAgeMonths?.toString().orEmpty(),
            minWeightInput = uiState.minWeightKg?.toString().orEmpty(),
            maxWeightInput = uiState.maxWeightKg?.toString().orEmpty(),
            onlyAvailable = uiState.onlyAvailable,
            selectedSort = uiState.sortOption,
            availableBreeds = uiState.availableBreeds,
            availableDistricts = uiState.availableLocations,
            availableFarms = uiState.availableFarms,
            onApply = { breed, gender, district, farm, availability, minPrice, maxPrice, minAge, maxAge, minWeight, maxWeight, availableOnly, sort ->
                marketplaceViewModel.updateFilters(
                    breed = breed,
                    gender = gender,
                    district = district,
                    farm = farm,
                    availabilityFilter = availability,
                    minPrice = minPrice.trim().toDoubleOrNull(),
                    maxPrice = maxPrice.trim().toDoubleOrNull(),
                    minAgeMonths = minAge.trim().toIntOrNull(),
                    maxAgeMonths = maxAge.trim().toIntOrNull(),
                    minWeightKg = minWeight.trim().toDoubleOrNull(),
                    maxWeightKg = maxWeight.trim().toDoubleOrNull(),
                    onlyAvailable = availableOnly,
                    sortOption = sort
                )
            },
            onClear = { marketplaceViewModel.clearFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }
}
