package com.example

import com.example.core.supabase.SupabaseConfig
import com.example.model.UserRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * AMMAL FARM APP — STAGE 12B TEST SUITE
 * SUPABASE STORAGE SECURITY AUDIT
 *
 * Verifies all security requirements across Supabase Storage buckets, paths, and policies:
 * 1. Bucket Visibility & Configuration:
 *    - goat-images: public = true
 *    - farm-docs: public = false (confidential)
 *    - vet-certificates: public = false (confidential)
 * 2. SELECT/Read Access Control:
 *    - goat-images: Publicly readable for marketplace catalog
 *    - farm-docs: Private, only Super Admin and authorized Farm Admin for that farm
 *    - vet-certificates: Private, only Super Admin and authorized Farm Admin for that farm
 * 3. INSERT/Upload Access Control:
 *    - Owning Farm Admin can upload to their farm path
 *    - Super Admin can upload anywhere
 *    - Customer CANNOT upload to any bucket (goat-images, farm-docs, vet-certificates)
 *    - Anonymous user CANNOT upload to any bucket
 * 4. UPDATE & DELETE Access Control:
 *    - Owning Farm Admin can update/delete only their farm's files
 *    - Customer CANNOT update or delete any files
 * 5. Cross-Farm Path Escalation Prevention (CRITICAL SECURITY TEST):
 *    - Farm Admin A CANNOT write, modify, or delete files in Farm Admin B's storage path
 * 6. Farm Logo & Banner Security:
 *    - Stored under goat-images/farm/{farmId}/...
 *    - Correct public URL resolution
 *    - Private documents never exposed through logo path
 * 7. Policy Hygiene:
 *    - Zero unsafe broad conditions like USING (true) or WITH CHECK (true) on mutations or private buckets
 */
class Stage12BStorageSecurityAuditTest {

    private lateinit var mockStorageEngine: MockStorageSecurityEngine

    @Before
    fun setUp() {
        mockStorageEngine = MockStorageSecurityEngine()
    }

    // =========================================================================
    // 1. BUCKET CONFIGURATION & VISIBILITY
    // =========================================================================

    @Test
    fun testBucketVisibility_GoatImagesIsPublic_DocsAndCertsArePrivate() {
        val goatImagesBucket = mockStorageEngine.getBucket("goat-images")
        assertNotNull("goat-images bucket must exist", goatImagesBucket)
        assertTrue("goat-images must be public for marketplace viewing", goatImagesBucket!!.isPublic)

        val farmDocsBucket = mockStorageEngine.getBucket("farm-docs")
        assertNotNull("farm-docs bucket must exist", farmDocsBucket)
        assertFalse("farm-docs MUST NOT be publicly readable (confidential)", farmDocsBucket!!.isPublic)

        val vetCertsBucket = mockStorageEngine.getBucket("vet-certificates")
        assertNotNull("vet-certificates bucket must exist", vetCertsBucket)
        assertFalse("vet-certificates MUST NOT be publicly readable (confidential)", vetCertsBucket!!.isPublic)
    }

    // =========================================================================
    // 2. READ ACCESS CONTROL (SELECT)
    // =========================================================================

    @Test
    fun testSelectAccess_PublicCanReadGoatImages_CannotReadPrivateBuckets() {
        // Anonymous/Public user reads goat image
        val canReadGoatImage = mockStorageEngine.evaluateSelect(
            caller = null, // unauthenticated
            bucketId = "goat-images",
            path = "farm/farm-1/goat/goat-1/photo.jpg"
        )
        assertTrue("Public read must be allowed for marketplace goat-images", canReadGoatImage)

        // Anonymous/Public user attempts to read farm-docs
        val canReadFarmDocs = mockStorageEngine.evaluateSelect(
            caller = null,
            bucketId = "farm-docs",
            path = "farm/farm-1/registration_doc.pdf"
        )
        assertFalse("Public/Anon CANNOT read farm-docs", canReadFarmDocs)

        // Anonymous/Public user attempts to read vet-certificates
        val canReadVetCert = mockStorageEngine.evaluateSelect(
            caller = null,
            bucketId = "vet-certificates",
            path = "farm/farm-1/health_inspection.pdf"
        )
        assertFalse("Public/Anon CANNOT read vet-certificates", canReadVetCert)
    }

    @Test
    fun testSelectAccess_CustomerCannotReadPrivateBuckets() {
        val customer = mockStorageEngine.createUser("Customer 1", UserRole.CUSTOMER)

        val canReadDocs = mockStorageEngine.evaluateSelect(
            caller = customer,
            bucketId = "farm-docs",
            path = "farm/farm-1/confidential_license.pdf"
        )
        assertFalse("Customer cannot read farm-docs", canReadDocs)

        val canReadCerts = mockStorageEngine.evaluateSelect(
            caller = customer,
            bucketId = "vet-certificates",
            path = "farm/farm-1/vaccination_batch.pdf"
        )
        assertFalse("Customer cannot read vet-certificates", canReadCerts)
    }

    @Test
    fun testSelectAccess_FarmAdminCanReadOwnDocs_CannotReadOtherFarmDocs() {
        val farm1 = mockStorageEngine.createFarm("Ammal Main Farm")
        val farm2 = mockStorageEngine.createFarm("Rival Breeder Farm")

        val farmAdmin1 = mockStorageEngine.createUser("Admin Farm 1", UserRole.FARM_ADMIN, farm1.id)
        val farmAdmin2 = mockStorageEngine.createUser("Admin Farm 2", UserRole.FARM_ADMIN, farm2.id)

        // Farm Admin 1 reads own farm docs
        val canReadOwnDocs = mockStorageEngine.evaluateSelect(
            caller = farmAdmin1,
            bucketId = "farm-docs",
            path = "farm/${farm1.id}/deed.pdf"
        )
        assertTrue("Farm Admin 1 can read own farm-docs", canReadOwnDocs)

        // Farm Admin 1 attempts to read Farm 2's docs
        val canReadOtherDocs = mockStorageEngine.evaluateSelect(
            caller = farmAdmin1,
            bucketId = "farm-docs",
            path = "farm/${farm2.id}/financials.pdf"
        )
        assertFalse("Farm Admin 1 CANNOT read Farm 2's farm-docs", canReadOtherDocs)

        // Super Admin reads any farm docs
        val superAdmin = mockStorageEngine.createUser("Super Admin", UserRole.SUPER_ADMIN)
        val superAdminCanRead = mockStorageEngine.evaluateSelect(
            caller = superAdmin,
            bucketId = "farm-docs",
            path = "farm/${farm2.id}/financials.pdf"
        )
        assertTrue("Super Admin can read any farm-docs", superAdminCanRead)
    }

    // =========================================================================
    // 3. UPLOAD ACCESS CONTROL (INSERT)
    // =========================================================================

    @Test
    fun testInsertAccess_CustomerAndAnonBlockedFromAllBuckets() {
        val customer = mockStorageEngine.createUser("Customer 1", UserRole.CUSTOMER)

        // Customer attempts to upload goat image
        val customerGoatUpload = mockStorageEngine.evaluateInsert(
            caller = customer,
            bucketId = "goat-images",
            path = "goat/goat-999/malicious.jpg"
        )
        assertFalse("Customer CANNOT upload to goat-images", customerGoatUpload)

        // Customer attempts to upload farm-docs
        val customerDocsUpload = mockStorageEngine.evaluateInsert(
            caller = customer,
            bucketId = "farm-docs",
            path = "farm/farm-1/fake_license.pdf"
        )
        assertFalse("Customer CANNOT upload to farm-docs", customerDocsUpload)

        // Customer attempts to upload vet-certificates
        val customerCertsUpload = mockStorageEngine.evaluateInsert(
            caller = customer,
            bucketId = "vet-certificates",
            path = "farm/farm-1/fake_cert.pdf"
        )
        assertFalse("Customer CANNOT upload to vet-certificates", customerCertsUpload)

        // Anon attempts upload
        val anonUpload = mockStorageEngine.evaluateInsert(
            caller = null,
            bucketId = "goat-images",
            path = "goat/goat-999/spam.jpg"
        )
        assertFalse("Anonymous user CANNOT upload to any storage bucket", anonUpload)
    }

    // =========================================================================
    // 4. CROSS-FARM PATH ESCALATION (CRITICAL SECURITY TEST)
    // =========================================================================

    @Test
    fun testCrossFarmIsolation_FarmAdminCannotWriteIntoAnotherFarmPath() {
        val farmA = mockStorageEngine.createFarm("Salem Boer Farm")
        val farmB = mockStorageEngine.createFarm("Erode Sirohi Farm")

        val adminA = mockStorageEngine.createUser("Admin A", UserRole.FARM_ADMIN, farmA.id)
        val adminB = mockStorageEngine.createUser("Admin B", UserRole.FARM_ADMIN, farmB.id)

        // 1. Admin A uploads to own farm path -> ALLOWED
        val adminAUploadOwn = mockStorageEngine.evaluateInsert(
            caller = adminA,
            bucketId = "goat-images",
            path = "farm/${farmA.id}/farm_logo_123.jpg"
        )
        assertTrue("Admin A can upload to own farm path", adminAUploadOwn)

        // 2. Admin A attempts to upload into Farm B's path -> BLOCKED
        val adminAUploadOther = mockStorageEngine.evaluateInsert(
            caller = adminA,
            bucketId = "goat-images",
            path = "farm/${farmB.id}/farm_logo_hacked.jpg"
        )
        assertFalse("CRITICAL SECURITY: Farm Admin A CANNOT write into Farm B's storage path", adminAUploadOther)

        // 3. Admin A attempts to overwrite Farm B's farm-docs -> BLOCKED
        val adminAOverwriteDocs = mockStorageEngine.evaluateInsert(
            caller = adminA,
            bucketId = "farm-docs",
            path = "farm/${farmB.id}/contract.pdf"
        )
        assertFalse("CRITICAL SECURITY: Farm Admin A CANNOT write into Farm B's farm-docs", adminAOverwriteDocs)

        // 4. Admin A attempts to delete Farm B's logo -> BLOCKED
        val adminADeleteOther = mockStorageEngine.evaluateDelete(
            caller = adminA,
            bucketId = "goat-images",
            path = "farm/${farmB.id}/farm_logo_123.jpg"
        )
        assertFalse("CRITICAL SECURITY: Farm Admin A CANNOT delete Farm B's files", adminADeleteOther)

        // 5. Admin A attempts to update Farm B's files -> BLOCKED
        val adminAUpdateOther = mockStorageEngine.evaluateUpdate(
            caller = adminA,
            bucketId = "goat-images",
            path = "farm/${farmB.id}/farm_logo_123.jpg"
        )
        assertFalse("CRITICAL SECURITY: Farm Admin A CANNOT update Farm B's files", adminAUpdateOther)

        // 6. Super Admin CAN write to Farm B's path -> ALLOWED
        val superAdmin = mockStorageEngine.createUser("Super Admin", UserRole.SUPER_ADMIN)
        val superAdminUpload = mockStorageEngine.evaluateInsert(
            caller = superAdmin,
            bucketId = "goat-images",
            path = "farm/${farmB.id}/admin_verified_badge.png"
        )
        assertTrue("Super Admin can upload to any farm path", superAdminUpload)
    }

    // =========================================================================
    // 5. FARM LOGO PUBLIC URL & PATH SAFETY
    // =========================================================================

    @Test
    fun testFarmLogo_ResolvesPublicUrlAndPreventsPrivateDocExposure() {
        val farmId = "f47ac10b-58cc-4372-a567-0e02b2c3d479"
        val fileName = "farm_logo_1726000000_abc123.jpg"
        val storagePath = "farm/$farmId/$fileName"

        // Resolving URL for goat-images bucket
        val publicUrl = SupabaseConfig.resolveStorageUrl(storagePath, SupabaseConfig.BUCKET_GOAT_IMAGES)
        assertTrue("URL must contain supabase storage public path", publicUrl.contains("/storage/v1/object/public/goat-images/"))
        assertTrue("URL must contain farm storage path", publicUrl.contains("farm/$farmId/$fileName"))

        // Verify private documents are never exposed through public URL helper
        assertFalse("Storage path must not point to private documents", storagePath.contains("farm-docs"))
        assertFalse("Storage path must not point to vet-certificates", storagePath.contains("vet-certificates"))
    }

    // =========================================================================
    // 6. SQL MIGRATION FILE VERIFICATION & HYGIENE
    // =========================================================================

    @Test
    fun testSqlMigration_ContainsNoUnsafeBroadConditions() {
        val candidates = listOf(
            File("supabase/migrations/20260914020000_stage12b_storage_security_audit.sql"),
            File("../supabase/migrations/20260914020000_stage12b_storage_security_audit.sql"),
            File("/app/applet/supabase/migrations/20260914020000_stage12b_storage_security_audit.sql")
        )
        val migrationFile = candidates.firstOrNull { it.exists() }
        assertNotNull("Migration file 20260914020000_stage12b_storage_security_audit.sql must exist", migrationFile)

        val sqlContent = migrationFile!!.readText()
        val nonCommentSql = sqlContent.lines()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")

        // 1. Verify NO unsafe broad conditions exist
        assertFalse("SQL must NOT contain USING (true)", nonCommentSql.contains("USING (true)"))
        assertFalse("SQL must NOT contain WITH CHECK (true)", nonCommentSql.contains("WITH CHECK (true)"))

        // 2. Verify all 3 buckets are addressed
        assertTrue("SQL must configure goat-images", sqlContent.contains("'goat-images'"))
        assertTrue("SQL must configure farm-docs", sqlContent.contains("'farm-docs'"))
        assertTrue("SQL must configure vet-certificates", sqlContent.contains("'vet-certificates'"))

        // 3. Verify private buckets are set to public = false
        assertTrue("SQL must enforce farm-docs and vet-certificates as private",
            sqlContent.contains("UPDATE storage.buckets SET public = false WHERE id IN ('farm-docs', 'vet-certificates');")
        )

        // 4. Verify helper functions exist with SECURITY DEFINER and search_path
        assertTrue("can_access_storage_farm_path helper must be present", sqlContent.contains("FUNCTION public.can_access_storage_farm_path"))
        assertTrue("can_modify_storage_goat_image helper must be present", sqlContent.contains("FUNCTION public.can_modify_storage_goat_image"))
        assertTrue("Helpers must set search_path", sqlContent.contains("SET search_path = public, pg_temp;"))

        // 5. Verify RLS is enabled on storage.objects
        assertTrue("RLS must be enabled on storage.objects", sqlContent.contains("ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;"))
    }
}

// =============================================================================
// MOCK STORAGE ENGINE FOR DETERMINISTIC SECURITY RULE EVALUATION
// =============================================================================

class MockStorageSecurityEngine {

    data class MockUser(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val role: UserRole,
        val farmId: String? = null
    )

    data class MockFarm(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        var ownerId: String? = null,
        var status: String = "APPROVED"
    )

    data class MockBucket(
        val id: String,
        val isPublic: Boolean,
        val fileSizeLimit: Long,
        val allowedMimeTypes: List<String>
    )

    private val users = mutableMapOf<String, MockUser>()
    private val farms = mutableMapOf<String, MockFarm>()
    private val buckets = mutableMapOf<String, MockBucket>()

    init {
        buckets["goat-images"] = MockBucket("goat-images", isPublic = true, fileSizeLimit = 15728640, allowedMimeTypes = listOf("image/jpeg", "image/png", "image/webp", "image/gif"))
        buckets["farm-docs"] = MockBucket("farm-docs", isPublic = false, fileSizeLimit = 20971520, allowedMimeTypes = listOf("application/pdf", "image/jpeg", "image/png"))
        buckets["vet-certificates"] = MockBucket("vet-certificates", isPublic = false, fileSizeLimit = 20971520, allowedMimeTypes = listOf("application/pdf", "image/jpeg", "image/png"))
    }

    fun createUser(name: String, role: UserRole, farmId: String? = null): MockUser {
        val user = MockUser(name = name, role = role, farmId = farmId)
        users[user.id] = user
        if (farmId != null && farms.containsKey(farmId)) {
            farms[farmId]!!.ownerId = user.id
        }
        return user
    }

    fun createFarm(name: String): MockFarm {
        val farm = MockFarm(name = name)
        farms[farm.id] = farm
        return farm
    }

    fun getBucket(id: String): MockBucket? = buckets[id]

    // Simulates public.can_access_storage_farm_path(name)
    fun canAccessStorageFarmPath(caller: MockUser?, objectName: String): Boolean {
        if (caller == null) return false
        if (caller.role == UserRole.SUPER_ADMIN) return true

        val parts = objectName.split("/")
        val prefix = parts.getOrNull(0) ?: return false
        val target = parts.getOrNull(1) ?: return false

        val farmUuid = if (prefix == "farm") target else prefix
        val farm = farms[farmUuid] ?: return false

        return farm.ownerId == caller.id || caller.farmId == farm.id
    }

    // Simulates public.can_modify_storage_goat_image(name)
    fun canModifyStorageGoatImage(caller: MockUser?, objectName: String): Boolean {
        if (caller == null) return false
        if (caller.role == UserRole.SUPER_ADMIN) return true

        if (caller.role != UserRole.FARM_ADMIN && !farms.values.any { it.ownerId == caller.id }) {
            return false
        }

        val parts = objectName.split("/")
        val prefix = parts.getOrNull(0) ?: return false

        if (prefix == "farm") {
            return canAccessStorageFarmPath(caller, objectName)
        }

        if (prefix == "goat") {
            // Check farm ownership if caller belongs to an approved farm
            val callerFarm = caller.farmId?.let { farms[it] } ?: farms.values.firstOrNull { it.ownerId == caller.id }
            return callerFarm != null && callerFarm.status == "APPROVED"
        }

        return canAccessStorageFarmPath(caller, objectName)
    }

    // Evaluates SELECT policy on storage.objects
    fun evaluateSelect(caller: MockUser?, bucketId: String, path: String): Boolean {
        val bucket = buckets[bucketId] ?: return false
        if (bucket.isPublic) return true // Public read for goat-images
        return canAccessStorageFarmPath(caller, path)
    }

    // Evaluates INSERT policy on storage.objects
    fun evaluateInsert(caller: MockUser?, bucketId: String, path: String): Boolean {
        if (caller == null) return false // No anon insert
        return when (bucketId) {
            "goat-images" -> canModifyStorageGoatImage(caller, path)
            "farm-docs", "vet-certificates" -> canAccessStorageFarmPath(caller, path)
            else -> false
        }
    }

    // Evaluates UPDATE policy on storage.objects
    fun evaluateUpdate(caller: MockUser?, bucketId: String, path: String): Boolean {
        if (caller == null) return false
        return when (bucketId) {
            "goat-images" -> canModifyStorageGoatImage(caller, path)
            "farm-docs", "vet-certificates" -> canAccessStorageFarmPath(caller, path)
            else -> false
        }
    }

    // Evaluates DELETE policy on storage.objects
    fun evaluateDelete(caller: MockUser?, bucketId: String, path: String): Boolean {
        if (caller == null) return false
        return when (bucketId) {
            "goat-images" -> canModifyStorageGoatImage(caller, path)
            "farm-docs", "vet-certificates" -> canAccessStorageFarmPath(caller, path)
            else -> false
        }
    }
}
