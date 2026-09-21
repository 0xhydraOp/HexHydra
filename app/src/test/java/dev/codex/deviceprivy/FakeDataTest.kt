package dev.codex.deviceprivy

import org.junit.Test
import org.junit.Assert.*

class FakeDataTest {
    @Test
    fun generatedImeisAreValid() {
        for (i in 1..100) {
            val imei = FakeData.generateValidIMEI()
            assertEquals(15, imei.length)
            assertTrue(isValidLuhn(imei))
            // TAC is the leading 8 digits; a real TAC may start with "0".
            assertTrue(FakeData.generateValidIMEI("Samsung").substring(0, 8) in IdentityData.TAC_POOLS.getValue("samsung"))
        }
        val onePlus = FakeData.generateValidIMEI("OnePlus").substring(0, 8)
        assertTrue(onePlus in IdentityData.TAC_POOLS.getValue("oneplus"))
        val nothing = FakeData.generateValidIMEI("Nothing").substring(0, 8)
        assertTrue(nothing in IdentityData.TAC_POOLS.getValue("nothing"))
    }

    private fun isValidLuhn(imei: String): Boolean {
        var sum = 0
        for (i in imei.indices.reversed()) {
            var n = imei[i] - '0'
            if ((imei.length - 1 - i) % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return sum % 10 == 0
    }

    @Test
    fun generateAllReturnsExpectedKeys() {
        val data = FakeData.generateAll()
        val expectedKeys = listOf(
            "imei", "meid", "imsi", "gsf_id", "hardware_id", "mac_address", 
            "mac_bssid", "mac_ssid", "bluetooth_mac", "android_id",
            "sim_serial", "sim_sub_id", "mobile_no", "media_drm_id",
            "sim_operator", "network_operator", "ip_address", "manufacturer",
            "country_iso", "locale", "timezone", "model", "brand", "device", "product", "build_id",
            "board", "device_name", "android_version", "aaid", "fingerprint", "latitude",
            "longitude", "screen_width", "screen_height", "screen_density",
            "user_agent", "gl_renderer", "gl_vendor", "battery_level"
        )
        for (key in expectedKeys) {
            assertTrue("Missing key: $key", data.containsKey(key))
            assertFalse("Empty value for key: $key", data[key].isNullOrEmpty())
        }
    }

    @Test
    fun generatedValuesUseExpectedFormats() {
        repeat(100) {
            val data = FakeData.generateAll()

            assertTrue(data.getValue("imei").matches(Regex("\\d{15}")))
            assertTrue(data.getValue("imsi").matches(Regex("\\d{15}")))
            assertTrue(
                "IMSI should start with MCC+MNC",
                data.getValue("imsi").startsWith(data.getValue("network_operator"))
            )
            assertFalse(
                "IMSI must differ from SIM serial",
                data.getValue("imsi") == data.getValue("sim_serial")
            )
            assertTrue(data.getValue("meid").matches(Regex("\\d{14}")))
            assertTrue(data.getValue("gsf_id").matches(Regex("[0-9a-f]{16}")))
            assertTrue(data.getValue("hardware_id").matches(Regex("[0-9a-f]{16}")))
            assertTrue(data.getValue("android_id").matches(Regex("[0-9a-f]{16}")))
            assertTrue(data.getValue("sim_serial").matches(Regex("\\d{20}")))
            assertTrue(data.getValue("network_operator").matches(Regex("\\d{5,6}")))
            assertTrue(data.getValue("country_iso").matches(Regex("[a-z]{2}")))
            assertTrue(data.getValue("locale").matches(Regex("[a-z]{2}-[A-Z]{2}")))
            assertTrue(data.getValue("timezone").contains("/"))
            assertTrue(data.getValue("build_id").matches(Regex("[A-Z0-9]{6}")))
            assertTrue(data.getValue("board").matches(Regex("[a-z0-9._-]+")))
            assertTrue(data.getValue("aaid").isUuid())
            assertTrue(data.getValue("media_drm_id").isUuid())

            assertValidMac(data.getValue("mac_address"))
            assertValidMac(data.getValue("mac_bssid"))
            assertValidMac(data.getValue("bluetooth_mac"))

            val ipParts = data.getValue("ip_address").split(".").map { it.toInt() }
            assertEquals(listOf(192, 168), ipParts.take(2))
            assertTrue(ipParts[2] in 0..255)
            assertTrue(ipParts[3] in 1..254)

            assertTrue(data.getValue("latitude").toDouble() in -90.0..90.0)
            assertTrue(data.getValue("longitude").toDouble() in -180.0..180.0)
            val lat = data.getValue("latitude").toDouble()
            val lon = data.getValue("longitude").toDouble()
            when (data.getValue("country_iso")) {
                "us" -> { assertTrue(lat in 25.0..50.0); assertTrue(lon in -125.0..-70.0) }
                "in" -> { assertTrue(lat in 8.0..35.0); assertTrue(lon in 68.0..90.0) }
                "gb" -> { assertTrue(lat in 50.0..59.0); assertTrue(lon in -5.0..2.0) }
                "fr" -> { assertTrue(lat in 42.0..51.0); assertTrue(lon in -2.0..8.0) }
                "au" -> { assertTrue(lat in -40.0..-12.0); assertTrue(lon in 113.0..153.0) }
            }
            assertTrue(data.getValue("screen_width").toInt() > 0)
            assertTrue(data.getValue("screen_height").toInt() > 0)
            assertTrue(data.getValue("screen_density").toInt() > 0)
            assertTrue(data.getValue("battery_level").toInt() in 1..100)
        }
    }

    @Test
    fun generatedMacsUseVerifiedOuiPools() {
        for (i in 1..100) {
            val samsung = FakeData.randomMac("Samsung")
            assertTrue(
                "OUI not in Samsung pool: $samsung",
                samsung.substring(0, 8).replace(":", "") in IdentityData.OUI_POOLS.getValue("samsung")
            )
            assertValidMac(samsung)
            val google = FakeData.randomMac("Google")
            assertTrue(
                "OUI not in Google pool: $google",
                google.substring(0, 8).replace(":", "") in IdentityData.OUI_POOLS.getValue("google")
            )
            assertValidMac(google)
            val default = FakeData.randomMac()
            assertTrue(
                "OUI outside all pools: $default",
                default.substring(0, 8).replace(":", "") in IdentityData.OUI_POOLS.values.flatten()
            )
        }
    }

    @Test
    fun generatedProfilesKeepCarrierAndLocaleConsistent() {
        repeat(100) {
            val data = FakeData.generateAll()
            when (data.getValue("country_iso")) {
                "us" -> {
                    assertTrue(data.getValue("network_operator") in setOf("311480", "310260", "310410"))
                    assertEquals("en-US", data.getValue("locale"))
                    assertTrue(data.getValue("mobile_no").matches(Regex("\\+[1-9]\\d{10}")))
                    assertEquals(12, data.getValue("mobile_no").length)
                    assertTrue(data.getValue("timezone").startsWith("America/"))
                }
                "in" -> {
                    assertTrue(data.getValue("network_operator") in setOf("405840", "40410"))
                    assertEquals("en-IN", data.getValue("locale"))
                    assertTrue(data.getValue("mobile_no").matches(Regex("\\+91[1-9]\\d{9}")))
                    assertEquals(13, data.getValue("mobile_no").length)
                    assertEquals("Asia/Kolkata", data.getValue("timezone"))
                }
                "gb" -> {
                    assertEquals("23415", data.getValue("network_operator"))
                    assertEquals("en-GB", data.getValue("locale"))
                    assertTrue(data.getValue("mobile_no").matches(Regex("\\+44[1-9]\\d{9}")))
                    assertEquals(13, data.getValue("mobile_no").length)
                    assertEquals("Europe/London", data.getValue("timezone"))
                }
                "fr" -> {
                    assertEquals("20801", data.getValue("network_operator"))
                    assertEquals("fr-FR", data.getValue("locale"))
                    assertTrue(data.getValue("mobile_no").matches(Regex("\\+33[1-9]\\d{8}")))
                    assertEquals(12, data.getValue("mobile_no").length)
                    assertEquals("Europe/Paris", data.getValue("timezone"))
                }
                "au" -> {
                    assertEquals("50501", data.getValue("network_operator"))
                    assertEquals("en-AU", data.getValue("locale"))
                    assertTrue(data.getValue("mobile_no").matches(Regex("\\+61[1-9]\\d{8}")))
                    assertEquals(12, data.getValue("mobile_no").length)
                    assertEquals("Australia/Sydney", data.getValue("timezone"))
                }
                else -> fail("Unexpected country ISO: ${data.getValue("country_iso")}")
            }
        }
    }

    @Test
    fun generatedProfilesKeepBuildAndUserAgentConsistent() {
        repeat(100) {
            val data = FakeData.generateAll()
            assertEquals(data.getValue("manufacturer"), data.getValue("brand"))
            assertEquals(data.getValue("model"), data.getValue("device_name"))
            assertTrue(data.getValue("fingerprint").startsWith("${data.getValue("manufacturer")}/${data.getValue("product")}/${data.getValue("device")}:"))
            assertTrue(data.getValue("fingerprint").contains(":${data.getValue("android_version")}/${data.getValue("build_id")}/"))
            assertTrue(data.getValue("user_agent").contains("Android ${data.getValue("android_version")}"))
            assertTrue(data.getValue("user_agent").contains(data.getValue("model")))
            assertTrue(data.getValue("user_agent").contains("Build/${data.getValue("build_id")}"))
        }
    }

    @Test
    fun generatedFingerprintsHaveAndroidBuildShape() {
        repeat(100) {
            val fingerprint = FakeData.generateAll().getValue("fingerprint")
            assertTrue(fingerprint, fingerprint.matches(Regex("[^/]+/[^/]+/[^:]+:\\d{2}/[A-Z0-9]{6}/\\d{7}:user/release-keys")))
        }
    }

    @Test
    fun stealthCheck() {
        val data = FakeData.generateAll()
        for ((key, value) in data) {
            assertFalse("Value for $key contains 'fake': $value", value.contains("fake", ignoreCase = true))
            assertFalse("Value for $key contains 'faker': $value", value.contains("faker", ignoreCase = true))
        }
    }

    private fun assertValidMac(value: String) {
        assertTrue(value, value.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        val firstOctet = value.substring(0, 2).toInt(16)
        assertEquals("MAC should be unicast", 0, firstOctet and 1)
        assertEquals("MAC should be globally administered", 0, firstOctet and 2)
    }

    private fun String.isUuid(): Boolean {
        return matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    }
}
