package com.peterle95.watchnotetaker.watch

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class WatchTransferWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val queue = WatchAudioQueue(applicationContext)
        if (queue.entries().isEmpty()) return Result.success()
        val ran = RecordingTransferRunner(queue::entries, WearRecordingTransport(applicationContext, queue)).run()
        return if (ran && queue.entries().isEmpty()) Result.success() else Result.retry()
    }
}

object WatchTransferWork {
    const val UNIQUE_NAME = "watch-recording-transfer"

    fun enqueue(context: Context, replace: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<WatchTransferWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
