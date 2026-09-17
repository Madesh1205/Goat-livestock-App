package com.example.core.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages the user's legal consent state for Privacy Policy and Terms & Conditions.
 * Persists locally via SharedPreferences so users only need to accept once on first sign-in.
 */
class PolicyConsentManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isAccepted = MutableStateFlow(hasAcceptedPolicies())
    val isAccepted: StateFlow<Boolean> = _isAccepted.asStateFlow()

    fun hasAcceptedPolicies(): Boolean {
        return prefs.getBoolean(KEY_POLICIES_ACCEPTED, false)
    }

    fun setPoliciesAccepted(accepted: Boolean = true) {
        prefs.edit()
            .putBoolean(KEY_POLICIES_ACCEPTED, accepted)
            .putLong(KEY_ACCEPTED_TIMESTAMP, System.currentTimeMillis())
            .putString(KEY_POLICY_VERSION, CURRENT_VERSION)
            .apply()
        _isAccepted.value = accepted
    }

    fun getAcceptedDateFormatted(): String? {
        val timestamp = prefs.getLong(KEY_ACCEPTED_TIMESTAMP, 0L)
        if (timestamp == 0L) return null
        return try {
            val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
            sdf.format(Date(timestamp))
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME = "ammal_policy_consent_prefs"
        private const val KEY_POLICIES_ACCEPTED = "policies_accepted"
        private const val KEY_ACCEPTED_TIMESTAMP = "policies_accepted_timestamp"
        private const val KEY_POLICY_VERSION = "policies_version"
        const val CURRENT_VERSION = "2026.09"
    }
}
