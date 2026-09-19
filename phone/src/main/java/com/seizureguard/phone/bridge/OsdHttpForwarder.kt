package com.seizureguard.phone.bridge

import java.net.HttpURLConnection
import java.net.URL

// Blocking: callers must dispatch on Dispatchers.IO. No retry/queue/batching by design.
class OsdHttpForwarder internal constructor(private val port: Int, private val timeoutMs: Int) {
    constructor() : this(OSD_PORT, TIMEOUT_MS)

    /** POSTs a form body to OSD ("/data" or "/settings"); never throws. */
    fun post(path: String, formBody: ByteArray): PostOutcome = try {
        val c = open(path)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(formBody) }
            classify(c.responseCode, readBody(c))
        } finally { c.disconnect() }
    } catch (e: Exception) { PostOutcome.UNREACHABLE }

    /** GET /data raw body for the alarm-state poll, or null on any failure. */
    fun getData(): String? = try {
        val c = open("/data")
        try {
            c.requestMethod = "GET"
            if (c.responseCode == 200) readBody(c) else null
        } finally { c.disconnect() }
    } catch (e: Exception) { null }

    private fun open(path: String): HttpURLConnection =
        (URL("http://$OSD_HOST:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Connection", "close")
        }

    private fun readBody(c: HttpURLConnection): String = c.inputStream.bufferedReader().use { it.readText() }

    companion object {
        const val OSD_HOST = "127.0.0.1" // loopback only: OSD's server is unauthenticated and LAN-bound
        const val OSD_PORT = 8080
        const val TIMEOUT_MS = 4000
    }
}
