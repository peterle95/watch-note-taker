package com.peterle95.watchnotetaker.notes

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter

data class MarkdownDeliveryResult(
    val note: ReviewableNote,
    val transitionDisposition: TransitionDisposition,
)

/**
 * Fold-side Markdown delivery module.
 *
 * It accepts only Fold-approved notes and owns the filename, metadata, atomic
 * write, retry, and idempotency details behind one delivery interface.
 */
class MarkdownDeliveryWorker(
    private val vaultDirectory: Path,
) {
    fun deliver(note: ReviewableNote): MarkdownDeliveryResult = when (note.status) {
        NoteStatus.APPROVED, NoteStatus.DELIVERY_FAILED -> deliverApproved(note)
        NoteStatus.DELIVERED -> MarkdownDeliveryResult(note, TransitionDisposition.ALREADY_APPLIED)
        else -> MarkdownDeliveryResult(note, TransitionDisposition.INVALID)
    }

    private fun deliverApproved(note: ReviewableNote): MarkdownDeliveryResult {
        val deliveryNote = if (note.status == NoteStatus.DELIVERY_FAILED) {
            NoteStateMachine.execute(note, NoteCommand.RetryDelivery).note
        } else {
            note
        }
        val started = NoteStateMachine.execute(deliveryNote, NoteCommand.BeginDelivery)
        check(started.disposition == TransitionDisposition.APPLIED)
        val attempt = requireNotNull(started.note.activeDeliveryAttempt)

        return try {
            writeIdempotently(started.note)
            processed(NoteStateMachine.execute(started.note, NoteCommand.MarkDelivered(attempt)))
        } catch (_: IOException) {
            processed(NoteStateMachine.execute(started.note, NoteCommand.MarkDeliveryFailed(attempt)))
        }
    }

    private fun writeIdempotently(note: ReviewableNote) {
        Files.createDirectories(vaultDirectory)
        val destination = vaultDirectory.resolve("${filenameTimestamp.format(note.createdAt)}-${note.id}.md")
        val content = markdownFor(note)

        if (Files.exists(destination)) {
            verifyExistingContent(destination, content)
            return
        }

        val temporary = Files.createTempFile(vaultDirectory, ".${note.id}.", ".tmp")
        try {
            Files.writeString(temporary, content, UTF_8)
            try {
                try {
                    Files.move(temporary, destination, ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination)
                }
            } catch (_: FileAlreadyExistsException) {
                verifyExistingContent(destination, content)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyExistingContent(destination: Path, expected: String) {
        if (Files.readString(destination, UTF_8) != expected) {
            throw IOException("Markdown destination already exists with different content: $destination")
        }
    }

    private fun markdownFor(note: ReviewableNote): String = """
        ---
        created: ${note.createdAt}
        source: watch-note-taker
        status: approved
        ---

        ${note.transcript}
        """.trimIndent() + "\n"

    private fun processed(transition: Transition): MarkdownDeliveryResult =
        MarkdownDeliveryResult(transition.note, transition.disposition)

    private companion object {
        val filenameTimestamp: DateTimeFormatter = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH-mm-ss'Z'")
            .withZone(UTC)
    }
}
