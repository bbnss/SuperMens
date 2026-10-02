// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File

object LocalModel {
    const val label = "Gemma 4 E2B"
    const val fileName = "gemma-4-E2B-it.litertlm"
    const val expectedBytes = 2_583_085_056L
    private const val downloadIdKey = "gemma_download_id"
    private const val revision = "7fa1d78473894f7e736a21d920c3aa80f950c0db"
    private const val url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/$revision/$fileName?download=true"
    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models/$fileName")
    fun ready(context: Context): Boolean = file(context).let { it.isFile && it.length() == expectedBytes }
    private fun preferences(context: Context) = context.getSharedPreferences("local_model", Context.MODE_PRIVATE)
    fun downloadId(context: Context) = preferences(context).getLong(downloadIdKey, -1L)
    fun start(context: Context): Long {
        require(!ready(context)) { context.uiString(R.string.model_already_installed) }
        val manager = context.getSystemService(DownloadManager::class.java)
        if (downloadId(context) >= 0 && state(context).active) return downloadId(context)
        val target = file(context)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("SuperMens · Gemma 4 E2B")
            .setDescription(context.uiString(R.string.model_download_description))
            .setMimeType("application/octet-stream")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(false)
        val id = manager.enqueue(request)
        preferences(context).edit().putLong(downloadIdKey, id).apply()
        return id
    }
    data class State(val message: String, val progress: Int, val active: Boolean)
    fun state(context: Context): State {
        if (ready(context)) return State(context.uiString(R.string.model_ready), 100, false)
        val id = downloadId(context)
        if (id < 0) return State(context.uiString(R.string.model_not_installed), 0, false)
        val manager = context.getSystemService(DownloadManager::class.java)
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return State(context.uiString(R.string.download_missing), 0, false)
            fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
            val bytes = number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val total = number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).takeIf { it > 0 } ?: expectedBytes
            val progress = ((bytes * 100) / total).toInt().coerceIn(0, 100)
            return when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_PENDING -> State(context.uiString(R.string.download_waiting), progress, true)
                DownloadManager.STATUS_RUNNING -> State(context.uiString(R.string.download_progress,progress), progress, true)
                DownloadManager.STATUS_PAUSED -> State(context.uiString(R.string.download_paused,progress), progress, true)
                DownloadManager.STATUS_SUCCESSFUL -> State(context.uiString(R.string.model_wrong_size,file(context).length()), progress, false)
                else -> State(context.uiString(R.string.download_failed), progress, false)
            }
        }
    }
    fun cancel(context: Context) {
        val id = downloadId(context)
        if (id >= 0) context.getSystemService(DownloadManager::class.java).remove(id)
        preferences(context).edit().remove(downloadIdKey).apply()
        file(context).delete()
    }
    fun import(context: Context, uri: Uri) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, "$fileName.import")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> staged.outputStream().use { input.copyTo(it) } }
                ?: error(context.uiString(R.string.file_unreadable))
            require(staged.length() == expectedBytes) { context.uiString(R.string.model_invalid_size,staged.length()) }
            ProcessingGuard.exclusive {
                LocalAi.unload()
                cancel(context)
                require(staged.renameTo(target)) { context.uiString(R.string.model_install_failed) }
            }
            ProcessingQueue.wakeProcessing(context)
        } finally { staged.delete() }
    }
}
