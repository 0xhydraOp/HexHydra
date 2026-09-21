package dev.codex.deviceprivy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-pushes the saved config to `deviceprivy.*` system properties after boot.
 *
 * System properties don't survive a reboot, so without this every scoped app
 * would fall back to random values until the user re-opens the app and saves.
 * On boot we re-read the saved profile and re-push it, restoring consistency.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val prefs = context.getSharedPreferences("device_privy_prefs", Context.MODE_PRIVATE)
            val map = HashMap<String, String>()
            for ((key, value) in prefs.all) {
                when (value) {
                    is String -> map[key] = value
                    is Boolean -> map[key] = value.toString()
                }
            }
            if (map.isNotEmpty()) Bridge.pushToSystemProperties(map)
        } catch (_: Throwable) {}
    }
}
