package com.ammalfarm.adusanthai.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ammalfarm.adusanthai.core.util.FarmLocations
import com.ammalfarm.adusanthai.ui.viewmodel.SortOption

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GoatFilterBottomSheet(
    selectedBreed: String = "All",
    selectedGender: String = "All",
    selectedPurpose: String = "All",
    selectedDistrict: String = "All",
    onlyAvailable: Boolean = false,
    selectedSort: SortOption = SortOption.NEWEST,
    availableBreeds: List<String> = listOf("All", "Tellicherry", "Boer", "Kanni Adu", "Kodi Adu", "Salem Black"),
    onApply: (breed: String, gender: String, purpose: String, district: String, onlyAvailable: Boolean, sort: SortOption) -> Unit = { _, _, _, _, _, _ -> },
    onClear: () -> Unit = {},
    onDismiss: () -> Unit
) {
    var breed by remember(selectedBreed) { mutableStateOf(selectedBreed) }
    var gender by remember(selectedGender) { mutableStateOf(selectedGender) }
    var purpose by remember(selectedPurpose) { mutableStateOf(selectedPurpose) }
    var district by remember(selectedDistrict) { mutableStateOf(selectedDistrict) }
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
                    TextButton(onClick = {
                        breed = "All"
                        gender = "All"
                        purpose = "All"
                        district = "All"
                        availableOnly = false
                        sort = SortOption.NEWEST
                        onClear()
                    }) {
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

            // Availability Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Available Goats Only", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Hide reserved or sold goats",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = availableOnly,
                    onCheckedChange = { availableOnly = it }
                )
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

            // Purpose
            Text("Purpose", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Breeding", "Meat", "Dairy", "Rear").forEach { item ->
                    FilterChip(
                        selected = purpose.equals(item, ignoreCase = true),
                        onClick = { purpose = item },
                        label = { Text(item) }
                    )
                }
            }

            // Breed
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

            // District
            Text("District", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (listOf("All") + FarmLocations.TAMIL_NADU_DISTRICTS).forEach { item ->
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
                    onApply(breed, gender, purpose, district, availableOnly, sort)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Apply Filters", fontWeight = FontWeight.Bold)
            }
        }
    }
}
