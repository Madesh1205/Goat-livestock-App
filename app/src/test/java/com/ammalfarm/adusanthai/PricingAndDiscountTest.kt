package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.core.util.PriceUtils
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.data.dto.SEED_AMMAL_FARM_UUID
import com.ammalfarm.adusanthai.model.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PricingAndDiscountTest {

    // --- 1. calculateFinalPrice(28000, 0) == 28000 ---
    @Test
    fun `calculateFinalPrice with zero discount returns original price`() {
        val result = PriceUtils.calculateFinalPrice(28000.0, 0.0)
        assertEquals(28000.0, result, 0.001)
    }

    // --- 2. calculateFinalPrice(28000, 10) == 25200 ---
    @Test
    fun `calculateFinalPrice with 10 percent discount returns 25200`() {
        val result = PriceUtils.calculateFinalPrice(28000.0, 10.0)
        assertEquals(25200.0, result, 0.001)
    }

    // --- 3. calculateFinalPrice(28000, 20) == 22400 ---
    @Test
    fun `calculateFinalPrice with 20 percent discount returns 22400`() {
        val result = PriceUtils.calculateFinalPrice(28000.0, 20.0)
        assertEquals(22400.0, result, 0.001)
    }

    // --- 4. calculateFinalPrice(10000, 15) == 8500 ---
    @Test
    fun `calculateFinalPrice with 15 percent discount returns 8500`() {
        val result = PriceUtils.calculateFinalPrice(10000.0, 15.0)
        assertEquals(8500.0, result, 0.001)
    }

    // --- 5. calculateFinalPrice(10000, 100) == 0 ---
    @Test
    fun `calculateFinalPrice with 100 percent discount returns 0`() {
        val result = PriceUtils.calculateFinalPrice(10000.0, 100.0)
        assertEquals(0.0, result, 0.001)
    }

    // --- 6. calculateFinalPrice(10000, -5) clamped to 0 discount ---
    @Test
    fun `calculateFinalPrice with negative discount clamps to 0 percent discount`() {
        val result = PriceUtils.calculateFinalPrice(10000.0, -5.0)
        assertEquals(10000.0, result, 0.001)
    }

    // --- 7. calculateFinalPrice(-1000, 10) returns 0 for negative price ---
    @Test
    fun `calculateFinalPrice with negative price safely returns 0`() {
        val result = PriceUtils.calculateFinalPrice(-1000.0, 10.0)
        assertEquals(0.0, result, 0.001)
    }

    // --- 8. calculateFinalPrice(10000, 120) clamped to 100 percent discount ---
    @Test
    fun `calculateFinalPrice with discount greater than 100 clamps to 100 percent`() {
        val result = PriceUtils.calculateFinalPrice(10000.0, 120.0)
        assertEquals(0.0, result, 0.001)
    }

    // --- 9. Decimal discounts (e.g. 12.5%) calculate correctly ---
    @Test
    fun `calculateFinalPrice with decimal discount calculates accurately`() {
        // 10,000 * (1 - 0.125) = 8,750
        val result = PriceUtils.calculateFinalPrice(10000.0, 12.5)
        assertEquals(8750.0, result, 0.001)

        // 28,000 * (1 - 0.125) = 24,500
        val result2 = PriceUtils.calculateFinalPrice(28000.0, 12.5)
        assertEquals(24500.0, result2, 0.001)
    }

    // --- 10. Goat.finalPrice reflects correct discount ---
    @Test
    fun `Goat domain model finalPrice reflects correct discount`() {
        val goat = createSampleGoat(price = 28000.0, discount = 20.0)
        assertEquals(22400.0, goat.finalPrice, 0.001)
        assertEquals(5600.0, goat.savingsAmount, 0.001)
    }

    // --- 11. Goat.hasDiscount false when discount is 0 ---
    @Test
    fun `Goat hasDiscount is false when discount is zero`() {
        val goat = createSampleGoat(price = 28000.0, discount = 0.0)
        assertFalse(goat.hasDiscount)
        assertEquals(28000.0, goat.finalPrice, 0.001)
        assertEquals(0.0, goat.savingsAmount, 0.001)
    }

    // --- 12. Goat.hasDiscount true when discount > 0 and price > 0 ---
    @Test
    fun `Goat hasDiscount is true when discount is positive and price is positive`() {
        val goat = createSampleGoat(price = 28000.0, discount = 20.0)
        assertTrue(goat.hasDiscount)
    }

    // --- 13. GoatDto correctly roundtrips price and discount ---
    @Test
    fun `GoatDto correctly serializes and deserializes price and discount`() {
        val originalGoat = createSampleGoat(price = 28000.0, discount = 20.0)
        val dto = GoatDto.fromDomain(originalGoat)

        assertEquals(28000.0, dto.price, 0.001)
        assertEquals(20.0, dto.discountPercentage, 0.001)

        val reconstructed = dto.toDomain()
        assertEquals(originalGoat.price, reconstructed.price, 0.001)
        assertEquals(originalGoat.discountPercentage, reconstructed.discountPercentage, 0.001)
        assertEquals(22400.0, reconstructed.finalPrice, 0.001)
        assertTrue(reconstructed.hasDiscount)
    }

    // --- 14. Booking amount matches finalPrice, not marked price ---
    @Test
    fun `Booking amount uses effective final price after discount`() {
        val goat = createSampleGoat(price = 28000.0, discount = 20.0)
        val booking = Booking(
            id = UUID.randomUUID().toString(),
            goatId = goat.id,
            goatName = goat.name,
            goatBreed = goat.breed,
            goatPhoto = "",
            customerId = UUID.randomUUID().toString(),
            customerName = "Test Buyer",
            customerPhone = "9876543210",
            farmId = goat.farmId,
            farmName = goat.farmName,
            amount = goat.finalPrice // Authoritative customer payable amount
        )

        assertEquals(22400.0, booking.amount, 0.001)
        assertEquals("₹22,400", booking.formattedAmount)
    }

    // --- 15. Search / filter respects finalPrice ---
    @Test
    fun `Filter maxPrice criteria respects final discounted price instead of marked price`() {
        // Goat marked at ₹30,000 with 20% discount => ₹24,000 effective price
        val goat = createSampleGoat(price = 30000.0, discount = 20.0)
        assertEquals(24000.0, goat.finalPrice, 0.001)

        // Customer filters with maxPrice = ₹25,000
        val criteriaWithUnderBudget = GoatFilterCriteria(
            maxPrice = 25000.0
        )
        // Should match because ₹24,000 <= ₹25,000
        assertTrue("Goat should match maxPrice 25000 because final price is 24000", criteriaWithUnderBudget.matches(goat))

        // Customer filters with maxPrice = ₹23,000
        val criteriaWithTooLowBudget = GoatFilterCriteria(
            maxPrice = 23000.0
        )
        // Should NOT match because ₹24,000 > ₹23,000
        assertFalse("Goat should not match maxPrice 23000 because final price is 24000", criteriaWithTooLowBudget.matches(goat))
    }

    // --- 16. Sort by PRICE_LOW_TO_HIGH uses finalPrice ---
    @Test
    fun `Sort by price low to high sorts by final discounted price`() {
        // Goat A: marked ₹30,000, 20% off => final ₹24,000
        val goatA = createSampleGoat(price = 30000.0, discount = 20.0).copy(id = "A")
        // Goat B: marked ₹26,000, 0% off => final ₹26,000
        val goatB = createSampleGoat(price = 26000.0, discount = 0.0).copy(id = "B")
        // Goat C: marked ₹25,000, 10% off => final ₹22,500
        val goatC = createSampleGoat(price = 25000.0, discount = 10.0).copy(id = "C")

        val list = listOf(goatA, goatB, goatC)
        val sortedLowToHigh = list.sortedBy { it.finalPrice }

        assertEquals("C", sortedLowToHigh[0].id) // 22,500
        assertEquals("A", sortedLowToHigh[1].id) // 24,000
        assertEquals("B", sortedLowToHigh[2].id) // 26,000

        val sortedHighToLow = list.sortedByDescending { it.finalPrice }
        assertEquals("B", sortedHighToLow[0].id) // 26,000
        assertEquals("A", sortedHighToLow[1].id) // 24,000
        assertEquals("C", sortedHighToLow[2].id) // 22,500
    }

    // --- 17-21. Indian Currency Formatting ---
    @Test
    fun `Indian currency formatting produces exact required representations`() {
        assertEquals("₹28,000", PriceUtils.formatCurrency(28000.0))
        assertEquals("₹22,400", PriceUtils.formatCurrency(22400.0))
        assertEquals("₹5,600", PriceUtils.formatCurrency(5600.0))
        assertEquals("₹8,500", PriceUtils.formatCurrency(8500.0))
        assertEquals("₹0", PriceUtils.formatCurrency(0.0))
        assertEquals("₹1,00,000", PriceUtils.formatCurrency(100000.0))
    }

    // --- 22. Form validation for Price ---
    @Test
    fun `Price validation rejects negative, empty, and non-numeric inputs`() {
        assertTrue(PriceUtils.validatePrice("28000") is PriceUtils.PriceValidationResult.Valid)
        assertEquals(28000.0, (PriceUtils.validatePrice("28000") as PriceUtils.PriceValidationResult.Valid).price, 0.001)

        assertTrue(PriceUtils.validatePrice("0") is PriceUtils.PriceValidationResult.Valid)

        assertTrue(PriceUtils.validatePrice("-500") is PriceUtils.PriceValidationResult.Error)
        assertTrue(PriceUtils.validatePrice("") is PriceUtils.PriceValidationResult.Error)
        assertTrue(PriceUtils.validatePrice("   ") is PriceUtils.PriceValidationResult.Error)
        assertTrue(PriceUtils.validatePrice("abc") is PriceUtils.PriceValidationResult.Error)
    }

    // --- 23. Form validation for Discount ---
    @Test
    fun `Discount validation accepts 0 to 100 and rejects negatives and above 100`() {
        assertTrue(PriceUtils.validateDiscount("0") is PriceUtils.DiscountValidationResult.Valid)
        assertTrue(PriceUtils.validateDiscount("20") is PriceUtils.DiscountValidationResult.Valid)
        assertTrue(PriceUtils.validateDiscount("100") is PriceUtils.DiscountValidationResult.Valid)
        assertTrue(PriceUtils.validateDiscount("") is PriceUtils.DiscountValidationResult.Valid) // Blank defaults to 0%

        assertTrue(PriceUtils.validateDiscount("-5") is PriceUtils.DiscountValidationResult.Error)
        assertTrue(PriceUtils.validateDiscount("101") is PriceUtils.DiscountValidationResult.Error)
        assertTrue(PriceUtils.validateDiscount("xyz") is PriceUtils.DiscountValidationResult.Error)
    }

    // --- 24. Savings & Badges ---
    @Test
    fun `Savings calculation and discount badge formatting`() {
        val savings = PriceUtils.calculateSavings(28000.0, 20.0)
        assertEquals(5600.0, savings, 0.001)

        assertEquals("20% OFF", PriceUtils.formatDiscountBadge(20.0))
        assertEquals("12.5% OFF", PriceUtils.formatDiscountBadge(12.5))
        assertEquals("20%", PriceUtils.formatDiscountPercent(20.0))
        assertEquals("12.5%", PriceUtils.formatDiscountPercent(12.5))
    }

    // --- 28. GoatDto JSON serialization excludes transient listing_fee columns ---
    @Test
    fun `GoatDto serialization omits listing_fee_paid and listing_fee_amount from JSON payload`() {
        val originalGoat = createSampleGoat(price = 25000.0, discount = 10.0)
        val dto = GoatDto.fromDomain(originalGoat)
        val json = Json.encodeToString(GoatDto.serializer(), dto)

        assertFalse("Serialized JSON must not contain listing_fee_paid", json.contains("listing_fee_paid"))
        assertFalse("Serialized JSON must not contain listing_fee_amount", json.contains("listing_fee_amount"))
        assertTrue("Serialized JSON must contain farm_id", json.contains("farm_id"))
        assertTrue("Serialized JSON must contain is_approved_by_admin", json.contains("is_approved_by_admin"))
    }

    // --- 29. GoatDto deserialization parses database JSON without listing_fee keys ---
    @Test
    fun `GoatDto cleanly deserializes database JSON response lacking listing_fee fields`() {
        val rawDbJson = """
            {
                "id": "8cc677e1-b501-4816-9e49-2b92c6b6c20c",
                "farm_id": "00000000-0000-0000-0000-000000000001",
                "goat_code": "GOAT-001",
                "name": "Kodi",
                "breed_id": null,
                "breed_name": "Kodi aadu",
                "gender": "MALE",
                "age_months": 14,
                "weight_kg": 42.0,
                "purpose": "BREEDING",
                "price": 28000.0,
                "status": "AVAILABLE",
                "description": "Healthy breeding pedigree goat.",
                "vaccination_status": "Fully Vaccinated",
                "is_approved_by_admin": true,
                "is_featured": false,
                "discount_percentage": 30.0
            }
        """.trimIndent()

        val parsed = Json.decodeFromString(GoatDto.serializer(), rawDbJson)
        assertEquals("8cc677e1-b501-4816-9e49-2b92c6b6c20c", parsed.id)
        assertEquals("GOAT-001", parsed.goatCode)
        assertTrue(parsed.isApprovedByAdmin)

        val domain = parsed.toDomain(resolvedFarmName = "Ammal Farm")
        assertEquals(ApprovalStatus.APPROVED, domain.approvalStatus)
        assertEquals(AvailabilityStatus.AVAILABLE, domain.availabilityStatus)
        assertEquals(19600.0, domain.finalPrice, 0.001)
    }

    private fun createSampleGoat(price: Double, discount: Double): Goat {
        return Goat(
            id = UUID.randomUUID().toString(),
            name = "Champion Boer",
            breed = "Boer",
            gender = GoatGender.MALE,
            ageMonths = 14,
            weightKg = 45.0,
            purpose = GoatPurpose.BREEDING,
            description = "Prime breeding stock",
            price = price,
            discountPercentage = discount,
            photos = listOf("https://example.com/goat.jpg"),
            farmId = SEED_AMMAL_FARM_UUID,
            farmName = "Ammal Farm",
            farmLocation = "Vellore, Tamil Nadu",
            availabilityStatus = AvailabilityStatus.AVAILABLE,
            approvalStatus = ApprovalStatus.APPROVED,
            listingFeePaid = true,
            listingFeeAmount = 0.0
        )
    }
}
