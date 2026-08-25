package com.peterle95.watchnotetaker.notes

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownDeliveryTest {
    @Test
    fun `only approved notes write once and become delivered`() {
        val vault = Files.createTempDirectory("vault")
        val transport = FileMarkdownDeliveryTransport(vault)
        val worker = MarkdownDeliveryWorker(transport)
        val note = ReviewableNote("stable", "hello", NoteStatus.APPROVED, Instant.parse("2024-01-02T03:04:05Z"))

        val delivered = worker.deliver(note)
        val retried = worker.deliver(delivered)
        val file = vault.resolve("2024-01-02T03-04-05Z-stable.md")

        assertEquals(NoteStatus.DELIVERED, delivered.status)
        assertEquals(delivered, retried)
        assertTrue(Files.exists(file))
        assertEquals(1, Files.list(vault).use { it.count() })
         assertTrue(Files.readString(file).contains("created: 2024-01-02T03:04:05Z\nsource: watch-note-taker\nstatus: approved"))
    }

    @Test
    fun `rejected notes are never written and failures remain retryable`() {
        val vault = Files.createTempDirectory("vault")
        var available = false
        val worker = MarkdownDeliveryWorker(MarkdownDeliveryTransport {
            if (!available) error("offline")
        })
        val rejected = ReviewableNote("rejected", "no", NoteStatus.REJECTED)
        val failed = worker.deliver(ReviewableNote("failed", "yes", NoteStatus.APPROVED))

        assertEquals(NoteStatus.REJECTED, worker.deliver(rejected).status)
        assertEquals(NoteStatus.DELIVERY_FAILED, failed.status)
        available = true
        assertEquals(NoteStatus.DELIVERED, worker.deliver(failed).status)
        assertFalse(Files.list(vault).use { it.anyMatch { true } })
    }
}
