package com.seizureguard.phone.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ── VERSION_MISMATCH (Batch 8b): tracked independently of the BridgeFault-derived period ──

    private fun open(kind: FaultKind) = log().periods().filter { it.kind == kind && it.endMs == null }

    @Test fun versionMismatch_opensOnIncompatible_closesOnMatch_andIsIdempotent() {
        val log = log()
        log.onVersionCompatibility(VersionCompatibility.MATCH) // nothing open: no entry
        assertEquals(emptyList<FaultPeriod>(), log.periods())
        log.onVersionCompatibility(VersionCompatibility.MISMATCH)
        now = 2_000
        log.onVersionCompatibility(VersionCompatibility.MISSING) // still incompatible: same period
        log.onVersionCompatibility(VersionCompatibility.MISMATCH)
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 1_000, null)), log.periods())
        now = 3_000
        log.onVersionCompatibility(VersionCompatibility.MATCH)
        log.onVersionCompatibility(VersionCompatibility.MATCH)
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 1_000, 3_000)), log.periods())
    }

    @Test fun versionMismatch_coexistsWithAnotherOpenFault_neitherClosesTheOther() {
        val log = log()
        log.onFault(BridgeFault.OSD_UNREACHABLE) // 1000
        now = 2_000
        log.onVersionCompatibility(VersionCompatibility.MISMATCH) // 2000, must not close OSD_UNREACHABLE
        assertEquals(
            listOf(FaultPeriod(FaultKind.OSD_UNREACHABLE, 1_000, null), FaultPeriod(FaultKind.VERSION_MISMATCH, 2_000, null)),
            log.periods(),
        )
        now = 3_000
        log.onFault(BridgeFault.NO_WATCH_DATA) // switches the bridge fault, must not close VERSION_MISMATCH
        now = 4_000
        log.onFault(BridgeFault.NONE) // bridge recovers, must not close VERSION_MISMATCH
        assertEquals(
            listOf(
                FaultPeriod(FaultKind.OSD_UNREACHABLE, 1_000, 3_000),
                FaultPeriod(FaultKind.VERSION_MISMATCH, 2_000, null),
                FaultPeriod(FaultKind.NO_WATCH_DATA, 3_000, 4_000),
            ),
            log.periods(),
        )
        now = 5_000
        log.onFault(BridgeFault.OSD_DATA_STALE) // new bridge fault while the version period is open
        now = 6_000
        log.onVersionCompatibility(VersionCompatibility.MATCH) // closes only the version period
        assertEquals(listOf(FaultPeriod(FaultKind.OSD_DATA_STALE, 5_000, null)), open(FaultKind.OSD_DATA_STALE))
        assertEquals(emptyList<FaultPeriod>(), open(FaultKind.VERSION_MISMATCH))
        assertEquals(
            FaultPeriod(FaultKind.VERSION_MISMATCH, 2_000, 6_000),
            log().periods().single { it.kind == FaultKind.VERSION_MISMATCH },
        )
    }

    @Test fun versionMismatch_repeatedBridgeFaultWhileVersionPeriodOpen_doesNotDuplicate() {
        val log = log()
        log.onVersionCompatibility(VersionCompatibility.MISSING)
        repeat(5) { log.onFault(BridgeFault.NO_WATCH_DATA) }
        assertEquals(2, log.periods().size)
    }

    @Test fun versionMismatch_persistsAndReloads() {
        log().onVersionCompatibility(VersionCompatibility.MISMATCH)
        val reborn = log()
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 1_000, null)), reborn.periods())
        now = 2_000
        reborn.onVersionCompatibility(VersionCompatibility.MATCH) // a reloaded open period can still be closed
        assertEquals(listOf(FaultPeriod(FaultKind.VERSION_MISMATCH, 1_000, 2_000)), log().periods())
        assertTrue(store.get("fault_periods")!!.contains("\"VERSION_MISMATCH\""))
    }

    @Test fun versionMismatch_closeOpenClosesItLikeAnyOtherOpenPeriod() {
        val log = log()
        log.onVersionCompatibility(VersionCompatibility.MISMATCH)
        log.closeOpen(4_000)
        assertEquals(4_000L, log.periods().single().endMs)
    }

    @Test fun oldLogWithoutTheNewKind_stillLoads_andNewKindCanBeAdded() {
        store.put("fault_periods", """[{"k":"NO_WATCH_DATA","s":1,"e":2},{"k":"SERVICE_DOWN","s":3}]""")
        val log = log()
        assertEquals(
            listOf(FaultPeriod(FaultKind.NO_WATCH_DATA, 1, 2), FaultPeriod(FaultKind.SERVICE_DOWN, 3, null)),
            log.periods(),
        )
        log.onVersionCompatibility(VersionCompatibility.MISSING)
        assertEquals(3, log().periods().size)
    }
}
