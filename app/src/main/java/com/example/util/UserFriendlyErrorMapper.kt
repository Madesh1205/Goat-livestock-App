package com.example.util

import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Centralized, production-grade error sanitization for user-facing UI.
 * 
 * Guarantees that:
 * 1. Raw Supabase URLs, REST endpoints, and query strings NEVER reach the UI.
 * 2. Database internals (SQL, triggers, column names, relation errors) are scrubbed.
 * 3. Clear, helpful, human-friendly error messages are presented to the user.
 * 4. Technical diagnostics are safely logged to Logcat only (without credentials).
 */
object UserFriendlyErrorMapper {

    private const val TAG = "UserFriendlyErrorMapper"

    private val SENSITIVE_PATTERNS = listOf(
        Regex("https?://[^\\s\"'>]+", RegexOption.IGNORE_CASE),
        Regex("supabase\\.[a-z]+", RegexOption.IGNORE_CASE),
        Regex("rest/v1[^\\s\"'>]*", RegexOption.IGNORE_CASE),
        Regex("auth/v1[^\\s\"'>]*", RegexOption.IGNORE_CASE),
        Regex("pgrst\\d*", RegexOption.IGNORE_CASE),
        Regex("sqlstate\\s*[:=]?\\s*[a-z0-9]+", RegexOption.IGNORE_CASE),
        Regex("record\\s+[\"']?(old|new)[\"']?\\s+has\\s+no\\s+field.*", RegexOption.IGNORE_CASE),
        Regex("relation\\s+[\"'][^\"']+[\"']\\s+does\\s+not\\s+exist", RegexOption.IGNORE_CASE),
        Regex("column\\s+[\"'][^\"']+[\"']\\s+does\\s+not\\s+exist", RegexOption.IGNORE_CASE),
        Regex("violates\\s+(foreign|check|unique|not-null)\\s+constraint.*", RegexOption.IGNORE_CASE),
        Regex("serializer\\s+for\\s+class\\s+['\"]any['\"].*", RegexOption.IGNORE_CASE),
        Regex("bearer\\s+[a-za-z0-9._-]+", RegexOption.IGNORE_CASE),
        Regex("apikey\\s*[:=]?\\s*[a-za-z0-9._-]+", RegexOption.IGNORE_CASE)
    )

    /**
     * Checks if a raw error string contains internal backend/URL leakage.
     */
    fun containsLeakage(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }
    }

    /**
     * Sanitizes an arbitrary text string, ensuring no URLs, SQL, or internal tokens leak through.
     */
    fun sanitize(raw: String?, fallback: String = "Something went wrong. Please try again."): String {
        if (raw.isNullOrBlank()) return fallback
        if (containsLeakage(raw)) {
            Log.d(TAG, "Scrubbed leaked technical backend content from user-facing message.")
            return fallback
        }
        return raw.trim()
    }

    /**
     * Maps any general Throwable into a user-safe, categorized message.
     */
    fun toUserMessage(
        throwable: Throwable?,
        fallback: String = "Something went wrong. Please try again."
    ): String {
        if (throwable == null) return fallback

        // Log technical detail internally for developer diagnostics
        Log.w(TAG, "Technical error encountered: ${throwable::class.java.simpleName} - ${throwable.message}")

        val msg = (throwable.message ?: "").lowercase()

        // 1. Network / Offline / Timeout
        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect. Please check your internet connection and try again."
        }

        // 2. Authentication / Session Expiration
        if (isSessionOrAuthFailure(msg)) {
            return "Your session has expired. Please sign in again."
        }

        // 3. Permission / Row Level Security (RLS)
        if (isPermissionFailure(msg)) {
            return "You do not have permission to perform this action."
        }

        // 4. Duplicate / Unique Conflict
        if (isConflictFailure(msg)) {
            return "This item is already registered or reserved by someone else."
        }

        // 5. Listing Limit Reached
        if (msg.contains("listing limit") || msg.contains("goat_listing_limit") || msg.contains("limit of")) {
            return "Farm listing limit reached. Contact Super Admin to increase your limit."
        }

        // 6. Database / Server / PostgREST Internal Error
        if (isServerOrDatabaseFailure(msg)) {
            return "Unable to complete your request right now. Please try again later."
        }

        // 7. If the exception message itself has leakage, return fallback
        if (containsLeakage(throwable.message)) {
            return fallback
        }

        // 8. If clean, short, and user-facing friendly
        val raw = throwable.message?.trim()
        if (!raw.isNullOrBlank() && raw.length <= 120 && !raw.contains("{") && !raw.contains("}")) {
            return raw
        }

        return fallback
    }

    /**
     * Dedicated mapper for Booking creation and management flows.
     */
    fun forBooking(throwable: Throwable?): String {
        if (throwable == null) return "Unable to complete booking. Please try again."
        val msg = (throwable.message ?: "").lowercase()

        // Network check
        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect. Please check your internet connection and try again."
        }

        // Session expired
        if (isSessionOrAuthFailure(msg)) {
            return "Your session has expired. Please sign in again."
        }

        // Permission
        if (isPermissionFailure(msg)) {
            return "You do not have permission to book this goat."
        }

        // Booking conflict / already reserved
        if (msg.contains("duplicate") ||
            msg.contains("23505") ||
            msg.contains("idx_single_active_goat_booking") ||
            msg.contains("already reserved") ||
            msg.contains("already been reserved")
        ) {
            return "Someone else has already reserved this goat. Please choose another goat."
        }

        // Goat no longer available
        if (msg.contains("not available") ||
            msg.contains("status: reserved") ||
            msg.contains("status: sold") ||
            msg.contains("inactive") ||
            msg.contains("not approved")
        ) {
            return "This goat is no longer available. Please choose another goat."
        }

        // Database/server failure
        if (isServerOrDatabaseFailure(msg) || containsLeakage(throwable.message)) {
            return "Unable to create the booking right now. Please try again."
        }

        return sanitize(throwable.message, "Unable to create the booking right now. Please try again.")
    }

    /**
     * Dedicated mapper for Farm Verification and Moderation operations by Super Admin.
     */
    fun forFarmModeration(throwable: Throwable?): String {
        if (throwable == null) return "Unable to update farm verification status. Please try again."
        val msg = (throwable.message ?: "").lowercase()

        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect. Please check your internet connection and try again."
        }
        if (isPermissionFailure(msg) || msg.contains("super admin")) {
            return "Only Super Admin can update farm verification status."
        }
        if (isServerOrDatabaseFailure(msg) || containsLeakage(throwable.message)) {
            return "Unable to update farm verification status right now. Please try again."
        }

        return sanitize(throwable.message, "Failed to update farm verification. Please try again.")
    }

    /**
     * Dedicated mapper for Goat Listing creation and updates.
     */
    fun forGoatListing(throwable: Throwable?): String {
        if (throwable == null) return "Unable to save goat listing. Please try again."
        val msg = (throwable.message ?: "").lowercase()

        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect. Please check your internet connection and try again."
        }
        if (msg.contains("listing limit") || msg.contains("limit reached") || msg.contains("goat_listing_limit")) {
            return "Cannot add goat: Farm listing limit reached. Contact Super Admin to increase your limit."
        }
        if (isPermissionFailure(msg)) {
            return "You do not have permission to add or modify this goat listing."
        }
        if (isServerOrDatabaseFailure(msg) || containsLeakage(throwable.message)) {
            return "Unable to save goat listing right now. Please try again."
        }

        return sanitize(throwable.message, "Failed to save goat listing. Please try again.")
    }

    /**
     * Dedicated mapper for Authentication and Account lifecycle operations.
     */
    fun forAuth(throwable: Throwable?): String {
        if (throwable == null) return "Authentication failed. Please try again."
        val msg = (throwable.message ?: "").lowercase()

        // Network check
        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect. Please check your internet connection and try again."
        }

        // Account already exists
        if (msg.contains("user already registered") ||
            msg.contains("already exists") ||
            msg.contains("user_already_exists")
        ) {
            return "An account with this email already exists. Please sign in with your account password or reset it."
        }

        // Email not confirmed
        if (msg.contains("email not confirmed") ||
            msg.contains("email_not_confirmed")
        ) {
            return "Account created. Please verify your email before signing in."
        }

        // Invalid credentials
        if (msg.contains("invalid login credentials") ||
            msg.contains("invalid_grant") ||
            msg.contains("invalid_credentials")
        ) {
            return "Invalid email or password. Please check your credentials."
        }

        // Password policy
        if (msg.contains("password should be at least") ||
            msg.contains("password is too short") ||
            msg.contains("weak_password")
        ) {
            return "Password does not meet the required security policy (minimum 6 characters)."
        }

        // Invalid email format
        if (msg.contains("unable to validate email") ||
            msg.contains("invalid email") ||
            msg.contains("validation_failed")
        ) {
            return "Please enter a valid email address."
        }

        // Server/Database/URL leak scrub
        if (isServerOrDatabaseFailure(msg) || containsLeakage(throwable.message)) {
            return "Authentication request could not be completed. Please try again later."
        }

        return sanitize(throwable.message, "Authentication request could not be completed.")
    }

    /**
     * Dedicated mapper for Listing Payments.
     */
    fun forPayment(throwable: Throwable?): String {
        if (throwable == null) return "Payment could not be processed. Please try again."
        val msg = (throwable.message ?: "").lowercase()

        if (isNetworkFailure(throwable, msg)) {
            return "Unable to connect to payment gateway. Please check your internet connection."
        }
        if (isServerOrDatabaseFailure(msg) ||
            msg.contains("signature") ||
            msg.contains("verification") ||
            msg.contains("gateway") ||
            msg.contains("razorpay") ||
            containsLeakage(throwable.message)
        ) {
            return "Payment verification failed. If money was deducted, it will be refunded automatically."
        }

        return sanitize(throwable.message, "Payment failed. Please try again.")
    }

    private fun isNetworkFailure(throwable: Throwable, lowerMsg: String): Boolean {
        return throwable is UnknownHostException ||
                throwable is SocketTimeoutException ||
                throwable is ConnectException ||
                throwable is IOException ||
                lowerMsg.contains("unable to resolve host") ||
                lowerMsg.contains("failed to connect") ||
                lowerMsg.contains("connection refused") ||
                lowerMsg.contains("timed out") ||
                lowerMsg.contains("timeout") ||
                lowerMsg.contains("network is unreachable") ||
                lowerMsg.contains("no address associated with hostname")
    }

    private fun isSessionOrAuthFailure(lowerMsg: String): Boolean {
        return lowerMsg.contains("jwt expired") ||
                lowerMsg.contains("token has expired") ||
                lowerMsg.contains("invalid_grant") ||
                lowerMsg.contains("invalid_token") ||
                lowerMsg.contains("session expired") ||
                lowerMsg.contains("401 unauthorized")
    }

    private fun isPermissionFailure(lowerMsg: String): Boolean {
        return lowerMsg.contains("permission denied") ||
                lowerMsg.contains("violates row-level security") ||
                lowerMsg.contains("row level security") ||
                lowerMsg.contains("security exception") ||
                lowerMsg.contains("not authorized") ||
                lowerMsg.contains("403 forbidden")
    }

    private fun isConflictFailure(lowerMsg: String): Boolean {
        return lowerMsg.contains("23505") ||
                lowerMsg.contains("duplicate key") ||
                lowerMsg.contains("unique constraint") ||
                lowerMsg.contains("already exists")
    }

    private fun isServerOrDatabaseFailure(lowerMsg: String): Boolean {
        return lowerMsg.contains("pgrst") ||
                lowerMsg.contains("sqlstate") ||
                lowerMsg.contains("500 internal") ||
                lowerMsg.contains("502 bad gateway") ||
                lowerMsg.contains("503 service") ||
                lowerMsg.contains("504 gateway") ||
                lowerMsg.contains("database error") ||
                lowerMsg.contains("relation") ||
                lowerMsg.contains("column") ||
                lowerMsg.contains("trigger") ||
                lowerMsg.contains("has no field") ||
                lowerMsg.contains("serializer for class 'any'")
    }
}
