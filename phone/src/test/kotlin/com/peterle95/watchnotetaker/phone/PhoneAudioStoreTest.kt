package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.TransitionDisposition
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneAudioStoreTest {
    private val directory = Files.createTempDirectory("phone-audio-store").toFile()
    private val metadata = InMemoryRecordingMetadataStore()
    private val noteId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `received recording and transcription state survive recreation`() {
        store().receive(noteId, 12, audio("first"))
        store().recordTranscriptionFailure(noteId, 1, "network", 1_000)

        val recording = store().recordings().single()

        assertContentEquals("first".encodeToByteArray(), recording.file.readBytes())
        assertEquals(1, recording.transcriptionAttemptCount)
        assertEquals("network", recording.lastTranscriptionError)
        assertEquals(1_000, recording.nextTranscriptionRetryAtMillis)
        assertEquals(NoteStatus.TRANSCRIBING, recording.status)
    }

    @Test
    fun `duplicate receipt keeps original audio and metadata`() {
        store().receive(noteId, 12, audio("first"))
        store().receive(noteId, 30, audio("replacement"))

        val recording = store().recordings().single()
        assertContentEquals("first".encodeToByteArray(), recording.file.readBytes())
        assertEquals(12, recording.durationSeconds)
    }

    @Test
    fun `partial temporary file is not exposed`() {
        directory.mkdirs()
        directory.resolve(".$noteId.tmp").writeText("partial")

        assertTrue(store().recordings().isEmpty())
    }

    @Test
    fun `invalid note ids and durations are rejected`() {
        assertFailsWith<IllegalArgumentException> { store().receive("../escape", 10, audio("x")) }
        assertFailsWith<IllegalArgumentException> { store().receive(noteId, 0, audio("x")) }
        assertFailsWith<IllegalArgumentException> { store().receive(noteId, 121, audio("x")) }
    }

    @Test
    fun `metadata failure does not expose audio`() {
        val failing = PhoneAudioStore(directory, FailingRecordingMetadataStore())

        assertFailsWith<IllegalStateException> { failing.receive(noteId, 10, audio("x")) }
        assertTrue(failing.recordings().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "m4a" })
    }

    @Test
    fun `truncated channel payload is not exposed or acknowledged as complete`() {
        assertFailsWith<IllegalArgumentException> {
            store().receive(noteId, 10, audio("short"), expectedBytes = 100)
        }

        assertTrue(store().recordings().isEmpty())
        assertTrue(directory.listFiles().orEmpty().none { it.extension == "m4a" })
    }

    @Test
    fun `failed transcription remains retryable`() {
        store().receive(noteId, 12, audio("first"))
        store().recordTranscriptionFailure(noteId, 2, "server", 2_000)

        val failed = store().recordings().single()
        assertEquals(NoteStatus.TRANSCRIBING, failed.status)
        assertEquals(2_000, failed.nextTranscriptionRetryAtMillis)

        store().saveTranscript(noteId, " Retried transcript ")
        val succeeded = store().recordings().single()
        assertEquals("Retried transcript", succeeded.transcript)
        assertEquals(NoteStatus.READY_FOR_REVIEW, succeeded.status)
        assertNull(succeeded.lastTranscriptionError)
        assertNull(succeeded.nextTranscriptionRetryAtMillis)
    }

    @Test
    fun `successful transcription is idempotent`() {
        store().receive(noteId, 12, audio("first"))
        store().saveTranscript(noteId, "Transcript")
        store().saveTranscript(noteId, "Transcript")

        val recording = store().recordings().single()
        assertEquals("Transcript", recording.transcript)
        assertEquals(NoteStatus.READY_FOR_REVIEW, recording.status)
    }

    @Test
    fun `marking ready reports idempotent and invalid completions without changing state`() {
        store().receive(noteId, 12, audio("first"))

        val ready = store().markReadyForReview(noteId, "Transcript")
        val repeated = store().markReadyForReview(noteId, "Transcript")
        store().approve(noteId)
        val late = store().markReadyForReview(noteId, "Transcript")

        assertEquals(TransitionDisposition.APPLIED, ready.disposition)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeated.disposition)
        assertEquals(TransitionDisposition.INVALID, late.disposition)
        assertEquals(NoteStatus.APPROVED, store().recordings().single().status)
    }

    @Test
    fun `legacy metadata uses the audio timestamp in persisted transitions`() {
        store().receive(noteId, 12, audio("first"))
        val timestamp = 1_700_000_000_000
        store().recordings().single().file.setLastModified(timestamp)
        metadata.save(noteId, metadata.load(noteId)!!.copy(createdAtMillis = 0))

        val ready = store().markReadyForReview(noteId, "Transcript")

        assertEquals(Instant.ofEpochMilli(timestamp), ready.note.createdAt)
    }

    @Test
    fun `ready notes can be approved or rejected and other notes cannot be reviewed`() {
        ready()

        assertEquals(TransitionDisposition.APPLIED, store().approve(noteId).disposition)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, store().approve(noteId).disposition)
        assertEquals(TransitionDisposition.INVALID, store().reject(noteId).disposition)
        assertEquals(NoteStatus.APPROVED, store().recordings().single().status)

        val rejectedNoteId = "123e4567-e89b-12d3-a456-426614174001"
        ready(rejectedNoteId)
        assertEquals(TransitionDisposition.APPLIED, store().reject(rejectedNoteId).disposition)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, store().reject(rejectedNoteId).disposition)
        assertEquals(TransitionDisposition.INVALID, store().approve(rejectedNoteId).disposition)
        assertEquals(NoteStatus.REJECTED, store().recordings().first { it.noteId == rejectedNoteId }.status)

        val transcribingNoteId = "123e4567-e89b-12d3-a456-426614174002"
        store().receive(transcribingNoteId, 12, audio("third"))
        assertEquals(TransitionDisposition.INVALID, store().approve(transcribingNoteId).disposition)
        assertEquals(TransitionDisposition.INVALID, store().reject(transcribingNoteId).disposition)
        assertEquals(NoteStatus.TRANSCRIBING, store().recordings().first { it.noteId == transcribingNoteId }.status)
    }

    @Test
    fun `delivery transitions persist failure retry and success`() {
        ready()
        store().approve(noteId)

        val firstAttempt = store().beginDelivery(noteId)
        assertEquals(TransitionDisposition.APPLIED, firstAttempt.disposition)
        assertEquals(1, firstAttempt.note.activeDeliveryAttempt)
        assertEquals(2, firstAttempt.note.nextDeliveryAttempt)
        assertEquals(
            TransitionDisposition.APPLIED,
            store().markDeliveryFailed(noteId, 1, DeliveryFailureClassification.CONFLICT).disposition,
        )
        assertEquals(DeliveryFailureClassification.CONFLICT, store().recordings().single().deliveryFailureClassification)
        assertEquals(
            TransitionDisposition.ALREADY_APPLIED,
            store().markDeliveryFailed(noteId, 1, DeliveryFailureClassification.CONFLICT).disposition,
        )
        assertEquals(TransitionDisposition.APPLIED, store().retryDelivery(noteId).disposition)
        assertNull(store().recordings().single().deliveryFailureClassification)

        val secondAttempt = store().beginDelivery(noteId)
        assertEquals(2, secondAttempt.note.activeDeliveryAttempt)
        assertEquals(TransitionDisposition.APPLIED, store().markDelivered(noteId, 2).disposition)

        val delivered = store().recordings().single().toReviewableNote()
        assertEquals(NoteStatus.DELIVERED, delivered.status)
        assertEquals(3, delivered.nextDeliveryAttempt)
        assertNull(delivered.activeDeliveryAttempt)
    }

    @Test
    fun `invalid delivery transitions do not change persisted state`() {
        ready()
        val ready = store().recordings().single().toReviewableNote()

        assertEquals(TransitionDisposition.INVALID, store().beginDelivery(noteId).disposition)
        assertEquals(TransitionDisposition.INVALID, store().retryDelivery(noteId).disposition)
        assertEquals(TransitionDisposition.INVALID, store().markDelivered(noteId, 1).disposition)
        assertEquals(
            TransitionDisposition.INVALID,
            store().markDeliveryFailed(noteId, 1, DeliveryFailureClassification.TRANSIENT).disposition,
        )
        assertEquals(ready, store().recordings().single().toReviewableNote())
    }

    @Test
    fun `active delivery attempt survives recreation and begin is idempotent`() {
        ready()
        store().approve(noteId)
        val started = store().beginDelivery(noteId)

        val restarted = store().beginDelivery(noteId)

        assertEquals(TransitionDisposition.ALREADY_APPLIED, restarted.disposition)
        assertEquals(started.note, restarted.note)
        assertEquals(started.note, store().recordings().single().toReviewableNote())
    }

    @Test
    fun `stale delivery completion is rejected without changing active attempt`() {
        ready()
        store().approve(noteId)
        store().beginDelivery(noteId)
        store().markDeliveryFailed(noteId, 1, DeliveryFailureClassification.TRANSIENT)
        store().retryDelivery(noteId)
        val secondAttempt = store().beginDelivery(noteId).note

        assertEquals(TransitionDisposition.INVALID, store().markDelivered(noteId, 1).disposition)
        assertEquals(
            TransitionDisposition.INVALID,
            store().markDeliveryFailed(noteId, 1, DeliveryFailureClassification.TRANSIENT).disposition,
        )
        assertEquals(secondAttempt, store().recordings().single().toReviewableNote())
    }

    @Test
    fun `delivery completion is idempotent`() {
        ready()
        store().approve(noteId)
        store().beginDelivery(noteId)

        val delivered = store().markDelivered(noteId, 1)
        val repeated = store().markDelivered(noteId, 1)

        assertEquals(TransitionDisposition.APPLIED, delivered.disposition)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeated.disposition)
        assertEquals(delivered.note, repeated.note)
    }

    @Test
    fun `transcription repository persists success and attempt count`() {
        store().receive(noteId, 12, audio("first"))
        val repository = TranscriptionRepository(
            store(),
            QueueTranscriptionClient(TranscriptionResult.Success("Transcript")),
        )

        assertEquals(TranscriptionRun.COMPLETED, repository.runNext(1_000))

        val recording = store().recordings().single()
        assertEquals(1, recording.transcriptionAttemptCount)
        assertEquals("Transcript", recording.transcript)
        assertEquals(NoteStatus.READY_FOR_REVIEW, recording.status)
    }

    @Test
    fun `transient failure waits for bounded retry and keeps audio`() {
        store().receive(noteId, 12, audio("first"))
        val repository = TranscriptionRepository(
            store(),
            QueueTranscriptionClient(
                TranscriptionResult.NetworkFailure,
                TranscriptionResult.Success("Recovered"),
            ),
        )

        assertEquals(TranscriptionRun.RETRY_SCHEDULED, repository.runNext(1_000))
        assertEquals(TranscriptionRun.WAITING, repository.runNext(1_001))
        val retryAt = store().recordings().single().nextTranscriptionRetryAtMillis!!
        assertEquals(TranscriptionRun.COMPLETED, repository.runNext(retryAt))

        val recording = store().recordings().single()
        assertEquals(2, recording.transcriptionAttemptCount)
        assertEquals("Recovered", recording.transcript)
        assertTrue(recording.file.isFile)
    }

    @Test
    fun `authentication failure does not retry automatically`() {
        store().receive(noteId, 12, audio("first"))
        val client = QueueTranscriptionClient(TranscriptionResult.AuthenticationFailure)
        val repository = TranscriptionRepository(store(), client)

        assertEquals(TranscriptionRun.BLOCKED, repository.runNext(1_000))
        assertEquals(TranscriptionRun.IDLE, repository.runNext(100_000))
        assertEquals(1, client.calls)
        assertNull(store().recordings().single().nextTranscriptionRetryAtMillis)
    }

    @Test
    fun `restart during a transcription attempt remains retryable`() {
        store().receive(noteId, 12, audio("first"))
        store().recordTranscriptionFailure(noteId, 1, "network", 1_000)
        store().startTranscriptionAttempt(noteId)

        val repository = TranscriptionRepository(
            store(),
            QueueTranscriptionClient(TranscriptionResult.Success("Recovered after restart")),
        )

        assertEquals(TranscriptionRun.COMPLETED, repository.runNext(2_000))
        assertEquals("Recovered after restart", store().recordings().single().transcript)
    }

    @Test
    fun `manual client uses normal transcription persistence`() {
        store().receive(noteId, 12, audio("first"))

        TranscriptionRepository(store(), ManualTranscriptionClient(" Manual ")).runNext(1_000)

        assertEquals("Manual", store().recordings().single().transcript)
        assertEquals(NoteStatus.READY_FOR_REVIEW, store().recordings().single().status)
    }

    private fun store() = PhoneAudioStore(directory, metadata)

    private fun ready(id: String = noteId) {
        store().receive(id, 12, audio("audio"))
        assertEquals(TransitionDisposition.APPLIED, store().markReadyForReview(id, "Transcript").disposition)
    }

    private fun audio(value: String) = ByteArrayInputStream(value.encodeToByteArray())
}

private class InMemoryRecordingMetadataStore : RecordingMetadataStore {
    private val values = mutableMapOf<String, RecordingMetadata>()

    override fun load(noteId: String): RecordingMetadata? = values[noteId]

    override fun save(noteId: String, metadata: RecordingMetadata): Boolean {
        values[noteId] = metadata
        return true
    }

    override fun remove(noteId: String): Boolean = values.remove(noteId) != null
}

private class FailingRecordingMetadataStore : RecordingMetadataStore {
    override fun load(noteId: String): RecordingMetadata? = null
    override fun save(noteId: String, metadata: RecordingMetadata): Boolean = false
    override fun remove(noteId: String): Boolean = true
}

private class QueueTranscriptionClient(vararg results: TranscriptionResult) : TranscriptionClient {
    private val results = ArrayDeque(results.toList())
    var calls = 0

    override fun transcribe(audio: ReceivedRecording): TranscriptionResult {
        calls++
        return results.removeFirst()
    }
}
