package com.ammalfarm.adusanthai.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.Bitmap
import com.ammalfarm.adusanthai.util.ImageUploadHelper
import com.ammalfarm.adusanthai.core.util.PriceUtils
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.ammalfarm.adusanthai.model.*
import com.ammalfarm.adusanthai.ui.viewmodel.MarketplaceUiState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GoatDetailScreen(
    goat: Goat,
    uiState: MarketplaceUiState,
    currentUser: UserProfile? = null,
    isAuthenticated: Boolean = false,
    onBackClick: () -> Unit,
    onFarmClick: (String) -> Unit,
    onBreedClick: ((String) -> Unit)? = null,
    onLoginRequired: () -> Unit = {},
    onBookGoat: (String, String) -> Unit = { _, _ -> },
    onSubmitReport: (targetType: String, targetId: String, targetTitle: String, reason: ReportReason, description: String, evidencePhotoUrl: String?) -> Unit = { _, _, _, _, _, _ -> },
    onToggleWishlist: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Dialog state
    var showReportDialog by remember { mutableStateOf(false) }
    var showAuthRequiredDialog by remember { mutableStateOf(false) }
    var showBookingPreviewDialog by remember { mutableStateOf(false) }
    var showCallUnlockPromptDialog by remember { mutableStateOf(false) }
    var showFullscreenImage by remember { mutableStateOf(false) }
    val isSavedInWishlist = remember(uiState.wishlistGoatIds, goat.id) {
        uiState.wishlistGoatIds.contains(goat.id)
    }
    var bookingNotes by remember { mutableStateOf("") }
    var reportSelectedReason by remember { mutableStateOf(ReportReason.INCORRECT_INFO) }
    var reportDetails by remember { mutableStateOf("") }
    var reportEvidenceUrl by remember { mutableStateOf("") }

    // Multi-photo list (from Supabase)
    val photosList = remember(goat.photos) { goat.photos.filter { it.isNotBlank() } }
    val hasPhotos = photosList.isNotEmpty()
    val pagerState = rememberPagerState(pageCount = { if (hasPhotos) photosList.size else 1 })

    // Resolve farm from the goat's real farmId UUID
    val resolvedFarm = remember(uiState.farms, goat.farmId) {
        uiState.farms.find { it.id == goat.farmId }
    }
    val displayFarmName = resolvedFarm?.name?.takeIf { it.isNotBlank() } ?: goat.farmName.ifBlank { "Ammal Farm" }
    val displayFarmLocation = resolvedFarm?.location?.takeIf { it.isNotBlank() } ?: goat.farmLocation.ifBlank { "Salem, Tamil Nadu" }
    val isFarmVerified = resolvedFarm?.verificationStatus == VerificationStatus.APPROVED

    // Availability & Approval State Analysis
    val isListingApproved = goat.approvalStatus == ApprovalStatus.APPROVED
    val isAvailable = isListingApproved && goat.availabilityStatus == AvailabilityStatus.AVAILABLE
    val isReserved = goat.availabilityStatus == AvailabilityStatus.RESERVED
    val isBooked = goat.availabilityStatus == AvailabilityStatus.BOOKING_PENDING || goat.availabilityStatus == AvailabilityStatus.CONFIRMED
    val isSold = goat.availabilityStatus == AvailabilityStatus.SOLD
    val isUnavailableOrRemoved = !isListingApproved || goat.availabilityStatus == AvailabilityStatus.CANCELLED || goat.availabilityStatus == AvailabilityStatus.REJECTED

    // Check if the current authenticated customer has a completed booking for this goat
    val currentUserId = uiState.currentUser?.id ?: ""
    val userActiveBooking = remember(uiState.customerBookings, goat.id, currentUserId) {
        if (currentUserId.isEmpty()) null
        else uiState.customerBookings.find {
            (it.goatId == goat.id || it.goatName.equals(goat.name, ignoreCase = true)) &&
            (it.status == AvailabilityStatus.BOOKING_PENDING || 
             it.status == AvailabilityStatus.RESERVED || 
             it.status == AvailabilityStatus.CONFIRMED || 
             it.status == AvailabilityStatus.COMPLETED || 
             it.status == AvailabilityStatus.SOLD)
        }
    }
    val hasValidBooking = userActiveBooking != null



    val effectiveUser = currentUser ?: uiState.currentUser
    val isSuperAdmin = effectiveUser?.role == UserRole.SUPER_ADMIN
    val isFarmAdmin = effectiveUser?.role == UserRole.FARM_ADMIN
    val userFarm = remember(uiState.farms, effectiveUser) {
        if (effectiveUser == null) null
        else uiState.farms.find {
            (!effectiveUser.id.isNullOrBlank() && it.ownerId == effectiveUser.id) ||
            (!effectiveUser.farmId.isNullOrBlank() && it.id == effectiveUser.farmId)
        }
    }
    val userFarmId = effectiveUser?.farmId?.takeIf { it.isNotBlank() } ?: userFarm?.id?.takeIf { it.isNotBlank() }

    val isOwnFarmGoat = isFarmAdmin && (
        (!userFarmId.isNullOrBlank() && goat.farmId.isNotBlank() && userFarmId == goat.farmId) ||
        (resolvedFarm != null && !resolvedFarm.ownerId.isNullOrBlank() && !effectiveUser?.id.isNullOrBlank() && resolvedFarm.ownerId == effectiveUser?.id)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = goat.name,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = goat.breed,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBackClick,
                        modifier = Modifier.testTag("goat_detail_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Wishlist toggle button
                    IconButton(
                        onClick = {
                            if (!isAuthenticated) {
                                showAuthRequiredDialog = true
                            } else {
                                onToggleWishlist()
                            }
                        },
                        modifier = Modifier.testTag("goat_detail_wishlist_button")
                    ) {
                        Icon(
                            imageVector = if (isSavedInWishlist) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isSavedInWishlist) "Remove from Wishlist" else "Save to Wishlist",
                            tint = if (isSavedInWishlist) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Share Listing
                    IconButton(
                        onClick = {
                            val priceInfo = if (goat.hasDiscount) "${goat.formattedFinalPrice} (${goat.formattedDiscountBadge} original ${goat.formattedPrice})" else goat.formattedPrice
                            val shareUrl = "https://adusanthai.ammalfarm.dpdns.org/goats/${goat.id}"
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Check out ${goat.name} on Adu Santhai")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Check out ${goat.name} (${goat.breed}, ${goat.gender.name.lowercase()}, ${goat.weightKg} kg) from $displayFarmName on Adu Santhai for $priceInfo!\n\nView listing: $shareUrl"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Goat Listing"))
                        }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share")
                    }

                    // Report Listing
                    IconButton(
                        onClick = { showReportDialog = true },
                        modifier = Modifier.testTag("report_listing_top_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Flag,
                            contentDescription = "Report Listing",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Amazon-style Fixed Sticky Bottom Action Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Total Price",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (goat.hasDiscount) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = goat.formattedFinalPrice,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = goat.formattedPrice,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                )
                            }
                        } else {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = goat.formattedPrice,
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    // Dynamic State-Aware Book Now / Action Button
                    when {
                        isOwnFarmGoat -> {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "You cannot book goats listed by your own farm.",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        userActiveBooking != null -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = when (userActiveBooking.status) {
                                        AvailabilityStatus.CONFIRMED -> Color(0xFFE8F5E9)
                                        AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFFE3F2FD)
                                        else -> Color(0xFFFFF3E0)
                                    }
                                ) {
                                    Text(
                                        text = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> "Confirmed"
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> "Completed"
                                            else -> "Booked (Pending)"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> Color(0xFF2E7D32)
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF1565C0)
                                            else -> Color(0xFFE65100)
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }

                                Button(
                                    onClick = {
                                        val contact = resolvedFarm?.contactNumber?.takeIf { it.isNotBlank() }
                                            ?: uiState.farms.find { it.id == goat.farmId }?.contactNumber
                                        if (!contact.isNullOrBlank()) {
                                            val callIntent = Intent(Intent.ACTION_DIAL).apply {
                                                data = Uri.parse("tel:$contact")
                                            }
                                            try {
                                                context.startActivity(callIntent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Breeder Contact: $contact", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Farm contact number not available", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("bottom_call_breeder_button")
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Call Breeder", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                        isAvailable -> {
                            Button(
                                onClick = {
                                    if (!isAuthenticated) {
                                        showAuthRequiredDialog = true
                                    } else {
                                        showBookingPreviewDialog = true
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF39C12) // Amazon-style Warm Gold Accent
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("reserve_goat_button")
                                    .testTag("book_now_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = "Reserve Goat",
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Reserve Goat",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = Color.Black
                                )
                            }
                        }
                        isReserved -> {
                            FilledTonalButton(
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "This goat is currently reserved by another buyer. If not confirmed, it will become available.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = Color(0xFFFFF3E0),
                                    contentColor = Color(0xFFE65100)
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.HourglassTop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Currently Reserved", fontWeight = FontWeight.Bold)
                            }
                        }
                        isBooked -> {
                            FilledTonalButton(
                                onClick = {
                                    Toast.makeText(
                                        context,
                                        "This goat has been booked and is awaiting farm verification and dispatch.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Booked by Buyer", fontWeight = FontWeight.Bold)
                            }
                        }
                        isSold -> {
                            FilledTonalButton(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Cancel, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sold Out", fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> {
                            FilledTonalButton(
                                onClick = {},
                                enabled = false,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.height(48.dp)
                            ) {
                                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Listing Inactive", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
        ) {
            // 1. LARGE IMAGE CAROUSEL WITH SWIPING / PAGING
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (!hasPhotos) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Pets,
                                contentDescription = "No image available",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(56.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No image available",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(enabled = hasPhotos) { showFullscreenImage = true }
                        ) {
                            if (hasPhotos) {
                                SubcomposeAsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current)
                                        .data(photosList.getOrNull(page) ?: "")
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = "${goat.name} photo ${page + 1}",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                    loading = {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                        }
                                    },
                                    error = {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(MaterialTheme.colorScheme.surfaceVariant),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.Pets,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(48.dp)
                                            )
                                        }
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Pets,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }

                            // Subtle bottom gradient for indicator legibility
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(60.dp)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                                        )
                                    )
                            )
                        }
                    }
                }

                // Top-Right: Availability Status Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        isAvailable -> Color(0xFF2E7D32)
                        isReserved -> Color(0xFFF39C12)
                        isBooked -> Color(0xFF1976D2)
                        isSold -> Color(0xFF616161)
                        else -> MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier
                        .padding(14.dp)
                        .align(Alignment.TopEnd)
                ) {
                    Text(
                        text = when {
                            isAvailable -> "AVAILABLE"
                            isReserved -> "RESERVED (24H HOLD)"
                            isBooked -> "BOOKED"
                            isSold -> "SOLD"
                            else -> "UNAVAILABLE"
                        },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                // Bottom-Right: Pager Index Counter (e.g. 1/4)
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier
                        .padding(14.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "${pagerState.currentPage + 1} / ${photosList.size}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Bottom-Center: Pager Dot Indicators
                if (photosList.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp)
                    ) {
                        repeat(photosList.size) { index ->
                            val isCurrent = pagerState.currentPage == index
                            Box(
                                modifier = Modifier
                                    .size(if (isCurrent) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (isCurrent) Color.White else Color.White.copy(alpha = 0.4f))
                            )
                        }
                    }
                }
            }

            // 2. INTERACTIVE THUMBNAIL STRIP
            if (photosList.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(photosList) { index, photoUrl ->
                        val isSelected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(
                                    width = if (isSelected) 2.5.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                        ) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalContext.current)
                                    .data(photoUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Thumbnail ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                },
                                error = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Pets,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 3. MAIN PRODUCT INFORMATION SECTION
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Breadcrumbs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Livestock",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onBreedClick?.invoke("") }
                    )
                    Text(" › ", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = goat.breed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onBreedClick?.invoke(goat.breed) }
                    )
                    Text(" › ", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    Text(
                        text = goat.gender.name.lowercase().replaceFirstChar { it.uppercase() },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Goat Name / Headline
                Text(
                    text = goat.name,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 28.sp
                )

                Divider(
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // 4. PRICING BLOCK
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp)
                ) {
                    if (goat.hasDiscount) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = goat.formattedFinalPrice,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = goat.formattedPrice,
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF2E7D32)
                            ) {
                                Text(
                                    text = goat.formattedDiscountBadge,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Price & Discount breakdown
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Price:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(goat.formattedPrice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Discount:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(PriceUtils.formatDiscountPercent(goat.discountPercentage), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Discount Amount:", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32))
                                    Text(goat.formattedSavings, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Final Price:", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    Text(goat.formattedFinalPrice, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    } else {
                        Text(
                            text = goat.formattedPrice,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (goat.hasDiscount) "Special discount applied. Inclusive of all local taxes."
                               else "Inclusive of all local taxes.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Direct Farm Visit & Inspection Info
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Storefront,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Direct Farm Visit & Inspection at $displayFarmName",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // 5. APPROPRIATE STATE ALERT BANNER (AVAILABLE / RESERVED / BOOKED / SOLD / REMOVED)
                when {
                    isAvailable -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFE8F5E9),
                            border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = Color(0xFF2E7D32),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "In Stock & Ready for 24-Hour Hold",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = "Book now to place an exclusive 24-hour reservation while you arrange transport or farm visit.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF2E7D32)
                                    )
                                }
                            }
                        }
                    }
                    isReserved -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFFF8E1),
                            border = BorderStroke(1.dp, Color(0xFFFFE082)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LockClock,
                                    contentDescription = null,
                                    tint = Color(0xFFF39C12),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Currently Reserved (24h Hold)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFFB78103)
                                    )
                                    Text(
                                        text = "Another customer has placed a 24-hour reservation on this goat. It will become available if not completed.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF795548)
                                    )
                                }
                            }
                        }
                    }
                    isBooked -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFE3F2FD),
                            border = BorderStroke(1.dp, Color(0xFF90CAF9)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.AssignmentTurnedIn,
                                    contentDescription = null,
                                    tint = Color(0xFF1976D2),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Booked by Customer",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF0D47A1)
                                    )
                                    Text(
                                        text = "This goat has been booked and is currently undergoing quarantine and health certificate issuance.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF1565C0)
                                    )
                                }
                            }
                        }
                    }
                    isSold -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFFFAFAFA),
                            border = BorderStroke(1.dp, Color(0xFFE0E0E0)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RemoveCircleOutline,
                                    contentDescription = null,
                                    tint = Color(0xFF757575),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Sold Out",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF424242)
                                    )
                                    Text(
                                        text = "This livestock listing has been sold. Explore other goats from $displayFarmName.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF616161)
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Listing Inactive or Removed",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = "This livestock listing is not currently available for customer booking.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 6. TECHNICAL SPECIFICATIONS & CHARACTERISTICS (AMAZON-STYLE SPEC TABLE)
                Text(
                    text = "Technical Specifications",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(10.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SpecRow(
                            label = "Breed",
                            value = goat.breed,
                            isClickable = onBreedClick != null,
                            onClick = { onBreedClick?.invoke(goat.breed) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SpecRow(
                            label = "Gender",
                            value = if (goat.gender == GoatGender.MALE) "Male (Buck / Stud)" else "Female (Doe / Dam)"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SpecRow(
                            label = "Age",
                            value = "${goat.ageMonths} Months (${goat.ageMonths / 12} yr ${goat.ageMonths % 12} mo)"
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SpecRow(
                            label = "Live Body Weight",
                            value = "${goat.weightKg} kg (Certified farm scale)"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 7. DESCRIPTION & LIVESTOCK HIGHLIGHTS
                Text(
                    text = "About this Goat",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = goat.description,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Bullet points of highlights
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HighlightBullet(text = "Healthy active temperament from quality lineage.")
                    HighlightBullet(text = "Clean farm rearing environment.")
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 8. FARM / SELLER INFORMATION CARD
                Text(
                    text = "Breeder & Seller Information",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFarmClick(goat.farmId) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                val logoUrl = resolvedFarm?.logoUrl
                                if (!logoUrl.isNullOrBlank()) {
                                    SubcomposeAsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(logoUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "$displayFarmName Logo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                                        loading = {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(20.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                        },
                                        error = {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.Default.Agriculture,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                        }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.Agriculture,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = displayFarmName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isFarmVerified) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                Icons.Default.Verified,
                                                contentDescription = "Verified Farm",
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = displayFarmLocation,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "View Farm Storefront",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Breeder Action buttons: Call Breeder is gated until a booking is placed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onFarmClick(goat.farmId) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Visit Farm", fontSize = 12.sp)
                            }

                            if (hasValidBooking) {
                                Button(
                                    onClick = {
                                        val contact = resolvedFarm?.contactNumber?.takeIf { it.isNotBlank() }
                                            ?: uiState.farms.find { it.id == goat.farmId }?.contactNumber
                                        if (!contact.isNullOrBlank()) {
                                            val callIntent = Intent(Intent.ACTION_DIAL).apply {
                                                data = Uri.parse("tel:$contact")
                                            }
                                            try {
                                                context.startActivity(callIntent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Breeder Contact: $contact", Toast.LENGTH_SHORT).show()
                                            }
                                        } else {
                                            Toast.makeText(context, "Farm contact number not available", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier.weight(1f).testTag("call_breeder_button")
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Call Breeder", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                FilledTonalButton(
                                    onClick = {
                                        if (!isAuthenticated) {
                                            showAuthRequiredDialog = true
                                        } else if (isAvailable) {
                                            showCallUnlockPromptDialog = true
                                        } else {
                                            Toast.makeText(context, "Direct phone contact is only available after placing a booking.", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.weight(1f).testTag("locked_contact_button")
                                ) {
                                    Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Call (Book First)", fontSize = 11.sp)
                                }
                            }
                        }

                        if (userActiveBooking != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = when (userActiveBooking.status) {
                                    AvailabilityStatus.CONFIRMED -> Color(0xFFE8F5E9)
                                    AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFFE3F2FD)
                                    else -> Color(0xFFFFF3E0)
                                },
                                border = BorderStroke(1.dp, when (userActiveBooking.status) {
                                    AvailabilityStatus.CONFIRMED -> Color(0xFFA5D6A7)
                                    AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF90CAF9)
                                    else -> Color(0xFFFFCC80)
                                }),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> Icons.Default.CheckCircle
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Icons.Default.Verified
                                            else -> Icons.Default.PhoneInTalk
                                        },
                                        contentDescription = null,
                                        tint = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> Color(0xFF2E7D32)
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF1565C0)
                                            else -> Color(0xFFE65100)
                                        },
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> "Booking Confirmed! You can coordinate pickup with breeder."
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> "Livestock purchase completed."
                                            else -> "Booking Created • Connect with breeder by phone while farm confirms."
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = when (userActiveBooking.status) {
                                            AvailabilityStatus.CONFIRMED -> Color(0xFF2E7D32)
                                            AvailabilityStatus.COMPLETED, AvailabilityStatus.SOLD -> Color(0xFF1565C0)
                                            else -> Color(0xFFE65100)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 9. GUARANTEE & REPORT LISTING FOOTER
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Marketplace Buyer Protection",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "All goat listings are verified against state livestock breed standards. 24-hour reservation holds ensure no duplicate bookings.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Report Listing Link
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showReportDialog = true }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Report,
                        contentDescription = "Report Listing",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Report incorrect livestock information or seller",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.Underline
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // --- FULLSCREEN IMAGE VIEWER MODAL ---
    if (showFullscreenImage) {
        Dialog(
            onDismissRequest = { showFullscreenImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(photosList.getOrNull(page) ?: "")
                                .crossfade(true)
                                .build(),
                            contentDescription = "${goat.name} Fullscreen",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                            loading = {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            },
                            error = {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Pets,
                                        contentDescription = null,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(64.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                // Top Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showFullscreenImage = false },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }

                    Text(
                        text = "${pagerState.currentPage + 1} of ${photosList.size}",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }

    // --- AUTHENTICATION REQUIRED MODAL (CUSTOMERS MUST BE LOGGED IN BEFORE BOOK NOW) ---
    if (showAuthRequiredDialog) {
        AlertDialog(
            onDismissRequest = { showAuthRequiredDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    text = "Sign In Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "You must be signed in to book or place a 24-hour reservation on this goat. Sign in or create a buyer profile to continue.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAuthRequiredDialog = false
                        onLoginRequired()
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("login_proceed_button")
                ) {
                    Text("Sign In / Register", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAuthRequiredDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- BOOKING PREVIEW / INQUIRY SHEET (DO NOT PROCESS MANDATORY IN-APP PAYMENT) ---
    if (showBookingPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showBookingPreviewDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.BookmarkAdded,
                    contentDescription = null,
                    tint = Color(0xFFF39C12),
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Book ${goat.name}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Reserve this goat with zero upfront online payment. Once submitted, $displayFarmName will receive your booking details and you can connect over the phone to confirm pickup/logistics. The breeder will then confirm your booking.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Goat Code:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(goat.goatCode.ifBlank { "GOAT-" + goat.id.take(6).uppercase() }, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Goat Price:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (goat.hasDiscount) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            goat.formattedFinalPrice,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            goat.formattedPrice,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "(${goat.formattedDiscountBadge})",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32)
                                        )
                                    }
                                } else {
                                    Text(goat.formattedPrice, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Upfront Payment:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("₹0 (No in-app charge)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Flow:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Book → Call Farm → Farm Confirms", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = bookingNotes,
                        onValueChange = { bookingNotes = it },
                        label = { Text("Special requirements / pickup notes") },
                        placeholder = { Text("e.g., Preferred weekend pickup, live transit") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isOwnFarmGoat) {
                            Toast.makeText(context, "You cannot book goats listed by your own farm.", Toast.LENGTH_LONG).show()
                            showBookingPreviewDialog = false
                            return@Button
                        }
                        if (isAvailable) {
                            onBookGoat(goat.id, bookingNotes)
                            showBookingPreviewDialog = false
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("confirm_reservation_button")
                ) {
                    Text("Confirm Booking Request", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBookingPreviewDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- LOCKED CONTACT INFO DIALOG ---
    if (showCallUnlockPromptDialog) {
        AlertDialog(
            onDismissRequest = { showCallUnlockPromptDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.fillMaxWidth(0.92f),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Outlined.PhoneCallback,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Contact Breeder",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "To protect breeder privacy and prevent unsolicited calls, direct phone contact is unlocked immediately after you place a booking request for this goat.\n\nNo upfront payment is required.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCallUnlockPromptDialog = false
                        if (isOwnFarmGoat) {
                            Toast.makeText(context, "You cannot book goats listed by your own farm.", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        if (isAvailable) {
                            showBookingPreviewDialog = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("floating_reserve_goat_button")
                ) {
                    Text("Reserve Goat", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCallUnlockPromptDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // --- REPORT LISTING DIALOG ---
    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Outlined.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Report Livestock Listing",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Help us maintain authentic, verified livestock on the marketplace. Please select the primary reason for reporting ${goat.name} (${goat.goatCode.ifBlank { "GOAT-" + goat.id.take(6).uppercase() }}):",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    ReportReason.values().forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reportSelectedReason = reason }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = reportSelectedReason == reason,
                                onClick = { reportSelectedReason = reason }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = reason.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (reportSelectedReason == reason) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reportDetails,
                        onValueChange = { reportDetails = it },
                        label = { Text("Description / Specific details *") },
                        placeholder = { Text("Explain the issue or discrepancy in detail...") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reportEvidenceUrl,
                        onValueChange = { reportEvidenceUrl = it },
                        label = { Text("Evidence Photo URL (optional)") },
                        placeholder = { Text("https://... or photo proof link") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val desc = reportDetails.ifBlank { reportSelectedReason.displayName }
                        val photo = reportEvidenceUrl.trim().ifBlank { null }
                        onSubmitReport(
                            "GOAT_LISTING",
                            goat.id,
                            "${goat.name} (${goat.goatCode.ifBlank { "GOAT-" + goat.id.take(6).uppercase() }})",
                            reportSelectedReason,
                            desc,
                            photo
                        )
                        showReportDialog = false
                        reportDetails = ""
                        reportEvidenceUrl = ""
                        Toast.makeText(
                            context,
                            "Thank you. Your report has been submitted to marketplace safety admins.",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Submit Report", fontWeight = FontWeight.Bold)
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
private fun SpecRow(
    label: String,
    value: String,
    isClickable: Boolean = false,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isClickable, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isClickable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            if (isClickable) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun HighlightBullet(text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "•",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}


