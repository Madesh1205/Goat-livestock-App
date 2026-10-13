package com.ammalfarm.adusanthai.core.util

/**
 * Standard list of districts and breeding regions across Tamil Nadu and neighbouring southern farming hubs.
 */
object FarmLocations {
    val TAMIL_NADU_DISTRICTS = listOf(
        "Ambur",
        "Ariyalur",
        "Attur",
        "Chengalpattu",
        "Chennai",
        "Coimbatore",
        "Cuddalore",
        "Dharmapuri",
        "Dindigul",
        "Erode",
        "Hosur",
        "Kallakurichi",
        "Kanchipuram",
        "Kanyakumari",
        "Karur",
        "Kovilpatti",
        "Krishnagiri",
        "Madurai",
        "Mayiladuthurai",
        "Nagapattinam",
        "Namakkal",
        "Nilgiris",
        "Palani",
        "Perambalur",
        "Pollachi",
        "Puducherry",
        "Pudukkottai",
        "Ramanathapuram",
        "Ranipet",
        "Salem",
        "Sivaganga",
        "Tenkasi",
        "Thanjavur",
        "Theni",
        "Thoothukudi (Tuticorin)",
        "Tiruchirappalli (Trichy)",
        "Tirunelveli",
        "Tirupathur",
        "Tiruppur",
        "Tiruvallur",
        "Tiruvannamalai",
        "Tiruvarur",
        "Vellore",
        "Viluppuram",
        "Virudhunagar"
    )

    /**
     * Popular filter locations prioritized for quick discovery
     */
    val POPULAR_LOCATIONS = listOf(
        "Vellore",
        "Trichy",
        "Madurai",
        "Coimbatore",
        "Salem",
        "Erode",
        "Tiruppur",
        "Dindigul",
        "Thanjavur",
        "Tirunelveli",
        "Namakkal",
        "Theni",
        "Dharmapuri",
        "Krishnagiri",
        "Hosur",
        "Pollachi",
        "Kanyakumari",
        "Virudhunagar",
        "Tuticorin",
        "Cuddalore",
        "Karur",
        "Chennai",
        "Pudukkottai",
        "Viluppuram"
    )

    private val STATE_PATTERNS = listOf(
        "(?i)\\btamil\\s*nadu\\b",
        "(?i)\\btamilnadu\\b",
        "(?i)\\btn\\b",
        "(?i)\\bindia\\b",
        "(?i)\\bstate\\b"
    )

    /**
     * Cleans raw location strings (e.g. "Vellore, Tamil Nadu", "(vellore,tamildu)")
     * to extract only the clean district name (e.g. "Vellore").
     */
    fun cleanDistrictName(rawLocation: String?): String {
        if (rawLocation.isNullOrBlank()) return ""

        var cleaned = rawLocation.trim()

        // Strip known state patterns
        for (pattern in STATE_PATTERNS) {
            cleaned = cleaned.replace(Regex(pattern), "")
        }

        // Clean leftover punctuation / parentheses
        cleaned = cleaned
            .replace("()", "")
            .replace("[]", "")
            .replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9() ]+$"), "")
            .trim()

        if (cleaned.isBlank()) return ""

        // Split by comma or slash to isolate town/district name from leftover tokens
        val parts = cleaned.split(",", "/", "-").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""

        // Try exact/fuzzy match against standard Tamil Nadu districts first
        for (part in parts) {
            val matched = TAMIL_NADU_DISTRICTS.firstOrNull { district ->
                district.equals(part, ignoreCase = true) ||
                        district.lowercase().split(" ").first() == part.lowercase()
            }
            if (matched != null) return matched
        }

        // Return first non-empty clean part if no standard district matches
        val bestPart = parts.firstOrNull { part ->
            val lower = part.lowercase()
            !lower.contains("tamil") && lower != "tn" && lower != "india" && lower != "state"
        } ?: parts.first()

        return bestPart.replace(Regex("^[^a-zA-Z0-9]+|[^a-zA-Z0-9() ]+$"), "").trim()
    }

    /**
     * Extracts distinct, clean district names from a list of raw location strings.
     */
    fun extractActiveDistricts(rawLocations: List<String?>): List<String> {
        val distinctDistricts = mutableSetOf<String>()
        rawLocations.forEach { raw ->
            val clean = cleanDistrictName(raw)
            if (clean.isNotBlank()) {
                distinctDistricts.add(clean)
            }
        }
        return distinctDistricts.sorted()
    }
}

