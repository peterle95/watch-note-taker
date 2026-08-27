package com.peterle95.watchnotetaker.notes

data class CapturedAudio(
    val noteId: String,
    val durationSeconds: Int,
    val content: ByteArray,
)

enum class CaptureQueueResult {
    ACCEPTED,
    FULL,
    DUPLICATE,
}

/** Bounded capture buffer shared by the watch buffer and Fold durable queue. */
class CaptureQueue(
    private val capacity: Int = 10,
    private val maxDurationSeconds: Int = 120,
    private val store: WritableCaptureQueueStore = InMemoryCaptureQueueStore(),
) {
    init {
        require(capacity > 0) { "capacity must be positive" }
        require(maxDurationSeconds > 0) { "maxDurationSeconds must be positive" }
    }

    fun enqueue(audio: CapturedAudio): CaptureQueueResult {
        return synchronized(store) {
            require(audio.noteId.isNotBlank()) { "noteId must not be blank" }
            require(audio.durationSeconds in 1..maxDurationSeconds) {
                "durationSeconds must be between 1 and $maxDurationSeconds"
            }
            val entries = store.read()
            if (entries.any { it.noteId == audio.noteId }) return@synchronized CaptureQueueResult.DUPLICATE
            if (entries.size >= capacity) return@synchronized CaptureQueueResult.FULL
            store.write(entries + audio.copy(content = audio.content.copyOf()))
            CaptureQueueResult.ACCEPTED
        }
    }

    fun snapshot(): List<CapturedAudio> = synchronized(store) {
        store.read().map { it.copy(content = it.content.copyOf()) }
    }

    fun remove(noteId: String): Boolean = synchronized(store) {
        val entries = store.read()
        if (entries.none { it.noteId == noteId }) return@synchronized false
        store.write(entries.filterNot { it.noteId == noteId })
        true
    }
}

interface CaptureQueueStore {
    fun read(): List<CapturedAudio>
}

interface WritableCaptureQueueStore : CaptureQueueStore {
    fun write(entries: List<CapturedAudio>)
}

class InMemoryCaptureQueueStore : WritableCaptureQueueStore {
    private var entries: List<CapturedAudio> = emptyList()

    override fun read(): List<CapturedAudio> = entries.map { it.copy(content = it.content.copyOf()) }

    override fun write(entries: List<CapturedAudio>) {
        this.entries = entries.map { it.copy(content = it.content.copyOf()) }
    }
}
