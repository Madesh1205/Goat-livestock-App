package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.util.PhoneValidator
import org.junit.Assert.*
import org.junit.Test

class PhoneNumberValidationTest {

    @Test
    fun testPhoneValidator_valid10DigitIndianNumbers() {
        // Valid 10-digit Indian numbers starting with 6, 7, 8, or 9
        assertTrue(PhoneValidator.isValid("9876543210"))
        assertTrue(PhoneValidator.isValid("8123456789"))
        assertTrue(PhoneValidator.isValid("7000000000"))
        assertTrue(PhoneValidator.isValid("6999999999"))

        // With standard Indian prefixes (+91, 0)
        assertTrue(PhoneValidator.isValid("+919876543210"))
        assertTrue(PhoneValidator.isValid("+91 98765 43210"))
        assertTrue(PhoneValidator.isValid("+91-98765-43210"))
        assertTrue(PhoneValidator.isValid("09876543210"))
    }

    @Test
    fun testPhoneValidator_invalidNumbers() {
        // Empty or blank
        assertFalse(PhoneValidator.isValid(""))
        assertFalse(PhoneValidator.isValid("   "))

        // Wrong lengths
        assertFalse(PhoneValidator.isValid("12345"))
        assertFalse(PhoneValidator.isValid("987654321")) // 9 digits
        assertFalse(PhoneValidator.isValid("98765432100")) // 11 digits without prefix

        // Non-digit characters
        assertFalse(PhoneValidator.isValid("abcdefghij"))
        assertFalse(PhoneValidator.isValid("98765abcde"))

        // Invalid Indian mobile starting digits (1, 2, 3, 4, 5)
        assertFalse(PhoneValidator.isValid("1234567890"))
        assertFalse(PhoneValidator.isValid("2345678901"))
        assertFalse(PhoneValidator.isValid("3456789012"))
        assertFalse(PhoneValidator.isValid("4567890123"))
        assertFalse(PhoneValidator.isValid("5678901234"))
        assertFalse(PhoneValidator.isValid("+91 12345 67890"))
    }

    @Test
    fun testPhoneValidator_extractionAndNormalization() {
        assertEquals("9876543210", PhoneValidator.extract10Digits("9876543210"))
        assertEquals("9876543210", PhoneValidator.extract10Digits("+91 98765 43210"))
        assertEquals("9876543210", PhoneValidator.extract10Digits("+91-98765-43210"))
        assertEquals("9876543210", PhoneValidator.extract10Digits("09876543210"))

        assertEquals("9876543210", PhoneValidator.normalize("+91 98765 43210"))
        assertEquals("9876543210", PhoneValidator.normalize("09876543210"))
    }

    @Test
    fun testPhoneValidator_displayFormatting() {
        assertEquals("+91 98765 43210", PhoneValidator.formatDisplay("9876543210"))
        assertEquals("+91 98765 43210", PhoneValidator.formatDisplay("+91 98765 43210"))
        assertEquals("+91 81234 56789", PhoneValidator.formatDisplay("8123456789"))
        assertEquals("Not provided", PhoneValidator.formatDisplay(""))
    }

    @Test
    fun testPhoneValidator_errorMessages() {
        assertNotNull(PhoneValidator.getValidationErrorMessage(""))
        assertNotNull(PhoneValidator.getValidationErrorMessage("123"))
        assertNotNull(PhoneValidator.getValidationErrorMessage("1234567890"))
        assertNull(PhoneValidator.getValidationErrorMessage("9876543210"))
        assertNull(PhoneValidator.getValidationErrorMessage("+91 98765 43210"))
    }
}
