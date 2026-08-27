package com.peterle95.watchnotetaker.watch

import android.content.Context
import android.content.SharedPreferences
import com.peterle95.watchnotetaker.transfer.WearDataProtocol
import java.io.File

data class QueuedWatchAudio(
    val noteId: String,
    val file: File,
    val durationSeconds: Int,
)

interface WatchRecordingMetadataStore {
    fun duration(fileName: String): Int?
    fun saveDuration(fileName: String, durationSeconds: Int): Boolean
    fun removeDuration(fileName: String): Boolean
    fun sentNode(noteId: String): String?
    fun saveSentNode(noteId: String, nodeId: String): Boolean
    fun removeSentNode(noteId: String): Boolean
}

class WatchAudioQueue internal constructor(
    private val directory: File,
    private val metadata: WatchRecordingMetadataStore,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "queued-audio"),
        SharedPreferencesWatchRecordingMetadataStore(
            context.getSharedPreferences("queued-audio", Context.MODE_PRIVATE),
        ),
    )

    fun enqueue(recording: File, durationSeconds: Int): Boolean = synchronized(ENQUEUE_LOCK) {
        require(recording.isFile) { "Recording does not exist" }
        require(recording.extension == "m4a" && WearDataProtocol.isValidNoteId(recording.nameWithoutExtension)) {
            "Invalid recording file"
        }
        require(durationSeconds in 1..120) { "Invalid recording duration" }
        if (entries().size >= CAPACITY) return@synchronized false
        check(directory.mkdirs() || directory.isDirectory) { "Could not create recording queue" }

        val target = File(directory, recording.name)
        if (target.exists()) {
            check(metadata.duration(target.name) == null) { "Recording is already queued" }
            check(target.delete()) { "Could not remove incomplete queued recording" }
        }

        val temporary = File.createTempFile(".${recording.nameWithoutExtension}-", ".tmp", directory)
        var finalized = false
        try {
            recording.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(target)) { "Could not finalize queued recording" }
            finalized = true
            if (!metadata.saveDuration(target.name, durationSeconds)) {
                metadata.removeDuration(target.name)
                target.delete()
                error("Could not save recording metadata")
            }
            recording.delete()
            true
        } catch (failure: Exception) {
            if (finalized && metadata.duration(target.name) == null) target.delete()
            throw failure
        } finally {
            temporary.delete()
        }
    }

    fun entries(): List<QueuedWatchAudio> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "m4a" }
        ?.sortedBy(File::lastModified)
        ?.mapNotNull { file ->
            metadata.duration(file.name)?.let { duration ->
                QueuedWatchAudio(
                    noteId = file.nameWithoutExtension,
                    file = file,
                    durationSeconds = duration,
                )
            }
        }
        ?: emptyList()

    fun remove(noteId: String): Boolean {
        if (!WearDataProtocol.isValidNoteId(noteId)) return false
        val file = File(directory, "$noteId.m4a")
        if (!file.delete()) return false
        metadata.removeDuration(file.name)
        return true
    }

    fun markSent(noteId: String, nodeId: String) {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        check(metadata.saveSentNode(noteId, nodeId)) { "Could not save target node" }
    }

    fun acknowledge(noteId: String, nodeId: String): Boolean {
        if (metadata.sentNode(noteId) != nodeId) return false
        val removed = remove(noteId)
        if (removed) metadata.removeSentNode(noteId)
        return removed
    }

    companion object {
        const val CAPACITY = 10
        private val ENQUEUE_LOCK = Any()
    }
}

private class SharedPreferencesWatchRecordingMetadataStore(
    private val preferences: SharedPreferences,
) : WatchRecordingMetadataStore {
    override fun duration(fileName: String): Int? =
        if (preferences.contains(fileName)) preferences.getInt(fileName, 0) else null

    override fun saveDuration(fileName: String, durationSeconds: Int): Boolean =
        preferences.edit().putInt(fileName, durationSeconds).commit()

    override fun removeDuration(fileName: String): Boolean = preferences.edit().remove(fileName).commit()

    override fun sentNode(noteId: String): String? = preferences.getString("sent-node.$noteId", null)

    override fun saveSentNode(noteId: String, nodeId: String): Boolean =
        preferences.edit().putString("sent-node.$noteId", nodeId).commit()

    override fun removeSentNode(noteId: String): Boolean = preferences.edit().remove("sent-node.$noteId").commit()
}
