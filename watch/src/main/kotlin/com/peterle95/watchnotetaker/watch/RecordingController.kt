package com.peterle95.watchnotetaker.watch

import android.content.Context
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.UUID

class RecordingController(
    context: Context,
    private val onFinished: (File, Int) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val recordingsDirectory = File(context.cacheDir, "recordings")
    private val handler = Handler(Looper.getMainLooper())
    private val stopAtMaximumDuration = Runnable(::stop)
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L

    fun start() {
        check(recorder == null) { "A recording is already in progress" }
        recordingsDirectory.mkdirs()
        output = File(recordingsDirectory, "${UUID.randomUUID()}.m4a")
        startedAt = System.currentTimeMillis()
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(output!!.absolutePath)
            setMaxDuration(MAX_DURATION_MILLIS)
            prepare()
            start()
        }
        handler.postDelayed(stopAtMaximumDuration, MAX_DURATION_MILLIS.toLong())
    }

    fun stop() {
        val activeRecorder = recorder ?: return
        recorder = null
        handler.removeCallbacks(stopAtMaximumDuration)
        val recording = output ?: return
        output = null
        val durationSeconds = ((System.currentTimeMillis() - startedAt + 999) / 1_000)
            .toInt()
            .coerceIn(1, MAX_DURATION_SECONDS)
        runCatching { activeRecorder.stop() }
            .onSuccess { onFinished(recording, durationSeconds) }
            .onFailure {
                recording.delete()
                onFailure("Recording failed. Try again.")
            }
        activeRecorder.release()
    }

    fun release() = stop()

    private companion object {
        const val MAX_DURATION_SECONDS = 120
        const val MAX_DURATION_MILLIS = MAX_DURATION_SECONDS * 1_000
    }
}
