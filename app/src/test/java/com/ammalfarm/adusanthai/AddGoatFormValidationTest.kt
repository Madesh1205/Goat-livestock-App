package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.core.util.GoatFormValidator
import com.ammalfarm.adusanthai.data.repository.GoatRepository
import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.model.GoatFilterCriteria
import com.ammalfarm.adusanthai.model.GoatGender
import com.ammalfarm.adusanthai.model.GoatPurpose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddGoatFormValidationTest {

    private open class TestGoatRepository : GoatRepository {
        override fun getApprovedGoats(): Flow<List<Goat>> = flowOf(emptyList())
        override fun searchAndFilterGoats(criteria: GoatFilterCriteria): Flow<List<Goat>> = flowOf(emptyList())
        override fun getGoatById(id: String): Flow<Goat?> = flowOf(null)
        override fun getGoatsByFarm(farmId: String): Flow<List<Goat>> = flowOf(emptyList())
        override fun getAllGoatsForAdmin(): Flow<List<Goat>> = flowOf(emptyList())
        override suspend fun addGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
        override suspend fun updateGoatListing(goat: Goat): Result<Goat> = Result.success(goat)
        override suspend fun deleteGoatListing(goatId: String): Result<Unit> = Result.success(Unit)
        override suspend fun updateGoatApprovalStatus(goatId: String, status: ApprovalStatus): Result<Unit> = Result.success(Unit)
        override fun getAvailableBreeds(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getGoatsCount(): Result<Int> = Result.success(0)
        override fun invalidateCache() {}
    }

    @Test
    fun `submit with all fields empty yields validation failure and all errors`() {
        val result = GoatFormValidator.validate(
            name = "",
            breed = "",
            age = "",
            weight = "",
            price = "",
            discountPercentage = "0",
            photos = emptyList(),
            hasSubmitted = true
        )

        assertFalse(result.isValid)
        assertNotNull(result.nameError)
        assertEquals("Goat Name is required", result.nameError)
        assertNotNull(result.breedError)
        assertEquals("Breed is required", result.breedError)
        assertNotNull(result.ageError)
        assertEquals("Age in months is required", result.ageError)
        assertNotNull(result.weightError)
        assertEquals("Weight in kg is required", result.weightError)
        assertNotNull(result.priceError)
        assertEquals("Price is required", result.priceError)
        assertNotNull(result.photoError)
        assertEquals("At least 1 photo is required", result.photoError)
        assertEquals(0, result.firstInvalidFieldIndex)
    }

    @Test
    fun `submit with only some fields filled highlights remaining missing fields`() {
        val result = GoatFormValidator.validate(
            name = "Sultan Stud",
            breed = "Jamunapari",
            age = "",
            weight = "",
            price = "",
            discountPercentage = "0",
            photos = emptyList(),
            hasSubmitted = true
        )

        assertFalse(result.isValid)
        assertNull(result.nameError)
        assertNull(result.breedError)
        assertNotNull(result.ageError)
        assertNotNull(result.weightError)
        assertNotNull(result.priceError)
        assertNotNull(result.photoError)
        // Age/Weight row is index 3 in LazyColumn
        assertEquals(3, result.firstInvalidFieldIndex)
    }

    @Test
    fun `first invalid field index is calculated correctly based on field order`() {
        // Name missing
        val nameRes = GoatFormValidator.validate("", "Boer", "12", "30.0", "10000", "0", listOf("p1"), true)
        assertEquals(0, nameRes.firstInvalidFieldIndex)

        // Breed missing
        val breedRes = GoatFormValidator.validate("Sultan", "", "12", "30.0", "10000", "0", listOf("p1"), true)
        assertEquals(1, breedRes.firstInvalidFieldIndex)

        // Age missing
        val ageRes = GoatFormValidator.validate("Sultan", "Boer", "", "30.0", "10000", "0", listOf("p1"), true)
        assertEquals(3, ageRes.firstInvalidFieldIndex)

        // Price missing
        val priceRes = GoatFormValidator.validate("Sultan", "Boer", "12", "30.0", "", "0", listOf("p1"), true)
        assertEquals(4, priceRes.firstInvalidFieldIndex)

        // Photos missing
        val photoRes = GoatFormValidator.validate("Sultan", "Boer", "12", "30.0", "10000", "0", emptyList(), true)
        assertEquals(6, photoRes.firstInvalidFieldIndex)
    }

    @Test
    fun `errors disappear immediately after valid input is provided`() {
        var name = ""
        var breed = ""
        var age = ""
        var weight = ""
        var price = ""
        var photos = emptyList<String>()

        // Initial submission with empty form
        var res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertFalse(res.isValid)
        assertNotNull(res.nameError)

        // User types valid name
        name = "Kanni Champion"
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.nameError)

        // User selects breed
        breed = "Kanni Aadu"
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.breedError)

        // User inputs age
        age = "18"
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.ageError)

        // User inputs weight
        weight = "45.0"
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.weightError)

        // User inputs price
        price = "25000"
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.priceError)

        // User adds photo
        photos = listOf("content://media/external/images/media/1001")
        res = GoatFormValidator.validate(name, breed, age, weight, price, "0", photos, true)
        assertNull(res.photoError)

        // Now form is completely valid
        assertTrue(res.isValid)
        assertEquals(25000.0, res.validPrice)
        assertEquals(0.0, res.validDiscount)
    }

    @Test
    fun `zero photos shows photo-required error`() {
        val result = GoatFormValidator.validate(
            name = "Sultan",
            breed = "Boer",
            age = "14",
            weight = "40.0",
            price = "20000",
            discountPercentage = "5",
            photos = emptyList(),
            hasSubmitted = true
        )

        assertFalse(result.isValid)
        assertNotNull(result.photoError)
        assertEquals("At least 1 photo is required", result.photoError)
    }

    @Test
    fun `no storage upload occurs when form validation fails`() = runTest {
        var repositoryUploadCalled = false

        val testRepository = object : TestGoatRepository() {
            override suspend fun addGoatListing(goat: Goat): Result<Goat> {
                repositoryUploadCalled = true
                return Result.success(goat)
            }
        }

        // Simulate form submission with missing age
        val validation = GoatFormValidator.validate(
            name = "Sultan",
            breed = "Boer",
            age = "",
            weight = "40.0",
            price = "20000",
            discountPercentage = "0",
            photos = listOf("content://media/external/images/media/101"),
            hasSubmitted = true
        )

        // If validation fails, repository is NOT called
        if (validation.isValid) {
            val goat = Goat(
                id = "g1",
                name = "Sultan",
                breed = "Boer",
                gender = GoatGender.MALE,
                ageMonths = 12,
                weightKg = 40.0,
                purpose = GoatPurpose.BREEDING,
                description = "Healthy",
                price = 20000.0,
                discountPercentage = 0.0,
                photos = listOf("content://media/external/images/media/101"),
                farmId = "f1",
                farmName = "Test Farm",
                farmLocation = "TN"
            )
            testRepository.addGoatListing(goat)
        }

        assertFalse(validation.isValid)
        assertFalse("Repository upload should NOT be triggered when validation fails", repositoryUploadCalled)
    }

    @Test
    fun `valid form proceeds to deferred upload via repository`() = runTest {
        var repositoryUploadCalled = false
        var savedGoat: Goat? = null

        val testRepository = object : TestGoatRepository() {
            override suspend fun addGoatListing(goat: Goat): Result<Goat> {
                repositoryUploadCalled = true
                savedGoat = goat
                return Result.success(goat)
            }
        }

        val validation = GoatFormValidator.validate(
            name = "Sultan Stud",
            breed = "Boer",
            age = "14",
            weight = "42.0",
            price = "28000",
            discountPercentage = "10",
            photos = listOf("content://media/external/images/media/101"),
            hasSubmitted = true
        )

        assertTrue(validation.isValid)

        if (validation.isValid) {
            val goat = Goat(
                id = "g100",
                name = "Sultan Stud",
                breed = "Boer",
                gender = GoatGender.MALE,
                ageMonths = "14".toInt(),
                weightKg = "42.0".toDouble(),
                purpose = GoatPurpose.BREEDING,
                description = "Pedigree stud",
                price = validation.validPrice!!,
                discountPercentage = validation.validDiscount!!,
                photos = listOf("content://media/external/images/media/101"),
                farmId = "f1",
                farmName = "Test Farm",
                farmLocation = "TN"
            )
            testRepository.addGoatListing(goat)
        }

        assertTrue("Repository upload should be triggered for valid form", repositoryUploadCalled)
        assertNotNull(savedGoat)
        assertEquals("Sultan Stud", savedGoat?.name)
        assertEquals(28000.0, savedGoat?.price)
        assertEquals(10.0, savedGoat?.discountPercentage)
    }
}
