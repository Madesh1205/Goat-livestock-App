package com.example.util

/**
 * Utility for validating and formatting Indian mobile phone numbers.
 * Requirements:
 * - Exactly 10 digits.
 * - Must start with 6, 7, 8, or 9.
 * - Allows clean extraction from common user input formats (e.g., "+91 98765 43210", "09876543210").
 */
object PhoneValidator {

    private val INDIAN_MOBILE_REGEX = Regex("^[6-9]\\d{9}$")

    /**
     * Strips whitespace, dashes, dots, brackets, commas.
     */
    fun clean(rawPhone: String): String {
        return rawPhone.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")
            .replace(".", "")
            .replace(",", "")
    }

    /**
     * Extracts the raw 10-digit national number if prefixed with +91, 91, or 0.
     */
    fun extract10Digits(rawPhone: String): String {
        var cleaned = clean(rawPhone)
        if (cleaned.startsWith("+91")) {
            cleaned = cleaned.removePrefix("+91")
        } else if (cleaned.startsWith("+")) {
            cleaned = cleaned.removePrefix("+")
        } else if (cleaned.startsWith("0") && cleaned.length == 11) {
            cleaned = cleaned.removePrefix("0")
        } else if (cleaned.startsWith("91") && cleaned.length == 12) {
            cleaned = cleaned.removePrefix("91")
        }
        return cleaned
    }

    /**
     * Validates whether the given string is a valid 10-digit Indian mobile number.
     * Must be exactly 10 digits and start with 6, 7, 8, or 9.
     */
    fun isValid(phone: String): Boolean {
        if (phone.isBlank()) return false
        val digits = extract10Digits(phone)
        return INDIAN_MOBILE_REGEX.matches(digits)
    }

    /**
     * Normalizes the phone number into standard 10-digit format (or +91 prefixed if needed).
     * Returns standard 10 digits if valid, or cleaned digits.
     */
    fun normalize(phone: String): String {
        val digits = extract10Digits(phone)
        return if (INDIAN_MOBILE_REGEX.matches(digits)) {
            digits
        } else {
            clean(phone)
        }
    }

    /**
     * Formats the phone number for readable UI display (e.g., +91 98765 43210).
     */
    fun formatDisplay(phone: String): String {
        val digits = extract10Digits(phone)
        if (digits.length == 10 && INDIAN_MOBILE_REGEX.matches(digits)) {
            val part1 = digits.substring(0, 5)
            val part2 = digits.substring(5)
            return "+91 $part1 $part2"
        }
        return phone.trim().ifBlank { "Not provided" }
    }

    /**
     * Returns a human-friendly error message if the phone is invalid, or null if valid.
     */
    fun getValidationErrorMessage(phone: String): String? {
        val trimmed = phone.trim()
        if (trimmed.isBlank()) {
            return "Phone number is required."
        }
        val cleaned = clean(trimmed)
        if (cleaned.any { !it.isDigit() && it != '+' }) {
            return "Phone number can only contain digits."
        }
        val digits = extract10Digits(trimmed)
        if (digits.length < 10) {
            return "Phone number must be exactly 10 digits."
        }
        if (digits.length > 10) {
            return "Phone number must be exactly 10 digits (excluding country code)."
        }
        if (!digits.first().let { it in '6'..'9' }) {
            return "Indian mobile numbers must start with 6, 7, 8, or 9."
        }
        if (!INDIAN_MOBILE_REGEX.matches(digits)) {
            return "Please enter a valid 10-digit mobile number."
        }
        return null
    }
}
