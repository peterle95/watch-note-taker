package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.MarkdownDeliveryTransport
import com.peterle95.watchnotetaker.notes.MarkdownConflictException
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.TransitionDisposition

enum class DeliveryRun {
    IDLE,
    COMPLETED,
    RETRY,
    CONFLICT,
}

class PhoneDeliveryRepository(
    private val store: PhoneAudioStore,
    private val transport: MarkdownDeliveryTransport,
) {
    fun runPending(): DeliveryRun {
        var attempted = false
        var failed = false
        var conflict = false
        store.recordings()
            .filter { it.status == NoteStatus.APPROVED || it.status == NoteStatus.DELIVERY_FAILED }
            .forEach { recording ->
                if (recording.status == NoteStatus.DELIVERY_FAILED) {
                    check(store.retryDelivery(recording.noteId).disposition == TransitionDisposition.APPLIED) {
                        "Could not retry delivery"
                    }
                }
                val started = store.beginDelivery(recording.noteId)
                check(started.disposition != TransitionDisposition.INVALID) { "Could not begin delivery" }
                val attempt = checkNotNull(started.note.activeDeliveryAttempt)
                attempted = true
                val delivered = try {
                    transport.write(started.note)
                    true
                } catch (_: MarkdownConflictException) {
                    conflict = true
                    false
                } catch (_: Exception) {
                    false
                }
                if (delivered) {
                    check(store.markDelivered(recording.noteId, attempt).disposition != TransitionDisposition.INVALID) {
                        "Could not persist delivery"
                    }
                } else {
                    check(store.markDeliveryFailed(recording.noteId, attempt).disposition != TransitionDisposition.INVALID) {
                        "Could not persist delivery failure"
                    }
                    failed = true
                }
            }
        return when {
            conflict -> DeliveryRun.CONFLICT
            failed -> DeliveryRun.RETRY
            attempted -> DeliveryRun.COMPLETED
            else -> DeliveryRun.IDLE
        }
    }
}
