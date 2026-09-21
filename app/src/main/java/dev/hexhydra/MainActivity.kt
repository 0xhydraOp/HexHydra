package dev.hexhydra

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private val inputs = mutableMapOf<String, EditText>()
    private val detailViews = mutableMapOf<String, TextView>()
    private lateinit var debugLogging: CheckBox
    private lateinit var hideSelf: CheckBox
    private val hookBoxes = mutableMapOf<String, CheckBox>()

    private val hookGroups = listOf(
        "hook_device" to "Device identity (Build fields)",
        "hook_telephony" to "Telephony & SIM",
        "hook_network" to "Network (Wi-Fi, DHCP, Bluetooth)",
        "hook_location" to "Location, locale & timezone",
        "hook_display" to "Display, GPU & battery",
        "hook_ids" to "Android IDs (Android ID, AAID, DRM)",
        "hook_ua" to "User-Agent & Java properties",
        "hook_stealth" to "Anti-detection & self-hiding"
    )
    private lateinit var statusText: TextView
    private lateinit var refreshText: TextView
    private lateinit var summaryText: TextView
    private lateinit var accordionContainer: LinearLayout
    private val expandedGroups = mutableSetOf(0) // first group open by default
    private var searchQuery = ""
    private var dirty = false // true once the user edits anything since load/save
    private val lockedKeys = mutableSetOf<String>() // fields Randomize must not touch
    private val historyPrefsName = "hexhydra_history" // separate file: keeps snapshots out of the hook data path

    // ========== Dark-mode aware palette ==========

    private fun isDark(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun c(light: String, dark: String): Int = Color.parseColor(if (isDark()) dark else light)

    private fun bgColor() = c("#F0F2F5", "#0B1220")
    private fun panelColor() = c("#FFFFFF", "#1F2937")
    private fun inputColor() = c("#FFFFFF", "#111827")
    private fun cardAltColor() = c("#F9FAFB", "#111827")
    private fun textPrimary() = c("#111827", "#F9FAFB")
    private fun textSecondary() = c("#374151", "#D1D5DB")
    private fun textHint() = c("#6B7280", "#9CA3AF")
    private fun faintColor() = c("#9CA3AF", "#6B7280")
    private fun dividerColor() = c("#E5E7EB", "#374151")

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
        // Check SystemProperties for the refresh timestamp
        try {
            val spClass = Class.forName("android.os.SystemProperties")
            val getMethod = spClass.getDeclaredMethod("get", String::class.java, String::class.java)
            val refreshed = getMethod.invoke(null, "hexhydra.refreshed", "") as? String ?: ""
            if (refreshed.isNotEmpty()) return true
        } catch (_: Throwable) {}
        // Fallback: check SharedPreferences populated by module
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mfr = prefs.getString("manufacturer", "") ?: ""
            if (mfr.isNotEmpty()) return true
        } catch (_: Throwable) {}
        return false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadValues()

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bgColor())
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(buildHeader())
        root.addView(spacer(12))
        root.addView(buildStatusBadge())
        refreshText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(faintColor())
            setPadding(0, dp(6), 0, 0)
        }
        root.addView(refreshText)
        root.addView(spacer(6))
        root.addView(buildActions())
        root.addView(spacer(12))
        root.addView(buildDeviceCard())
        root.addView(spacer(12))
        root.addView(buildHistory())
        root.addView(spacer(12))
        root.addView(buildSettings())
        root.addView(spacer(12))
        root.addView(buildAccordionEditor())
        root.addView(spacer(8))
        root.addView(buildFooter())
    }

    override fun onResume() {
        super.onResume()
        refreshStatusBadge()
    }

    private fun refreshStatusBadge() {
        if (!::statusText.isInitialized) return
        val active = isModuleActive()
        statusText.text = if (active) "MODULE ACTIVE – Spoofing running" else "MODULE INACTIVE – Reboot needed"
        statusText.setTextColor(if (active) Color.parseColor("#065F46") else Color.parseColor("#991B1B"))
        val badge = statusText.parent as? LinearLayout ?: return
        badge.background = rounded(if (active) Color.parseColor("#ECFDF5") else Color.parseColor("#FEF2F2"), 12)
        badge.getChildAt(0)?.background =
            rounded(if (active) Color.parseColor("#10B981") else Color.parseColor("#EF4444"), 999)
        if (::refreshText.isInitialized) {
            val refreshed = readRefreshed()
            refreshText.text = if (refreshed > 0) {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                "Last refreshed: ${sdf.format(Date(refreshed))}"
            } else {
                "Last refreshed: never (Save pushes to props)"
            }
        }
    }

    // ========== Header ==========

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)

            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.mipmap.ic_launcher)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.WHITE, 14)
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }, LinearLayout.LayoutParams(dp(56), dp(56)))

            val copy = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            copy.addView(TextView(this@MainActivity).apply {
                text = "HexHydra"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary())
            })
            copy.addView(TextView(this@MainActivity).apply {
                text = "v${BuildConfig.VERSION_NAME}"
                textSize = 13f
                setTextColor(textHint())
            })
            addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ========== Status Badge ==========

    private fun buildStatusBadge(): View {
        val active = isModuleActive()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(if (active) Color.parseColor("#ECFDF5") else Color.parseColor("#FEF2F2"), 12)

            addView(View(this@MainActivity).apply {
                background = rounded(if (active) Color.parseColor("#10B981") else Color.parseColor("#EF4444"), 999)
            }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) })

            statusText = TextView(this@MainActivity).apply {
                text = if (active) "MODULE ACTIVE – Spoofing running" else "MODULE INACTIVE – Reboot needed"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (active) Color.parseColor("#065F46") else Color.parseColor("#991B1B"))
            }
            addView(statusText)
        }
    }

    // ========== Actions ==========

    private fun buildActions(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Actions"))

            // Randomize — full width, prominent
            addView(actionButton("🎲  Randomize Profile", Color.parseColor("#2563EB")) {
                randomizeAll()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })

            // Save + Fast Reboot side by side
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            lateinit var saveBtn: Button
            saveBtn = actionButton("💾  Save", Color.parseColor("#059669")) {
                saveConfig(saveBtn)
                Toast.makeText(this@MainActivity, "Saved – restart target apps.", Toast.LENGTH_SHORT).show()
            }
            row.addView(saveBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })

            row.addView(actionButton("🔄  Soft Reboot", Color.parseColor("#DC2626")) {
                softReboot()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })

            addView(row)
        }
    }

    // ========== Device Preview Card ==========

    private fun buildDeviceCard(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Device Profile"))

            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(10))
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = "📱"
                textSize = 28f
                setPadding(0, 0, dp(12), 0)
            })
            val nameCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            summaryText = TextView(this@MainActivity).apply {
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textPrimary())
                setLineSpacing(0f, 1.2f)
            }
            nameCol.addView(summaryText)
            headerRow.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(headerRow)

            // Divider
            addView(divider())

            val details = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            details.addView(detailRow("IMEI", "imei"))
            details.addView(detailRow("Android ID", "android_id"))
            details.addView(detailRow("WiFi MAC", "mac_address"))
            details.addView(detailRow("Carrier", "sim_operator"))
            addView(details)
            addView(TextView(this@MainActivity).apply {
                text = "Tap a value to copy it."
                textSize = 11f
                setTextColor(faintColor())
                setPadding(0, dp(6), 0, 0)
            })

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
                text = displayValue(key, "\u2014")
                textSize = 14f
                typeface = Typeface.MONOSPACE
                setTextColor(textSecondary())
                maxLines = 1
                setHorizontallyScrolling(true)
                isClickable = true
                isFocusable = true
                detailViews[key] = this
                setOnClickListener {
                    val value = displayValue(key, "")
                    if (value.isBlank() || value == "\u2014") {
                        Toast.makeText(this@MainActivity, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    } else {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(this@MainActivity, "Copied $label", Toast.LENGTH_SHORT).show()
                    }
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ========== Profile History + Export/Import ==========

    private var historyContainer: LinearLayout? = null
    private val maxHistory = 10

    private fun buildHistory(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Profile History"))

            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(actionButton("\uD83D\uDCE4 Export", Color.parseColor("#2563EB")) {
                exportProfile()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) })
            row.addView(actionButton("\uD83D\uDCE5 Import", Color.parseColor("#7C3AED")) {
                importProfileDialog()
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
            addView(row)
            addView(spacer(10))

            historyContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(historyContainer)
            refreshHistory()
        }
    }

    private fun snapshotToJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for (k in fieldKeys) o.put(k, values[k].orEmpty())
        return o
    }

    private fun pushHistory() {
        try {
            val prefs = getSharedPreferences(historyPrefsName, Context.MODE_PRIVATE)
            val arr = try {
                org.json.JSONArray(prefs.getString("profile_history", "[]"))
            } catch (_: Exception) { org.json.JSONArray() }
            val entry = org.json.JSONObject()
            entry.put("ts", System.currentTimeMillis())
            entry.put("values", snapshotToJson())
            arr.put(entry)
            while (arr.length() > maxHistory) arr.remove(0)
            prefs.edit().putString("profile_history", arr.toString()).apply()
        } catch (_: Exception) {}
        refreshHistory()
    }

    private fun readHistory(): List<Pair<Long, Map<String, String>>> {
        val out = mutableListOf<Pair<Long, Map<String, String>>>()
        try {
            val raw = getSharedPreferences(historyPrefsName, Context.MODE_PRIVATE)
                .getString("profile_history", "[]").orEmpty()
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val v = e.optJSONObject("values") ?: continue
                val map = mutableMapOf<String, String>()
                for (k in fieldKeys) map[k] = v.optString(k, "")
                out.add(e.optLong("ts", 0L) to map)
            }
        } catch (_: Exception) {}
        return out.reversed() // newest first
    }

    private fun refreshHistory() {
        val container = historyContainer ?: return
        container.removeAllViews()
        val entries = readHistory()
        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No saved profiles yet — press Save to record one."
                textSize = 12f
                setTextColor(faintColor())
            })
            return
        }
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
        for ((ts, map) in entries) {
            val mfr = map["manufacturer"]?.takeIf { it.isNotBlank() } ?: "Unknown"
            val model = map["model"]?.takeIf { it.isNotBlank() } ?: "device"
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(TextView(this).apply {
                text = "${fmt.format(java.util.Date(ts))}  •  ${profileTitle(mfr, model)}"
                textSize = 13f
                setTextColor(textSecondary())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Restore"
                textSize = 12f
                setAllCaps(false)
                setTextColor(Color.WHITE)
                background = rounded(Color.parseColor("#2563EB"), 8)
                setOnClickListener {
                    values.putAll(map)
                    for ((k, et) in inputs) et.setText(map[k].orEmpty())
                    dirty = true
                    refreshSummary()
                    Toast.makeText(this@MainActivity, "Profile restored — press Save to apply.", Toast.LENGTH_SHORT).show()
                }
            })
            container.addView(row)
        }
    }

    private fun exportProfile() {
        try {
            val json = snapshotToJson().toString(2)
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("hexhydra-profile", json))
            Toast.makeText(this, "Profile JSON copied to clipboard.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importProfileDialog() {
        val input = EditText(this).apply {
            hint = "Paste profile JSON here"
            textSize = 13f
            setTextColor(textPrimary())
            setHintTextColor(faintColor())
            background = rounded(inputColor(), 8)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this)
            .setTitle("Import profile")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import") { _, _ ->
                try {
                    val o = org.json.JSONObject(input.text.toString())
                    var count = 0
                    for (k in fieldKeys) {
                        if (o.has(k)) { values[k] = o.optString(k, ""); count++ }
                    }
                    if (count == 0) {
                        Toast.makeText(this, "No known fields found in JSON.", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    for ((k, et) in inputs) et.setText(values[k].orEmpty())
                    dirty = true
                    refreshSummary()
                    Toast.makeText(this, "Imported $count fields — press Save to apply.", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid JSON: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    // ========== Settings ==========

    private fun buildSettings(): View {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Module Options"))

            debugLogging = CheckBox(this@MainActivity).apply {
                text = "Debug logging"
                textSize = 14f
                setTextColor(textSecondary())
                isChecked = prefs.getBoolean("setting_debug_log", false)
                setPadding(0, dp(4), 0, dp(4))
            }
            hideSelf = CheckBox(this@MainActivity).apply {
                text = "Hide module from scoped apps"
                textSize = 14f
                setTextColor(textSecondary())
                isChecked = prefs.getBoolean("setting_hide_self", true)
                setPadding(0, dp(4), 0, dp(4))
            }
            addView(debugLogging)
            addView(hideSelf)

            addView(TextView(this@MainActivity).apply {
                text = "Hook categories (restart target apps after changing)"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textHint())
                setPadding(0, dp(12), 0, dp(4))
            })
            hookBoxes.clear()
            for ((key, label) in hookGroups) {
                val box = CheckBox(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(textSecondary())
                    isChecked = prefs.getBoolean(key, true)
                    setPadding(0, dp(4), 0, dp(4))
                }
                hookBoxes[key] = box
                addView(box)
            }
        }
    }

    // ========== Accordion Editor ==========

    private fun buildAccordionEditor(): View {
        return panel().apply {
            orientation = LinearLayout.VERTICAL
            addView(sectionTitle("Field Editor"))
            addView(TextView(this@MainActivity).apply {
                text = "Tap a group to expand and edit its fields."
                textSize = 12f
                setTextColor(faintColor())
                setPadding(0, 0, 0, dp(10))
            })

            addView(EditText(this@MainActivity).apply {
                hint = "\uD83D\uDD0D Search fields…"
                setSingleLine()
                textSize = 14f
                setTextColor(textPrimary())
                setHintTextColor(faintColor())
                background = rounded(cardAltColor(), 8)
                setPadding(dp(12), 0, dp(12), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        searchQuery = s?.toString()?.trim().orEmpty()
                        refreshAccordion()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(10) })

            accordionContainer = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(accordionContainer)

            groups.forEachIndexed { index, group ->
                accordionContainer.addView(buildGroupSection(index, group))
                if (index < groups.size - 1) accordionContainer.addView(spacer(6))
            }
        }
    }

    private fun buildGroupSection(index: Int, group: FieldGroup): View {
        val isExpanded = index in expandedGroups
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardAltColor(), 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = if (isExpanded) "▾  ${group.title}" else "▸  ${group.title}"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textSecondary())
            tag = "header_$index"
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(TextView(this).apply {
            text = "${group.keys.size}"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textHint())
            background = rounded(dividerColor(), 999)
            setPadding(dp(10), dp(2), dp(10), dp(2))
        })

        header.setOnClickListener {
            if (index in expandedGroups) expandedGroups.remove(index)
            else expandedGroups.add(index)
            refreshAccordion()
        }
        card.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (isExpanded) View.VISIBLE else View.GONE
            tag = "content_$index"
            setPadding(0, dp(8), 0, 0)
        }

        // Always populate fields, even for collapsed sections — they sit
        // hidden until expanded. Doing it lazily (only when expanded) caused
        // Randomize All to skip fields in non-default sections, because the
        // EditTexts for those fields never made it into the `inputs` map.
        populateFields(content, group)

        card.addView(content)
        return card
    }

    private fun populateFields(container: LinearLayout, group: FieldGroup) {
        group.keys.forEach { key ->
            val label = fieldLabels[key] ?: key.replace("_", " ").replaceFirstChar { it.uppercase() }
            val labelRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }
            labelRow.addView(TextView(this).apply {
                text = label
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textHint())
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val lockView = TextView(this).apply {
                text = if (key in lockedKeys) "\uD83D\uDD12" else "\uD83D\uDD13"
                contentDescription = if (key in lockedKeys) "Unlock $label" else "Lock $label"
                textSize = 14f
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
                isFocusable = true
            }
            lockView.setOnClickListener {
                if (key in lockedKeys) lockedKeys.remove(key) else lockedKeys.add(key)
                val locked = key in lockedKeys
                lockView.text = if (locked) "\uD83D\uDD12" else "\uD83D\uDD13"
                lockView.contentDescription = if (locked) "Unlock $label" else "Lock $label"
                persistLocks()
                Toast.makeText(
                    this,
                    if (locked) "$label locked — Randomize will keep it" else "$label unlocked",
                    Toast.LENGTH_SHORT
                ).show()
            }
            labelRow.addView(lockView)
            container.addView(labelRow)
            val errorView = TextView(this).apply {
                textSize = 11f
                setTextColor(Color.parseColor("#DC2626"))
                setPadding(0, dp(2), 0, 0)
                visibility = View.GONE
            }
            container.addView(EditText(this).apply {
                setText(values[key].orEmpty())
                setSingleLine()
                textSize = 14f
                setTextColor(textPrimary())
                hint = label
                inputType = inputTypeFor(key)
                background = rounded(inputColor(), 8)
                setPadding(dp(12), 0, dp(12), 0)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        values[key] = s?.toString().orEmpty()
                        dirty = true
                        val err = validateField(key, values[key].orEmpty())
                        errorView.text = err.orEmpty()
                        errorView.visibility = if (err == null) View.GONE else View.VISIBLE
                        refreshSummary()
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
                val initialErr = validateField(key, values[key].orEmpty())
                errorView.text = initialErr.orEmpty()
                errorView.visibility = if (initialErr == null) View.GONE else View.VISIBLE
                inputs[key] = this
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
            container.addView(errorView)
        }
    }

    private fun inputTypeFor(key: String): Int = when (key) {
        "screen_width", "screen_height", "screen_density", "battery_level", "battery_scale",
        "imei", "meid", "imsi", "sim_sub_id" -> InputType.TYPE_CLASS_NUMBER
        "latitude", "longitude" ->
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        "mobile_no" -> InputType.TYPE_CLASS_PHONE
        else -> InputType.TYPE_CLASS_TEXT
    }

    private fun groupMatches(group: FieldGroup): Boolean {
        if (searchQuery.isBlank()) return true
        val q = searchQuery.lowercase()
        if (group.title.lowercase().contains(q)) return true
        return group.keys.any { key ->
            key.contains(q, ignoreCase = true) ||
                (fieldLabels[key]?.contains(q, ignoreCase = true) == true)
        }
    }

    private fun refreshAccordion() {
        for (i in 0 until accordionContainer.childCount) {
            val child = accordionContainer.getChildAt(i)
            if (child is LinearLayout && child.tag == null && child.childCount >= 2) {
                // This is a group card — find header text and content by index
                val headerRow = child.getChildAt(0) as? LinearLayout ?: continue
                val headerText = headerRow.getChildAt(0) as? TextView ?: continue
                val tag = headerText.tag as? String ?: continue
                if (!tag.startsWith("header_")) continue
                val idx = tag.removePrefix("header_").toIntOrNull() ?: continue
                val content = child.getChildAt(1) as? LinearLayout ?: continue
                val group = groups[idx]
                val matches = groupMatches(group)
                // Hide non-matching groups while searching; matching groups auto-expand.
                child.visibility = if (matches) View.VISIBLE else View.GONE
                if (!matches) continue
                val isExpanded = idx in expandedGroups || searchQuery.isNotBlank()

                headerText.text = if (isExpanded) "▾  ${group.title}" else "▸  ${group.title}"
                content.visibility = if (isExpanded) View.VISIBLE else View.GONE

                if (isExpanded && content.childCount == 0) {
                    populateFields(content, group)
                }
            }
        }
    }

    // ========== Footer ==========

    private fun buildFooter(): View {
        return TextView(this).apply {
            text = "Scope target apps in LSPosed to spoof them.\nAfter saving, restart the target app to apply."
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(faintColor())
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }
    }

    // ========== Data ==========

    private fun loadValues() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fieldKeys.forEach { values[it] = prefs.getString(it, "").orEmpty() }
        lockedKeys.clear()
        prefs.getString("locked_fields", "").orEmpty()
            .split(",").map { it.trim() }.filter { it in fieldKeys }
            .forEach { lockedKeys.add(it) }
        dirty = false
    }

    private fun persistLocks() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("locked_fields", lockedKeys.sorted().joinToString(","))
            .apply()
    }

    private fun randomizeAll() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle("Discard unsaved edits?")
                .setMessage("Randomizing replaces the values you edited but haven't saved.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Randomize") { _, _ -> doRandomize() }
                .show()
        } else {
            doRandomize()
        }
    }

    private fun doRandomize() {
        // Locked fields survive: snapshot first, restore after generating.
        val preserved = lockedKeys.associateWith { values[it].orEmpty() }
        val fresh = FakeData.generateAll().toMutableMap()
        for ((k, v) in preserved) fresh[k] = v
        // Brief crossfade on the profile card for visual feedback
        val deviceCard = summaryText.parent?.parent as? ViewGroup
        deviceCard?.animate()?.alpha(0.3f)?.setDuration(120)?.withEndAction {
            values.putAll(fresh)
            for ((key, editText) in inputs) {
                editText.setText(values[key].orEmpty())
            }
            refreshSummary()
            deviceCard.animate().alpha(1f).setDuration(200).start()
        }?.start() ?: run {
            values.putAll(fresh)
            for ((key, editText) in inputs) {
                editText.setText(values[key].orEmpty())
            }
            refreshSummary()
        }
        Toast.makeText(this, "New profile generated.", Toast.LENGTH_SHORT).show()
        dirty = true
    }

    private fun refreshSummary() {
        if (!::summaryText.isInitialized) return
        val mfr = displayValue("manufacturer", "Unknown")
        val model = displayValue("model", "Unknown")
        val android = displayValue("android_version", "0")
        summaryText.text = "${profileTitle(mfr, model)}\nAndroid $android"

        // Refresh detail views in the device card
        for (key in listOf("imei", "android_id", "mac_address", "sim_operator")) {
            detailViews[key]?.text = displayValue(key, "\u2014")
        }
    }

    private fun displayValue(key: String, fallback: String): String {
        return values[key]?.takeIf { it.isNotBlank() } ?: fallback
    }

    /**
     * Some profiles store the brand inside `model` already ("OnePlus 13R",
     * "Xiaomi 15"), so joining manufacturer + model would print it twice.
     * Collapse to the model when it already starts with the manufacturer.
     */
    private fun profileTitle(manufacturer: String, model: String): String =
        if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"

    // ========== Save ==========

    /**
     * Cross-process bridge for ROMs that keep app prefs outside the path
     * XSharedPreferences reads. On this Nothing OS device the framework stores
     * prefs under /data/misc/<uuid>/prefs/<pkg>/ instead of
     * /data/user/<id>/<pkg>/shared_prefs/, so XSharedPreferences always comes
     * back empty and the module falls back to random values per process.
     *
     * System properties are globally readable, and the module already reads
     * them via readFromSystemProperties(). We push the saved config there with
     * root (same mechanism the module's own bridge uses), so every scoped app
     * gets the SAME saved identity instead of a fresh random one.
     */
    private fun pushConfigToSystemProperties() {
        try {
            val hooks = HashMap<String, Boolean>()
            for ((key, box) in hookBoxes) hooks[key] = box.isChecked
            Bridge.pushToSystemProperties(Bridge.buildPropMap(values, debugLogging.isChecked, hideSelf.isChecked, hooks))
            logToLogcat("Bridge props pushed")
        } catch (e: Exception) {
            logToLogcat("Bridge push failed: ${e.message}")
        }
    }

    private fun saveConfig(saveButton: Button? = null) {
        val invalid = fieldKeys.mapNotNull { key ->
            validateField(key, values[key].orEmpty())?.let { key to it }
        }
        if (invalid.isNotEmpty()) {
            val names = invalid.take(3).joinToString(", ") { (key, _) -> fieldLabels[key] ?: key }
            val more = if (invalid.size > 3) " +${invalid.size - 3} more" else ""
            Toast.makeText(this, "Fix invalid fields: $names$more", Toast.LENGTH_LONG).show()
            val firstKey = invalid.first().first
            groups.forEachIndexed { index, group -> if (firstKey in group.keys) expandedGroups.add(index) }
            refreshAccordion()
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putBoolean("setting_debug_log", debugLogging.isChecked)
        editor.putBoolean("setting_hide_self", hideSelf.isChecked)
        for ((key, box) in hookBoxes) editor.putBoolean(key, box.isChecked)
        fieldKeys.forEach { editor.putString(it, values[it].orEmpty().trim()) }

        val saved = editor.commit()
        if (saved) {
            dirty = false
            pushHistory()
            pushConfigToSystemProperties()
            logToLogcat("Config saved via commit()")
        } else {
            logToLogcat("Config save failed via commit()")
        }

        try {
            val dataDir = File(applicationInfo.dataDir)
            val prefsDir = File(dataDir, "shared_prefs")
            val prefsFile = File(prefsDir, "${PREFS_NAME}.xml")
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false)
                prefsDir.setReadable(true, false)
                prefsDir.setExecutable(true, false)
                dataDir.setReadable(true, false)
                dataDir.setExecutable(true, false)
            }
        } catch (e: Exception) {
            logToLogcat("Permission fix failed: ${e.message}")
        }

        // Brief confirmation animation on the save button
        saveButton?.let { btn ->
            val originalText = btn.text.toString()
            btn.text = "✓ Saved!"
            btn.background = rounded(Color.parseColor("#10B981"), 10)
            btn.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150)
                .withEndAction {
                    btn.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }.start()
            btn.postDelayed({
                btn.text = originalText
                btn.background = rounded(Color.parseColor("#059669"), 10)
            }, 1200)
        }
    }

    // ========== Soft Reboot ==========

    private fun hasRoot(): Boolean = try {
        Runtime.getRuntime().exec("which su").waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun softReboot() {
        if (!hasRoot()) {
            Toast.makeText(this, "Root (su) not available — soft reboot needs root.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Soft reboot?")
            .setMessage("This restarts the Android runtime (system_server). The screen will go black for ~15s. Save your work in other apps first.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reboot") { _, _ -> doSoftReboot() }
            .show()
    }

    private fun doSoftReboot() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = process.outputStream
            os.write("killall -9 system_server\n".toByteArray())
            os.write("exit\n".toByteArray())
            os.flush()
            os.close()
            process.waitFor()
            Toast.makeText(this, "Soft reboot triggered — system will restart in ~15s.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Soft reboot failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ========== Helpers ==========

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(panelColor(), 14)
            elevation = dp(1).toFloat()
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textPrimary())
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun actionButton(label: String, color: Int, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setAllCaps(false)
            background = rounded(color, 10)
            setOnClickListener { onClick() }
        }
    }

    private fun divider(): View {
        return View(this).apply {
            background = GradientDrawable().apply { setColor(dividerColor()) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun spacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun logToLogcat(msg: String) {
        android.util.Log.d("HexHydra", msg)
    }
}
