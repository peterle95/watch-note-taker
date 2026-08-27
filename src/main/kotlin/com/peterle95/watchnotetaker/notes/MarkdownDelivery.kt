package com.peterle95.watchnotetaker.notes

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter

/** Fold-side transport which writes an approved note to the Markdown vault. */
fun interface MarkdownDeliveryTransport {
    fun write(note: ReviewableNote)
}

class MarkdownConflictException(message: String) : IllegalStateException(message)

class FileMarkdownDeliveryTransport(
    private val vault: Path,
) : MarkdownDeliveryTransport {
    override fun write(note: ReviewableNote) {
        require(note.status == NoteStatus.APPROVED) { "Only approved notes can be delivered" }
        Files.createDirectories(vault)
        val target = vault.resolve(note.markdownFileName())
        val content = note.markdownContent()
        if (Files.exists(target)) {
            if (Files.readString(target, UTF_8) != content) {
                throw MarkdownConflictException("Markdown destination already contains different content: $target")
            }
            return
        }

        val temporary = Files.createTempFile(vault, ".${note.id}-", ".tmp")
        try {
            Files.writeString(temporary, content, UTF_8)
            try {
                Files.move(temporary, target, ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                try {
                    Files.move(temporary, target)
                } catch (_: FileAlreadyExistsException) {
                    verifyExisting(target, content)
                }
            } catch (_: FileAlreadyExistsException) {
                verifyExisting(target, content)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifyExisting(target: Path, content: String) {
        if (Files.readString(target, UTF_8) != content) {
            throw MarkdownConflictException("Markdown destination already contains different content: $target")
        }
    }
}

/** Runs one delivery attempt and returns Fold's resulting authoritative note. */
class MarkdownDeliveryWorker(
    private val transport: MarkdownDeliveryTransport,
) {
    fun deliver(note: ReviewableNote): ReviewableNote {
        if (note.status == NoteStatus.DELIVERED || note.status == NoteStatus.REJECTED) return note
        val approved = when (note.status) {
            NoteStatus.APPROVED -> note
            NoteStatus.DELIVERY_FAILED -> NoteStateMachine.execute(note, NoteCommand.RetryDelivery).note
            else -> return note
        }
        val started = NoteStateMachine.execute(approved, NoteCommand.BeginDelivery).note
        return try {
            transport.write(started.copy(status = NoteStatus.APPROVED))
            NoteStateMachine.execute(started, NoteCommand.MarkDelivered(started.activeDeliveryAttempt!!)).note
        } catch (_: Exception) {
            NoteStateMachine.execute(started, NoteCommand.MarkDeliveryFailed(started.activeDeliveryAttempt!!)).note
        }
    }
}

private val markdownTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
    .withZone(UTC)

fun ReviewableNote.markdownFileName(): String = "${markdownTimestamp.format(createdAt)}-$id.md"

fun ReviewableNote.markdownContent(): String = buildString {
    appendLine("---")
    appendLine("created: ${createdAt}")
    appendLine("source: watch-note-taker")
    appendLine("status: approved")
    appendLine("---")
    appendLine()
    append(transcript)
    appendLine()
}
