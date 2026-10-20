package com.ammalfarm.adusanthai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ammalfarm.adusanthai.core.util.FarmLocations
import com.ammalfarm.adusanthai.ui.viewmodel.SortOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoatFilterBottomSheet(
    selectedBreed: String = "All",
    selectedGender: String = "All",
    selectedDistrict: String = "All",
    selectedFarm: String = "All",
    selectedAvailability: String = "All",
    minPriceInput: String = "",
    maxPriceInput: String = "",
    minAgeInput: String = "",
    maxAgeInput: String = "",
    minWeightInput: String = "",
    maxWeightInput: String = "",
    onlyAvailable: Boolean = false,
    selectedSort: SortOption = SortOption.NEWEST,
    availableBreeds: List<String> = listOf("All"),
    availableDistricts: List<String> = listOf("All"),
    availableFarms: List<String> = listOf("All"),
    onApply: (
        breed: String,
        gender: String,
        district: String,
        farm: String,
        availability: String,
        minPrice: String,
        maxPrice: String,
        minAge: String,
        maxAge: String,
        minWeight: String,
        maxWeight: String,
        onlyAvailable: Boolean,
        sort: SortOption
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onClear: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var breed by remember(selectedBreed) { mutableStateOf(selectedBreed) }
    var gender by remember(selectedGender) { mutableStateOf(selectedGender) }
    var district by remember(selectedDistrict) { mutableStateOf(selectedDistrict) }
    var farm by remember(selectedFarm) { mutableStateOf(selectedFarm) }
    var availability by remember(selectedAvailability) { mutableStateOf(selectedAvailability) }
    var minPrice by remember(minPriceInput) { mutableStateOf(minPriceInput) }
    var maxPrice by remember(maxPriceInput) { mutableStateOf(maxPriceInput) }
    var minAge by remember(minAgeInput) { mutableStateOf(minAgeInput) }
    var maxAge by remember(maxAgeInput) { mutableStateOf(maxAgeInput) }
    var minWeight by remember(minWeightInput) { mutableStateOf(minWeightInput) }
    var maxWeight by remember(maxWeightInput) { mutableStateOf(maxWeightInput) }
    var availableOnly by remember(onlyAvailable) { mutableStateOf(onlyAvailable) }
    var sort by remember(selectedSort) { mutableStateOf(selectedSort) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Filter & Sort Goats",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            breed = "All"
                            gender = "All"
                            district = "All"
                            farm = "All"
                            availability = "All"
                            minPrice = ""
                            maxPrice = ""
                            minAge = ""
                            maxAge = ""
                            minWeight = ""
                            maxWeight = ""
                            availableOnly = false
                            sort = SortOption.NEWEST
                            onClear()
                        },
                        modifier = Modifier.testTag("btn_reset_filters_sheet")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
            }

            HorizontalDivider()

            // Sort Options
            Text("Sort By", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SortOption.entries.forEach { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { sort = option },
                        label = { Text(option.label) }
                    )
                }
            }

            // Availability Status & Toggle
            Text("Availability", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Available Goats Only", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Hide reserved or sold listings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = availableOnly,
                    onCheckedChange = {
                        availableOnly = it
                        if (it) availability = "AVAILABLE"
                        else if (availability.equals("AVAILABLE", ignoreCase = true)) availability = "All"
                    }
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("All" to "All Statuses", "AVAILABLE" to "Available", "RESERVED" to "Reserved", "SOLD" to "Sold").forEach { (key, label) ->
                    FilterChip(
                        selected = availability.equals(key, ignoreCase = true),
                        onClick = {
                            availability = key
                            availableOnly = key.equals("AVAILABLE", ignoreCase = true)
                        },
                        label = { Text(label) }
                    )
                }
            }

            // Breed (Purpose filter removed per Requirement 5 & 8)
            Text("Breed", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableBreeds.forEach { item ->
                    FilterChip(
                        selected = breed.equals(item, ignoreCase = true),
                        onClick = { breed = item },
                        label = { Text(item) }
                    )
                }
            }

            // Gender
            Text("Gender", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Male", "Female").forEach { item ->
                    FilterChip(
                        selected = gender.equals(item, ignoreCase = true),
                        onClick = { gender = item },
                        label = { Text(item) }
                    )
                }
            }

            // Price Range (INR)
            Text("Price Range (₹)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = minPrice,
                    onValueChange = { minPrice = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Min Price (₹)") },
                    placeholder = { Text("e.g. 8000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxPrice,
                    onValueChange = { maxPrice = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Max Price (₹)") },
                    placeholder = { Text("e.g. 25000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Age Range (Months)
            Text("Age Range (Months)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = minAge,
                    onValueChange = { minAge = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Min Age (mos)") },
                    placeholder = { Text("e.g. 6") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxAge,
                    onValueChange = { maxAge = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Max Age (mos)") },
                    placeholder = { Text("e.g. 24") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Weight Range (Kg)
            Text("Weight Range (kg)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = minWeight,
                    onValueChange = { minWeight = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Min Weight (kg)") },
                    placeholder = { Text("e.g. 15") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = maxWeight,
                    onValueChange = { maxWeight = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    label = { Text("Max Weight (kg)") },
                    placeholder = { Text("e.g. 45") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }

            // Partner Farm Filter
            if (availableFarms.size > 1) {
                Text("Partner Farm", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableFarms.forEach { item ->
                        FilterChip(
                            selected = farm.equals(item, ignoreCase = true),
                            onClick = { farm = item },
                            label = { Text(item) }
                        )
                    }
                }
            }

            // Location / District Filter
            Text("Location / District", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableDistricts.forEach { item ->
                    FilterChip(
                        selected = district.equals(item, ignoreCase = true),
                        onClick = { district = item },
                        label = { Text(item) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onApply(
                        breed,
                        gender,
                        district,
                        farm,
                        availability,
                        minPrice,
                        maxPrice,
                        minAge,
                        maxAge,
                        minWeight,
                        maxWeight,
                        availableOnly,
                        sort
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("btn_apply_filters"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Apply Filters", fontWeight = FontWeight.Bold)
            }
        }
    }
}

