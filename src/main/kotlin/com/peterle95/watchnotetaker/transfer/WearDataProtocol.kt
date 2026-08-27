package com.peterle95.watchnotetaker.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

data class RecordingTransferMetadata(
    val noteId: String,
    val durationSeconds: Int,
    val audioBytes: Long,
    val protocolVersion: Int = WearDataProtocol.PROTOCOL_VERSION,
)

data class RecordingAcknowledgement(
    val noteId: String,
    val protocolVersion: Int = WearDataProtocol.PROTOCOL_VERSION,
)

object WearDataProtocol {
    const val PHONE_CAPABILITY = "watch_note_taker_phone"
    const val RECORDING_CHANNEL_PATH = "/recordings"
    const val ACKNOWLEDGEMENT_MESSAGE_PATH = "/recordings/ack"
    const val PROTOCOL_VERSION = 1
    private const val MAX_METADATA_BYTES = 1_024
    private val NOTE_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun encodeMetadata(metadata: RecordingTransferMetadata): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(metadata.protocolVersion)
            output.writeUTF(metadata.noteId)
            output.writeInt(metadata.durationSeconds)
            output.writeLong(metadata.audioBytes)
        }
    }.toByteArray()

    fun decodeMetadata(bytes: ByteArray): RecordingTransferMetadata? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val metadata = RecordingTransferMetadata(
                protocolVersion = input.readInt(),
                noteId = input.readUTF(),
                durationSeconds = input.readInt(),
                audioBytes = input.readLong(),
            )
            check(input.available() == 0)
            metadata.takeIf(::valid)
        }
    }.getOrNull()

    fun writeMetadata(output: OutputStream, metadata: RecordingTransferMetadata) {
        val bytes = encodeMetadata(metadata)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
        }
    }

    fun readMetadata(input: InputStream): RecordingTransferMetadata? = runCatching {
        val data = DataInputStream(input)
        val size = data.readInt()
        require(size in 1..MAX_METADATA_BYTES)
        ByteArray(size).also(data::readFully)
    }.getOrNull()?.let(::decodeMetadata)

    fun encodeAcknowledgement(noteId: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(PROTOCOL_VERSION)
            output.writeUTF(noteId)
        }
    }.toByteArray()

    fun decodeAcknowledgement(bytes: ByteArray): RecordingAcknowledgement? = runCatching {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val acknowledgement = RecordingAcknowledgement(
                protocolVersion = input.readInt(),
                noteId = input.readUTF(),
            )
            check(input.available() == 0)
            acknowledgement.takeIf { it.protocolVersion == PROTOCOL_VERSION && isValidNoteId(it.noteId) }
        }
    }.getOrNull()

    fun isValidNoteId(noteId: String) = noteId.matches(NOTE_ID)

    private fun valid(metadata: RecordingTransferMetadata) =
        metadata.protocolVersion == PROTOCOL_VERSION &&
            isValidNoteId(metadata.noteId) &&
            metadata.durationSeconds in 1..120 &&
            metadata.audioBytes in 1..MAX_AUDIO_BYTES

    private const val MAX_AUDIO_BYTES = 25L * 1_024 * 1_024
}
