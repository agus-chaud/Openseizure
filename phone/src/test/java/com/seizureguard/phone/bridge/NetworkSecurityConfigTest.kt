package com.seizureguard.phone.bridge

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Android blocks cleartext HTTP by default (targetSdk >= 28), and OSD only speaks plain HTTP on
 * 127.0.0.1:8080. JVM/Robolectric loopback tests never apply that platform policy, so the block was
 * only found on a real phone (2026-10-06). These tests pin the manifest + config that allow
 * cleartext to the loopback address and nothing else.
 */
class NetworkSecurityConfigTest {
    // Gradle runs unit tests with the module directory (phone/) as the working directory.
    private val manifest = File("src/main/AndroidManifest.xml")
    private val config = File("src/main/res/xml/network_security_config.xml")

    private fun parse(f: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(f).documentElement

    @Test
    fun manifest_pointsTheApplicationAtTheNetworkSecurityConfig() {
        val app = parse(manifest).getElementsByTagName("application").item(0) as Element
        assertEquals(
            "@xml/network_security_config",
            app.getAttributeNS("http://schemas.android.com/apk/res/android", "networkSecurityConfig"),
        )
    }

    @Test
    fun config_permitsCleartextOnlyForLoopback_andCoversTheOsdHost() {
        val root = parse(config)
        assertEquals("no base-config may widen cleartext globally", 0, root.getElementsByTagName("base-config").length)

        val domainConfigs = root.getElementsByTagName("domain-config")
        assertEquals(1, domainConfigs.length)
        val dc = domainConfigs.item(0) as Element
        assertEquals("true", dc.getAttribute("cleartextTrafficPermitted"))

        val domains = dc.getElementsByTagName("domain")
        val names = (0 until domains.length).map { (domains.item(it) as Element).textContent.trim() }.toSet()
        assertEquals(setOf("127.0.0.1", "localhost"), names)
        assertTrue("the forwarder's host must be covered", OsdHttpForwarder.OSD_HOST in names)
        (0 until domains.length).forEach {
            assertEquals("false", (domains.item(it) as Element).getAttribute("includeSubdomains"))
        }
    }
}
