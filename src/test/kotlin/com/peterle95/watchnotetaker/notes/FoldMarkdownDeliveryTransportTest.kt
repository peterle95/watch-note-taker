package com.peterle95.watchnotetaker.notes

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FoldMarkdownDeliveryTransportTest {
    @Test
    fun `a rejection is persisted and acknowledged to the watch without writing Markdown`() {
        val root = Files.createTempDirectory("fold-rejection")
        val noteStorePath = root.resolve("fold-notes.properties")
        val vault = root.resolve("vault")
        val note = ReviewableNote(
            id = "note-123",
            transcript = "Discard this note.",
            status = NoteStatus.READY_FOR_REVIEW,
            createdAt = Instant.parse("2026-08-24T15:00:00Z"),
        )
        FileFoldNoteStore(noteStorePath).save(note)

        val result = FoldMarkdownDeliveryTransport(
            FileFoldNoteStore(noteStorePath),
            MarkdownDeliveryWorker(vault),
        ).send(PendingReviewDecision(note.id, ReviewDecision.Reject, note.transcript))

        assertEquals(
            ReviewDecisionDelivery.Delivered(AuthoritativeReviewStatus(note.id, note.transcript, NoteStatus.REJECTED)),
            result,
        )
        assertEquals(NoteStatus.REJECTED, FileFoldNoteStore(noteStorePath).note(note.id)?.status)
        assertEquals(false, Files.exists(vault))
    }

    @Test
    fun `approval is durably delivered to the watch after a Fold restart and a retry`() {
        val root = Files.createTempDirectory("fold-delivery")
        val noteStorePath = root.resolve("fold-notes.properties")
        val watchStorePath = root.resolve("watch-outbox.properties")
        val vault = root.resolve("vault")
        val note = ReviewableNote(
            id = "note-123",
            transcript = "Buy coffee beans and oatmeal.",
            status = NoteStatus.READY_FOR_REVIEW,
            createdAt = Instant.parse("2026-08-24T15:00:00Z"),
        )
        FileFoldNoteStore(noteStorePath).save(note)
        Files.createDirectories(vault)
        Files.createDirectory(vault.resolve("2026-08-24T15-00-00Z-note-123.md"))

        val disconnectedWatch = ReviewDecisionOutbox(
            FileReviewDecisionStore(watchStorePath),
            FoldMarkdownDeliveryTransport(FileFoldNoteStore(noteStorePath), MarkdownDeliveryWorker(vault)),
        )
        disconnectedWatch.queue(ReviewableTranscript(note.id, note.transcript, note.status), ReviewDecision.Approve)
        disconnectedWatch.synchronize()

        assertEquals(NoteStatus.DELIVERY_FAILED, FileFoldNoteStore(noteStorePath).note(note.id)?.status)
        Files.delete(vault.resolve("2026-08-24T15-00-00Z-note-123.md"))

        val restartedWatch = ReviewDecisionOutbox(
            FileReviewDecisionStore(watchStorePath),
            FoldMarkdownDeliveryTransport(FileFoldNoteStore(noteStorePath), MarkdownDeliveryWorker(vault)),
        )
        restartedWatch.synchronize()

        assertEquals(NoteStatus.DELIVERED, FileFoldNoteStore(noteStorePath).note(note.id)?.status)
        assertEquals(NoteStatus.DELIVERED, restartedWatch.authoritativeStatus(note.id)?.status)
        assertEquals(emptyList(), restartedWatch.pendingDecisions())
        assertEquals(
            "Buy coffee beans and oatmeal.\n",
            Files.readString(vault.resolve("2026-08-24T15-00-00Z-note-123.md")).substringAfter("---\n\n"),
        )
    }
}
