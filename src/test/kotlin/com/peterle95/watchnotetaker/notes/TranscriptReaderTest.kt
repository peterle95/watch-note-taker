package com.peterle95.watchnotetaker.notes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TranscriptReaderTest {
    @Test
    fun `opens a short ready transcript as one complete page`() {
        val reader = TranscriptReader(pageSize = 10)

        val state = reader.open(transcript("hello"))

        assertEquals("hello", state.content)
        assertEquals(1, state.pageNumber)
        assertEquals(1, state.totalPages)
        assertFalse(state.canMoveBackward)
        assertFalse(state.canMoveForward)
    }

    @Test
    fun `moves through every page of a long transcript without truncating content`() {
        val reader = TranscriptReader(pageSize = 5)
        val original = "abcdefghijk"
        var state = reader.open(transcript(original))
        val pages = mutableListOf(state.content)

        while (state.canMoveForward) {
            state = reader.moveForward()
            pages += state.content
        }

        assertEquals(original, pages.joinToString(separator = ""))
        assertEquals(3, state.pageNumber)
        assertEquals(3, state.totalPages)
        assertTrue(state.canMoveBackward)
        assertFalse(state.canMoveForward)
    }

    @Test
    fun `handles empty and exact boundary transcripts`() {
        val reader = TranscriptReader(pageSize = 5)

        val empty = reader.open(transcript(""))
        assertEquals("", empty.content)
        assertEquals(1, empty.pageNumber)
        assertEquals(1, empty.totalPages)

        val exactBoundary = reader.open(transcript("abcde"))
        assertEquals("abcde", exactBoundary.content)
        assertEquals(1, exactBoundary.pageNumber)
        assertEquals(1, exactBoundary.totalPages)
    }

    @Test
    fun `forward and backward boundaries retain the current page`() {
        val reader = TranscriptReader(pageSize = 5)
        val first = reader.open(transcript("abcdefghij"))

        assertEquals(first, reader.moveBackward())

        val second = reader.moveForward()
        assertEquals(second, reader.moveForward())
        assertEquals(first, reader.moveBackward())
    }

    @Test
    fun `keeps words together when a page has a whitespace boundary`() {
        val reader = TranscriptReader(pageSize = 7)
        val original = "hello world"

        val first = reader.open(transcript(original))
        val second = reader.moveForward()

        assertEquals("hello ", first.content)
        assertEquals("world", second.content)
        assertEquals(original, first.content + second.content)
    }

    @Test
    fun `does not split a supplementary Unicode character at a page boundary`() {
        val reader = TranscriptReader(pageSize = 2)
        val original = "a😀b"

        val first = reader.open(transcript(original))
        val second = reader.moveForward()

        assertEquals("a😀", first.content)
        assertEquals("b", second.content)
        assertEquals(original, first.content + second.content)
    }

    @Test
    fun `moves backward through every page with accurate progress`() {
        val reader = TranscriptReader(pageSize = 5)
        reader.open(transcript("abcdefghijk"))
        reader.moveForward()
        val last = reader.moveForward()

        val middle = reader.moveBackward()
        val first = reader.moveBackward()

        assertEquals(3, last.pageNumber)
        assertEquals(2, middle.pageNumber)
        assertEquals(1, first.pageNumber)
        assertFalse(first.canMoveBackward)
        assertTrue(first.canMoveForward)
    }

    @Test
    fun `rejects a transcript that is not ready for review`() {
        val reader = TranscriptReader(pageSize = 5)
        val approved = ReviewableTranscript(
            id = "note-123",
            transcript = "Already approved",
            status = NoteStatus.APPROVED,
        )

        assertFailsWith<IllegalArgumentException> { reader.open(approved) }
        assertEquals(TranscriptReaderAvailability.Closed, reader.snapshot())
    }

    @Test
    fun `back closes the reader without changing the ready note state`() {
        val reader = TranscriptReader(pageSize = 5)
        val reviewable = transcript("abcdef")
        val original = reviewable.copy()
        reader.open(reviewable)

        reader.back()

        assertEquals(TranscriptReaderAvailability.Closed, reader.snapshot())
        assertEquals(original, reviewable)
    }

    private fun transcript(content: String) = ReviewableTranscript(
        id = "note-123",
        transcript = content,
        status = NoteStatus.READY_FOR_REVIEW,
    )
}
