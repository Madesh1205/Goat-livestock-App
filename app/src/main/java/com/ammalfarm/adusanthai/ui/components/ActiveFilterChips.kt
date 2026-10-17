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
    selectedPurpose: String = "All",
    selectedDistrict: String = "All",
    onlyAvailable: Boolean = false,
    sortOption: SortOption = SortOption.NEWEST,
    onRemoveBreed: () -> Unit = {},
    onRemoveGender: () -> Unit = {},
    onRemovePurpose: () -> Unit = {},
    onRemoveDistrict: () -> Unit = {},
    onRemoveAvailable: () -> Unit = {},
    onClearAll: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val hasActiveFilters = selectedBreed != "All" ||
            selectedGender != "All" ||
            selectedPurpose != "All" ||
            selectedDistrict != "All" ||
            onlyAvailable ||
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
        if (selectedPurpose != "All") {
            InputChip(
                selected = true,
                onClick = onRemovePurpose,
                label = { Text("Purpose: $selectedPurpose") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (selectedDistrict != "All") {
            InputChip(
                selected = true,
                onClick = onRemoveDistrict,
                label = { Text("District: $selectedDistrict") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        if (onlyAvailable) {
            InputChip(
                selected = true,
                onClick = onRemoveAvailable,
                label = { Text("Available Only") },
                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) }
            )
        }
        TextButton(onClick = onClearAll) {
            Text("Clear All")
        }
    }
}
