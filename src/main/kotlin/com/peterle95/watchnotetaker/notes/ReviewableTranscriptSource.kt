package com.peterle95.watchnotetaker.notes

/**
 * The Fold-facing retrieval seam for notes visible in watch review.
 */
fun interface FoldReviewableNoteSource {
    fun fetch(): FoldReviewableNoteAvailability
}

sealed interface FoldReviewableNoteAvailability {
    data object Loading : FoldReviewableNoteAvailability

    data class Available(
        val notes: List<ReviewableNote>,
    ) : FoldReviewableNoteAvailability

    data object Disconnected : FoldReviewableNoteAvailability

    data object Unavailable : FoldReviewableNoteAvailability
}

/**
 * The Fold-authoritative data available to the watch review list.
 */
data class ReviewableTranscript(
    val id: String,
    val transcript: String,
    val status: NoteStatus,
)

/**
 * Availability is explicit so a watch can distinguish an empty review queue
 * from synchronization in progress, a temporary disconnection, or an
 * unavailable Fold source.
 */
sealed interface ReviewableTranscriptAvailability {
    data object Loading : ReviewableTranscriptAvailability

    data class Available(
        val transcripts: List<ReviewableTranscript>,
    ) : ReviewableTranscriptAvailability

    data class Disconnected(
        val retainedTranscripts: List<ReviewableTranscript>,
    ) : ReviewableTranscriptAvailability

    data object Unavailable : ReviewableTranscriptAvailability
}

/**
 * Retrieves the reviewable portion of Fold note state for the watch.
 *
 * A successful Fold snapshot replaces the retained review list. While the Fold
 * is temporarily disconnected, that list remains visible rather than making a
 * reviewable transcript disappear from the watch.
 */
class ReviewableTranscriptSource(
    private val foldNoteSource: FoldReviewableNoteSource,
) {
    private var latestReviewableTranscripts: List<ReviewableTranscript> = emptyList()
    private var availability: ReviewableTranscriptAvailability = ReviewableTranscriptAvailability.Loading

    fun refresh() {
        availability = when (val foldAvailability = foldNoteSource.fetch()) {
            FoldReviewableNoteAvailability.Loading -> ReviewableTranscriptAvailability.Loading
            is FoldReviewableNoteAvailability.Available -> availableReviewableTranscripts(foldAvailability.notes)
            FoldReviewableNoteAvailability.Disconnected -> {
                ReviewableTranscriptAvailability.Disconnected(latestReviewableTranscripts)
            }
            FoldReviewableNoteAvailability.Unavailable -> ReviewableTranscriptAvailability.Unavailable
        }
    }

    fun snapshot(): ReviewableTranscriptAvailability = availability

    private fun availableReviewableTranscripts(notes: List<ReviewableNote>): ReviewableTranscriptAvailability.Available {
        latestReviewableTranscripts = notes
            .filter { it.status == NoteStatus.READY_FOR_REVIEW }
            .map { note ->
                ReviewableTranscript(
                    id = note.id,
                    transcript = note.transcript,
                    status = note.status,
                )
            }
        return ReviewableTranscriptAvailability.Available(latestReviewableTranscripts)
    }
}
