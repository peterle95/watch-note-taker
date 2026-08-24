package com.peterle95.watchnotetaker.notes

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.Base64
import java.util.Properties

interface FoldNoteStore {
    fun note(id: String): ReviewableNote?

    fun save(note: ReviewableNote)
}

class FileFoldNoteStore(
    private val path: Path,
) : FoldNoteStore {
    override fun note(id: String): ReviewableNote? = notes()[id]

    override fun save(note: ReviewableNote) {
        val updated = notes() + (note.id to note)
        path.parent?.let(Files::createDirectories)
        val temporary = Files.createTempFile(path.parent ?: Path.of("."), "${path.fileName}.", ".tmp")
        try {
            Files.newOutputStream(temporary).use { output -> updated.toProperties().store(output, null) }
            FileChannel.open(temporary, WRITE).use { channel -> channel.force(true) }
            try {
                Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, REPLACE_EXISTING)
            }
            syncDirectory(path.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun syncDirectory(directory: Path?) {
        if (directory == null) return
        try {
            FileChannel.open(directory, READ).use { channel -> channel.force(true) }
        } catch (_: java.io.IOException) {
            // Directory fsync is best-effort on JVM filesystems without support.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is best-effort on JVM filesystems without support.
        }
    }

    private fun notes(): Map<String, ReviewableNote> {
        if (Files.notExists(path)) return emptyMap()
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        return (0 until properties.required("count").toInt()).associate { index ->
            val prefix = "note.$index"
            val note = ReviewableNote(
                id = properties.required("$prefix.id").decode(),
                transcript = properties.required("$prefix.transcript").decode(),
                status = NoteStatus.valueOf(properties.required("$prefix.status")),
                createdAt = Instant.parse(properties.required("$prefix.createdAt")),
                nextDeliveryAttempt = properties.required("$prefix.nextDeliveryAttempt").toLong(),
                activeDeliveryAttempt = properties.getProperty("$prefix.activeDeliveryAttempt")?.toLong(),
            )
            note.id to note
        }
    }
}

/**
 * Fold-side transport for watch review commands. It durably records approval
 * before vault I/O, persists delivery outcomes, and reports only final state
 * back to the watch outbox.
 */
class FoldMarkdownDeliveryTransport(
    private val noteStore: FoldNoteStore,
    private val deliveryWorker: MarkdownDeliveryWorker,
) : ReviewDecisionTransport {
    override fun send(decision: PendingReviewDecision): ReviewDecisionDelivery {
        val existing = noteStore.note(decision.noteId) ?: return ReviewDecisionDelivery.RetryableFailure
        if (decision.decision == ReviewDecision.Reject) {
            val rejected = when (existing.status) {
                NoteStatus.READY_FOR_REVIEW -> NoteStateMachine.execute(existing, NoteCommand.Reject).note
                NoteStatus.REJECTED -> existing
                else -> return ReviewDecisionDelivery.RetryableFailure
            }
            noteStore.save(rejected)
            return ReviewDecisionDelivery.Delivered(
                AuthoritativeReviewStatus(rejected.id, rejected.transcript, NoteStatus.REJECTED),
            )
        }

        val approved = approvedNote(existing) ?: return ReviewDecisionDelivery.RetryableFailure
        noteStore.save(approved)

        val delivered = deliveryWorker.deliver(approved)
        noteStore.save(delivered.note)
        return if (delivered.note.status == NoteStatus.DELIVERED) {
            ReviewDecisionDelivery.Delivered(
                AuthoritativeReviewStatus(delivered.note.id, delivered.note.transcript, NoteStatus.DELIVERED),
            )
        } else {
            ReviewDecisionDelivery.RetryableFailure
        }
    }

    private fun approvedNote(note: ReviewableNote): ReviewableNote? = when {
        note.status == NoteStatus.READY_FOR_REVIEW -> NoteStateMachine.execute(note, NoteCommand.Approve).note
        note.status in setOf(NoteStatus.APPROVED, NoteStatus.DELIVERY_FAILED, NoteStatus.DELIVERED) -> note
        else -> null
    }
}

private fun Map<String, ReviewableNote>.toProperties(): Properties = Properties().apply {
    setProperty("count", this@toProperties.size.toString())
    this@toProperties.values.sortedBy { it.id }.forEachIndexed { index, note ->
        val prefix = "note.$index"
        setProperty("$prefix.id", note.id.encode())
        setProperty("$prefix.transcript", note.transcript.encode())
        setProperty("$prefix.status", note.status.name)
        setProperty("$prefix.createdAt", note.createdAt.toString())
        setProperty("$prefix.nextDeliveryAttempt", note.nextDeliveryAttempt.toString())
        note.activeDeliveryAttempt?.let { setProperty("$prefix.activeDeliveryAttempt", it.toString()) }
    }
}

private fun Properties.required(key: String): String = getProperty(key) ?: error("Missing required Fold note property: $key")

private fun String.encode(): String = Base64.getUrlEncoder().encodeToString(toByteArray(UTF_8))

private fun String.decode(): String = String(Base64.getUrlDecoder().decode(this), UTF_8)
