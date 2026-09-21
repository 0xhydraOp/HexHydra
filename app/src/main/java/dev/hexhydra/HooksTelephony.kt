package dev.hexhydra

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookTelephony(classLoader: ClassLoader) {
        val tm = "android.telephony.TelephonyManager"
        hookMethodRet(tm, classLoader, "getDeviceId", "imei")
        hookMethodRet(tm, classLoader, "getDeviceId", "imei", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getImei", "imei")
        hookMethodRet(tm, classLoader, "getImei", "imei", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getMeid", "meid")
        hookMethodRet(tm, classLoader, "getMeid", "meid", Int::class.javaPrimitiveType!!)
        hookMethodRet(tm, classLoader, "getSimSerialNumber", "sim_serial")
        hookMethodRet(tm, classLoader, "getSimOperator", "network_operator")
        hookMethodRet(tm, classLoader, "getSimOperatorName", "sim_operator")
        hookMethodRet(tm, classLoader, "getNetworkOperator", "network_operator")
        hookMethodRet(tm, classLoader, "getNetworkOperatorName", "sim_operator")
        hookMethodRet(tm, classLoader, "getNetworkCountryIso", "country_iso")
        hookMethodRet(tm, classLoader, "getSimCountryIso", "country_iso")
        hookMethodRet(tm, classLoader, "getLine1Number", "mobile_no")
        hookMethodRet(tm, classLoader, "getSubscriberId", "imsi")
        try {
            hookMethodRet(tm, classLoader, "getSubscriptionId", "sim_sub_id")
        } catch (e: Throwable) {}
    }

    // ========== v3.7.6: PhoneStateListener Hooks ==========
    // TelephonyManager.listen() registers a PhoneStateListener for live signal/cell
    // updates. We intercept the listener and hook its callbacks to return spoofed data.
internal fun XposedEntry.hookPhoneStateListener(classLoader: ClassLoader) {
        try {
            val tm = "android.telephony.TelephonyManager"
            XposedHelpers.findAndHookMethod(tm, classLoader, "listen",
                android.telephony.PhoneStateListener::class.java, Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val listener = param.args[0] as? android.telephony.PhoneStateListener ?: return
                        // Hook onSignalStrengthsChanged on this listener instance
                        try {
                            XposedHelpers.findAndHookMethod(listener.javaClass, "onSignalStrengthsChanged",
                                android.telephony.SignalStrength::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        p.result = null // block callback entirely — no signal leak
                                    }
                                })
                        } catch (e: Throwable) {}
                        // Hook onCellInfoChanged to return empty list
                        try {
                            XposedHelpers.findAndHookMethod(listener.javaClass, "onCellInfoChanged",
                                java.util.List::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(p: MethodHookParam) {
                                        p.result = java.util.Collections.emptyList<Any>()
                                    }
                                })
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

