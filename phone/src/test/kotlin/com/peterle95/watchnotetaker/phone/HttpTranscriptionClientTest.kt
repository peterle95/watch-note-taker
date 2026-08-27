package com.peterle95.watchnotetaker.phone

import com.peterle95.watchnotetaker.notes.NoteStatus
import java.net.ServerSocket
import java.nio.file.Files
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HttpTranscriptionClientTest {
    @Test
    fun `uploads authenticated audio with stable note metadata`() {
        TestHttpServer(200, "transcribed text").use { server ->
            val result = HttpTranscriptionClient(server.url, "device-token").transcribe(recording("audio"))

            assertEquals(TranscriptionResult.Success("transcribed text"), result)
            assertEquals("Bearer device-token", server.headers["authorization"])
            assertEquals(NOTE_ID, server.headers["x-note-id"])
            assertEquals("12", server.headers["x-duration-seconds"])
            assertContentEquals("audio".encodeToByteArray(), server.body)
        }
    }

    @Test
    fun `maps backend failures to explicit results`() {
        val expected = mapOf(
            400 to TranscriptionResult.InvalidAudio,
            401 to TranscriptionResult.AuthenticationFailure,
            402 to TranscriptionResult.BudgetExceeded,
            403 to TranscriptionResult.AuthenticationFailure,
            413 to TranscriptionResult.InvalidAudio,
            415 to TranscriptionResult.InvalidAudio,
            429 to TranscriptionResult.ServerFailure,
            500 to TranscriptionResult.ServerFailure,
        )

        expected.forEach { (status, result) ->
            TestHttpServer(status).use { server ->
                assertEquals(result, HttpTranscriptionClient(server.url, "token").transcribe(recording("audio")))
            }
        }
    }

    private fun recording(content: String): ReceivedRecording {
        val file = Files.createTempFile("recording", ".m4a").toFile().apply { writeText(content) }
        return ReceivedRecording(NOTE_ID, 12, file, null, NoteStatus.TRANSCRIBING, Instant.EPOCH, 0, null, null)
    }

    companion object {
        private const val NOTE_ID = "123e4567-e89b-12d3-a456-426614174000"
    }
}

private class TestHttpServer(
    private val status: Int,
    private val response: String = "",
) : AutoCloseable {
    private val socket = ServerSocket(0)
    private val worker = thread {
        socket.accept().use { connection ->
            val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            input.readLine()
            generateSequence(input::readLine)
                .takeWhile(String::isNotEmpty)
                .forEach { line ->
                    val separator = line.indexOf(':')
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            body = CharArray(headers["content-length"]?.toInt() ?: 0)
                .also { input.read(it) }
                .concatToString()
                .encodeToByteArray()
            val bytes = response.encodeToByteArray()
            connection.getOutputStream().write(
                "HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .encodeToByteArray() + bytes,
            )
        }
    }

    val url = "http://127.0.0.1:${socket.localPort}/transcribe"
    val headers = mutableMapOf<String, String>()
    var body = ByteArray(0)

    override fun close() {
        worker.join(5_000)
        socket.close()
    }
}
