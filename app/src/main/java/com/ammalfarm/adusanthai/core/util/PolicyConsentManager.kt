package com.ammalfarm.adusanthai.core.util

import android.content.Context
import android.content.SharedPreferences

class PolicyConsentManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("policy_consent_prefs", Context.MODE_PRIVATE)

    fun hasAcceptedPolicies(): Boolean {
        return prefs.getBoolean("accepted_policies", false)
    }

    fun setPoliciesAccepted(accepted: Boolean) {
        prefs.edit().putBoolean("accepted_policies", accepted).apply()
    }

    fun getAcceptedDateFormatted(): String {
        return "Today"
    }

    companion object {
        fun hasAcceptedPolicies(context: Context): Boolean {
            return PolicyConsentManager(context).hasAcceptedPolicies()
        }

        fun setPoliciesAccepted(context: Context, accepted: Boolean) {
            PolicyConsentManager(context).setPoliciesAccepted(accepted)
        }

        fun getAcceptedDateFormatted(context: Context): String {
            return PolicyConsentManager(context).getAcceptedDateFormatted()
        }
    }
}
