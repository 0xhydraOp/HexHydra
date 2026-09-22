package dev.hexhydra

import java.io.File

/**
 * Cross-process bridge used by both the UI (on Save) and the boot receiver.
 *
 * The saved config is pushed to globally-readable `hexhydra.*` system
 * properties via root. The module reads them with readFromSystemProperties()
 * in every scoped process, so all scoped apps share one consistent identity.
 *
 * This is needed because XSharedPreferences only reads
 * /data/user/<id>/<pkg>/shared_prefs/, and on this Nothing OS ROM the
 * framework stores prefs elsewhere (/data/misc/<uuid>/prefs/<pkg>/), which
 * made the module fall back to random values per process.
 */
object Bridge {

    /**
     * Merge the user-entered field values (trimmed), the app settings and the
     * per-hook toggles into the single map that gets pushed as system props.
     * Pure, so it can be unit-tested. Mirrors the UI save flow exactly.
     */
    fun buildPropMap(
        fieldValues: Map<String, String>,
        debugLog: Boolean,
        hideSelf: Boolean,
        hooks: Map<String, Boolean>
    ): Map<String, String> {
        val map = HashMap<String, String>()
        for ((key, value) in fieldValues) {
            map[key] = value.trim()
        }
        map["setting_debug_log"] = debugLog.toString()
        map["setting_hide_self"] = hideSelf.toString()
        for ((key, enabled) in hooks) {
            map[key] = enabled.toString()
        }
        return map
    }

    /**
     * Android caps a system-property value at ~91 bytes (PROP_VALUE_MAX), but
     * generated values like `user_agent` (~137 chars) exceed it, so a single
     * `setprop` for them always fails. Long values are split into 80-char
     * chunks (`key`, `key2`, `key3`) that the module reassembles; chunking the
     * raw value BEFORE shell-escaping keeps every chunk safely under the cap.
     */
    const val PROP_CHUNK_SIZE = 80
    private val SPLIT_KEYS = setOf("user_agent")
    private const val MAX_CHUNKS = 3

    /** Expand splittable long values into chunked entries. Pure, unit-tested. */
    fun expandForProps(values: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in values) {
            if (value.isEmpty()) continue
            if (key in SPLIT_KEYS && value.length > PROP_CHUNK_SIZE) {
                out[key] = value.substring(0, PROP_CHUNK_SIZE)
                var rest = value.substring(PROP_CHUNK_SIZE)
                var i = 2
                while (rest.isNotEmpty() && i <= MAX_CHUNKS) {
                    out["$key$i"] = rest.take(PROP_CHUNK_SIZE)
                    rest = rest.drop(PROP_CHUNK_SIZE)
                    i++
                }
            } else {
                out[key] = value
            }
        }
        return out
    }

    /** Rejoin chunked entries (`key` + `key2` + `key3`). Pure, unit-tested. */
    fun reassembleSplitValue(values: Map<String, String>, key: String): String {
        val first = values[key]?.takeIf { it.isNotEmpty() } ?: return ""
        return first + values[key + "2"].orEmpty() + values[key + "3"].orEmpty()
    }

    /**
     * Build the single `su -c` script that sets each non-empty value.
     * Blank values are skipped (the hook layer falls back to generated data).
     * The refresh timestamp is written last so the module's poller always sees
     * a complete, consistent write.
     */
    fun buildPushScript(values: Map<String, String>, timestamp: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        for ((key, value) in expandForProps(values)) {
            sb.append("setprop hexhydra.").append(key)
                .append(" '").append(value.replace("'", "'\\''")).append("';")
        }
        // refreshed timestamp last, so the module's poller sees a fresh write
        sb.append("setprop hexhydra.refreshed '").append(timestamp).append("'")
        return sb.toString()
    }

    /** Locate `su` by probing the usual absolute paths, with a shell PATH fallback. */
    fun locateSu(): String? {
        val candidates = listOf(
            "/sbin/su", "/system/bin/su", "/system/xbin/su",
            "/system_ext/bin/su", "/vendor/bin/su", "/vendor/xbin/su",
            "/odm/bin/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/su/bin/su", "/debug_ramdisk/su"
        )
        for (path in candidates) {
            if (File(path).canExecute()) return path
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su 2>/dev/null || which su 2>/dev/null"))
            val line = p.inputStream.bufferedReader().readLine()?.trim()
            p.waitFor()
            line?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    /**
     * Push the given key/value map to `hexhydra.*` system properties via an
     * absolute-path `su`. Returns true only when `su` was found and the
     * `setprop` script exited 0, so callers can surface failures instead of
     * silently doing nothing.
     */
    fun pushToSystemProperties(values: Map<String, String>): Boolean {
        val su = locateSu() ?: return false
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(su, "-c", buildPushScript(values)))
            process.waitFor()
            process.exitValue() == 0
        } catch (_: Throwable) {
            false
        }
    }
}
