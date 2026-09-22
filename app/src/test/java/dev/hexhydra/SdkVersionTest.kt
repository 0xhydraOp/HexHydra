package dev.hexhydra

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the RELEASE -> SDK_INT mapping.
 *
 * Before the fix, Android 8-11 were mapped one level too high
 * (e.g. "11" -> 31, which is Android 12), making the spoofed
 * ro.build.version.sdk contradict the spoofed release string —
 * a trivial coherence tell for fingerprinting SDKs.
 */
class SdkVersionTest {

    @Test
    fun knownReleasesMapToCorrectSdk() {
        val expected = mapOf(
            "16" to 36,
            "15" to 35,
            "14" to 34,
            "13" to 33,
            "12" to 31,   // Android 12 = 31 (12L = 32; base mapping preferred)
            "11" to 30,
            "10" to 29,
            "9" to 28,
            "8.1.0" to 26, // base mapping: 8.x -> 26
            "8" to 26,
        )
        for ((release, sdk) in expected) {
            assertEquals("release $release", sdk, XposedEntry.sdkVersionToInt(release))
        }
    }

    @Test
    fun unknownReleaseDefaultsToAndroid15() {
        assertEquals(35, XposedEntry.sdkVersionToInt("99"))
        assertEquals(35, XposedEntry.sdkVersionToInt(""))
    }
}
