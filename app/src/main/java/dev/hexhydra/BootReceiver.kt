package dev.hexhydra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-pushes the saved config to `hexhydra.*` system properties after boot.
 *
 * System properties don't survive a reboot, so without this every scoped app
 * would fall back to random values until the user re-opens the app and saves.
 * On boot we re-read the saved profile and re-push it, restoring consistency.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // goAsync: su can block on a grant prompt, and onReceive runs on the
        // main thread — blocking it would ANR the whole broadcast queue.
        // Bridge.pushToSystemProperties is timeout-bounded; finish() always runs.
        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                val prefs = appContext.getSharedPreferences("hexhydra_prefs", Context.MODE_PRIVATE)
                val map = HashMap<String, String>()
                for ((key, value) in prefs.all) {
                    if (key == "locked_fields") continue // UI-only state, not a spoof value
                    when (value) {
                        is String -> map[key] = value
                        is Boolean -> map[key] = value.toString()
                    }
                }
                if (map.isNotEmpty()) {
                    val ok = Bridge.pushToSystemProperties(map)
                    android.util.Log.d("HexHydra", if (ok) "Boot props pushed" else "Boot prop push failed (no su or setprop error)")
                }
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}
