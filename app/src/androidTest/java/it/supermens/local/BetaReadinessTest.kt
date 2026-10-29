package it.supermens.local

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BetaReadinessTest {
    @Test fun stoppedReceiptCannotBeRevivedByALateProgressCallback() = ProcessingGuard.exclusive {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        BrainStore(context).use {store ->
            val id=store.add("note","Cancellation regression")
            try {
                store.writableDatabase.execSQL("INSERT INTO processing_jobs(item_id,kind,manual,payload,state,created_at) VALUES(?,?,?,?,?,?)",arrayOf(id,"summary",1,"","waiting",System.currentTimeMillis()))
                val job=ProcessingQueue.jobs(store).single {it.itemId==id}
                ProcessingQueue.stop(context,id)
                ProcessingQueue.state(store,job,"running","late callback")
                assertEquals("failed",ProcessingQueue.jobs(store).single {it.itemId==id}.state)
            } finally {store.delete(id)}
        }
    }
    @Test fun newInstallRunsOnBatteryAndUpdatesPreserveBothExplicitChoices() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString()
        val database=File(base.cacheDir,"$id.db")
        val context=object:ContextWrapper(base) {
            override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("$id-$name",mode)
            override fun getDatabasePath(name:String)=database
        }
        val options=context.getSharedPreferences("local_options",Context.MODE_PRIVATE)
        try {
            assertFalse(LocalPrefs.chargingOnly(context))
            LocalPrefs.initialize(context);assertFalse(LocalPrefs.chargingOnly(context))
            LocalPrefs.setChargingOnly(context,true)
            LocalPrefs.initialize(context);assertTrue(LocalPrefs.chargingOnly(context))
            LocalPrefs.setChargingOnly(context,false)
            LocalPrefs.initialize(context);assertFalse(LocalPrefs.chargingOnly(context))
            options.edit().clear().commit();database.writeText("Existing archive")
            LocalPrefs.initialize(context);assertFalse(LocalPrefs.chargingOnly(context))
        } finally {options.edit().clear().commit();database.delete()}
    }
    @Test fun diagnosticsExcludeUserContentAndPersistentDeviceIdentifiers() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val value=org.json.JSONObject(ModelDiagnostics.snapshot(context))
        listOf("serial","android_id","prompt","url","transcript","body","question").forEach {assertFalse(value.has(it))}
        assertFalse(value.getBoolean("device_certified"))
        assertTrue(value.has("model_label"));assertTrue(value.has("model"))
    }
}
