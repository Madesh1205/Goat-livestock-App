package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.model.AvailabilityStatus
import com.example.model.Goat
import com.example.model.GoatGender
import com.example.model.UserRole
import com.example.ui.viewmodel.MarketplaceUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    uiState: MarketplaceUiState,
    onBackClick: () -> Unit,
    onGoatClick: (Goat) -> Unit,
    onRemoveFromWishlist: (String) -> Unit,
    onBookGoat: (Goat) -> Unit,
    onBrowseMarketplace: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedBreedFilter by remember { mutableStateOf<String?>(null) }

    val savedGoats = uiState.wishlistedGoats

    val filteredGoats = remember(savedGoats, searchQuery, selectedBreedFilter) {
        savedGoats.filter { goat ->
            val matchesQuery = searchQuery.isBlank() ||
                    goat.name.contains(searchQuery, ignoreCase = true) ||
                    goat.breed.contains(searchQuery, ignoreCase = true) ||
                    goat.farmName.contains(searchQuery, ignoreCase = true) ||
                    goat.tagNumber.contains(searchQuery, ignoreCase = true)

            val matchesBreed = selectedBreedFilter == null || goat.breed.equals(selectedBreedFilter, ignoreCase = true)
            matchesQuery && matchesBreed
        }
    }

    val availableBreeds = remember(savedGoats) {
        savedGoats.map { it.breed }.distinct().sorted()
    }
    val isFarmAdmin = uiState.currentUser?.role == UserRole.FARM_ADMIN

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Saved Livestock",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        if (savedGoats.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = "${savedGoats.size}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("wishlist_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Wishlist")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (savedGoats.isEmpty()) {
                // Empty Wishlist State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Empty Wishlist",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(20.dp))

                            Text(
                                text = "Your Wishlist is Empty",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Text(
                                text = "Save verified breeding bucks, does, and starter herds to your profile for instant comparison and priority bookings.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 20.sp
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            Button(
                                onClick = onBrowseMarketplace,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Explore Marketplace", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                // Header Search & Filters
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Search Bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search by name, tag, or breed...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Breed Filter Chips
                    if (availableBreeds.size > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedBreedFilter == null,
                                onClick = { selectedBreedFilter = null },
                                label = { Text("All (${savedGoats.size})") },
                                shape = RoundedCornerShape(8.dp)
                            )
                            availableBreeds.forEach { breed ->
                                val count = savedGoats.count { it.breed.equals(breed, ignoreCase = true) }
                                FilterChip(
                                    selected = selectedBreedFilter.equals(breed, ignoreCase = true),
                                    onClick = {
                                        selectedBreedFilter = if (selectedBreedFilter == breed) null else breed
                                    },
                                    label = { Text("$breed ($count)") },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }

                // Grid of Wishlisted Goats
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 165.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredGoats, key = { it.id }) { goat ->
                        val isOwnFarm = isFarmAdmin && (
                            (uiState.currentUser?.farmId != null && uiState.currentUser.farmId == goat.farmId) ||
                            uiState.farms.find { it.id == goat.farmId }?.ownerId == uiState.currentUser?.id
                        )
                        WishlistGoatCard(
                            goat = goat,
                            onClick = { onGoatClick(goat) },
                            onRemove = {
                                onRemoveFromWishlist(goat.id)
                                Toast.makeText(context, "Removed ${goat.name} from Wishlist", Toast.LENGTH_SHORT).show()
                            },
                            canBook = !isOwnFarm,
                            onBook = {
                                if (isOwnFarm) {
                                    Toast.makeText(context, "You cannot book goats listed by your own farm.", Toast.LENGTH_LONG).show()
                                } else {
                                    onBookGoat(goat)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WishlistGoatCard(
    goat: Goat,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onBook: () -> Unit,
    canBook: Boolean = true,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            // Photo & Badges Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val photoUrl = goat.photos.firstOrNull() ?: ""
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(photoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = goat.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    error = {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                )

                // Top Left: Availability Pill
                val statusColor = when (goat.availabilityStatus) {
                    AvailabilityStatus.AVAILABLE -> Color(0xFF008947)
                    AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING -> Color(0xFFD97706)
                    else -> Color(0xFF6B7280)
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor,
                    modifier = Modifier
                        .padding(6.dp)
                        .align(Alignment.TopStart)
                ) {
                    Text(
                        text = if (goat.availabilityStatus == AvailabilityStatus.AVAILABLE) "AVAILABLE" else goat.availabilityStatus.name,
                        color = Color.White,
                        fontSize = 7.5.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Top Right: Remove Heart Button
                Surface(
                    shape = CircleShape,
                    color = Color.White,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .testTag("wishlist_remove_button_${goat.id}")
                        .padding(6.dp)
                        .size(28.dp)
                        .align(Alignment.TopEnd)
                        .clickable { onRemove() }
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = "Remove from Wishlist",
                            tint = Color(0xFFE91E63),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Goat Name
            Text(
                text = goat.name,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Breed & Metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${goat.breed} • ${goat.gender.name}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${goat.weightKg} kg",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // Farm Name
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Storefront,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = goat.farmName,
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Price & Reserve Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    if (goat.hasDiscount) {
                        Text(
                            text = goat.formattedFinalPrice,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = goat.formattedPrice,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = goat.formattedDiscountBadge,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF2E7D32)
                            )
                        }
                    } else {
                        Text(
                            text = goat.formattedPrice,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (canBook && goat.availabilityStatus == AvailabilityStatus.AVAILABLE) {
                    FilledTonalButton(
                        onClick = onBook,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Book", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
