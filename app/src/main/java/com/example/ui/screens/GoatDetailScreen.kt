package com.example.ui.screens

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
import com.example.util.ImageUploadHelper
import com.example.core.util.PriceUtils
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.model.*
import com.example.ui.viewmodel.MarketplaceUiState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GoatDetailScreen(
    goat: Goat,
    uiState: MarketplaceUiState,
    isAuthenticated: Boolean = false,
    onBackClick: () -> Unit,
    onFarmClick: (String) -> Unit,
    onBreedClick: ((String) -> Unit)? = null,
    onLoginRequired: () -> Unit = {},
    onBookGoat: (String, String) -> Unit = { _, _ -> },
    onAddReview: (bookingId: String, goatId: String, rating: Int, comment: String, photos: List<String>) -> Unit = { _, _, _, _, _ -> },
    onReportReview: (reviewId: String, reason: String) -> Unit = { _, _ -> },
    onDeleteReview: (reviewId: String) -> Unit = {},
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
    var showWriteReviewDialog by remember { mutableStateOf(false) }
    var showReportReviewDialog by remember { mutableStateOf<Review?>(null) }
    var selectedReviewPhotoForPreview by remember { mutableStateOf<String?>(null) }
    var showFullscreenImage by remember { mutableStateOf(false) }
    val isSavedInWishlist = remember(uiState.wishlistGoatIds, goat.id) {
        uiState.wishlistGoatIds.contains(goat.id)
    }
    var bookingNotes by remember { mutableStateOf("") }
    var reportSelectedReason by remember { mutableStateOf(ReportReason.INCORRECT_INFO) }
    var reportDetails by remember { mutableStateOf("") }
    var reportEvidenceUrl by remember { mutableStateOf("") }

    // Review dialog inputs
    var reviewRating by remember { mutableIntStateOf(5) }
    var reviewComment by remember { mutableStateOf("") }
    var reviewPhotos by remember { mutableStateOf<List<String>>(emptyList()) }
    var isUploadingReviewPhoto by remember { mutableStateOf(false) }
    var reviewReportSelectedReason by remember { mutableStateOf(ReportReason.OTHER) }
    var reviewReportDetails by remember { mutableStateOf("") }

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

    // Live Reviews for this goat
    val goatReviews = remember(uiState.allReviews, goat.id) {
        uiState.allReviews.filter { it.goatId == goat.id }
    }

    val avgRating = remember(goatReviews, goat.rating) {
        if (goatReviews.isNotEmpty()) {
            val sum = goatReviews.sumOf { it.rating.toDouble() }
            Math.round((sum / goatReviews.size) * 10.0) / 10.0
        } else {
            goat.rating
        }
    }

    val totalReviewCount = remember(goatReviews, goat.reviewCount) {
        if (goatReviews.isNotEmpty()) goatReviews.size else goat.reviewCount
    }

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

    val userCompletedBooking = remember(uiState.customerBookings, goat.id, currentUserId) {
        if (currentUserId.isEmpty()) null
        else uiState.customerBookings.find {
            (it.goatId == goat.id || it.goatName.equals(goat.name, ignoreCase = true)) &&
            (it.status == AvailabilityStatus.COMPLETED || it.status == AvailabilityStatus.SOLD || it.status == AvailabilityStatus.CONFIRMED)
        }
    }

    // Check if user already submitted a review for this goat
    val userExistingReview = remember(goatReviews, currentUserId) {
        if (currentUserId.isEmpty()) null
        else goatReviews.find { it.customerId == currentUserId }
    }

    val isEligibleToReview = isAuthenticated && userCompletedBooking != null && userExistingReview == null
    val isSuperAdmin = uiState.currentUser?.role == UserRole.SUPER_ADMIN

    val pickReviewPhotosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty() && userCompletedBooking != null) {
            val bId = userCompletedBooking.id
            isUploadingReviewPhoto = true
            coroutineScope.launch {
                var count = 0
                val initialCount = reviewPhotos.size
                for ((idx, uri) in uris.withIndex()) {
                    val compressed = ImageUploadHelper.compressAndResizeImage(context, uri)
                    if (compressed != null) {
                        val uploadResult = ImageUploadHelper.uploadReviewImage(context, compressed, bId, initialCount + idx + 1)
                        uploadResult.onSuccess { url ->
                            reviewPhotos = reviewPhotos + url
                            count++
                        }.onFailure {
                            val local = ImageUploadHelper.saveImageLocally(context, compressed, "review_$bId")
                            reviewPhotos = reviewPhotos + local
                            count++
                        }
                    }
                }
                isUploadingReviewPhoto = false
                if (count > 0) {
                    Toast.makeText(context, "$count photo(s) added to review!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val takeReviewCameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null && userCompletedBooking != null) {
            val bId = userCompletedBooking.id
            isUploadingReviewPhoto = true
            coroutineScope.launch {
                val compressed = ImageUploadHelper.compressAndResizeBitmap(bitmap)
                val uploadResult = ImageUploadHelper.uploadReviewImage(context, compressed, bId, reviewPhotos.size + 1)
                uploadResult.onSuccess { url ->
                    reviewPhotos = reviewPhotos + url
                    Toast.makeText(context, "Photo added to review!", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    val local = ImageUploadHelper.saveImageLocally(context, compressed, "review_$bId")
                    reviewPhotos = reviewPhotos + local
                    Toast.makeText(context, "Photo added to review!", Toast.LENGTH_SHORT).show()
                }
                isUploadingReviewPhoto = false
            }
        }
    }

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
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Check out ${goat.name} on Goat Marketplace")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Check out ${goat.name} (${goat.breed}, ${goat.gender.name.lowercase()}, ${goat.weightKg} kg) from $displayFarmName on the Goat Marketplace for $priceInfo!"
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
                                    .testTag("book_now_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.BookmarkBorder,
                                    contentDescription = null,
                                    tint = Color.Black,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Book Now (No Payment)",
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
                            isReserved -> "RESERVED (48H HOLD)"
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

                Spacer(modifier = Modifier.height(6.dp))

                // Rating and Review Count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (totalReviewCount > 0 && avgRating > 0.0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFFFF8E1),
                            border = BorderStroke(1.dp, Color(0xFFFFD54F))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = String.format("%.1f", avgRating),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFB78103)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = Color(0xFFF39C12),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "$totalReviewCount customer ${if (totalReviewCount == 1) "rating" else "ratings"}",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RateReview,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "No customer reviewed",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

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
                                        text = "In Stock & Ready for 48-Hour Hold",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFF1B5E20)
                                    )
                                    Text(
                                        text = "Book now to place an exclusive 48-hour reservation while you arrange transport or farm visit.",
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
                                        text = "Currently Reserved (48h Hold)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color(0xFFB78103)
                                    )
                                    Text(
                                        text = "Another customer has placed a 48-hour reservation on this goat. It will become available if not completed.",
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
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                        SpecRow(
                            label = "Primary Purpose",
                            value = goat.purpose.name.lowercase().replaceFirstChar { it.uppercase() }
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

                // 9. CUSTOMER RATINGS & VERIFIED REVIEWS SECTION
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Customer Reviews ($totalReviewCount)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    if (isEligibleToReview) {
                        Button(
                            onClick = {
                                reviewRating = 5
                                reviewComment = ""
                                reviewPhotos = emptyList()
                                showWriteReviewDialog = true
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("write_review_button")
                        ) {
                            Icon(Icons.Default.RateReview, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Write a Review", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (goatReviews.isNotEmpty() || (totalReviewCount > 0 && avgRating > 0.0)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = String.format("%.1f", avgRating),
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row {
                                        repeat(5) { i ->
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                tint = if (i < avgRating.toInt()) Color(0xFFF39C12) else Color(0xFFE0E0E0),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "$totalReviewCount ${if (totalReviewCount == 1) "review" else "reviews"}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.width(20.dp))

                                // Rating breakdown bars
                                val count5 = goatReviews.count { it.rating == 5 }
                                val count4 = goatReviews.count { it.rating == 4 }
                                val count3 = goatReviews.count { it.rating == 3 }
                                val count2 = goatReviews.count { it.rating == 2 }
                                val count1 = goatReviews.count { it.rating == 1 }
                                val totalR = if (goatReviews.isNotEmpty()) goatReviews.size.toFloat() else 1f

                                Column(modifier = Modifier.weight(1f)) {
                                    RatingBarRow(stars = "5 star", percentage = if (goatReviews.isNotEmpty()) count5 / totalR else 0.0f)
                                    RatingBarRow(stars = "4 star", percentage = if (goatReviews.isNotEmpty()) count4 / totalR else 0.0f)
                                    RatingBarRow(stars = "3 star", percentage = if (goatReviews.isNotEmpty()) count3 / totalR else 0.0f)
                                    RatingBarRow(stars = "2 star", percentage = if (goatReviews.isNotEmpty()) count2 / totalR else 0.0f)
                                    RatingBarRow(stars = "1 star", percentage = if (goatReviews.isNotEmpty()) count1 / totalR else 0.0f)
                                }
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RateReview,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "No customer reviewed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Be the first verified buyer to leave a review for this livestock.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Eligible banner / already reviewed banner / buyer protection notice
                        Spacer(modifier = Modifier.height(14.dp))
                        if (userExistingReview != null) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFFE8F5E9),
                                border = BorderStroke(1.dp, Color(0xFFA5D6A7)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "You reviewed this livestock purchase (${userExistingReview.rating}★)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1B5E20)
                                    )
                                }
                            }
                        } else if (isEligibleToReview) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Verified, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Verified purchase completed. Share your feedback with other buyers!",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TextButton(
                                        onClick = {
                                            reviewRating = 5
                                            reviewComment = ""
                                            reviewPhotos = emptyList()
                                            showWriteReviewDialog = true
                                        }
                                    ) {
                                        Text("Review Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        "Only customers with a completed livestock booking can leave a review. This guarantees authentic feedback.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 15.sp
                                    )
                                }
                            }
                        }

                        // List of reviews
                        if (goatReviews.isNotEmpty()) {
                            val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                            goatReviews.forEachIndexed { index, review ->
                                Spacer(modifier = Modifier.height(14.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(modifier = Modifier.height(12.dp))

                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = review.customerName.take(1).uppercase(),
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(review.customerName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                    if (review.isVerifiedPurchase) {
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("• Verified Buyer", fontSize = 11.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                                Text(
                                                    dateFormat.format(Date(review.createdAt)),
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            repeat(5) { i ->
                                                Icon(
                                                    Icons.Default.Star,
                                                    contentDescription = null,
                                                    tint = if (i < review.rating) Color(0xFFF39C12) else Color(0xFFE0E0E0),
                                                    modifier = Modifier.size(13.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = review.comment,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        lineHeight = 17.sp
                                    )

                                    // Review Photos if any
                                    if (review.photos.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(review.photos.size) { photoIdx ->
                                                val photoUrl = review.photos[photoIdx]
                                                SubcomposeAsyncImage(
                                                    model = ImageRequest.Builder(LocalContext.current)
                                                        .data(photoUrl)
                                                        .crossfade(true)
                                                        .build(),
                                                    contentDescription = "Review Photo",
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(56.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                                                        .clickable { selectedReviewPhotoForPreview = photoUrl }
                                                )
                                            }
                                        }
                                    }

                                    // Action buttons (Report / Super Admin Delete)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isSuperAdmin) {
                                            TextButton(
                                                onClick = { onDeleteReview(review.id) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(13.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("Delete (Admin)", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                            }
                                        }

                                        TextButton(
                                            onClick = { showReportReviewDialog = review },
                                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                        ) {
                                            Icon(Icons.Outlined.Flag, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(13.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Report", fontSize = 11.sp, color = Color.Gray)
                                        }
                                    }
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "No customer reviewed this goat yet. Verified buyers can submit feedback after booking.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 10. GUARANTEE & REPORT LISTING FOOTER
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
                                text = "All goat listings are verified against state livestock breed standards. 48-hour reservation holds ensure no duplicate bookings.",
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
                        text = "You must be signed in to book or place a 48-hour reservation on this goat. Sign in or create a buyer profile to continue.",
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
                                Text("Goat Tag:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(goat.tagNumber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                        if (isAvailable) {
                            showBookingPreviewDialog = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Book Now (Free)", fontWeight = FontWeight.Bold)
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
                        text = "Help us maintain authentic, verified livestock on the marketplace. Please select the primary reason for reporting ${goat.name} (${goat.tagNumber}):",
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
                            "${goat.name} (${goat.tagNumber})",
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

    // --- WRITE REVIEW DIALOG ---
    if (showWriteReviewDialog && userCompletedBooking != null) {
        val booking = userCompletedBooking
        AlertDialog(
            onDismissRequest = { showWriteReviewDialog = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .padding(vertical = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            icon = {
                Icon(
                    Icons.Default.RateReview,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Rate & Review Livestock",
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
                        text = "Verified purchase for ${booking.goatName} from ${booking.farmName}. Your genuine review helps the community.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Star Selector
                    Text("Rating:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        (1..5).forEach { star ->
                            IconButton(
                                onClick = { reviewRating = star },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "$star stars",
                                    tint = if (star <= reviewRating) Color(0xFFF39C12) else Color(0xFFD6D6D6),
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = when (reviewRating) {
                            5 -> "5 Stars - Excellent Stock & Experience"
                            4 -> "4 Stars - Very Good"
                            3 -> "3 Stars - Average"
                            2 -> "2 Stars - Disappointing"
                            else -> "1 Star - Poor Experience"
                        },
                        fontSize = 11.sp,
                        color = Color(0xFFF39C12),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Review Comment Text
                    OutlinedTextField(
                        value = reviewComment,
                        onValueChange = { reviewComment = it },
                        label = { Text("Written review *") },
                        placeholder = { Text("Describe the goat's health, temperament, breeder communication...") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("review_comment_input"),
                        minLines = 3,
                        maxLines = 5
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Optional Photos from Gallery or Camera
                    Text("Add Photos (Optional):", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                pickReviewPhotosLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            enabled = !isUploadingReviewPhoto,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isUploadingReviewPhoto) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Uploading...", fontSize = 11.sp)
                            } else {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gallery", fontSize = 12.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                try {
                                    takeReviewCameraLauncher.launch(null)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Camera not available: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isUploadingReviewPhoto,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Camera", fontSize = 12.sp)
                        }
                    }

                    // Attached photos thumbnail strip
                    if (reviewPhotos.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(reviewPhotos.size) { pIdx ->
                                val pUrl = reviewPhotos[pIdx]
                                Box(modifier = Modifier.size(48.dp)) {
                                    SubcomposeAsyncImage(
                                        model = pUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(6.dp))
                                    )
                                    IconButton(
                                        onClick = {
                                            reviewPhotos = reviewPhotos.toMutableList().also { it.removeAt(pIdx) }
                                        },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(18.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (reviewComment.isBlank()) {
                            Toast.makeText(context, "Please write a brief comment for your review", Toast.LENGTH_SHORT).show()
                        } else {
                            onAddReview(
                                booking.id,
                                booking.goatId,
                                reviewRating,
                                reviewComment.trim(),
                                reviewPhotos
                            )
                            showWriteReviewDialog = false
                            Toast.makeText(context, "Review published successfully! Thank you.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("submit_review_button")
                ) {
                    Text("Submit Review", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWriteReviewDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- REPORT REVIEW DIALOG ---
    if (showReportReviewDialog != null) {
        val targetReview = showReportReviewDialog!!

        AlertDialog(
            onDismissRequest = { showReportReviewDialog = null },
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
                    text = "Report Customer Review",
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
                        text = "Why are you reporting the review by ${targetReview.customerName} on ${goat.name}?",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    ReportReason.values().forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { reviewReportSelectedReason = reason }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = reviewReportSelectedReason == reason,
                                onClick = { reviewReportSelectedReason = reason }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = reason.displayName, fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = reviewReportDetails,
                        onValueChange = { reviewReportDetails = it },
                        label = { Text("Details / Explanation *") },
                        placeholder = { Text("Explain why this review violates platform standards...") },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val desc = reviewReportDetails.ifBlank { reviewReportSelectedReason.displayName }
                        onSubmitReport(
                            "REVIEW",
                            targetReview.id,
                            "Review on ${goat.name} by ${targetReview.customerName}",
                            reviewReportSelectedReason,
                            desc,
                            null
                        )
                        onReportReview(targetReview.id, desc)
                        showReportReviewDialog = null
                        reviewReportDetails = ""
                        Toast.makeText(context, "Review reported to Super Admin moderation team.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Submit Report", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportReviewDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- FULLSCREEN REVIEW PHOTO PREVIEW ---
    if (selectedReviewPhotoForPreview != null) {
        Dialog(
            onDismissRequest = { selectedReviewPhotoForPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(selectedReviewPhotoForPreview)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Review photo preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { selectedReviewPhotoForPreview = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Preview",
                        tint = Color.White
                    )
                }
            }
        }
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

@Composable
private fun RatingBarRow(stars: String, percentage: Float) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Text(
            text = stars,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        LinearProgressIndicator(
            progress = { percentage },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFFF39C12),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${(percentage * 100).toInt()}%",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
    }
}
