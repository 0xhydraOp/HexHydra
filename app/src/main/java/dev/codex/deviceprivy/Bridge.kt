package dev.codex.deviceprivy

/**
 * Cross-process bridge used by both the UI (on Save) and the boot receiver.
 *
 * The saved config is pushed to globally-readable `deviceprivy.*` system
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
     * Build the single `su -c` script that sets each non-empty value.
     * Blank values are skipped (the hook layer falls back to generated data).
     * The refresh timestamp is written last so the module's poller always sees
     * a complete, consistent write.
     */
    fun buildPushScript(values: Map<String, String>, timestamp: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        for ((key, value) in values) {
            if (value.isEmpty()) continue
            sb.append("setprop deviceprivy.").append(key)
                .append(" '").append(value.replace("'", "'\\''")).append("';")
        }
        // refreshed timestamp last, so the module's poller sees a fresh write
        sb.append("setprop deviceprivy.refreshed '").append(timestamp).append("'")
        return sb.toString()
    }

    /** Push the given key/value map to `deviceprivy.*` system properties. */
    fun pushToSystemProperties(values: Map<String, String>) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", buildPushScript(values)))
            process.waitFor()
        } catch (_: Throwable) {}
    }
}
