package com.bupt.schedule.data.local

import com.bupt.schedule.domain.model.CustomAssignment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.json.JSONObject

class CustomAssignmentStoreTest {
    @get:Rule val folder = TemporaryFolder()
    // Real files exercise recreation/persistence; Android's AtomicFile recovery needs device verification.
    private fun storage() = object : CustomAssignmentStore.Storage {
        override fun read(ownerHash: String): String? = File(folder.root, ownerHash).takeIf { it.exists() }?.readText()
        override fun write(ownerHash: String, value: String) { File(folder.root, ownerHash).writeText(value) }
    }
    private fun task() = CustomAssignment(title = "练习\"一\"", courseName = "同名课程", deadlineMillis = 1791734340000,
        description = "第一行\n第二行 <无需HTML>")

    @Test fun mutationsPersistAcrossStoreRecreationAndAccounts() {
        val task = task()
        CustomAssignmentStore(storage()).upsert("demo-a", task)
        assertEquals(listOf(task), CustomAssignmentStore(storage()).load("demo-a"))
        assertEquals(emptyList<CustomAssignment>(), CustomAssignmentStore(storage()).load("demo-b"))
        val edited = task.copy(title = "新名称", courseName = "手输课程", deadlineMillis = task.deadlineMillis + 86400000)
        CustomAssignmentStore(storage()).upsert("demo-a", edited)
        assertEquals(edited, CustomAssignmentStore(storage()).load("demo-a").single())
        CustomAssignmentStore(storage()).upsert("demo-b", task.copy(title = "另一个账号"))
        CustomAssignmentStore(storage()).complete("demo-a", task.id)
        assertTrue(CustomAssignmentStore(storage()).load("demo-a").isEmpty())
        assertEquals("另一个账号", CustomAssignmentStore(storage()).load("demo-b").single().title)
        CustomAssignmentStore(storage()).delete("demo-b", task.id)
        assertTrue(CustomAssignmentStore(storage()).load("demo-b").isEmpty())
    }

    @Test fun sameNamesRemainSeparateTasksAndAccountWhitespaceIsNormalized() {
        val task = task()
        val store = CustomAssignmentStore(storage())
        store.upsert(" demo ", task)
        store.upsert("demo", task.copy(id = "LOCAL:other"))
        assertEquals(2, store.load("demo").size)
    }

    @Test fun failedWriteLeavesLastSavedTaskUsable() {
        val storage = storage()
        val task = task()
        CustomAssignmentStore(storage).upsert("demo", task)
        val failing = CustomAssignmentStore(object : CustomAssignmentStore.Storage {
            override fun read(ownerHash: String) = storage.read(ownerHash)
            override fun write(ownerHash: String, value: String) { throw java.io.IOException("test failure") }
        })
        assertThrows(java.io.IOException::class.java) { failing.complete("demo", task.id) }
        assertEquals(task, CustomAssignmentStore(storage).load("demo").single())
    }

    @Test fun corruptDataCannotBeOverwrittenByNormalMutation() {
        val storage = storage()
        storage.write(AssignmentJsonCodec.accountOwnerHash("demo"), "broken")
        val store = CustomAssignmentStore(storage)
        assertThrows(Exception::class.java) { store.upsert("demo", task()) }
        assertEquals("broken", storage.read(AssignmentJsonCodec.accountOwnerHash("demo")))
    }

    @Test fun codecChecksOwnerAndPreservesDescriptionWithoutCompletionHistory() {
        val task = task()
        val json = CustomAssignmentJsonCodec.encode("demo", listOf(task))
        assertEquals(2, JSONObject(json).getInt("version"))
        assertFalse(JSONObject(json).getJSONArray("tasks").getJSONObject(0).has("completed"))
        assertEquals(listOf(task), CustomAssignmentJsonCodec.decode("demo", json))
        assertThrows(IllegalArgumentException::class.java) { CustomAssignmentJsonCodec.decode("other", json) }
        assertThrows(IllegalArgumentException::class.java) { CustomAssignment(title = " ", courseName = "course", deadlineMillis = 1) }
        assertThrows(IllegalArgumentException::class.java) { task.copy(courseName = " ") }
    }

    private fun legacyJson(account: String, tasks: List<Pair<CustomAssignment, Boolean>>): String =
        JSONObject(CustomAssignmentJsonCodec.encode(account, tasks.map { it.first })).apply {
            put("version", 1)
            val array = getJSONArray("tasks")
            tasks.forEachIndexed { index, (_, completed) -> array.getJSONObject(index).put("completed", completed) }
        }.toString()

    @Test fun legacyCompletedHistoryIsPurgedFromDiskWithoutChangingOtherAccount() {
        val active = task()
        val finished = task().copy(id = "LOCAL:finished-history")
        val storage = storage()
        val hash = AssignmentJsonCodec.accountOwnerHash("demo-a")
        storage.write(hash, legacyJson("demo-a", listOf(active to false, finished to true)))
        val otherHash = AssignmentJsonCodec.accountOwnerHash("demo-b")
        val otherBytes = legacyJson("demo-b", listOf(finished to false))
        storage.write(otherHash, otherBytes)
        assertEquals(listOf(active), CustomAssignmentStore(storage).load("demo-a"))
        val migrated = storage.read(hash)!!
        assertEquals(2, JSONObject(migrated).getInt("version"))
        assertFalse(migrated.contains(finished.id))
        assertFalse(migrated.contains("completed"))
        assertEquals(otherBytes, storage.read(otherHash))
        assertEquals(listOf(active), CustomAssignmentStore(storage()).load("demo-a"))
    }

    @Test fun allCompletedLegacyDataBecomesAnEmptyFileAndNeverReturnsAfterRecreation() {
        val hash = AssignmentJsonCodec.accountOwnerHash("demo")
        storage().write(hash, legacyJson("demo", listOf(task() to true)))
        assertTrue(CustomAssignmentStore(storage()).load("demo").isEmpty())
        assertEquals(0, JSONObject(storage().read(hash)!!).getJSONArray("tasks").length())
        assertTrue(CustomAssignmentStore(storage()).load("demo").isEmpty())
    }

    @Test fun failedMigrationKeepsOriginalBytesForRetry() {
        val active = task()
        val original = legacyJson("demo", listOf(active to false, task().copy(id = "LOCAL:finished") to true))
        val storage = storage()
        val hash = AssignmentJsonCodec.accountOwnerHash("demo")
        storage.write(hash, original)
        val failing = CustomAssignmentStore(object : CustomAssignmentStore.Storage {
            override fun read(ownerHash: String) = storage.read(ownerHash)
            override fun write(ownerHash: String, value: String) { throw java.io.IOException("test failure") }
        })
        assertThrows(java.io.IOException::class.java) { failing.load("demo") }
        assertEquals(original, storage.read(hash))
        assertEquals(listOf(active), CustomAssignmentStore(storage).load("demo"))
    }
}
