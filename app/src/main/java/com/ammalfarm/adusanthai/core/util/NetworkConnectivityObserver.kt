package com.ammalfarm.adusanthai.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * System Network Connectivity Monitor
 * Observes device network state changes and provides real-time online/offline status
 * to prevent unnecessary WebSocket reconnect loops and REST polling failures when offline.
 */
object NetworkConnectivityObserver {
    private const val TAG = "NetworkConnectivity"

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var isRegistered = false

    fun initialize(context: Context) {
        if (isRegistered) return
        try {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager = cm
            if (cm == null) return

            _isOnline.value = checkCurrentConnectivity(cm)

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network connection established")
                    _isOnline.value = true
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network connection lost")
                    _isOnline.value = checkCurrentConnectivity(cm)
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _isOnline.value = hasInternet
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
            isRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
            _isOnline.value = true
        }
    }

    fun isCurrentlyOnline(): Boolean {
        val cm = connectivityManager ?: return _isOnline.value
        return checkCurrentConnectivity(cm)
    }

    private fun checkCurrentConnectivity(cm: ConnectivityManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = cm.activeNetwork ?: return false
                val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = cm.activeNetworkInfo
                @Suppress("DEPRECATION")
                activeNetworkInfo != null && activeNetworkInfo.isConnected
            }
        } catch (e: Exception) {
            true
        }
    }
}
