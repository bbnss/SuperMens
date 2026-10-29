// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class InferenceLimitException(message:String):IllegalStateException(message)
internal data class GenerationResult(val text:String,val elapsedMs:Long,val firstTokenMs:Long?)

/** Cancellation requests never release the native owner before its terminal callback. */
internal object GenerationRunner {
    fun run(start:((String)->Unit,()->Unit,(Throwable)->Unit)->Unit,cancel:()->Unit,
            check:()->Unit={},partial:(String)->Unit={},timeoutMs:Long=180_000,maxChars:Int=8000,
            now:()->Long={System.nanoTime()/1_000_000}):GenerationResult {
        val finished=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val text=StringBuffer()
        val started=now()
        var first:Long?=null
        check()
        start({chunk ->
            synchronized(text) {
                if(first==null && chunk.isNotEmpty()) first=now()-started
                if(text.length+chunk.length<=maxChars) text.append(chunk)
                else failure.compareAndSet(null,InferenceLimitException("AI response exceeded the output limit"))
            }
        },{finished.countDown()},{error ->failure.compareAndSet(null,error);finished.countDown()})
        var interrupted=false
        var cancellationRequested=false
        var last=""
        while(true) {
            try {
                val completed=try {finished.await(100,TimeUnit.MILLISECONDS)} catch(error:InterruptedException) {
                    interrupted=true
                    throw error
                }
                if(completed) break
                check()
                if(now()-started>=timeoutMs) throw InferenceLimitException("AI inference exceeded the time limit")
                val snapshot=text.toString()
                if(snapshot!=last) {partial(snapshot);last=snapshot}
            } catch(error:Throwable) {
                failure.compareAndSet(null,error)
            }
            if(failure.get()!=null && !cancellationRequested) {
                cancellationRequested=true
                // Even when cancel fails, await native completion before closing its resources.
                runCatching {cancel()}
            }
        }
        if(interrupted) Thread.currentThread().interrupt()
        failure.get()?.let {throw it}
        check()
        val result=text.toString().trim()
        partial(result)
        return GenerationResult(result,now()-started,first)
    }
}
