package com.ammalfarm.adusanthai.core.util

data class GoatFormValidationResult(
    val isValid: Boolean,
    val nameError: String? = null,
    val breedError: String? = null,
    val ageError: String? = null,
    val weightError: String? = null,
    val priceError: String? = null,
    val discountError: String? = null,
    val photoError: String? = null,
    val firstInvalidFieldIndex: Int = -1,
    val validPrice: Double? = null,
    val validDiscount: Double? = null
)

object GoatFormValidator {
    fun validate(name: String, breed: String, price: Double): String? {
        if (name.isBlank()) return "Goat name is required."
        if (breed.isBlank()) return "Breed is required."
        if (price <= 0.0) return "Price must be greater than zero."
        return null
    }

    fun validate(
        name: String,
        breed: String,
        age: String,
        weight: String,
        price: String,
        discountPercentage: String = "0",
        photos: List<String> = emptyList(),
        hasSubmitted: Boolean = true
    ): GoatFormValidationResult {
        val nameErr = if (name.isBlank()) "Goat Name is required" else null
        val breedErr = if (breed.isBlank()) "Breed is required" else null
        val parsedAge = age.toIntOrNull()
        val ageErr = if (age.isBlank() || parsedAge == null || parsedAge <= 0) "Age in months is required" else null
        val parsedWeight = weight.toDoubleOrNull()
        val weightErr = if (weight.isBlank() || parsedWeight == null || parsedWeight <= 0.0) "Weight in kg is required" else null
        val parsedPrice = price.toDoubleOrNull()
        val priceErr = if (price.isBlank() || parsedPrice == null || parsedPrice <= 0.0) "Price is required" else null
        val parsedDiscount = discountPercentage.toDoubleOrNull() ?: 0.0
        val discountErr = if (parsedDiscount < 0.0 || parsedDiscount > 100.0) "Invalid discount" else null
        val photoErr = if (photos.isEmpty()) "At least 1 photo is required" else null

        val firstIdx = when {
            nameErr != null -> 0
            breedErr != null -> 1
            ageErr != null || weightErr != null -> 3
            priceErr != null || discountErr != null -> 4
            photoErr != null -> 6
            else -> -1
        }
        val valid = firstIdx == -1
        return GoatFormValidationResult(
            isValid = valid,
            nameError = nameErr,
            breedError = breedErr,
            ageError = ageErr,
            weightError = weightErr,
            priceError = priceErr,
            discountError = discountErr,
            photoError = photoErr,
            firstInvalidFieldIndex = firstIdx,
            validPrice = if (valid) parsedPrice else null,
            validDiscount = if (valid) parsedDiscount else null
        )
    }

    fun isValid(
        name: String,
        breed: String,
        price: Double,
        weight: Double,
        age: Int
    ): Boolean {
        return name.trim().isNotEmpty() &&
                breed.trim().isNotEmpty() &&
                price > 0.0 &&
                weight > 0.0 &&
                age > 0
    }
}
