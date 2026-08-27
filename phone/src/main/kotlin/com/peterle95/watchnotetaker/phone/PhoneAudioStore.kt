package com.peterle95.watchnotetaker.phone

import android.content.Context
import android.content.SharedPreferences
import com.peterle95.watchnotetaker.notes.NoteCommand
import com.peterle95.watchnotetaker.notes.NoteStateMachine
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.ReviewableNote
import java.io.File
import java.io.InputStream
import java.time.Instant

typealias PhoneNoteStatus = NoteStatus

data class ReceivedRecording(
    val noteId: String,
    val durationSeconds: Int,
    val file: File,
    val transcript: String?,
    val status: NoteStatus,
    val createdAt: Instant,
    val transcriptionAttemptCount: Int,
    val lastTranscriptionError: String?,
    val nextTranscriptionRetryAtMillis: Long?,
)

data class RecordingMetadata(
    val durationSeconds: Int,
    val createdAtMillis: Long,
    val transcript: String? = null,
    val status: NoteStatus = NoteStatus.TRANSCRIBING,
    val transcriptionAttemptCount: Int = 0,
    val lastTranscriptionError: String? = null,
    val nextTranscriptionRetryAtMillis: Long? = null,
)

interface RecordingMetadataStore {
    fun load(noteId: String): RecordingMetadata?
    fun save(noteId: String, metadata: RecordingMetadata): Boolean
    fun remove(noteId: String): Boolean
}

class PhoneAudioStore(
    private val directory: File,
    private val metadataStore: RecordingMetadataStore,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "received-audio"),
        SharedPreferencesRecordingMetadataStore(
            context.getSharedPreferences("received-audio", Context.MODE_PRIVATE),
        ),
    )

    @Synchronized
    fun receive(noteId: String, durationSeconds: Int, input: InputStream) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        require(durationSeconds in 1..120) { "Invalid recording duration" }
        directory.mkdirs()
        val target = File(directory, "$noteId.m4a")
        if (target.isFile) {
            if (metadataStore.load(noteId) == null) {
                check(metadataStore.save(noteId, RecordingMetadata(durationSeconds, target.lastModified()))) {
                    "Could not save recording metadata"
                }
            }
            return
        }

        val temporary = File.createTempFile(".$noteId-", ".tmp", directory)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes += count
                    require(bytes <= MAX_AUDIO_BYTES) { "Recording is too large" }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            check(metadataStore.save(noteId, RecordingMetadata(durationSeconds, System.currentTimeMillis()))) {
                "Could not save recording metadata"
            }
            if (!temporary.renameTo(target)) {
                metadataStore.remove(noteId)
                error("Could not store recording")
            }
        } finally {
            temporary.delete()
        }
    }

    fun recordings(): List<ReceivedRecording> = directory.listFiles()
        ?.filter { it.isFile && it.extension == "m4a" }
        ?.sortedBy(File::lastModified)
        ?.mapNotNull { file ->
            metadataStore.load(file.nameWithoutExtension)?.let { metadata ->
                ReceivedRecording(
                    noteId = file.nameWithoutExtension,
                    durationSeconds = metadata.durationSeconds,
                    file = file,
                    transcript = metadata.transcript,
                    status = metadata.status,
                    createdAt = Instant.ofEpochMilli(metadata.createdAtMillis.takeIf { it > 0 } ?: file.lastModified()),
                    transcriptionAttemptCount = metadata.transcriptionAttemptCount,
                    lastTranscriptionError = metadata.lastTranscriptionError,
                    nextTranscriptionRetryAtMillis = metadata.nextTranscriptionRetryAtMillis,
                )
            }
        }
        ?: emptyList()

    @Synchronized
    fun saveTranscript(noteId: String, transcript: String) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        val normalized = transcript.trim()
        require(normalized.isNotEmpty()) { "Transcript must not be blank" }
        require(File(directory, "$noteId.m4a").isFile) { "Recording does not exist" }
        val current = metadata(noteId)
        if (current.transcript != null) {
            require(current.transcript == normalized) { "Transcript conflicts with persisted transcript" }
            return
        }
        val transition = NoteStateMachine.execute(
            current.toReviewableNote(noteId, normalized),
            NoteCommand.MarkReadyForReview,
        )
        check(transition.note.status == NoteStatus.READY_FOR_REVIEW) { "Recording cannot be transcribed from ${current.status}" }
        save(
            noteId,
            current.copy(
                transcript = normalized,
                status = transition.note.status,
                lastTranscriptionError = null,
                nextTranscriptionRetryAtMillis = null,
            ),
            "Could not save transcript",
        )
    }

    @Synchronized
    fun startTranscriptionAttempt(noteId: String): ReceivedRecording {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        val current = metadata(noteId)
        check(current.status == NoteStatus.TRANSCRIBING && current.transcript == null) { "Recording is not awaiting transcription" }
        save(
            noteId,
            current.copy(
                transcriptionAttemptCount = current.transcriptionAttemptCount + 1,
                lastTranscriptionError = null,
                nextTranscriptionRetryAtMillis = null,
            ),
            "Could not save transcription attempt",
        )
        return recordings().first { it.noteId == noteId }
    }

    @Synchronized
    fun recordTranscriptionFailure(noteId: String, attemptCount: Int, error: String, nextRetryAtMillis: Long?) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        require(attemptCount > 0) { "Attempt count must be positive" }
        require(error.isNotBlank()) { "Transcription error must not be blank" }
        val current = metadata(noteId)
        if (current.status != NoteStatus.TRANSCRIBING) return
        save(
            noteId,
            current.copy(
                transcriptionAttemptCount = maxOf(current.transcriptionAttemptCount, attemptCount),
                lastTranscriptionError = error,
                nextTranscriptionRetryAtMillis = nextRetryAtMillis,
            ),
            "Could not save transcription failure",
        )
    }

    @Synchronized
    fun retryTranscription(noteId: String, nowMillis: Long) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        val current = metadata(noteId)
        check(current.status == NoteStatus.TRANSCRIBING && current.transcript == null) { "Recording is not awaiting transcription" }
        save(
            noteId,
            current.copy(lastTranscriptionError = null, nextTranscriptionRetryAtMillis = nowMillis),
            "Could not retry transcription",
        )
    }

    fun decide(noteId: String, approved: Boolean): NoteStatus {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        val current = metadata(noteId)
        val transcript = current.transcript ?: return current.status
        val transition = NoteStateMachine.execute(
            current.toReviewableNote(noteId, transcript),
            if (approved) NoteCommand.Approve else NoteCommand.Reject,
        )
        if (transition.note.status != current.status) {
            save(noteId, current.copy(status = transition.note.status), "Could not save review decision")
        }
        return transition.note.status
    }

    fun markDelivery(noteId: String, delivered: Boolean) {
        require(noteId.matches(NOTE_ID)) { "Invalid note ID" }
        val current = metadata(noteId)
        require(current.status == NoteStatus.APPROVED || current.status == NoteStatus.DELIVERY_FAILED) {
            "Only approved notes can be delivered"
        }
        save(
            noteId,
            current.copy(status = if (delivered) NoteStatus.DELIVERED else NoteStatus.DELIVERY_FAILED),
            "Could not save delivery status",
        )
    }

    private fun metadata(noteId: String): RecordingMetadata =
        metadataStore.load(noteId) ?: error("Recording does not exist")

    private fun save(noteId: String, metadata: RecordingMetadata, message: String) {
        check(metadataStore.save(noteId, metadata)) { message }
    }

    private fun RecordingMetadata.toReviewableNote(noteId: String, transcript: String) = ReviewableNote(
        id = noteId,
        transcript = transcript,
        status = status,
        createdAt = Instant.ofEpochMilli(createdAtMillis),
    )

    companion object {
        const val MAX_AUDIO_BYTES = 25L * 1_024 * 1_024
        private val NOTE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

private class SharedPreferencesRecordingMetadataStore(
    private val preferences: SharedPreferences,
) : RecordingMetadataStore {
    override fun load(noteId: String): RecordingMetadata? {
        val durationKey = "duration.$noteId"
        val legacyDurationKey = "$noteId.m4a"
        if (!preferences.contains(durationKey) && !preferences.contains(legacyDurationKey)) return null
        return RecordingMetadata(
            durationSeconds = preferences.getInt(durationKey, preferences.getInt(legacyDurationKey, 0)),
            createdAtMillis = preferences.getLong("created.$noteId", 0),
            transcript = preferences.getString("transcript.$noteId", null),
            status = preferences.getString("status.$noteId", NoteStatus.TRANSCRIBING.name)
                ?.let(NoteStatus::valueOf) ?: NoteStatus.TRANSCRIBING,
            transcriptionAttemptCount = preferences.getInt("transcriptionAttempts.$noteId", 0),
            lastTranscriptionError = preferences.getString("transcriptionError.$noteId", null),
            nextTranscriptionRetryAtMillis = preferences.longOrNull("transcriptionRetry.$noteId"),
        )
    }

    override fun save(noteId: String, metadata: RecordingMetadata): Boolean = preferences.edit()
        .putInt("duration.$noteId", metadata.durationSeconds)
        .putLong("created.$noteId", metadata.createdAtMillis)
        .putString("status.$noteId", metadata.status.name)
        .putInt("transcriptionAttempts.$noteId", metadata.transcriptionAttemptCount)
        .putNullableString("transcript.$noteId", metadata.transcript)
        .putNullableString("transcriptionError.$noteId", metadata.lastTranscriptionError)
        .putNullableLong("transcriptionRetry.$noteId", metadata.nextTranscriptionRetryAtMillis)
        .commit()

    override fun remove(noteId: String): Boolean = preferences.edit()
        .remove("duration.$noteId")
        .remove("created.$noteId")
        .remove("transcript.$noteId")
        .remove("status.$noteId")
        .remove("transcriptionAttempts.$noteId")
        .remove("transcriptionError.$noteId")
        .remove("transcriptionRetry.$noteId")
        .commit()

    private fun SharedPreferences.longOrNull(key: String): Long? = if (contains(key)) getLong(key, 0) else null

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?) =
        if (value == null) remove(key) else putLong(key, value)
}
