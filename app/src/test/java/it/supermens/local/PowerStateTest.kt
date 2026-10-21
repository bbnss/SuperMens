package it.supermens.local

import org.junit.Assert.*
import org.junit.Test

class PowerStateTest {
    @Test fun externalPowerCountsWithChargingPausedOrBatteryFull() {
        listOf(1,2,4,8).forEach {assertTrue(PowerState.connected(it,false))}
        assertTrue(PowerState.connected(0,true))
        assertFalse(PowerState.connected(0,false))
        assertFalse(PowerState.connected(-1,false))
    }
}
