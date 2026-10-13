package com.ammalfarm.adusanthai.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ammalfarm.adusanthai.R

/**
 * Deliberate, full-screen branded splash loading screen for Adu Santhai.
 * Uses a prominent, centered goat emblem on brand forest-green background
 * and a smooth, accessible linear progress bar with vibrant brand green accents.
 */
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier,
    statusText: String = "Connecting to Santhai..."
) {
    val infiniteTransition = rememberInfiniteTransition(label = "splash_loading_transition")
    val progressAnimation by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "splash_progress"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF095A37),
                        Color(0xFF064B2D),
                        Color(0xFF032B1A)
                    )
                )
            )
            .testTag("splash_screen_container")
    ) {
        // Centered Brand Content with Large Emblem and Typography
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Prominent, uncropped brand emblem
            Image(
                painter = painterResource(id = R.drawable.ic_splash_emblem),
                contentDescription = "Adu Santhai Emblem",
                modifier = Modifier
                    .size(160.dp)
                    .testTag("splash_brand_emblem"),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "ADU SANTHAI",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp,
                color = Color(0xFFF7FAF4),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "AMMAL FARM • LIVESTOCK MARKETPLACE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = Color(0xFFC5A059), // Rich gold brand accent
                textAlign = TextAlign.Center
            )
        }

        // Bottom Loading Progress Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 36.dp, vertical = 52.dp)
                .widthIn(max = 340.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LinearProgressIndicator(
                progress = { progressAnimation },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .testTag("splash_linear_progress_bar"),
                color = Color(0xFF10B981), // Vibrant brand green accent
                trackColor = Color(0x3310B981) // Translucent green track
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = statusText,
                color = Color(0xFFD1FAE5),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}
