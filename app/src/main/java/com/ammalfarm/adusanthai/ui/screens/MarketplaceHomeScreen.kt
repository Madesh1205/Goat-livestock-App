package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.GoatPurpose
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.viewmodel.AuthUiState
import com.ammalfarm.adusanthai.ui.viewmodel.AuthViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceHomeScreen(
    authViewModel: AuthViewModel,
    uiState: AuthUiState,
    onNavigateToProfile: () -> Unit,
    onNavigateToWishlist: () -> Unit,
    onNavigateToBookings: () -> Unit,
    onNavigateToOrders: () -> Unit,
    onNavigateToGoatDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedPurpose by remember { mutableStateOf<GoatPurpose?>(null) }
    var selectedTab by remember { mutableStateOf(0) }

    // Dummy sample marketplace goats if repository is loading
    val sampleGoats = remember {
        listOf(
            Goat(
                id = "1",
                goatCode = "AS-BOER-001",
                farmName = "Ammal Pedigreed Farm",
                farmLocation = "Salem, Tamil Nadu",
                name = "Champion Boer Buck",
                breed = "Purebred Boer",
                ageMonths = 14,
                weightKg = 45.5,
                price = 28000.0,
                discountPercentage = 5.0,
                purpose = GoatPurpose.BREEDING,
                description = "Supreme quality pure Boer buck, fully vaccinated, excellent growth rate.",
                imageUrls = listOf("https://images.unsplash.com/photo-1524024973431-2ad916746881?auto=format&fit=crop&q=80&w=600"),
                isAvailable = true,
                isFeatured = true
            ),
            Goat(
                id = "2",
                goatCode = "AS-TL-002",
                farmName = "Cauvery Valley Farm",
                farmLocation = "Erode, Tamil Nadu",
                name = "Thalachery Breeding Doe",
                breed = "Thalachery",
                ageMonths = 12,
                weightKg = 32.0,
                price = 18500.0,
                discountPercentage = 0.0,
                purpose = GoatPurpose.DAIRY,
                description = "Healthy Thalachery milking doe with high prolificacy.",
                imageUrls = listOf("https://images.unsplash.com/photo-1535083783855-76ae62b2914e?auto=format&fit=crop&q=80&w=600"),
                isAvailable = true,
                isFeatured = false
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Adu Santhai", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        Text("Tamil Nadu Livestock Marketplace", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToWishlist) {
                        Icon(Icons.Outlined.FavoriteBorder, contentDescription = "Wishlist")
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Profile")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Market") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.BookmarkBorder, contentDescription = "Bookings") },
                    label = { Text("Bookings") },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        onNavigateToBookings()
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.FavoriteBorder, contentDescription = "Wishlist") },
                    label = { Text("Wishlist") },
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        onNavigateToWishlist()
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = "Profile") },
                    label = { Text("Profile") },
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                        onNavigateToProfile()
                    }
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by breed, farm, or name...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            // Purpose Filter Chips
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(
                        selected = selectedPurpose == null,
                        onClick = { selectedPurpose = null },
                        label = { Text("All Goats") }
                    )
                }
                items(GoatPurpose.values()) { purpose ->
                    FilterChip(
                        selected = selectedPurpose == purpose,
                        onClick = { selectedPurpose = purpose },
                        label = { Text(purpose.name) }
                    )
                }
            }

            // Goat Listings
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                val filtered = sampleGoats.filter { goat ->
                    val matchesQuery = searchQuery.isBlank() ||
                            goat.name.contains(searchQuery, ignoreCase = true) ||
                            goat.breed.contains(searchQuery, ignoreCase = true) ||
                            goat.farmName.contains(searchQuery, ignoreCase = true)
                    val matchesPurpose = selectedPurpose == null || goat.purpose == selectedPurpose
                    matchesQuery && matchesPurpose
                }

                if (filtered.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No livestock listings found matching your criteria.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filtered) { goat ->
                        Card(
                            onClick = { onNavigateToGoatDetail(goat.id) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = goat.imageUrls.firstOrNull(),
                                    contentDescription = goat.name,
                                    modifier = Modifier
                                        .size(100.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentScale = ContentScale.Crop
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(goat.breed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                            if (goat.hasDiscount) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = MaterialTheme.colorScheme.errorContainer
                                                ) {
                                                    Text(
                                                        text = goat.formattedDiscountBadge,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(goat.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(goat.farmName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("${goat.ageMonths} mos • ${goat.weightKg} kg", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(goat.formattedPrice, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
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
