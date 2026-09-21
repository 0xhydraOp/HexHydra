package dev.hexhydra

import org.junit.Test
import org.junit.Assert.*

class ProfileCoherenceTest {

    @Test
    fun generatedProfilesAreAlwaysCoherent() {
        repeat(2000) {
            val profile = FakeData.generateAll()
            val issues = ProfileCoherence.issues(profile)
            assertTrue("Expected coherent profile, found: $issues", issues.isEmpty())
        }
    }

    @Test
    fun brandMismatchIsReported() {
        val p = base().toMutableMap()
        p["brand"] = "Apple"
        assertReported(p, "brand")
    }

    @Test
    fun unrelatedDeviceNameIsReported() {
        val p = base().toMutableMap()
        p["device_name"] = "Unrelated phone"
        assertReported(p, "device_name")
    }

    @Test
    fun boardMustBeLowercasedCodename() {
        val p = base().toMutableMap()
        p["board"] = "KOMODO"
        assertReported(p, "board")
    }

    @Test
    fun fingerprintPrefixMismatchIsReported() {
        val p = base().toMutableMap()
        p["fingerprint"] = "Acme/alien/tokay:15/AAA111/1234567:user/release-keys"
        assertReported(p, "fingerprint")
    }

    @Test
    fun malformedFingerprintIsReported() {
        val p = base().toMutableMap()
        p["fingerprint"] = "google/komodo:15/AAA111/1234567"
        assertReported(p, "fingerprint")
    }

    @Test
    fun nonVerifiedTacIsReported() {
        val p = base().toMutableMap()
        // Google has no TAC starting 99000000 → must be rejected.
        p["imei"] = "99000000" + "123456" + luhnDigit("99000000" + "123456")
        assertReported(p, "imei")
    }

    @Test
    fun nonVerifiedMacOuiIsReported() {
        val p = base().toMutableMap()
        p["mac_address"] = "000000:AA:BB:CC"
        assertReported(p, "mac")
    }

    @Test
    fun wrongBatteryScaleIsReported() {
        val p = base().toMutableMap()
        p["manufacturer"] = "Samsung"
        p["battery_scale"] = "100"
        assertReported(p, "battery_scale")
    }

    @Test
    fun mccCountryConflictIsReported() {
        val p = base().toMutableMap()
        p["country_iso"] = "in"
        p["network_operator"] = "310260" // MCC 310 = us, not in
        p["locale"] = "en-IN"
        p["timezone"] = "Asia/Kolkata"
        p["mobile_no"] = "+911234567890"
        assertReported(p, "country_iso")
    }

    @Test
    fun localeCountryConflictIsReported() {
        val p = base().toMutableMap()
        // Force a known mismatch regardless of the random carrier base() picked.
        p["country_iso"] = "us"
        p["locale"] = "en-GB"
        assertReported(p, "locale")
    }

    @Test
    fun timezoneCountryConflictIsReported() {
        val p = base().toMutableMap()
        p["country_iso"] = "us"
        p["timezone"] = "Europe/Paris"
        assertReported(p, "timezone")
    }

    @Test
    fun mobileNoCountryConflictIsReported() {
        val p = base().toMutableMap()
        p["country_iso"] = "us"
        p["mobile_no"] = "+33612345678"
        assertReported(p, "mobile_no")
    }

    @Test
    fun imsiOperatorConflictIsReported() {
        val p = base().toMutableMap()
        p["network_operator"] = "311480"
        p["imsi"] = "234150012345678"
        assertReported(p, "imsi")
    }

    @Test
    fun blankFieldsAreAllowed() {
        val p = mutableMapOf<String, String>()
        p["manufacturer"] = "   "
        p["brand"] = "" // both blank or one blank → no contradiction
        assertTrue(ProfileCoherence.issues(p).isEmpty())
    }

    // ---------- helpers ----------

    private fun base(): Map<String, String> = FakeData.generateAll()

    private fun assertReported(p: Map<String, String>, needle: String) {
        val issues = ProfileCoherence.issues(p)
        assertTrue("Expected an issue containing '$needle', got: $issues",
            issues.any { it.contains(needle, ignoreCase = true) })
    }

    private fun luhnDigit(body14: String): String {
        var sum = 0
        for (i in body14.indices.reversed()) {
            var n = body14[i] - '0'
            if ((body14.length - i) % 2 == 1) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
        }
        return ((10 - (sum % 10)) % 10).toString()
    }
}