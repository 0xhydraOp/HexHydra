package dev.hexhydra

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage

class XposedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    internal val MODULE_PACKAGE = "dev.hexhydra"
    private val PREFS_NAME = "hexhydra_prefs"
    private val PROVIDER_URI = Uri.parse("content://$MODULE_PACKAGE.provider")
    
    private var debugEnabled = false
    private val cachedValues = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var dataFetched = false
    private var fetchAttempts = 0
    private var prefs: XSharedPreferences? = null
    @Volatile private var appContext: Context? = null
    
    private var lastSysPropRefresh = 0L
    private var lastPropPollTime = 0L
    private val PROP_POLL_INTERVAL_MS = 2000L
    private var dataFromUserPrefs = false  // true when data came from XSharedPreferences (user's saved config)
    private var deferredAntiXposed = false // install anti-detection hooks after app init
    @Volatile private var sysPropsWritten = false  // prevent duplicate su calls across zygotes
    // Set true after Instrumentation.callApplicationOnCreate finishes the app's
    // onCreate. Hooks that affect resource resolution (Locale.getDefault(),
    // java.lang.System.getProperty for java.runtime.version etc.) must NOT
    // fire before this point — replacing Locale before the app loads its
    // resources leaves package IDs unresolved and antsec-style integrity
    // SDKs throw a HandlerException blaming ARouter.
    //
    // internal (not private) so extension-function hooks in other files
    // (HooksDevice.hookJavaSystemProperties, HooksLocation.hookLocaleAndTimezone)
    // can read this flag.
    @Volatile internal var appInitialized = false

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        XposedBridge.log("HexHydra: initZygote started")
        initPrefs()
        fetchData()
        if (cachedValues.isEmpty()) {
            XposedBridge.log("HexHydra: initZygote - no prefs data, generating fake defaults")
            cachedValues.putAll(FakeData.generateAll())
        }
        if (!dataFetched) dataFetched = true
        if (dataFromUserPrefs) writeSharedFile()  // only main zygote with real prefs
        writeToSystemProperties()
        lastSysPropRefresh = System.currentTimeMillis()
        debugEnabled = cachedValues["setting_debug_log"] == "true"
        XposedBridge.log("HexHydra: initZygote complete, ${cachedValues.size} values cached")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == MODULE_PACKAGE) {
            try {
                XposedHelpers.findAndHookMethod(
                    MainActivity::class.java.name, lpparam.classLoader, "isModuleActive",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = true
                        }
                    }
                )
            } catch (_: Throwable) {}
            return
        }

        if (lpparam.packageName == "android" || lpparam.packageName == "com.android.systemui") {
            return
        }

        XposedBridge.log("HexHydra: [${lpparam.packageName}] Loading module hooks")

        // Always re-fetch from shared prefs so the user's saved values take priority
        dataFetched = false
        fetchData()

        // Skip the reflection/classloader/proc-altering hooks for protected apps
        // and whenever compatibility mode is on (default). Spoof values are
        // still delivered by the plain getter hooks below.
        val safeMode = inCompatibilityMode(lpparam.packageName)
        if (safeMode) {
            XposedBridge.log("HexHydra: [${lpparam.packageName}] compatibility mode — skipping anti-detection/reflection hooks")
        }
        
        // NOTE: hookBuildFields is DEFERRED — it runs from
        // afterHookedMethod of callApplicationOnCreate below, not here.
        // Build field spoofing during early init trips Chamet's antsec
        // resource loader ("No package ID 6b found for resource ID
        // 0x6b0b0013" → throws HandlerException blaming ARouter).
        //
        // hookSystemProperties is installed here as a callback-only hook
        // (no static-field writes), but its callback body is also gated by
        // appInitialized so SystemProperties.get returns real values during
        // init. The hook still gets installed early so any post-init call
        // is covered without race.
        if (hookEnabled("hook_device") && !safeMode) {
            try { hookSystemProperties(lpparam.classLoader) } catch (_: Throwable) {}
        }

        try {
            val instrumentationClass = XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
            XposedHelpers.findAndHookMethod(instrumentationClass, "callApplicationOnCreate",
                android.app.Application::class.java, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val app = param.args[0] as? android.app.Application
                        if (app != null) {
                            appContext = app.applicationContext ?: app
                            XposedBridge.log("HexHydra: [${lpparam.packageName}] Application context captured, refreshing data")
                            dataFetched = false
                            fetchData(appContext)
                            if (!dataFetched) dataFetched = true
                            // Install deferred anti-detection hooks BEFORE app is fully initialized
                            // (some anti-Xposed checks run inside onCreate).
                            if (deferredAntiXposed && hookEnabled("hook_stealth")) {
                                deferredAntiXposed = false
                                try { hookAntiXposed(lpparam.classLoader) } catch (_: Throwable) {}
                                try { hookSensors(lpparam.classLoader) } catch (_: Throwable) {}
                            }
                        }
                    }
                    // afterHookedMethod runs AFTER the app's onCreate completes.
                    // Flipping appInitialized here releases the deferred hooks
                    // (Locale.getDefault, java.lang.System.getProperty,
                    //  SystemProperties.get, Build fields) so they only spoof
                    // after resource loading is done.
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!appInitialized) {
                            appInitialized = true
                            // Now safe to set static Build fields — the app has
                            // finished its own resource loading.
                            if (hookEnabled("hook_device")) {
                                try { hookBuildFields(lpparam.classLoader) } catch (_: Throwable) {}
                            }
                            XposedBridge.log("HexHydra: [${lpparam.packageName}] Application onCreate complete, deferred hooks now active")
                        }
                    }
                })
        } catch (_: Throwable) {}

        if (hookEnabled("hook_telephony")) {
            try { hookTelephony(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookPhoneStateListener(lpparam.classLoader) } catch (_: Throwable) {}
        }
        if (hookEnabled("hook_network")) {
            try { hookWifi(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookWifiDhcp(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookNetworkInterface(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookBluetooth(lpparam.classLoader) } catch (_: Throwable) {}
        }
        if (hookEnabled("hook_ids")) {
            try { hookSettings(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookSharedPreferences(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookMediaDrm(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookAAID(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookGServices(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookContentResolverQueries(lpparam.classLoader) } catch (_: Throwable) {}
        }
        if (hookEnabled("hook_location")) {
            try { hookLocation(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookLocationFused(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookLocationLive(lpparam.classLoader) } catch (_: Throwable) {}
            // Locale.getDefault / TimeZone.getDefault are global framework calls —
            // risky for protected apps, skipped in compatibility mode.
            if (!safeMode) {
                try { hookLocaleAndTimezone(lpparam.classLoader) } catch (_: Throwable) {}
            }
        }
        if (hookEnabled("hook_display")) {
            try { hookDisplay(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookOpenGL(lpparam.classLoader) } catch (_: Throwable) {}
            try { hookBattery(lpparam.classLoader) } catch (_: Throwable) {}
        }
        if (hookEnabled("hook_ua")) {
            try { hookUserAgent(lpparam.classLoader) } catch (_: Throwable) {}
            // java.lang.System.getProperty is a global framework call — risky for
            // protected apps, skipped in compatibility mode.
            if (!safeMode) {
                try { hookJavaSystemProperties(lpparam.classLoader) } catch (_: Throwable) {}
            }
        }
        if (hookEnabled("hook_stealth") && !safeMode) {
            try { hookPackageManager(lpparam.classLoader) } catch (_: Throwable) {}
            deferredAntiXposed = true
        } else {
            deferredAntiXposed = false
        }
    }

    private fun initPrefs() {
        synchronized(this) {
            prefs = XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)
            prefs?.makeWorldReadable()
        }
    }

    @SuppressLint("Range")
    private fun fetchData(contextHint: Context? = appContext) {
        fetchAttempts++
        
        synchronized(cachedValues) {
            // Try XSharedPreferences FIRST — this contains the user's saved config
            try {
                initPrefs()
                prefs?.let {
                    it.reload()
                    val all = it.all
                    if (all != null && all.isNotEmpty()) {
                        cachedValues.clear()
                        for ((key, value) in all) {
                            cachedValues[key] = value.toString()
                        }
                        dataFetched = true
                        dataFromUserPrefs = true
                        debugEnabled = cachedValues["setting_debug_log"] == "true"
                        XposedBridge.log("HexHydra: Data fetched via XSharedPreferences (count=${all.size})")
                        return
                    }
                }
            } catch (e: Throwable) {
                XposedBridge.log("HexHydra: XSharedPreferences error: ${e.message}")
            }

            try {
                val context = contextHint ?: run {
                    val activityThreadClass = XposedHelpers.findClass("android.app.ActivityThread", null)
                    val activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
                    if (activityThread != null) {
                        (XposedHelpers.callMethod(activityThread, "getApplication") as? Context)
                            ?: (XposedHelpers.callMethod(activityThread, "getSystemContext") as? Context)
                    } else null
                }
                if (context != null) {
                    val cr = context.contentResolver
                    cr.query(PROVIDER_URI, null, null, null, null)?.use { cursor ->
                        if (cursor.count > 0) {
                            cachedValues.clear()
                            while (cursor.moveToNext()) {
                                val key = cursor.getString(cursor.getColumnIndex("key")) ?: continue
                                val value = cursor.getString(cursor.getColumnIndex("value")) ?: ""
                                cachedValues[key] = value
                            }
                            dataFetched = true
                            debugEnabled = getValue("setting_debug_log") == "true"
                            XposedBridge.log("HexHydra: Data updated via ContentProvider (count=${cursor.count})")
                            return
                        }
                    }
                }
            } catch (e: Throwable) {
                if (contextHint != null) XposedBridge.log("HexHydra: ContentProvider update error: ${e.message}")
            }

            // Fallback: Shared file (written by main zygote, readable across processes)
            if (cachedValues.isEmpty()) {
                if (readSharedFile()) {
                    dataFetched = true
                    debugEnabled = cachedValues["setting_debug_log"] == "true"
                    return
                }
            }

            // Fallback: SystemProperties (may have stale data from initZygote)
            if (cachedValues.isEmpty()) {
                if (!readFromSystemProperties()) {
                    XposedBridge.log("HexHydra: All data sources failed, generating fake defaults")
                    cachedValues.putAll(FakeData.generateAll())
                }
            }
            dataFetched = true
            debugEnabled = cachedValues["setting_debug_log"] == "true"
        }
    }

    internal fun getValue(key: String): String {
        if (!dataFetched) fetchData()
        val now = System.currentTimeMillis()
        // Only poll SystemProperties if data did NOT come from user's saved prefs
        if (dataFetched && !dataFromUserPrefs && now - lastPropPollTime > PROP_POLL_INTERVAL_MS) {
            lastPropPollTime = now
            try {
                val spClass = Class.forName("android.os.SystemProperties")
                val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
                val refreshed = getMethod.invoke(null, "hexhydra.refreshed", "0") as? String ?: "0"
                val ts = refreshed.toLongOrNull() ?: 0
                if (ts > lastSysPropRefresh) {
                    readFromSystemProperties()
                    lastSysPropRefresh = ts
                }
            } catch (e: Throwable) {}
        }
        return cachedValues[key] ?: ""
    }

    internal fun getLiteralOrValue(valueOrKey: String): String {
        return when (valueOrKey) {
            "REL", "user", "android-build", "release-keys", "us", "01" -> valueOrKey
            else -> getValue(valueOrKey)
        }
    }

    internal fun log(msg: String) {
        if (debugEnabled) XposedBridge.log("HexHydra: $msg")
    }

    internal fun hookEnabled(key: String): Boolean = cachedValues[key] != "false"

    // ===== Compatibility mode =====
    // Apps that ship aggressive anti-tamper / anti-Xposed SDKs (payment and
    // banking apps, antsec-based wrappers) run runtime class/dex work during
    // Application.onCreate — e.g. ARouter's route scan. Hooks that alter
    // reflection or classloading (a global Class.forName interceptor, /proc
    // read rewriting, PackageManager hiding) collide with that and crash the
    // app before any spoof value is read.
    //
    // In compatibility mode we skip ONLY those risky mechanisms. Every spoof
    // parameter stays available through the ordinary getter hooks, so the app
    // still receives fake values — it just no longer trips its own protection.
    private val PROTECTED_PACKAGES = setOf(
        "net.one97.paytm",       // Paytm
        "com.paytm.merchant",    // Paytm for Business
        "com.phonepe.app",       // PhonePe
        "com.phonepe.app.business"
    )

    private fun isProtectedPackage(pkg: String): Boolean =
        pkg in PROTECTED_PACKAGES || pkg.startsWith("com.antsafe")

    /**
     * Compatibility mode is ON by default (`setting_compat_mode` unset), and is
     * forced ON for [PROTECTED_PACKAGES]. Set `setting_compat_mode=false` to
     * re-enable the aggressive anti-detection hooks on ordinary apps.
     */
    private fun inCompatibilityMode(pkg: String): Boolean =
        isProtectedPackage(pkg) || cachedValues["setting_compat_mode"] != "false"

    internal fun hookMethodRet(className: String, classLoader: ClassLoader, methodName: String, retValKey: String, vararg argTypes: Any) {
        try {
            val args = arrayOf(*argTypes, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // Crash-safe: never let a hook callback throw into the
                    // target process's call site. The outer `try` only protects
                    // hook installation — without this inner guard, an
                    // unexpected class state (e.g., partial initialization,
                    // verification error from a sibling hook) could kill the
                    // scoped app. Observed in Chamet prior to v3.8.6.
                    try {
                        val value = getLiteralOrValue(retValKey)
                        if (value.isNotEmpty()) {
                            param.result = value
                            log("Hooked $className.$methodName -> $value")
                        }
                    } catch (_: Throwable) {}
                }
            })
            XposedHelpers.findAndHookMethod(className, classLoader, methodName, *args)
        } catch (_: Throwable) {}
    }


    internal fun setStaticSafe(clazz: Class<*>, fieldName: String, valueKey: String) {
        try {
            val value = getLiteralOrValue(valueKey)
            if (value.isNotEmpty()) {
                XposedHelpers.setStaticObjectField(clazz, fieldName, value)
            }
        } catch (e: Throwable) {}
    }

    internal fun setStaticSafe(clazz: Class<*>, fieldName: String, value: Int) {
        // ART 11+ ignores the `Field.modifiers` reflection trick used to clear
        // `final` on static int fields. On Android 14+ the verifier can raise
        // `IncompatibleClassChangeError` after this method returns, bypassing
        // the try/catch and crashing the target process (observed in Chamet).
        //
        // The actual working path is `hookSystemProperties` below, which
        // intercepts `SystemProperties.getInt("ro.build.version.sdk", …)`.
        // This wrapper is kept for compatibility but only attempts a single
        // best-effort write and never touches `Field.modifiers`.
        try {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            field.setInt(null, value)
        } catch (_: Throwable) {
            // Expected on Android 11+: silently swallow; the SystemProperties
            // hook covers SDK_INT spoofing for all post-ART-7 Android versions.
        }
    }


























    // ========== SystemProperties Bridge ==========

    companion object {
        private val KNOWN_KEYS = setOf(
            "manufacturer", "model", "brand", "device", "product", "board",
            "device_name", "build_id", "android_version", "fingerprint", "hardware_id",
            "android_id", "gsf_id", "aaid", "media_drm_id",
            "imei", "meid", "imsi", "sim_serial", "sim_sub_id", "mobile_no",
            "sim_operator", "network_operator", "country_iso",
            "mac_address", "mac_bssid", "mac_ssid", "bluetooth_mac", "ip_address",
            "latitude", "longitude", "locale", "timezone",
            "screen_width", "screen_height", "screen_density", "user_agent",
            "gl_renderer", "gl_vendor", "battery_level", "battery_scale",
            "setting_debug_log", "setting_hide_self",
            "hook_device", "hook_telephony", "hook_network", "hook_location",
            "hook_display", "hook_ids", "hook_ua", "hook_stealth"
        )

        fun sdkVersionToInt(sdkVersion: String): Int = when {
            sdkVersion.startsWith("16") -> 36
            sdkVersion.startsWith("15") -> 35
            sdkVersion.startsWith("14") -> 34
            sdkVersion.startsWith("13") -> 33
            sdkVersion.startsWith("12") -> 32
            sdkVersion.startsWith("11") -> 31
            sdkVersion.startsWith("10") -> 30
            sdkVersion.startsWith("9") -> 29
            sdkVersion.startsWith("8") -> 28
            else -> 35  // default to Android 15 if unknown
        }
    }

    // ========== Shared File IPC (Android 15 cross-process workaround) ==========
    
    private val SHARED_FILE = "/data/adb/hexhydra_data.json"

    private fun writeSharedFile() {
        // Skip if another zygote already wrote SystemProperties recently (prevents duplicate su prompts)
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val existing = getMethod.invoke(null, "hexhydra.refreshed", "") as? String ?: ""
            if (existing.isNotEmpty()) {
                val lastWrite = existing.toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - lastWrite < 120_000L) return // written < 2 min ago
            }
        } catch (_: Throwable) {}

        // Try writing to SystemProperties via setprop (su -c) as cross-process bridge.
        // Direct SystemProperties.set() is blocked on Android 15, but setprop via root works.
        try {
            val sb = StringBuilder()
            for ((key, value) in cachedValues) {
                if (value.isNotEmpty() && key in KNOWN_KEYS) {
                    // Escape single quotes for the su -c shell layer (e.g. device_name "O'Brien").
                    sb.append("setprop hexhydra.$key '${value.replace("'", "'\\''")}'")
                    sb.append(";")
                }
            }
            sb.append("setprop hexhydra.refreshed '${System.currentTimeMillis()}'")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", sb.toString()))
            process.waitFor()
            if (process.exitValue() == 0) {
                lastSysPropRefresh = System.currentTimeMillis()
                XposedBridge.log("HexHydra: SystemProperties written via setprop (${cachedValues.size} values)")
            }
        } catch (e: Throwable) {
            // Fallback: try JSON file (may still fail on strict SELinux)
            try {
                val json = org.json.JSONObject(cachedValues as Map<String, Any>).toString()
                java.io.File(SHARED_FILE).writeText(json)
                XposedBridge.log("HexHydra: Shared file written (fallback)")
            } catch (_: Throwable) {}
        }
    }

    private fun readSharedFile(): Boolean {
        try {
            val file = java.io.File(SHARED_FILE)
            if (!file.exists() || !file.canRead()) return false
            // Only use if written recently (within last 24h)
            if (System.currentTimeMillis() - file.lastModified() > 86400000L) return false
            val json = file.readText()
            if (json.isBlank()) return false
            val obj = org.json.JSONObject(json)
            cachedValues.clear()
            for (key in obj.keys()) {
                cachedValues[key] = obj.optString(key, "")
            }
            XposedBridge.log("HexHydra: Data fetched via shared file (count=${cachedValues.size})")
            return cachedValues.isNotEmpty()
        } catch (e: Throwable) {
            XposedBridge.log("HexHydra: Shared file read error: ${e.message}")
            return false
        }
    }

    private fun writeToSystemProperties() {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val setMethod = spClass.getDeclaredMethod("set", String::class.java, String::class.java)
            var count = 0
            for ((key, value) in cachedValues) {
                if (value.isNotEmpty() && key in KNOWN_KEYS) {
                    try {
                        setMethod.invoke(null, "hexhydra.$key", value)
                        count++
                    } catch (e: Throwable) {}
                }
            }
            setMethod.invoke(null, "hexhydra.refreshed", System.currentTimeMillis().toString())
            XposedBridge.log("HexHydra: SystemProperties written ($count values)")
        } catch (e: Throwable) {
            log("SystemProperties write error: ${e.message}")
        }
    }

    private fun readFromSystemProperties(): Boolean {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            
            val refreshed = getMethod.invoke(null, "hexhydra.refreshed", "") as? String ?: ""
            if (refreshed.isEmpty()) return false

            var count = 0
            for (key in KNOWN_KEYS) {
                val value = getMethod.invoke(null, "hexhydra.$key", "") as? String ?: ""
                if (value.isNotEmpty()) {
                    cachedValues[key] = value
                    count++
                }
            }
            if (count > 0) {
                dataFetched = true
                debugEnabled = cachedValues["setting_debug_log"] == "true"
                XposedBridge.log("HexHydra: Read $count values from SystemProperties")
                return true
            }
        } catch (e: Throwable) {
            XposedBridge.log("HexHydra: SystemProperties read error: ${e.message}")
        }
        return false
    }
}
