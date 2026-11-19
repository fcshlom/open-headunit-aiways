package com.andrerinas.openheadunit.aiways

import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ProjectionDeepLink
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiwaysWirelessSetupTest {

    @Test
    fun `a Bluetooth address is normalized from any common spelling`() {
        assertEquals("00:11:22:AA:BB:CC", AiwaysWirelessSetup.normalizeMac("001122aabbcc"))
        assertEquals("00:11:22:AA:BB:CC", AiwaysWirelessSetup.normalizeMac("00-11-22-aa-bb-cc"))
        assertEquals("00:11:22:AA:BB:CC", AiwaysWirelessSetup.normalizeMac(" 00:11:22:AA:BB:CC "))
    }

    @Test
    fun `anything that is not twelve hex digits is no address`() {
        assertNull(AiwaysWirelessSetup.normalizeMac(null))
        assertNull(AiwaysWirelessSetup.normalizeMac(""))
        assertNull(AiwaysWirelessSetup.normalizeMac("00:11:22:AA:BB"))
        assertNull(AiwaysWirelessSetup.normalizeMac("00:11:22:AA:BB:CC:DD"))
        assertNull(AiwaysWirelessSetup.normalizeMac("00:11:22:AA:BB:CG"))
    }

    @Test
    fun `the daemon config is read the way tbox_daemon reads it`() {
        val conf = AiwaysWirelessSetup.parseConf(
            "# the access point\n" +
                "  wifi_ssid   = MyAiways-X2TMW\r\n" +
                "; old key\n" +
                "wifi_key=  pass word#1  \n" +
                "no equals sign here\n" +
                "api_port = 9000\n" +
                "wifi_ssid = MyAiways-NEW\n"
        )
        assertEquals("MyAiways-NEW", conf["wifi_ssid"])
        assertEquals("pass word#1", conf["wifi_key"])
        assertEquals("9000", conf["api_port"])
        assertEquals(3, conf.size)
    }

    @Test
    fun `the strongest access point with the exact name gives the BSSID`() {
        val scan = listOf(
            AiwaysWirelessSetup.Ap("MyAiways-X2TMW", "aa:bb:cc:00:00:01", -70),
            AiwaysWirelessSetup.Ap("\"MyAiways-X2TMW\"", "aa:bb:cc:00:00:02", -40),
            AiwaysWirelessSetup.Ap("MyAiways-X2TMW-guest", "aa:bb:cc:00:00:03", -10),
            AiwaysWirelessSetup.Ap("Phone", "aa:bb:cc:00:00:04", -20),
        )
        assertEquals("AA:BB:CC:00:00:02", AiwaysWirelessSetup.strongestBssid(scan, "MyAiways-X2TMW"))
        assertNull(AiwaysWirelessSetup.strongestBssid(scan, "Elsewhere"))
        assertNull(AiwaysWirelessSetup.strongestBssid(scan, null))
    }

    @Test
    fun `without the config the T-Box network is found by its name prefix`() {
        val scan = listOf(
            AiwaysWirelessSetup.Ap("Phone", "aa:bb:cc:00:00:04", -20),
            AiwaysWirelessSetup.Ap("MyAiways-X2TMW", "aa:bb:cc:00:00:01", -60),
        )
        assertEquals("MyAiways-X2TMW", AiwaysWirelessSetup.tboxSsidFromScan(scan))
        assertNull(AiwaysWirelessSetup.tboxSsidFromScan(listOf(AiwaysWirelessSetup.Ap("Phone", "x", 0))))
    }

    @Test
    fun `only an address on the T-Box bridge other than the T-Box counts`() {
        assertTrue(AiwaysWirelessSetup.isOnTboxBridge("192.168.1.30"))
        assertFalse(AiwaysWirelessSetup.isOnTboxBridge("192.168.1.1"))
        assertFalse(AiwaysWirelessSetup.isOnTboxBridge("10.24.39.41"))
        assertFalse(AiwaysWirelessSetup.isOnTboxBridge(null))
    }

    @Test
    fun `the FTP passive reply gives the data port`() {
        assertEquals(195 * 256 + 80, AiwaysWirelessSetup.pasvPort("227 Entering Passive Mode (192,168,1,1,195,80)."))
        assertEquals(-1, AiwaysWirelessSetup.pasvPort("500 no"))
        assertEquals(-1, AiwaysWirelessSetup.pasvPort("227 Entering Passive Mode (192,168,1,1,195)"))
        assertEquals(-1, AiwaysWirelessSetup.pasvPort("227 Entering Passive Mode (192,168,1,1,300,80)"))
    }

    @Test
    fun `the log line never carries the key`() {
        val line = AiwaysWirelessSetup.describe("MyAiways-X2TMW", "AA:BB:CC:00:00:01", "192.168.1.30", 5299, "00:11:22:AA:BB:CC")
        assertEquals("ssid=MyAiways-X2TMW bssid=AA:BB:CC:00:00:01 endpoint=192.168.1.30:5299 bluetooth=00:11:22:AA:BB:CC", line)
    }

    @Test
    fun `the setup link names the T-Box network, this unit on the bridge and Adayo's module`() {
        val result = ProjectionDeepLink.build(
            ssid = "MyAiways-X2TMW",
            passkey = "secret123",
            bssid = "AA:BB:CC:00:00:01",
            wppTcpIp = "192.168.1.30",
            wppTcpPort = AiwaysWirelessSetup.WPP_PORT,
            bluetoothMac = "00:11:22:aa:bb:cc",
        )
        val url = (result as ProjectionDeepLink.Result.Ok).url
        assertTrue(url.startsWith("https://androidauto.com/projection/setup?data="))
        val data = Wireless.ProjectionDeepLinkData.parseFrom(Base64.getUrlDecoder().decode(url.substringAfter("data=")))
        assertEquals("MyAiways-X2TMW", data.ssid)
        assertEquals("secret123", data.passkey)
        assertEquals("aa:bb:cc:00:00:01", data.bssid)
        assertEquals("192.168.1.30", data.wppTcpIp)
        assertEquals(5299, data.wppTcpPort)
        assertEquals("00:11:22:AA:BB:CC", data.bluetoothMac)
    }
}
