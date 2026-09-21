# HexHydra

[![Build](https://github.com/0xhydraOp/HexHydra/actions/workflows/build.yml/badge.svg)](https://github.com/0xhydraOp/HexHydra/actions/workflows/build.yml)
[![Version](https://img.shields.io/badge/version-v3.9.2-blue)](https://github.com/0xhydraOp/HexHydra/releases/tag/v3.9.2)
[![Platform](https://img.shields.io/badge/platform-Android%2015%20%7C%20LSPosed-green)]()
[![License](https://img.shields.io/badge/license-approval--required-lightgrey)](LICENSE)

**HexHydra** is an [LSPosed](https://github.com/LSPosed/LSPosed)/Xposed module that presents a fully synthetic device identity to scoped apps. It intercepts **41 device identifiers** at the Android framework layer and serves them from a single, user-controlled profile — internally consistent across telephony, network, locale, and hardware surfaces.

> **Scope note.** HexHydra operates at the Java framework layer. It defeats ordinary apps and SDKs that read identity through public Android APIs. It does **not** spoof native-layer reads or hardware-backed attestation — see [Known limitations](#known-limitations).

---

## Contents

- [How it works](#how-it-works)
- [Spoofing coverage](#spoofing-coverage)
- [Coherence guarantee](#coherence-guarantee)
- [App features](#app-features)
- [Requirements](#requirements)
- [Building](#building)
- [Installation & usage](#installation--usage)
- [Verifying it works](#verifying-it-works)
- [Configuration reference](#configuration-reference)
- [Project structure](#project-structure)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)
- [Signing & secrets](#signing--secrets)
- [Versioning](#versioning)
- [License](#license)

---

## How it works

```
┌────────────────┐   ✏️ save    ┌────────────────────────┐
│  HexHydra   │ ───────────▶ │ SharedPreferences     │
│  UI            │              │ hexhydra_prefs    │
└────────────────┘              │ (world-readable for   │
                                │  XSharedPreferences)  │
                                └───────────┬────────────┘
                                            │ ① read at zygote init
                                            ▼
                          ┌────────────────────────────┐
                          │  XposedEntry.initZygote    │────▶ ③ setprop bridge
                          │  4-strategy config read    │      (hexhydra.*,
                          │                            │       via root su)
                          └───────────┬────────────────┘
                                      │ ② scoped app spawns
                                      ▼
                          ┌────────────────────────────┐
                          │  Handle-load chain         │
                          │  fetch order per value:    │
                          │  1. XSharedPreferences     │
                          │  2. ContentProvider        │
                          │  3. Shared JSON file       │
                          │  4. SystemProperties       │
                          │  5. FakeData (never empty) │
                          └────────────────────────────┘
```

**Design pillars**

- **One global profile, every scoped process.** All scoped apps observe the same identity by design — there are no per-app profiles.
- **Missing key = hook enabled.** The eight hook-category toggles default to *on*, so older configs upgrade without silently losing coverage.
- **Never-empty guarantee.** If every bridge fails, the hook layer serves freshly generated data rather than leaking real values.
- **Crash-safe by construction.** No `sun.misc.Unsafe`, no `hookAllMethods`, and every hook install is individually guarded — one failing hook never takes down an app.
- **Persistence across reboots.** Saved config is re-pushed to the `hexhydra.*` system properties at `BOOT_COMPLETED`, so spoofed values survive a reboot without reopening the app.

## Spoofing coverage

| Category | What's spoofed |
|---|---|
| Device identity | `Build` fields (manufacturer → bootloader), `VERSION.RELEASE`/`SDK_INT`/`CODENAME`/`INCREMENTAL`, serial, fingerprint |
| Telephony / SIM | IMEI, MEID, **IMSI** (dedicated value, not a SIM-serial alias), ICCID, line number, SIM/network operator + name, MCC/MNC, country ISO; `PhoneStateListener` signal/cell callbacks neutralized |
| Network | Wi-Fi MAC/BSSID/SSID/IP, DHCP (gateway, DNS, netmask, server), Bluetooth MAC/name, `NetworkInterface` hardware address |
| Location | `LocationManager`, `Location` constructors (all code paths), Fused provider (pre-completed `Task`), locale + timezone |
| Display & GPU | Resolution, density/DPI, `GL_VENDOR`/`GL_RENDERER` (randomized pool, no fixed device→GPU mapping) |
| Battery | Level scaled to the manufacturer-specific scale (100 vs 1000) |
| Android IDs | Android ID, GSF ID, advertising ID (AAID), MediaDrm device-unique ID |
| User-Agent | `WebSettings`, `WebView.loadUrl` injection, `SystemProperties` + `System.getProperty("http.agent")`, `os.*` / JVM properties |
| Anti-detection | `Class.forName` blocks for Xposed/LSPosed lookups (stack-trace whitelisted so LSPosed itself keeps working), `/proc/{self/maps,cpuinfo,meminfo}` filters, per-manufacturer sensor vendors, self-hiding from package lists |

**Identity data.** 42 Android 15 device profiles across 14 brands; 8 carrier profiles, each binding MCC/MNC ↔ locale ↔ timezone ↔ city-centred geo ↔ a valid national number length; 15 resolution × 7 GPU hardware pools.

**Brand-authentic identifiers.** IMEI and MAC generation draw on **verified allocation pools** — 107 real 8-digit IMEI TACs and 1,934 real IEEE MAC OUIs across 13 manufacturers (samsung, google, oneplus, xiaomi, oppo, realme, sony, motorola, honor, nubia, fairphone, asus, nothing). The first digits of an IMEI and the first half of a MAC therefore match the brand they claim to belong to.

## Coherence guarantee

Values are not just random — they are **mutually consistent**. `FakeData.generateAll()` threads a carrier and a device profile end-to-end, and the `ProfileCoherence` validator cross-checks every generated or hand-edited profile for contradictions:

| Check | Rule |
|---|---|
| Brand ↔ manufacturer | Must match (substring-insensitive) |
| Model ↔ device name | May differ, but both come from the device entry |
| Board ↔ codename | `board` = lowercased, sanitized codename |
| Fingerprint | Must start `manufacturer/product/device:` and parse |
| IMEI | 15 digits, valid Luhn check digit, TAC ∈ verified brand pool |
| MAC / BSSID / BT MAC | Unicast, globally-administered, OUI ∈ verified brand pool |
| Battery scale | `1000` for Samsung/Xiaomi/OPPO/Realme, otherwise `100` |
| MCC ↔ country | `network_operator` prefix maps to `country_iso` |
| Locale ↔ country | Locale country must match `country_iso` |
| Timezone ↔ country | TZ region must match `country_iso` |
| Phone number | Dial prefix must match `country_iso` |
| IMSI ↔ operator | `imsi` must start with `network_operator` |

Blank fields are always allowed. Unknown manufacturers gracefully fall back to the flattened pool so output stays plausible. A 2,000-iteration fuzz test proves generated profiles pass every check every time.

## App features

- **Field editor** — every value viewable and editable, with search, inline validation (IMEI/IMSI/MAC/IP/geo/ID formats), and per-field keyboards.
- **Field locks** — locked fields survive profile randomization.
- **Profile history** — last 10 saved profiles with one-tap restore; export/import via clipboard JSON.
- **Hook toggles** — eight category switches (device, telephony, network, location, display, IDs, UA, stealth). Takes effect on target-app restart.
- **Status badge** — live module-active detection, refreshed on resume.
- **Dark mode** — follows the system theme throughout.

## Requirements

| Component | Version |
|---|---|
| JDK | 17 (Temurin recommended) |
| Android SDK | compileSdk 35 (`platforms;android-35`, `build-tools;35.0.0`) |
| Gradle | 8.10+ (8.11.1 verified locally; CI pins 8.10.2) |
| Kotlin / AGP | 1.9.10 / 8.7.3 |
| Target device | Rooted Android 15 with LSPosed (Zygisk build) |
| Xposed API | 82 (`compileOnly`) |

`minSdk 27`, `targetSdk 33`. R8/minification is off — the module relies on reflection.

## Building

```powershell
# set JAVA_HOME to a JDK 17 and ANDROID_HOME to your SDK, then:
cd DevicePrivacyLab
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
& C:\path\to\gradle-8.11.1\bin\gradle.bat assembleDebug
```

| Command | Output |
|---|---|
| `gradle assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| `gradle assembleRelease` | `app/build/outputs/apk/release/app-release.apk` (needs signing — see below) |
| `gradle testDebugUnitTest` | 41 unit tests (validators, generators, coherence fuzz, bridge) |

CI runs `assembleDebug` + the full unit-test suite on every push and PR (`.github/workflows/build.yml`).

## Installation & usage

1. Install LSPosed (Zygisk build) on a rooted device.
2. Install the APK — `adb install app-debug.apk` for testing.
3. In LSPosed Manager, enable HexHydra and **scope your target apps**.
4. Open HexHydra → **Randomize** (or edit fields directly) → **Save**.
5. Restart the target app (or use **Soft Reboot** from the app, root required).

> After an APK upgrade, re-check the LSPosed scope — Android assigns a new install path and LSPosed may need the module re-enabled and re-scoped.

## Verifying it works

1. Scope a device-info app (e.g. *Device Info*) in LSPosed.
2. In HexHydra, randomize + save; note the IMEI / IMSI / Android ID shown.
3. Open the scoped app — it must show **your** values, and IMSI must differ from the SIM serial.
4. The status badge must read **MODULE ACTIVE**.

## Configuration reference

All settings live in `hexhydra_prefs` (plus `hexhydra_history` for snapshots — kept out of the hook data path):

| Key family | Example | Notes |
|---|---|---|
| Identity fields | `imei`, `imsi`, `android_id`, `mac_address`, … | Blank = fall back to generated data |
| `setting_debug_log` | `true` / `false` | Xposed log spam; default off |
| `setting_hide_self` | `true` / `false` | Hide module from package lists; default on |
| `hook_*` | `hook_telephony`, `hook_stealth`, … (8 keys) | Missing key = enabled |
| `locked_fields` | `imei,android_id` | Preserved across randomization |

## Project structure

```
app/src/main/java/dev/hexhydra/
├── XposedEntry.kt       # lifecycle; 4-strategy config bridge (XSP → provider → file → sysprops → FakeData)
├── HooksDevice.kt       # Build fields, SystemProperties, UA, java.lang.System
├── HooksTelephony.kt    # TelephonyManager, PhoneStateListener
├── HooksNetwork.kt      # Wi-Fi, DHCP, NetworkInterface, Bluetooth
├── HooksIds.kt          # Settings, AAID, GSF, MediaDrm, ContentResolver
├── HooksLocation.kt     # LocationManager, Fused provider, constructors, locale/TZ
├── HooksHardware.kt     # Display, OpenGL, battery, sensors
├── HooksStealth.kt      # PackageManager hiding, Class.forName, /proc filters
├── Bridge.kt            # pushToSystemProperties(): single-su setprop chain with quote escaping
├── BootReceiver.kt      # BOOT_COMPLETED → re-pushes saved config to hexhydra.*
├── FakeData.kt          # generators (verified TAC IMEI, OUI MAC, MCC/MNC IMSI, city geo, …)
├── DeviceProfiles.kt    # 42 device entries
├── IdentityData.kt      # verified per-manufacturer TAC + OUI pools (pure data)
├── ProfileCoherence.kt  # cross-field contradiction validator (pure Kotlin)
├── FieldValidators.kt   # per-field input rules (pure Kotlin, unit-tested)
├── DataProvider.kt      # internal ContentProvider fallback (not exported)
└── MainActivity.kt      # programmatic UI, no XML layouts
```

## Known limitations

These are platform constraints, not bugs:

- **Native code is out of reach.** Anything reading identity via NDK syscalls (`getifaddrs`, raw `/proc` reads, `__system_property_get`) bypasses Java-layer hooks entirely.
- **Hardware attestation can't be faked.** Play Integrity / key-attestation verdicts are signed in the TEE and verified server-side; the module can only hide its own presence, never forge a verdict.
- **`Build.VERSION.SDK_INT`** is a `static final int` — constants compiled directly into app bytecode can't be fully redirected.
- **`SystemProperties.set()` from zygote is blocked on Android 15** — the module bridges via `setprop` over `su`, with file/XSharedPreferences fallbacks.
- Unscoped system packages (`android`, `com.android.systemui`) are deliberately never hooked.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Badge reads **INACTIVE** | Module not enabled/scoped, or target needs restart | Re-enable + scope in LSPosed; restart the target app |
| Scoped app shows real values | Stale LSPosed DB path after an APK upgrade | Re-enable the module, re-scope, soft-reboot |
| Save blocked with "invalid fields" | A field fails format validation | Error text sits under the offending field; blank is always allowed |
| Randomize wiped a value | Field wasn't locked | Tap the lock next to the field before randomizing |
| Release build is unsigned | No signing credentials configured | See [Signing & secrets](#signing--secrets) |

## Signing & secrets

Release credentials are **never committed**. Provide them via `local.properties` (gitignored — see `local.properties.example`) or the `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_PASSWORD` / `RELEASE_KEY_ALIAS` environment variables. Without them, release builds simply come out unsigned; debug builds are unaffected.

## Versioning

`versionCode` + `versionName` in `app/build.gradle` are the source of truth; `CHANGELOG.md` records every release. Current: **v3.9.2** (versionCode 67).

## License

Custom license — personal use allowed; **forking, modifying, or redistributing requires prior written approval** from the repository owner. See [LICENSE](LICENSE). Use in compliance with applicable law and target apps' terms of service.