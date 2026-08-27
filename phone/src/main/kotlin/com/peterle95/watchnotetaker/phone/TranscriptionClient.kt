package com.peterle95.watchnotetaker.phone

import java.net.HttpURLConnection
import java.net.URL

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

class ManualTranscriptionClient(
    private val transcript: String,
) : TranscriptionClient {
    override fun transcribe(audio: ReceivedRecording): TranscriptionResult =
        if (transcript.isBlank()) TranscriptionResult.InvalidAudio else TranscriptionResult.Success(transcript)
}

class HttpTranscriptionClient(
    endpoint: String,
    private val deviceToken: String,
) : TranscriptionClient {
    private val endpoint = URL(endpoint)

    init {
        require(this.endpoint.protocol == "https" || this.endpoint.host in setOf("127.0.0.1", "localhost")) {
            "Transcription endpoint must use HTTPS"
        }
    }

    override fun transcribe(audio: ReceivedRecording): TranscriptionResult {
        if (deviceToken.isBlank()) return TranscriptionResult.AuthenticationFailure
        if (!audio.file.isFile || audio.file.length() !in 1..PhoneAudioStore.MAX_AUDIO_BYTES || audio.durationSeconds !in 1..120) {
            return TranscriptionResult.InvalidAudio
        }
        return runCatching {
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout = 180_000
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(audio.file.length())
                connection.setRequestProperty("Authorization", "Bearer $deviceToken")
                connection.setRequestProperty("Content-Type", "audio/mp4")
                connection.setRequestProperty("X-Note-Id", audio.noteId)
                connection.setRequestProperty("X-Duration-Seconds", audio.durationSeconds.toString())
                audio.file.inputStream().use { input ->
                    connection.outputStream.use(input::copyTo)
                }
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { reader ->
                        buildString {
                            val buffer = CharArray(8_192)
                            while (length <= MAX_TRANSCRIPT_CHARS) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                append(buffer, 0, count)
                            }
                        }.takeIf { it.length <= MAX_TRANSCRIPT_CHARS }
                    }
                        ?.takeIf(String::isNotBlank)
                        ?.let(TranscriptionResult::Success)
                        ?: TranscriptionResult.ServerFailure

                    HttpURLConnection.HTTP_UNAUTHORIZED,
                    HttpURLConnection.HTTP_FORBIDDEN,
                    -> TranscriptionResult.AuthenticationFailure

                    HttpURLConnection.HTTP_PAYMENT_REQUIRED -> TranscriptionResult.BudgetExceeded

                    HttpURLConnection.HTTP_BAD_REQUEST,
                    HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
                    HttpURLConnection.HTTP_UNSUPPORTED_TYPE,
                    -> TranscriptionResult.InvalidAudio

                    429,
                    in 500..599,
                    -> TranscriptionResult.ServerFailure
                    else -> TranscriptionResult.NetworkFailure
                }
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(TranscriptionResult.NetworkFailure)
    }

    companion object {
        private const val MAX_TRANSCRIPT_CHARS = 256 * 1_024
    }
}
