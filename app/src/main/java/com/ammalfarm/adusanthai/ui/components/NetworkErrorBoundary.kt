package com.ammalfarm.adusanthai.ui.components

import androidx.compose.runtime.Composable

@Composable
fun NetworkErrorBoundary(content: @Composable () -> Unit) {
    content()
}
