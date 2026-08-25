package com.peterle95.watchnotetaker.notes

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Base64
import java.util.Properties

/**
 * JVM persistence adapter for [ReviewDecisionOutbox].
 *
 * Each update is written to a sibling temporary file and atomically moved into
 * place where the filesystem supports it, so a process interruption leaves
 * either the previous complete snapshot or the new one available on restart.
 */
class FileReviewDecisionStore(
    private val path: Path,
) : WritableReviewDecisionStore {
    override fun read(): ReviewDecisionOutboxSnapshot {
        if (Files.notExists(path)) return ReviewDecisionOutboxSnapshot()
        return loadProperties().toSnapshot()
    }

    override fun write(snapshot: ReviewDecisionOutboxSnapshot) {
        path.parent?.let(Files::createDirectories)
        val temporaryPath = Files.createTempFile(path.parent ?: Path.of("."), "${path.fileName}.", ".tmp")
        try {
            temporaryPath.outputStream().use { output -> snapshot.toProperties().store(output, null) }
            FileChannel.open(temporaryPath, WRITE).use { channel -> channel.force(true) }
            try {
                Files.move(temporaryPath, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, path, REPLACE_EXISTING)
            }
            syncDirectory(path.parent)
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    private fun loadProperties(): Properties = Properties().also { properties ->
        path.inputStream().use(properties::load)
    }

    private fun syncDirectory(directory: Path?) {
        if (directory == null) return
        try {
            FileChannel.open(directory, READ).use { channel -> channel.force(true) }
        } catch (_: IOException) {
            // Some filesystems do not expose directories as forceable channels.
        } catch (_: UnsupportedOperationException) {
            // Directory fsync is best-effort on JVM filesystems without support.
        }
    }
}

private fun ReviewDecisionOutboxSnapshot.toProperties(): Properties = Properties().apply {
    setProperty("pending.count", pendingDecisions.size.toString())
    pendingDecisions.forEachIndexed { index, decision ->
        setProperty("pending.$index.noteId", decision.noteId.encode())
        setProperty("pending.$index.decision", decision.decision.name)
        decision.transcript?.let { transcript -> setProperty("pending.$index.transcript", transcript.encode()) }
    }

    setProperty("status.count", authoritativeStatuses.size.toString())
    authoritativeStatuses.values.forEachIndexed { index, status ->
        setProperty("status.$index.noteId", status.noteId.encode())
        setProperty("status.$index.transcript", status.transcript.encode())
        setProperty("status.$index.status", status.status.name)
    }
}

private fun Properties.toSnapshot(): ReviewDecisionOutboxSnapshot = ReviewDecisionOutboxSnapshot(
    pendingDecisions = (0 until requiredCount("pending.count")).map { index ->
        PendingReviewDecision(
            noteId = required("pending.$index.noteId").decode(),
            decision = ReviewDecision.valueOf(required("pending.$index.decision")),
            transcript = getProperty("pending.$index.transcript")?.decode(),
        )
    },
    authoritativeStatuses = (0 until requiredCount("status.count")).associate { index ->
        val noteId = required("status.$index.noteId").decode()
        noteId to AuthoritativeReviewStatus(
            noteId = noteId,
            transcript = required("status.$index.transcript").decode(),
            status = NoteStatus.valueOf(required("status.$index.status")),
        )
    },
)

private fun Properties.requiredCount(key: String): Int = required(key).toInt()

private fun Properties.required(key: String): String =
    getProperty(key) ?: error("Missing required outbox property: $key")

private fun String.encode(): String = Base64.getUrlEncoder().encodeToString(toByteArray(UTF_8))

private fun String.decode(): String = String(Base64.getUrlDecoder().decode(this), UTF_8)

private fun Path.inputStream(): InputStream = Files.newInputStream(this)

private fun Path.outputStream(): OutputStream = Files.newOutputStream(this)
