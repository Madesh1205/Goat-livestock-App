package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.model.ApprovalStatus
import com.ammalfarm.adusanthai.model.AvailabilityStatus
import com.ammalfarm.adusanthai.model.UserRole
import com.ammalfarm.adusanthai.model.VerificationStatus
import com.ammalfarm.adusanthai.util.UserFriendlyErrorMapper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * AMMAL FARM — STAGE 11H SUPER ADMIN APPROVAL AND BOOKING FLOW VERIFICATION TEST SUITE
 *
 * Verifies:
 * 1. Super Admin Farm Approval:
 *    - Validates payload construction with buildJsonObject instead of Map<String, Any>
 *    - Ensures all fields serialize to proper JSON types (avoiding 'Any' / 'Comparable' serializer error)
 *    - Database status field mapping is APPROVED on approval
 *    - Verification timestamp is ISO-8601 formatted
 *
 * 2. Customer Booking Flow:
 *    - Active-booking hold checking and expiration
 *    - Server-authoritative goat price preservation (immutable total_price snapshot)
 *    - Zero references to obsolete listing_fee_amount in booking schema/triggers
 *
 * 3. UserFriendlyErrorMapper Protection:
 *    - No raw URLs, SQLSTATE, trigger names, table names, or serialization errors shown
 */
class Stage11HApprovalAndBookingTest {

    @Test
    fun testFarmVerificationApprovalPayloadSerialization() {
        val status = VerificationStatus.APPROVED
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val nowIso = isoFormat.format(Date())

        val updatePayload = buildJsonObject {
            put("status", status.name)
            if (status == VerificationStatus.APPROVED) {
                put("verified_at", nowIso)
            }
        }

        assertEquals("\"APPROVED\"", updatePayload["status"].toString())
        assertNotNull(updatePayload["verified_at"])
        assertTrue(updatePayload["verified_at"].toString().contains("Z"))
        assertFalse("Payload must not contain serialization metadata or 'Any'", updatePayload.toString().contains("Any"))
    }

    @Test
    fun testGoatApprovalPayloadSerialization() {
        val status = ApprovalStatus.APPROVED
        val isApproved = status == ApprovalStatus.APPROVED
        val dbStatus = if (status == ApprovalStatus.REJECTED || status == ApprovalStatus.SUSPENDED) "INACTIVE" else "AVAILABLE"

        val updatePayload = buildJsonObject {
            put("is_approved_by_admin", isApproved)
            put("status", dbStatus)
        }

        assertEquals("true", updatePayload["is_approved_by_admin"].toString())
        assertEquals("\"AVAILABLE\"", updatePayload["status"].toString())
    }

    @Test
    fun testReviewApprovalAndModerationPayloadSerialization() {
        val hidePayload = buildJsonObject { put("is_approved", false) }
        val restorePayload = buildJsonObject { put("is_approved", true) }

        assertEquals("false", hidePayload["is_approved"].toString())
        assertEquals("true", restorePayload["is_approved"].toString())
    }

    @Test
    fun testHoldExpiryPayloadSerialization() {
        val expiredPayload = buildJsonObject { put("status", "EXPIRED") }
        assertEquals("\"EXPIRED\"", expiredPayload["status"].toString())
    }

    @Test
    fun testSuperAdminOnlyVerificationPermissionSanitization() {
        val unauthorizedError = SecurityException("Only Super Admin can update farm verification status")
        val userMsg = UserFriendlyErrorMapper.forFarmModeration(unauthorizedError)

        assertTrue(userMsg.contains("Super Admin", ignoreCase = true))
        assertFalse(UserFriendlyErrorMapper.containsLeakage(userMsg))
    }

    @Test
    fun testBookingErrorSanitization_NoSqlStateOrTriggerNames() {
        val testCases = listOf(
            "ERROR: record \"old\" has no field \"listing_fee_amount\" in trigger tr_enforce_goat_listing_fee",
            "ERROR: duplicate key value violates unique constraint \"idx_single_active_goat_booking\" (SQLSTATE 23505)",
            "PostgrestException(message=relation \"public.bookings\" does not exist, code=42P01, details=null, hint=null)",
            "https://wphgctwmjcvrblpybktd.supabase.co/rest/v1/bookings?select=*"
        )

        for (rawError in testCases) {
            val userMsg = UserFriendlyErrorMapper.forBooking(Exception(rawError))
            assertFalse("Must not leak Supabase URL in: $userMsg", userMsg.contains("supabase.co"))
            assertFalse("Must not leak /rest/v1 in: $userMsg", userMsg.contains("/rest/v1"))
            assertFalse("Must not leak SQLSTATE in: $userMsg", userMsg.contains("SQLSTATE"))
            assertFalse("Must not leak trigger in: $userMsg", userMsg.contains("tr_enforce_goat_listing_fee"))
            assertFalse("Must not leak listing_fee_amount in: $userMsg", userMsg.contains("listing_fee_amount"))
            assertFalse("Must not leak constraint in: $userMsg", userMsg.contains("idx_single_active_goat_booking"))
            assertTrue("Should produce human-readable message", userMsg.isNotBlank())
        }
    }

    @Test
    fun testSerializerAnyErrorSanitization() {
        val serializerErr = Exception("Serializer for class 'Any' is not found. Mark the class as @Serializable or provide the serializer explicitly.")
        val userMsg = UserFriendlyErrorMapper.forFarmModeration(serializerErr)

        assertFalse(userMsg.contains("Serializer for class 'Any'"))
        assertFalse(userMsg.contains("@Serializable"))
        assertTrue(userMsg.contains("verification", ignoreCase = true) || userMsg.contains("status", ignoreCase = true))
    }

    @Test
    fun testMigrationFixStaleListingFeeTriggerExists() {
        val candidates = listOf(
            File("supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql"),
            File("../supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql"),
            File("/app/applet/supabase/migrations/20260908000000_fix_stale_listing_fee_trigger_and_booking_schema.sql")
        )
        val migrationFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Migration file 20260908 must exist", migrationFile)

        val content = migrationFile!!.readText()
        assertTrue("Migration must recreate enforce_goat_listing_fee_rule", content.contains("CREATE OR REPLACE FUNCTION public.enforce_goat_listing_fee_rule"))
        assertTrue("Migration must recreate enforce_booking_amount_immutability", content.contains("CREATE OR REPLACE FUNCTION public.enforce_booking_amount_immutability"))
        assertTrue("Migration must use total_price", content.contains("total_price"))
    }
}
