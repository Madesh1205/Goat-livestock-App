package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

/**
 * Adu Santhai Official Logo Component.
 * Displays the authentic Adu Santhai emblem badge with rich green, cream, gold accents and optional typography.
 */
@Composable
fun AduSanthaiLogoBadge(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    showBorder: Boolean = false,
    elevation: Dp = 4.dp
) {
    val shape = RoundedCornerShape((size.value * 0.22f).dp)
    Box(
        modifier = modifier
            .size(size)
            .shadow(elevation, shape = shape, clip = false)
            .clip(shape)
            .then(
                if (showBorder) {
                    Modifier.border(1.5.dp, Color(0xFFC5A059), shape)
                } else Modifier
            )
            .testTag("adu_santhai_logo_badge"),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_adu_santhai_logo),
            contentDescription = "Adu Santhai Logo",
            modifier = Modifier.size(size)
        )
    }
}

/**
 * Adu Santhai Brand Header Composable (Horizontal layout for TopBars).
 */
@Composable
fun AduSanthaiTopBarBrand(
    modifier: Modifier = Modifier,
    badgeSize: Dp = 38.dp,
    onBrandClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.testTag("adu_santhai_topbar_brand"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AduSanthaiLogoBadge(
            size = badgeSize,
            elevation = 2.dp
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "ADU",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = Color(0xFF145A32)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "SANTHAI",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "AMMAL FARM • LIVESTOCK MARKETPLACE",
                fontSize = 8.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = Color(0xFFD97706)
            )
        }
    }
}
