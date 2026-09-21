package dev.hexhydra

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookWifi(classLoader: ClassLoader) {
        val wi = "android.net.wifi.WifiInfo"
        hookMethodRet(wi, classLoader, "getMacAddress", "mac_address")
        hookMethodRet(wi, classLoader, "getBSSID", "mac_bssid")
        hookMethodRet(wi, classLoader, "getSSID", "mac_ssid")
        
        try {
            XposedHelpers.findAndHookMethod(wi, classLoader, "getIpAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val ip = getValue("ip_address")
                    if (ip.isNotEmpty()) {
                        val p = ip.split(".")
                        if (p.size == 4) {
                            try {
                                param.result = (p[3].toInt() shl 24) or (p[2].toInt() shl 16) or (p[1].toInt() shl 8) or p[0].toInt()
                            } catch (e: Throwable) {}
                        }
                    }
                }
            })
        } catch (e: Throwable) {}
    }

    // ========== v3.7.6: WifiManager DHCP Info Hook ==========
    // getDhcpInfo() leaks real gateway, DNS, server IP, and netmask from the actual
    // network connection. We spoof it to match our fake IP address.
internal fun XposedEntry.hookWifiDhcp(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("android.net.wifi.WifiManager", classLoader, "getDhcpInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val info = param.result ?: return
                        val ip = getValue("ip_address")
                        if (ip.isEmpty()) return
                        val parts = ip.split(".")
                        if (parts.size != 4) return
                        try {
                            val a = parts[0].toInt()
                            val b = parts[1].toInt()
                            val c = parts[2].toInt()
                            val d = parts[3].toInt()
                            val ipInt = (d shl 24) or (c shl 16) or (b shl 8) or a
                            val gwInt = (1 shl 24) or (c shl 16) or (b shl 8) or a
                            val dns = (8 shl 24) or (8 shl 16) or (8 shl 8) or 8
                            XposedHelpers.setIntField(info, "ipAddress", ipInt)
                            XposedHelpers.setIntField(info, "gateway", gwInt)
                            XposedHelpers.setIntField(info, "dns1", dns)
                            XposedHelpers.setIntField(info, "dns2", dns)
                            XposedHelpers.setIntField(info, "serverAddress", gwInt)
                            XposedHelpers.setIntField(info, "netmask", 0x00FFFFFF)
                        } catch (e: Throwable) {}
                    }
                })
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookNetworkInterface(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod("java.net.NetworkInterface", classLoader, "getHardwareAddress", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mac = getValue("mac_address")
                    if (mac.isNotEmpty() && mac.contains(":")) {
                        try {
                            val bytes = mac.split(":").map { it.toInt(16).toByte() }.toByteArray()
                            if (bytes.size == 6) {
                                param.result = bytes
                            }
                        } catch (e: Throwable) {}
                    }
                }
            })
        } catch (e: Throwable) {}
    }

internal fun XposedEntry.hookBluetooth(classLoader: ClassLoader) {
        hookMethodRet("android.bluetooth.BluetoothAdapter", classLoader, "getAddress", "bluetooth_mac")
        hookMethodRet("android.bluetooth.BluetoothAdapter", classLoader, "getName", "device_name")
    }

