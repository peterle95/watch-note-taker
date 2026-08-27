package com.peterle95.watchnotetaker.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CaptureQueueTest {
    @Test
    fun `accepts ten recordings and rejects the eleventh without eviction`() {
        val queue = CaptureQueue()
        repeat(10) { assertEquals(CaptureQueueResult.ACCEPTED, queue.enqueue(audio("note-$it"))) }

        assertEquals(CaptureQueueResult.FULL, queue.enqueue(audio("note-10")))
        assertEquals((0 until 10).map { "note-$it" }, queue.snapshot().map { it.noteId })
    }

    @Test
    fun `duplicate IDs are idempotent`() {
        val queue = CaptureQueue()
        val recording = audio("same")

        assertEquals(CaptureQueueResult.ACCEPTED, queue.enqueue(recording))
        assertEquals(CaptureQueueResult.DUPLICATE, queue.enqueue(recording))
        assertEquals(listOf(recording.noteId), queue.snapshot().map { it.noteId })
    }

    @Test
    fun `duration is capped at two minutes`() {
        val queue = CaptureQueue()

        assertFailsWith<IllegalArgumentException> { queue.enqueue(audio("too-long", 121)) }
    }

    @Test
    fun `removing a recording frees capacity`() {
        val queue = CaptureQueue(capacity = 1)
        queue.enqueue(audio("first"))

        assertEquals(true, queue.remove("first"))
        assertEquals(CaptureQueueResult.ACCEPTED, queue.enqueue(audio("second")))
    }

    private fun audio(id: String, duration: Int = 120) = CapturedAudio(id, duration, byteArrayOf(1, 2, 3))
}
