package com.example.core.util

data class GoatFormValidationResult(
    val isValid: Boolean,
    val nameError: String? = null,
    val breedError: String? = null,
    val ageError: String? = null,
    val weightError: String? = null,
    val priceError: String? = null,
    val discountError: String? = null,
    val photoError: String? = null,
    val validPrice: Double? = null,
    val validDiscount: Double? = null,
    val firstInvalidFieldIndex: Int? = null
)

object GoatFormValidator {

    fun validate(
        name: String,
        breed: String,
        age: String,
        weight: String,
        price: String,
        discountPercentage: String,
        photos: List<String>,
        hasSubmitted: Boolean
    ): GoatFormValidationResult {
        val priceRes = PriceUtils.validatePrice(price)
        val discountRes = PriceUtils.validateDiscount(discountPercentage)

        var validPrice: Double? = (priceRes as? PriceUtils.PriceValidationResult.Valid)?.price
        var validDiscount: Double? = (discountRes as? PriceUtils.DiscountValidationResult.Valid)?.discount ?: 0.0

        if (!hasSubmitted) {
            return GoatFormValidationResult(
                isValid = false,
                validPrice = validPrice,
                validDiscount = validDiscount
            )
        }

        var nameErr: String? = null
        var breedErr: String? = null
        var ageErr: String? = null
        var weightErr: String? = null
        var priceErr: String? = null
        var discountErr: String? = null
        var photoErr: String? = null

        // 1. Name validation
        if (name.trim().isEmpty()) {
            nameErr = "Goat Name is required"
        }

        // 2. Breed validation
        if (breed.trim().isEmpty()) {
            breedErr = "Breed is required"
        }

        // 3. Age validation
        val ageTrimmed = age.trim()
        if (ageTrimmed.isEmpty()) {
            ageErr = "Age in months is required"
        } else {
            val ageInt = ageTrimmed.toIntOrNull()
            if (ageInt == null || ageInt <= 0) {
                ageErr = "Age must be a valid positive number"
            }
        }

        // 4. Weight validation
        val weightTrimmed = weight.trim()
        if (weightTrimmed.isEmpty()) {
            weightErr = "Weight in kg is required"
        } else {
            val weightDbl = weightTrimmed.toDoubleOrNull()
            if (weightDbl == null || weightDbl <= 0.0) {
                weightErr = "Weight must be a valid positive number"
            }
        }

        // 5. Price & Discount validation
        if (price.trim().isEmpty()) {
            priceErr = "Price is required"
            validPrice = null
        } else {
            when (priceRes) {
                is PriceUtils.PriceValidationResult.Error -> {
                    priceErr = priceRes.message
                    validPrice = null
                }
                is PriceUtils.PriceValidationResult.Valid -> {
                    validPrice = priceRes.price
                }
            }
        }

        if (discountPercentage.trim().isNotEmpty()) {
            when (discountRes) {
                is PriceUtils.DiscountValidationResult.Error -> {
                    discountErr = discountRes.message
                    validDiscount = null
                }
                is PriceUtils.DiscountValidationResult.Valid -> {
                    validDiscount = discountRes.discount
                }
            }
        } else {
            validDiscount = 0.0
        }

        // 6. Photo validation (Minimum 1 photo)
        if (photos.isEmpty()) {
            photoErr = "At least 1 photo is required"
        }

        val isValid = nameErr == null &&
                breedErr == null &&
                ageErr == null &&
                weightErr == null &&
                priceErr == null &&
                discountErr == null &&
                photoErr == null

        val firstInvalidIndex = when {
            nameErr != null -> 0
            breedErr != null -> 1
            ageErr != null || weightErr != null -> 3
            priceErr != null || discountErr != null -> 4
            photoErr != null -> 6
            else -> null
        }

        return GoatFormValidationResult(
            isValid = isValid,
            nameError = nameErr,
            breedError = breedErr,
            ageError = ageErr,
            weightError = weightErr,
            priceError = priceErr,
            discountError = discountErr,
            photoError = photoErr,
            validPrice = validPrice,
            validDiscount = validDiscount,
            firstInvalidFieldIndex = firstInvalidIndex
        )
    }
}
