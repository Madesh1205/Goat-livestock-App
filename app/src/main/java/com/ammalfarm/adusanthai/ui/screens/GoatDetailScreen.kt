package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
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
    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenInitialPage by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
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
                (listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() }) + goat.imageUrls)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
            }
            val heroPagerState = rememberPagerState(initialPage = 0) {
                allImages.size.coerceAtLeast(1)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Image Gallery with Horizontal Swiping (Requirement 7) and Tap to View Full Screen (Requirement 6)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    if (allImages.isNotEmpty()) {
                        HorizontalPager(
                            state = heroPagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("pager_goat_photos")
                        ) { page ->
                            val imageUrl = allImages.getOrNull(page) ?: ""
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable {
                                        fullScreenInitialPage = page
                                        showFullScreenViewer = true
                                    }
                            ) {
                                if (imageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = imageUrl,
                                        contentDescription = "${goat.name} photo ${page + 1}",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (goat.imageUrl.isNotBlank()) {
                                        fullScreenInitialPage = 0
                                        showFullScreenViewer = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (goat.imageUrl.isNotBlank()) {
                                AsyncImage(
                                    model = goat.imageUrl,
                                    contentDescription = goat.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Default.Pets, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    // Availability & Photo Counter Badges
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = when (goat.availabilityStatus) {
                                AvailabilityStatus.AVAILABLE -> Color(0xFF1B5E20)
                                AvailabilityStatus.RESERVED, AvailabilityStatus.BOOKING_PENDING, AvailabilityStatus.PENDING, AvailabilityStatus.CONFIRMED, AvailabilityStatus.APPROVED -> Color(0xFFE65100)
                                AvailabilityStatus.SOLD, AvailabilityStatus.COMPLETED -> Color(0xFFB71C1C)
                                else -> Color(0xFF616161)
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

                        if (allImages.size > 1) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    fullScreenInitialPage = heroPagerState.currentPage
                                    showFullScreenViewer = true
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ZoomIn,
                                        contentDescription = "Tap to enlarge photo",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${heroPagerState.currentPage + 1} / ${allImages.size}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else if (allImages.size == 1) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.clickable {
                                    fullScreenInitialPage = 0
                                    showFullScreenViewer = true
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ZoomIn,
                                        contentDescription = "Tap to enlarge photo",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "View",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
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
                            val isSelected = heroPagerState.currentPage == idx
                            Surface(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clickable {
                                        coroutineScope.launch {
                                            heroPagerState.animateScrollToPage(idx)
                                        }
                                    },
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
                    // Title & Breed Header (Goat purpose removed per Requirement 5)
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
                            text = goat.breed.ifBlank { "Pure Breed Livestock" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Key Specifications Grid (Coat color and Vaccination status removed per Requirement 5)
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
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                SpecMetricItem(label = "Age", value = "${goat.ageMonths} Months", modifier = Modifier.weight(1f))
                                SpecMetricItem(label = "Weight", value = "${goat.weightKg} kg", modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Description (Requirement 5: Healthcare Notes removed from goat details page)
                    if (goat.description.isNotBlank()) {
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
                                Text("About This Goat", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(goat.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // Seller Farm Card with consistent Farm Banner and Logo (Requirement 4)
                    val displayFarmName = farm?.name?.ifBlank { null } ?: goat.farmName.ifBlank { "Ammal Farm" }
                    val displayFarmLocation = farm?.location?.ifBlank { null } ?: goat.farmLocation.ifBlank { "Tamil Nadu" }
                    val displayFarmPhone = farm?.contactNumber?.ifBlank { null } ?: goat.farmContact
                    val displayFarmLogo = farm?.logoUrl.orEmpty()
                    val displayFarmBanner = farm?.bannerUrl.orEmpty()

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
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (displayFarmBanner.isNotBlank()) {
                                AsyncImage(
                                    model = displayFarmBanner,
                                    contentDescription = "$displayFarmName Banner",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(96.dp),
                                    contentScale = ContentScale.Crop
                                )
                            }
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
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(46.dp)
                                        ) {
                                            if (displayFarmLogo.isNotBlank()) {
                                                AsyncImage(
                                                    model = displayFarmLogo,
                                                    contentDescription = "$displayFarmName Logo",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(CircleShape),
                                                    contentScale = ContentScale.Crop
                                                )
                                            } else {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Agriculture, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                                }
                                            }
                                        }
                                        Column {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(displayFarmName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                if (farm?.verificationStatus?.isApprovedOrVerified == true || farm?.isAmmalOwnFarm == true) {
                                                    Icon(
                                                        imageVector = Icons.Default.Verified,
                                                        contentDescription = "Verified Farm",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
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
            isSubmitting = uiState?.isLoading == true,
            errorMessage = uiState?.actionErrorMessage,
            onDismiss = {
                marketplaceViewModel.clearActionMessages()
                showBookingDialog = false
            },
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

    if (showFullScreenViewer && goat != null) {
        val allImages = (listOfNotNull(goat.imageUrl.takeIf { it.isNotBlank() }) + goat.imageUrls)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (allImages.isNotEmpty()) {
            FullScreenImageViewerDialog(
                images = allImages,
                initialPage = fullScreenInitialPage.coerceIn(0, allImages.size - 1),
                goatName = goat.name.ifBlank { "Goat Photo" },
                onDismiss = { showFullScreenViewer = false }
            )
        }
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
    isSubmitting: Boolean = false,
    errorMessage: String? = null,
    onDismiss: () -> Unit,
    onConfirmBooking: (visitDate: String, advanceAmount: Double, notes: String) -> Unit
) {
    val defaultAdvance = remember(goat.effectivePrice) {
        (goat.effectivePrice * 0.10).coerceAtLeast(500.0)
    }
    var visitDate by remember { mutableStateOf("Within 3 Days") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!isSubmitting) onDismiss() },
        title = { Text("Reserve ${goat.name.ifBlank { goat.breed }}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Confirm your reservation request with ${goat.farmName.ifBlank { "the farm" }}. Once reserved, this goat is held for your farm visit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (!errorMessage.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

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
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Customer Notes (Optional)") },
                    placeholder = { Text("Any specific questions or pickup timing...") },
                    minLines = 2,
                    enabled = !isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmBooking(visitDate.trim(), defaultAdvance, notes.trim()) },
                enabled = visitDate.isNotBlank() && !isSubmitting,
                modifier = Modifier.testTag("btn_confirm_booking_dialog")
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reserving...")
                } else {
                    Text("Confirm Booking")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSubmitting
            ) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullScreenImageViewerDialog(
    images: List<String>,
    initialPage: Int,
    goatName: String,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, (images.size - 1).coerceAtLeast(0))) {
        images.size.coerceAtLeast(1)
    }
    var isCurrentZoomed by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("fullscreen_image_viewer")
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !isCurrentZoomed
            ) { page ->
                val imageUrl = images.getOrNull(page) ?: ""
                ZoomableImageView(
                    imageUrl = imageUrl,
                    contentDescription = "$goatName photo ${page + 1}",
                    onZoomChanged = { zoomed ->
                        if (pagerState.currentPage == page) {
                            isCurrentZoomed = zoomed
                        }
                    }
                )
            }

            // Top Bar Overlay
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("btn_close_fullscreen_viewer")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close full screen image viewer",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }

                if (images.size > 1) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.size(48.dp))
            }
        }
    }
}

@Composable
private fun ZoomableImageView(
    imageUrl: String,
    contentDescription: String,
    onZoomChanged: (Boolean) -> Unit = {}
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                            onZoomChanged(false)
                        } else {
                            scale = 2.5f
                            onZoomChanged(true)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    val isZoomed = newScale > 1.05f
                    onZoomChanged(isZoomed)
                    if (isZoomed) {
                        val maxOffsetX = (size.width * (newScale - 1f)) / 2f
                        val maxOffsetY = (size.height * (newScale - 1f)) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(
                model = imageUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offsetX
                        translationY = offsetY
                    }
            )
        } else {
            Icon(
                Icons.Default.Pets,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(96.dp)
            )
        }
    }
}
