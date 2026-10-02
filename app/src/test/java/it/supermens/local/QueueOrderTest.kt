// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QueueOrderTest {
    private fun job(id:Long,item:String="post$id",manual:Boolean=false,kind:String="process",state:String="waiting")=ProcessingJob(id,item,kind,manual,"",state,0,"",id)
    @Test fun oldestPostFirstEvenWhenRetryArrivedLater() {
        val jobs=listOf(job(1,"new"),job(2,"old"))
        assertEquals("old",QueueOrder.next(jobs,emptySet(),{true},{if(it=="old") 10 else 20})!!.itemId)
    }
    @Test fun manualQuestionPrecedesAutomaticQueueButNeverPreemptsRunningJob() {
        val jobs=listOf(job(1,state="running"),job(2),job(3,manual=true,kind="question"))
        assertEquals(3L,QueueOrder.next(jobs,emptySet(),{true},{10})!!.id)
    }
    @Test fun incompleteAcquisitionDoesNotBlockOtherReadyPosts() {
        val jobs=listOf(job(1,"old"),job(2,"old",kind="acquire"),job(3,"new"))
        assertEquals(3L,QueueOrder.next(jobs,emptySet(),{true},{10})!!.id)
    }
    @Test fun failedSourceBlocksItsOwnInferenceUntilExplicitRetry() {
        val jobs=listOf(job(1,"old"),job(2,"old",kind="acquire",state="failed"),job(3,"new"))
        assertEquals(3L,QueueOrder.next(jobs,emptySet(),{true},{10})!!.id)
    }
    @Test fun batteryAllowsManualAndLeavesAutomaticPending() {
        val jobs=listOf(job(1),job(2,manual=true))
        assertEquals(2L,QueueOrder.next(jobs,emptySet(),{it.manual},{10})!!.id)
        assertNull(QueueOrder.next(jobs,setOf(2),{it.manual},{10}))
    }
    @Test fun guardSerializesWholeOperationsIncludingNestedCallsAndFailures() {
        val start=CountDownLatch(1);val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val active=AtomicInteger();val maximum=AtomicInteger()
        val first=Thread {ProcessingGuard.exclusive {active.incrementAndGet();entered.countDown();release.await(5,TimeUnit.SECONDS);ProcessingGuard.exclusive {assertTrue(ProcessingGuard.busy)};active.decrementAndGet()}}
        val second=Thread {start.countDown();ProcessingGuard.exclusive {maximum.set(active.incrementAndGet());active.decrementAndGet()}}
        first.start();assertTrue(entered.await(5,TimeUnit.SECONDS));second.start();assertTrue(start.await(5,TimeUnit.SECONDS));assertEquals(1,active.get());release.countDown();first.join();second.join()
        assertEquals(1,maximum.get());assertFalse(ProcessingGuard.busy)
        runCatching {ProcessingGuard.exclusive {error("test")}};assertFalse(ProcessingGuard.busy)
    }
}
