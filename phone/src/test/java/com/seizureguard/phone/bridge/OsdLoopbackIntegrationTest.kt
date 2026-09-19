package com.seizureguard.phone.bridge

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Stub server that captures the raw request so we assert the exact bytes NanoHTTPD would parse.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OsdLoopbackIntegrationTest {
    private class Captured(val requestLine: String, val headers: Map<String, String>, val body: ByteArray)

    private fun withStub(responseBody: String, block: (port: Int, result: Future<Captured>) -> Unit) {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        val result = pool.submit<Captured> {
            server.accept().use { s ->
                val input = s.getInputStream()
                val head = ByteArrayOutputStream()
                while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) head.write(input.read())
                val lines = head.toString(Charsets.ISO_8859_1.name()).trim().split("\r\n")
                val headers = lines.drop(1).associate {
                    it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim()
                }
                val body = ByteArray(headers.getValue("content-length").toInt())
                var off = 0
                while (off < body.size) off += input.read(body, off, body.size - off)
                val out = "HTTP/1.1 200 OK\r\nContent-Length: ${responseBody.length}\r\nConnection: close\r\n\r\n$responseBody"
                s.getOutputStream().write(out.toByteArray())
                Captured(lines[0], headers, body)
            }
        }
        try { block(server.localPort, result) } finally { server.close(); pool.shutdownNow() }
    }

    private fun post(port: Int, path: String, body: ByteArray): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 4000
        c.readTimeout = 4000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Connection", "close")
        c.outputStream.use { it.write(body) }
        return c.responseCode to c.inputStream.bufferedReader().readText()
    }

    @Test fun rawData_wireBytesMatchOsdContract() {
        val json = OsdPayloadCodec.rawDataJson(DoubleArray(125) { it * 1.25 })
        withStub("OK") { port, result ->
            val (code, resp) = post(port, "/data", OsdPayloadCodec.formBody(json))
            val cap = result.get()
            assertEquals("POST /data HTTP/1.1", cap.requestLine)
            assertEquals("application/x-www-form-urlencoded", cap.headers["content-type"])
            val body = String(cap.body, Charsets.UTF_8)
            assertTrue(body.startsWith("dataObj="))
            assertFalse(body.contains('\r') || body.contains('\n'))
            assertEquals(json, URLDecoder.decode(body.removePrefix("dataObj="), "UTF-8"))
            assertEquals(PostOutcome.OK, classify(code, resp))
        }
    }

    @Test fun settings_postedToSettingsPath() {
        val json = OsdPayloadCodec.settingsJson(battery = 55)
        withStub("OK") { port, result ->
            post(port, "/settings", OsdPayloadCodec.formBody(json))
            val cap = result.get()
            assertEquals("POST /settings HTTP/1.1", cap.requestLine)
            assertEquals(json, URLDecoder.decode(String(cap.body).removePrefix("dataObj="), "UTF-8"))
        }
    }

    @Test fun sendSettingsResponse_isClassified() {
        withStub("sendSettings") { port, _ ->
            val (code, resp) = post(port, "/data", OsdPayloadCodec.formBody(OsdPayloadCodec.rawDataJson(doubleArrayOf(1.0))))
            assertEquals(PostOutcome.SEND_SETTINGS, classify(code, resp))
        }
    }
}
