package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.components.GoatCard
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmDetailScreen(
    farmId: String,
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateBack: () -> Unit,
    onNavigateToGoatDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val farm = remember(uiState?.farms, farmId) {
        uiState?.farms?.find { it.id == farmId }
    }
    val farmGoats = remember(uiState?.allGoats, farmId) {
        uiState?.allGoats?.filter {
            it.farmId == farmId &&
            it.approvalStatus == ApprovalStatus.APPROVED &&
            it.isActive &&
            it.availabilityStatus != AvailabilityStatus.UNAVAILABLE
        }.orEmpty()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(farm?.name ?: "Farm Profile", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val useTwoColumns = maxWidth >= 320.dp
            val chunkedGoats = remember(farmGoats, useTwoColumns) {
                if (useTwoColumns) farmGoats.chunked(2) else farmGoats.chunked(1)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Farm Banner Image
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(145.dp)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
                                            )
                                        )
                                    )
                            ) {
                                if (!farm?.bannerUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = farm!!.bannerUrl,
                                        contentDescription = "${farm.name} Banner",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Landscape,
                                            contentDescription = null,
                                            tint = Color.White.copy(alpha = 0.35f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                    }
                                }
                            }

                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
                                        shadowElevation = 2.dp,
                                        modifier = Modifier.size(60.dp)
                                    ) {
                                        if (!farm?.logoUrl.isNullOrBlank()) {
                                            AsyncImage(
                                                model = farm!!.logoUrl,
                                                contentDescription = "${farm.name} Logo",
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    Icons.Default.Agriculture,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(30.dp)
                                                )
                                            }
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = farm?.name ?: "Verified Partner Farm",
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            if (farm?.verificationStatus?.isApprovedOrVerified == true || farm?.isAmmalOwnFarm == true) {
                                                Icon(
                                                    Icons.Default.Verified,
                                                    contentDescription = "Verified Farm",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = buildString {
                                                append(farm?.location?.ifBlank { "Tamil Nadu" } ?: "Tamil Nadu")
                                                if (!farm?.district.isNullOrBlank() &&
                                                    farm?.location?.contains(farm.district, ignoreCase = true) == false
                                                ) {
                                                    append(" • ${farm.district}")
                                                }
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (!farm?.description.isNullOrBlank()) {
                                    Text(
                                        text = farm!!.description,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                 if (!farm?.contactNumber.isNullOrBlank()) {
                                     Button(
                                         onClick = {
                                             val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${farm!!.contactNumber}"))
                                             context.startActivity(intent)
                                         },
                                         modifier = Modifier.fillMaxWidth(),
                                         shape = RoundedCornerShape(10.dp)
                                     ) {
                                         Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                         Spacer(modifier = Modifier.width(6.dp))
                                         Text("Call Farm")
                                     }
                                 }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "Available Livestock (${farmGoats.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (farmGoats.isEmpty()) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                        ) {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text("No active goat listings available from this farm right now.")
                            }
                        }
                    }
                } else {
                    items(chunkedGoats, key = { row -> row.joinToString("_") { it.id } }) { rowGoats ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowGoats.forEach { goat ->
                                GoatCard(
                                    goat = goat.copy(
                                        farmName = goat.farmName.ifBlank { farm?.name.orEmpty() },
                                        farmLocation = goat.farmLocation.ifBlank { farm?.location.orEmpty() }
                                    ),
                                    onClick = { onNavigateToGoatDetail(goat.id) },
                                    isWishlisted = uiState?.wishlistedIds?.contains(goat.id) == true,
                                    onToggleWishlist = {
                                        if (currentUser != null && marketplaceViewModel != null) {
                                            marketplaceViewModel.toggleWishlist(currentUser.id, goat.id)
                                        }
                                    },
                                    farmLogoUrl = farm?.logoUrl.orEmpty(),
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
