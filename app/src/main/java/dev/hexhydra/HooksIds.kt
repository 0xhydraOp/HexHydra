package dev.hexhydra

import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookSettings(classLoader: ClassLoader) {
        try {
            val hook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val requestedKey = param.args.getOrNull(1) as? String ?: return
                    val fake = when (requestedKey) {
                        "android_id" -> getValue("android_id")
                        "device_name", "bluetooth_name" -> getValue("device_name")
                        else -> null
                    }
                    if (!fake.isNullOrEmpty()) {
                        param.result = fake
                    }
                }
            }
            XposedHelpers.findAndHookMethod("android.provider.Settings.Secure", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod("android.provider.Settings.System", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod("android.provider.Settings.Global", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, hook)
            
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.Secure", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.System", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            try {
                XposedHelpers.findAndHookMethod("android.provider.Settings.Global", classLoader, "getStringForUser",
                    android.content.ContentResolver::class.java, String::class.java, Int::class.javaPrimitiveType!!, hook)
            } catch (e: Throwable) {}
            
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookSharedPreferences(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.SharedPreferencesImpl",
                classLoader,
                "getString",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        val fake = when (key) {
                            "market_name", "device_name", "bluetooth_name" -> getValue("device_name")
                            else -> null
                        }
                        if (!fake.isNullOrEmpty()) {
                            param.result = fake
                        }
                    }
                }
            )
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookAAID(classLoader: ClassLoader) {
    try {
        // Hook AdvertisingIdClient.Info.getId() ONCE at setup time.
        //
        // The previous implementation installed a fresh XC_MethodHook on every
        // call to getAdvertisingIdInfo() (called by analytics, ID-rotation, and
        // crash reporters like Bugly). Each call stacked another hook layer on
        // getId(), causing unbounded hook accumulation and a measurable
        // slowdown — combined with other hooks, this contributed to startup
        // crashes in apps like Chamet.
        val infoClass = XposedHelpers.findClass(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
            classLoader
        )
        XposedHelpers.findAndHookMethod(infoClass, "getId", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val aaid = getValue("aaid")
                if (aaid.isNotEmpty()) param.result = aaid
            }
        })
    } catch (_: Throwable) {
        // AdvertisingIdClient.Info may not exist on GMS-free devices — silent skip.
    }
}

internal fun XposedEntry.hookGServices(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.google.android.gsf.Gservices", classLoader, "getString",
                android.content.ContentResolver::class.java, String::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[1] == "android_id") {
                        val gsf = getValue("gsf_id")
                        if (gsf.isNotEmpty()) param.result = gsf
                    }
                }
            })
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookContentResolverQueries(classLoader: ClassLoader) {
    val hook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            try {
                val uri = param.args.firstOrNull() as? Uri ?: return
                if (uri.authority != "com.google.android.gsf.gservices") return

                val gsf = getValue("gsf_id")
                if (gsf.isEmpty()) return

                // Selection-args vary by overload — pick the first String[] in args.
                val selectionArgs = param.args.filterIsInstance<Array<String>>().firstOrNull()
                val queryArgs = param.args.filterIsInstance<android.os.Bundle>().firstOrNull()
                // Constant value: android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS = "android:query-arg-sql-selection-args"
                val bundleSelectionArgs = queryArgs?.getStringArray("android:query-arg-sql-selection-args")
                val requestedKeys = selectionArgs ?: bundleSelectionArgs ?: return
                if (requestedKeys.none { it == "android_id" }) return

                // CRITICAL: only intercept when the caller's projection is one we
                // can safely fill. If the caller asks for columns beyond
                // ("key", "value") we don't know how to populate them — falling
                // through to the original avoids CursorIndexOutOfBoundsException
                // (observed to crash scoped apps on Android 15).
                // Constant value: android.content.ContentResolver.QUERY_ARG_SELECT_COLUMNS = "android:query-arg-columns"
                val projection: Array<String>? = when {
                    // query(Uri, projection, selection, args, order)         — 5 args
                    // query(Uri, projection, selection, args, order, CS)      — 6 args
                    // query(Uri, projection, Bundle, CS)                      — 4 args (Bundle API)
                    param.args.size == 5 -> param.args[1] as? Array<String>
                    param.args.size == 6 &&
                        param.args[5] is android.os.CancellationSignal ->
                        param.args[1] as? Array<String>
                    param.args.size == 4 ->
                        (param.args[2] as? android.os.Bundle)
                            ?.getStringArray("android:query-arg-columns")
                    else -> null
                }
                val safeToIntercept = projection == null ||
                    projection.isEmpty() ||
                    projection.all { it == "key" || it == "value" }
                if (!safeToIntercept) return  // unknown columns — let the real query run

                param.result = MatrixCursor(arrayOf("key", "value")).apply {
                    addRow(arrayOf("android_id", gsf))
                }
            } catch (_: Throwable) {
                // Defensive: never let the target process die from a query hook.
            }
        }
    }

    try {
        XposedHelpers.findAndHookMethod(
            "android.content.ContentResolver",
            classLoader,
            "query",
            Uri::class.java,
            Array<String>::class.java,
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            hook
        )
    } catch (_: Throwable) {}

    try {
        XposedHelpers.findAndHookMethod(
            "android.content.ContentResolver",
            classLoader,
            "query",
            Uri::class.java,
            Array<String>::class.java,
            String::class.java,
            Array<String>::class.java,
            String::class.java,
            android.os.CancellationSignal::class.java,
            hook
        )
    } catch (_: Throwable) {}

    try {
        XposedHelpers.findAndHookMethod(
            "android.content.ContentResolver",
            classLoader,
            "query",
            Uri::class.java,
            Array<String>::class.java,
            android.os.Bundle::class.java,
            android.os.CancellationSignal::class.java,
            hook
        )
    } catch (_: Throwable) {}
}

internal fun XposedEntry.hookMediaDrm(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.media.MediaDrm", classLoader, "getPropertyByteArray", String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && "deviceUniqueId" == param.args[0]) {
                        val did = getValue("media_drm_id")
                        if (did.isNotEmpty()) {
                            try {
                                val uuid = java.util.UUID.fromString(did)
                                param.result = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
                            } catch (e: Exception) {}
                        }
                    }
                }
            })
        } catch (e: Throwable) {}
    }

