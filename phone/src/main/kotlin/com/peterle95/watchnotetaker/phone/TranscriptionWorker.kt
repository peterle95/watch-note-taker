package com.peterle95.watchnotetaker.phone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class TranscriptionWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val client = if (BuildConfig.BACKEND_URL.isBlank()) {
            object : TranscriptionClient {
                override fun transcribe(audio: ReceivedRecording) = TranscriptionResult.ServerFailure
            }
        } else {
            HttpTranscriptionClient(BuildConfig.BACKEND_URL, DeviceTokenStore(applicationContext).get())
        }
        val repository = TranscriptionRepository(PhoneAudioStore(applicationContext), client)
        while (true) {
            when (repository.runNext()) {
                TranscriptionRun.COMPLETED,
                TranscriptionRun.BLOCKED,
                TranscriptionRun.RETRY_SCHEDULED,
                -> continue

                TranscriptionRun.IDLE,
                -> return Result.success()

                TranscriptionRun.WAITING -> return Result.retry()
            }
        }
    }
}

object TranscriptionWork {
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "phone-transcription",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}

class DeviceTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("backend-auth", Context.MODE_PRIVATE)

    fun get(): String = preferences.getString("device-token", "").orEmpty()

    fun set(token: String) {
        require(token.isNotBlank()) { "Device token must not be blank" }
        check(preferences.edit().putString("device-token", token.trim()).commit()) { "Could not save device token" }
    }
}
