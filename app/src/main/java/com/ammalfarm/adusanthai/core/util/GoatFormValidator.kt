package com.ammalfarm.adusanthai.core.util

object GoatFormValidator {
    fun validate(name: String, breed: String, price: Double): String? {
        if (name.isBlank()) return "Goat name is required."
        if (breed.isBlank()) return "Breed is required."
        if (price <= 0.0) return "Price must be greater than zero."
        return null
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
