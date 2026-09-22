# Changelog

## v3.9.4 (2026-09-22)
- **Fix: long values (notably `user_agent`) now survive the prop bridge.**
  Android caps a property value at ~91 bytes; the generated UA (~137 chars)
  made `setprop hexhydra.user_agent` fail silently, and `hookUserAgent` then
  skipped installing (empty at install) — UA spoofing was dead end-to-end on
  ROMs without working XSharedPreferences. `Bridge.expandForProps()` splits
  long values into `key`/`key2`/`key3` chunks (80 chars) and
  `Bridge.reassembleSplitValue()` rejoins them on the module side
  (`user_agent2`/`user_agent3` added to `KNOWN_KEYS`); the zygote writer now
  reuses `Bridge.buildPushScript` so all writers agree.
- **Fix: UA hook reads live.** `hookUserAgent` no longer snapshots the UA at
  install and bails when empty; the `WebView.loadUrl` override reads
  `getValue("user_agent")` per call (`getDefaultUserAgent` was already live).
- **Fix: silent push failures surface.** `Bridge.pushToSystemProperties()`
  returns success (`su` found + `setprop` exit 0); Save toasts a warning when
  the push fails and `BootReceiver` logs it.
- **Tests:** new UA split/reassemble round-trip tests; generated-profile
  round-trip updated for chunked UA. All unit tests pass; debug APK builds.
- VersionCode: 69

## v3.9.3 (2026-09-22)
- **Fix: bare `su` in the cross-process bridge.** `Bridge.pushToSystemProperties()`
  and `XposedEntry.writeSharedFile()` invoked `su` by bare name and relied on
  PATH. On stock ROMs (confirmed on Nothing OS / Magisk 30.7) `su` exists only
  at `/system_ext/bin/su` and is absent from the app's PATH, so the Save-time
  property push and the boot-time re-push silently did nothing. A new
  `Bridge.locateSu()` probes the standard absolute paths (with a `sh -c` PATH
  fallback), and every caller — Bridge, `BootReceiver`, the Activity's
  root/soft-reboot path, and the zygote shared-file writer — now resolves `su`
  through it. Same bug class fixed for Soft Reboot in v3.9.2, now closed for
  the bridge.
- **Fix: sensor type-string corruption.** `hookSensors()` overwrote the
  sensor's `mStringType` (e.g. `android.sensor.accelerometer`) with the spoofed
  vendor name, corrupting `Sensor.getStringType()`. Vendor spoofing now writes
  `mVendor` only.
- **Refactor:** `Bridge.locateSu()` is the single su-resolution point; the
  duplicated root lookup in `MainActivity` was removed in favor of it.
- **Tests:** 41/41 unit tests pass; debug APK builds.
- VersionCode: 68

## v3.9.2 (2026-09-21)
- **Developer-tool polish (#4):** generated data is now brand-plausible and
  internally consistent.
- **Verified IMEI TAC pools:** new `IdentityData` (pure Kotlin, unit-testable)
  carries 107 real 8-digit TACs from the GSMA/IMEIDB allocations across 13
  manufacturers (samsung 9, google 14, oneplus 15, xiaomi 17, oppo 13,
  realme 13, sony 2, motorola 1, honor 1, nubia 1, fairphone 1, asus 4,
  nothing 16). `generateValidIMEI(manufacturer)` builds a structurally valid
  15-digit IMEI (TAC + 6-digit serial + Luhn check digit) from the matching
  pool. TACs may legitimately start with "0", so no leading-digit rewrite.
- **Verified MAC OUI pools:** `IdentityData.OUI_POOLS` holds 1,934 real IEEE
  OUIs (sourced from the nmap-mac-prefixes list) for the same manufacturers.
  `randomMac(manufacturer)` emits a unicast, globally-administered MAC whose
  first three octets are a real allocation for that brand; `mac_bssid` /
  `bluetooth_mac` derive from the same impression.
- **ProfileCoherence validator (#4):** new pure-Kotlin `ProfileCoherence`
  cross-checks every generated or user-entered profile for contradictions:
  brand↔manufacturer, model↔device_name, board↔codename, fingerprint format
  + prefix, IMEI TAC ∈ pool, MAC OUIs ∈ pool, battery scale (Samsung/Xiaomi/
  OPPO/Realme = 1000, else 100), MCC↔country, locale↔country, timezone↔
  country, mobile_no dial prefix↔country, IMSI↔network operator. Blank fields
  are always allowed. `FakeData.generateAll()` is guaranteed coherent.
- **Tests:** `ProfileCoherenceTest` (15 tests incl. 2,000-iteration coherence
  fuzz + 13 contradiction tests) and expanded `FakeDataTest` (Luhn +
  OUI-pool membership). 41/41 unit tests pass, debug APK builds.
- VersionCode: 67

## v3.9.2 (2026-09-21)
- **Risky-hook gate complete (#1):** hooks that can destabilize scoped apps with
  aggressive init-time checks are now fully gated behind the `safeMode` compat
  flag. Gated: `SystemProperties` get/set, `java.lang.System.getProperty`,
  `Locale`/`TimeZone`, `PackageManager` hiding, and deferred anti-Xposed checks.
  Safe read-only compat getters (`UserAgent`, `Location`/`Fused`/`Live`) stay
  hooked unconditionally.
- **Reboot persistence (#3):** saved config is re-pushed to `hexhydra.*`
  system properties on boot. New `BootReceiver` (BOOT_COMPLETED, exported)
  reads `hexhydra_prefs` and calls a new `Bridge.pushToSystemProperties()`
  — the same single-`su` chain of quote-escaped `setprop` calls the Save
  button uses, so values survive a device reboot without re-opening the app.
  Verified via root `am broadcast` of the receiver: all 51 props re-pushed,
  `hexhydra.refreshed` populated (was blank).
- Verified on device (Nothing A015, Android 15): build clean (30s), 14/14 unit
  tests pass, reinstall OK.
- VersionCode: 67

## v3.9.1 (2026-09-20)
- **Bug fix (visible symptom of v3.9.0):** `Randomize` still left the Device
  Profile card's IMEI / Android ID / WiFi MAC / Carrier rows showing `—`.
  Root cause: `refreshSummary()` looked for the detail rows under
  `summaryText.parent?.parent`, which is the header row — the detail rows are
  siblings one level higher. `findViewWithTag("detail_…")` never matched, so
  the card was never updated even though the summary line changed. Detail
  TextViews are now registered in a `detailViews` map and written directly;
  no more parent-chain guessing.
- **Bug fix:** brand printed twice for profiles whose `model` already contains
  the manufacturer ("OnePlus OnePlus 13R", "Xiaomi Xiaomi 15 Ultra"). Added
  `profileTitle()` and used it in both the Device Profile summary and the
  Profile History rows, so those render as "OnePlus 13R" / "Xiaomi 15 Ultra".
- Verified on device (Nothing A015, Android 15): Randomize populates all 41
  fields across all 6 groups; Save records history; values persist across a
  cold `force-stop` + relaunch. 14/14 unit tests pass.
- VersionCode: 66

## v3.9.0 (2026-09-20)
- UI fix: `Randomize All` no longer leaves fields blank in collapsed sections.
  Root cause: `populateFields()` was called only for sections that started expanded,
  so fields in groups 1-5 never made it into the `inputs` map. `Randomize All`
  looped over `inputs`, so it updated only the 11 fields of group 0. Now all
  sections are populated at build time; collapsed sections' EditTexts sit
  hidden until expanded.
- Removed the per-package skip list (`com.hkfuliao.chamet`). Module installs
  hooks for every scoped package without exception. Note: this means apps with
  Vector/LSPosed vs antsec-style HookBridge conflicts (e.g. Chamet
  `com.antsafe.sec.wrapper.MyApplication`) will now crash on scope. The
  workaround for those apps is to remove them from the LSPosed scope list,
  not to special-case them in code.

## v3.8.6-CHAMET-FIX (2026-09-19)
- Anti-crash: removed `Field.modifiers` reflection on `Build.VERSION.SDK_INT` (broken on ART 11+, could crash scoped apps). The `SystemProperties.getInt("ro.build.version.sdk")` hook remains the working path.
- Anti-crash: skip `Build.SERIAL` static write on Android 10+ (deprecated, guarded by `READ_PHONE_STATE`).
- Anti-crash: `/proc` filters (`maps`/`cpuinfo`/`meminfo`) now preserve the original `bytesRead`, replacing stripped lines with same-length whitespace so `BufferedReader` consumers don't desync.
- Anti-detection: `Class.forName` blocklist now throws `ClassNotFoundException` for app-side probes (was effectively a no-op because `org.lsposed.*` is always on the stack).
- Anti-leak: `hookAAID` now installs the `getId` hook once at setup time (was installing a fresh `XC_MethodHook` on every `getAdvertisingIdInfo()` call, accumulating unboundedly).
- Anti-crash: `hookContentResolverQueries` now matches the caller's projection and falls through to the original when the projection contains columns beyond `("key", "value")` (avoids `CursorIndexOutOfBoundsException`).
- Crash-safety: `hookMethodRet` callback body is wrapped in `try/catch` so unexpected target-app class state can't propagate.
- VersionCode: 58

## v3.8.5-FIX (2026-09-15)
- IMSI fix: new `imsi` field (generator + UI + validation); `getSubscriberId` remapped off `sim_serial`
- Consistency: city-based lat/lon per carrier, per-country phone lengths, `CHROME_VERSION` constant
- UI: field locks for Randomize, profile history (last 10) with restore, export/import via clipboard JSON
- Hook category toggles: 8 switches (device/network/location/stealth…), missing key defaults to enabled
- Refactor: XposedEntry split into 7 hook modules + shared helpers; sensor vendor mapping unified
- Tests: FieldValidators extracted (pure Kotlin) + FieldValidatorsTest; FakeDataTest covers IMSI/phone/geo
- CI: GitHub Actions workflow (assembleDebug + unit tests)
- VersionCode: 57

## v3.8.0-FINAL (2026-05-15)
- Final code review: 0 errors, 0 warnings, 0 crashes on scoped app
- Cross-process data bridge: setprop via su from main zygote to SystemProperties
- Both zygotes now read XSharedPreferences (42 values) — no more FakeData fallback
- Anti-Xposed Class.forName hook: stack-trace whitelist prevents LSPosed self-blocking
- SystemProperties write error log gated behind debugEnabled
- Secondary zygote no longer triggers root prompt (writeSharedFile guarded)
- VersionCode: 52

## v3.7.7-POLISH (2026-05-15)
- UI overhaul: accordion editor replaces spinner, all groups visible
- Human-readable field labels (battery_scale to Battery Scale)
- Device profile preview card with detail rows
- Status detection fixed: checks SystemProperties + SharedPreferences
- Improved button layout: full-width Randomize, side-by-side Save/Soft Reboot
- Button animations: crossfade on randomize, checkmark pulse on save
- "Fast Reboot" renamed to "Soft Reboot" with toast confirmation
- debugEnabled defaults to false (no log spam)
- getValue() polling guarded: won't overwrite user prefs with stale SystemProperties
- sdkVersionToInt: added Android 16 (SDK 36)
- hookSystemProperties: separate try-catch for get(String) fallback
- VersionCode: 50

## v3.7.6-SHIELD (2026-05-15)
- WifiManager.getDhcpInfo() hook: gateway, DNS, netmask, DHCP server spoofed
- PhoneStateListener hooks: onSignalStrengthsChanged blocked, onCellInfoChanged returns empty
- /proc filters expanded: /proc/cpuinfo (CPU name rewritten), /proc/meminfo (memory ~8GB)
- VersionCode: 49

## v3.7.5-HYDRA (2026-05-14)
- Device profiles: 42 Android 15 devices, Samsung trimmed from ~25 to 6
- Randomized hardware pools: 15 resolution combos x 7 GPU pairs per call
- Location constructor hook: catches ALL Location objects (String + copy constructor)
- FusedLocationProviderClient: returns pre-completed Task (fixes getResult() crash)
- Anti-Xposed detection: Class.forName block, /proc/self/maps filter
- Sensor spoofing: SensorManager vendor rewrite per manufacturer
- Battery scale manufacturer-specific (100 vs 1000)
- Fast randomize: direct EditText update, no view recreation
- VersionCode: 48

## v3.7.4-CRASH-SAFE (2026-05-13)
- Starting point: Xposed/LSPosed module with 41+ spoofed fields
- Removed sun.misc.Unsafe (native SIGSEGV risk on Android 15)
- Removed hookAllMethods on System (JVM instability)
- Patched LSPosed modules_config.db at byte offset 16250
- VersionCode: 47
