# Project Specification: HexHydra v3.8.5-FIX

## Project Overview
HexHydra is an advanced Android privacy tool implemented as an LSPosed/Xposed module. It allows users to intercept and spoof sensitive device identifiers at the system API level, protecting user privacy and enabling advanced anti-detection testing.

## Technical Architecture
- **Hooking Engine**: Xposed Framework (via LSPosed + Zygisk/Vector).
- **Primary Language**: Kotlin.
- **Xposed Lifecycle**:
  1. **Zygote Init**: `IXposedHookZygoteInit` initializes `XSharedPreferences`. Falls back to `FakeData.generateAll()` if empty.
  2. **Package Load**: `IXposedHookLoadPackage` installs hooks per scoped target.
  3. **Application Start**: `Instrumentation.callApplicationOnCreate` captures context, refetches config, reapplies static `Build` fields.
- **IPC Mechanism**: 4-strategy data pipeline:
  1. **SystemProperties** — Fastest bridge. Blocked on Android 15 (Permission denied).
  2. **XSharedPreferences** — LSPosed bridge. Confirmed working on Android 15.
  3. **ContentProvider** — Fallback. May be blocked by AppsFilter.
  4. **FakeData.generateAll()** — Ultimate fallback. Never returns empty.
- **Build System**: Gradle 8.10.2, JDK 17 Temurin.

## Implemented Spoofing Categories (45+ fields, 24+ hook categories)

### Device Identity
- **Build Fields**: Manufacturer, Model, Brand, Device, Product, Board, Hardware, Fingerprint, ID, Display, Tags, Type, User, Host, Bootloader
- **Build.VERSION**: RELEASE, SDK_INT (reflection final bypass + SystemProperties.getInt), CODENAME, INCREMENTAL
- **Serial**: via `Build.SERIAL` and SystemProperties

### Telephony / SIM / Carrier
- IMEI, MEID, IMSI (getSubscriberId), ICCID (SIM serial), phone number
- SIM operator name, network operator name, MCC/MNC, country ISO
- Subscription ID
- **🆕 PhoneStateListener**: `onSignalStrengthsChanged` blocked, `onCellInfoChanged` returns empty — no live cell tower leaks

### Network
- Wi-Fi MAC address, BSSID, SSID, IP address (all `WifiInfo` methods)
- Bluetooth MAC address, Bluetooth device name
- NetworkInterface.getHardwareAddress()
- **🆕 DHCP**: `WifiManager.getDhcpInfo()` — gateway, DNS, netmask, DHCP server all spoofed

### Location (3 paths)
- `LocationManager.getLastKnownLocation()` — legacy API
- `Location(String)` and `Location(Location)` constructors — catches ALL Location objects
- `FusedLocationProviderClient.getLastLocation()` — returns pre-completed Task (Google Play Services)

### Display & GPU
- `Display.getMetrics()` / `getRealMetrics()` — width, height, density, DPI
- `GLES20.glGetString(GL_VENDOR/GL_RENDERER)`

### Battery
- Level + manufacturer-specific scale (100 for Google/Nothing/Moto, 1000 for Samsung/Xiaomi/Oppo/Realme)
- Level computed proportionally to scale

### Android / Google IDs
- Android ID (Settings.Secure), GSF ID, AAID (Advertising ID), MediaDrm unique ID

### User-Agent
- `SystemProperties.get("http.agent")` — Android API
- `System.getProperty("http.agent")` — Java API (OkHttp, HttpURLConnection)
- `WebSettings.getDefaultUserAgent()`, `WebView.loadUrl()` UA injection

### Java System Properties
- `os.name` → "Linux", `os.version` → kernel version, `os.arch` → "aarch64"
- `java.vm.version`, `java.runtime.version`

### Settings / SharedPreferences
- `Settings.Secure/System/Global.getString()` — android_id, device_name, bluetooth_name
- `SharedPreferencesImpl.getString()` — market_name, device_name

### Profiles & Randomization
- **42 Android 15 devices** across 14 brands (Google, Samsung, OnePlus, Xiaomi, Nothing, Motorola, Asus, Sony, Oppo, Honor, Realme, Nubia, Fairphone)
- **Randomized hardware pools**: 15 resolution/density combos + 7 GPU vendor/renderer pairs per call
- **8 carrier profiles** with matching locale/timezone/country
- `FakeData.generateAll()` fallback — never returns empty

### Stealth / Anti-Detection
- **Self-hiding**: removes module from `getInstalledApplications/getInstalledPackages`
- **Class.forName block**: Xposed/LSPosed/EdXposed class lookups throw ClassNotFoundException
- **/proc filesystem filters**:
  - `/proc/self/maps` — strips Xposed/LSPosed library lines
  - `/proc/cpuinfo` — rewrites CPU hardware name per manufacturer (Snapdragon 8 Gen 3 / Tensor G4 / ARMv8)
  - `/proc/meminfo` — fakes memory to ~8GB total
- **Sensor spoofing**: `SensorManager.getSensorList/getDefaultSensor` rewrites vendor field per manufacturer (STMicro for Samsung, Bosch for Google, Qualcomm for others)
- **Locale/Timezone**: `Locale.getDefault()`, `TimeZone.getDefault()`

### UI
- Programmatic layout (no XML layouts)
- Per-group field editor with spinner
- **Fast randomize**: direct EditText update — near-instant response
- Soft reboot button (`killall system_server`)
- Debug logging toggle, self-hiding toggle

## Build Status
- **Current Version**: v3.8.5-FIX
- **Version Code**: `57`
- **Last Build**: 2026-05-14 — BUILD SUCCESSFUL, 0 errors, 1 minor warning (unchecked Consumer cast)
- **APKs**: `app/build/outputs/apk/debug/app-debug.apk` / `app/build/outputs/apk/release/app-release.apk`

## Device Verification — 2026-05-14
- **Device**: Android 15 (SDK 35), Nothing A015
- **LSPosed**: v2.0 (Zygisk/Vector)
- **Target**: `com.kbntqdcg.wewx2762.mfujp` (8jjbet) — CONFIRMED HOOKING ✅
- **Module Loaded**: ✅ `initZygote complete, 42 values cached`
- **LSPosed DB**: Patched after APK upgrade (stale path → new path)
- **Crashes**: 0 — crash-safe (no Unsafe, no hookAllMethods, all hooks in try-catch)

## Version History

### v3.8.5-FIX — IMSI + consistency + locks/history/toggles + hook-module split
- `getSubscriberId` serves a dedicated `imsi` value (was: SIM serial duplicate); IMSI generated as MCC+MNC+MSIN
- City-based lat/lon per carrier, per-country phone lengths, `CHROME_VERSION` constant in UA
- Field locks, profile history (10) with restore, export/import via clipboard JSON, 8 hook-category toggles
- XposedEntry split into Hooks{Device,Telephony,Network,Ids,Location,Hardware,Stealth}.kt; unified sensor vendors
- FieldValidators extracted + unit-tested; CI workflow runs assembleDebug + unit tests

### v3.7.6-SHIELD — DHCP spoofing + PhoneStateListener + /proc filters
- **WifiManager.getDhcpInfo()** hook: gateway, DNS, netmask, DHCP server all spoofed
- **PhoneStateListener hooks**: `onSignalStrengthsChanged` blocked, `onCellInfoChanged` returns empty list
- **/proc filters expanded**: `/proc/cpuinfo` (CPU name rewritten), `/proc/meminfo` (memory faked to 8GB)
- VersionCode bumped to 49

### v3.7.5-HYDRA — Hardware randomization + Anti-detection + Sensors + Location overhaul
- Randomized hardware pools (15 resolution × 7 GPU combos) — no fixed device-to-hardware mapping
- Location constructor hook catches all paths (`Location(String)` + `Location(Location)`)
- FusedLocationProviderClient returns pre-completed Task (fixes getResult() crash on incomplete Task)
- Anti-Xposed: `Class.forName` block + `/proc/self/maps` filter
- Sensor vendor spoofing via `SensorManager` hooks
- Battery scale manufacturer-specific (100 vs 1000)
- Device profiles: 42 Android 15 devices, Samsung reduced from ~25 to 6
- Fast randomize: direct EditText update

### v3.7.4-CRASH-SAFE — Crash-safe & DB patch
- Removed `sun.misc.Unsafe` (native SIGSEGV risk on Android 15)
- Removed `hookAllMethods` on `System` (JVM instability)
- Patched LSPosed `modules_config.db` at byte offset 16250 (stale APK path after upgrade)

### v3.7.3-SDK-INT-V3 — Build.getInt + SystemProperties.getInt hooks
### v3.7.2-CLIENT-UA-FIX — Java System.getProperty("http.agent") hook
### v3.7.1 — Android version mismatch fix (reflection SDK_INT)
### v3.7.0 — 4-strategy IPC pipeline with FakeData fallback

## Known Limitations (Android 15)
- `SystemProperties.set()` from zygote blocked (Permission denied) — falls back to XSharedPreferences
- `Build.VERSION.SDK_INT` = `static final int` — compiled-in constants in app bytecode cannot be fully redirected
- Apps using native code or `/proc` filesystem directly cannot be hooked via Xposed
- Some hooks may not apply if the app loads classes with a custom classloader
- FusedLocationProviderClient hook requires Google Play Services (silently skipped on GMS-free devices)
