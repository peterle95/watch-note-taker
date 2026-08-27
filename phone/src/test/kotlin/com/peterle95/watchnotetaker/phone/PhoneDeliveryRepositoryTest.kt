package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.MarkdownDeliveryTransport
import com.peterle95.watchnotetaker.notes.MarkdownConflictException
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.ReviewableNote
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PhoneDeliveryRepositoryTest {
    private val directory = Files.createTempDirectory("phone-delivery").toFile()
    private val metadata = DeliveryMetadataStore()
    private val store = PhoneAudioStore(directory, metadata)

    @Test
    fun `only approved notes are delivered`() {
        val approved = addReady("123e4567-e89b-12d3-a456-426614174000")
        val rejected = addReady("123e4567-e89b-12d3-a456-426614174001")
        store.approve(approved)
        store.reject(rejected)
        val written = mutableListOf<ReviewableNote>()

        val result = PhoneDeliveryRepository(store, MarkdownDeliveryTransport(written::add)).runPending()

        assertEquals(DeliveryRun.COMPLETED, result)
        assertEquals(listOf(approved), written.map(ReviewableNote::id))
        assertEquals(NoteStatus.APPROVED, written.single().status)
        assertEquals(NoteStatus.DELIVERED, recording(approved).status)
        assertEquals(NoteStatus.REJECTED, recording(rejected).status)
    }

    @Test
    fun `failed delivery persists a retryable new attempt`() {
        val id = addReady("123e4567-e89b-12d3-a456-426614174000")
        store.approve(id)
        val attempts = mutableListOf<Long?>()
        var available = false
        val repository = PhoneDeliveryRepository(store, MarkdownDeliveryTransport { note ->
            attempts += note.activeDeliveryAttempt
            if (!available) error("provider unavailable")
        })

        assertEquals(DeliveryRun.RETRY, repository.runPending())
        assertEquals(NoteStatus.DELIVERY_FAILED, recording(id).status)
        assertNull(recording(id).activeDeliveryAttempt)

        available = true
        assertEquals(DeliveryRun.COMPLETED, repository.runPending())
        assertEquals(listOf<Long?>(1, 2), attempts)
        assertEquals(NoteStatus.DELIVERED, recording(id).status)
        assertEquals(3, recording(id).nextDeliveryAttempt)
    }

    @Test
    fun `multiple approved notes are attempted in one run`() {
        val ids = listOf(
            addReady("123e4567-e89b-12d3-a456-426614174000"),
            addReady("123e4567-e89b-12d3-a456-426614174001"),
        )
        ids.forEach(store::approve)
        val written = mutableListOf<String>()

        assertEquals(
            DeliveryRun.COMPLETED,
            PhoneDeliveryRepository(store, MarkdownDeliveryTransport { written += it.id }).runPending(),
        )

        assertEquals(ids, written)
        assertEquals(listOf(NoteStatus.DELIVERED, NoteStatus.DELIVERED), ids.map { recording(it).status })
    }

    @Test
    fun `completed delivery is not retried`() {
        val id = addReady("123e4567-e89b-12d3-a456-426614174000")
        store.approve(id)
        var writes = 0
        val repository = PhoneDeliveryRepository(store, MarkdownDeliveryTransport { writes++ })

        assertEquals(DeliveryRun.COMPLETED, repository.runPending())
        assertEquals(DeliveryRun.IDLE, repository.runPending())

        assertEquals(1, writes)
        assertEquals(2, recording(id).nextDeliveryAttempt)
    }

    @Test
    fun `conflicting destination is terminal until user retries`() {
        val id = addReady("123e4567-e89b-12d3-a456-426614174000")
        store.approve(id)
        var writes = 0
        val repository = PhoneDeliveryRepository(store, MarkdownDeliveryTransport {
            writes++
            throw MarkdownConflictException("different content")
        })

        assertEquals(DeliveryRun.CONFLICT, repository.runPending())
        assertEquals(NoteStatus.DELIVERY_FAILED, recording(id).status)
        assertEquals(1, writes)
    }

    @Test
    fun `persisted active attempt resumes without allocating a duplicate`() {
        val id = addReady("123e4567-e89b-12d3-a456-426614174000")
        store.approve(id)
        store.beginDelivery(id)
        var deliveredAttempt: Long? = null

        assertEquals(
            DeliveryRun.COMPLETED,
            PhoneDeliveryRepository(store, MarkdownDeliveryTransport {
                deliveredAttempt = it.activeDeliveryAttempt
            }).runPending(),
        )

        assertEquals(1L, deliveredAttempt)
        assertEquals(2, recording(id).nextDeliveryAttempt)
        assertEquals(NoteStatus.DELIVERED, recording(id).status)
    }

    private fun addReady(id: String): String {
        store.receive(id, 10, ByteArrayInputStream("audio".encodeToByteArray()))
        store.markReadyForReview(id, "Transcript")
        return id
    }

    private fun recording(id: String) = store.recordings().first { it.noteId == id }
}

private class DeliveryMetadataStore : RecordingMetadataStore {
    private val values = mutableMapOf<String, RecordingMetadata>()

    override fun load(noteId: String): RecordingMetadata? = values[noteId]

    override fun save(noteId: String, metadata: RecordingMetadata): Boolean {
        values[noteId] = metadata
        return true
    }

    override fun remove(noteId: String): Boolean = values.remove(noteId) != null
}
