package com.seizureguard.phone.setup

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPlanTest {
    private val notif = Manifest.permission.POST_NOTIFICATIONS
    private val bt = Manifest.permission.BLUETOOTH_CONNECT

    @Test fun api34_nothingGranted_asksBoth() =
        assertEquals(listOf(notif, bt), missingRuntimePermissions(34, false, false))

    @Test fun api34_allGranted_asksNothing() =
        assertEquals(emptyList<String>(), missingRuntimePermissions(34, true, true))

    @Test fun api33_asksBoth() =
        assertEquals(listOf(notif, bt), missingRuntimePermissions(33, false, false))

    @Test fun api32_needsBluetoothOnly() =
        assertEquals(listOf(bt), missingRuntimePermissions(32, false, false))

    @Test fun api26_needsNothing() =
        assertEquals(emptyList<String>(), missingRuntimePermissions(26, false, false))

    @Test fun batteryExemption_neededOnlyWhenOptimised() {
        assertTrue(needsBatteryExemption(false))
        assertFalse(needsBatteryExemption(true))
    }
}
