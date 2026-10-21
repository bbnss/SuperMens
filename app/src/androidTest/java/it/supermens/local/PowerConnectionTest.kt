package it.supermens.local

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Run on battery, then with external power and BATTERY_STATUS_NOT_CHARGING. */
class PowerConnectionTest {
    @Test fun externalPowerAndManualEligibilityMatchDeviceState() {
        val argument=InstrumentationRegistry.getArguments().getString("expectedPowered")
        org.junit.Assume.assumeNotNull(argument)
        val expected=argument!!.toBoolean()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val old=LocalPrefs.chargingOnly(context)
        try {
            LocalPrefs.setChargingOnly(context,true)
            assertEquals(expected,ProcessingQueue.charging(context))
            val automatic=ProcessingJob(1,"fixture","summary",false,"","waiting",0,"",0)
            assertEquals(expected,ProcessingQueue.eligible(context,automatic))
            assertTrue(ProcessingQueue.eligible(context,automatic.copy(manual=true)))
        } finally {LocalPrefs.setChargingOnly(context,old)}
    }
}
