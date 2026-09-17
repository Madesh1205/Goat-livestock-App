package com.example.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import java.util.Locale

data class ActiveFilterChipModel(
    val key: String,
    val label: String,
    val valueText: String,
    val onRemove: () -> Unit
)

@Composable
fun ActiveFilterChips(
    criteria: GoatFilterCriteria,
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
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chips = mutableListOf<ActiveFilterChipModel>()

    if (criteria.searchQuery.isNotBlank()) {
        chips.add(
            ActiveFilterChipModel(
                key = "search",
                label = "Search",
                valueText = "\"${criteria.searchQuery}\"",
                onRemove = onRemoveSearchQuery
            )
        )
    }

    if (criteria.breed != null) {
        chips.add(
            ActiveFilterChipModel(
                key = "breed",
                label = "Breed",
                valueText = criteria.breed,
                onRemove = onRemoveBreed
            )
        )
    }

    if (criteria.farmName != null || criteria.farmId != null) {
        chips.add(
            ActiveFilterChipModel(
                key = "farm",
                label = "Farm",
                valueText = criteria.farmName ?: "Partner Farm",
                onRemove = onRemoveFarm
            )
        )
    }

    if (criteria.gender != null) {
        chips.add(
            ActiveFilterChipModel(
                key = "gender",
                label = "Gender",
                valueText = if (criteria.gender == GoatGender.MALE) "Male (Buck)" else "Female (Doe)",
                onRemove = onRemoveGender
            )
        )
    }

    if (criteria.minAgeMonths != null || criteria.maxAgeMonths != null) {
        val minAge = criteria.minAgeMonths ?: 0
        val maxAge = criteria.maxAgeMonths ?: 60
        val text = if (maxAge >= 60) "${minAge}+ mo" else "$minAge - $maxAge mo"
        chips.add(
            ActiveFilterChipModel(
                key = "age",
                label = "Age",
                valueText = text,
                onRemove = onRemoveAge
            )
        )
    }

    if (criteria.minWeightKg != null || criteria.maxWeightKg != null) {
        val minW = (criteria.minWeightKg ?: 10.0).toInt()
        val maxW = (criteria.maxWeightKg ?: 120.0).toInt()
        chips.add(
            ActiveFilterChipModel(
                key = "weight",
                label = "Weight",
                valueText = "$minW - $maxW kg",
                onRemove = onRemoveWeight
            )
        )
    }

    if (criteria.minPrice != null || criteria.maxPrice != null) {
        val minP = (criteria.minPrice ?: 5000.0).toInt()
        val maxP = (criteria.maxPrice ?: 100000.0).toInt()
        chips.add(
            ActiveFilterChipModel(
                key = "price",
                label = "Price",
                valueText = "₹$minP - ₹$maxP",
                onRemove = onRemovePrice
            )
        )
    }

    if (criteria.purpose != null) {
        chips.add(
            ActiveFilterChipModel(
                key = "purpose",
                label = "Purpose",
                valueText = criteria.purpose.name.lowercase().replaceFirstChar { it.uppercase() },
                onRemove = onRemovePurpose
            )
        )
    }

    if (!criteria.location.isNullOrBlank()) {
        chips.add(
            ActiveFilterChipModel(
                key = "location",
                label = "Location",
                valueText = criteria.location,
                onRemove = onRemoveLocation
            )
        )
    }

    if (criteria.availability != null && criteria.availability != AvailabilityStatus.AVAILABLE) {
        chips.add(
            ActiveFilterChipModel(
                key = "availability",
                label = "Status",
                valueText = criteria.availability.name,
                onRemove = onRemoveAvailability
            )
        )
    }

    if (chips.isEmpty()) return

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(chips, key = { it.key }) { chip ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                ),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${chip.label}: ",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                    )
                    Text(
                        text = chip.valueText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                            .clickable { chip.onRemove() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove ${chip.label} filter",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        if (chips.size >= 2) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.clickable { onClearAll() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FilterAltOff,
                            contentDescription = "Clear All",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Clear All",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}
