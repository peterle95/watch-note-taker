package com.peterle95.watchnotetaker.phone

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class DeliveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : Worker(context, parameters) {
    override fun doWork(): Result {
        val vault = VaultDelivery(applicationContext)
        if (vault.folderState() != VaultFolderState.CONNECTED) return Result.success()
        return when (PhoneDeliveryRepository(PhoneAudioStore(applicationContext), vault).runPending()) {
            DeliveryRun.RETRY -> Result.retry()
            DeliveryRun.IDLE, DeliveryRun.COMPLETED, DeliveryRun.CONFLICT -> Result.success()
        }
    }
}

object DeliveryWork {
    const val UNIQUE_NAME = "markdown-delivery"

    fun enqueue(context: Context, replace: Boolean = false): Operation {
        val request = OneTimeWorkRequestBuilder<DeliveryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NAME,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }
}
