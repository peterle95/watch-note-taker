package com.peterle95.watchnotetaker.watch

import android.content.Context
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
        if (!recording.renameTo(target)) {
            recording.copyTo(target, overwrite = false)
            recording.delete()
        }
        preferences.edit().putInt(target.name, durationSeconds).apply()
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

    fun remove(noteId: String) {
        val file = File(directory, "$noteId.m4a")
        if (file.delete()) preferences.edit().remove(file.name).apply()
    }

    companion object {
        const val CAPACITY = 10
    }
}
