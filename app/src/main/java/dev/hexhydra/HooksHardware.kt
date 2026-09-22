package dev.hexhydra

import android.content.Intent
import android.content.IntentFilter
import android.util.DisplayMetrics
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/** Single source of truth for spoofed sensor vendors (behavior identical to the old inline copies). */
internal fun sensorVendorFor(manufacturer: String): String = when {
    manufacturer.equals("Samsung", ignoreCase = true) -> "STMicroelectronics"
    manufacturer.equals("Google", ignoreCase = true) -> "Bosch"
    else -> "Qualcomm" // Xiaomi, OnePlus, Nothing, Motorola, Asus and others
}

internal fun XposedEntry.hookDisplay(classLoader: ClassLoader) {
        val hook = object : SafeHook() {
            override fun onAfter(param: MethodHookParam) {
                // Crash-safe: a hook callback must never throw into the target process.
                try {
                    val dm = param.args.getOrNull(0) as? DisplayMetrics ?: return
                    val w = getValue("screen_width").toIntOrNull()
                    val h = getValue("screen_height").toIntOrNull()
                    val d = getValue("screen_density").toIntOrNull()
                    if (w != null) dm.widthPixels = w
                    if (h != null) dm.heightPixels = h
                    if (d != null) {
                        dm.densityDpi = d
                        dm.density = d / 160f
                        dm.scaledDensity = d / 160f
                        dm.xdpi = d.toFloat()
                        dm.ydpi = d.toFloat()
                    }
                } catch (_: Throwable) {}
            }
        }
        try {
            XposedHelpers.findAndHookMethod("android.view.Display", classLoader, "getMetrics", DisplayMetrics::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.view.Display", classLoader, "getRealMetrics", DisplayMetrics::class.java, hook)
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookOpenGL(classLoader: ClassLoader) {
        val hook = object : SafeHook() {
            override fun onBefore(param: MethodHookParam) {
                val type = param.args[0] as? Int ?: return
                if (type == 0x1F00) {
                    val vendor = getValue("gl_vendor")
                    if (vendor.isNotEmpty()) param.result = vendor
                } else if (type == 0x1F01) {
                    val renderer = getValue("gl_renderer")
                    if (renderer.isNotEmpty()) param.result = renderer
                }
            }
        }
        try { XposedHelpers.findAndHookMethod("android.opengl.GLES20", classLoader, "glGetString", Int::class.javaPrimitiveType!!, hook) } catch (e: Throwable) {}
        try { XposedHelpers.findAndHookMethod("android.opengl.GLES30", classLoader, "glGetString", Int::class.javaPrimitiveType!!, hook) } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Battery Scale Fix ==========
internal fun XposedEntry.hookBattery(classLoader: ClassLoader) {
        val hook = object : SafeHook() {
            override fun onAfter(param: MethodHookParam) {
                // Crash-safe: a hook callback must never throw into the target process.
                try {
                    val filter = param.args.getOrNull(1) as? android.content.IntentFilter ?: return
                    if (filter.hasAction(android.content.Intent.ACTION_BATTERY_CHANGED)) {
                        val intent = param.result as? android.content.Intent ?: return
                        val level = getValue("battery_level").toIntOrNull() ?: 85
                        val scale = getValue("battery_scale").toIntOrNull() ?: 100
                        val scaledLevel = (level * scale / 100).coerceIn(1, scale)
                        intent.putExtra("level", scaledLevel)
                        intent.putExtra("scale", scale)
                        intent.putExtra("status", 2)
                    }
                } catch (_: Throwable) {}
            }
        }

        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                Int::class.javaPrimitiveType!!, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                String::class.java, android.os.Handler::class.java, hook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.content.ContextWrapper", classLoader, "registerReceiver",
                android.content.BroadcastReceiver::class.java, android.content.IntentFilter::class.java,
                String::class.java, android.os.Handler::class.java, Int::class.javaPrimitiveType!!, hook)
        } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Sensor Spoofing ==========
internal fun XposedEntry.hookSensors(classLoader: ClassLoader) {
        val sensorHook = object : SafeHook() {
            override fun onAfter(param: MethodHookParam) {
                val sensors = param.result as? List<*> ?: return
                if (sensors.isEmpty()) return
                
                val vendor = sensorVendorFor(getValue("manufacturer"))
                
                for (sensor in sensors) {
                    try {
                        XposedHelpers.setObjectField(sensor, "mVendor", vendor)
                        // mStringType deliberately untouched: writing a vendor
                        // name into it corrupts Sensor.getStringType() and can
                        // crash apps that switch on sensor types.
                    } catch (e: Throwable) {}
                }
                param.result = sensors
            }
        }
        
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", classLoader,
                "getFullSensorList", sensorHook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SystemSensorManager", classLoader,
                "getSensorList", Int::class.javaPrimitiveType!!, sensorHook)
        } catch (e: Throwable) {}
        
        try {
            XposedHelpers.findAndHookMethod("android.hardware.SensorManager", classLoader,
                "getDefaultSensor", Int::class.javaPrimitiveType!!, object : SafeHook() {
                    override fun onAfter(param: MethodHookParam) {
                        val sensor = param.result ?: return
                        val vendor = sensorVendorFor(getValue("manufacturer"))
                        try {
                            XposedHelpers.setObjectField(sensor, "mVendor", vendor)
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

