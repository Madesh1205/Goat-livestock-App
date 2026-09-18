package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.core.supabase.SupabaseConfig
import com.example.model.*
import com.example.ui.components.ActiveFilterChips
import com.example.ui.components.DatabaseConnectionErrorScreen
import com.example.ui.components.GoatFilterBottomSheet
import com.example.ui.components.GoatGridCard
import com.example.ui.components.GoatListCard
import com.example.ui.components.NetworkErrorCard
import com.example.ui.components.NetworkOfflineBanner
import com.example.ui.viewmodel.MarketplaceUiState
import com.example.ui.viewmodel.ViewMode

data class BreedCategoryItem(
    val name: String,
    val subtitle: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceHomeScreen(
    uiState: MarketplaceUiState,
    onSearchQueryChange: (String) -> Unit,
    onApplyCriteria: (GoatFilterCriteria) -> Unit,
    onBreedSelect: (String?) -> Unit,
    onPurposeSelect: (GoatPurpose?) -> Unit,
    onSortOptionSelect: (SortOption) -> Unit,
    onClearAllFilters: () -> Unit,
    onRemoveBreed: () -> Unit,
    onRemoveFarm: () -> Unit,
    onRemoveGender: () -> Unit,
    onRemoveAge: () -> Unit,
    onRemoveWeight: () -> Unit,
    onRemovePrice: () -> Unit,
    onRemovePurpose: () -> Unit,
    onRemoveLocation: () -> Unit,
    onRemoveAvailability: () -> Unit,
    onRemoveSearchQuery: () -> Unit,
    onGoatClick: (Goat) -> Unit,
    onFarmClick: (String) -> Unit,
    onBookingsClick: () -> Unit,
    onProfileClick: () -> Unit = {},
    onWishlistClick: () -> Unit = {},
    onToggleWishlist: (Goat) -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    onNavigateToSuperAdmin: () -> Unit = {},
    onNavigateToFarmAdmin: () -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isDatabaseConnected = SupabaseConfig.isConfigured && !(uiState.networkError != null && uiState.goats.isEmpty() && uiState.farms.isEmpty() && !uiState.isLoading)

    if (!isDatabaseConnected) {
        DatabaseConnectionErrorScreen(
            errorMessage = uiState.networkError ?: if (!SupabaseConfig.isConfigured) "Database not connected: Supabase configuration is missing or unconfigured." else null,
            isRetrying = uiState.isRetrying,
            onRetry = onRefresh,
            onProfileClick = onProfileClick,
            modifier = modifier
        )
        return
    }

    var showFilterSheet by remember { mutableStateOf(false) }
    var showNotificationsSheet by remember { mutableStateOf(false) }
    var currentViewMode by remember { mutableStateOf(ViewMode.GRID) }

    // Breed Category definitions derived dynamically from real database listings
    val breedCategories = remember(uiState.allBreeds, uiState.goats) {
        val distinctBreeds = (uiState.allBreeds + uiState.goats.map { it.breed.trim() })
            .filter { it.isNotBlank() }
            .distinct()
        if (distinctBreeds.isEmpty()) {
            emptyList()
        } else {
            listOf(BreedCategoryItem("All", "All Breeds")) + distinctBreeds.map { BreedCategoryItem(it, it) }
        }
    }

    val criteria = uiState.filterCriteria
    val activeFilterCount = criteria.activeFilterCount
    val isFilteringOrSearching = !criteria.isDefault

    // Distinct sections (showcase)
    val featuredGoats = remember(uiState.goats) {
        uiState.goats.filter { it.isFeatured || (it.rating >= 4.0 && it.reviewCount > 0) }.take(5)
    }

    val recentlyAddedGoats = remember(uiState.goats) {
        uiState.goats.take(4)
    }

    // Filter approved farms by search query (farm name / location / description)
    val matchingApprovedFarms = remember(uiState.approvedFarms, criteria.searchQuery) {
        val approved = uiState.approvedFarms
        if (criteria.searchQuery.isBlank()) {
            approved
        } else {
            val queryTerms = criteria.searchQuery.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
            approved.filter { farm ->
                queryTerms.all { term ->
                    farm.name.lowercase().contains(term) ||
                    farm.location.lowercase().contains(term) ||
                    farm.description.lowercase().contains(term)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                // Responsive Top App Bar for mobile screens: Brand on left, Action buttons on right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Logo and Brand Name (weight(1f) ensures it adapts flexibly on narrow screens without pushing actions)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onClearAllFilters() }
                    ) {
                        com.example.ui.components.AduSanthaiLogoBadge(
                            size = 38.dp,
                            elevation = 2.dp
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "ADU",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "SANTHAI",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = "AMMAL FARM • LIVESTOCK",
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Right: Actions Row (Wishlist, Notifications, Profile, plus role shortcuts)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Admin Shortcut (Compact icon button for admin roles)
                        if (uiState.currentUser?.role == UserRole.SUPER_ADMIN) {
                            IconButton(
                                onClick = onNavigateToSuperAdmin,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AdminPanelSettings,
                                    contentDescription = "Admin Console",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else if (uiState.currentUser?.role == UserRole.FARM_ADMIN) {
                            IconButton(
                                onClick = onNavigateToFarmAdmin,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Storefront,
                                    contentDescription = "Farm Portal",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Wishlist Action with Badge
                        IconButton(
                            onClick = onWishlistClick,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .testTag("home_wishlist_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    val wishlistCount = uiState.wishlistGoatIds.size
                                    if (wishlistCount > 0) {
                                        Badge(
                                            containerColor = Color(0xFFE91E63),
                                            contentColor = Color.White
                                        ) {
                                            Text(wishlistCount.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = if (uiState.wishlistGoatIds.isNotEmpty()) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Wishlist",
                                    tint = if (uiState.wishlistGoatIds.isNotEmpty()) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Notification Action with Badge
                        IconButton(
                            onClick = onNotificationsClick,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            BadgedBox(
                                badge = {
                                    val unreadCount = uiState.notifications.count { !it.isRead }
                                    if (unreadCount > 0) {
                                        Badge(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = Color.White
                                        ) {
                                            Text(unreadCount.toString(), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Notifications,
                                    contentDescription = "Notifications",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Profile Avatar
                        IconButton(
                            onClick = onProfileClick,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        ) {
                            if (uiState.currentUser != null) {
                                Text(
                                    text = uiState.currentUser.name.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = "Profile",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Search & Filter Input Bar (Taller 52dp, Clear Placeholder & Icon Sizes)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Box(modifier = Modifier.weight(1f)) {
                                if (criteria.searchQuery.isEmpty()) {
                                    Text(
                                        text = "Search goat breed, farm, purpose...",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = criteria.searchQuery,
                                    onValueChange = onSearchQueryChange,
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("marketplace_search_input")
                                )
                            }
                            if (criteria.searchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = onRemoveSearchQuery,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .testTag("clear_search_button")
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Clear Search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Filter Button with Badge showing active filter count (52dp x 52dp)
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (activeFilterCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .border(
                                BorderStroke(
                                    if (activeFilterCount > 0) 1.5.dp else 1.dp,
                                    if (activeFilterCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
                                ),
                                RoundedCornerShape(14.dp)
                            )
                    ) {
                        BadgedBox(
                            badge = {
                                if (activeFilterCount > 0) {
                                    Badge(
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = Color.White
                                    ) {
                                        Text("$activeFilterCount", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Open Filters",
                                tint = if (activeFilterCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Active Filter Chips Bar (Removable)
                ActiveFilterChips(
                    criteria = criteria,
                    onRemoveBreed = onRemoveBreed,
                    onRemoveFarm = onRemoveFarm,
                    onRemoveGender = onRemoveGender,
                    onRemoveAge = onRemoveAge,
                    onRemoveWeight = onRemoveWeight,
                    onRemovePrice = onRemovePrice,
                    onRemovePurpose = onRemovePurpose,
                    onRemoveLocation = onRemoveLocation,
                    onRemoveAvailability = onRemoveAvailability,
                    onRemoveSearchQuery = onRemoveSearchQuery,
                    onClearAll = onClearAllFilters
                )

                // Horizontal Breed/Category Showcase (Scrollable, Enhanced Typography & Contrast)
                if (breedCategories.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(breedCategories) { category ->
                            val isSelected = (category.name == "All" && criteria.breed == null) ||
                                    (criteria.breed?.contains(category.name, ignoreCase = true) == true)

                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (category.name == "All") {
                                        onBreedSelect(null)
                                    } else {
                                        onBreedSelect(category.name)
                                    }
                                },
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            text = category.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                                        )
                                        Text(
                                            text = category.subtitle,
                                            fontSize = 10.5.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (category.name == "All") Icons.Default.AllInclusive else Icons.Default.Pets,
                                        contentDescription = category.name,
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("marketplace_pull_to_refresh")
        ) {
            // Main Catalog Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (currentViewMode == ViewMode.GRID) 2 else 1),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
            // Network Offline / Sync Banner
            if (uiState.networkError != null && uiState.goats.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    NetworkOfflineBanner(
                        isRetrying = uiState.isRetrying,
                        onRetry = onRefresh
                    )
                }
            }

            // If not filtering, show curated Featured & Farm carousels
            if (!isFilteringOrSearching) {
                // 1. FEATURED GOATS SPOTLIGHT
                if (featuredGoats.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.WorkspacePremium,
                                        contentDescription = "Featured",
                                        tint = Color(0xFFD97706),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Featured Champion Pedigrees",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(featuredGoats, key = { "feat-${it.id}" }) { goat ->
                                    Card(
                                        modifier = Modifier
                                            .width(280.dp)
                                            .clickable { onGoatClick(goat) },
                                        shape = RoundedCornerShape(16.dp),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                    ) {
                                        Column {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(140.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                            ) {
                                                val photo = goat.photos.firstOrNull() ?: ""
                                                SubcomposeAsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(photo)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = goat.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                    error = {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize(),
                                                            contentAlignment = Alignment.Center
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Pets,
                                                                contentDescription = "Goat",
                                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                                                modifier = Modifier.size(40.dp)
                                                            )
                                                        }
                                                    }
                                                )

                                                // Top Banner
                                                Surface(
                                                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.align(Alignment.TopStart)
                                                ) {
                                                    Text(
                                                        text = "⭐ TOP BREEDING STUD",
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Black,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }

                                                // Price Chip
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = Color.Black.copy(alpha = 0.80f),
                                                    modifier = Modifier
                                                        .padding(8.dp)
                                                        .align(Alignment.BottomEnd)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                    ) {
                                                        if (goat.hasDiscount) {
                                                            Text(
                                                                text = "₹${goat.finalPrice.toInt()}",
                                                                color = Color.White,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Black
                                                            )
                                                            Text(
                                                                text = "₹${goat.price.toInt()}",
                                                                color = Color.LightGray,
                                                                fontSize = 10.sp,
                                                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                                            )
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF2E7D32)
                                                            ) {
                                                                Text(
                                                                    text = "${goat.discountPercentage.toInt()}%",
                                                                    color = Color.White,
                                                                    fontSize = 9.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        } else {
                                                            Text(
                                                                text = "₹${goat.price.toInt()}",
                                                                color = Color.White,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Black
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = goat.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${goat.breed} • ${goat.ageMonths} mo • ${goat.weightKg} kg",
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "📍 ${goat.farmName}",
                                                        fontSize = 10.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (goat.rating > 0.0 && goat.reviewCount > 0) {
                                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                                            Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFC107), modifier = Modifier.size(11.dp))
                                                            Text(text = " ${String.format("%.1f", goat.rating)}", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                                        }
                                                    } else {
                                                        Text(text = "No reviews", fontSize = 9.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            }

            // POPULAR / MATCHING VERIFIED FARMS (Filtered by farm search query, or shown during standard browsing)
            val shouldShowVerifiedFarms = if (criteria.searchQuery.isNotBlank()) {
                matchingApprovedFarms.isNotEmpty()
            } else {
                !isFilteringOrSearching && matchingApprovedFarms.isNotEmpty()
            }

            if (shouldShowVerifiedFarms) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Verified,
                                    contentDescription = "Farms",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (criteria.searchQuery.isNotBlank()) "Matching Verified Farms" else "Popular Verified Farms",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (criteria.searchQuery.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "${matchingApprovedFarms.size} ${if (matchingApprovedFarms.size == 1) "farm" else "farms"}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(matchingApprovedFarms, key = { it.id }) { farm ->
                                Card(
                                    modifier = Modifier
                                        .width(285.dp)
                                        .clickable { onFarmClick(farm.id) },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.size(62.dp)
                                        ) {
                                            if (farm.logoUrl.isNotBlank()) {
                                                SubcomposeAsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(farm.logoUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = farm.name,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize(),
                                                    error = {
                                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                            Icon(
                                                                Icons.Default.Agriculture,
                                                                contentDescription = null,
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(28.dp)
                                                            )
                                                        }
                                                    }
                                                )
                                            } else {
                                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Default.Agriculture,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(28.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(12.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = farm.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.5.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(
                                                    Icons.Default.CheckCircle,
                                                    contentDescription = "Verified",
                                                    tint = Color(0xFF2E7D32),
                                                    modifier = Modifier.size(15.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "📍 ${farm.location}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(3.dp))
                                            val farmRev = uiState.allReviews.filter { it.farmId == farm.id }
                                            val farmAvg = if (farmRev.isNotEmpty()) {
                                                val sum = farmRev.sumOf { it.rating.toDouble() }
                                                Math.round((sum / farmRev.size) * 10.0) / 10.0
                                            } else {
                                                farm.rating
                                            }
                                            val farmCount = if (farmRev.isNotEmpty()) farmRev.size else farm.totalReviews

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                if (farmCount > 0 && farmAvg > 0.0) {
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = Color(0xFFFFF8E1).copy(alpha = 0.15f)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Star,
                                                                contentDescription = null,
                                                                tint = Color(0xFFFFC107),
                                                                modifier = Modifier.size(12.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(2.dp))
                                                            Text(
                                                                text = String.format("%.1f", farmAvg),
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = MaterialTheme.colorScheme.onSurface
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                } else {
                                                    Text(
                                                        text = "No reviews",
                                                        fontSize = 11.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                }
                                                Text(
                                                    text = "• ${farm.totalGoatsListed} listed",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section Header & Result Count (Unified Controls & Consistent Heights)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val headerTitle = when {
                                criteria.searchQuery.isNotBlank() -> "Results for \"${criteria.searchQuery}\""
                                criteria.breed != null -> "${criteria.breed} Goats"
                                criteria.purpose != null -> "${criteria.purpose.name.lowercase().replaceFirstChar { it.uppercase() }} Goats"
                                else -> "All Approved Listings"
                            }
                            Text(
                                text = headerTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Showing ${uiState.goats.size} verified goats",
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Sort Badge Button (Unified 38dp height)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { showFilterSheet = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Sort,
                                        contentDescription = "Sort",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = criteria.sortBy.displayName,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            // View Mode Toggle Button (Grid vs List, Matching 38dp x 38dp)
                            IconButton(
                                onClick = {
                                    currentViewMode = if (currentViewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                                },
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .border(
                                        BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                                        RoundedCornerShape(10.dp)
                                    )
                            ) {
                                Icon(
                                    imageVector = if (currentViewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = "Toggle Grid/List View",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // LOADING STATE
            if (uiState.isLoading && uiState.goats.isEmpty()) {
                items(4) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            // EMPTY STATE: High-Grade, Production-Intentional Empty State
            else if (uiState.goats.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (uiState.networkError != null) {
                        NetworkErrorCard(
                            errorMessage = uiState.networkError,
                            isRetrying = uiState.isRetrying,
                            onRetry = onRefresh,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp, horizontal = 4.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 32.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(56.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Pets,
                                            contentDescription = "Livestock",
                                            modifier = Modifier.size(28.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = if (isFilteringOrSearching) "No goats match your filters" else "None",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (isFilteringOrSearching)
                                        "Try clearing filters or adjusting your search keywords."
                                    else
                                        "No items found in database.",
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                Spacer(modifier = Modifier.height(20.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isFilteringOrSearching) {
                                        OutlinedButton(
                                            onClick = onClearAllFilters,
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                        ) {
                                            Icon(
                                                Icons.Outlined.FilterAltOff,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Clear Filters", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                    Button(
                                        onClick = onRefresh,
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Refresh Listings", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // PRODUCT LISTINGS (GRID OR LIST)
            else {
                items(uiState.goats, key = { it.id }) { goat ->
                    val isWishlisted = uiState.wishlistGoatIds.contains(goat.id)
                    if (currentViewMode == ViewMode.GRID) {
                        GoatGridCard(
                            goat = goat,
                            onClick = { onGoatClick(goat) },
                            isWishlisted = isWishlisted,
                            onWishlistToggle = { onToggleWishlist(goat) }
                        )
                    } else {
                        GoatListCard(
                            goat = goat,
                            onClick = { onGoatClick(goat) },
                            isWishlisted = isWishlisted,
                            onWishlistToggle = { onToggleWishlist(goat) }
                        )
                    }
                }
            }
        }
    }
    }

    // Modal Filter & Sort Bottom Sheet
    if (showFilterSheet) {
        GoatFilterBottomSheet(
            currentCriteria = criteria,
            availableFarms = uiState.approvedFarms,
            totalMatchingCount = uiState.goats.size,
            onApplyFilters = { newCriteria ->
                onApplyCriteria(newCriteria)
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }

    // Notifications Bottom Sheet
    if (showNotificationsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNotificationsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Marketplace Notifications",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.notifications.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No notifications yet.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(uiState.notifications) { notif ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = notif.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = notif.message,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}
