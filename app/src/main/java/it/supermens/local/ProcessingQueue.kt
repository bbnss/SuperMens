// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import android.app.Application
import android.app.DownloadManager
import android.content.*
import android.os.BatteryManager
import androidx.work.*
import java.util.concurrent.TimeUnit

object ProcessingQueue {
    private val mutation=Any()
    fun jobs(store:BrainStore):List<ProcessingJob> = store.readableDatabase.rawQuery("SELECT * FROM processing_jobs ORDER BY id",null).use { c -> buildList {
        while(c.moveToNext()) add(ProcessingJob(c.getLong(0),c.getString(1),c.getString(2),c.getInt(3)==1,c.getString(4),c.getString(5),c.getInt(6),c.getString(7),c.getLong(8)))
    } }
    fun enqueue(context:Context,id:String,manual:Boolean=false,enrichOnly:Boolean=false) = synchronized(mutation) {
        BrainStore(context).use { store ->
            val item=store.get(id) ?: return@synchronized
            if(!enrichOnly && item.type in setOf("web","x","instagram","facebook","tiktok","youtube","video","pdf")) add(store,id,"acquire",manual)
            add(store,id,"process",manual)
        }
        wake(context)
    }
    fun question(context:Context,id:String,text:String) { BrainStore(context).use { add(it,id,"question",true,text) };wake(context) }
    fun summary(context:Context,id:String) { BrainStore(context).use { it.writableDatabase.delete("summary_chunks","item_id=?",arrayOf(id));add(it,id,"summary",true) };wake(context) }
    fun remove(context:Context,id:String):BrainItem? {
        // Deleting the item cascades to every persistent job. Never wait on native inference.
        val old=synchronized(mutation) { BrainStore(context).use {it.delete(id)} } ?: return null
        WorkManager.getInstance(context).cancelUniqueWork("analyze-$id")
        listOf(old.pendingMedia,old.attachment,old.thumbnail).filter {it.isNotBlank()}.distinct().forEach {java.io.File(it).delete()}
        if(old.documentUri.isNotBlank()) BrainStore(context).use { store ->
            if(store.list().none {it.documentUri==old.documentUri}) runCatching {
                context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old.documentUri),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        wake(context)
        return old
    }
    private fun add(store:BrainStore,id:String,kind:String,manual:Boolean,payload:String="") = synchronized(mutation) {
        if(store.get(id)==null) return@synchronized
        val existing=jobs(store).firstOrNull { it.itemId==id && it.kind==kind && it.payload==payload && it.state in setOf("waiting","running") }
        if(existing!=null) { if(manual) store.writableDatabase.execSQL("UPDATE processing_jobs SET manual=1 WHERE id=?",arrayOf(existing.id));return@synchronized }
        store.writableDatabase.execSQL("UPDATE processing_jobs SET state='superseded' WHERE item_id=? AND kind=? AND state='failed'",arrayOf(id,kind))
        store.writableDatabase.insertOrThrow("processing_jobs",null,ContentValues().apply {
            put("item_id",id);put("kind",kind);put("manual",if(manual) 1 else 0);put("payload",payload);put("created_at",System.currentTimeMillis())
        })
    }
    fun state(store:BrainStore,job:ProcessingJob,state:String,progress:String="",error:Boolean=false) {
        store.writableDatabase.execSQL("UPDATE processing_jobs SET state=?,progress=?,attempts=attempts+? WHERE id=?",arrayOf<Any>(state,progress,if(error) 1 else 0,job.id))
    }
    fun charging(context:Context)=(context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
    fun eligible(context:Context,job:ProcessingJob)=job.manual || !LocalPrefs.chargingOnly(context) || charging(context)
    fun canUnload(context:Context,checkBusy:Boolean=true):Boolean = (!checkBusy || !ProcessingGuard.busy) && BrainStore(context).use { store ->
        val jobs=jobs(store)
        jobs.none {it.kind!="acquire" && it.state=="running"} && QueueOrder.next(jobs,emptySet(),{eligible(context,it) && LocalModel.ready(context)},{store.get(it)?.createdAt})==null
    }
    fun wake(context:Context) {
        val manager=WorkManager.getInstance(context)
        // APPEND avoids losing a wake arriving just as a running drain finishes.
        manager.enqueueUniqueWork("supermens-acquire",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<AcquireWorker>().build())
        wakeProcessing(context)
    }
    fun wakeProcessing(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("supermens-process",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<ProcessingWorker>().build())
    }
    fun waitNetwork(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("supermens-network-wake",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<NetworkWakeWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun connected(context:Context):Boolean {
        val manager=context.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities=manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    fun waitCharging(context:Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("supermens-charge-wake",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ChargingWakeWorker>().setConstraints(Constraints.Builder().setRequiresCharging(true).build()).build())
    }
    fun reconcile(context:Context,store:BrainStore) {
            store.list().filter { it.status=="saved" || it.status.startsWith("processing") || it.status.startsWith("pending_ai") }.forEach {
                if(jobs(store).none { job->job.itemId==it.id && job.state in setOf("waiting","running","failed") }) enqueue(context,it.id,enrichOnly=it.type=="pdf" && it.pdfPages>0 || store.segments(it.id).any { s->s.source!="testo condiviso" })
            }
    }
    fun recover(context:Context) {
        BrainStore(context).use { store ->
            store.writableDatabase.execSQL("UPDATE processing_jobs SET state='waiting' WHERE state='running'")
            reconcile(context,store)
        }
        wake(context)
    }
}
class SuperMensApp:Application() {
    override fun onCreate() { super.onCreate();Thread { LocalModel.state(this);ProcessingQueue.recover(this) }.start() }
}
class QueueWakeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action==DownloadManager.ACTION_DOWNLOAD_COMPLETE && intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID,-1)==LocalModel.downloadId(context)) LocalModel.state(context)
    }
}
class ChargingWakeWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { ProcessingQueue.wakeProcessing(applicationContext);return Result.success() }
}
class AcquireWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = BrainStore(applicationContext).use { store ->
        var retry=false
        for(job in ProcessingQueue.jobs(store).filter { it.kind=="acquire" && it.state=="waiting" }.sortedBy { store.get(it.itemId)?.createdAt ?: it.createdAt }) {
            if(isStopped) return@use Result.retry()
            val item=store.get(job.itemId) ?: continue
            if(item.type !in setOf("video","pdf") && !ProcessingQueue.connected(applicationContext)) {
                store.update(item.id,status="waiting_network");ProcessingQueue.waitNetwork(applicationContext);continue
            }
            ProcessingQueue.state(store,job,"running");store.update(item.id,status="acquiring")
            try {
                SourceAcquisition.acquire(applicationContext,store,item)
                ProcessingQueue.state(store,job,"done");store.update(item.id,status="pending_ai")
                ProcessingQueue.wakeProcessing(applicationContext)
            } catch(e:Exception) {
                if(store.get(item.id)==null) continue
                if(!ProcessingQueue.connected(applicationContext)) {
                    ProcessingQueue.state(store,job,"waiting",applicationContext.uiString(R.string.waiting_internet))
                    store.update(item.id,status="waiting_network");ProcessingQueue.waitNetwork(applicationContext);continue
                }
                val failed=job.attempts>=2
                ProcessingQueue.state(store,job,if(failed) "failed" else "waiting",e.message.orEmpty(),true)
                store.update(item.id,status=if(failed) "extraction_failed · ${e.message}" else "waiting_network")
                retry=retry || !failed
            }
        }
        if(retry) Result.retry() else Result.success()
    }
}
class ProcessingWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result = BrainStore(applicationContext).use { store ->
        val attempted=mutableSetOf<Long>()
        while(!isStopped) {
            val jobs=ProcessingQueue.jobs(store)
            val job=QueueOrder.next(jobs,attempted,{ProcessingQueue.eligible(applicationContext,it)},{store.get(it)?.createdAt})
            if(job==null) {
                if(jobs.any { it.kind!="acquire" && it.state=="waiting" && !ProcessingQueue.eligible(applicationContext,it) }) ProcessingQueue.waitCharging(applicationContext)
                break
            }
            attempted.add(job.id)
            val item=store.get(job.itemId) ?: continue
            ProcessingGuard.exclusive {
                ProcessingQueue.state(store,job,"running")
                fun check() { if(isStopped || store.get(item.id)==null || !ProcessingQueue.eligible(applicationContext,job)) throw InterruptedException("checkpoint") }
                try {
                    check()
                    runCatching { setForegroundAsync(ProcessingNotifications.foreground(applicationContext,item)).get() }
                    val processor=PostProcessor(applicationContext,store,::check)
                    when(job.kind) {
                        "question" -> {
                            if(!LocalModel.ready(applicationContext)) throw ModelWaiting()
                            val (reply,evidence)=LocalAi.answer(applicationContext,item,store.segments(item.id),job.payload,AppLanguage.code(applicationContext),{ ProcessingQueue.state(store,job,"running",it) },::check)
                            store.addAnswer(item.id,job.payload,reply,evidence,LocalAi.modelName(applicationContext))
                        }
                        "summary" -> processor.summarize(item)
                        else -> processor.process(item)
                    }
                    ProcessingQueue.state(store,job,"done")
                } catch(e:Exception) {
                    if(store.get(item.id)==null) return@exclusive
                    val waitingModel=e is ModelWaiting
                    val paused=e is InterruptedException
                    if(paused && !job.manual && LocalPrefs.chargingOnly(applicationContext)) ProcessingQueue.waitCharging(applicationContext)
                    val failed=!waitingModel && !paused && job.attempts>=2
                    ProcessingQueue.state(store,job,if(failed) "failed" else "waiting",if(waitingModel) applicationContext.uiString(R.string.install_model_hint) else if(paused) applicationContext.uiString(R.string.wait_charging) else e.message.orEmpty(),!waitingModel && !paused)
                    if(job.kind!="question") store.update(item.id,status=if(failed) "processing_failed · ${e.message}" else if(paused) "waiting_charging" else "pending_ai")
                    if(!failed && !paused && !waitingModel) {
                        WorkManager.getInstance(applicationContext).enqueueUniqueWork("supermens-retry",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<RetryWakeWorker>().setInitialDelay(30,TimeUnit.SECONDS).build())
                    }
                }
            }
        }
        Result.success()
    }
}
class RetryWakeWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result { ProcessingQueue.wakeProcessing(applicationContext);return Result.success() }
}
class ModelWaiting:IllegalStateException("model")

class NetworkWakeWorker(context:Context,params:WorkerParameters):Worker(context,params) {
    override fun doWork():Result {ProcessingQueue.wake(applicationContext);return Result.success()}
}
