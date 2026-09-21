package dev.hexhydra

/**
 * Cross-field coherence checks for a generated or user-entered profile.
 * Pure Kotlin (no Android dependencies) so it runs in plain JVM unit tests.
 *
 * Contract: blank = allowed (ProfileCoherence only reports on non-blank
 * values that contradict each other). A profile produced by FakeData.generateAll()
 * must always return an empty list; anything non-empty is a real contradiction.
 */
object ProfileCoherence {

    // MCC (first 3 digits of the network operator / MCC-MNC) → country ISO.
    private val mccCountry = mapOf(
        "310" to "us", "311" to "us", "312" to "us",
        "404" to "in", "405" to "in",
        "234" to "gb", "235" to "gb",
        "208" to "fr",
        "505" to "au"
    )

    // IANA timezone prefix → country ISO for the carriers we generate.
    private val timezoneCountry = mapOf(
        "America/New_York" to "us",
        "America/Los_Angeles" to "us",
        "America/Chicago" to "us",
        "Asia/Kolkata" to "in",
        "Europe/London" to "gb",
        "Europe/Paris" to "fr",
        "Australia/Sydney" to "au"
    )

    // Dial prefix → country ISO (only prefixes we generate).
    private val dialPrefixCountry = mapOf(
        "+1" to "us", "+91" to "in", "+44" to "gb", "+33" to "fr", "+61" to "au"
    )

    private fun Map<String, String>.value(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotEmpty() }

    /** Runs every check and returns human-readable issues. Empty = coherent. */
    fun issues(profile: Map<String, String>): List<String> {
        val out = mutableListOf<String>()
        val m = profile.value("manufacturer")?.lowercase()

        brandMatchesManufacturer(profile)?.let { out.add(it) }
        modelMatchesDeviceName(profile)?.let { out.add(it) }
        boardMatchesCodename(profile)?.let { out.add(it) }
        fingerprintConsistent(profile)?.let { out.add(it) }
        m?.let { manufacturer ->
            tacInPool(profile, manufacturer)?.let { out.add(it) }
            macOuiInPool(profile, manufacturer)?.let { out.add(it) }
            batteryScaleMatches(profile, manufacturer)?.let { out.add(it) }
        }
        mccMatchesCountry(profile)?.let { out.add(it) }
        localeMatchesCountry(profile)?.let { out.add(it) }
        timezoneMatchesCountry(profile)?.let { out.add(it) }
        mobileNoMatchesCountry(profile)?.let { out.add(it) }
        imsiMatchesOperator(profile)?.let { out.add(it) }
        return out
    }

    fun isCoherent(profile: Map<String, String>): Boolean = issues(profile).isEmpty()

    private fun eq(a: String?, b: String?): Boolean =
        a != null && b != null && a.equals(b, ignoreCase = true)

    private fun brandMatchesManufacturer(p: Map<String, String>): String? {
        val brand = p.value("brand")
        val manu = p.value("manufacturer")
        if (brand == null || manu == null) return null
        if (!brand.equals(manu, ignoreCase = true))
            return "brand '$brand' does not match manufacturer '$manu'"
        return null
    }

    private fun modelMatchesDeviceName(p: Map<String, String>): String? {
        val model = p.value("model")
        val name = p.value("device_name")
        if (model == null || name == null) return null
        if (!name.contains(model, ignoreCase = true) && !model.contains(name, ignoreCase = true))
            return "device_name '$name' does not relate to model '$model'"
        return null
    }

    private fun boardMatchesCodename(p: Map<String, String>): String? {
        val board = p.value("board")
        val device = p.value("device")
        if (board == null || device == null) return null
        val expected = device.lowercase().replace(Regex("[^a-z0-9._-]"), "")
        if (board != expected)
            return "board '$board' should be the lowercased codename '$expected'"
        return null
    }

    private fun fingerprintConsistent(p: Map<String, String>): String? {
        val fp = p.value("fingerprint") ?: return null
        val manu = p.value("manufacturer")
        val product = p.value("product")
        val device = p.value("device")
        if (manu != null && product != null && device != null) {
            val prefix = "$manu/$product/$device:"
            if (!fp.startsWith(prefix, ignoreCase = true))
                return "fingerprint does not start with '$prefix'"
        }
        val fmt = Regex("^[^/:]+/[^/:]+/[^/:]+:[^/]+/[^/]+/[0-9]+:user/release-keys$")
        if (!fp.matches(fmt))
            return "fingerprint has unexpected format (want manufacturer/product/device:version/build/number:user/release-keys)"
        return null
    }

    private fun tacInPool(p: Map<String, String>, manufacturer: String): String? {
        val pool = IdentityData.TAC_POOLS[manufacturer] ?: return null // skip: no verified pool
        val imei = p.value("imei") ?: return null
        if (imei.length < 8) return "imei too short to carry a TAC"
        val tac = imei.substring(0, 8)
        if (tac !in pool)
            return "imei TAC $tac is not a verified $manufacturer TAC"
        return null
    }

    private fun macOuiInPool(p: Map<String, String>, manufacturer: String): String? {
        val pool = IdentityData.OUI_POOLS[manufacturer] ?: return null // skip: no verified pool
        val keys = listOf("mac_address", "mac_bssid", "bluetooth_mac")
        for (k in keys) {
            val mac = p[k]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val octets = mac.split(":")
            if (octets.size < 3) return "$k '$mac' is not a valid MAC address"
            val oui = octets.take(3).joinToString("").uppercase()
            if (oui !in pool)
                return "$k OUI $oui is not a verified $manufacturer OUI"
        }
        return null
    }

    private fun batteryScaleMatches(p: Map<String, String>, manufacturer: String): String? {
        val scale = p.value("battery_scale") ?: return null
        val expected = when {
            "samsung" in manufacturer || "xiaomi" in manufacturer ||
                "oppo" in manufacturer || "realme" in manufacturer -> "1000"
            else -> "100"
        }
        if (scale != expected)
            return "battery_scale $scale does not match $manufacturer (want $expected)"
        return null
    }

    private fun mccMatchesCountry(p: Map<String, String>): String? {
        val operator = p.value("network_operator") ?: return null
        val iso = p.value("country_iso") ?: return null
        val mcc = operator.take(3)
        val expected = mccCountry[mcc] ?: return null // skip: unknown MCC
        if (!iso.equals(expected, ignoreCase = true))
            return "country_iso '$iso' conflicts with network_operator MCC '$mcc' (want '$expected')"
        return null
    }

    private fun localeMatchesCountry(p: Map<String, String>): String? {
        val locale = p.value("locale") ?: return null
        val iso = p.value("country_iso") ?: return null
        val dash = locale.indexOf('-')
        if (dash < 0) return null
        val localeCountry = locale.substring(dash + 1)
        if (!iso.equals(localeCountry, ignoreCase = true))
            return "locale '$locale' does not match country_iso '$iso'"
        return null
    }

    private fun timezoneMatchesCountry(p: Map<String, String>): String? {
        val tz = p.value("timezone") ?: return null
        val iso = p.value("country_iso") ?: return null
        val expected = timezoneCountry[tz] ?: timezoneCountry.entries
            .firstOrNull { tz.startsWith(it.key) }?.value ?: return null
        if (!iso.equals(expected, ignoreCase = true))
            return "timezone '$tz' does not match country_iso '$iso' (want '$expected')"
        return null
    }

    private fun mobileNoMatchesCountry(p: Map<String, String>): String? {
        val no = p.value("mobile_no") ?: return null
        val iso = p.value("country_iso") ?: return null
        for ((prefix, expected) in dialPrefixCountry) {
            if (no.startsWith(prefix) && !iso.equals(expected, ignoreCase = true))
                return "mobile_no '$no' prefix '$prefix' does not match country_iso '$iso'"
        }
        return null
    }

    private fun imsiMatchesOperator(p: Map<String, String>): String? {
        val imsi = p.value("imsi") ?: return null
        val operator = p.value("network_operator") ?: return null
        if (operator.length < 4) return null
        if (!imsi.startsWith(operator))
            return "imsi '$imsi' does not start with network_operator '$operator'"
        return null
    }
}