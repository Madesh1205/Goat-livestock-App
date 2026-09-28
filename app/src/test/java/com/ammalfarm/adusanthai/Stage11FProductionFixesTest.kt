package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.VerificationStatus
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * AMMAL FARM — STAGE 11F PRODUCTION FIXES VERIFICATION TEST SUITE
 *
 * Tests:
 * 1. Super Admin Farm Moderation:
 *    - Validates payload construction with buildJsonObject instead of Map<String, Any>
 *    - Ensures all fields serialize to proper JSON types (avoiding 'Any' serializer error)
 *
 * 2. Booking Database Error Handling:
 *    - Verifies migration file exists and replaces stale 'listing_fee_amount' trigger
 *    - Verifies 'record "old" has no field "listing_fee_amount"' is captured and mapped cleanly
 *
 * 3. Error Sanitization & Leakage Prevention:
 *    - Verifies that Supabase URLs, SQL errors, trigger names, and internal REST details
 *      are stripped and never returned to the user.
 */
class Stage11FProductionFixesTest {

    @Test
    fun testFarmVerificationPayloadSerialization() {
        // Verify buildJsonObject correctly constructs valid JsonObject without needing 'Any' serializer
        val status = VerificationStatus.APPROVED
        val nowIso = "2026-09-13T12:00:00Z"
        val payload = buildJsonObject {
            put("verification_status", status.name)
            when (status) {
                VerificationStatus.APPROVED -> {
                    put("verified_at", nowIso)
                }
                VerificationStatus.REJECTED -> {
                    put("verified_at", "")
                }
                VerificationStatus.SUSPENDED -> {
                    put("verified_at", "")
                }
                VerificationStatus.PENDING -> {
                    put("verified_at", "")
                }
            }
        }

        assertEquals("\"APPROVED\"", payload["verification_status"].toString())
        assertEquals("\"2026-09-13T12:00:00Z\"", payload["verified_at"].toString())
        assertFalse(payload.toString().contains("Any"))
    }

    @Test
    fun testRawSupabaseUrlSanitization() {
        val rawErrorMsg = "HTTP 500 error calling https://rvvszhflbfljrcdypnjr.supabase.co/rest/v1/farms?id=eq.123: internal server error"
        val sanitized = UserFriendlyErrorMapper.sanitize(rawErrorMsg)

        assertFalse("Sanitized error must NOT contain supabase.co", sanitized.contains("supabase.co"))
        assertFalse("Sanitized error must NOT contain /rest/v1", sanitized.contains("/rest/v1"))
        assertFalse("Sanitized error must NOT contain rvvszhflbfljrcdypnjr", sanitized.contains("rvvszhflbfljrcdypnjr"))
        assertTrue("Sanitized error should provide user-friendly message", sanitized.isNotEmpty())
    }

    @Test
    fun testStaleListingFeeTriggerErrorSanitization() {
        val dbTriggerError = Exception("Database error: record \"old\" has no field \"listing_fee_amount\" in trigger tr_enforce_goat_listing_fee")
        val userMsg = UserFriendlyErrorMapper.forBooking(dbTriggerError)

        assertFalse("Error message must not mention listing_fee_amount", userMsg.contains("listing_fee_amount"))
        assertFalse("Error message must not mention tr_enforce_goat_listing_fee", userMsg.contains("tr_enforce_goat_listing_fee"))
        assertFalse("Error message must not mention \"old\"", userMsg.contains("\"old\""))
        assertTrue(userMsg.contains("reservation", ignoreCase = true) || userMsg.contains("booking", ignoreCase = true))
    }

    @Test
    fun testSerializerAnyErrorSanitization() {
        val serializationError = Exception("Failed to update farm verification: Serializer for class 'Any' is not found. Mark the class as @Serializable or provide the serializer explicitly.")
        val userMsg = UserFriendlyErrorMapper.forFarmModeration(serializationError)

        assertFalse("Error must not leak serialization mechanics", userMsg.contains("Serializer for class 'Any' is not found"))
        assertFalse("Error must not mention @Serializable", userMsg.contains("@Serializable"))
        assertTrue(userMsg.contains("verification", ignoreCase = true) || userMsg.contains("moderation", ignoreCase = true) || userMsg.contains("Farm", ignoreCase = true))
    }

    @Test
    fun testSqlStateAndDatabaseLeakageSanitization() {
        val sqlStateError = Exception("ERROR: duplicate key value violates unique constraint \"idx_single_active_goat_booking\" (SQLSTATE 23505)")
        val userMsg = UserFriendlyErrorMapper.forBooking(sqlStateError)

        assertFalse(userMsg.contains("SQLSTATE"))
        assertFalse(userMsg.contains("idx_single_active_goat_booking"))
        assertFalse(userMsg.contains("unique constraint"))
        assertTrue(userMsg.contains("already reserved", ignoreCase = true))
    }

    @Test
    fun testMigrationFileExistsAndRemovesStaleTriggerReference() {
        val candidates = listOf(
            File("supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql"),
            File("../supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql"),
            File("/app/applet/supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql")
        )
        val migrationFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Migration file must exist in one of the expected paths", migrationFile)

        val sqlContent = migrationFile!!.readText()
        assertTrue("Migration must drop old trigger", sqlContent.contains("DROP TRIGGER IF EXISTS tr_enforce_goat_listing_fee ON public.goats"))
        assertTrue("Migration must recreate clean trigger", sqlContent.contains("CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule"))
        
        val functionBody = sqlContent.substringAfter("CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule()")
            .substringBefore("LANGUAGE plpgsql")
        assertFalse("New function body must NOT reference OLD.listing_fee_amount", functionBody.contains("OLD.listing_fee_amount"))
        assertFalse("New function body must NOT reference NEW.listing_fee_amount", functionBody.contains("NEW.listing_fee_amount"))
        assertTrue("Migration must fix booking immutability trigger", sqlContent.contains("enforce_booking_amount_immutability"))
    }
}
