package dev.hexhydra

/**
 * Pure-Kotlin field validation for user-entered spoof values.
 * No Android dependencies, so it runs in plain JVM unit tests.
 *
 * Contract: blank = allowed (the hook layer falls back to generated data).
 * Returns an error message for an invalid non-blank value, null when valid.
 */
object FieldValidators {

    private val macRegex = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
    private val ipv4Regex = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    private val hex16Regex = Regex("^[0-9a-fA-F]{16}$")

    fun validate(key: String, raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        return when (key) {
            "imei", "imsi" -> if (!v.matches(Regex("^\\d{15}$"))) "Must be 15 digits" else null
            "meid" -> if (!v.matches(Regex("^\\d{14}$"))) "Must be 14 digits" else null
            "mac_address", "mac_bssid", "bluetooth_mac" ->
                if (!v.matches(macRegex)) "Format AA:BB:CC:DD:EE:FF" else null
            "ip_address" -> if (!v.matches(ipv4Regex)) "Invalid IPv4 address" else null
            "latitude" -> {
                val d = v.toDoubleOrNull() ?: return "Must be a number"
                if (d < -90 || d > 90) "Range -90 … 90" else null
            }
            "longitude" -> {
                val d = v.toDoubleOrNull() ?: return "Must be a number"
                if (d < -180 || d > 180) "Range -180 … 180" else null
            }
            "screen_width", "screen_height", "screen_density" ->
                if (v.toIntOrNull()?.takeIf { it > 0 } == null) "Must be a positive number" else null
            "battery_level" -> {
                val n = v.toIntOrNull() ?: return "Must be 0 … 100"
                if (n < 0 || n > 100) "Must be 0 … 100" else null
            }
            "android_id", "gsf_id" ->
                if (!v.matches(hex16Regex)) "Must be 16 hex chars" else null
            "mobile_no" -> if (!v.matches(Regex("^\\+\\d{7,15}$"))) "Format +<country><number>" else null
            else -> null
        }
    }
}
