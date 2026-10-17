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

                    // Search Bar + Filter Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.searchQuery,
                            onValueChange = { marketplaceViewModel.updateSearchQuery(it) },
                            placeholder = { Text("Search breed, farm, district, tag...") },
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
                                .weight(1f)
                                .testTag("search_marketplace_input")
                        )

                        FilledTonalIconButton(
                            onClick = { showFilterSheet = true },
                            modifier = Modifier
                                .size(52.dp)
                                .testTag("btn_open_filters"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (uiState.activeFilterCount > 0) {
                                        Badge { Text(uiState.activeFilterCount.toString()) }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Filters and Sort")
                            }
                        }
                    }

                    // Horizontal Breed Category Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.availableBreeds.forEach { breed ->
                            val isSelected = uiState.selectedBreed.equals(breed, ignoreCase = true)
                            FilterChip(
                                selected = isSelected,
                                onClick = { marketplaceViewModel.selectBreed(breed) },
                                label = {
                                    Text(
                                        text = breed,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                } else null
                            )
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
                selectedPurpose = uiState.selectedPurpose,
                selectedDistrict = uiState.selectedDistrict,
                onlyAvailable = uiState.onlyAvailable,
                sortOption = uiState.sortOption,
                onRemoveBreed = { marketplaceViewModel.updateFilters(breed = "All") },
                onRemoveGender = { marketplaceViewModel.updateFilters(gender = "All") },
                onRemovePurpose = { marketplaceViewModel.updateFilters(purpose = "All") },
                onRemoveDistrict = { marketplaceViewModel.updateFilters(district = "All") },
                onRemoveAvailable = { marketplaceViewModel.updateFilters(onlyAvailable = false) },
                onClearAll = { marketplaceViewModel.clearFilters() }
            )

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
                                text = "No Eligible Goat Listings Found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (uiState.activeFilterCount > 0 || uiState.searchQuery.isNotBlank())
                                    "Try clearing your active search or filters to view all verified listings."
                                else
                                    "Approved goat listings from verified farms will appear here automatically.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (uiState.activeFilterCount > 0 || uiState.searchQuery.isNotBlank()) {
                                OutlinedButton(onClick = { marketplaceViewModel.clearFilters() }) {
                                    Text("Reset Search & Filters")
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
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
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

                        items(uiState.filteredGoats, key = { it.id }) { goat ->
                            GoatCard(
                                goat = goat,
                                onClick = { onNavigateToGoatDetail(goat.id) },
                                isWishlisted = uiState.wishlistedIds.contains(goat.id),
                                onToggleWishlist = {
                                    if (currentUser != null) {
                                        marketplaceViewModel.toggleWishlist(currentUser.id, goat.id)
                                    }
                                }
                            )
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
            selectedPurpose = uiState.selectedPurpose,
            selectedDistrict = uiState.selectedDistrict,
            onlyAvailable = uiState.onlyAvailable,
            selectedSort = uiState.sortOption,
            availableBreeds = uiState.availableBreeds,
            onApply = { breed, gender, purpose, district, availableOnly, sort ->
                marketplaceViewModel.updateFilters(
                    breed = breed,
                    gender = gender,
                    purpose = purpose,
                    district = district,
                    onlyAvailable = availableOnly,
                    sortOption = sort
                )
            },
            onClear = { marketplaceViewModel.clearFilters() },
            onDismiss = { showFilterSheet = false }
        )
    }
}
