package it.supermens.local

/** Eligibility is injected so FIFO and manual priority have no Android dependency. */
data class ProcessingJob(val id:Long,val itemId:String,val kind:String,val manual:Boolean,val payload:String,val state:String,val attempts:Int,val progress:String,val createdAt:Long)
object QueueOrder {
    fun next(jobs:List<ProcessingJob>,attempted:Set<Long>,eligible:(ProcessingJob)->Boolean,itemCreated:(String)->Long?):ProcessingJob? = jobs
        .filter {it.kind!="acquire" && it.state=="waiting" && it.id !in attempted && eligible(it)}
        .filter {job -> jobs.none {it.itemId==job.itemId && it.kind=="acquire" && it.state in setOf("waiting","running","failed")}}
        .sortedWith(compareByDescending<ProcessingJob> {it.manual}.thenBy {if(it.manual) it.createdAt else itemCreated(it.itemId) ?: it.createdAt}.thenBy {it.id}).firstOrNull()
}
/** Remains held until the underlying native operation actually returns, including cancellation. */
object ProcessingGuard {
    private val lock=Any()
    @Volatile var busy=false; private set
    fun <T> exclusive(block:()->T):T = synchronized(lock) {
        val outer=!busy
        if(outer) busy=true
        try {block()} finally {if(outer) busy=false}
    }
}
