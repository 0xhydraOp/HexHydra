package dev.hexhydra

import de.robv.android.xposed.XC_MethodHook

/**
 * Crash-proof hook base class.
 *
 * A hook callback that throws propagates the exception into the TARGET app's
 * call site — an unexpected state in one hook can kill a scoped app. SafeHook
 * swallows any Throwable from the callback body so the target always sees the
 * original (unhooked) behavior instead of a crash.
 *
 * Deliberate exception: hooks whose whole purpose is to throw into the target
 * (HooksStealth's Class.forName interceptor, which throws
 * ClassNotFoundException to mimic a clean device) must NOT use SafeHook.
 */
internal abstract class SafeHook : XC_MethodHook() {

    final override fun beforeHookedMethod(param: MethodHookParam) {
        try {
            onBefore(param)
        } catch (_: Throwable) {
            // Never let a spoof hook crash the scoped app.
        }
    }

    final override fun afterHookedMethod(param: MethodHookParam) {
        try {
            onAfter(param)
        } catch (_: Throwable) {
            // Never let a spoof hook crash the scoped app.
        }
    }

    protected open fun onBefore(param: MethodHookParam) {}
    protected open fun onAfter(param: MethodHookParam) {}
}
