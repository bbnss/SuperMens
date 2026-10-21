// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/** Kept so WorkManager can retire requests persisted by earlier APKs without inference. */
class DailyWakeWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork() = Result.success()
}
class DailySummaryWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork() = Result.success()
}
internal object RetiredDailyWork {
    fun cancel(context: Context) {
        val manager=WorkManager.getInstance(context)
        manager.cancelUniqueWork("supermens-daily")
        manager.cancelUniqueWork("supermens-daily-now")
        manager.cancelAllWorkByTag(DailySummaryWorker::class.java.name)
        manager.cancelAllWorkByTag(DailyWakeWorker::class.java.name)
    }
}
