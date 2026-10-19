package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.components.GoatCard
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishlistScreen(
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateBack: () -> Unit,
    onNavigateToGoatDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val wishlistedGoats = remember(uiState?.allGoats, uiState?.wishlistedIds) {
        val ids = uiState?.wishlistedIds.orEmpty()
        uiState?.allGoats?.filter { ids.contains(it.id) }.orEmpty()
    }

    LaunchedEffect(currentUser?.id) {
        if (currentUser != null) {
            marketplaceViewModel?.loadUserSpecificData(currentUser)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Wishlist (${wishlistedGoats.size})", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (wishlistedGoats.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(76.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.FavoriteBorder,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Text(
                        text = "Your Wishlist is Empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Tap the heart icon on any goat listing in the marketplace to save it here for quick comparison.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onNavigateBack) {
                        Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Explore Marketplace")
                    }
                }
            }
        } else {
            val farmsById = remember(uiState?.farms) {
                uiState?.farms.orEmpty().associateBy { it.id }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val useTwoColumns = maxWidth >= 320.dp
                val chunkedGoats = remember(wishlistedGoats, useTwoColumns) {
                    if (useTwoColumns) wishlistedGoats.chunked(2) else wishlistedGoats.chunked(1)
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(chunkedGoats, key = { row -> row.joinToString("_") { it.id } }) { rowGoats ->
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
                                    isWishlisted = true,
                                    onToggleWishlist = {
                                        if (currentUser != null && marketplaceViewModel != null) {
                                            marketplaceViewModel.toggleWishlist(currentUser.id, goat.id)
                                        }
                                    },
                                    farmLogoUrl = linkedFarm?.logoUrl.orEmpty(),
                                    isCompactGrid = useTwoColumns,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (useTwoColumns && rowGoats.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
