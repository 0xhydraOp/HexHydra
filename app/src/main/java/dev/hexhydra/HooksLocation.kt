package dev.hexhydra

import android.location.Location
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Locale
import java.util.TimeZone

internal fun XposedEntry.hookLocation(classLoader: ClassLoader) {
        try {
            val lm = "android.location.LocationManager"
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val loc = param.result as? Location ?: return
                    val lat = getValue("latitude").toDoubleOrNull()
                    val lon = getValue("longitude").toDoubleOrNull()
                    if (lat != null && lon != null) {
                        loc.latitude = lat
                        loc.longitude = lon
                        loc.time = System.currentTimeMillis()
                        loc.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                    }
                }
            }
            XposedHelpers.findAndHookMethod(lm, classLoader, "getLastKnownLocation", String::class.java, hook)
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    XposedHelpers.findAndHookMethod(lm, classLoader, "getLastKnownLocation", String::class.java, 
                        XposedHelpers.findClass("android.location.LastLocationRequest", classLoader), hook)
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Fused Location Provider Hook ==========
    // Returns a pre-completed Task with spoofed Location instead of modifying
    // the real task (calling getResult() on an incomplete Task crashes).
internal fun XposedEntry.hookLocationFused(classLoader: ClassLoader) {
        try {
            val fusedClass = XposedHelpers.findClass(
                "com.google.android.gms.location.FusedLocationProviderClient", classLoader)
            XposedHelpers.findAndHookMethod(fusedClass, "getLastLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        val loc = Location("gps").apply {
                            latitude = lat
                            longitude = lon
                            time = System.currentTimeMillis()
                            elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                        }
                        try {
                            val tasksClass = XposedHelpers.findClass(
                                "com.google.android.gms.tasks.Tasks", classLoader)
                            param.result = XposedHelpers.callStaticMethod(
                                tasksClass, "forResult", loc)
                        } catch (e: Throwable) {
                            log("Tasks.forResult failed: ${e.message}")
                        }
                    }
                })
        } catch (e: Throwable) {
            log("FusedLocationProviderClient not available (no GMS)")
        }
    }

    // ========== v3.7.5: Live Location Hook (constructor-level) ==========
    // Hooks the Location(String) constructor so EVERY Location object created
    // anywhere — by LocationManager, FusedProvider, or app code — gets spoofed
    // coordinates. Much more robust than hooking individual listeners.
internal fun XposedEntry.hookLocationLive(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookConstructor(
                "android.location.Location", classLoader, String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val loc = param.thisObject as? Location ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        loc.latitude = lat
                        loc.longitude = lon
                    }
                })
        } catch (e: Throwable) {
            log("Location constructor hook failed: ${e.message}")
        }

        // Also hook the copy-constructor Location(Location) for completeness
        try {
            XposedHelpers.findAndHookConstructor(
                "android.location.Location", classLoader,
                XposedHelpers.findClass("android.location.Location", classLoader),
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val loc = param.thisObject as? Location ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        loc.latitude = lat
                        loc.longitude = lon
                    }
                })
        } catch (e: Throwable) {}

        // Android 14+: LocationManager.getCurrentLocation()
        try {
            val lm = "android.location.LocationManager"
            XposedHelpers.findAndHookMethod(lm, classLoader, "getCurrentLocation",
                String::class.java, android.os.CancellationSignal::class.java,
                java.util.concurrent.Executor::class.java, java.util.function.Consumer::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val consumer = param.args[3] as? java.util.function.Consumer<Any> ?: return
                        val lat = getValue("latitude").toDoubleOrNull() ?: return
                        val lon = getValue("longitude").toDoubleOrNull() ?: return
                        param.args[3] = java.util.function.Consumer<Any> { locObj ->
                            if (locObj is Location) {
                                locObj.latitude = lat
                                locObj.longitude = lon
                                locObj.time = System.currentTimeMillis()
                                locObj.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                            }
                            consumer.accept(locObj)
                        }
                    }
                })
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookLocaleAndTimezone(classLoader: ClassLoader) {
    // Locale/TimeZone spoofing is DEFERRED until after Application.onCreate
    // completes. Replacing Locale.getDefault() during early init breaks
    // resource resolution — observed in Chamet, where the antsec SDK loads
    // a hidden resource that fails lookup under an unexpected locale and
    // throws HandlerException blaming ARouter ("No package ID 6b found").
    try {
        XposedHelpers.findAndHookMethod("java.util.Locale", classLoader, "getDefault", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!appInitialized) return
                val localeTag = getValue("locale")
                if (localeTag.isNotEmpty()) {
                    param.result = java.util.Locale.forLanguageTag(localeTag)
                }
            }
        })
    } catch (_: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("java.util.Locale", classLoader, "getDefault",
                java.util.Locale.Category::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!appInitialized) return
                        val localeTag = getValue("locale")
                        if (localeTag.isNotEmpty()) {
                            param.result = java.util.Locale.forLanguageTag(localeTag)
                        }
                    }
                })
        } catch (_: Throwable) {}

        try {
            XposedHelpers.findAndHookMethod("java.util.TimeZone", classLoader, "getDefault", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!appInitialized) return
                    val zone = getValue("timezone")
                    if (zone.isNotEmpty()) {
                        param.result = java.util.TimeZone.getTimeZone(zone)
                    }
                }
            })
        } catch (_: Throwable) {}
}

