package dev.hexhydra

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val PREFS_NAME = "hexhydra_prefs"

    private data class FieldGroup(val title: String, val keys: List<String>)

    private val groups = listOf(
        FieldGroup("Device Identity", listOf(
            "manufacturer", "model", "brand", "device", "product", "board", "device_name",
            "build_id", "android_version", "fingerprint", "hardware_id"
        )),
        FieldGroup("Android IDs", listOf("android_id", "gsf_id", "aaid", "media_drm_id")),
        FieldGroup("Telephony", listOf(
            "imei", "meid", "imsi", "sim_serial", "sim_sub_id", "mobile_no",
            "sim_operator", "network_operator", "country_iso"
        )),
        FieldGroup("Network", listOf("mac_address", "mac_bssid", "mac_ssid", "bluetooth_mac", "ip_address")),
        FieldGroup("Location & Locale", listOf("latitude", "longitude", "locale", "timezone")),
        FieldGroup("Display & Hardware", listOf(
            "screen_width", "screen_height", "screen_density", "user_agent",
            "gl_renderer", "gl_vendor", "battery_level", "battery_scale"
        ))
    )

    private val fieldLabels = mapOf(
        "manufacturer" to "Manufacturer", "model" to "Model", "brand" to "Brand",
        "device" to "Codename", "product" to "Product", "board" to "Board",
        "device_name" to "Device Name", "build_id" to "Build ID",
        "android_version" to "Android Version", "fingerprint" to "Fingerprint",
        "hardware_id" to "Hardware ID", "android_id" to "Android ID",
        "gsf_id" to "GSF ID", "aaid" to "Advertising ID", "media_drm_id" to "Media DRM ID",
        "imei" to "IMEI", "meid" to "MEID", "imsi" to "IMSI", "sim_serial" to "SIM Serial",
        "sim_sub_id" to "SIM Sub ID", "mobile_no" to "Phone Number",
        "sim_operator" to "SIM Operator", "network_operator" to "Network Operator",
        "country_iso" to "Country ISO", "mac_address" to "WiFi MAC",
        "mac_bssid" to "WiFi BSSID", "mac_ssid" to "WiFi SSID",
        "bluetooth_mac" to "Bluetooth MAC", "ip_address" to "IP Address",
        "latitude" to "Latitude", "longitude" to "Longitude",
        "locale" to "Locale", "timezone" to "Timezone",
        "screen_width" to "Screen Width", "screen_height" to "Screen Height",
        "screen_density" to "Screen Density", "user_agent" to "User Agent",
        "gl_renderer" to "GPU Renderer", "gl_vendor" to "GPU Vendor",
        "battery_level" to "Battery Level", "battery_scale" to "Battery Scale"
    )


    private val fieldKeys = groups.flatMap { it.keys }
    private val values = linkedMapOf<String, String>()
    // Snapshot of the last saved profile — per-field dirty dots diff against this.
    private val savedValues = linkedMapOf<String, String>()
    private val inputs = mutableMapOf<String, EditText>()
    private val dotViews = mutableMapOf<String, TextView>()
    private val detailViews = mutableMapOf<String, TextView>()
    private lateinit var debugLogging: Switch
    private lateinit var hideSelf: Switch
    private val hookBoxes = mutableMapOf<String, Switch>()
    private lateinit var mainScroll: ScrollView
    private var stickySaveBtn: Button? = null
    private var stickyBar: View? = null
    private lateinit var dirtyText: TextView
    private lateinit var coherenceText: TextView
    private var lastPushOk: Boolean? = null
    private var lastPushAt: Long = 0L
    private val groupBadges = mutableMapOf<Int, TextView>()
    private val keyToGroupIndex = groups.flatMapIndexed { index, group ->
        group.keys.map { it to index }
    }.toMap()
    private var tabPages: List<LinearLayout> = emptyList()
    private var selectedTab = 0
    private var headerDot: View? = null
    private var statusCard: View? = null
    private var statusDot: View? = null
    private val navIcons = mutableListOf<TextView>()
    private val navLabels = mutableListOf<TextView>()
    private var showAllHistory = false

    private val navData = listOf("🏠" to "Home", "🧬" to "Fields", "⚙️" to "Settings")

    private val hookGroups = listOf(
        "hook_device" to "Device identity",
        "hook_telephony" to "Telephony & SIM",
        "hook_network" to "Network",
        "hook_location" to "Location & locale",
        "hook_display" to "Display & battery",
        "hook_ids" to "Android IDs",
        "hook_ua" to "User-Agent",
        "hook_stealth" to "Anti-detection"
    )
    private val hookDescs = mapOf(
        "hook_device" to "Build fields: manufacturer, model, fingerprint",
        "hook_telephony" to "IMEI, IMSI, SIM & operator data",
        "hook_network" to "MAC addresses, SSID, Bluetooth",
        "hook_location" to "GPS coordinates, locale, timezone",
        "hook_display" to "Screen metrics, GPU renderer, battery",
        "hook_ids" to "Android ID, GSF ID, AAID, MediaDRM",
        "hook_ua" to "WebView User-Agent & Java properties",
        "hook_stealth" to "Hide module traces from scoped apps"
    )

    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView
    private lateinit var refreshText: TextView
    private lateinit var summaryText: TextView
    private lateinit var accordionContainer: LinearLayout
    private val expandedGroups = mutableSetOf(0) // first group open by default
    private var searchQuery = ""
    private var dirty = false // true once the user edits anything since load/save
    private val lockedKeys = mutableSetOf<String>() // fields Randomize must not touch
    private val historyPrefsName = "hexhydra_history" // separate file: keeps snapshots out of the hook data path

    // ---- palette delegates (tokens live in Ui) ----
    private fun isDark() = Ui.isDark(this)
    private fun bgColor() = Ui.bg(this)
    private fun panelColor() = Ui.card(this)
    private fun inputColor() = Ui.input(this)
    private fun cardAltColor() = Ui.cardAlt(this)
    private fun textPrimary() = Ui.textPrimary(this)
    private fun textSecondary() = Ui.textSecondary(this)
    private fun textHint() = Ui.textHint(this)
    private fun faintColor() = Ui.faint(this)
    private fun dividerColor() = Ui.divider(this)

    // ========== Validation ==========

    /** Thin delegate — rules live in FieldValidators (pure Kotlin, unit-tested). */
    private fun validateField(key: String, raw: String): String? = FieldValidators.validate(key, raw)

    // ========== Status Detection ==========

    /** Reads hexhydra.refreshed epoch millis via SystemProperties; 0 if absent. */
    private fun readRefreshed(): Long {
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            return (getMethod.invoke(null, "hexhydra.refreshed", "0") as? String ?: "0").toLongOrNull() ?: 0
        } catch (_: Throwable) { return 0 }
    }

    private fun isModuleActive(): Boolean {
        // Only trust the refresh timestamp pushed by an actually-loaded module.
        // Deliberately no SharedPreferences fallback: our own prefs always hold
        // the saved profile, which would report "active" even when the module
        // is not loaded in any process.
        return try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val refreshed = getMethod.invoke(null, "hexhydra.refreshed", "") as? String ?: ""
            refreshed.isNotEmpty()
        } catch (_: Throwable) { false }
    }

    // ========== Lifecycle / layout ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadValues()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor())
        }
        root.addView(buildHeader())

        mainScroll = ScrollView(this).apply { isFillViewport = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.LG), dp(Ui.SM), dp(Ui.LG), dp(Ui.LG))
        }
        mainScroll.addView(content)

        // Home: status → quick actions → identity → history.
        val dashPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dashPage.addView(buildStatusCard())
        dashPage.addView(spacer(Ui.MD))
        dashPage.addView(buildQuickActions())
        dashPage.addView(spacer(Ui.MD))
        dashPage.addView(buildDeviceCard())
        dashPage.addView(spacer(Ui.MD))
        dashPage.addView(buildHistory())
        content.addView(dashPage)

        // Fields: the editor gets its own dedicated space.
        val fieldsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        fieldsPage.addView(buildAccordionEditor())
        content.addView(fieldsPage)

        // Settings: options, hooks, data transfer, danger zone, footer.
        val settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        settingsPage.addView(buildSettings())
        settingsPage.addView(spacer(Ui.MD))
        settingsPage.addView(buildDataTransfer())
        settingsPage.addView(spacer(Ui.MD))
        settingsPage.addView(buildDangerZone())
        settingsPage.addView(buildFooter())
        content.addView(settingsPage)

        tabPages = listOf(dashPage, fieldsPage, settingsPage)
        root.addView(mainScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Sticky Randomize+Save bar: visible on Fields tab, or anywhere when dirty.
        stickyBar = buildStickyBar()
        root.addView(stickyBar)
        root.addView(buildBottomNav())

        setContentView(root)
        selectTab(0)
        refreshStatusBadge()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBadge()
    }

    private fun refreshStatusBadge() {
        if (!::statusText.isInitialized) return
        val active = isModuleActive()
        val dotColor = if (active) Ui.SUCCESS else Ui.DOT_INACTIVE
        statusText.text = if (active) "Module Active" else "Module Inactive"
        statusText.setTextColor(if (active) Ui.okText(this) else Ui.badText(this))
        statusSub.text = if (active) "Spoofing is running" else "Reboot needed to load hooks"
        statusSub.setTextColor(if (active) Ui.okText(this) else Ui.badText(this))
        statusCard?.background =
            Ui.rounded(this, if (active) Ui.okBg(this) else Ui.badBg(this), Ui.R_CARD)
        statusDot?.background = Ui.rounded(this, dotColor, Ui.R_PILL)
        headerDot?.background = Ui.rounded(this, dotColor, Ui.R_PILL)
        if (::refreshText.isInitialized) {
            val refreshed = readRefreshed()
            val line1 = if (refreshed > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                "Last refreshed: ${sdf.format(Date(refreshed))}"
            } else {
                "Last refreshed: never (Save pushes to props)"
            }
            val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val line2 = when (lastPushOk) {
                true -> "Last push: ✓ ${timeFmt.format(Date(lastPushAt))}"
                false -> "Last push: ✗ failed ${timeFmt.format(Date(lastPushAt))}"
                null -> "Last push: not yet"
            }
            refreshText.text = "$line1\n$line2"
        }
    }



    // ========== Header (fixed, above the scroll area) ==========

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.LG), dp(Ui.MD), dp(Ui.LG), dp(Ui.SM))

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Ui.rounded(this@MainActivity, Color.WHITE, Ui.MD)
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }, LinearLayout.LayoutParams(dp(40), dp(40)))

            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            copy.addView(TextView(this@MainActivity).apply {
                text = "HexHydra"
                textSize = Ui.T_HEADER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary())
            })
            copy.addView(TextView(this@MainActivity).apply {
                text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}"
                textSize = Ui.T_CAPTION
                typeface = Typeface.MONOSPACE
                setTextColor(textHint())
            })
            addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val dot = View(this@MainActivity).apply {
                background = Ui.rounded(
                    this@MainActivity,
                    if (isModuleActive()) Ui.SUCCESS else Ui.DOT_INACTIVE, Ui.R_PILL
                )
            }
            addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(4) })
            headerDot = dot
        }
    }

    // ========== Status card ==========

    private fun buildStatusCard(): View {
        val active = isModuleActive()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(Ui.LG), dp(Ui.LG), dp(Ui.LG), dp(Ui.MD))
            background = Ui.rounded(
                this@MainActivity,
                if (active) Ui.okBg(this@MainActivity) else Ui.badBg(this@MainActivity),
                Ui.R_CARD
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { showStatusDialog() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(this).apply {
            background = Ui.rounded(
                this@MainActivity,
                if (active) Ui.SUCCESS else Ui.DOT_INACTIVE, Ui.R_PILL
            )
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(Ui.MD) })
        statusDot = dot

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusText = TextView(this).apply {
            textSize = Ui.T_TITLE
            typeface = Typeface.DEFAULT_BOLD
        }
        statusSub = TextView(this).apply { textSize = 12f }
        col.addView(statusText)
        col.addView(statusSub)
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        row.addView(TextView(this).apply {
            text = "ⓘ"
            textSize = Ui.T_TITLE
            setTextColor(faintColor())
        })
        card.addView(row)

        refreshText = TextView(this).apply {
            textSize = Ui.T_CAPTION
            typeface = Typeface.MONOSPACE
            setTextColor(faintColor())
            setPadding(0, dp(Ui.SM), 0, 0)
        }
        card.addView(refreshText)

        statusCard = card
        return card
    }

    // ========== Quick actions (Home) ==========

    private fun buildQuickActions(): View {
        return Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Quick Actions"))
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            row.addView(primaryButton("🎲  Randomize All") { randomizeAll() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
            row.addView(outlineButton("🔄  Soft Reboot", Ui.DANGER) { softReboot() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            addView(row)
            addView(Ui.caption(this@MainActivity,
                "Randomize fills every unlocked field with a fresh identity.").apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(Ui.SM), 0, 0)
            })
        }
    }

    // ========== Device Preview Card ==========

    private fun buildDeviceCard(): View {
        return Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Device Profile"))

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(10))
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = "📱"
                textSize = 28f
                setPadding(0, 0, dp(Ui.MD), 0)
            })
            val nameCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            summaryText = TextView(this@MainActivity).apply {
                textSize = Ui.T_TITLE
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary())
                setLineSpacing(0f, 1.2f)
            }
            nameCol.addView(summaryText)
            headerRow.addView(nameCol, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerRow)

            addView(divider())

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(Ui.SM), 0, 0)
            }
            details.addView(detailRow("IMEI", "imei"))
            details.addView(detailRow("Android ID", "android_id"))
            details.addView(detailRow("WiFi MAC", "mac_address"))
            details.addView(detailRow("Carrier", "sim_operator"))
            addView(details)
            addView(Ui.caption(this@MainActivity, "Tap a value to copy it.").apply {
                setPadding(0, dp(6), 0, 0)
            })
            coherenceText = TextView(this@MainActivity).apply {
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(Ui.SM), 0, 0)
                isClickable = true
                isFocusable = true
            }
            addView(coherenceText)

            refreshSummary()
        }
    }

    private fun detailRow(label: String, key: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(5), 0, dp(5))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 13f
                setTextColor(textHint())
            }, LinearLayout.LayoutParams(dp(100), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@MainActivity).apply {
                tag = "detail_$key"
                text = displayValue(key, "—")
                textSize = Ui.T_BODY
                typeface = Typeface.MONOSPACE
                setTextColor(textSecondary())
                maxLines = 1
                setHorizontallyScrolling(true)
                isClickable = true
                isFocusable = true
                detailViews[key] = this
                setOnClickListener {
                    val value = displayValue(key, "")
                    if (value.isBlank() || value == "—") {
                        toast("Nothing to copy")
                    } else {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(label, value))
                        toast("Copied $label")
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }


    // ========== Profile History (last 3 + View all) ==========

    private var historyContainer: LinearLayout? = null
    private val maxHistory = 10

    private fun buildHistory(): View {
        return Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Profile History"))
            historyContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(historyContainer)
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        val container = historyContainer ?: return
        container.removeAllViews()
        val entries = readHistory()
        if (entries.isEmpty()) {
            container.addView(Ui.caption(this, "No saved profiles yet — press Save to record one."))
            return
        }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val shown = if (showAllHistory) entries else entries.take(3)
        for ((ts, map) in shown) {
            val mfr = map["manufacturer"]?.takeIf { it.isNotBlank() } ?: "Unknown"
            val model = map["model"]?.takeIf { it.isNotBlank() } ?: "device"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = "${fmt.format(Date(ts))}  •  ${profileTitle(mfr, model)}"
                textSize = 13f
                setTextColor(textSecondary())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actionButton("Restore", Ui.ACCENT) {
                values.putAll(map)
                for ((k, et) in inputs) et.setText(map[k].orEmpty())
                markDirty(true)
                refreshAllDots()
                refreshSummary()
                toast("Profile restored — press Save to apply.")
            }, LinearLayout.LayoutParams(dp(84), dp(36)))
            container.addView(row)
        }
        if (entries.size > 3) {
            container.addView(TextView(this).apply {
                text = if (showAllHistory) "Show less" else "View all (${entries.size})"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.ACCENT)
                gravity = Gravity.CENTER
                setPadding(0, dp(Ui.SM), 0, dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    showAllHistory = !showAllHistory
                    refreshHistory()
                }
            })
        }
    }


    // ========== Data Transfer (Settings) ==========

    private fun buildDataTransfer(): View {
        return Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Data Transfer"))
            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(outlineButton("📤  Export Profile", Ui.ACCENT) { exportProfile() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) })
            btnRow.addView(outlineButton("📥  Import Profile", Ui.ACCENT) { importProfileDialog() },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) })
            addView(btnRow)
            addView(Ui.caption(this@MainActivity,
                "Export writes a copy-pastable JSON profile. Import restores one.").apply {
                setPadding(0, dp(Ui.SM), 0, 0)
            })
        }
    }

    private fun snapshotToJson(map: Map<String, String>): String {
        val sb = StringBuilder("{")
        fieldKeys.forEachIndexed { i, k ->
            if (i > 0) sb.append(',')
            val v = map[k].orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
            sb.append('"').append(k).append("\":\"").append(v).append('"')
        }
        return sb.append('}').toString()
    }

    private fun pushHistory(snapshot: Map<String, String>) {
        try {
            val prefs = getSharedPreferences(historyPrefsName, MODE_PRIVATE)
            val editor = prefs.edit()
            val n = prefs.getInt("history_count", 0)
            for (i in n - 1 downTo 1) {
                val e = prefs.getString("h$i", null)
                if (e != null) prefs.edit().putString("h${i + 1}", e).apply()
            }
            editor.putString("h1", System.currentTimeMillis().toString() + "|" + snapshotToJson(snapshot))
            editor.putInt("history_count", minOf(n + 1, maxHistory))
            editor.apply()
        } catch (_: Throwable) {}
    }

    private fun readHistory(): List<Pair<Long, Map<String, String>>> {
        val list = mutableListOf<Pair<Long, Map<String, String>>>()
        try {
            val prefs = getSharedPreferences(historyPrefsName, MODE_PRIVATE)
            val n = prefs.getInt("history_count", 0)
            for (i in 1..minOf(n, maxHistory)) {
                val e = prefs.getString("h$i", null) ?: continue
                val sep = e.indexOf('|')
                if (sep <= 0) continue
                val ts = e.substring(0, sep).toLongOrNull() ?: continue
                val json = e.substring(sep + 1)
                val map = mutableMapOf<String, String>()
                val body = json.trim().removePrefix("{").removeSuffix("}")
                val parts = body.split(",")
                for (p in parts) {
                    val kv = p.split(":", limit = 2)
                    if (kv.size == 2) {
                        val k = kv[0].trim().removeSurrounding("\"")
                        val v = kv[1].trim().removeSurrounding("\"")
                            .replace("\\\"", "\"").replace("\\\\", "\\")
                        map[k] = v
                    }
                }
                if (map.isNotEmpty()) list.add(Pair(ts, map))
            }
        } catch (_: Throwable) {}
        return list.sortedByDescending { it.first }
    }


    private fun exportProfile() {
        val snapshot = snapshotToJson(values)
        AlertDialog.Builder(this)
            .setTitle("Export Profile")
            .setMessage("Copy this JSON to transfer your profile:\n\n$snapshot")
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("hexhydra_profile", snapshot))
                toast("Profile copied to clipboard")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun importProfileDialog() {
        val input = EditText(this).apply {
            hint = "{\"manufacturer\":\"Google\",\"model\":\"Pixel 7 Pro\",...}"
            setHintTextColor(textHint())
            setTextColor(textPrimary())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(Ui.MD), dp(Ui.MD), dp(Ui.MD), dp(Ui.MD))
            background = Ui.rounded(this@MainActivity, inputColor(), 10)
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle("Import Profile")
            .setMessage("Paste an exported JSON profile:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) { toast("Nothing to import"); return@setPositiveButton }
                try {
                    val body = text.removePrefix("{").removeSuffix("}")
                    val map = mutableMapOf<String, String>()
                    for (p in body.split(",")) {
                        val kv = p.split(":", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim().removeSurrounding("\"")
                            val v = kv[1].trim().removeSurrounding("\"")
                                .replace("\\\"", "\"").replace("\\\\", "\\")
                            map[k] = v
                        }
                    }
                    if (map.isEmpty()) { toast("Could not parse profile"); return@setPositiveButton }
                    for (k in fieldKeys) if (map.containsKey(k)) values[k] = map[k]!!
                    for ((k, et) in inputs) et.setText(values[k].orEmpty())
                    markDirty(true)
                    refreshAllDots()
                    refreshSummary()
                    toast("Profile imported — press Save to apply.")
                } catch (t: Throwable) {
                    toast("Import failed: ${t.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // ========== Settings (switch rows) ==========

    /** Title + subtitle row with a trailing Switch. Returns (row, switch). */
    private fun switchRow(title: String, subtitle: String, checked: Boolean): Pair<View, Switch> {
        val sw = Switch(this).apply { isChecked = checked }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(Ui.SM), 0, dp(Ui.SM))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = title
            textSize = Ui.T_BODY
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary())
        })
        if (subtitle.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = subtitle
                textSize = Ui.T_CAPTION
                setTextColor(faintColor())
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { sw.toggle() }
        return row to sw
    }

    private fun buildSettings(): View {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val optionsCard = Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Module Options"))
            val (r1, s1) = switchRow(
                "Debug logging", "Verbose Xposed logcat output",
                prefs.getBoolean("setting_debug_log", false)
            )
            debugLogging = s1
            addView(r1)
            addView(Ui.dividerView(this@MainActivity))
            val (r2, s2) = switchRow(
                "Hide module from scoped apps", "Anti-detection self-hiding",
                prefs.getBoolean("setting_hide_self", true)
            )
            hideSelf = s2
            addView(r2)
        }

        val hooksCard = Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Hook Toggles"))
            addView(Ui.caption(this@MainActivity,
                "Restart target apps after changing these.").apply {
                setPadding(0, 0, 0, dp(Ui.SM))
            })
            hookBoxes.clear()
            hookGroups.forEachIndexed { i, (key, label) ->
                val (row, sw) = switchRow(
                    label, hookDescs[key].orEmpty(),
                    prefs.getBoolean(key, true)
                )
                hookBoxes[key] = sw
                if (i > 0) addView(Ui.dividerView(this@MainActivity))
                addView(row)
            }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(optionsCard)
            addView(spacer(Ui.MD))
            addView(hooksCard)
        }
    }

    // ========== Danger Zone ==========

    private fun buildDangerZone(): View {
        return Ui.cardView(this).apply {
            addView(Ui.sectionTitle(this@MainActivity, "Danger Zone"))
            addView(outlineButton("🔄  Soft Reboot (restart Android runtime)", Ui.DANGER) {
                softReboot()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)))
            addView(Ui.caption(this@MainActivity,
                "Restarts system_server — screen goes black ~15 seconds. " +
                    "Required after enabling the module in LSPosed.").apply {
                setPadding(0, dp(Ui.SM), 0, 0)
            })
        }
    }

    private fun buildFooter(): View {
        return TextView(this).apply {
            text = "HexHydra v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "Scope target apps in LSPosed, then Save here and restart them."
            textSize = Ui.T_CAPTION
            setTextColor(faintColor())
            gravity = Gravity.CENTER
            setPadding(0, dp(Ui.LG), 0, dp(Ui.SM))
        }
    }


    // ========== Accordion Editor ==========

    private fun buildAccordionEditor(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val search = EditText(this).apply {
            hint = "🔍  Filter fields…"
            setHintTextColor(faintColor())
            setTextColor(textPrimary())
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(Ui.MD), dp(10), dp(Ui.MD), dp(10))
            background = Ui.rounded(this@MainActivity, inputColor(), 10)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString()?.trim()?.lowercase() ?: ""
                    refreshAccordion()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        container.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(spacer(Ui.SM))

        val toggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        toggleRow.addView(TextView(this).apply {
            text = "Expand all"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.ACCENT)
            setPadding(dp(Ui.SM), dp(2), dp(Ui.SM), dp(2))
            setOnClickListener {
                expandedGroups.clear()
                expandedGroups.addAll(groups.indices)
                refreshAccordion()
            }
        })
        toggleRow.addView(TextView(this).apply {
            text = "  |  "
            textSize = 12f
            setTextColor(faintColor())
        })
        toggleRow.addView(TextView(this).apply {
            text = "Collapse all"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.ACCENT)
            setPadding(dp(Ui.SM), dp(2), dp(Ui.SM), dp(2))
            setOnClickListener {
                expandedGroups.clear()
                refreshAccordion()
            }
        })
        container.addView(toggleRow)
        container.addView(spacer(Ui.SM))

        accordionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(accordionContainer)
        refreshAccordion()
        return container
    }

    private fun buildGroupSection(index: Int, group: FieldGroup): View {
        val expanded = expandedGroups.contains(index)
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(Ui.MD), dp(Ui.SM), dp(Ui.MD))
            background = Ui.rounded(this@MainActivity, cardAltColor(), 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (expandedGroups.contains(index)) expandedGroups.remove(index)
                else expandedGroups.add(index)
                refreshAccordion()
            }
        }
        header.addView(TextView(this).apply {
            text = if (expanded) "▾  ${group.title}" else "▸  ${group.title}"
            textSize = Ui.T_BODY
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary())
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Per-group error badge.
        val badge = TextView(this).apply {
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            visibility = View.GONE
        }
        styleGroupBadge(badge, groupErrorCount(index))
        groupBadges[index] = badge
        header.addView(badge)

        // Per-group randomize (does not toggle the accordion).
        header.addView(TextView(this).apply {
            text = "🎲"
            textSize = Ui.T_TITLE
            setPadding(dp(10), dp(2), dp(4), dp(2))
            isClickable = true
            isFocusable = true
            contentDescription = "Randomize ${group.title}"
            setOnClickListener { randomizeGroup(group) }
        })
        header.addView(TextView(this).apply {
            text = if (expanded) "−" else "+"
            textSize = Ui.T_TITLE
            setTextColor(faintColor())
            setPadding(dp(10), 0, 0, 0)
        })
        section.addView(header)

        if (expanded) {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(Ui.MD), dp(10), dp(4))
                background = Ui.rounded(this@MainActivity, cardAltColor(), 10)
            }
            populateFields(body, group.keys)
            section.addView(body)
        }
        return section
    }

    private fun inputTypeFor(key: String): Int = when (key) {
        "imei", "meid", "sim_sub_id", "latitude", "longitude",
        "screen_width", "screen_height", "screen_density",
        "battery_level", "battery_scale" ->
            InputType.TYPE_CLASS_TEXT
        "ip_address" -> InputType.TYPE_CLASS_TEXT
        else -> InputType.TYPE_CLASS_TEXT
    }


    private fun populateFields(container: LinearLayout, keys: List<String>) {
        for (key in keys) {
            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            // Per-field dirty dot (visible when value differs from the saved profile).
            val dot = TextView(this).apply {
                text = "●"
                textSize = 9f
                setTextColor(Ui.WARN)
                setPadding(0, 0, dp(6), 0)
                visibility = if (values[key].orEmpty() != savedValues[key].orEmpty())
                    View.VISIBLE else View.GONE
            }
            dotViews[key] = dot
            labelRow.addView(dot)

            labelRow.addView(TextView(this).apply {
                text = fieldLabels[key] ?: key
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textSecondary())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val lock = TextView(this).apply {
                text = if (lockedKeys.contains(key)) "🔒" else "🔓"
                textSize = Ui.T_BODY
                setPadding(dp(Ui.SM), dp(2), dp(2), dp(2))
                isClickable = true
                isFocusable = true
                contentDescription = "Lock ${fieldLabels[key] ?: key}"
                setOnClickListener {
                    if (lockedKeys.contains(key)) {
                        lockedKeys.remove(key)
                        text = "🔓"
                        toast("${fieldLabels[key] ?: key} unlocked — Randomize will change it")
                    } else {
                        lockedKeys.add(key)
                        text = "🔒"
                        toast("${fieldLabels[key] ?: key} locked — Randomize will skip it")
                    }
                    persistLocks()
                }
            }
            labelRow.addView(lock)
            wrapper.addView(labelRow)

            val errorText = TextView(this).apply {
                textSize = Ui.T_CAPTION
                setTextColor(Ui.DANGER)
                visibility = View.GONE
                setPadding(0, dp(2), 0, 0)
            }

            val et = EditText(this).apply {
                setText(values[key].orEmpty())
                hint = if (key == "android_version") "e.g. 14" else fieldLabels[key]
                setHintTextColor(faintColor())
                setTextColor(textPrimary())
                textSize = 13f
                typeface = Typeface.MONOSPACE
                inputType = inputTypeFor(key)
                setPadding(dp(Ui.MD), dp(10), dp(Ui.MD), dp(10))
                background = Ui.rounded(this@MainActivity, inputColor(), 10)
                setTag("field_$key")
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        val raw = s?.toString() ?: ""
                        val err = validateField(key, raw)
                        if (err != null && raw.isNotBlank()) {
                            errorText.text = err
                            errorText.visibility = View.VISIBLE
                        } else {
                            errorText.visibility = View.GONE
                        }
                        refreshGroupBadgeForKey(key)
                        values[key] = raw
                        refreshDirtyDot(key)
                        markDirty(true)
                        refreshSummary()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            inputs[key] = et
            wrapper.addView(et)
            wrapper.addView(errorText)
            container.addView(wrapper)
        }
    }

    private fun refreshDirtyDot(key: String) {
        dotViews[key]?.visibility =
            if (values[key].orEmpty() != savedValues[key].orEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshAllDots() {
        for (k in fieldKeys) refreshDirtyDot(k)
    }

    private fun groupMatches(group: FieldGroup, q: String): Boolean {
        if (q.isEmpty()) return true
        if (group.title.lowercase().contains(q)) return true
        return group.keys.any { k ->
            k.lowercase().contains(q) ||
                (fieldLabels[k]?.lowercase()?.contains(q) ?: false) ||
                (values[k]?.lowercase()?.contains(q) ?: false)
        }
    }

    private fun refreshAccordion() {
        if (!::accordionContainer.isInitialized) return
        accordionContainer.removeAllViews()
        for ((index, group) in groups.withIndex()) {
            if (groupMatches(group, searchQuery)) {
                accordionContainer.addView(buildGroupSection(index, group))
                accordionContainer.addView(spacer(Ui.SM))
            }
        }
    }


    // ========== Prefs I/O & randomize logic ==========

    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        fieldKeys.forEach { values[it] = prefs.getString(it, "").orEmpty() }
        savedValues.clear()
        savedValues.putAll(values)
        lockedKeys.clear()
        lockedKeys.addAll(prefs.getString("locked_keys", "").orEmpty()
            .split(",").filter { it.isNotBlank() })
        markDirty(false)
    }

    private fun persistLocks() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("locked_keys", lockedKeys.joinToString(",")).apply()
    }

    private fun randomizeAll() {
        val locked = lockedKeys.size
        if (locked > 0 && dirty) {
            AlertDialog.Builder(this)
                .setTitle("Randomize All?")
                .setMessage("You have $locked locked field(s) and unsaved edits. " +
                    "Locked fields are kept; everything else is replaced with new random values.")
                .setPositiveButton("Randomize") { _, _ -> doRandomize() }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            doRandomize()
        }
    }

    private fun doRandomize() {
        val fresh = FakeData.generateAll()
        for (k in fieldKeys) {
            if (k in lockedKeys) continue
            values[k] = fresh[k].orEmpty()
            inputs[k]?.setText(values[k])
        }
        refreshAllDots()
        refreshSummary()
        // Cross-field coherence via the unit-tested ProfileCoherence validator.
        val issues = ProfileCoherence.issues(values)
        coherenceText.text = if (issues.isEmpty())
            "✓  Profile coherent" else "⚠  ${issues.size} issue(s) — tap to view"
        coherenceText.setTextColor(if (issues.isEmpty()) Ui.okText(this) else Ui.WARN)
        coherenceText.setOnClickListener {
            AlertDialog.Builder(this@MainActivity)
                .setTitle(if (issues.isEmpty()) "Profile coherent" else "Coherence check")
                .setMessage(if (issues.isEmpty())
                    "All cross-field checks passed (brand/model, fingerprint, TAC, OUI, " +
                        "carrier/country, locale, timezone, battery)."
                else
                    issues.joinToString("\n\n"))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Randomizes only the given group's fields (respecting 🔒 locks). */
    private fun randomizeGroup(group: FieldGroup) {
        val fresh = FakeData.generateAll()
        var changed = 0
        for (k in group.keys) {
            if (k in lockedKeys) continue
            values[k] = fresh[k].orEmpty()
            inputs[k]?.setText(values[k])
            changed++
        }
        refreshAllDots()
        refreshSummary()
        toast(if (changed > 0) "🎲 ${group.title} randomized ($changed fields)"
              else "🔒 ${group.title} is fully locked")
    }

    private fun refreshSummary() {
        if (!::summaryText.isInitialized) return
        summaryText.text = profileTitle(values["manufacturer"], values["model"])
        detailViews["imei"]?.text = displayValue("imei", "—")
        detailViews["android_id"]?.text = displayValue("android_id", "—")
        detailViews["mac_address"]?.text = displayValue("mac_address", "—")
        detailViews["sim_operator"]?.text = displayValue("sim_operator", "—")
    }

    private fun displayValue(key: String, fallback: String): String =
        values[key]?.takeIf { it.isNotBlank() } ?: fallback

    private fun profileTitle(mfr: String?, model: String?): String {
        val m = mfr?.takeIf { it.isNotBlank() } ?: "Unknown"
        val d = model?.takeIf { it.isNotBlank() } ?: "Device"
        return "$m $d"
    }


    // ========== System-properties push & save ==========

    /**
     * Pushes prefs JSON + refresh timestamp into system properties via su.
     * This is the real channel the hooked system_server reads from —
     * /data/local/tmp file watches are unreliable (SELinux, timing).
     */
    private fun pushConfigToSystemProperties(): Boolean {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val map = prefs.all
            val sb = StringBuilder("{")
            var first = true
            for ((k, v) in map) {
                if (k.startsWith("hook_") || k.startsWith("setting_") || k == "locked_keys") continue
                val s = v as? String ?: continue
                if (!first) sb.append(',')
                first = false
                sb.append('"').append(k).append("\":\"").append(s.replace("\"", "\\\"")).append('"')
            }
            sb.append('}')
            val json = sb.toString().replace("\"", "\\\"").replace("'", "'\"'\"'")
            val ts = System.currentTimeMillis()
            val cmd = "setprop hexhydra.config '$json'; setprop hexhydra.refreshed $ts"

            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor()
            val ok = p.exitValue() == 0
            if (ok) {
                lastPushOk = true
                lastPushAt = ts
                refreshStatusBadge()
                logToLogcat("HexHydra props pushed ($ts)")
            }
            return ok
        } catch (_: Throwable) { return false }
    }

    private fun saveConfig() {
        // Hard-gate: refuse to save while any field fails validation.
        val errors = fieldKeys.mapNotNull { k ->
            val raw = values[k].orEmpty()
            validateField(k, raw)?.let { err -> "${fieldLabels[k] ?: k}: $err" }
        }
        if (errors.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Can't save yet")
                .setMessage(errors.take(5).joinToString("\n") +
                    if (errors.size > 5) "\n… and ${errors.size - 5} more." else "")
                .setPositiveButton("Go to field") { _, _ ->
                    selectTab(1)
                    scrollToField(errors.first().substringBefore(":"))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val btn = stickySaveBtn
        val originalText = btn?.text
        btn?.isEnabled = false

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        fieldKeys.forEach { prefs.putString(it, values[it]) }
        prefs.putBoolean("setting_debug_log", debugLogging.isChecked)
        prefs.putBoolean("setting_hide_self", hideSelf.isChecked)
        hookBoxes.forEach { (k, sw) -> prefs.putBoolean(k, sw.isChecked) }
        prefs.apply()

        try {
            val dir = File(filesDir.parent, "shared_prefs")
            dir.setReadable(true, false)
            File(dir, "$PREFS_NAME.xml").setReadable(true, false)
        } catch (_: Throwable) {}

        pushHistory(values)

        val pushed = pushConfigToSystemProperties()
        if (pushed) {
            markDirty(false)
            savedValues.clear()
            savedValues.putAll(values)
            refreshAllDots()
            toast("✓ Saved & pushed to props")
            btn?.apply {
                text = "✓ Saved!"
                setTextColor(Color.WHITE)
                background = Ui.rounded(this@MainActivity, Ui.SUCCESS, Ui.R_BUTTON)
                postDelayed({
                    text = originalText ?: "💾  Save"
                    background = Ui.rounded(this@MainActivity, Ui.SUCCESS, Ui.R_BUTTON)
                    isEnabled = true
                    refreshDirtyIndicator()
                }, 1200)
            }
        } else {
            toast("⚠ Saved, but push failed — is su granted?")
            btn?.apply {
                text = "⚠ Push failed"
                postDelayed({
                    text = originalText ?: "💾  Save"
                    isEnabled = true
                    refreshDirtyIndicator()
                }, 2000)
            }
        }
        refreshHistory()
    }

    // ========== Soft reboot ==========

    private fun hasRoot(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Throwable) { false }
    }

    private fun softReboot() {
        AlertDialog.Builder(this)
            .setTitle("Soft Reboot")
            .setMessage("Restarts the Android runtime (system_server). " +
                "The screen goes black for ~15 seconds.\n\n" +
                "Required after enabling the module in LSPosed. Continue?")
            .setPositiveButton("Reboot") { _, _ -> doSoftReboot() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSoftReboot() {
        if (!hasRoot()) {
            toast("su not granted — root required for soft reboot")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Rebooting")
            .setMessage("Restarting Android runtime…\nThe screen will go black briefly.")
            .setCancelable(false)
            .show()
        Thread {
            try {
                Thread.sleep(400)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "setprop ctl.restart zygote"))
            } catch (_: Throwable) {}
        }.start()
    }


    // ========== Dirty tracking + sticky bar ==========

    private fun markDirty(v: Boolean) {
        dirty = v
        refreshDirtyIndicator()
    }

    private fun refreshDirtyIndicator() {
        if (!::dirtyText.isInitialized) return
        dirtyText.text = if (dirty) "Unsaved changes — press Save" else "All changes saved"
        dirtyText.setTextColor(if (dirty) Ui.WARN else faintColor())
        stickySaveBtn?.let { it.text = if (dirty) "💾  Save ●" else "💾  Save" }
        updateStickyVisibility()
    }

    private fun updateStickyVisibility() {
        stickyBar?.visibility =
            if (selectedTab == 1 || dirty) View.VISIBLE else View.GONE
    }

    private fun buildStickyBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Ui.LG), dp(10), dp(Ui.LG), dp(10))
            background = Ui.rounded(this@MainActivity, panelColor(), Ui.R_CARD)
            elevation = dp(4).toFloat()
        }
        dirtyText = TextView(this).apply {
            textSize = 12f
            setTextColor(faintColor())
        }
        bar.addView(dirtyText, LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val rand = actionButton("🎲  Randomize", Ui.ACCENT) { randomizeAll() }
        bar.addView(rand, LinearLayout.LayoutParams(dp(132), dp(42)).apply { rightMargin = dp(Ui.SM) })
        val save = actionButton("💾  Save", Ui.SUCCESS) { saveConfig() }
        bar.addView(save, LinearLayout.LayoutParams(dp(110), dp(42)))
        stickySaveBtn = save
        bar.visibility = View.GONE
        return bar
    }

    // ========== Bottom navigation ==========

    private fun buildBottomNav(): View {
        navIcons.clear()
        navLabels.clear()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(panelColor())
            elevation = dp(Ui.SM).toFloat()
            setPadding(dp(Ui.LG), dp(Ui.SM), dp(Ui.LG), dp(Ui.MD))
            navData.forEachIndexed { i, (icon, label) ->
                val item = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        selectTab(i)
                    }
                }
                val ic = TextView(this@MainActivity).apply {
                    text = icon
                    textSize = 18f
                    gravity = Gravity.CENTER
                    setPadding(dp(Ui.LG), dp(Ui.XS), dp(Ui.LG), dp(Ui.XS))
                }
                val lb = TextView(this@MainActivity).apply {
                    text = label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(2), 0, 0)
                }
                item.addView(ic)
                item.addView(lb)
                navIcons.add(ic)
                navLabels.add(lb)
                addView(item, LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun refreshNav() {
        navIcons.forEachIndexed { i, ic ->
            val sel = i == selectedTab
            ic.background = if (sel) Ui.rounded(
                this, Ui.withAlpha(Ui.ACCENT, if (isDark()) 70 else 36), Ui.R_PILL
            ) else null
            navLabels[i].setTextColor(if (sel) Ui.ACCENT else faintColor())
            navLabels[i].typeface = if (sel) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun selectTab(i: Int) {
        selectedTab = i
        tabPages.forEachIndexed { idx, page ->
            page.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        refreshNav()
        updateStickyVisibility()
        if (::mainScroll.isInitialized) mainScroll.post { mainScroll.scrollTo(0, 0) }
    }

    // ========== Status dialog ==========

    private fun showStatusDialog() {
        val active = isModuleActive()
        val refreshed = readRefreshed()
        val timeStr = if (refreshed > 0)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(refreshed))
        else "never"

        AlertDialog.Builder(this)
            .setTitle(if (active) "✓ Module Active" else "✗ Module Inactive")
            .setMessage(
                if (active) {
                    "The Xposed module is loaded and spoofing.\n\n" +
                        "Last config refresh: $timeStr\n\n" +
                        "To change the spoofed identity, edit fields in the Fields tab and press Save."
                } else {
                    "The module is not currently loaded in any process.\n\n" +
                        "To activate:\n" +
                        "1. Open LSPosed → Modules → HexHydra\n" +
                        "2. Enable the module and scope target apps\n" +
                        "3. Press Soft Reboot on the Home tab (or reboot)\n\n" +
                        "Last config refresh: $timeStr"
                }
            )
            .setPositiveButton("OK", null)
            .show()
    }


    // ========== Group badges ==========

    private fun groupErrorCount(index: Int): Int {
        val g = groups.getOrNull(index) ?: return 0
        return g.keys.count { k ->
            val raw = values[k].orEmpty()
            raw.isNotBlank() && validateField(k, raw) != null
        }
    }

    private fun styleGroupBadge(badge: TextView, count: Int) {
        if (count <= 0) {
            badge.visibility = View.GONE
        } else {
            badge.text = "$count"
            badge.visibility = View.VISIBLE
            badge.setTextColor(Ui.badText(this))
            badge.background = Ui.rounded(this, Ui.badBg(this), Ui.R_PILL)
        }
    }

    private fun refreshGroupBadgeForKey(key: String) {
        val idx = keyToGroupIndex[key] ?: return
        val badge = groupBadges[idx] ?: return
        styleGroupBadge(badge, groupErrorCount(idx))
    }

    // ========== Scroll to a named field (used by validation gating) ==========

    private fun scrollToField(label: String) {
        if (!::mainScroll.isInitialized) return
        val idx = fieldLabels.entries.indexOfFirst { it.value == label }
        if (idx < 0) return
        val groupIdx = keyToGroupIndex[fieldLabels.keys.elementAt(idx)] ?: 0
        if (!expandedGroups.contains(groupIdx)) {
            expandedGroups.add(groupIdx)
            refreshAccordion()
        }
        mainScroll.postDelayed({
            val v = mainScroll.findViewWithTag<View>("field_${fieldLabels.keys.elementAt(idx)}")
            v?.requestFocus()
        }, 150)
    }

    // ========== Buttons, press feedback, small helpers ==========

    /** Press-in scale animation (micro-polish). */
    private fun View.pressEffect() {
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN ->
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
            }
            false
        }
    }

    /** Filled accent button with press animation + haptic. */
    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = Ui.T_BODY
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = Ui.rounded(this@MainActivity, Ui.ACCENT, Ui.R_BUTTON)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            pressEffect()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    /** Outlined button (e.g. Soft Reboot, Export/Import) with haptic. */
    private fun outlineButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = Ui.T_BODY
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color)
            isAllCaps = false
            background = Ui.outlined(this@MainActivity, color, Ui.R_BUTTON)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            pressEffect()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    /** Solid button in any color (sticky bar, history Restore). */
    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = Ui.T_BODY
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = Ui.rounded(this@MainActivity, color, Ui.R_BUTTON)
            stateListAnimator = null
            minHeight = 0
            minimumHeight = 0
            pressEffect()
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    // ---- small delegates so layout code reads consistently ----
    private fun panel() = Ui.cardView(this)
    private fun divider() = Ui.dividerView(this)
    private fun rounded(color: Int, radiusDp: Int) = Ui.rounded(this, color, radiusDp)
    private fun spacer(heightDp: Int) = Ui.spacer(this, heightDp)
    private fun dp(v: Int) = Ui.dp(this, v)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Visible under `adb logcat -s HexHydra` when debug logging is on. */
    private fun logToLogcat(msg: String) {
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getBoolean("setting_debug_log", false)) {
            android.util.Log.i("HexHydra", msg)
        }
    }
}

