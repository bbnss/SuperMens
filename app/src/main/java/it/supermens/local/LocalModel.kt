// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.UUID

object LocalModel {
    const val label = "Gemma 4 E2B"
    const val fileName = "gemma-4-E2B-it.litertlm"
    const val expectedBytes = 2_583_085_056L
    const val expectedSha256 = "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
    private const val downloadIdKey = "gemma_download_id"
    private const val revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    private const val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$revision/$fileName?download=true"
    private const val verificationWork = "supermens-model-verification"
    private val lock = Any()
    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/$fileName")
    private fun partial(context: Context) = File(file(context).parentFile, "$fileName.download")
    private fun preferences(context: Context) = context.getSharedPreferences("local_model", Context.MODE_PRIVATE)
    fun ready(context: Context): Boolean {
        val target = file(context)
        val prefs = preferences(context)
        return target.isFile && target.length() == expectedBytes &&
            prefs.getString("verified_sha256", "") == expectedSha256 &&
            prefs.getLong("verified_modified", -1) == target.lastModified()
    }
    fun downloadId(context: Context) = preferences(context).getLong(downloadIdKey, -1L)
    fun start(context: Context, allowMetered: Boolean = false): Long = synchronized(lock) {
        require(!ready(context)) { context.uiString(R.string.model_already_installed) }
        if (state(context).active) return@synchronized downloadId(context)
        cancel(context)
        val target = partial(context)
        target.parentFile?.mkdirs()
        val request = DownloadManager.Request(Uri.parse("$url&request=${UUID.randomUUID()}"))
            .setTitle("SuperMens · Gemma 4 E2B")
            .setDescription(context.uiString(R.string.model_download_description))
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(allowMetered)
            .setAllowedOverRoaming(false)
        if (!allowMetered) request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
        val id = context.getSystemService(DownloadManager::class.java).enqueue(request)
        preferences(context).edit().putLong(downloadIdKey, id).putString("generation", UUID.randomUUID().toString()).apply()
        id
    }
    data class State(val message: String, val progress: Int, val active: Boolean, val waitingForWifi: Boolean = false)
    fun state(context: Context): State {
        if (ready(context)) return State(context.uiString(R.string.model_ready), 100, false)
        val failure = preferences(context).getString("verification_error", "").orEmpty()
        if (failure.isNotBlank()) return State(failure, 0, false)
        // DownloadManager preallocates the full size before downloading. Honor its status
        // before validating an earlier build's final-path download.
        fun legacyState() = if (file(context).isFile && file(context).length() == expectedBytes) verifying(context) else null
        val id = downloadId(context)
        if (id < 0) return legacyState() ?: State(context.uiString(R.string.model_not_installed), 0, false)
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return legacyState() ?: State(context.uiString(R.string.download_missing), 0, false)
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val bytes = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: expectedBytes
            val progress = ((bytes * 100) / total).toInt().coerceIn(0, 100)
            return when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_PENDING -> State(context.uiString(R.string.download_waiting), progress, true)
                DownloadManager.STATUS_RUNNING -> State(context.uiString(R.string.download_progress, progress), progress, true)
                DownloadManager.STATUS_PAUSED -> {
                    val reason = number(DownloadManager.COLUMN_REASON).toInt()
                    State(context.uiString(if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) R.string.download_waiting_wifi else R.string.download_paused, progress), progress, true, reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
                }
                DownloadManager.STATUS_SUCCESSFUL -> verifying(context)
                else -> State(context.uiString(R.string.download_failed_reason, number(DownloadManager.COLUMN_REASON)), progress, false)
            }
        }
    }
    private fun verifying(context: Context): State {
        synchronized(lock) {
            val prefs = preferences(context)
            val generation = prefs.getString("generation", null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString("generation", it).apply()
            }
            WorkManager.getInstance(context).enqueueUniqueWork(verificationWork, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ModelVerificationWorker>().setInputData(workDataOf("generation" to generation)).build())
        }
        return State(context.uiString(R.string.model_verifying), 100, true)
    }
    fun cancel(context: Context) = synchronized(lock) {
        val installed = ready(context)
        WorkManager.getInstance(context).cancelUniqueWork(verificationWork)
        val id = downloadId(context)
        if (id >= 0) context.getSystemService(DownloadManager::class.java).remove(id)
        val editor = preferences(context).edit().remove(downloadIdKey)
            .remove("verification_error").putString("generation", UUID.randomUUID().toString())
        if (!installed) editor.remove("verified_sha256").remove("verified_modified")
        editor.apply()
        partial(context).delete()
        // A cancelled download must not remove a previously verified model.
        if (!installed) file(context).delete()
    }
    private fun markVerified(context: Context) {
        preferences(context).edit().putString("verified_sha256", expectedSha256)
            .putLong("verified_modified", file(context).lastModified()).remove("verification_error").apply()
    }
    internal fun verifyDownload(context: Context, generation: String, check: () -> Unit) {
        val prefs = preferences(context)
        if (prefs.getString("generation", "") != generation) return
        val candidate = partial(context).takeIf { it.isFile } ?: file(context)
        try {
            require(ModelIntegrity.matches(candidate, expectedBytes, expectedSha256, check)) {
                context.uiString(R.string.model_integrity_failed)
            }
            ProcessingGuard.exclusive {
                synchronized(lock) {
                    check()
                    if (prefs.getString("generation", "") != generation) return@exclusive
                    LocalAi.unload()
                    if (candidate != file(context)) {
                        file(context).delete()
                        require(candidate.renameTo(file(context))) { context.uiString(R.string.model_install_failed) }
                    }
                    markVerified(context)
                }
            }
            if (ready(context)) ProcessingQueue.wakeProcessing(context)
        } catch (error: Exception) {
            if (error is InterruptedException) throw error
            synchronized(lock) {
                if (prefs.getString("generation", "") == generation) {
                    prefs.edit().putString("verification_error", error.message ?: context.uiString(R.string.model_integrity_failed)).apply()
                }
            }
        }
    }
    fun import(context: Context, uri: Uri) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val staged = File.createTempFile("model-import-", ".litertlm", target.parentFile)
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> staged.outputStream().use { input.copyTo(it) } }
                ?: error(context.uiString(R.string.file_unreadable))
            require(ModelIntegrity.matches(staged, expectedBytes, expectedSha256)) { context.uiString(R.string.model_integrity_failed) }
            ProcessingGuard.exclusive {
                synchronized(lock) {
                    LocalAi.unload()
                    cancel(context)
                    target.delete()
                    require(staged.renameTo(target)) { context.uiString(R.string.model_install_failed) }
                    markVerified(context)
                }
            }
            ProcessingQueue.wakeProcessing(context)
        } finally { staged.delete() }
    }
}

class ModelVerificationWorker(context: Context, params: WorkerParameters): Worker(context, params) {
    override fun doWork(): Result {
        return try {
            LocalModel.verifyDownload(applicationContext, inputData.getString("generation").orEmpty()) {
                if (isStopped) throw InterruptedException("Model verification interrupted")
            }
            Result.success()
        } catch (_: InterruptedException) { Result.retry() }
    }
}
