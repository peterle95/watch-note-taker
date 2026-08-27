package com.peterle95.watchnotetaker.phone

import android.content.Context
import java.io.File
import java.io.InputStream

data class ReceivedRecording(
    val noteId: String,
    val durationSeconds: Int,
    val file: File,
    val transcript: String?,
)

class PhoneAudioStore(context: Context) {
    private val directory = File(context.filesDir, "received-audio")
    private val preferences = context.getSharedPreferences("received-audio", Context.MODE_PRIVATE)

    fun receive(noteId: String, durationSeconds: Int, input: InputStream) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        require(durationSeconds in 1..120) { "Invalid recording duration" }
        directory.mkdirs()
        val target = File(directory, "$noteId.m4a")
        if (!target.exists()) {
            val temporary = File.createTempFile(".$noteId-", ".tmp", directory)
            try {
                temporary.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
                check(temporary.renameTo(target)) { "Could not store recording" }
            } finally {
                temporary.delete()
            }
        }
        check(preferences.edit().putInt(target.name, durationSeconds).commit()) { "Could not save recording metadata" }
    }

    fun recordings(): List<ReceivedRecording> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "m4a" }
        ?.sortedBy(File::lastModified)
        ?.map { file ->
            ReceivedRecording(
                noteId = file.nameWithoutExtension,
                durationSeconds = preferences.getInt(file.name, 0),
                file = file,
                transcript = preferences.getString("transcript.${file.nameWithoutExtension}", null),
            )
        }
        ?: emptyList()

    fun saveTranscript(noteId: String, transcript: String) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        require(transcript.isNotBlank()) { "Transcript must not be blank" }
        require(File(directory, "$noteId.m4a").isFile) { "Recording does not exist" }
        check(preferences.edit().putString("transcript.$noteId", transcript.trim()).commit()) {
            "Could not save transcript"
        }
    }

    companion object {
        private val NOTE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
