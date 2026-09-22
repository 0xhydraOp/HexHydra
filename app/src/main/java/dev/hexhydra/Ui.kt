package dev.hexhydra

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Central design tokens + tiny view factories. Every screen builds from these
 * so the whole app stays visually consistent. Pure view code — no module or
 * hook logic lives here.
 */
object Ui {

    // ---- dark-mode helper ----
    fun isDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun c(ctx: Context, light: String, dark: String): Int =
        Color.parseColor(if (isDark(ctx)) dark else light)

    // ---- palette ----
    fun bg(ctx: Context) = c(ctx, "#F1F5F9", "#0B1220")
    fun card(ctx: Context) = c(ctx, "#FFFFFF", "#151E2E")
    fun cardAlt(ctx: Context) = c(ctx, "#F8FAFC", "#101A2B")
    fun input(ctx: Context) = c(ctx, "#FFFFFF", "#0D1522")
    fun textPrimary(ctx: Context) = c(ctx, "#0F172A", "#F8FAFC")
    fun textSecondary(ctx: Context) = c(ctx, "#334155", "#CBD5E1")
    fun textHint(ctx: Context) = c(ctx, "#64748B", "#94A3B8")
    fun faint(ctx: Context) = c(ctx, "#94A3B8", "#64748B")
    fun divider(ctx: Context) = c(ctx, "#E2E8F0", "#263449")

    const val ACCENT: Int = 0xFF0D9488.toInt()   // teal — primary actions, selected nav
    const val DANGER: Int = 0xFFDC2626.toInt()
    const val SUCCESS: Int = 0xFF10B981.toInt()
    const val WARN: Int = 0xFFD97706.toInt()
    const val DOT_INACTIVE: Int = 0xFFEF4444.toInt()

    fun okBg(ctx: Context) = c(ctx, "#ECFDF5", "#06281F")
    fun okText(ctx: Context) = c(ctx, "#065F46", "#6EE7B7")
    fun badBg(ctx: Context) = c(ctx, "#FEF2F2", "#2E0B0B")
    fun badText(ctx: Context) = c(ctx, "#991B1B", "#FCA5A5")

    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    // ---- spacing scale (dp) ----
    const val XS = 4
    const val SM = 8
    const val MD = 12
    const val LG = 16
    const val XL = 24

    // ---- corner radii (dp) ----
    const val R_CARD = 14
    const val R_BUTTON = 10
    const val R_PILL = 999

    // ---- type sizes (sp) ----
    const val T_CAPTION = 11f
    const val T_BODY = 14f
    const val T_TITLE = 16f
    const val T_HEADER = 20f

    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun outlined(ctx: Context, color: Int, radiusDp: Int, widthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setStroke(dp(ctx, widthDp), color)
        }

    // ---- tiny factories ----
    fun cardView(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, LG), dp(ctx, LG), dp(ctx, LG), dp(ctx, LG))
        background = rounded(ctx, card(ctx), R_CARD)
        elevation = dp(ctx, 1).toFloat()
    }

    fun sectionTitle(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setTextColor(textHint(ctx))
        setPadding(0, 0, 0, dp(ctx, MD))
    }

    fun caption(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        textSize = T_CAPTION
        setTextColor(faint(ctx))
    }

    fun dividerView(ctx: Context): View = View(ctx).apply {
        background = GradientDrawable().apply { setColor(divider(ctx)) }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)
        ).apply {
            topMargin = dp(ctx, SM)
            bottomMargin = dp(ctx, SM)
        }
    }

    fun spacer(ctx: Context, heightDp: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, heightDp)
        )
    }
}
