package com.seizureguard.phone.bridge

import com.seizureguard.phone.summary.buildSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val SEC = 1_000L
private const val MIN = 60 * SEC
private const val H = 60 * MIN

/** Counts writes per key on top of [MemoryStore]. */
private class CountingStore : StringStore {
    val inner = MemoryStore()
    val writes = mutableMapOf<String, Int>()
    override fun get(key: String) = inner.get(key)
    override fun put(key: String, value: String) {
        writes[key] = (writes[key] ?: 0) + 1
        inner.put(key, value)
    }
}

internal class FakeLink(var nodes: List<WatchNode>? = emptyList()) : WatchLink {
    val sentTo = mutableListOf<String>()
    var sendFails = false
    var sendThrows = false
    var checks = 0
    override fun connectedNodes(): List<WatchNode>? { checks++; return nodes }
    override fun sendResume(nodeId: String): Boolean {
        if (sendThrows) throw IllegalStateException("boom")
        sentTo += nodeId
        return !sendFails
    }
}

internal class FakeAlert : NoDataAlertSink {
    data class Post(val sinceMs: Long, val cause: NodeCheck, val retries: Int)
    val posts = mutableListOf<Post>()
    var cancels = 0
    override fun post(sinceMs: Long, cause: NodeCheck, retriesTried: Int) { posts += Post(sinceMs, cause, retriesTried) }
    override fun cancel() { cancels++ }
}

// ── Pure policy ───────────────────────────────────────────────────────────────

class NoDataActionTest {
    private fun act(elapsed: Long, retries: Int = 0, alert: Boolean = false, stopped: Boolean = false, lastRetry: Long? = null) =
        noDataAction(1_000_000 + elapsed, 1_000_000, retries, alert, stopped, lastRetry)

    @Test fun signedConstants() {
        assertEquals(3 * H, NO_DATA_RETRY_AFTER_MS)
        assertEquals(1 * H, NO_DATA_RETRY_INTERVAL_MS)
        assertEquals(3, NO_DATA_MAX_RETRIES)
        assertEquals(8 * H, NO_DATA_ALERT_AFTER_MS)
    }

    @Test fun nothingBeforeThreeHours() {
        assertEquals(NoDataAction.NONE, act(0))
        assertEquals(NoDataAction.NONE, act(30 * SEC))
        assertEquals(NoDataAction.NONE, act(2 * H + 59 * MIN))
        assertEquals(NoDataAction.NONE, act(3 * H - 1))
    }

    @Test fun firstRetryAtThreeHours() {
        assertEquals(NoDataAction.RETRY, act(3 * H))
        assertEquals(NoDataAction.RETRY, act(3 * H + 10 * SEC))
    }

    @Test fun secondRetryAtFourHours() {
        assertEquals(NoDataAction.NONE, act(3 * H + 59 * MIN, retries = 1))
        assertEquals(NoDataAction.RETRY, act(4 * H, retries = 1))
    }

    @Test fun thirdRetryAtFiveHours() {
        assertEquals(NoDataAction.NONE, act(4 * H + 59 * MIN, retries = 2))
        assertEquals(NoDataAction.RETRY, act(5 * H, retries = 2))
    }

    @Test fun neverMoreThanThreeRetries() {
        assertEquals(NoDataAction.NONE, act(5 * H, retries = 3))
        assertEquals(NoDataAction.NONE, act(6 * H, retries = 3))
        assertEquals(NoDataAction.NONE, act(7 * H + 59 * MIN, retries = 3))
        assertEquals(NoDataAction.NONE, act(7 * H + 59 * MIN + 59 * SEC, retries = 3))
    }

    @Test fun alertAtEightHours() {
        assertEquals(NoDataAction.ALERT, act(8 * H, retries = 3))
        assertEquals(NoDataAction.ALERT, act(8 * H + 10 * SEC, retries = 3))
        assertEquals(NoDataAction.ALERT, act(30 * H, retries = 3))
    }

    @Test fun alertWinsOverADueRetry() {
        assertEquals(NoDataAction.ALERT, act(8 * H, retries = 1))
        assertEquals(NoDataAction.ALERT, act(8 * H, retries = 0))
    }

    @Test fun afterTheAlertNothingMore_oncePossibleRetriesAreDone() {
        assertEquals(NoDataAction.NONE, act(8 * H, retries = 3, alert = true))
        assertEquals(NoDataAction.NONE, act(9 * H, retries = 3, alert = true))
        assertEquals(NoDataAction.NONE, act(48 * H, retries = 3, alert = true))
    }

    @Test fun anOverdueRetryStillRunsAfterTheAlert() {
        // The app was not running when the retries came due: a retry is cheap and may recover the watch.
        assertEquals(NoDataAction.RETRY, act(9 * H, retries = 1, alert = true))
    }

    @Test fun stoppedByUserSilencesEverything() {
        listOf(0L, 3 * H, 4 * H, 5 * H, 8 * H, 9 * H, 100 * H).forEach { t ->
            assertEquals("t=$t", NoDataAction.NONE, act(t, stopped = true))
            assertEquals("t=$t", NoDataAction.NONE, act(t, retries = 1, stopped = true))
        }
    }

    @Test fun lateWakeUpDoesNotBurnTheWholeRetryBudgetInThreeTicks() {
        val start = 5_000_000L
        val now = start + 6 * H
        assertEquals(NoDataAction.RETRY, noDataAction(now, start, 0, false, false, null))
        // The first retry just ran; the second is overdue but must wait one interval.
        assertEquals(NoDataAction.NONE, noDataAction(now + 10 * SEC, start, 1, false, false, now))
        assertEquals(NoDataAction.NONE, noDataAction(now + 59 * MIN, start, 1, false, false, now))
        assertEquals(NoDataAction.RETRY, noDataAction(now + 1 * H, start, 1, false, false, now))
    }

    @Test fun clockGoingBackwardsCountsAsZeroElapsed() {
        assertEquals(NoDataAction.NONE, noDataAction(1_000, 9_000_000, 0, false, false))
        assertEquals(NoDataAction.NONE, noDataAction(0, Long.MAX_VALUE / 2, 3, false, false))
    }

    @Test fun lastRetryInTheFutureDoesNotBlockRetries() {
        assertEquals(NoDataAction.RETRY, noDataAction(10 * H, 6 * H, 1, false, false, lastRetryAtMs = 100 * H))
    }
}

// ── Persisted outage clock ────────────────────────────────────────────────────

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoDataTrackerTest {
    private val store = CountingStore()
    private var wall = 10_000_000L
    private var mono = 0L
    private fun tracker(s: StringStore = store) = NoDataTracker(s, { wall }, { mono })

    @Test fun noPersistedValue_baselineIsServiceStart() {
        val t = tracker()
        t.onServiceStart()
        assertEquals(wall, t.snapshot().outageStartMs)
        assertEquals(wall.toString(), store.inner.get("nd_last_valid_ms"))
        wall += 2 * H + 59 * MIN
        assertEquals(NoDataAction.NONE, t.decide())
        wall += 1 * MIN
        assertEquals(NoDataAction.RETRY, t.decide())
    }

    @Test fun processRestartOrPhoneReboot_doesNotResetTheClock() {
        val first = tracker()
        first.onServiceStart()
        val start = wall
        wall += 4 * H // phone off or process dead for 4 h
        val second = tracker() // new process: nothing in memory
        second.onServiceStart()
        assertEquals(start, second.snapshot().outageStartMs)
        assertEquals(NoDataAction.RETRY, second.decide())
    }

    @Test fun retryAlertAndStopSurviveARestart() {
        val first = tracker()
        first.onServiceStart()
        first.recordRetry(NodeCheck.CONNECTED)
        first.recordRetry(NodeCheck.NOT_CONNECTED)
        first.recordAlertPosted()
        val s = tracker().snapshot()
        assertEquals(2, s.retriesDone)
        assertTrue(s.alertPosted)
        assertEquals(NodeCheck.NOT_CONNECTED, s.lastNodeCheck)
        assertEquals(wall, s.lastRetryAtMs)
        first.onStoppedByUser()
        val after = tracker().snapshot()
        assertTrue(after.stoppedByUser)
        assertFalse("a user stop voids the alert", after.alertPosted)
    }

    @Test fun validData_writesAtMostOncePerMinute() {
        val t = tracker()
        t.onServiceStart()
        val base = store.writes["nd_last_valid_ms"] ?: 0
        repeat(120) { // 10 minutes of 5 s chunks
            wall += 5 * SEC
            mono += 5 * SEC
            t.onValidData()
        }
        val writes = (store.writes["nd_last_valid_ms"] ?: 0) - base
        assertTrue("writes=$writes", writes in 9..11)
        assertEquals(wall, t.snapshot().outageStartMs) // memory is always fresh
    }

    @Test fun validData_resetsCountersClearsStopAndReportsIt() {
        val t = tracker()
        t.onServiceStart()
        t.recordRetry(NodeCheck.CONNECTED)
        t.recordAlertPosted()
        t.onStoppedByUser()
        wall += 5 * SEC
        assertTrue(t.onValidData())
        val s = t.snapshot()
        assertEquals(0, s.retriesDone)
        assertEquals(null, s.lastRetryAtMs)
        assertFalse(s.alertPosted)
        assertFalse(s.stoppedByUser)
        assertEquals(wall, s.outageStartMs)
        assertFalse("nothing left to clear", t.onValidData())
        // and it is persisted immediately, not on the next rate-limited write
        assertEquals(0, tracker().snapshot().retriesDone)
    }

    @Test fun userStart_resetsBaselineAndCounters() {
        val t = tracker()
        t.onServiceStart()
        wall += 9 * H
        t.recordRetry(NodeCheck.NOT_CONNECTED)
        t.recordAlertPosted()
        t.onUserStart()
        val s = t.snapshot()
        assertEquals(wall, s.outageStartMs)
        assertEquals(0, s.retriesDone)
        assertFalse(s.alertPosted)
        assertEquals(NoDataAction.NONE, t.decide())
        // a restart right after keeps the new baseline
        assertEquals(wall, tracker().apply { onServiceStart() }.snapshot().outageStartMs)
    }

    @Test fun stoppedByUser_silencesUntilDataReturns() {
        val t = tracker()
        t.onServiceStart()
        t.onStoppedByUser()
        wall += 20 * H
        assertEquals(NoDataAction.NONE, t.decide())
        t.onValidData()
        assertEquals(NoDataAction.NONE, t.decide())
        wall += 8 * H
        assertEquals(NoDataAction.ALERT, t.decide()) // a later outage escalates normally
    }

    @Test fun clockGoingBackwards_neverFiresEarlyAndRecoversOnData() {
        val t = tracker()
        t.onServiceStart()
        wall -= 5 * H // user changes the clock, NTP correction...
        assertEquals(NoDataAction.NONE, t.decide())
        t.onValidData()
        assertEquals(wall, t.snapshot().outageStartMs)
        wall += 3 * H
        assertEquals(NoDataAction.RETRY, t.decide())
    }

    @Test fun corruptPersistedValues_areClamped() {
        store.inner.map["nd_last_valid_ms"] = "garbage"
        store.inner.map["nd_retries"] = "99"
        store.inner.map["nd_node_check"] = "???"
        val s = tracker().snapshot()
        assertEquals(0L, s.outageStartMs)
        assertEquals(NO_DATA_MAX_RETRIES, s.retriesDone)
        assertEquals(NodeCheck.UNKNOWN, s.lastNodeCheck)
        val t = tracker()
        t.onServiceStart() // an unusable stamp is replaced by "now"
        assertEquals(wall, t.snapshot().outageStartMs)
    }
}

// ── Retry and alert actions ───────────────────────────────────────────────────

@RunWith(RobolectricTestRunner::class) // org.json (FaultLog) and android.util.Log need the Android runtime
@Config(sdk = [34])
class NoDataEscalatorTest {
    private val store = MemoryStore()
    private var wall = 50_000_000L
    private val tracker = NoDataTracker(store, { wall }, { wall })
    private val link = FakeLink()
    private val alert = FakeAlert()
    private val log = FaultLog(MemoryStore()) { wall }
    private var relistens = 0
    private fun escalator(t: NoDataTracker = tracker) =
        NoDataEscalator(t, link, alert, log, relisten = { relistens++ }, wallClockMs = { wall })

    private val nodeA = WatchNode("a", "Galaxy Watch")
    private fun events() = log.periods().filter { it.kind.isEvent }

    @Test fun noRetryNoAlertBeforeThreeHours() {
        val e = escalator().also { it.onServiceStart() }
        repeat(((3 * H - 10 * SEC) / (10 * SEC)).toInt()) { wall += 10 * SEC; e.onTick() }
        assertEquals(0, relistens)
        assertEquals(0, link.checks)
        assertTrue(alert.posts.isEmpty())
        assertTrue(log.periods().isEmpty())
    }

    @Test fun retry_watchConnected_reregistersAndAsksEveryNodeToResume() {
        link.nodes = listOf(nodeA, WatchNode("b", "Other"))
        val e = escalator().also { it.onServiceStart() }
        wall += 3 * H
        e.onTick()
        assertEquals(1, relistens)
        assertEquals(listOf("a", "b"), link.sentTo)
        assertEquals(1, tracker.snapshot().retriesDone)
        assertEquals(NodeCheck.CONNECTED, tracker.snapshot().lastNodeCheck)
        assertEquals(listOf(FaultKind.NO_DATA_RETRY_WATCH_CONNECTED), events().map { it.kind })
        assertEquals(wall, events().single().startMs)
        assertEquals(wall, events().single().endMs)
    }

    @Test fun retry_watchNotConnected_stillReregisters_sendsNothing_recordsOutcome() {
        link.nodes = emptyList()
        val e = escalator().also { it.onServiceStart() }
        wall += 3 * H
        e.onTick()
        assertEquals(1, relistens)
        assertTrue(link.sentTo.isEmpty())
        assertEquals(NodeCheck.NOT_CONNECTED, tracker.snapshot().lastNodeCheck)
        assertEquals(listOf(FaultKind.NO_DATA_RETRY_WATCH_NOT_CONNECTED), events().map { it.kind })
    }

    @Test fun retry_failedNodeCheck_isCountedSoItCannotLoopEveryTick() {
        link.nodes = null
        val e = escalator().also { it.onServiceStart() }
        wall += 3 * H
        e.onTick()
        wall += 10 * SEC
        e.onTick()
        assertEquals(1, tracker.snapshot().retriesDone)
        assertEquals(1, relistens)
        assertEquals(NodeCheck.UNKNOWN, tracker.snapshot().lastNodeCheck)
    }

    @Test fun retry_failingSendAndFailingRelisten_neverThrow_andStillCount() {
        link.nodes = listOf(nodeA)
        link.sendThrows = true
        val e = NoDataEscalator(tracker, link, alert, log, relisten = { throw IllegalStateException("gms") }, wallClockMs = { wall })
        e.onServiceStart()
        wall += 3 * H
        e.onTick()
        assertEquals(1, tracker.snapshot().retriesDone)
    }

    @Test fun nineHoursOfTicks_threeRetriesAtThreeFourFive_oneAlertAtEight() {
        link.nodes = listOf(nodeA)
        val e = escalator().also { it.onServiceStart() }
        val start = wall
        val retryTimes = mutableListOf<Long>()
        val alertTimes = mutableListOf<Long>()
        var lastRetries = 0
        var lastAlerts = 0
        repeat((9 * H / (10 * SEC)).toInt()) {
            wall += 10 * SEC
            e.onTick()
            if (relistens != lastRetries) { retryTimes += wall - start; lastRetries = relistens }
            if (alert.posts.size != lastAlerts) { alertTimes += wall - start; lastAlerts = alert.posts.size }
        }
        assertEquals(listOf(3 * H, 4 * H, 5 * H), retryTimes)
        assertEquals(listOf(8 * H), alertTimes)
        assertEquals(
            listOf(
                FaultKind.NO_DATA_RETRY_WATCH_CONNECTED, FaultKind.NO_DATA_RETRY_WATCH_CONNECTED,
                FaultKind.NO_DATA_RETRY_WATCH_CONNECTED, FaultKind.NO_DATA_ALERT,
            ),
            events().map { it.kind },
        )
        assertEquals(3, alert.posts.single().retries)
        assertEquals(start, alert.posts.single().sinceMs)
    }

    @Test fun alert_causeComesFromAFreshNodeCheck() {
        link.nodes = listOf(nodeA)
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        assertEquals(NodeCheck.CONNECTED, alert.posts.single().cause)
    }

    @Test fun alert_whenTheCheckFails_usesTheLastKnownCause() {
        link.nodes = emptyList()
        val e = escalator().also { it.onServiceStart() }
        wall += 3 * H
        e.onTick() // retry records NOT_CONNECTED
        link.nodes = null
        wall += 5 * H
        e.onTick()
        assertEquals(NodeCheck.NOT_CONNECTED, alert.posts.single().cause)
    }

    @Test fun alert_neverCheckedAndCheckFails_causeIsUnknown() {
        link.nodes = null
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        assertEquals(NodeCheck.UNKNOWN, alert.posts.single().cause)
    }

    @Test fun alert_isPostedOncePerOutage_dataCancelsIt_aNewOutageAlertsAgain() {
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        repeat(5) { wall += 10 * SEC; e.onTick() }
        assertEquals(1, alert.posts.size)
        val cancelsBefore = alert.cancels
        e.onValidData()
        assertEquals(cancelsBefore + 1, alert.cancels)
        wall += 8 * H
        e.onTick()
        assertEquals(2, alert.posts.size)
    }

    @Test fun plainValidData_doesNotTouchTheNotification() {
        val e = escalator().also { it.onServiceStart() }
        repeat(50) { wall += 5 * SEC; e.onValidData() }
        assertEquals(0, alert.cancels)
    }

    @Test fun userStop_cancelsTheAlert_andNothingMoreHappens() {
        link.nodes = listOf(nodeA)
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        e.onStoppedByUser()
        assertEquals(1, alert.cancels)
        val events = events().size
        wall += 30 * H
        repeat(10) { wall += 10 * SEC; e.onTick() }
        assertEquals(1, alert.posts.size)
        assertEquals(events, events().size)
    }

    @Test fun userStop_beforeAnyEscalation_preventsRetriesAndAlert() {
        val e = escalator().also { it.onServiceStart() }
        e.onStoppedByUser()
        wall += 9 * H
        e.onTick()
        assertEquals(0, relistens)
        assertTrue(alert.posts.isEmpty())
        assertTrue(log.periods().isEmpty())
    }

    @Test fun lostStopMessage_failsSafe_theAlertStillFires() {
        // The watch stopped but its message never arrived: nothing set the stopped flag.
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        assertEquals(1, alert.posts.size)
    }

    @Test fun serviceRestart_putsBackAnAlertThatWasOnScreen() {
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        assertEquals(1, alert.posts.size)
        // Process or phone restarted: the notification is gone, the persisted state says it was posted.
        val restarted = escalator(NoDataTracker(store, { wall }, { wall }))
        restarted.onServiceStart()
        assertEquals(2, alert.posts.size)
        assertEquals(alert.posts[0], alert.posts[1])
        restarted.onTick()
        assertEquals("restoring is not a second alert", 2, alert.posts.size)
    }

    @Test fun serviceRestart_afterAUserStop_restoresNothing() {
        val e = escalator().also { it.onServiceStart() }
        wall += 8 * H
        e.onTick()
        e.onStoppedByUser()
        escalator(NoDataTracker(store, { wall }, { wall })).onServiceStart()
        assertEquals(1, alert.posts.size)
    }

    @Test fun retriesAndAlert_doNotChangeTheMorningSummary() {
        link.nodes = listOf(nodeA)
        val e = escalator().also { it.onServiceStart() }
        val start = wall
        repeat((9 * H / (10 * SEC)).toInt()) { wall += 10 * SEC; e.onTick() }
        val outage = FaultPeriod(FaultKind.NO_WATCH_DATA, start + 30 * SEC, null)
        val withEvents = buildSummary(log.periods() + outage, start, wall)
        val without = buildSummary(listOf(outage), start, wall)
        assertEquals(4, events().size)
        assertEquals(without, withEvents)
        assertEquals(1, withEvents.interruptions)
    }
}
