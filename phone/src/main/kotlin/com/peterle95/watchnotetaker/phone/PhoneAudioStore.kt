package com.peterle95.watchnotetaker.phone

import android.content.Context
import android.content.SharedPreferences
import com.peterle95.watchnotetaker.notes.NoteCommand
import com.peterle95.watchnotetaker.notes.NoteStateMachine
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.ReviewableNote
import com.peterle95.watchnotetaker.notes.Transition
import com.peterle95.watchnotetaker.notes.TransitionDisposition
import com.peterle95.watchnotetaker.transfer.WearDataProtocol
import java.io.File
import java.io.InputStream
import java.time.Instant

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
    val nextDeliveryAttempt: Long = 1,
    val activeDeliveryAttempt: Long? = null,
)

fun ReceivedRecording.toReviewableNote() = ReviewableNote(
    id = noteId,
    transcript = transcript.orEmpty(),
    status = status,
    createdAt = createdAt,
    nextDeliveryAttempt = nextDeliveryAttempt,
    activeDeliveryAttempt = activeDeliveryAttempt,
)

data class RecordingMetadata(
    val durationSeconds: Int,
    val createdAtMillis: Long,
    val transcript: String? = null,
    val status: NoteStatus = NoteStatus.TRANSCRIBING,
    val transcriptionAttemptCount: Int = 0,
    val lastTranscriptionError: String? = null,
    val nextTranscriptionRetryAtMillis: Long? = null,
    val nextDeliveryAttempt: Long = 1,
    val activeDeliveryAttempt: Long? = null,
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

    fun receive(noteId: String, durationSeconds: Int, input: InputStream, expectedBytes: Long? = null) = synchronized(RECEIVE_LOCK) {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        require(durationSeconds in 1..120) { "Invalid recording duration" }
        require(expectedBytes == null || expectedBytes in 1..MAX_AUDIO_BYTES) { "Invalid recording size" }
        directory.mkdirs()
        val target = File(directory, "$noteId.m4a")
        if (target.isFile) {
            if (metadataStore.load(noteId) == null) {
                check(metadataStore.save(noteId, RecordingMetadata(durationSeconds, target.lastModified()))) {
                    "Could not save recording metadata"
                }
            }
            return@synchronized
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
                require(expectedBytes == null || bytes == expectedBytes) { "Incomplete recording" }
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
                    createdAt = Instant.ofEpochMilli(file.lastModified()),
                    transcriptionAttemptCount = metadata.transcriptionAttemptCount,
                    lastTranscriptionError = metadata.lastTranscriptionError,
                    nextTranscriptionRetryAtMillis = metadata.nextTranscriptionRetryAtMillis,
                    nextDeliveryAttempt = metadata.nextDeliveryAttempt,
                    activeDeliveryAttempt = metadata.activeDeliveryAttempt,
                )
            }
        }
        ?: emptyList()

    @Synchronized
    fun saveTranscript(noteId: String, transcript: String) {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        val normalized = transcript.trim()
        require(normalized.isNotEmpty()) { "Transcript must not be blank" }
        require(File(directory, "$noteId.m4a").isFile) { "Recording does not exist" }
        val current = metadata(noteId)
        if (current.transcript != null) {
            require(current.transcript == normalized) { "Transcript conflicts with persisted transcript" }
            return
        }
        val transition = markReadyForReview(noteId, normalized)
        check(transition.note.status == NoteStatus.READY_FOR_REVIEW) { "Recording cannot be transcribed from ${current.status}" }
    }

    @Synchronized
    fun startTranscriptionAttempt(noteId: String): ReceivedRecording {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
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
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
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
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        val current = metadata(noteId)
        check(current.status == NoteStatus.TRANSCRIBING && current.transcript == null) { "Recording is not awaiting transcription" }
        save(
            noteId,
            current.copy(lastTranscriptionError = null, nextTranscriptionRetryAtMillis = nowMillis),
            "Could not retry transcription",
        )
    }

    @Synchronized
    fun markReadyForReview(noteId: String, transcript: String): Transition {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        val normalized = transcript.trim()
        require(normalized.isNotEmpty()) { "Transcript must not be blank" }
        require(File(directory, "$noteId.m4a").isFile) { "Recording does not exist" }
        val current = metadata(noteId)
        current.transcript?.let { require(it == normalized) { "Transcript conflicts with persisted transcript" } }
        return transition(noteId, current, NoteCommand.MarkReadyForReview, normalized)
    }

    @Synchronized
    fun approve(noteId: String): Transition = transition(noteId, NoteCommand.Approve)

    @Synchronized
    fun reject(noteId: String): Transition = transition(noteId, NoteCommand.Reject)

    @Synchronized
    fun beginDelivery(noteId: String): Transition = transition(noteId, NoteCommand.BeginDelivery)

    @Synchronized
    fun markDelivered(noteId: String, attempt: Long): Transition =
        transition(noteId, NoteCommand.MarkDelivered(attempt))

    @Synchronized
    fun markDeliveryFailed(noteId: String, attempt: Long): Transition =
        transition(noteId, NoteCommand.MarkDeliveryFailed(attempt))

    @Synchronized
    fun retryDelivery(noteId: String): Transition = transition(noteId, NoteCommand.RetryDelivery)

    private fun transition(noteId: String, command: NoteCommand): Transition {
        require(WearDataProtocol.isValidNoteId(noteId)) { "Invalid note ID" }
        return transition(noteId, metadata(noteId), command)
    }

    private fun transition(
        noteId: String,
        current: RecordingMetadata,
        command: NoteCommand,
        transcript: String? = current.transcript,
    ): Transition {
        val transition = NoteStateMachine.execute(current.toReviewableNote(noteId, transcript.orEmpty()), command)
        if (transition.disposition == TransitionDisposition.APPLIED) {
            save(
                noteId,
                current.copy(
                    transcript = transcript,
                    status = transition.note.status,
                    lastTranscriptionError = if (command == NoteCommand.MarkReadyForReview) null else current.lastTranscriptionError,
                    nextTranscriptionRetryAtMillis = if (command == NoteCommand.MarkReadyForReview) null else current.nextTranscriptionRetryAtMillis,
                    nextDeliveryAttempt = transition.note.nextDeliveryAttempt,
                    activeDeliveryAttempt = transition.note.activeDeliveryAttempt,
                ),
                "Could not save note transition",
            )
        }
        return transition
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
        createdAt = Instant.ofEpochMilli(File(directory, "$noteId.m4a").lastModified()),
        nextDeliveryAttempt = nextDeliveryAttempt,
        activeDeliveryAttempt = activeDeliveryAttempt,
    )

    companion object {
        const val MAX_AUDIO_BYTES = 25L * 1_024 * 1_024
        private val RECEIVE_LOCK = Any()
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
            nextDeliveryAttempt = preferences.getLong("nextDeliveryAttempt.$noteId", 1),
            activeDeliveryAttempt = preferences.longOrNull("activeDeliveryAttempt.$noteId"),
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
        .putLong("nextDeliveryAttempt.$noteId", metadata.nextDeliveryAttempt)
        .putNullableLong("activeDeliveryAttempt.$noteId", metadata.activeDeliveryAttempt)
        .commit()

    override fun remove(noteId: String): Boolean = preferences.edit()
        .remove("duration.$noteId")
        .remove("created.$noteId")
        .remove("transcript.$noteId")
        .remove("status.$noteId")
        .remove("transcriptionAttempts.$noteId")
        .remove("transcriptionError.$noteId")
        .remove("transcriptionRetry.$noteId")
        .remove("nextDeliveryAttempt.$noteId")
        .remove("activeDeliveryAttempt.$noteId")
        .commit()

    private fun SharedPreferences.longOrNull(key: String): Long? = if (contains(key)) getLong(key, 0) else null

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) =
        if (value == null) remove(key) else putString(key, value)

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?) =
        if (value == null) remove(key) else putLong(key, value)
}
