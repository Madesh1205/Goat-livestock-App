package com.ammalfarm.adusanthai.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.core.util.PriceUtils
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoatFilterBottomSheet(
    currentCriteria: GoatFilterCriteria,
    availableFarms: List<Farm>,
    totalMatchingCount: Int,
    onApplyFilters: (GoatFilterCriteria) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Draft criteria state inside bottom sheet
    var draftCriteria by remember(currentCriteria) { mutableStateOf(currentCriteria) }

    val popularBreeds = remember {
        listOf(
            "Boer",
            "Tellicherry (Malabari)",
            "Jamunapari",
            "Sirohi",
            "Salem Black",
            "Barbari",
            "Beetal",
            "Osmanabadi",
            "Kanni Aadu"
        )
    }

    val locations = remember {
        com.ammalfarm.adusanthai.core.util.FarmLocations.POPULAR_LOCATIONS
    }

    val currencyFormatter = remember {
        NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
            maximumFractionDigits = 0
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Filter",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Filter & Sort Goats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                TextButton(
                    onClick = {
                        draftCriteria = GoatFilterCriteria(
                            searchQuery = draftCriteria.searchQuery,
                            sortBy = SortOption.RELEVANCE
                        )
                    }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear All", fontWeight = FontWeight.SemiBold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // Scrollable Content
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                // --- 1. SORTING ---
                item {
                    FilterSectionHeader(
                        title = "Sort By",
                        icon = Icons.Outlined.Sort,
                        selectedSubtitle = draftCriteria.sortBy.displayName
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SortOption.values().forEach { option ->
                            val isSelected = draftCriteria.sortBy == option
                            FilterChip(
                                selected = isSelected,
                                onClick = { draftCriteria = draftCriteria.copy(sortBy = option) },
                                label = { Text(option.displayName, fontSize = 13.sp) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                // --- 2. BREED FILTER ---
                item {
                    FilterSectionHeader(
                        title = "Breed",
                        icon = Icons.Outlined.Pets,
                        selectedSubtitle = draftCriteria.breed ?: "All Breeds"
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // All Breeds pill
                        FilterChip(
                            selected = draftCriteria.breed == null,
                            onClick = { draftCriteria = draftCriteria.copy(breed = null) },
                            label = { Text("All Breeds", fontSize = 13.sp) }
                        )

                        popularBreeds.forEach { breedName ->
                            val isSelected = draftCriteria.breed == breedName
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = draftCriteria.copy(
                                        breed = if (isSelected) null else breedName
                                    )
                                },
                                label = { Text(breedName, fontSize = 13.sp) },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                } else null,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }

                // --- 3. GENDER ---
                item {
                    FilterSectionHeader(
                        title = "Gender",
                        icon = Icons.Outlined.Transgender,
                        selectedSubtitle = when (draftCriteria.gender) {
                            GoatGender.MALE -> "Male (Buck / He-goat)"
                            GoatGender.FEMALE -> "Female (Doe / She-goat)"
                            null -> "Any Gender"
                        }
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Any
                        OutlinedCard(
                            onClick = { draftCriteria = draftCriteria.copy(gender = null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (draftCriteria.gender == null)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (draftCriteria.gender == null) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "All",
                                    fontWeight = if (draftCriteria.gender == null) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "Male & Female",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Male
                        OutlinedCard(
                            onClick = { draftCriteria = draftCriteria.copy(gender = GoatGender.MALE) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (draftCriteria.gender == GoatGender.MALE)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (draftCriteria.gender == GoatGender.MALE) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Male,
                                        contentDescription = "Male",
                                        tint = Color(0xFF1976D2),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Male",
                                        fontWeight = if (draftCriteria.gender == GoatGender.MALE) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                }
                                Text(
                                    text = "Buck / Stud",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Female
                        OutlinedCard(
                            onClick = { draftCriteria = draftCriteria.copy(gender = GoatGender.FEMALE) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.outlinedCardColors(
                                containerColor = if (draftCriteria.gender == GoatGender.FEMALE)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (draftCriteria.gender == GoatGender.FEMALE) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Outlined.Female,
                                        contentDescription = "Female",
                                        tint = Color(0xFFD81B60),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Female",
                                        fontWeight = if (draftCriteria.gender == GoatGender.FEMALE) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp
                                    )
                                }
                                Text(
                                    text = "Doe / Milker",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // --- 4. AGE RANGE ---
                item {
                    val currentMinAge = draftCriteria.minAgeMonths ?: 0
                    val currentMaxAge = draftCriteria.maxAgeMonths ?: 60
                    var sliderRange by remember(currentMinAge, currentMaxAge) {
                        mutableStateOf(currentMinAge.toFloat()..currentMaxAge.toFloat())
                    }

                    FilterSectionHeader(
                        title = "Age Range",
                        icon = Icons.Outlined.CalendarMonth,
                        selectedSubtitle = if (draftCriteria.minAgeMonths != null || draftCriteria.maxAgeMonths != null) {
                            formatAgeDisplay(draftCriteria.minAgeMonths ?: 0, draftCriteria.maxAgeMonths ?: 60)
                        } else "Any Age (0 - 5+ yrs)"
                    )

                    // Quick presets
                    val agePresets = listOf(
                        Triple("Kids (< 6 mo)", 0, 6),
                        Triple("6 - 12 months", 6, 12),
                        Triple("1 - 2 years", 12, 24),
                        Triple("2 - 3 years", 24, 36),
                        Triple("3+ years", 36, 72)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(agePresets) { (label, minM, maxM) ->
                            val isSelected = draftCriteria.minAgeMonths == minM && draftCriteria.maxAgeMonths == maxM
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = if (isSelected) {
                                        draftCriteria.copy(minAgeMonths = null, maxAgeMonths = null)
                                    } else {
                                        draftCriteria.copy(minAgeMonths = minM, maxAgeMonths = maxM)
                                    }
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Range Slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${sliderRange.start.toInt()} mo (${(sliderRange.start / 12f).format1Dec()} yr)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${sliderRange.endInclusive.toInt()} mo (${(sliderRange.endInclusive / 12f).format1Dec()} yr)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        RangeSlider(
                            value = sliderRange,
                            onValueChange = { range ->
                                sliderRange = range
                                draftCriteria = draftCriteria.copy(
                                    minAgeMonths = if (range.start.toInt() <= 0) null else range.start.toInt(),
                                    maxAgeMonths = if (range.endInclusive.toInt() >= 60) null else range.endInclusive.toInt()
                                )
                            },
                            valueRange = 0f..60f,
                            steps = 11,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // --- 5. WEIGHT RANGE ---
                item {
                    val currentMinWeight = draftCriteria.minWeightKg ?: 10.0
                    val currentMaxWeight = draftCriteria.maxWeightKg ?: 120.0
                    var weightSliderRange by remember(currentMinWeight, currentMaxWeight) {
                        mutableStateOf(currentMinWeight.toFloat()..currentMaxWeight.toFloat())
                    }

                    FilterSectionHeader(
                        title = "Weight Range",
                        icon = Icons.Outlined.Scale,
                        selectedSubtitle = if (draftCriteria.minWeightKg != null || draftCriteria.maxWeightKg != null) {
                            "${(draftCriteria.minWeightKg ?: 10.0).toInt()} kg - ${(draftCriteria.maxWeightKg ?: 120.0).toInt()} kg"
                        } else "Any Weight (10 - 120+ kg)"
                    )

                    // Quick weight presets
                    val weightPresets = listOf(
                        Triple("10 - 25 kg", 10.0, 25.0),
                        Triple("25 - 45 kg", 25.0, 45.0),
                        Triple("30 - 50 kg", 30.0, 50.0),
                        Triple("50 - 75 kg", 50.0, 75.0),
                        Triple("75+ kg (Heavy)", 75.0, 130.0)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(weightPresets) { (label, minW, maxW) ->
                            val isSelected = draftCriteria.minWeightKg == minW && draftCriteria.maxWeightKg == maxW
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = if (isSelected) {
                                        draftCriteria.copy(minWeightKg = null, maxWeightKg = null)
                                    } else {
                                        draftCriteria.copy(minWeightKg = minW, maxWeightKg = maxW)
                                    }
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Weight Range Slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${weightSliderRange.start.toInt()} kg",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${weightSliderRange.endInclusive.toInt()} kg",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        RangeSlider(
                            value = weightSliderRange,
                            onValueChange = { range ->
                                weightSliderRange = range
                                draftCriteria = draftCriteria.copy(
                                    minWeightKg = if (range.start <= 10f) null else range.start.toDouble(),
                                    maxWeightKg = if (range.endInclusive >= 120f) null else range.endInclusive.toDouble()
                                )
                            },
                            valueRange = 10f..120f,
                            steps = 21,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // --- 6. PRICE RANGE ---
                item {
                    val currentMinPrice = draftCriteria.minPrice ?: 5000.0
                    val currentMaxPrice = draftCriteria.maxPrice ?: 100000.0
                    var priceSliderRange by remember(currentMinPrice, currentMaxPrice) {
                        mutableStateOf(currentMinPrice.toFloat()..currentMaxPrice.toFloat())
                    }

                    FilterSectionHeader(
                        title = "Price Range (₹)",
                        icon = Icons.Outlined.CurrencyRupee,
                        selectedSubtitle = if (draftCriteria.minPrice != null || draftCriteria.maxPrice != null) {
                            "${PriceUtils.formatCurrency(draftCriteria.minPrice ?: 5000.0)} - ${PriceUtils.formatCurrency(draftCriteria.maxPrice ?: 100000.0)}"
                        } else "Any Budget (₹5K - ₹1L+)"
                    )

                    // Quick price presets
                    val pricePresets = listOf(
                        Triple("< ₹15,000", 0.0, 15000.0),
                        Triple("₹15K - ₹25K", 15000.0, 25000.0),
                        Triple("₹25K - ₹40K", 25000.0, 40000.0),
                        Triple("₹40K - ₹60K", 40000.0, 60000.0),
                        Triple("₹60K+", 60000.0, 150000.0)
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(pricePresets) { (label, minP, maxP) ->
                            val isSelected = draftCriteria.minPrice == minP && draftCriteria.maxPrice == maxP
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = if (isSelected) {
                                        draftCriteria.copy(minPrice = null, maxPrice = null)
                                    } else {
                                        draftCriteria.copy(minPrice = minP, maxPrice = maxP)
                                    }
                                },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    // Price Range Slider
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = PriceUtils.formatCurrency(priceSliderRange.start.toDouble()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = PriceUtils.formatCurrency(priceSliderRange.endInclusive.toDouble()),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        RangeSlider(
                            value = priceSliderRange,
                            onValueChange = { range ->
                                priceSliderRange = range
                                draftCriteria = draftCriteria.copy(
                                    minPrice = if (range.start <= 5000f) null else range.start.toDouble(),
                                    maxPrice = if (range.endInclusive >= 100000f) null else range.endInclusive.toDouble()
                                )
                            },
                            valueRange = 5000f..100000f,
                            steps = 18,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // --- 7. VERIFIED FARM PARTNER ---
                item {
                    FilterSectionHeader(
                        title = "Farm / Breeder",
                        icon = Icons.Outlined.Agriculture,
                        selectedSubtitle = draftCriteria.farmName ?: "All Verified Farms"
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = draftCriteria.farmId == null && draftCriteria.farmName == null,
                            onClick = {
                                draftCriteria = draftCriteria.copy(farmId = null, farmName = null)
                            },
                            label = { Text("All Farms", fontSize = 13.sp) }
                        )

                        availableFarms.forEach { farm ->
                            val isSelected = draftCriteria.farmId == farm.id || draftCriteria.farmName == farm.name
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = if (isSelected) {
                                        draftCriteria.copy(farmId = null, farmName = null)
                                    } else {
                                        draftCriteria.copy(farmId = farm.id, farmName = farm.name)
                                    }
                                },
                                label = { Text(farm.name, fontSize = 13.sp) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.Verified,
                                        contentDescription = "Verified",
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF2E7D32),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                // --- 9. LOCATION ---
                item {
                    FilterSectionHeader(
                        title = "Location (Tamil Nadu)",
                        icon = Icons.Outlined.LocationOn,
                        selectedSubtitle = draftCriteria.location ?: "All Districts"
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = draftCriteria.location.isNullOrBlank(),
                            onClick = { draftCriteria = draftCriteria.copy(location = null) },
                            label = { Text("All Districts", fontSize = 13.sp) }
                        )

                        locations.forEach { loc ->
                            val isSelected = draftCriteria.location == loc
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    draftCriteria = draftCriteria.copy(
                                        location = if (isSelected) null else loc
                                    )
                                },
                                label = { Text(loc, fontSize = 13.sp) }
                            )
                        }
                    }
                }

                // --- 10. AVAILABILITY STATUS ---
                item {
                    FilterSectionHeader(
                        title = "Availability",
                        icon = Icons.Outlined.Inventory2,
                        selectedSubtitle = when (draftCriteria.availability) {
                            AvailabilityStatus.AVAILABLE -> "Available Now"
                            AvailabilityStatus.RESERVED -> "Reserved"
                            AvailabilityStatus.SOLD -> "Sold Out"
                            null -> "Available (Default)"
                            else -> draftCriteria.availability?.name
                        }
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = draftCriteria.availability == null || draftCriteria.availability == AvailabilityStatus.AVAILABLE,
                            onClick = { draftCriteria = draftCriteria.copy(availability = AvailabilityStatus.AVAILABLE) },
                            label = { Text("Available", fontSize = 13.sp) }
                        )

                        FilterChip(
                            selected = draftCriteria.availability == AvailabilityStatus.RESERVED,
                            onClick = { draftCriteria = draftCriteria.copy(availability = AvailabilityStatus.RESERVED) },
                            label = { Text("Reserved", fontSize = 13.sp) }
                        )

                        FilterChip(
                            selected = draftCriteria.availability == AvailabilityStatus.SOLD,
                            onClick = { draftCriteria = draftCriteria.copy(availability = AvailabilityStatus.SOLD) },
                            label = { Text("Sold", fontSize = 13.sp) }
                        )
                    }
                }
            }

            // Bottom Action Bar (Apply & Summary)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reset
                    OutlinedButton(
                        onClick = {
                            draftCriteria = GoatFilterCriteria(
                                searchQuery = draftCriteria.searchQuery,
                                sortBy = SortOption.RELEVANCE
                            )
                        },
                        modifier = Modifier.weight(0.35f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Text("Reset", fontWeight = FontWeight.SemiBold)
                    }

                    // Apply
                    Button(
                        onClick = {
                            onApplyFilters(draftCriteria)
                            onDismiss()
                        },
                        modifier = Modifier.weight(0.65f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (totalMatchingCount > 0) "Apply Filters ($totalMatchingCount Goats)" else "Apply Filters",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterSectionHeader(
    title: String,
    icon: ImageVector,
    selectedSubtitle: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (!selectedSubtitle.isNullOrBlank()) {
            Text(
                text = selectedSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatAgeDisplay(minM: Int, maxM: Int): String {
    val minStr = if (minM < 12) "$minM mo" else "${(minM / 12f).format1Dec()} yr"
    val maxStr = if (maxM < 12) "$maxM mo" else "${(maxM / 12f).format1Dec()} yr"
    return "$minStr - $maxStr"
}

private fun Float.format1Dec(): String {
    return if (this % 1.0f == 0.0f) {
        "${this.toInt()}"
    } else {
        String.format(Locale.US, "%.1f", this)
    }
}
