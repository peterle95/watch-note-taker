package com.peterle95.watchnotetaker.notes

sealed interface TranscriptReaderAvailability {
    data object Closed : TranscriptReaderAvailability

    data class Open(
        val content: String,
        val pageNumber: Int,
        val totalPages: Int,
        val canMoveBackward: Boolean,
        val canMoveForward: Boolean,
    ) : TranscriptReaderAvailability
}

/**
 * Read-only transcript paging for the watch review flow.
 *
 * This module owns navigation only. It never emits note commands, so leaving
 * the reader cannot change a Fold-authoritative note state.
 */
class TranscriptReader(
    private val pageSize: Int,
) {
    private var pages: List<String> = emptyList()
    private var currentPageIndex: Int? = null

    init {
        require(pageSize > 0) { "pageSize must be positive" }
    }

    fun open(transcript: ReviewableTranscript): TranscriptReaderAvailability.Open {
        require(transcript.status == NoteStatus.READY_FOR_REVIEW) {
            "Only ready transcripts can enter review"
        }
        pages = paginate(transcript.transcript)
        currentPageIndex = 0
        return currentPage()
    }

    fun moveForward(): TranscriptReaderAvailability.Open {
        val index = currentPageIndex ?: error("No transcript is open")
        if (index < pages.lastIndex) {
            currentPageIndex = index + 1
        }
        return currentPage()
    }

    fun moveBackward(): TranscriptReaderAvailability.Open {
        val index = currentPageIndex ?: error("No transcript is open")
        if (index > 0) {
            currentPageIndex = index - 1
        }
        return currentPage()
    }

    fun back() {
        pages = emptyList()
        currentPageIndex = null
    }

    fun snapshot(): TranscriptReaderAvailability = currentPageIndex?.let { currentPage() }
        ?: TranscriptReaderAvailability.Closed

    private fun currentPage(): TranscriptReaderAvailability.Open {
        val index = currentPageIndex ?: error("No transcript is open")
        return TranscriptReaderAvailability.Open(
            content = pages[index],
            pageNumber = index + 1,
            totalPages = pages.size,
            canMoveBackward = index > 0,
            canMoveForward = index < pages.lastIndex,
        )
    }

    private fun paginate(transcript: String): List<String> {
        if (transcript.isEmpty()) return listOf("")

        val pages = mutableListOf<String>()
        var start = 0
        while (start < transcript.length) {
            var end = advanceByCodePoints(transcript, start)
            if (end < transcript.length) {
                val lastWhitespace = (end - 1 downTo start).firstOrNull { transcript[it].isWhitespace() }
                if (lastWhitespace != null) end = lastWhitespace + 1
            }
            pages += transcript.substring(start, end)
            start = end
        }
        return pages
    }

    private fun advanceByCodePoints(transcript: String, start: Int): Int {
        var end = start
        repeat(pageSize) {
            if (end == transcript.length) return end
            end += Character.charCount(transcript.codePointAt(end))
        }
        return end
    }
}
