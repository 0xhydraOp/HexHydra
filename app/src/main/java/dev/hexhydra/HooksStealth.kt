package dev.hexhydra

import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

internal fun XposedEntry.hookPackageManager(classLoader: ClassLoader) {
        if (getValue("setting_hide_self") != "true") return
        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader)
            val hook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val list = param.result as? List<*> ?: return
                    val newList = java.util.ArrayList<Any>()
                    for (item in list) {
                        if (item != null) {
                            try {
                                val pkgName = XposedHelpers.getObjectField(item, "packageName") as? String
                                if (pkgName != MODULE_PACKAGE) newList.add(item)
                            } catch (e: Throwable) {
                                newList.add(item)
                            }
                        }
                    }
                    param.result = newList
                }
            }
            XposedHelpers.findAndHookMethod(pmClass, "getInstalledApplications", Int::class.javaPrimitiveType!!, hook)
            XposedHelpers.findAndHookMethod(pmClass, "getInstalledPackages", Int::class.javaPrimitiveType!!, hook)
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    XposedHelpers.findAndHookMethod(pmClass, "getInstalledApplications",
                        android.content.pm.PackageManager.ApplicationInfoFlags::class.java, hook)
                } catch (e: Throwable) {}
                try {
                    XposedHelpers.findAndHookMethod(pmClass, "getInstalledPackages",
                        android.content.pm.PackageManager.PackageInfoFlags::class.java, hook)
                } catch (e: Throwable) {}
            }
        } catch (e: Throwable) {}
    }

    // ========== v3.7.5: Anti-Xposed Detection Hooks ==========
internal fun XposedEntry.hookAntiXposed(classLoader: ClassLoader) {
        // --- Hook 1: Class.forName() ---
        // Blocks app detection of Xposed/LSPosed without breaking LSPosed internals.
        //
        // Stack frame layout at our beforeHookedMethod callback:
        //   [0] = our hook method
        //   [1] = caller of our hook = LSPosed's hook dispatcher (org.lsposed.*)
        //   [2+] = original caller chain, ultimately reaching app code that
        //          invoked Class.forName
        //
        // The previous implementation walked the ENTIRE trace and returned
        // early on ANY LSPosed frame. Because the LSPosed dispatcher is always
        // on the stack when our callback fires, this hook effectively never
        // threw — apps probing for Xposed succeeded and Chamet's anti-tamper
        // detected us.
        //
        // The fix: check only the IMMEDIATE caller of our hook. If it's
        // LSPosed, let the call through (LSPosed is loading its own helpers).
        // If it's anything else (typically app code), throw
        // ClassNotFoundException so the app sees "class not found" — which is
        // what a clean device looks like.
        val forNameHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val className = param.args[0] as? String ?: return
                val lower = className.lowercase()
                if (!lower.contains("xposed") &&
                    !lower.contains("edxposed") &&
                    !lower.contains("lsposed")) return

                val trace = Thread.currentThread().stackTrace
                // Skip our own frame (index 0); index 1 is the immediate caller.
                val callerName = trace.getOrNull(1)?.className.orEmpty()
                val fromLSPosed = callerName.startsWith("org.lsposed") ||
                                  callerName.startsWith("de.robv.android.xposed")
                if (fromLSPosed) return  // LSPosed internals — allow.

                // App-side probe — block by throwing ClassNotFoundException,
                // the same response a non-hooked device would give.
                throw ClassNotFoundException(className)
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Class::class.java, "forName", String::class.java, forNameHook)
        } catch (e: Throwable) {}
        try {
            XposedHelpers.findAndHookMethod(Class::class.java, "forName", String::class.java,
                Boolean::class.javaPrimitiveType!!, ClassLoader::class.java, forNameHook)
        } catch (e: Throwable) {}

        // --- Hook 2: /proc filesystem filters ---
        // Filters /proc/self/maps (Xposed libraries), /proc/cpuinfo (CPU details),
        // and /proc/meminfo (RAM size) to prevent hardware fingerprinting.
        //
        // Critical: the returned byte count MUST match `bytesRead`. Returning a
        // smaller count (because we stripped lines) desyncs BufferedReader-based
        // consumers and crashes them with IOException / parse errors. We rewrite
        // filtered content in place, padding lines we remove with same-length
        // whitespace so line numbering is preserved.
        try {
            XposedHelpers.findAndHookMethod("java.io.FileInputStream", classLoader, "read",
                ByteArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val fis = param.thisObject
                            val path = XposedHelpers.getObjectField(fis, "path") as? String ?: return
                            if (!path.contains("/proc/")) return

                            val bytesRead = param.result as? Int ?: return
                            if (bytesRead <= 0) return

                            val buf = param.args[0] as ByteArray
                            val content = String(buf, 0, bytesRead, Charsets.UTF_8)

                            // Build replacement content of EXACTLY `bytesRead` chars.
                            val sb = StringBuilder(bytesRead)
                            val lines = content.split('\n')
                            for ((idx, line) in lines.withIndex()) {
                                val replacement = rewriteLine(path, line)
                                if (replacement.length == line.length) {
                                    sb.append(replacement)
                                } else if (replacement.length < line.length) {
                                    sb.append(replacement)
                                    repeat(line.length - replacement.length) { sb.append(' ') }
                                } else {
                                    sb.append(replacement.substring(0, line.length))
                                }
                                if (idx < lines.size - 1) sb.append('\n')
                            }

                            // If we somehow produced a different total length (e.g.,
                            // the original chunk didn't end on a line boundary),
                            // truncate or pad to keep bytesRead stable.
                            val newContent = sb.toString()
                            val safeLen = minOf(newContent.length, bytesRead)
                            System.arraycopy(newContent.toByteArray(Charsets.UTF_8), 0, buf, 0, safeLen)
                            if (safeLen < bytesRead) {
                                for (i in safeLen until bytesRead) buf[i] = ' '.code.toByte()
                            }
                            // ALWAYS return the original byte count so the caller's
                            // loop and any BufferedReader wrapper stay in sync.
                            param.result = bytesRead
                        } catch (_: Throwable) {}
                    }

                    // Returns the rewritten line for `path`. Same length as input
                    // wherever possible; callers pad/truncate to match.
                    private fun rewriteLine(path: String, line: String): String {
                        if (path.contains("maps")) {
                            val lower = line.lowercase()
                            if (lower.contains("xposed") ||
                                lower.contains("lsposed") ||
                                lower.contains("edxposed")) {
                                // Blank the line in-place: keep length, remove content.
                                return " ".repeat(line.length)
                            }
                            return line
                        }
                        if (path.contains("cpuinfo")) {
                            return when {
                                line.startsWith("Hardware") -> {
                                    val manufacturer = getValue("manufacturer")
                                    val cpuName = when {
                                        manufacturer.equals("Samsung", true) -> "Qualcomm Snapdragon 8 Gen 3"
                                        manufacturer.equals("Google", true) -> "Google Tensor G4"
                                        else -> "ARMv8 Processor rev 1 (v8l)"
                                    }
                                    "Hardware\t: $cpuName"
                                }
                                // Keep core count and all other lines untouched.
                                else -> line
                            }
                        }
                        if (path.contains("meminfo")) {
                            return when {
                                line.startsWith("MemTotal") -> "MemTotal:        8164000 kB"
                                line.startsWith("MemFree") -> "MemFree:          524000 kB"
                                line.startsWith("MemAvailable") -> "MemAvailable:    2800000 kB"
                                else -> line
                            }
                        }
                        return line
                    }
                })
        } catch (_: Throwable) {}
    }

