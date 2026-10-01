package com.seizureguard.phone.bridge

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.wearable.MessageEvent
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

/**
 * Batch 8b integration: real watch `/osd/settings` messages go through [OsdBridgeService.onMessage] and are
 * evaluated for contract-version compatibility, while forwarding to OSD stays exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchVersionServiceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = MemoryStore()
    private var now = 1_000L
    private val log = FaultLog(store) { now }
    private val recorder = VersionMismatchRecorder(log)
    private val seen = mutableListOf<WatchSettings>()

    private val recordingObserver = object : BridgeObserver {
        override fun onAlarmDataPolled(body: String?) = Unit
        override fun onHealthTick(fault: BridgeFault) = Unit
        override fun onWatchSettings(settings: WatchSettings) { seen += settings; recorder.onWatchSettings(settings) }
    }

    private var controller: ServiceController<OsdBridgeService>? = null
    private var stub: LoopbackOsd? = null

    @After fun tearDown() {
        controller?.destroy()
        stub?.close()
    }

    private fun message(path: String, json: String) = object : MessageEvent {
        override fun getRequestId() = 0
        override fun getPath() = path
        override fun getData() = json.toByteArray(Charsets.UTF_8)
        override fun getSourceNodeId() = "watch"
    }

    private fun settingsMsg(version: String?) =
        message(OsdBridgeService.PATH_SETTINGS, """{"battery":87,"sample_freq":25${version?.let { ""","contract_version":$it""" } ?: ""}}""")

    private fun accelMsg() = message(
        OsdBridgeService.PATH_ACCEL, """{"samples":[${(0 until 125).joinToString(",") { (it * 7 % 31 + 1).toString() }}]}""",
    )

    private fun openVersion() = log.periods().filter { it.kind == FaultKind.VERSION_MISMATCH && it.endMs == null }

    private fun idleService(): OsdBridgeService =
        Robolectric.buildService(OsdBridgeService::class.java).create().also { controller = it }.get().also { it.observer = recordingObserver }

    // ── evaluation on every real settings message ─────────────────────────────

    @Test fun matchingVersion_opensNoPeriod() {
        idleService().onMessage(settingsMsg("1"))
        assertEquals(1, seen.size)
        assertEquals(emptyList<FaultPeriod>(), log.periods())
    }

    @Test fun mismatchOrMissing_opensPeriod_thenMatchingMessageCloses() {
        val service = idleService()
        listOf("2", null, "0", "99").forEach { v ->
            service.onMessage(settingsMsg(v))
            assertEquals("watch=$v", 1, openVersion().size)
            service.onMessage(settingsMsg("1"))
            assertEquals("watch=$v", 0, openVersion().size)
        }
        assertEquals(4, log.periods().size)
    }

    @Test fun invalidVersionValue_countsAsMissing() {
        idleService().onMessage(settingsMsg("-3"))
        assertEquals(1, openVersion().size)
    }

    @Test fun malformedSettings_areDroppedAndNeverEvaluated() {
        val service = idleService()
        service.onMessage(message(OsdBridgeService.PATH_SETTINGS, """{"battery":500,"sample_freq":25,"contract_version":2}"""))
        service.onMessage(message(OsdBridgeService.PATH_SETTINGS, "not json"))
        assertTrue(seen.isEmpty())
        assertEquals(emptyList<FaultPeriod>(), log.periods())
    }

    @Test fun accelMessages_neverTriggerTheVersionCheck() {
        idleService().onMessage(accelMsg())
        assertTrue(seen.isEmpty())
    }

    @Test fun coexistsWithAnotherFaultPeriod_viaTheServicePath() {
        val service = idleService()
        log.onFault(BridgeFault.OSD_UNREACHABLE)
        service.onMessage(settingsMsg("2"))
        service.onMessage(settingsMsg("2"))
        log.onFault(BridgeFault.NONE)
        assertEquals(1, openVersion().size)
        service.onMessage(settingsMsg("1"))
        assertEquals(
            listOf(FaultKind.OSD_UNREACHABLE, FaultKind.VERSION_MISMATCH),
            log.periods().map { it.kind },
        )
        assertTrue(log.periods().all { it.endMs != null })
    }

    // ── the default observer records into the process-wide fault log ──────────

    @Test fun defaultObserver_recordsOpenAndClose_inTheProcessFaultLog() {
        val service = Robolectric.buildService(OsdBridgeService::class.java).create().also { controller = it }.get()
        val processLog = BridgeHistory.of(app).first
        fun open() = processLog.periods().count { it.kind == FaultKind.VERSION_MISMATCH && it.endMs == null }
        service.onMessage(settingsMsg("2"))
        assertEquals(1, open())
        service.onMessage(settingsMsg("1"))
        assertEquals(0, open())
    }

    // ── forwarding never depends on the version result ────────────────────────

    @Test fun settingsAreQueuedForOsd_beforeAndRegardlessOfTheVersionCheck() {
        val service = idleService()
        service.observer = object : BridgeObserver {
            override fun onAlarmDataPolled(body: String?) = Unit
            override fun onHealthTick(fault: BridgeFault) = Unit
            override fun onWatchSettings(settings: WatchSettings) = throw IllegalStateException("version check bug")
        }
        service.onMessage(settingsMsg("2"))
        assertEquals(WatchSettings(87, 25, 2), service.settingsSlot.take())
    }

    @Test fun forwardedSettingsBody_isIdenticalForEveryVersion() {
        val expected = OsdPayloadCodec.formBody(OsdPayloadCodec.settingsJson(87, 25)).toString(Charsets.UTF_8)
        val bodies = listOf("1", "2", "0", null).map { v ->
            val osd = LoopbackOsd().also { stub = it }
            val service = startedService(osd)
            service.onMessage(settingsMsg(v))
            val req = osd.awaitPost("/settings")
            controller!!.destroy()
            controller = null
            osd.close()
            req
        }
        bodies.forEach { assertNotNull(it) }
        bodies.forEach { assertEquals(expected, it) }
        // The wire body carries no version field at all.
        assertTrue(!URLDecoder.decode(expected, "UTF-8").contains("contract_version"))
    }

    @Test fun handshakeDefaultSettings_neverOpenAVersionPeriod() {
        val osd = LoopbackOsd(dataResponse = "sendSettings").also { stub = it }
        val service = startedService(osd)
        service.onMessage(accelMsg()) // OSD answers sendSettings -> the bridge posts handshake defaults itself
        val handshake = osd.awaitPost("/settings")
        assertNotNull(handshake)
        assertEquals(
            OsdPayloadCodec.formBody(OsdPayloadCodec.settingsJson(DEFAULT_HANDSHAKE_BATTERY, WatchMessageParser.SAMPLE_FREQ_HZ))
                .toString(Charsets.UTF_8),
            handshake,
        )
        assertTrue("handshake must not be evaluated", seen.isEmpty())
        assertEquals(emptyList<FaultPeriod>(), log.periods())
        assertNull(openVersion().firstOrNull())
    }

    private fun startedService(osd: LoopbackOsd): OsdBridgeService {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        val c = Robolectric.buildService(OsdBridgeService::class.java, Intent(app, OsdBridgeService::class.java)).create()
        controller = c
        val service = c.get()
        service.forwarder = OsdHttpForwarder(osd.port, 2_000)
        service.observer = recordingObserver
        c.startCommand(0, 1)
        return service
    }

    /** Minimal loopback OSD: records every POST body (decoded as sent) and answers /data with [dataResponse]. */
    private class LoopbackOsd(private val dataResponse: String = "OK") {
        private val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val posts = CopyOnWriteArrayList<Pair<String, String>>()
        val port: Int get() = server.localPort

        private val thread = Thread {
            try {
                while (true) server.accept().use { s ->
                    val input = s.getInputStream()
                    val head = ByteArrayOutputStream()
                    while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                        val b = input.read()
                        if (b < 0) return@use
                        head.write(b)
                    }
                    val lines = head.toString(Charsets.ISO_8859_1.name()).trim().split("\r\n")
                    val length = lines.drop(1).firstOrNull { it.lowercase().startsWith("content-length:") }
                        ?.substringAfter(":")?.trim()?.toInt() ?: 0
                    val body = ByteArray(length)
                    var off = 0
                    while (off < length) off += input.read(body, off, length - off)
                    val (method, path) = lines[0].split(" ").let { it[0] to it[1] }
                    val response = if (method == "POST" && path == "/data") dataResponse else "OK"
                    if (method == "POST") posts += path to String(body, Charsets.UTF_8)
                    s.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray(),
                    )
                }
            } catch (_: Exception) {
                // closed by the test
            }
        }.also { it.isDaemon = true; it.start() }

        /** Raw form body of the first POST to [path], or null after a 10 s timeout. */
        fun awaitPost(path: String): String? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                posts.firstOrNull { it.first == path }?.let { return it.second }
                Thread.sleep(20)
            }
            return null
        }

        fun close() = server.close()
    }
}
