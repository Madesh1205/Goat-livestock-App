package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.components.GoatGridCard
import com.ammalfarm.adusanthai.ui.components.GoatListCard
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceUiState
import com.ammalfarm.adusanthai.ui.viewmodel.ViewMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmDetailScreen(
    farm: Farm,
    uiState: MarketplaceUiState,
    onBackClick: () -> Unit,
    onGoatClick: (Goat) -> Unit,
    onBreedFilterClick: (String) -> Unit,
    onSubmitReport: (targetType: String, targetId: String, targetTitle: String, reason: ReportReason, description: String, evidencePhotoUrl: String?) -> Unit = { _, _, _, _, _, _ -> },
    onToggleWishlist: (Goat) -> Unit = {},
    onRetryLoadFarmGoats: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedBreedFilter by remember { mutableStateOf<String?>(null) }
    var selectedSortOption by remember { mutableStateOf(SortOption.RELEVANCE) }
    var farmViewMode by remember { mutableStateOf(ViewMode.GRID) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showReportFarmDialog by remember { mutableStateOf(false) }
    var reportSelectedReason by remember { mutableStateOf(ReportReason.SUSPICIOUS_SELLER) }
    var reportDetails by remember { mutableStateOf("") }
    var reportEvidenceUrl by remember { mutableStateOf("") }

    // Reset local filters and load public goats when switching farms
    LaunchedEffect(farm.id) {
        searchQuery = ""
        selectedBreedFilter = null
        selectedSortOption = SortOption.RELEVANCE
        onRetryLoadFarmGoats()
    }

    // Security check: Customer cannot view unapproved or suspended farms
    val isFarmApproved = farm.verificationStatus == VerificationStatus.APPROVED

    if (!isFarmApproved) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Farm Verification") },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Security Alert",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Farm Unavailable to Public",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This farm is currently ${farm.verificationStatus.name.lowercase()} and is not approved for public customer listings. Our marketplace only displays verified, compliant livestock breeders.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onBackClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Return to Marketplace")
                        }
                    }
                }
            }
        }
        return
    }

    // Source of eligible public goats for this farm
    // Uses the public marketplace dataset strictly for this exact farm UUID
    val validFarmUuid = remember(farm.id) {
        try { com.ammalfarm.adusanthai.data.dto.ensureValidUuid(farm.id.trim()) } catch (_: Exception) { farm.id.trim() }
    }
    val farmGoats = remember(uiState.selectedFarmGoats, validFarmUuid, farm.id) {
        uiState.selectedFarmGoats.filter {
            (it.farmId == farm.id || it.farmId == validFarmUuid) &&
            it.approvalStatus == ApprovalStatus.APPROVED &&
            it.availabilityStatus == AvailabilityStatus.AVAILABLE
        }
    }

    val totalListedCount = remember(farmGoats.size, uiState.selectedFarmTotalListed) {
        maxOf(farmGoats.size, uiState.selectedFarmTotalListed)
    }
    val readyStockCount = farmGoats.size

    // Available breeds in this farm
    val availableBreeds = remember(farmGoats) {
        farmGoats.map { it.breed }.distinct().sorted()
    }

    // Filtered and sorted goats within the farm
    val filteredFarmGoats = remember(farmGoats, searchQuery, selectedBreedFilter, selectedSortOption) {
        var list = farmGoats.filter { goat ->
            val matchesBreed = selectedBreedFilter == null || goat.breed.equals(selectedBreedFilter, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() ||
                    goat.name.contains(searchQuery, ignoreCase = true) ||
                    goat.goatCode.contains(searchQuery, ignoreCase = true) ||
                    goat.breed.contains(searchQuery, ignoreCase = true) ||
                    goat.description.contains(searchQuery, ignoreCase = true)
            matchesBreed && matchesQuery
        }

        val statusPriority: (AvailabilityStatus) -> Int = { status ->
            when (status) {
                AvailabilityStatus.AVAILABLE -> 0
                AvailabilityStatus.BOOKING_PENDING -> 1
                AvailabilityStatus.RESERVED -> 2
                AvailabilityStatus.CONFIRMED -> 3
                AvailabilityStatus.COMPLETED -> 4
                AvailabilityStatus.SOLD -> 5
                AvailabilityStatus.CANCELLED -> 6
                AvailabilityStatus.REJECTED -> 7
            }
        }

        when (selectedSortOption) {
            SortOption.RELEVANCE -> list.sortedWith(
                compareBy<Goat> { statusPriority(it.availabilityStatus) }
                    .thenByDescending { it.isFeatured }
                    .thenByDescending { it.createdAt }
            )
            SortOption.PRICE_LOW_HIGH -> list.sortedBy { it.finalPrice }
            SortOption.PRICE_HIGH_LOW -> list.sortedByDescending { it.finalPrice }
            SortOption.NEWEST -> list.sortedByDescending { it.createdAt }
            SortOption.AGE_YOUNGEST -> list.sortedBy { it.ageMonths }
            SortOption.AGE_OLDEST -> list.sortedByDescending { it.ageMonths }
            SortOption.WEIGHT_HEAVIEST -> list.sortedByDescending { it.weightKg }
            SortOption.WEIGHT_LIGHTEST -> list.sortedBy { it.weightKg }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = farm.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Verified Farm",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "${farm.location}, ${farm.state}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("farm_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val callIntent = Intent(Intent.ACTION_DIAL).apply {
                                data = Uri.parse("tel:${farm.contactNumber}")
                            }
                            try {
                                context.startActivity(callIntent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Contact: ${farm.contactNumber}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Icon(Icons.Default.Phone, contentDescription = "Call Farm", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(
                        onClick = {
                            val shareUrl = "https://adusanthai.ammalfarm.dpdns.org/farm/${farm.id}"
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, farm.name)
                                putExtra(Intent.EXTRA_TEXT, "Check out ${farm.name} on Adu Santhai: ${farm.description}\n\nView farm: $shareUrl")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Farm"))
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share Farm")
                    }
                    IconButton(
                        onClick = { showReportFarmDialog = true },
                        modifier = Modifier.testTag("report_farm_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Flag,
                            contentDescription = "Report Farm",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(if (farmViewMode == ViewMode.GRID) 2 else 1),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. HERO BANNER & FARM PROFILE HEADER
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        // Banner with overlay
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(farm.bannerUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Farm Banner",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                Brush.verticalGradient(
                                                    colors = listOf(
                                                        MaterialTheme.colorScheme.primaryContainer,
                                                        MaterialTheme.colorScheme.primary
                                                    )
                                                )
                                            )
                                    )
                                }
                            )

                            // Gradient scrim
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                                        )
                                    )
                            )

                            // Top verified ribbon
                            Surface(
                                shape = RoundedCornerShape(bottomEnd = 12.dp),
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.align(Alignment.TopStart)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.VerifiedUser,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "VERIFIED LIVESTOCK PARTNER",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Farm Identity & Avatar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                // Farm Logo Avatar
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .offset(y = (-36).dp)
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
                                            loading = {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            },
                                            error = {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Default.Agriculture,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(36.dp)
                                                    )
                                                }
                                            }
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Agriculture,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(36.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .offset(y = (-16).dp)
                                ) {
                                    Text(
                                        text = farm.name,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "${farm.location}, ${farm.state}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            // Verification & Owner Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset(y = (-12).dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFE8F5E9),
                                    border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Verified,
                                            contentDescription = null,
                                            tint = Color(0xFF2E7D32),
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Verified Breeder",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                ) {
                                    Text(
                                        text = "Owner: ${farm.ownerName}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Bio / Description
                            Text(
                                text = farm.description,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Badges & Capabilities Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                AssistChip(
                                    onClick = {},
                                    leadingIcon = {
                                        Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                                    },
                                    label = { Text("Farm Visit", fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
            }

            // 2. FARM STATISTICS DASHBOARD
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        FarmStatBox(
                            icon = Icons.Default.Pets,
                            value = "$totalListedCount",
                            label = "Total Listed",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        FarmStatBox(
                            icon = Icons.Default.CheckCircle,
                            value = "$readyStockCount",
                            label = "Ready Stock",
                            tint = Color(0xFF2E7D32)
                        )
                        FarmStatBox(
                            icon = Icons.Default.VerifiedUser,
                            value = "Verified",
                            label = "Breeder Status",
                            tint = Color(0xFF1976D2)
                        )
                        FarmStatBox(
                            icon = Icons.Default.Timer,
                            value = "< 1 hr",
                            label = "Response",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // 3. CATALOG CONTROLS: SEARCH & BREED FILTER CHIPS
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Available Goats at ${farm.name}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${filteredFarmGoats.size} verified goat listings ready for booking",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // View mode & sort controls
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    farmViewMode = if (farmViewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                                }
                            ) {
                                Icon(
                                    imageVector = if (farmViewMode == ViewMode.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                    contentDescription = "Toggle Grid/List View",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        Icons.Default.Sort,
                                        contentDescription = "Sort Options",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Relevance / Featured") },
                                        onClick = {
                                            selectedSortOption = SortOption.RELEVANCE
                                            showSortMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Price: Low to High") },
                                        onClick = {
                                            selectedSortOption = SortOption.PRICE_LOW_HIGH
                                            showSortMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Price: High to Low") },
                                        onClick = {
                                            selectedSortOption = SortOption.PRICE_HIGH_LOW
                                            showSortMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Newest Stock") },
                                        onClick = {
                                            selectedSortOption = SortOption.NEWEST
                                            showSortMenu = false
                                        },
                                        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // In-farm search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("farm_search_input"),
                        placeholder = { Text("Search this farm by breed, name, or code...", fontSize = 13.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface
                        )
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Breed selection chips for this farm
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            FilterChip(
                                selected = selectedBreedFilter == null,
                                onClick = { selectedBreedFilter = null },
                                label = { Text("All Breeds ($readyStockCount)", fontSize = 12.sp) },
                                leadingIcon = if (selectedBreedFilter == null) {
                                    { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                } else null
                            )
                        }

                        availableBreeds.forEach { breed ->
                            item {
                                val isSelected = selectedBreedFilter == breed
                                val breedCount = farmGoats.count { it.breed == breed }
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedBreedFilter = if (isSelected) null else breed
                                    },
                                    label = { Text("$breed ($breedCount)", fontSize = 12.sp) },
                                    leadingIcon = if (isSelected) {
                                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            // 4. GOAT CATALOG GRID OR SEPARATE EMPTY STATES
            if (uiState.isSelectedFarmLoading && farmGoats.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Loading ${farm.name} listings...",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (uiState.selectedFarmError != null && farmGoats.isEmpty()) {
                // Empty State D: Database/query failure
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Query Failure",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Unable to load goats. Please try again.",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.selectedFarmError ?: "Failed to query the database. Check connection.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onRetryLoadFarmGoats,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            } else if (farmGoats.isEmpty()) {
                if (totalListedCount > 0) {
                    // Empty State B: Goats exist but none are currently available
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Schedule,
                                    contentDescription = "None Currently Available",
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "$totalListedCount goats listed · 0 currently available.",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "All goats for this farm are currently reserved, pending review, or out of stock.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    // Empty State A: No goats exist
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pets,
                                    contentDescription = "No Goats Listed",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No goats listed by this farm yet.",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${farm.name} has not published any listings to the marketplace yet.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else if (filteredFarmGoats.isEmpty()) {
                // Empty State C: Search/filter excludes all goats
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.SearchOff,
                                contentDescription = "No Results",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No goats match your current filters.",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Try adjusting your search query or breed filter.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = {
                                    searchQuery = ""
                                    selectedBreedFilter = null
                                }
                            ) {
                                Text("Reset Farm Filters")
                            }
                        }
                    }
                }
            } else {
                items(filteredFarmGoats, key = { it.id }) { goat ->
                    val isWishlisted = uiState.wishlistGoatIds.contains(goat.id)
                    if (farmViewMode == ViewMode.GRID) {
                        GoatGridCard(
                            goat = goat,
                            onClick = { onGoatClick(goat) },
                            isWishlisted = isWishlisted,
                            onWishlistToggle = { onToggleWishlist(goat) },
                            modifier = Modifier.testTag("farm_goat_card_${goat.id}")
                        )
                    } else {
                        GoatListCard(
                            goat = goat,
                            onClick = { onGoatClick(goat) },
                            isWishlisted = isWishlisted,
                            onWishlistToggle = { onToggleWishlist(goat) },
                            modifier = Modifier.testTag("farm_goat_list_${goat.id}")
                        )
                    }
                }
            }
        }
    }

    // --- REPORT FARM DIALOG ---
    if (showReportFarmDialog) {
        AlertDialog(
            onDismissRequest = { showReportFarmDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Outlined.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Report Breeder / Farm",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Help us keep the marketplace safe. Please select the primary reason for reporting ${farm.name}:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ReportReason.values().forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reportSelectedReason = reason }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = reportSelectedReason == reason,
                                onClick = { reportSelectedReason = reason }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = reason.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (reportSelectedReason == reason) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it },
                        label = { Text("Description / Specific incident *") },
                        placeholder = { Text("Describe the suspicious activity or violation in detail...") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reportEvidenceUrl,
                        onValueChange = { reportEvidenceUrl = it },
                        label = { Text("Evidence / Screenshot Photo URL (optional)") },
                        placeholder = { Text("https://... or photo proof link") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val desc = reportDetails.ifBlank { reportSelectedReason.displayName }
                        val photo = reportEvidenceUrl.trim().ifBlank { null }
                        onSubmitReport(
                            "FARM",
                            farm.id,
                            farm.name,
                            reportSelectedReason,
                            desc,
                            photo
                        )
                        showReportFarmDialog = false
                        reportDetails = ""
                        reportEvidenceUrl = ""
                        Toast.makeText(
                            context,
                            "Thank you. Your report on ${farm.name} has been submitted for investigation.",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Submit Report", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportFarmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FarmStatBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    tint: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
