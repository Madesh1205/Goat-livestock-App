package com.ammalfarm.adusanthai.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ammalfarm.adusanthai.ui.viewmodel.SortOption

@Composable
fun ActiveFilterChips(
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
    sortOption: SortOption = SortOption.NEWEST,
    onRemoveBreed: () -> Unit = {},
    onRemoveGender: () -> Unit = {},
    onRemoveDistrict: () -> Unit = {},
    onRemoveFarm: () -> Unit = {},
    onRemovePrice: () -> Unit = {},
    onRemoveAge: () -> Unit = {},
    onRemoveWeight: () -> Unit = {},
    onRemoveAvailable: () -> Unit = {},
    onClearAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasPrice = minPriceInput.isNotBlank() || maxPriceInput.isNotBlank()
    val hasAge = minAgeInput.isNotBlank() || maxAgeInput.isNotBlank()
    val hasWeight = minWeightInput.isNotBlank() || maxWeightInput.isNotBlank()
    val hasAvailability = onlyAvailable || selectedAvailability != "All"

    val hasActiveFilters = selectedBreed != "All" ||
            selectedGender != "All" ||
            selectedDistrict != "All" ||
            selectedFarm != "All" ||
            hasPrice ||
            hasAge ||
            hasWeight ||
            hasAvailability ||
            sortOption != SortOption.NEWEST

    if (!hasActiveFilters) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedBreed != "All") {
            InputChip(
                selected = true,
                onClick = onRemoveBreed,
                label = { Text("Breed: $selectedBreed") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (selectedGender != "All") {
            InputChip(
                selected = true,
                onClick = onRemoveGender,
                label = { Text("Gender: $selectedGender") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (selectedDistrict != "All") {
            InputChip(
                selected = true,
                onClick = onRemoveDistrict,
                label = { Text("Location: $selectedDistrict") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (selectedFarm != "All") {
            InputChip(
                selected = true,
                onClick = onRemoveFarm,
                label = { Text("Farm: $selectedFarm") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (hasPrice) {
            val label = when {
                minPriceInput.isNotBlank() && maxPriceInput.isNotBlank() -> "₹$minPriceInput–₹$maxPriceInput"
                minPriceInput.isNotBlank() -> "Min ₹$minPriceInput"
                else -> "Max ₹$maxPriceInput"
            }
            InputChip(
                selected = true,
                onClick = onRemovePrice,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (hasAge) {
            val label = when {
                minAgeInput.isNotBlank() && maxAgeInput.isNotBlank() -> "${minAgeInput}–${maxAgeInput} mos"
                minAgeInput.isNotBlank() -> "${minAgeInput}+ mos"
                else -> "≤${maxAgeInput} mos"
            }
            InputChip(
                selected = true,
                onClick = onRemoveAge,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (hasWeight) {
            val label = when {
                minWeightInput.isNotBlank() && maxWeightInput.isNotBlank() -> "${minWeightInput}–${maxWeightInput} kg"
                minWeightInput.isNotBlank() -> "${minWeightInput}+ kg"
                else -> "≤${maxWeightInput} kg"
            }
            InputChip(
                selected = true,
                onClick = onRemoveWeight,
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (hasAvailability) {
            val statusLabel = if (selectedAvailability != "All") selectedAvailability else "Available Only"
            InputChip(
                selected = true,
                onClick = onRemoveAvailable,
                label = { Text("Status: $statusLabel") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        TextButton(onClick = onClearAll) {
            Text("Clear All")
        }
    }
}

