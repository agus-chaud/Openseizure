package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class MemoryStore : StringStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
}

@RunWith(RobolectricTestRunner::class) // org.json needs the Android runtime
@Config(sdk = [34])
class FaultLogTest {
    private val store = MemoryStore()
    private var now = 1_000L
    private fun log() = FaultLog(store) { now }

    @Test fun transitions_openCloseAndSwitch() {
        val log = log()
        log.onFault(BridgeFault.NONE)
        assertEquals(emptyList<FaultPeriod>(), log.periods())
        log.onFault(BridgeFault.NO_WATCH_DATA)
        now = 5_000
        log.onFault(BridgeFault.NO_WATCH_DATA) // same fault: no new period
        log.onFault(BridgeFault.OSD_UNREACHABLE)
        now = 9_000
        log.onFault(BridgeFault.NONE)
        assertEquals(
            listOf(
                FaultPeriod(FaultKind.NO_WATCH_DATA, 1_000, 5_000),
                FaultPeriod(FaultKind.OSD_UNREACHABLE, 5_000, 9_000),
            ),
            log.periods(),
        )
    }

    @Test fun survivesProcessDeath_andClosesStaleOpenPeriod() {
        log().onFault(BridgeFault.OSD_DATA_STALE)
        val reborn = log() // new instance, same storage
        assertEquals(listOf(FaultPeriod(FaultKind.OSD_DATA_STALE, 1_000, null)), reborn.periods())
        reborn.closeOpen(4_000)
        assertEquals(4_000L, reborn.periods().single().endMs)
    }

    @Test fun retention_dropsOldPeriods_andCapsEntries() {
        val log = log()
        log.add(FaultPeriod(FaultKind.SERVICE_DOWN, 0, 10))
        now = FAULT_LOG_RETENTION_MS + 1_000
        repeat(FAULT_LOG_MAX_ENTRIES + 20) { log.add(FaultPeriod(FaultKind.NO_WATCH_DATA, now - 5, now - 1)) }
        assertEquals(FAULT_LOG_MAX_ENTRIES, log.periods().size)
        assertNull(log.periods().firstOrNull { it.kind == FaultKind.SERVICE_DOWN })
    }

    @Test fun corruptStorage_yieldsEmptyLog() {
        store.put("fault_periods", "not json")
        assertEquals(emptyList<FaultPeriod>(), log().periods())
    }

    @Test fun unknownKind_isSkipped() {
        store.put("fault_periods", """[{"k":"FUTURE","s":1},{"k":"SERVICE_DOWN","s":2,"e":3}]""")
        assertEquals(listOf(FaultPeriod(FaultKind.SERVICE_DOWN, 2, 3)), log().periods())
    }
}
