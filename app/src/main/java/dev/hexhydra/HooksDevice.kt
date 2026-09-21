package dev.hexhydra

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookBuildFields(classLoader: ClassLoader) {
        try {
            val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
            setStaticSafe(buildClass, "MANUFACTURER", "manufacturer")
            setStaticSafe(buildClass, "MODEL", "model")
            setStaticSafe(buildClass, "BRAND", "brand")
            setStaticSafe(buildClass, "DEVICE", "device")
            setStaticSafe(buildClass, "PRODUCT", "product")
            setStaticSafe(buildClass, "BOARD", "board")
            setStaticSafe(buildClass, "HARDWARE", "hardware_id")
            // Build.SERIAL is deprecated and guarded by READ_PHONE_STATE on
            // Android 10+. Writing it via reflection can confuse ART's verifier
            // (observed to crash scoped apps on Android 15). Skip on API 29+.
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                setStaticSafe(buildClass, "SERIAL", "hardware_id")
            }
            setStaticSafe(buildClass, "FINGERPRINT", "fingerprint")
            setStaticSafe(buildClass, "ID", "build_id")
            setStaticSafe(buildClass, "DISPLAY", "build_id")
            setStaticSafe(buildClass, "TAGS", "release-keys")
            setStaticSafe(buildClass, "TYPE", "user")
            setStaticSafe(buildClass, "USER", "android-build")
            setStaticSafe(buildClass, "HOST", "android-build")
            setStaticSafe(buildClass, "BOOTLOADER", "build_id")
            
            val versionClass = XposedHelpers.findClass("android.os.Build\$VERSION", classLoader)
            setStaticSafe(versionClass, "RELEASE", "android_version")
            try {
                val sdkIntValue = XposedEntry.sdkVersionToInt(getValue("android_version"))
                setStaticSafe(versionClass, "SDK_INT", sdkIntValue)
            } catch (e: Throwable) {
                setStaticSafe(versionClass, "SDK_INT", 35)
            }
            setStaticSafe(versionClass, "CODENAME", "REL")
            setStaticSafe(versionClass, "INCREMENTAL", "build_id")
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookSystemProperties(classLoader: ClassLoader) {
    // SystemProperties callbacks are gated by appInitialized. The hook is
    // installed early so post-init calls are covered; during init, the
    // callback is a no-op and the real SystemProperties value is returned.
    // This protects Chamet's antsec resource lookup during onCreate.
    try {
        val spClass = XposedHelpers.findClass("android.os.SystemProperties", classLoader)
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!appInitialized) return
                val key = param.args[0] as? String ?: return
                val fake = when {
                        key.contains("ro.product.model") -> getValue("model")
                        key.contains("ro.product.manufacturer") -> getValue("manufacturer")
                        key.contains("ro.product.brand") -> getValue("brand")
                        key.contains("ro.product.device") -> getValue("device")
                        key.contains("ro.product.name") -> getValue("product")
                        key.contains("ro.product.board") -> getValue("board")
                        key.contains("ro.board.platform") -> getValue("board")
                        key.contains("ro.product.marketname") -> getValue("device_name")
                        key.contains("ro.product.system.marketname") -> getValue("device_name")
                        key.contains("ro.product.system_ext.marketname") -> getValue("device_name")
                        key.contains("ro.product.vendor.marketname") -> getValue("device_name")
                        key.contains("ro.product.odm.marketname") -> getValue("device_name")
                        key.contains("ro.config.marketing_name") -> getValue("device_name")
                        key.contains("persist.sys.device_name") -> getValue("device_name")
                        key.contains("ro.serialno") -> getValue("hardware_id")
                        key.contains("ro.boot.serialno") -> getValue("hardware_id")
                        key.contains("ro.build.id") -> getValue("build_id")
                        key.contains("ro.build.display.id") -> getValue("build_id")
                        key.contains("ro.build.fingerprint") -> getValue("fingerprint")
                        key.contains("ro.build.version.release") -> getValue("android_version")
                        key.contains("ro.build.version.sdk") -> {
                            XposedEntry.sdkVersionToInt(getValue("android_version")).toString()
                        }
                        key.contains("http.agent") -> getValue("user_agent")
                        key.contains("gsm.version.baseband") -> "M8996_1234.56.01R"
                        key.contains("ro.gsm.imei") -> getValue("imei")
                        key.contains("gsm.operator.iso-country") -> getValue("country_iso")
                        key.contains("persist.sys.locale") -> getValue("locale")
                        key.contains("persist.sys.timezone") -> getValue("timezone")
                        else -> null
                    }
                    if (fake != null && fake.isNotEmpty()) {
                        param.result = fake
                    }
                }
            }
            try { XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, hook) } catch (_: Throwable) {}
            XposedHelpers.findAndHookMethod(spClass, "get", String::class.java, String::class.java, hook)
            try {
                val intHook = object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!appInitialized) return
                        val key = param.args[0] as? String ?: return
                        if (key.contains("ro.build.version.sdk")) {
                            param.result = XposedEntry.sdkVersionToInt(getValue("android_version"))
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(spClass, "getInt", String::class.java, Int::class.javaPrimitiveType!!, intHook)
            } catch (_: Throwable) {}
    } catch (_: Throwable) {}
}

internal fun XposedEntry.hookUserAgent(classLoader: ClassLoader) {
        try {
            val ua = getValue("user_agent")
            if (ua.isEmpty()) return
            hookMethodRet("android.webkit.WebSettings", classLoader, "getDefaultUserAgent", "user_agent", Context::class.java)
            
            try {
                XposedHelpers.findAndHookMethod("android.webkit.WebView", classLoader, "loadUrl", String::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val settings = XposedHelpers.callMethod(param.thisObject, "getSettings")
                            XposedHelpers.callMethod(settings, "setUserAgentString", ua)
                        } catch (e: Throwable) {}
                    }
                })
            } catch (e: Throwable) {}
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookJavaSystemProperties(classLoader: ClassLoader) {
    // java.lang.System.getProperty is DEFERRED until after Application.onCreate
    // completes. Replacing java.runtime.version / java.vm.version during
    // early init trips Chamet's antsec integrity check (it reads the runtime
    // version during class verification). The hook fires normally for the
    // app's normal operation; only init-time calls pass through to the real
    // implementation.
    try {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!appInitialized) return
                val key = param.args[0] as? String ?: return
                val fake = when {
                    key.contains("http.agent") -> {
                        getValue("user_agent")
                    }
                    key.contains("os.name") -> "Linux"
                    key.contains("os.version") -> {
                        val ver = getValue("android_version")
                        when {
                            ver.startsWith("15") -> "6.1.43-android-15"
                            ver.startsWith("14") -> "5.15.123-android-14"
                            ver.startsWith("13") -> "5.10.198-android-13"
                            else -> "6.1.43-android-15"
                        }
                    }
                    key.contains("os.arch") -> "aarch64"
                    key.contains("java.vm.version") -> "2.1.0"
                    key.contains("java.runtime.version") -> "1.8.0"
                    else -> null
                }
                if (fake != null && fake.isNotEmpty()) {
                    param.result = fake
                }
            }
        }
        XposedHelpers.findAndHookMethod(java.lang.System::class.java, "getProperty",
            String::class.java, hook)
        XposedHelpers.findAndHookMethod(java.lang.System::class.java, "getProperty",
            String::class.java, String::class.java, hook)
    } catch (_: Throwable) {
        // Silently skip if java.lang.System.getProperty can't be hooked.
    }
}

