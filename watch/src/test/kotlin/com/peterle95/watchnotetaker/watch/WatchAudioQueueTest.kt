package com.peterle95.watchnotetaker.watch

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchAudioQueueTest {
    private val directory = Files.createTempDirectory("watch-audio-queue").toFile()
    private val metadata = InMemoryWatchRecordingMetadataStore()
    private val noteId = "123e4567-e89b-12d3-a456-426614174000"

    @Test
    fun `audio becomes visible only after its metadata is durable`() {
        val source = source(noteId, "audio")

        assertTrue(WatchAudioQueue(directory, metadata).enqueue(source, 12))

        val queued = WatchAudioQueue(directory, metadata).entries().single()
        assertEquals(noteId, queued.noteId)
        assertEquals(12, queued.durationSeconds)
        assertContentEquals("audio".encodeToByteArray(), queued.file.readBytes())
        assertFalse(source.exists())
    }

    @Test
    fun `metadata failure keeps source and removes uncommitted target`() {
        val source = source(noteId, "audio")

        assertFailsWith<IllegalStateException> {
            WatchAudioQueue(directory, FailingWatchRecordingMetadataStore).enqueue(source, 12)
        }

        assertTrue(source.isFile)
        assertTrue(WatchAudioQueue(directory, metadata).entries().isEmpty())
        assertFalse(directory.resolve("$noteId.m4a").exists())
    }

    @Test
    fun `orphan audio is not exposed and can be safely recovered from source`() {
        directory.mkdirs()
        directory.resolve("$noteId.m4a").writeText("partial")
        val source = source(noteId, "complete")
        val queue = WatchAudioQueue(directory, metadata)

        assertTrue(queue.entries().isEmpty())
        assertTrue(queue.enqueue(source, 12))
        assertContentEquals("complete".encodeToByteArray(), queue.entries().single().file.readBytes())
    }

    @Test
    fun `existing queued recording is never overwritten`() {
        val queue = WatchAudioQueue(directory, metadata)
        queue.enqueue(source(noteId, "first"), 12)
        val replacement = source(noteId, "replacement")

        assertFailsWith<IllegalStateException> { queue.enqueue(replacement, 30) }

        assertTrue(replacement.isFile)
        assertEquals(12, queue.entries().single().durationSeconds)
        assertContentEquals("first".encodeToByteArray(), queue.entries().single().file.readBytes())
    }

    @Test
    fun `queue bounds preserve the unqueued source`() {
        val queue = WatchAudioQueue(directory, metadata)
        repeat(WatchAudioQueue.CAPACITY) { index ->
            val id = "123e4567-e89b-12d3-a456-${index.toString().padStart(12, '0')}"
            assertTrue(queue.enqueue(source(id, "audio"), 12))
        }
        val overflow = source("123e4567-e89b-12d3-a456-999999999999", "overflow")

        assertFalse(queue.enqueue(overflow, 12))
        assertEquals(WatchAudioQueue.CAPACITY, queue.entries().size)
        assertTrue(overflow.isFile)
    }

    @Test
    fun `only an acknowledgement from the sent node removes audio`() {
        val queue = WatchAudioQueue(directory, metadata)
        queue.enqueue(source(noteId, "audio"), 12)
        queue.markSent(noteId, "phone")

        assertFalse(queue.acknowledge(noteId, "other-phone"))
        assertEquals(1, queue.entries().size)
        assertTrue(queue.acknowledge(noteId, "phone"))
        assertTrue(queue.entries().isEmpty())
    }

    private fun source(id: String, content: String) =
        Files.createTempDirectory("watch-audio-source").resolve("$id.m4a").toFile().apply { writeText(content) }
}

private class InMemoryWatchRecordingMetadataStore : WatchRecordingMetadataStore {
    private val durations = mutableMapOf<String, Int>()
    private val nodes = mutableMapOf<String, String>()

    override fun duration(fileName: String): Int? = durations[fileName]
    override fun saveDuration(fileName: String, durationSeconds: Int): Boolean = durations.put(fileName, durationSeconds).let { true }
    override fun removeDuration(fileName: String): Boolean = durations.remove(fileName).let { true }
    override fun sentNode(noteId: String): String? = nodes[noteId]
    override fun saveSentNode(noteId: String, nodeId: String): Boolean = nodes.put(noteId, nodeId).let { true }
    override fun removeSentNode(noteId: String): Boolean = nodes.remove(noteId).let { true }
}

private object FailingWatchRecordingMetadataStore : WatchRecordingMetadataStore {
    override fun duration(fileName: String): Int? = null
    override fun saveDuration(fileName: String, durationSeconds: Int): Boolean = false
    override fun removeDuration(fileName: String): Boolean = true
    override fun sentNode(noteId: String): String? = null
    override fun saveSentNode(noteId: String, nodeId: String): Boolean = false
    override fun removeSentNode(noteId: String): Boolean = true
}
