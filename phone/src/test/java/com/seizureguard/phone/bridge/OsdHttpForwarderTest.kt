package com.seizureguard.phone.bridge

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsdHttpForwarderTest {
    private fun serve(response: String?, hold: Boolean = false, block: (Int) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        pool.submit {
            server.accept().use { s ->
                val input = s.getInputStream()
                val buf = ByteArray(8192)
                input.read(buf)
                if (hold) Thread.sleep(2000)
                else if (response != null) s.getOutputStream().write(
                    "HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n$response".toByteArray()
                )
            }
        }
        try { block(server.localPort) } finally { server.close(); pool.shutdownNow() }
    }

    @Test fun targetIsLoopbackOnly() {
        assertEquals("127.0.0.1", OsdHttpForwarder.OSD_HOST)
        assertEquals(8080, OsdHttpForwarder.OSD_PORT)
        assertEquals(4000, OsdHttpForwarder.TIMEOUT_MS)
    }

    @Test fun post_classifiesOk() = serve("OK") { port ->
        assertEquals(PostOutcome.OK, OsdHttpForwarder(port, 1000).post("/data", "dataObj=x".toByteArray()))
    }

    @Test fun post_classifiesSendSettings() = serve("sendSettings") { port ->
        assertEquals(PostOutcome.SEND_SETTINGS, OsdHttpForwarder(port, 1000).post("/data", "dataObj=x".toByteArray()))
    }

    @Test fun post_classifiesWrongDatasource() = serve("you should not see this message") { port ->
        assertEquals(PostOutcome.WRONG_DATASOURCE, OsdHttpForwarder(port, 1000).post("/data", "dataObj=x".toByteArray()))
    }

    @Test fun post_connectionRefusedIsUnreachable() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(PostOutcome.UNREACHABLE, OsdHttpForwarder(port, 500).post("/data", ByteArray(1)))
    }

    @Test fun post_readTimeoutIsUnreachable() = serve(null, hold = true) { port ->
        val start = System.currentTimeMillis()
        assertEquals(PostOutcome.UNREACHABLE, OsdHttpForwarder(port, 300).post("/data", ByteArray(1)))
        assertTrue(System.currentTimeMillis() - start < 1500)
    }

    @Test fun getData_returnsBodyOrNull() {
        serve("{\"alarmState\":2}") { port -> assertEquals("{\"alarmState\":2}", OsdHttpForwarder(port, 1000).getData()) }
        val dead = ServerSocket(0).use { it.localPort }
        assertNull(OsdHttpForwarder(dead, 500).getData())
    }
}
