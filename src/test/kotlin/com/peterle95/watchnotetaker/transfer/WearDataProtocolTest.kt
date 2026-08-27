package com.peterle95.watchnotetaker.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WearDataProtocolTest {
    private val noteId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `recording metadata round trips through the stream frame`() {
        val output = ByteArrayOutputStream()
        WearDataProtocol.writeMetadata(output, RecordingTransferMetadata(noteId, 12, 5))

        assertEquals(
            RecordingTransferMetadata(noteId, 12, 5),
            WearDataProtocol.readMetadata(ByteArrayInputStream(output.toByteArray())),
        )
    }

    @Test
    fun `invalid and unsupported metadata is rejected`() {
        assertNull(WearDataProtocol.decodeMetadata(byteArrayOf(1, 2, 3)))
        assertNull(
            WearDataProtocol.decodeMetadata(
                WearDataProtocol.encodeMetadata(RecordingTransferMetadata(noteId, 12, 5, protocolVersion = 2)),
            ),
        )
        assertNull(WearDataProtocol.decodeMetadata(WearDataProtocol.encodeMetadata(RecordingTransferMetadata("../bad", 12, 5))))
        assertNull(WearDataProtocol.decodeMetadata(WearDataProtocol.encodeMetadata(RecordingTransferMetadata(noteId, 0, 5))))
        assertNull(WearDataProtocol.decodeMetadata(WearDataProtocol.encodeMetadata(RecordingTransferMetadata(noteId, 12, 0))))
    }

    @Test
    fun `acknowledgement validates protocol and note ID`() {
        assertEquals(noteId, WearDataProtocol.decodeAcknowledgement(WearDataProtocol.encodeAcknowledgement(noteId))?.noteId)
        assertNull(WearDataProtocol.decodeAcknowledgement(acknowledgement(2, noteId)))
        assertNull(WearDataProtocol.decodeAcknowledgement(acknowledgement(1, "../bad")))
        assertNull(WearDataProtocol.decodeAcknowledgement(ByteArray(0)))
    }

    private fun acknowledgement(version: Int, noteId: String) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(version)
            output.writeUTF(noteId)
        }
    }.toByteArray()
}
