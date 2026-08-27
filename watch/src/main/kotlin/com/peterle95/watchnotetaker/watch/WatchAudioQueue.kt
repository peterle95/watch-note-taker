package com.peterle95.watchnotetaker.watch

import android.content.Context
import com.peterle95.watchnotetaker.transfer.WearDataProtocol
import java.io.File

data class QueuedWatchAudio(
    val noteId: String,
    val file: File,
    val durationSeconds: Int,
)

class WatchAudioQueue(context: Context) {
    private val directory = File(context.filesDir, "queued-audio")
    private val preferences = context.getSharedPreferences("queued-audio", Context.MODE_PRIVATE)

    fun enqueue(recording: File, durationSeconds: Int): Boolean {
        if (entries().size >= CAPACITY) return false
        directory.mkdirs()
        val target = File(directory, recording.name)
        check(preferences.edit().putInt(target.name, durationSeconds).commit()) { "Could not save recording metadata" }
        try {
            if (!recording.renameTo(target)) {
                recording.copyTo(target, overwrite = false)
                recording.delete()
            }
        } catch (error: Exception) {
            preferences.edit().remove(target.name).commit()
            throw error
        }
        return true
    }

    fun entries(): List<QueuedWatchAudio> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "m4a" }
        ?.sortedBy(File::lastModified)
        ?.map { file ->
            QueuedWatchAudio(
                noteId = file.nameWithoutExtension,
                file = file,
                durationSeconds = preferences.getInt(file.name, 0),
            )
        }
        ?: emptyList()

    fun remove(noteId: String): Boolean {
        if (!WearDataProtocol.isValidNoteId(noteId)) return false
        val file = File(directory, "$noteId.m4a")
        if (!file.delete()) return false
        preferences.edit().remove(file.name).commit()
        return true
    }

    fun markSent(noteId: String, nodeId: String) {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        check(preferences.edit().putString("sent-node.$noteId", nodeId).commit()) { "Could not save target node" }
    }

    fun acknowledge(noteId: String, nodeId: String): Boolean {
        if (preferences.getString("sent-node.$noteId", null) != nodeId) return false
        val removed = remove(noteId)
        if (removed) preferences.edit().remove("sent-node.$noteId").commit()
        return removed
    }

    companion object {
        const val CAPACITY = 10
    }
}
