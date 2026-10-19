package com.ammalfarm.adusanthai.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Goat

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoatCard(
    goat: Goat,
    onClick: () -> Unit,
    isWishlisted: Boolean = false,
    onToggleWishlist: (() -> Unit)? = null,
    farmLogoUrl: String = "",
    isCompactGrid: Boolean = false,
    modifier: Modifier = Modifier
) {
    val primaryImage = goat.imageUrl.ifBlank {
        goat.imageUrls.firstOrNull { it.isNotBlank() }.orEmpty()
    }
    val photoCount = (listOf(goat.imageUrl) + goat.imageUrls)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .size

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("goat_item_card_${goat.id}"),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Image & Badges Section with responsive aspect ratio
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isCompactGrid) Modifier.aspectRatio(1.15f)
                        else Modifier.height(175.dp)
                    )
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                if (primaryImage.isNotBlank()) {
                    AsyncImage(
                        model = primaryImage,
                        contentDescription = "${goat.name} - ${goat.breed}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = "Goat Placeholder",
                                modifier = Modifier.size(if (isCompactGrid) 36.dp else 48.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = goat.breed.ifBlank { "Livestock" },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Top Row: Availability Badge & Wishlist Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(if (isCompactGrid) 8.dp else 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    val (badgeColor, badgeText) = when (goat.availabilityStatus) {
                        AvailabilityStatus.AVAILABLE -> Color(0xFF1B5E20) to "AVAILABLE"
                        AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.PENDING, AvailabilityStatus.CONFIRMED, AvailabilityStatus.APPROVED -> Color(0xFFE65100) to "RESERVED"
                        AvailabilityStatus.SOLD, AvailabilityStatus.COMPLETED -> Color(0xFFB71C1C) to "SOLD"
                        else -> Color(0xFF616161) to "UNAVAILABLE"
                    }

                    Surface(
                        color = badgeColor.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = badgeText,
                            color = Color.White,
                            fontSize = if (isCompactGrid) 10.sp else 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(
                                horizontal = if (isCompactGrid) 6.dp else 8.dp,
                                vertical = 3.dp
                            )
                        )
                    }

                    if (onToggleWishlist != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shadowElevation = 2.dp,
                            modifier = Modifier.size(36.dp)
                        ) {
                            IconButton(
                                onClick = onToggleWishlist,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag("wishlist_toggle_${goat.id}")
                            ) {
                                Icon(
                                    imageVector = if (isWishlisted) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = if (isWishlisted) "Remove from Wishlist" else "Add to Wishlist",
                                    tint = if (isWishlisted) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // Discount Badge at Bottom Left of Image
                if (goat.discountPercentage > 0.0) {
                    Surface(
                        color = Color(0xFFD32F2F),
                        shape = RoundedCornerShape(topEnd = 10.dp),
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        Text(
                            text = "${goat.discountPercentage.toInt()}% OFF",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }

                // Verified / Multi-Photo Badge at Bottom Right of Image
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (photoCount > 1) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(topStart = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PhotoLibrary,
                                    contentDescription = "$photoCount photos",
                                    tint = Color.White,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = "$photoCount",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    if (goat.isVerified) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(topStart = if (photoCount > 1) 0.dp else 10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "Verified Goat",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (!isCompactGrid) {
                                    Text(
                                        text = "Verified",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Content Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (isCompactGrid) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isCompactGrid) {
                    // Stacked title + price for 2-column phone grid so neither clips
                    Text(
                        text = goat.name.ifBlank { "${goat.breed} Goat" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = goat.breed.ifBlank { "Pure Breed" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = PriceUtils.formatINR(goat.effectivePrice),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        if (goat.discountPercentage > 0.0 && goat.effectivePrice < goat.price) {
                            Text(
                                text = PriceUtils.formatINR(goat.price),
                                style = MaterialTheme.typography.labelSmall,
                                textDecoration = TextDecoration.LineThrough,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = goat.name.ifBlank { "${goat.breed} Goat" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = goat.breed.ifBlank { "Pure Breed" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Price Column
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = PriceUtils.formatINR(goat.effectivePrice),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (goat.discountPercentage > 0.0 && goat.effectivePrice < goat.price) {
                                Text(
                                    text = PriceUtils.formatINR(goat.price),
                                    style = MaterialTheme.typography.labelSmall,
                                    textDecoration = TextDecoration.LineThrough,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Specs Pills: Gender, Age, Weight only (Tag ID, Vaccination, Coat Color, Purpose removed per Requirement 5)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpecChip(text = goat.gender.ifBlank { "Male" })
                    SpecChip(text = "${goat.ageMonths}m")
                    if (goat.weightKg > 0.0) {
                        SpecChip(text = "${goat.weightKg}kg")
                    }
                }

                // Farm Info Row
                if (goat.farmName.isNotBlank() || goat.farmLocation.isNotBlank()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (farmLogoUrl.isNotBlank()) {
                            AsyncImage(
                                model = farmLogoUrl,
                                contentDescription = goat.farmName,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Agriculture,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = buildString {
                                append(goat.farmName.ifBlank { "Verified Farm" })
                                if (goat.farmLocation.isNotBlank()) {
                                    append(" • ")
                                    append(goat.farmLocation)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecChip(
    text: String,
    isHighlight: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (isHighlight) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(
            0.5.dp,
            if (isHighlight) MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (isHighlight) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

