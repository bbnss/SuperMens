package it.supermens.local

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GenerationRunnerTest {
    @Test fun rejectedPreviewRequestsCancellationAndNeverPublishesSuccess() {
        var finish:()->Unit={};var cancelled=false
        val error=runCatching {
            GenerationRunner.run(start={chunk,done,_ ->finish=done;chunk("rejected text")},cancel={cancelled=true;finish()},partial={throw AiResponseRejected()})
        }.exceptionOrNull()
        assertTrue(error is AiResponseRejected);assertTrue(cancelled)
    }
    @Test fun streamsPreviewAndReturnsCompleteUnicodeOutput() {
        val previewed=CountDownLatch(1)
        var previews=0
        val result=GenerationRunner.run(start={chunk,done,_ ->
            Thread {
                chunk("Città 🌍")
                if(!previewed.await(3,TimeUnit.SECONDS)) error("No preview")
                chunk(" complete");done()
            }.apply {isDaemon=true}.start()
        },cancel={fail("Unexpected cancel")},partial={previews++;previewed.countDown()})
        assertEquals("Città 🌍 complete",result.text)
        assertTrue(previews>=2);assertNotNull(result.firstTokenMs)
    }
    @Test fun cancellationWaitsForNativeTerminalCallback() {
        val terminal=AtomicBoolean(false)
        var done:()->Unit={}
        var checks=0;var cancellations=0
        val error=runCatching {
            GenerationRunner.run(start={chunk,finish,_ ->done=finish;chunk("partial")},check={
                checks++;if(checks>1) throw InterruptedException("checkpoint")
            },cancel={
                cancellations++
                assertFalse(terminal.get())
                Thread {terminal.set(true);done()}.apply {isDaemon=true}.start()
            })
        }.exceptionOrNull()
        assertTrue(error is InterruptedException)
        assertTrue(terminal.get());assertEquals(1,cancellations)
        assertFalse(Thread.currentThread().isInterrupted)
    }
    @Test fun timeoutRequestsNativeCancellationInsteadOfPublishingPartialText() {
        var finish:()->Unit={};var tick=0L;var cancelled=false
        val error=runCatching {GenerationRunner.run(start={chunk,done,_ ->finish=done;chunk("partial")},cancel={cancelled=true;finish()},timeoutMs=1,now={tick++})}.exceptionOrNull()
        assertTrue(error is InferenceLimitException);assertTrue(cancelled)
    }
    @Test fun oversizedOutputIsCancelledAndNotReturnedAsSuccessfulText() {
        var finish:()->Unit={};var cancelled=false
        val error=runCatching {GenerationRunner.run(start={chunk,done,_ ->finish=done;chunk("oversized")},cancel={cancelled=true;finish()},maxChars=2)}.exceptionOrNull()
        assertTrue(error is InferenceLimitException);assertTrue(cancelled)
    }
    @Test fun nativeFailurePropagatesAndStaleResultsAreNotPublished() {
        val failure=IllegalStateException("native")
        assertSame(failure,runCatching {GenerationRunner.run(start={_,_,error ->error(failure)},cancel={})}.exceptionOrNull())
        var stopped=false
        val error=runCatching {GenerationRunner.run(start={chunk,done,_ ->chunk("result");stopped=true;done()},cancel={},check={if(stopped) throw InterruptedException()})}.exceptionOrNull()
        assertTrue(error is InterruptedException)
    }
}
