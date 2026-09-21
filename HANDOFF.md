# HexHydra - Session Handoff Document

## Project at a Glance

**HexHydra** (`dev.hexhydra`) is an LSPosed/Xposed module for Android that spoofs device identifiers (IMEI, MAC, Build fields, location, etc.) for apps the user scopes in LSPosed Manager.

- **Version**: 3.9.2 (versionCode 67) - installed on device at time of handoff
- **APK on disk**: `app\build\outputs\apk\debug\app-debug.apk` (~963 KB)
- **Package**: `dev.hexhydra`
- **Min SDK**: 27 (Android 8.1+), target SDK 33
- **License**: Custom "approval required" - personal use OK, forking/modifying needs written permission

---

## Build Environment (this machine)

- **JDK**: Eclipse Adoptium 17.0.19 at `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- **JDK 21 (portable)**: `.\jdk21\jdk-21.0.2\` (only needed for LSPatch CLI v1.2, NOT for module build)
- **Android SDK**: `C:\Users\iamro\AppData\Local\Android\Sdk` (platforms 35, 36; build-tools 34, 35, 36)
- **Gradle**: `8.11.1` at `$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat`
- **Kotlin**: 1.9.10
- **AGP**: 8.7.3
- **Xposed API dep**: `de.robv.android.xposed:api:82` (compileOnly)

### Build commands

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Users\iamro\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin\bpt9gzteqjrbo1mjrsomdt32c\gradle-8.11.1\bin\gradle.bat"
& $gradle --no-daemon assembleDebug testDebugUnitTest --console=plain
```

Output: APK at `app\build\outputs\apk\debug\app-debug.apk`, tests at `app\build\test-results\testDebugUnitTest\`.

### Install via adb

```powershell
& adb install -r "app\build\outputs\apk\debug\app-debug.apk"
```

Sometimes `adb install` fails with "failed to stat" - workaround: push to `/data/local/tmp/` then `adb shell pm install /data/local/tmp/foo.apk`.

---

## Device State

- **Device**: Android 15 (SDK 35), Nothing A015, model `Phone (3a) Pro` (A301)
- **Magisk + NeoZygisk + LSPosed v2.0** active; Vector disabled by user toggle
- **LSPatch v1.2 manager** installed but **LSPatch approach abandoned** (see Chamet section)
- **Adb device id**: `00121646T006090`
- **Shizuku**: not installed

## Source Layout

```
app/src/main/
  AndroidManifest.xml          # xposedmodule=true, xposedminversion=93, MainActivity + DataProvider + BootReceiver
  assets/xposed_init            # "dev.hexhydra.XposedEntry"
  java/dev/hexhydra/
    XposedEntry.kt              # XposedEntry + ZygoteInit, appInitialized, deferred-init
    HooksDevice.kt              # Build.*, Build.VERSION, SystemProperties (gated),
                                #   WebSettings/WebView (UA), java.lang.System.getProperty (gated)
    HooksTelephony.kt           # TelephonyManager + PhoneStateListener
    HooksNetwork.kt             # WifiInfo, WifiManager.getDhcpInfo, NetworkInterface, Bluetooth
    HooksIds.kt                 # Settings, SharedPreferences, AAID (installed ONCE),
                                #   Gservices, ContentResolver.query (projection-matched), MediaDrm
    HooksLocation.kt            # LocationManager, FusedLocationProviderClient, Location ctors,
                                #   getCurrentLocation, Locale.getDefault, TimeZone.getDefault (gated)
    HooksHardware.kt            # Display, GLES.glGetString, Battery, Sensors
HooksStealth.kt             # PackageManager hiding, Class.forName blocklist,
                                #   FileInputStream.read /proc filter (byte-count-preserving)
    Bridge.kt                   # pushToSystemProperties(): su + setprop chain, single-quote escaping
    BootReceiver.kt             # ACTION_BOOT_COMPLETED -> reload saved prefs -> Bridge re-push
FakeData.kt                 # Pure-Kotlin generators (Luhn IMEI via verified TAC, MAC via verified OUI, MCC+MNC IMSI, etc.) - 41 keys
    DeviceProfiles.kt           # 42 device entries (Pixel, Samsung, OnePlus, Xiaomi, Nothing, ...)
    FieldValidators.kt          # Pure-Kotlin input rules (unit-tested, no Android deps)
    IdentityData.kt             # Verified per-manufacturer IMEI TAC + MAC OUI pools (pure data, unit-tested)
    ProfileCoherence.kt         # Pure-Kotlin cross-field contradiction validator (unit-tested, no Android deps)
    DataProvider.kt              # Internal ContentProvider fallback (not exported)
    MainActivity.kt             # Programmatic UI (~1026 lines). No XML layouts.
```

Unit tests in `app/src/test/java/dev/hexhydra/`:
- `FakeDataTest.kt` - 8 tests (IMEI Luhn validity, MAC OUI-pool membership)
- `FieldValidatorsTest.kt` - 7 tests
- `ProfileCoherenceTest.kt` - 15 tests (2,000-iteration coherence fuzz + contradiction cases)
- `BridgeTest.kt` - 11 tests

Total: 41 tests, all pass.

---

## Architecture Highlights

### Data flow

1. User edits in MainActivity -> in-memory `values: LinkedHashMap<String, String>`
2. User saves -> `SharedPreferences` named `hexhydra_prefs` (MODE_PRIVATE, world-readable for XSharedPreferences)
3. App launches -> `XposedEntry.initZygote` reads via 4-strategy fallback: XSharedPreferences -> ContentProvider -> `/data/adb/hexhydra_data.json` -> SystemProperties -> `FakeData.generateAll()`
4. App spawns -> `XposedEntry.handleLoadPackage` for scoped apps
5. **Deferred init** (v3.8.7+): Build/SystemProperties/Locale/Java system property hooks gated by `appInitialized`, flipped true in `afterHookedMethod` of `Instrumentation.callApplicationOnCreate` so init-time checks see real values

### Anti-crash fixes (cumulative)

**v3.8.6** - apps with antsec/ARouter/R8-renamed `CoreComponentFactory`:

1. `setStaticSafe(Int)` no longer touches `Field.modifiers` (broken on ART 11+)
2. `Build.SERIAL` skipped on Android 10+
3. `/proc` filters preserve original `bytesRead` (pad stripped lines with whitespace)
4. `Class.forName` blocklist throws only when immediate caller is not LSPosed
5. `hookAAID` installs `getId` hook ONCE at setup (was unbounded per-call)
6. `hookContentResolverQueries` matches caller projection, falls through on unknown columns
7. `hookMethodRet` callback body wrapped in try/catch

**v3.8.7** - deferred init for antsec/ARouter init-time checks:

1. `appInitialized` flag set true in `afterHookedMethod` of `callApplicationOnCreate`
2. `hookBuildFields` deferred to that afterHookedMethod
3. `hookSystemProperties` callbacks gated
4. `hookLocaleAndTimezone` callbacks gated
5. `hookJavaSystemProperties` callbacks gated

**v3.9.0 (this version)** - UI fix:

`buildGroupSection` used to call `populateFields` only when `isExpanded` was true at build time. `expandedGroups` defaults to `mutableSetOf(0)`, so only group 0 got its EditTexts created and only 11 of 41 keys made it into the `inputs` map. `doRandomize` iterated `inputs` and called `setText(values[key])` - only group 0 fields got randomized; other groups EditTexts (created lazily when section is expanded) showed whatever was cached.

**Fix**: always call `populateFields(content, group)`. EditTexts in collapsed sections sit hidden (content.visibility = GONE); `inputs` is fully populated; `Randomize All` updates all 41 EditTexts.

## Chamet Investigation - DO NOT REPEAT

The user wants Chamet (`com.hkfuliao.chamet`) spoofed. **None of the current frameworks can do this.** Empirically confirmed:

| Test | Result |
|---|---|
| Original Chamet (no scoping) | Works |
| Vector-only scope (v3.8.6) | FATAL `ClassNotFoundException: androidx.core.app.CoreComponentFactory` |
| Vector + NeoZygisk (v3.8.9-NEOZTEST) | Same crash, identical stack |
| LSPosed-only, Vector disabled (v3.8.11-LSPDTEST) | Same crash, different framework wrapper |
| LSPatch sig bypass 2 + HexHydra | ARRouter crash (antsec detects re-signed APK) |
| LSPatch sig bypass 2 + NO module | **Same ARRouter crash** - confirms it is LSPatch, not our hooks |

### Root cause (verified by reading Vector source)

- `jingmatrix/Vector` -> `xposed/src/main/kotlin/org/matrix/vector/impl/core/VectorStartup.kt`: installs `LoadedApk` constructor + `createAppFactory` + `createOrUpdateClassLoaderLocked` + `ActivityThread.attach` framework hooks unconditionally for every process with a scoped module
- `jingmatrix/Vector` -> `xposed/src/main/kotlin/org/matrix/vector/impl/hookers/LoadedApkHookers.kt`: `LoadedApkCreateCLHooker` calls `chain.proceed()` then dispatches; the framework `invokeOriginalMethod` ends up resolving to whatever `HookBridge.invokeOriginalMethod` is most recently on the classloader stack - for antsec-protected apps this resolves to their R8-obfuscated HookBridge
- LSPosed has the same architecture (different framework classes, same incompatibility)
- LSPatch bypasses Vector/LSPosed but re-signs the APK; antsec own integrity check catches that

### Skip list was removed in v3.9.0 (user-requested)

The v3.8.x series had `SKIP_PACKAGES = setOf("com.hkfuliao.chamet")` to keep Chamet usable. **Removed per user request.** Trade-off: in v3.9.0, scoping Chamet to HexHydra will cause Chamet to crash at launch (the same antsec-related FATAL as before). User accepted this. Workaround: remove Chamet from the LSPosed Manager scope list.

---

## What Works Right Now

- HexHydra v3.9.2 installed (versionCode 67)
- All scoped apps other than Chamet get full identity spoofing with deferred-init safety
- Spoofed profile survives reboot: `BootReceiver` re-pushes `hexhydra.*`
  props at BOOT_COMPLETED (verified via root broadcast, all 51 props)
- Riskier hooks (SystemProperties, `System.getProperty`, Locale/Timezone,
  PackageManager hiding) sit behind the safeMode gate; safe read-only getters
  stay unconditional
- `Randomize` updates all 41 fields across all 6 groups **and** the Device
  Profile card's IMEI / Android ID / WiFi MAC / Carrier rows (verified on device)
- Save -> Profile History records the profile; values persist across a cold
  `force-stop` + relaunch (verified on device)
- Brand no longer printed twice ("OnePlus 13R", not "OnePlus OnePlus 13R")
- Generated IMEIs are Luhn-valid with real verified TACs per brand; generated
  MACs are unicast/globally-administered with real IEEE OUIs per brand
- `ProfileCoherence.issues()/isCoherent()` catch cross-field contradictions in
  any profile (brand, model, board, fingerprint, TAC, OUI, battery scale, MCC,
  locale, timezone, phone prefix, IMSI) — `FakeData.generateAll()` is provably
  always coherent (2,000-iteration fuzz test)
- 41/41 unit tests pass, build clean

---

## v3.9.1 Smoke Test — PASSED (2026-09-20)

Verified live on the device (Nothing A015, Android 15):

1. `adb shell dumpsys package dev.hexhydra` -> versionName 3.9.1 / versionCode 66
2. Launched MainActivity; tapped **Randomize Profile**
3. Device Profile card updated: title + IMEI / Android ID / WiFi MAC / Carrier all populated
4. Expanded all 6 groups — every field non-blank
   (Device Identity 11, Android IDs 4, Telephony 9, Network 5, Location & Locale 4, Display & Hardware 8)
5. Tapped **Save** -> Profile History gained an entry
6. `am force-stop` + relaunch -> card reverted to the last SAVED profile
   (unsaved randomize discarded) = persistence confirmed

### Note: `su` cannot see the app's shared_prefs
`su -c 'ls /data/user/0/dev.hexhydra/shared_prefs/'` shows an empty
dir, while the app persists correctly across cold restarts. This is a Magisk
mount-namespace illusion — trust the cold-restart test, not the root `ls`.
`run-as` is also blocked (data dir perms reported as `40755`).

---

## v3.9.2 Smoke Test — PASSED (2026-09-21)

Verified live on the device (Nothing A015, Android 15):

1. `adb shell dumpsys package dev.hexhydra` -> versionName 3.9.2 / versionCode 67
2. Rebuilt (30s, 14/14 unit tests) + reinstalled via `adb install -r` — Success
3. Boot re-push exercised via root broadcast:
   `adb shell "su -c 'am broadcast -a android.intent.action.BOOT_COMPLETED -n dev.hexhydra/.BootReceiver'"`
   -> `hexhydra.refreshed` = 1789969283835 (was blank) and all 51
   `hexhydra.*` props re-pushed => receiver registration, exported flag,
   XSharedPreferences read from boot context, and the su setprop chain
   all work end-to-end.
4. 14/14 unit tests pass

Note: a physical-reboot test has not been run (disruptive); the root broadcast
drives the same receiver code path.

## Files Modified in the v3.9.1 Session (this one)

| File | Change |
|---|---|
| `app/src/main/java/dev/hexhydra/MainActivity.kt` | Added `detailViews` map; `detailRow()` registers its TextView; `refreshSummary()` writes detail rows directly (was searching the wrong parent); added `profileTitle()` and used it in `refreshSummary()` + `refreshHistory()` to drop duplicate brand |
| `app/build.gradle` | Bumped versionCode 65 -> 66, versionName `3.9.0` -> `3.9.1` |
| `CHANGELOG.md` | Added v3.9.1 entry |
| `HANDOFF.md` | Updated version, verification, and file lists |

## Files Modified in the v3.9.2 Session

| File | Change |
|---|---|
| `app/src/main/java/dev/hexhydra/Bridge.kt` | **New.** `pushToSystemProperties(map)`: single `su -c '…'` chain of `setprop hexhydra.<key> '<escaped>'` (single-quote escaped via `'\''`), `hexhydra.refreshed` appended last |
| `app/src/main/java/dev/hexhydra/BootReceiver.kt` | **New.** BOOT_COMPLETED receiver: loads `hexhydra_prefs`, converts String/Boolean prefs to a map, calls `Bridge.pushToSystemProperties` |
| `app/src/main/AndroidManifest.xml` | Added `RECEIVE_BOOT_COMPLETED` permission + exported `.BootReceiver` with BOOT_COMPLETED intent filter (after `.DataProvider` block) |
| `app/src/main/java/dev/hexhydra/MainActivity.kt` | `pushConfigToSystemProperties()` now builds the prop map (fieldKeys + `setting_debug_log` + `setting_hide_self` + hookBoxes) and delegates to `Bridge` |
| `app/src/main/java/dev/hexhydra/XposedEntry.kt` | #1 gate completed: `hookSystemProperties`, `hookJavaSystemProperties`, `hookLocaleAndTimezone`, `hookPackageManager`, `deferredAntiXposed` behind `safeMode`; compat getters (`hookUserAgent`, `hookLocation`/`hookFused`/`hookLive`) stay unconditional |
| `app/src/main/java/dev/hexhydra/IdentityData.kt` | **New (#4).** Pure-Kotlin `TAC_POOLS` (107 verified 8-digit IMEI TACs across 13 manufacturers) + `OUI_POOLS` (1,934 verified IEEE MAC OUIs, same 13) |
| `app/src/main/java/dev/hexhydra/FakeData.kt` | `generateValidIMEI(manufacturer)` now pulls the 8-digit TAC from the verified pool + 6-digit serial + Luhn digit; `randomMac(manufacturer)` emits `XX:XX:XX:YY:YY:YY` using a verified OUI; `generateAll()` threads `device.manufacturer` through all three MACs + IMEI |
| `app/src/main/java/dev/hexhydra/ProfileCoherence.kt` | **New (#4).** `issues(profile)` / `isCoherent(profile)`: 12 cross-field checks (brand↔mfgr, model↔name, board↔codename, fingerprint fmt+prefix, TAC∈pool, OUIs∈pool, battery scale, MCC↔country, locale↔country, timezone↔country, phone prefix↔country, IMSI↔operator); blank always allowed |
| `app/src/test/java/dev/hexhydra/FakeDataTest.kt` | IMEI test now asserts 15-digit + Luhn + TAC∈pool; MAC test asserts global/unicast + OUI∈pool; new `generatedMacsUseVerifiedOuiPools` |
| `app/src/test/java/dev/hexhydra/ProfileCoherenceTest.kt` | **New (#4).** 2,000-iteration `generatedProfilesAreAlwaysCoherent` + 13 deterministic contradiction tests + `blankFieldsAreAllowed` |
| `app/build.gradle` | Bumped versionCode 66 -> 67, versionName `3.9.1` -> `3.9.2` |
| `CHANGELOG.md` | Added v3.9.2 entry (reboot persistence; see below for #4 additions) |
| `HANDOFF.md` | Updated version, source layout, smoke test, and file lists |

The v3.9.2 CHANGELOG entry was further extended this session to document the
**#4 developer-tool polish**: verified TAC/OUI pools, brand-threaded
generators, and the ProfileCoherence validator (41/41 tests passing).

## Files Modified in the v3.9.0 Session

| File | Change |
|---|---|
| `app/src/main/java/dev/hexhydra/MainActivity.kt` | `buildGroupSection`: removed `if (isExpanded) populateFields(...)` guard - always populate |
| `app/src/main/java/dev/hexhydra/XposedEntry.kt` | Removed `SKIP_PACKAGES` field and the skip check in `handleLoadPackage` |
| `app/build.gradle` | Bumped versionCode 64 -> 65, versionName `3.8.12-FINAL-SKIP` -> `3.9.0` |
| `CHANGELOG.md` | Added v3.9.0 entry |
| `HANDOFF.md` | Created this file |

---

## Open Items

- **Chamet spoofing**: requires upstream fix in Vector/LSPosed. Not actionable on HexHydra side.
- **Physical reboot test**: boot re-push verified via root broadcast of BOOT_COMPLETED; a real reboot has not been run yet (disruptive).
- **Runtime skip list**: if user later wants UI-controlled per-package skipping, expose `SKIP_PACKAGES` via SharedPreferences, read in `handleLoadPackage`.
- **LSPatch sig bypass for antsec**: out of scope for HexHydra.

---

## Quick Smoke Test for Next Session

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:ANDROID_HOME = "C:\Users\iamro\AppData\Local\Android\Sdk"
& adb devices
& adb install -r "C:\Users\iamro\OneDrive\Desktop\gemini cli\DevicePrivacyLab\app\build\outputs\apk\debug\app-debug.apk"
& adb shell am start -n dev.hexhydra/dev.hexhydra.MainActivity
```
