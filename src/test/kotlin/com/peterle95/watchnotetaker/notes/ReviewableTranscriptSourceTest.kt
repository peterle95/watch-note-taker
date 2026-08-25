package com.peterle95.watchnotetaker.notes

import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewableTranscriptSourceTest {
    @Test
    fun `refresh obtains only ready transcripts with their authoritative data`() {
        val fold = FakeFoldReviewableNoteSource(
            FoldReviewableNoteAvailability.Available(
                listOf(
                    note(id = "ready-1", status = NoteStatus.READY_FOR_REVIEW),
                    note(id = "approved-1", status = NoteStatus.APPROVED),
                    note(id = "ready-2", status = NoteStatus.READY_FOR_REVIEW),
                    note(id = "failed-1", status = NoteStatus.DELIVERY_FAILED),
                ),
            ),
        )
        val source = ReviewableTranscriptSource(fold)

        source.refresh()

        assertEquals(
            ReviewableTranscriptAvailability.Available(
                listOf(
                    ReviewableTranscript(
                        id = "ready-1",
                        transcript = "Transcript ready-1",
                        status = NoteStatus.READY_FOR_REVIEW,
                    ),
                    ReviewableTranscript(
                        id = "ready-2",
                        transcript = "Transcript ready-2",
                        status = NoteStatus.READY_FOR_REVIEW,
                    ),
                ),
            ),
            source.snapshot(),
        )
    }

    @Test
    fun `loading empty disconnected and unavailable states are distinguishable`() {
        val fold = FakeFoldReviewableNoteSource(FoldReviewableNoteAvailability.Loading)
        val source = ReviewableTranscriptSource(fold)

        source.refresh()
        assertEquals(ReviewableTranscriptAvailability.Loading, source.snapshot())

        fold.availability = FoldReviewableNoteAvailability.Available(emptyList())
        source.refresh()
        assertEquals(ReviewableTranscriptAvailability.Available(emptyList()), source.snapshot())

        fold.availability = FoldReviewableNoteAvailability.Disconnected
        source.refresh()
        assertEquals(ReviewableTranscriptAvailability.Disconnected(emptyList()), source.snapshot())

        fold.availability = FoldReviewableNoteAvailability.Unavailable
        source.refresh()
        assertEquals(ReviewableTranscriptAvailability.Unavailable, source.snapshot())
    }

    @Test
    fun `unavailable Fold does not expose a retained review list`() {
        val fold = FakeFoldReviewableNoteSource(
            FoldReviewableNoteAvailability.Available(
                listOf(note(id = "ready-1", status = NoteStatus.READY_FOR_REVIEW)),
            ),
        )
        val source = ReviewableTranscriptSource(fold)
        source.refresh()

        fold.availability = FoldReviewableNoteAvailability.Unavailable
        source.refresh()

        assertEquals(ReviewableTranscriptAvailability.Unavailable, source.snapshot())
    }

    @Test
    fun `temporary disconnection retains the last reviewable transcript`() {
        val readyNote = note(id = "ready-1", status = NoteStatus.READY_FOR_REVIEW)
        val fold = FakeFoldReviewableNoteSource(FoldReviewableNoteAvailability.Available(listOf(readyNote)))
        val source = ReviewableTranscriptSource(fold)
        source.refresh()

        fold.availability = FoldReviewableNoteAvailability.Disconnected
        source.refresh()

        assertEquals(
            ReviewableTranscriptAvailability.Disconnected(
                listOf(
                    ReviewableTranscript(
                        id = readyNote.id,
                        transcript = readyNote.transcript,
                        status = readyNote.status,
                    ),
                ),
            ),
            source.snapshot(),
        )
    }

    private fun note(id: String, status: NoteStatus) = ReviewableNote(
        id = id,
        transcript = "Transcript $id",
        status = status,
    )

    private class FakeFoldReviewableNoteSource(
        var availability: FoldReviewableNoteAvailability,
    ) : FoldReviewableNoteSource {
        override fun fetch(): FoldReviewableNoteAvailability = availability
    }
}
