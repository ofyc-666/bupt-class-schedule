package com.bupt.schedule.data.local

import android.content.Context
import android.util.AtomicFile
import com.bupt.schedule.domain.model.CustomAssignment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object CustomAssignmentJsonCodec {
    fun encode(account: String, tasks: List<CustomAssignment>): String = JSONObject()
        .put("version", 2)
        .put("owner_hash", AssignmentJsonCodec.accountOwnerHash(account))
        .put("tasks", JSONArray().apply {
            tasks.forEach { task -> put(JSONObject()
                .put("id", task.id).put("title", task.title).put("course", task.courseName)
                .put("deadline", task.deadlineMillis).put("description", task.description)) }
        }).toString()

    fun decode(account: String, json: String): List<CustomAssignment> {
        val root = JSONObject(json)
        val version = root.getInt("version")
        require(version in 1..2) { "不支持的本地作业版本" }
        require(root.getString("owner_hash") == AssignmentJsonCodec.accountOwnerHash(account))
        val array = root.getJSONArray("tasks")
        return (0 until array.length()).mapNotNull { index ->
            val task = array.getJSONObject(index)
            // Version 1 kept completed history. The new contract removes it permanently.
            if (version == 1 && task.getBoolean("completed")) return@mapNotNull null
            CustomAssignment(task.getString("id"), task.getString("title"), task.getString("course"),
                task.getLong("deadline"), task.optString("description"))
        }.also { tasks -> require(tasks.map { it.id }.distinct().size == tasks.size) }
    }
}

/** Separate files per account. Cloud refresh/credential clearing never writes here. */
class CustomAssignmentStore(private val storage: Storage) {
    interface Storage {
        fun read(ownerHash: String): String?
        fun write(ownerHash: String, value: String)
    }

    constructor(context: Context) : this(object : Storage {
        private val directory = File(context.filesDir, "custom_assignments").apply {
            check(isDirectory || mkdirs()) { "无法创建本地作业目录" }
        }
        private fun file(hash: String) = AtomicFile(File(directory, "$hash.json"))
        override fun read(ownerHash: String): String? {
            val file = file(ownerHash)
            // openRead restores AtomicFile's backup even when the base file is absent.
            if (!file.baseFile.exists() && !File(directory, "$ownerHash.json.bak").exists()) return null
            return file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        override fun write(ownerHash: String, value: String) {
            val file = file(ownerHash)
            val output = file.startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        }
    })

    @Synchronized fun load(account: String): List<CustomAssignment> {
        require(account.isNotBlank())
        val json = storage.read(AssignmentJsonCodec.accountOwnerHash(account)) ?: return emptyList()
        // Never silently replace unreadable user data with an empty list.
        val tasks = CustomAssignmentJsonCodec.decode(account, json)
        if (JSONObject(json).getInt("version") == 1) save(account, tasks)
        return tasks
    }

    @Synchronized fun save(account: String, tasks: List<CustomAssignment>) {
        require(account.isNotBlank())
        storage.write(AssignmentJsonCodec.accountOwnerHash(account), CustomAssignmentJsonCodec.encode(account, tasks))
    }

    fun upsert(account: String, task: CustomAssignment) = change(account) { tasks ->
        tasks.filterNot { it.id == task.id } + task
    }
    fun delete(account: String, id: String) = change(account) { tasks -> tasks.filterNot { it.id == id } }
    /** Completion is a durable removal, with no finished state or undo history. */
    fun complete(account: String, id: String) = delete(account, id)
    @Synchronized private fun change(account: String, update: (List<CustomAssignment>) -> List<CustomAssignment>) {
        save(account, update(load(account)))
    }
}
