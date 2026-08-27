package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.NoteStatus

enum class TranscriptionRun {
    IDLE,
    WAITING,
    COMPLETED,
    RETRY_SCHEDULED,
    BLOCKED,
}

class TranscriptionRepository(
    private val store: PhoneAudioStore,
    private val client: TranscriptionClient,
) {
    fun runNext(nowMillis: Long = System.currentTimeMillis()): TranscriptionRun {
        val pending = store.recordings().filter {
            it.status == NoteStatus.TRANSCRIBING && it.transcript == null
        }
        val recording = pending.firstOrNull {
            it.lastTranscriptionError == null ||
                it.nextTranscriptionRetryAtMillis?.let { retryAt -> retryAt <= nowMillis } == true
        } ?: return if (pending.any { it.nextTranscriptionRetryAtMillis != null }) {
            TranscriptionRun.WAITING
        } else {
            TranscriptionRun.IDLE
        }

        return run(recording, nowMillis)
    }

    fun run(noteId: String, nowMillis: Long = System.currentTimeMillis()): TranscriptionRun {
        val recording = store.recordings().firstOrNull { it.noteId == noteId }
            ?: return TranscriptionRun.IDLE
        return run(recording, nowMillis)
    }

    private fun run(recording: ReceivedRecording, nowMillis: Long): TranscriptionRun {
        val attempt = store.startTranscriptionAttempt(recording.noteId)
        return when (val result = runCatching { client.transcribe(attempt) }
            .getOrDefault(TranscriptionResult.NetworkFailure)) {
            is TranscriptionResult.Success -> {
                store.saveTranscript(recording.noteId, result.transcript)
                TranscriptionRun.COMPLETED
            }

            TranscriptionResult.NetworkFailure,
            TranscriptionResult.ServerFailure,
            -> transientFailure(attempt, result, nowMillis)

            TranscriptionResult.AuthenticationFailure,
            TranscriptionResult.BudgetExceeded,
            TranscriptionResult.InvalidAudio,
            -> {
                store.recordTranscriptionFailure(
                    recording.noteId,
                    attempt.transcriptionAttemptCount,
                    result.errorName(),
                    null,
                )
                TranscriptionRun.BLOCKED
            }
        }
    }

    private fun transientFailure(
        recording: ReceivedRecording,
        result: TranscriptionResult,
        nowMillis: Long,
    ): TranscriptionRun {
        val retryAt = if (recording.transcriptionAttemptCount < MAX_AUTO_ATTEMPTS) {
            nowMillis + (BASE_RETRY_MILLIS shl (recording.transcriptionAttemptCount - 1)).coerceAtMost(MAX_RETRY_MILLIS)
        } else {
            null
        }
        store.recordTranscriptionFailure(
            recording.noteId,
            recording.transcriptionAttemptCount,
            result.errorName(),
            retryAt,
        )
        return if (retryAt == null) TranscriptionRun.BLOCKED else TranscriptionRun.RETRY_SCHEDULED
    }

    private fun TranscriptionResult.errorName(): String = when (this) {
        is TranscriptionResult.Success -> error("Success is not an error")
        TranscriptionResult.AuthenticationFailure -> "authentication"
        TranscriptionResult.BudgetExceeded -> "budget exceeded"
        TranscriptionResult.NetworkFailure -> "network"
        TranscriptionResult.InvalidAudio -> "invalid audio"
        TranscriptionResult.ServerFailure -> "server"
    }

    companion object {
        private const val BASE_RETRY_MILLIS = 30_000L
        private const val MAX_RETRY_MILLIS = 6 * 60 * 60 * 1_000L
        private const val MAX_AUTO_ATTEMPTS = 8
    }
}
