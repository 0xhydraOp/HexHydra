package dev.hexhydra

import org.junit.Test
import org.junit.Assert.*

class BridgeTest {

    // --- buildPropMap ---

    @Test
    fun buildPropMapMergesFieldsSettingsAndHooks() {
        val fields = mapOf("imei" to "  356938035643809 ", "model" to "Pixel 8")
        val map = Bridge.buildPropMap(fields, debugLog = true, hideSelf = false, hooks = mapOf("hook_ua" to true, "hook_location" to false))

        assertEquals("356938035643809", map["imei"])
        assertEquals("Pixel 8", map["model"])
        assertEquals("true", map["setting_debug_log"])
        assertEquals("false", map["setting_hide_self"])
        assertEquals("true", map["hook_ua"])
        assertEquals("false", map["hook_location"])
    }

    @Test
    fun buildPropMapTrimsFieldValuesButKeepsBlanks() {
        val map = Bridge.buildPropMap(mapOf("imei" to "   ", "mac_address" to " AA:BB:CC:DD:EE:FF "), debugLog = false, hideSelf = true, hooks = emptyMap())

        assertEquals("", map["imei"])
        assertEquals("AA:BB:CC:DD:EE:FF", map["mac_address"])
    }

    // --- buildPushScript ---

    @Test
    fun buildPushScriptSkipsEmptyValuesAndPushesTheRest() {
        val script = Bridge.buildPushScript(
            mapOf("model" to "Pixel 8", "imei" to "", "android_version" to "15"),
            timestamp = 123456789L
        )

        assertTrue(script.contains("setprop hexhydra.model 'Pixel 8';"))
        assertTrue(script.contains("setprop hexhydra.android_version '15';"))
        assertFalse(script.contains("hexhydra.imei"))
    }

    @Test
    fun buildPushScriptWritesRefreshTimestampLast() {
        val script = Bridge.buildPushScript(
            mapOf("model" to "Pixel 8", "imei" to "356938035643809"),
            timestamp = 123456789L
        )

        assertTrue(script.endsWith("setprop hexhydra.refreshed '123456789'"))
        val refreshedIndex = script.indexOf("hexhydra.refreshed")
        assertTrue(refreshedIndex > script.indexOf("hexhydra.model"))
    }

    @Test
    fun buildPushScriptEsquotesApostrophesAndDoubleQuotes() {
        val script = Bridge.buildPushScript(mapOf("device_name" to "Bob's \"Galaxy\""), timestamp = 1L)
        // Apostrophes are shell-escaped; double quotes are safe inside single quotes.
        assertTrue(script, script.contains("setprop hexhydra.device_name 'Bob'\\''s \"Galaxy\"';"))
    }

    @Test
    fun buildPushScriptSurvivesShellParsing() {
        // Proving correctness the way the OS consumes it: a script whose single-
        // quoted segments (plus the '\'' idiom) round-trip to the exact values.
        val value = "it's a 'test' value"
        val lang = "en-US"
        val script = Bridge.buildPushScript(
            linkedMapOf("locale" to lang, "device_name" to value),
            timestamp = 1L
        )

        val parsed = parseShellLiterals(script)
        // Every value is preserved verbatim after sh-style parsing.
        assertTrue("locale lost", parsed.contains("hexhydra.locale $lang"))
        assertTrue("value with quotes lost", parsed.contains("hexhydra.device_name $value"))
        assertTrue("refresh timestamp lost", parsed.contains("hexhydra.refreshed 1"))
    }

    @Test
    fun buildPushScriptWithEmptyMapStillWritesTimestamp() {
        val script = Bridge.buildPushScript(emptyMap(), timestamp = 5L)
        assertTrue(script.endsWith("setprop hexhydra.refreshed '5'"))
        assertTrue(script.startsWith("setprop hexhydra.refreshed"))
        assertTrue(script.endsWith("'5'"))
    }

    @Test
    fun buildPushScriptIsInjectionSafe() {
        val evil = "x; rm -rf / & echo 'quoted'"
        val script = Bridge.buildPushScript(mapOf("network_operator" to evil), timestamp = 1L)

        // After sh-style parsing, the value is reconstructed as a single literal:
        // neither ';' nor '&' terminates the setprop, and nothing is executed.
        val parsed = parseShellLiterals(script)
        assertTrue("value mangled", parsed.contains("hexhydra.network_operator $evil"))
    }

    @Test
    fun buildPushScriptHandlesUnicodeAndSpecialChars() {
        val script = Bridge.buildPushScript(
            mapOf("device_name" to "Galaxy S25 Ultra (5G) #Pro", "build_id" to "7ZP45C"),
            timestamp = 42L
        )
        assertTrue(script.contains("setprop hexhydra.device_name 'Galaxy S25 Ultra (5G) #Pro';"))
        assertTrue(script.contains("setprop hexhydra.build_id '7ZP45C';"))
    }

    @Test
    fun buildPushScriptOrderIsStable() {
        val values = linkedMapOf(
            "model" to "Pixel 8",
            "imei" to "356938035643809",
            "locale" to "en-US"
        )
        val script = Bridge.buildPushScript(values, timestamp = 7L)

        val modelIdx = script.indexOf("hexhydra.model")
        val imeiIdx = script.indexOf("hexhydra.imei")
        val localeIdx = script.indexOf("hexhydra.locale")
        assertTrue(modelIdx in 0 until imeiIdx)
        assertTrue(imeiIdx in 0 until localeIdx)
        assertTrue(localeIdx < script.indexOf("hexhydra.refreshed"))
    }

    @Test
    fun buildPushScriptWithGeneratedProfileRoundTrips() {
        repeat(100) {
            val data = FakeData.generateAll()
            val script = Bridge.buildPushScript(data, timestamp = 1L)
            val parsed = parseShellLiterals(script)
            for ((key, value) in data) {
                if (value.isNotBlank()) {
                    assertTrue("$key not round-tripped", parsed.contains("hexhydra.$key $value"))
                }
            }
        }
    }

    /**
     * Minimal sh single-quote parser: '…' segments are literal; inside them `\`
     * is literal too; the `'\''` idiom closes, escapes a quote, then re-opens.
     * Returns the concatenated literal content of the whole script.
     */
    private fun parseShellLiterals(script: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                quoted && c == '\'' -> quoted = false
                quoted -> out.append(c)
                c == '\'' -> quoted = true
                c == '\\' && i + 1 < script.length -> { out.append(script[i + 1]); i++ }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}