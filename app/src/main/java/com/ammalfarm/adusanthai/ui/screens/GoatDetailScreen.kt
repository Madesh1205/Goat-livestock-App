package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.UserProfile
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoatDetailScreen(
    goatId: String,
    marketplaceViewModel: MarketplaceViewModel? = null,
    currentUser: UserProfile? = null,
    onNavigateBack: () -> Unit,
    onNavigateToFarmDetail: (String) -> Unit = {},
    onNavigateToBookings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState = marketplaceViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val goat = remember(uiState?.allGoats, goatId) {
        uiState?.allGoats?.find { it.id == goatId }
    }
    val farm = remember(uiState?.farms, goat?.farmId) {
        uiState?.farms?.find { it.id == goat?.farmId }
    }
    val isWishlisted = uiState?.wishlistedIds?.contains(goatId) == true

    var showBookingDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedImageIndex by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState?.actionSuccessMessage, uiState?.actionErrorMessage) {
        uiState?.actionSuccessMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
        uiState?.actionErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            marketplaceViewModel.clearActionMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(goat?.name?.ifBlank { "Goat Details" } ?: "Goat Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (goat != null && currentUser != null && marketplaceViewModel != null) {
                        IconButton(
                            onClick = { marketplaceViewModel.toggleWishlist(currentUser.id, goat.id) },
                            modifier = Modifier.testTag("btn_detail_wishlist")
                        ) {
                            Icon(
                                imageVector = if (isWishlisted) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Wishlist",
                                tint = if (isWishlisted) Color(0xFFD32F2F) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(
                            onClick = { showReportDialog = true },
                            modifier = Modifier.testTag("btn_report_listing")
                        ) {
                            Icon(Icons.Outlined.Flag, contentDescription = "Report Listing")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (goat != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Final Price",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = PriceUtils.formatINR(goat.effectivePrice),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (goat.discountPercentage > 0.0 && goat.effectivePrice < goat.price) {
                                    Text(
                                        text = PriceUtils.formatINR(goat.price),
                                        style = MaterialTheme.typography.bodyMedium,
                                        textDecoration = TextDecoration.LineThrough,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        val isAvailable = goat.availabilityStatus == AvailabilityStatus.AVAILABLE && goat.isActive
                        val isOwnFarm = currentUser?.farmId != null && currentUser.farmId == goat.farmId

                        Button(
                            onClick = { showBookingDialog = true },
                            enabled = isAvailable && !isOwnFarm,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("btn_book_goat"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.EventAvailable, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when {
                                    isOwnFarm -> "Your Farm Listing"
                                    isAvailable -> "Reserve / Book Now"
                                    else -> goat.availabilityStatus.name
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        if (goat == null) {
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
                    Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Goat Listing Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Listing ID: $goatId", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { marketplaceViewModel?.refreshMarketplace() }) {
                        Text("Reload Listing")
                    }
                }
            }
        } else {
            val allImages = remember(goat) {
                (goat.imageUrls + listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() })).distinct()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Image Gallery
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    val activeUrl = allImages.getOrNull(selectedImageIndex) ?: goat.imageUrl
                    if (activeUrl.isNotBlank()) {
                        AsyncImage(
                            model = activeUrl,
                            contentDescription = goat.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }

                    // Status & Tag Badge
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Tag: ${goat.tagId.ifBlank { goat.goatCode.ifBlank { goat.id.take(8) } }}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        Surface(
                            color = when (goat.availabilityStatus) {
                                AvailabilityStatus.AVAILABLE -> Color(0xFF1B5E20)
                                AvailabilityStatus.RESERVED -> Color(0xFFE65100)
                                AvailabilityStatus.SOLD -> Color(0xFFB71C1C)
                                AvailabilityStatus.UNAVAILABLE -> Color(0xFF616161)
                            },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = goat.availabilityStatus.name,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                // Thumbnail Strip if multiple images
                if (allImages.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        allImages.forEachIndexed { idx, imgUrl ->
                            Surface(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable { selectedImageIndex = idx },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    width = if (selectedImageIndex == idx) 2.5.dp else 1.dp,
                                    color = if (selectedImageIndex == idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                )
                            ) {
                                AsyncImage(
                                    model = imgUrl,
                                    contentDescription = "Thumbnail ${idx + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                // Details Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title & Breed Header
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = goat.name.ifBlank { "${goat.breed} Goat" },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                            if (goat.isVerified) {
                                Icon(
                                    imageVector = Icons.Default.Verified,
                                    contentDescription = "Verified",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Text(
                            text = "${goat.breed} • ${goat.purpose.ifBlank { "Breeding & Livestock" }}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Key Specifications Grid
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("Livestock Specifications", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SpecMetricItem(label = "Breed", value = goat.breed.ifBlank { "Native" }, modifier = Modifier.weight(1f))
                                SpecMetricItem(label = "Gender", value = goat.gender.ifBlank { "Male" }, modifier = Modifier.weight(1f))
                                SpecMetricItem(label = "Age", value = "${goat.ageMonths} Months", modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SpecMetricItem(label = "Weight", value = "${goat.weightKg} kg", modifier = Modifier.weight(1f))
                                SpecMetricItem(label = "Color / Coat", value = goat.color.ifBlank { "Natural" }, modifier = Modifier.weight(1f))
                                SpecMetricItem(label = "Vaccinated", value = if (goat.isVaccinated) "Yes (Verified)" else "Pending", modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Description & Health Notes
                    if (goat.description.isNotBlank() || goat.healthNotes.isNotBlank()) {
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (goat.description.isNotBlank()) {
                                    Text("About This Goat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(goat.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (goat.healthNotes.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Health & Vaccination Record", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(goat.healthNotes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }

                    // Seller Farm Card
                    val displayFarmName = farm?.name ?: goat.farmName.ifBlank { "Ammal Farm" }
                    val displayFarmLocation = farm?.location ?: goat.farmLocation.ifBlank { "Tamil Nadu" }
                    val displayFarmPhone = farm?.contactNumber ?: goat.farmContact

                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (goat.farmId.isNotBlank()) {
                                    onNavigateToFarmDetail(goat.farmId)
                                }
                            }
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Agriculture, contentDescription = null, tint = Color.White)
                                        }
                                    }
                                    Column {
                                        Text(displayFarmName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(displayFarmLocation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                Icon(Icons.Default.ChevronRight, contentDescription = "View Farm")
                            }

                            if (displayFarmPhone.isNotBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$displayFarmPhone"))
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Call Farm")
                                    }
                                    if (goat.farmId.isNotBlank()) {
                                        FilledTonalButton(
                                            onClick = { onNavigateToFarmDetail(goat.farmId) },
                                            modifier = Modifier.weight(1f),
                                            shape = RoundedCornerShape(10.dp)
                                        ) {
                                            Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Farm Profile")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showBookingDialog && goat != null && currentUser != null && marketplaceViewModel != null) {
        CreateBookingDialog(
            goat = goat,
            customer = currentUser,
            onDismiss = { showBookingDialog = false },
            onConfirmBooking = { visitDate, advance, notes ->
                marketplaceViewModel.createBooking(
                    goat = goat,
                    customer = currentUser,
                    preferredVisitDate = visitDate,
                    advanceAmount = advance,
                    notes = notes,
                    onSuccess = {
                        showBookingDialog = false
                        onNavigateToBookings()
                    }
                )
            }
        )
    }

    if (showReportDialog && goat != null && currentUser != null && marketplaceViewModel != null) {
        var reportReason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("Report Listing", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Please describe the issue with this listing for Super Admin review:")
                    OutlinedTextField(
                        value = reportReason,
                        onValueChange = { reportReason = it },
                        placeholder = { Text("e.g. Inaccurate weight, duplicate photo, wrong price") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reportReason.isNotBlank()) {
                            marketplaceViewModel.submitReport(goat.id, currentUser.id, reportReason)
                            showReportDialog = false
                        }
                    },
                    enabled = reportReason.isNotBlank()
                ) {
                    Text("Submit Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun SpecMetricItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CreateBookingDialog(
    goat: Goat,
    customer: UserProfile,
    onDismiss: () -> Unit,
    onConfirmBooking: (visitDate: String, advanceAmount: Double, notes: String) -> Unit
) {
    val defaultAdvance = remember(goat.effectivePrice) {
        (goat.effectivePrice * 0.10).coerceAtLeast(500.0)
    }
    var visitDate by remember { mutableStateOf("Within 3 Days") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reserve ${goat.name.ifBlank { goat.breed }}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Confirm your reservation request with ${goat.farmName.ifBlank { "the farm" }}. Once reserved, this goat is held for your farm visit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Goat Price: ${PriceUtils.formatINR(goat.effectivePrice)}", fontWeight = FontWeight.Bold)
                        Text(
                            "Recommended Advance Hold: ${PriceUtils.formatINR(defaultAdvance)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                OutlinedTextField(
                    value = visitDate,
                    onValueChange = { visitDate = it },
                    label = { Text("Preferred Farm Visit Date *") },
                    placeholder = { Text("e.g. Tomorrow Morning / 25 Oct") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Customer Notes (Optional)") },
                    placeholder = { Text("Any specific questions or pickup timing...") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmBooking(visitDate.trim(), defaultAdvance, notes.trim()) },
                enabled = visitDate.isNotBlank(),
                modifier = Modifier.testTag("btn_confirm_booking_dialog")
            ) {
                Text("Confirm Booking")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
