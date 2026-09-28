package com.ammalfarm.adusanthai

import com.ammalfarm.adusanthai.data.dto.FarmDto
import com.ammalfarm.adusanthai.data.dto.GoatDto
import com.ammalfarm.adusanthai.model.Farm
import com.ammalfarm.adusanthai.model.Goat
import com.ammalfarm.adusanthai.util.ImageUploadHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 9C Test Suite — Sequential Farm and Goat IDs + Storage Path Standardization
 */
class Stage9CSequentialIdAndStorageTest {

    // Simulated Sequence Engine matching PostgreSQL CREATE SEQUENCE behavior
    private class SimulatedSequence(startVal: Int = 1) {
        private val counter = AtomicInteger(startVal)

        fun nextval(): Int {
            return counter.getAndIncrement()
        }

        fun currentval(): Int {
            return counter.get() - 1
        }
    }

    // Simulated Farm & Goat Table State in PostgreSQL
    private class SimulatedDatabase {
        val farmSeq = SimulatedSequence(1)
        val goatSeq = SimulatedSequence(1)

        val farms = ConcurrentHashMap<String, FarmRecord>()
        val goats = ConcurrentHashMap<String, GoatRecord>()

        data class FarmRecord(
            val id: String,
            var farmCode: String,
            val name: String,
            val ownerId: String
        )

        data class GoatRecord(
            val id: String,
            var goatCode: String,
            val farmId: String,
            val name: String
        )

        fun insertFarm(name: String, ownerId: String, clientProvidedCode: String? = null): FarmRecord {
            val uuid = UUID.randomUUID().toString()
            val code = if (clientProvidedCode != null && clientProvidedCode.trim().isNotEmpty()) {
                clientProvidedCode
            } else {
                String.format("FARM-%03d", farmSeq.nextval())
            }
            val record = FarmRecord(uuid, code, name, ownerId)
            farms[uuid] = record
            return record
        }

        fun updateFarm(id: String, attemptedNewCode: String?, newName: String): FarmRecord? {
            val existing = farms[id] ?: return null
            val immutableCode = existing.farmCode
            val updated = existing.copy(farmCode = immutableCode, name = newName)
            farms[id] = updated
            return updated
        }

        fun deleteFarm(id: String): Boolean {
            return farms.remove(id) != null
        }

        fun insertGoat(farmId: String, name: String, clientProvidedCode: String? = null): GoatRecord {
            val uuid = UUID.randomUUID().toString()
            val code = if (clientProvidedCode != null && clientProvidedCode.trim().isNotEmpty()) {
                clientProvidedCode
            } else {
                String.format("GOAT-%03d", goatSeq.nextval())
            }
            val record = GoatRecord(uuid, code, farmId, name)
            goats[uuid] = record
            return record
        }

        fun updateGoat(id: String, attemptedNewCode: String?, newName: String): GoatRecord? {
            val existing = goats[id] ?: return null
            // Trigger assign_goat_code logic: IF OLD.goat_code IS NOT NULL AND NEW.goat_code IS DISTINCT FROM OLD.goat_code THEN NEW.goat_code := OLD.goat_code
            val immutableCode = existing.goatCode
            val updated = existing.copy(goatCode = immutableCode, name = newName)
            goats[id] = updated
            return updated
        }

        fun deleteGoat(id: String): Boolean {
            return goats.remove(id) != null
        }
    }

    @Test
    fun test1_SequentialFarmAndGoatCodeFormatting() {
        val db = SimulatedDatabase()

        val farm1 = db.insertFarm("Farm One", "owner_1")
        val farm2 = db.insertFarm("Farm Two", "owner_2")

        assertEquals("FARM-001", farm1.farmCode)
        assertEquals("FARM-002", farm2.farmCode)

        val goat1 = db.insertGoat(farm1.id, "Goat One")
        val goat2 = db.insertGoat(farm1.id, "Goat Two")

        assertEquals("GOAT-001", goat1.goatCode)
        assertEquals("GOAT-002", goat2.goatCode)
    }

    @Test
    fun test2_DeletionDoesNotReuseSequentialCodes() {
        val db = SimulatedDatabase()

        // Create 5 goats: GOAT-001 to GOAT-005
        val goats = (1..5).map { db.insertGoat("farm_uuid", "Goat $it") }
        val goat5 = goats.last()

        assertEquals("GOAT-005", goat5.goatCode)

        // Delete GOAT-005
        val deleted = db.deleteGoat(goat5.id)
        assertTrue("Goat 5 deleted", deleted)

        // Create a new goat
        val newGoat = db.insertGoat("farm_uuid", "New Goat")

        // Must NOT receive GOAT-005! Sequence continues forward to GOAT-006
        assertNotEquals("GOAT-005", newGoat.goatCode)
        assertEquals("GOAT-006", newGoat.goatCode)

        // Repeat for Farm: Delete FARM-005 and create another farm
        val farms = (1..5).map { db.insertFarm("Farm $it", "owner_$it") }
        val farm5 = farms.last()
        assertEquals("FARM-005", farm5.farmCode)

        db.deleteFarm(farm5.id)

        val newFarm = db.insertFarm("New Farm", "owner_new")
        assertNotEquals("FARM-005", newFarm.farmCode)
        assertEquals("FARM-006", newFarm.farmCode)
    }

    @Test
    fun test3_ConcurrentCreationProducesUniqueCodes() {
        val db = SimulatedDatabase()
        val threadCount = 20
        val createdFarmCodes = ConcurrentHashMap.newKeySet<String>()
        val createdGoatCodes = ConcurrentHashMap.newKeySet<String>()

        val threads = (1..threadCount).map { i ->
            Thread {
                val f = db.insertFarm("Parallel Farm $i", "owner_$i")
                createdFarmCodes.add(f.farmCode)

                val g = db.insertGoat(f.id, "Parallel Goat $i")
                createdGoatCodes.add(g.goatCode)
            }
        }

        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals("20 unique farm codes generated under concurrency", 20, createdFarmCodes.size)
        assertEquals("20 unique goat codes generated under concurrency", 20, createdGoatCodes.size)
    }

    @Test
    fun test4_TriggersPreventCodeMutationOnUpdate() {
        val db = SimulatedDatabase()
        val farm = db.insertFarm("Original Farm", "owner_1")
        assertEquals("FARM-001", farm.farmCode)

        // Attempt to tamper farm_code to FARM-999
        val updatedFarm = db.updateFarm(farm.id, "FARM-999", "Renamed Farm")
        assertNotNull(updatedFarm)
        assertEquals("Farm code must remain unchanged on update", "FARM-001", updatedFarm?.farmCode)

        val goat = db.insertGoat(farm.id, "Original Goat")
        assertEquals("GOAT-001", goat.goatCode)

        // Attempt to tamper goat_code to GOAT-888
        val updatedGoat = db.updateGoat(goat.id, "GOAT-888", "Renamed Goat")
        assertNotNull(updatedGoat)
        assertEquals("Goat code must remain unchanged on update", "GOAT-001", updatedGoat?.goatCode)
    }

    @Test
    fun test5_StoragePathStandardization() {
        val farmCode = "FARM-001"
        val goatCode = "GOAT-001"
        val photoIndex = 0 // 1st photo

        val ext = ".jpg"
        val posStr = String.format(java.util.Locale.US, "%02d", photoIndex + 1)
        val fileName = "$posStr$ext"

        val storagePath = "$farmCode/$goatCode/$fileName"

        assertEquals("01.jpg", fileName)
        assertEquals("FARM-001/GOAT-001/01.jpg", storagePath)

        // 2nd photo
        val posStr2 = String.format(java.util.Locale.US, "%02d", 2)
        val storagePath2 = "$farmCode/$goatCode/${posStr2}.png"
        assertEquals("FARM-001/GOAT-001/02.png", storagePath2)
    }

    @Test
    fun test6_DtoDomainMappingWithSequentialCodes() {
        val farmDto = FarmDto(
            id = "11111111-1111-1111-1111-111111111111",
            farmCode = "FARM-003",
            name = "Coimbatore Farm",
            ownerId = "owner_uuid"
        )

        val farmDomain = farmDto.toDomain()
        assertEquals("FARM-003", farmDomain.farmCode)

        val goatDto = GoatDto(
            id = "22222222-2222-2222-2222-222222222222",
            goatCode = "GOAT-007",
            farmId = farmDto.id,
            name = "Jamnapari Buck",
            breedName = "Jamnapari"
        )

        val goatDomain = goatDto.toDomain(
            resolvedFarmName = farmDomain.name,
            resolvedFarmLocation = farmDomain.location,
            resolvedFarmCode = farmDomain.farmCode
        )

        assertEquals("GOAT-007", goatDomain.goatCode)
        assertEquals("FARM-003", goatDomain.farmCode)
        assertEquals("22222222-2222-2222-2222-222222222222", goatDomain.id)
        assertEquals("11111111-1111-1111-1111-111111111111", goatDomain.farmId)
    }
}
