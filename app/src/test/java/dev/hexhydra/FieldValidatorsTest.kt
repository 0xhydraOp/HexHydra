package dev.hexhydra

import org.junit.Test
import org.junit.Assert.*

class FieldValidatorsTest {

    @Test
    fun blankIsAlwaysAllowed() {
        for (key in listOf("imei", "imsi", "mac_address", "ip_address", "latitude", "battery_level", "android_id", "mobile_no", "unknown_key")) {
            assertNull("blank $key", FieldValidators.validate(key, ""))
            assertNull("spaces $key", FieldValidators.validate(key, "   "))
        }
    }

    @Test
    fun imeiAndImsiNeed15Digits() {
        for (key in listOf("imei", "imsi")) {
            assertNull(FieldValidators.validate(key, "356938035643809"))
            assertNotNull(FieldValidators.validate(key, "35693803564380"))
            assertNotNull(FieldValidators.validate(key, "3569380356438090"))
            assertNotNull(FieldValidators.validate(key, "35693803564380a"))
        }
        assertNotNull(FieldValidators.validate("meid", "356938035643809"))
        assertNull(FieldValidators.validate("meid", "35693803564380"))
    }

    @Test
    fun macNeedsSixHexOctets() {
        assertNull(FieldValidators.validate("mac_address", "A2:B4:C6:D8:E0:12"))
        assertNull(FieldValidators.validate("bluetooth_mac", "a2:b4:c6:d8:e0:12"))
        assertNotNull(FieldValidators.validate("mac_address", "A2:B4:C6:D8:E0"))
        assertNotNull(FieldValidators.validate("mac_bssid", "A2-B4-C6-D8-E0-12"))
        assertNotNull(FieldValidators.validate("mac_address", "G2:B4:C6:D8:E0:12"))
    }

    @Test
    fun ipMustBeValidV4() {
        assertNull(FieldValidators.validate("ip_address", "192.168.1.10"))
        assertNull(FieldValidators.validate("ip_address", "10.0.0.1"))
        assertNotNull(FieldValidators.validate("ip_address", "999.1.1.1"))
        assertNotNull(FieldValidators.validate("ip_address", "192.168.1"))
        assertNotNull(FieldValidators.validate("ip_address", "not-an-ip"))
    }

    @Test
    fun geoRangesAreEnforced() {
        assertNull(FieldValidators.validate("latitude", "19.076000"))
        assertNull(FieldValidators.validate("longitude", "-74.006000"))
        assertNotNull(FieldValidators.validate("latitude", "91"))
        assertNotNull(FieldValidators.validate("latitude", "-90.5"))
        assertNotNull(FieldValidators.validate("longitude", "181"))
        assertNotNull(FieldValidators.validate("latitude", "abc"))
    }

    @Test
    fun androidIdsNeed16Hex() {
        assertNull(FieldValidators.validate("android_id", "a1b2c3d4e5f60718"))
        assertNotNull(FieldValidators.validate("gsf_id", "xyz"))
        assertNotNull(FieldValidators.validate("android_id", "a1b2c3d4e5f6071"))
    }

    @Test
    fun unknownKeysPassThrough() {
        assertNull(FieldValidators.validate("fingerprint", "anything at all 123 !@#"))
        assertNull(FieldValidators.validate("locale", "en-US"))
    }
}
