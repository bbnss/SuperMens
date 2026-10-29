// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object DeviceHealth {
    private val executor=Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable,"supermens-health").apply {isDaemon=true}
    }
    @Volatile var thermalStatus=PowerManager.THERMAL_STATUS_NONE; private set
    val paused get()=BetaPolicy.thermalPause(thermalStatus)
    fun start(context:Context) {
        val app=context.applicationContext
        val power=app.getSystemService(PowerManager::class.java)
        thermalStatus=power.currentThermalStatus
        power.addThermalStatusListener(executor) {status ->
            val wasPaused=paused
            thermalStatus=status
            if(wasPaused && !paused) ProcessingQueue.wakeProcessing(app)
        }
        executor.scheduleWithFixedDelay({
            runCatching {
                if(LocalAi.loaded.value && LocalAi.idle && !ProcessingGuard.busy && ProcessingQueue.canUnload(app)) {
                    ProcessingGuard.exclusive {if(LocalAi.idle && ProcessingQueue.canUnload(app,false)) LocalAi.unload()}
                }
            }
        },1,1,TimeUnit.MINUTES)
    }
    fun releaseIdle(context:Context) {
        executor.execute {
            if(!ProcessingGuard.busy) ProcessingGuard.exclusive {
                if(ProcessingQueue.canUnload(context,false)) LocalAi.unload()
            }
        }
    }
}
