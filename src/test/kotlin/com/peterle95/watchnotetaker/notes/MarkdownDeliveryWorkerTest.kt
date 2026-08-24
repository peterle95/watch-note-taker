package com.peterle95.watchnotetaker.notes

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownDeliveryWorkerTest {
    @Test
    fun `does not write a note until the Fold has approved it`() {
        val vault = Files.createTempDirectory("watch-note-vault")
        val worker = MarkdownDeliveryWorker(vault)
        val ready = note(NoteStatus.READY_FOR_REVIEW)

        val result = worker.deliver(ready)

        assertEquals(TransitionDisposition.INVALID, result.transitionDisposition)
        assertEquals(ready, result.note)
        assertTrue(Files.list(vault).use { it.findAny().isEmpty })
    }

    @Test
    fun `writes an approved note with its stable identity and metadata then reports delivery`() {
        val vault = Files.createTempDirectory("watch-note-vault")
        val worker = MarkdownDeliveryWorker(vault)
        val approved = note(NoteStatus.APPROVED)

        val result = worker.deliver(approved)
        val expectedPath = vault.resolve("2026-08-24T15-00-00Z-note-123.md")

        assertEquals(NoteStatus.DELIVERED, result.note.status)
        assertEquals(TransitionDisposition.APPLIED, result.transitionDisposition)
        assertTrue(Files.exists(expectedPath))
        assertEquals(
            """
            ---
            created: 2026-08-24T15:00:00Z
            source: watch-note-taker
            status: approved
            ---

            Buy coffee beans and oatmeal.
            """.trimIndent() + "\n",
            Files.readString(expectedPath),
        )
    }

    @Test
    fun `retrying after a write failure keeps the transcript and writes exactly one Markdown file`() {
        val vault = Files.createTempDirectory("watch-note-vault")
        val worker = MarkdownDeliveryWorker(vault)
        val approved = note(NoteStatus.APPROVED)
        val blockedTarget = vault.resolve("2026-08-24T15-00-00Z-note-123.md")
        Files.createDirectory(blockedTarget)

        val failed = worker.deliver(approved)
        Files.delete(blockedTarget)
        val retried = worker.deliver(failed.note)

        assertEquals(NoteStatus.DELIVERY_FAILED, failed.note.status)
        assertEquals(approved.transcript, failed.note.transcript)
        assertEquals(NoteStatus.DELIVERED, retried.note.status)
        assertEquals(
            listOf("2026-08-24T15-00-00Z-note-123.md"),
            Files.list(vault).use { files -> files.map { it.fileName.toString() }.toList() },
        )
    }

    @Test
    fun `an approved note recovers from an interrupted status update without creating a duplicate file`() {
        val vault = Files.createTempDirectory("watch-note-vault")
        val firstWorker = MarkdownDeliveryWorker(vault)
        val approved = note(NoteStatus.APPROVED)
        firstWorker.deliver(approved)

        val recovered = MarkdownDeliveryWorker(vault).deliver(approved)

        assertEquals(NoteStatus.DELIVERED, recovered.note.status)
        assertEquals(TransitionDisposition.APPLIED, recovered.transitionDisposition)
        assertEquals(
            listOf("2026-08-24T15-00-00Z-note-123.md"),
            Files.list(vault).use { files -> files.map { it.fileName.toString() }.toList() },
        )
    }

    @Test
    fun `repeating delivery after a completed write does not create another file`() {
        val vault = Files.createTempDirectory("watch-note-vault")
        val worker = MarkdownDeliveryWorker(vault)
        val delivered = worker.deliver(note(NoteStatus.APPROVED)).note

        val repeated = worker.deliver(delivered)

        assertEquals(NoteStatus.DELIVERED, repeated.note.status)
        assertEquals(TransitionDisposition.ALREADY_APPLIED, repeated.transitionDisposition)
        assertEquals(
            listOf("2026-08-24T15-00-00Z-note-123.md"),
            Files.list(vault).use { files -> files.map { it.fileName.toString() }.toList() },
        )
        assertFalse(Files.exists(vault.resolve("2026-08-24T15-00-00Z-note-123-2.md")))
    }

    private fun note(status: NoteStatus) = ReviewableNote(
        id = "note-123",
        transcript = "Buy coffee beans and oatmeal.",
        status = status,
        createdAt = Instant.parse("2026-08-24T15:00:00Z"),
    )
}
