package com.ammalfarm.adusanthai.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ammalfarm.adusanthai.core.util.FarmLocations
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.*

enum class AmazonFilterCategory(
    val title: String,
    val icon: ImageVector
) {
    SUGGESTED("Suggested Filters", Icons.Outlined.AutoAwesome),
    LOCATION("Farm Location", Icons.Outlined.LocationOn),
    BREED("Breed", Icons.Outlined.Pets),
    GOAT_SPECS("Goat Specs", Icons.Outlined.Tune),
    PRICE("Price and Deals", Icons.Outlined.CurrencyRupee),
    AVAILABILITY("Availability", Icons.Outlined.Inventory2),
    REVIEWS("Customer Reviews", Icons.Outlined.StarRate),
    SORTING_NEW("Sorting & New Arrivals", Icons.Outlined.Sort)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoatFilterBottomSheet(
    currentCriteria: GoatFilterCriteria,
    availableFarms: List<Farm>,
    allGoats: List<Goat> = emptyList(),
    totalMatchingCount: Int = 0,
    onApplyFilters: (GoatFilterCriteria) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var draftCriteria by remember(currentCriteria) { mutableStateOf(currentCriteria) }
    var selectedCategory by remember { mutableStateOf(AmazonFilterCategory.SUGGESTED) }
    var filterSearchQuery by remember { mutableStateOf("") }

    // Dynamic breed list combining available breeds from farms/goats and popular defaults
    val popularBreeds = remember(allGoats) {
        val goatBreeds = allGoats.map { it.breed.trim() }.filter { it.isNotBlank() }
        val defaults = listOf(
            "Boer", "Tellicherry (Malabari)", "Jamunapari", "Sirohi",
            "Salem Black", "Barbari", "Beetal", "Osmanabadi", "Kanni Aadu"
        )
        (goatBreeds + defaults).distinct().sorted()
    }

    // Dynamic district list sorted alphabetically
    val districtOptions = remember(availableFarms, allGoats) {
        val farmLocs = availableFarms.map { it.location.trim() }
        val goatLocs = allGoats.map { it.farmLocation.trim() }
        val rawList = (farmLocs + goatLocs + FarmLocations.TAMIL_NADU_DISTRICTS).filter { it.isNotBlank() }
        val distinct = mutableSetOf<String>()
        rawList.forEach { loc ->
            loc.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { distinct.add(it) }
        }
        distinct.sorted()
    }

    // Dynamic count matching current draft criteria
    val matchingGoatCount = remember(draftCriteria, allGoats, totalMatchingCount) {
        if (allGoats.isNotEmpty()) {
            allGoats.count { draftCriteria.matches(it) }
        } else {
            totalMatchingCount
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f)
        ) {
            // --- A. FIXED HEADER ---
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Filters",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("filter_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Filters",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // --- B. SEARCH FILTERS BAR ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = filterSearchQuery,
                    onValueChange = { filterSearchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search filters and options",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (filterSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { filterSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear Search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("filter_search_input")
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            // --- C. TWO-PANEL SPLIT LAYOUT ---
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // --- LEFT PANEL: FILTER CATEGORIES (32% Width) ---
                val categories = AmazonFilterCategory.values().filter { category ->
                    if (filterSearchQuery.isBlank()) true
                    else {
                        val query = filterSearchQuery.trim().lowercase()
                        category.title.lowercase().contains(query) ||
                                categoryMatchesOption(category, query, popularBreeds, districtOptions)
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier
                        .weight(0.32f)
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(categories, key = { it.name }) { category ->
                            val isSelected = category == selectedCategory
                            val activeCount = getCategoryActiveCount(category, draftCriteria)

                            Surface(
                                onClick = { selectedCategory = category },
                                color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("filter_category_${category.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(IntrinsicSize.Min),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left Blue Accent Bar
                                    Box(
                                        modifier = Modifier
                                            .width(4.dp)
                                            .fillMaxHeight()
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                                            )
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = category.title,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )

                                        if (activeCount > 0) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Surface(
                                                shape = CircleShape,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = "$activeCount",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }

                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                // --- RIGHT PANEL: FILTER OPTIONS (68% Width) ---
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .weight(0.68f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        // Category Title Header
                        Text(
                            text = selectedCategory.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            when (selectedCategory) {
                                AmazonFilterCategory.SUGGESTED -> {
                                    item {
                                        Text(
                                            text = "Quick Shortcuts",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChipOption(
                                                label = "Boer Goats",
                                                selected = draftCriteria.breed == "Boer",
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(
                                                        breed = if (draftCriteria.breed == "Boer") null else "Boer"
                                                    )
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Under ₹25,000",
                                                selected = draftCriteria.maxPrice == 25000.0,
                                                onClick = {
                                                    draftCriteria = if (draftCriteria.maxPrice == 25000.0)
                                                        draftCriteria.copy(maxPrice = null)
                                                    else
                                                        draftCriteria.copy(minPrice = null, maxPrice = 25000.0)
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Tamil Nadu",
                                                selected = draftCriteria.location == "Tamil Nadu",
                                                onClick = {
                                                    draftCriteria = if (draftCriteria.location == "Tamil Nadu")
                                                        draftCriteria.copy(location = null)
                                                    else
                                                        draftCriteria.copy(location = "Tamil Nadu")
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Male Goats",
                                                selected = draftCriteria.gender == GoatGender.MALE,
                                                onClick = {
                                                    draftCriteria = if (draftCriteria.gender == GoatGender.MALE)
                                                        draftCriteria.copy(gender = null)
                                                    else
                                                        draftCriteria.copy(gender = GoatGender.MALE)
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Discounted Deals",
                                                selected = draftCriteria.onlyDeals,
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(onlyDeals = !draftCriteria.onlyDeals)
                                                }
                                            )
                                        }
                                    }
                                }

                                AmazonFilterCategory.LOCATION -> {
                                    item {
                                        Text("State", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            listOf("All States", "Tamil Nadu", "Kerala", "Karnataka", "Andhra Pradesh", "Puducherry").forEach { state ->
                                                val isSel = (state == "All States" && draftCriteria.location.isNullOrBlank()) ||
                                                        (draftCriteria.location == state)
                                                FilterChipOption(
                                                    label = state,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = draftCriteria.copy(
                                                            location = if (state == "All States") null else state
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Districts", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))

                                        val searchQ = filterSearchQuery.trim().lowercase()
                                        val filteredDistricts = districtOptions.filter {
                                            searchQ.isBlank() || it.lowercase().contains(searchQ)
                                        }

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            filteredDistricts.forEach { dist ->
                                                val isSel = draftCriteria.location?.equals(dist, ignoreCase = true) == true
                                                FilterChipOption(
                                                    label = dist,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = draftCriteria.copy(
                                                            location = if (isSel) null else dist
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                AmazonFilterCategory.BREED -> {
                                    item {
                                        val searchQ = filterSearchQuery.trim().lowercase()
                                        val filteredBreeds = popularBreeds.filter {
                                            searchQ.isBlank() || it.lowercase().contains(searchQ)
                                        }

                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChipOption(
                                                label = "All Breeds",
                                                selected = draftCriteria.breed == null,
                                                onClick = { draftCriteria = draftCriteria.copy(breed = null) }
                                            )
                                            filteredBreeds.forEach { breedName ->
                                                val isSel = draftCriteria.breed == breedName
                                                FilterChipOption(
                                                    label = breedName,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = draftCriteria.copy(
                                                            breed = if (isSel) null else breedName
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                AmazonFilterCategory.PRICE -> {
                                    item {
                                        Text("Quick Budget Ranges", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val pricePresets = listOf(
                                            Triple("< ₹15,000", null, 15000.0),
                                            Triple("₹15K - ₹25K", 15000.0, 25000.0),
                                            Triple("₹25K - ₹40K", 25000.0, 40000.0),
                                            Triple("₹40K - ₹60K", 40000.0, 60000.0),
                                            Triple("₹60K+", 60000.0, null)
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            pricePresets.forEach { (label, minP, maxP) ->
                                                val isSel = draftCriteria.minPrice == minP && draftCriteria.maxPrice == maxP
                                                FilterChipOption(
                                                    label = label,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = if (isSel)
                                                            draftCriteria.copy(minPrice = null, maxPrice = null)
                                                        else
                                                            draftCriteria.copy(minPrice = minP, maxPrice = maxP)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Custom Price (₹)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = draftCriteria.minPrice?.toInt()?.toString() ?: "",
                                                onValueChange = { input ->
                                                    val v = input.toDoubleOrNull()
                                                    draftCriteria = draftCriteria.copy(minPrice = v)
                                                },
                                                placeholder = { Text("Min ₹", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("-", fontWeight = FontWeight.Bold)
                                            OutlinedTextField(
                                                value = draftCriteria.maxPrice?.toInt()?.toString() ?: "",
                                                onValueChange = { input ->
                                                    val v = input.toDoubleOrNull()
                                                    draftCriteria = draftCriteria.copy(maxPrice = v)
                                                },
                                                placeholder = { Text("Max ₹", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        ToggleOptionRow(
                                            title = "Discounted Deals Only",
                                            subtitle = "Show items with price discounts",
                                            checked = draftCriteria.onlyDeals,
                                            onCheckedChange = {
                                                draftCriteria = draftCriteria.copy(onlyDeals = it)
                                            }
                                        )
                                    }
                                }

                                AmazonFilterCategory.GOAT_SPECS -> {
                                    item {
                                        Text("Gender", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChipOption(
                                                label = "All Genders",
                                                selected = draftCriteria.gender == null,
                                                onClick = { draftCriteria = draftCriteria.copy(gender = null) }
                                            )
                                            FilterChipOption(
                                                label = "Male (Buck)",
                                                selected = draftCriteria.gender == GoatGender.MALE,
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(
                                                        gender = if (draftCriteria.gender == GoatGender.MALE) null else GoatGender.MALE
                                                    )
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Female (Doe)",
                                                selected = draftCriteria.gender == GoatGender.FEMALE,
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(
                                                        gender = if (draftCriteria.gender == GoatGender.FEMALE) null else GoatGender.FEMALE
                                                    )
                                                }
                                            )
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Age Presets", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val agePresets = listOf(
                                            Triple("Kids (< 6 mo)", 0, 6),
                                            Triple("6 - 12 mo", 6, 12),
                                            Triple("1 - 2 yrs", 12, 24),
                                            Triple("2 - 3 yrs", 24, 36),
                                            Triple("3+ yrs", 36, null)
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            agePresets.forEach { (label, minA, maxA) ->
                                                val isSel = draftCriteria.minAgeMonths == minA && draftCriteria.maxAgeMonths == maxA
                                                FilterChipOption(
                                                    label = label,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = if (isSel)
                                                            draftCriteria.copy(minAgeMonths = null, maxAgeMonths = null)
                                                        else
                                                            draftCriteria.copy(minAgeMonths = minA, maxAgeMonths = maxA)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("Custom Age Range (Months)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = draftCriteria.minAgeMonths?.toString() ?: "",
                                                onValueChange = { input ->
                                                    draftCriteria = draftCriteria.copy(minAgeMonths = input.toIntOrNull())
                                                },
                                                placeholder = { Text("Min mo", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("-", fontWeight = FontWeight.Bold)
                                            OutlinedTextField(
                                                value = draftCriteria.maxAgeMonths?.toString() ?: "",
                                                onValueChange = { input ->
                                                    draftCriteria = draftCriteria.copy(maxAgeMonths = input.toIntOrNull())
                                                },
                                                placeholder = { Text("Max mo", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(14.dp))
                                        Text("Weight Presets (Kg)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val weightPresets = listOf(
                                            Triple("10 - 25 kg", 10.0, 25.0),
                                            Triple("25 - 45 kg", 25.0, 45.0),
                                            Triple("30 - 50 kg", 30.0, 50.0),
                                            Triple("50 - 75 kg", 50.0, 75.0),
                                            Triple("75+ kg", 75.0, null)
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            weightPresets.forEach { (label, minW, maxW) ->
                                                val isSel = draftCriteria.minWeightKg == minW && draftCriteria.maxWeightKg == maxW
                                                FilterChipOption(
                                                    label = label,
                                                    selected = isSel,
                                                    onClick = {
                                                        draftCriteria = if (isSel)
                                                            draftCriteria.copy(minWeightKg = null, maxWeightKg = null)
                                                        else
                                                            draftCriteria.copy(minWeightKg = minW, maxWeightKg = maxW)
                                                    }
                                                )
                                            }
                                        }
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text("Custom Weight Range (Kg)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = draftCriteria.minWeightKg?.toInt()?.toString() ?: "",
                                                onValueChange = { input ->
                                                    draftCriteria = draftCriteria.copy(minWeightKg = input.toDoubleOrNull())
                                                },
                                                placeholder = { Text("Min kg", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text("-", fontWeight = FontWeight.Bold)
                                            OutlinedTextField(
                                                value = draftCriteria.maxWeightKg?.toInt()?.toString() ?: "",
                                                onValueChange = { input ->
                                                    draftCriteria = draftCriteria.copy(maxWeightKg = input.toDoubleOrNull())
                                                },
                                                placeholder = { Text("Max kg", fontSize = 12.sp) },
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }

                                AmazonFilterCategory.AVAILABILITY -> {
                                    item {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilterChipOption(
                                                label = "Available",
                                                selected = draftCriteria.availability == null || draftCriteria.availability == AvailabilityStatus.AVAILABLE,
                                                onClick = { draftCriteria = draftCriteria.copy(availability = AvailabilityStatus.AVAILABLE) }
                                            )
                                            FilterChipOption(
                                                label = "Reserved",
                                                selected = draftCriteria.availability == AvailabilityStatus.RESERVED,
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(
                                                        availability = if (draftCriteria.availability == AvailabilityStatus.RESERVED) null else AvailabilityStatus.RESERVED
                                                    )
                                                }
                                            )
                                            FilterChipOption(
                                                label = "Sold",
                                                selected = draftCriteria.availability == AvailabilityStatus.SOLD,
                                                onClick = {
                                                    draftCriteria = draftCriteria.copy(
                                                        availability = if (draftCriteria.availability == AvailabilityStatus.SOLD) null else AvailabilityStatus.SOLD
                                                    )
                                                }
                                            )
                                        }
                                    }
                                }

                                AmazonFilterCategory.REVIEWS -> {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            listOf(
                                                Triple("4.0 Stars & Above", 4.0, "★★★★☆ & up"),
                                                Triple("3.0 Stars & Above", 3.0, "★★★☆☆ & up"),
                                                Triple("All Ratings", null, "All listings")
                                            ).forEach { (label, minR, sub) ->
                                                val isSel = draftCriteria.minRating == minR
                                                Surface(
                                                    onClick = {
                                                        draftCriteria = draftCriteria.copy(minRating = minR)
                                                    },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column {
                                                            Text(label, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, fontSize = 13.sp)
                                                            Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        }
                                                        if (isSel) {
                                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                AmazonFilterCategory.SORTING_NEW -> {
                                    item {
                                        ToggleOptionRow(
                                            title = "Recently Added (Last 30 Days)",
                                            subtitle = "Show newly listed goats on Adu Santhai",
                                            checked = draftCriteria.isNewArrival,
                                            onCheckedChange = {
                                                draftCriteria = draftCriteria.copy(isNewArrival = it)
                                            }
                                        )
                                    }

                                    item {
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Sort Order", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SortOption.values().forEach { option ->
                                                val isSel = draftCriteria.sortBy == option
                                                Surface(
                                                    onClick = { draftCriteria = draftCriteria.copy(sortBy = option) },
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                    border = BorderStroke(1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = option.displayName,
                                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                            fontSize = 13.sp
                                                        )
                                                        if (isSel) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = "Selected",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(18.dp)
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
                    }
                }
            }

            // --- 5. FIXED BOTTOM ACTION BAR ---
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            draftCriteria = GoatFilterCriteria(
                                searchQuery = draftCriteria.searchQuery,
                                location = draftCriteria.location
                            )
                        },
                        modifier = Modifier.testTag("filter_clear_all_button")
                    ) {
                        Text(
                            text = "Clear All",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Button(
                        onClick = {
                            onApplyFilters(draftCriteria)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFD814), // Amazon Signature Yellow
                            contentColor = Color(0xFF111111)
                        ),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                        modifier = Modifier.testTag("filter_show_results_button")
                    ) {
                        Text(
                            text = if (matchingGoatCount > 0) "Show $matchingGoatCount results" else "Show Results",
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
private fun FilterChipOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 4.dp)
                )
            }
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ToggleOptionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(10.dp),
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

private fun getCategoryActiveCount(category: AmazonFilterCategory, criteria: GoatFilterCriteria): Int {
    return when (category) {
        AmazonFilterCategory.SUGGESTED -> 0
        AmazonFilterCategory.LOCATION -> if (!criteria.location.isNullOrBlank()) 1 else 0
        AmazonFilterCategory.BREED -> if (criteria.breed != null) 1 else 0
        AmazonFilterCategory.GOAT_SPECS -> {
            var c = 0
            if (criteria.gender != null) c++
            if (criteria.minAgeMonths != null || criteria.maxAgeMonths != null) c++
            if (criteria.minWeightKg != null || criteria.maxWeightKg != null) c++
            c
        }
        AmazonFilterCategory.PRICE -> {
            var c = 0
            if (criteria.minPrice != null || criteria.maxPrice != null) c++
            if (criteria.onlyDeals) c++
            c
        }
        AmazonFilterCategory.AVAILABILITY -> if (criteria.availability != null) 1 else 0
        AmazonFilterCategory.REVIEWS -> if (criteria.minRating != null) 1 else 0
        AmazonFilterCategory.SORTING_NEW -> {
            var c = 0
            if (criteria.isNewArrival) c++
            if (criteria.sortBy != SortOption.RELEVANCE) c++
            c
        }
    }
}

private fun categoryMatchesOption(
    category: AmazonFilterCategory,
    query: String,
    breeds: List<String>,
    districts: List<String>
): Boolean {
    return when (category) {
        AmazonFilterCategory.BREED -> breeds.any { it.lowercase().contains(query) }
        AmazonFilterCategory.LOCATION -> districts.any { it.lowercase().contains(query) }
        AmazonFilterCategory.GOAT_SPECS -> listOf("gender", "male", "female", "age", "weight", "kg").any { it.contains(query) }
        AmazonFilterCategory.SORTING_NEW -> listOf("sort", "new", "arrival", "recent", "price").any { it.contains(query) }
        else -> false
    }
}
