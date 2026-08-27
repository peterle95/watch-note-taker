package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.NoteStatus
import java.io.ByteArrayInputStream
import java.nio.file.Files
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
