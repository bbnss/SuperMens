package it.supermens.local

/** External power also counts when Android pauses battery charging. */
internal object PowerState {
    fun connected(plugged:Int,charging:Boolean)=plugged>0 || charging
}
