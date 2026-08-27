package com.peterle95.watchnotetaker.phone

interface TranscriptionClient {
    fun transcribe(audio: ReceivedRecording): TranscriptionResult
}

sealed interface TranscriptionResult {
    data class Success(val transcript: String) : TranscriptionResult
    data object AuthenticationFailure : TranscriptionResult
    data object BudgetExceeded : TranscriptionResult
    data object NetworkFailure : TranscriptionResult
    data object InvalidAudio : TranscriptionResult
    data object ServerFailure : TranscriptionResult
}
