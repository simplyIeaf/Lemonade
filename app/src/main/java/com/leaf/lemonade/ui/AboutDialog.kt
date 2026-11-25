package com.leaf.lemonade.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** File → About and the start page's About: version, credits and where to get the latest version. */
object AboutDialog {
    const val YOUTUBE_URL = "https://www.youtube.com/@fiowwwys/featured"
    const val DOWNLOAD_URL = "https://www.mediafire.com/folder/33xwlczg9fxs1/mobile+studio"

    /** The installed version name and number, e.g. "1.1" and 2. */
    fun version(ctx: Context): Pair<String, Long> = try {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        (pi.versionName ?: "?") to code
    } catch (_: Exception) { "?" to 0L }

    fun open(act: Activity, url: String) {
        try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { StudioToast.show(act, "No browser found to open $url", true) }
    }

    fun show(act: Activity) {
        fun dp(v: Float) = Ui.dp(act, v)
        val (ver, code) = version(act)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20f), dp(18f), dp(20f), dp(6f)) }

        // App name and version.
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(act).apply {
            setImageDrawable(Icons.general(act, "Place"))
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = GradientDrawable().apply { setColor(Ui.blend(Ui.PANEL, Ui.ACCENT, 0.22f)); cornerRadius = dp(12f).toFloat() }
        }, Ui.lp(dp(52f), dp(52f)).apply { rightMargin = dp(14f) })
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.addView(Ui.text(act, "Lemonade", 20f, Ui.TEXT, true))
        names.addView(Ui.text(act, "Version $ver  (build $code)", 13f, Ui.DIM))
        head.addView(names, Ui.lp(0, Ui.WRAP, 1f))
        box.addView(head)

        box.addView(Ui.text(act, "A Roblox-Studio-style editor for Android: open, build, script, playtest and publish your places on the go.", 13f, Ui.TEXT).apply {
            isSingleLine = false; setPadding(0, dp(14f), 0, dp(4f))
        })

        fun section(title: String) = box.addView(Ui.text(act, title.uppercase(), 11f, Ui.DIM, true).apply { letterSpacing = 0.08f; setPadding(0, dp(16f), 0, dp(6f)) })
        fun linkButton(icon: String, label: String, sub: String, url: String) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10f), dp(9f), dp(12f), dp(9f))
                background = Ui.rippleBg(act, Ui.CARD, 10f)
                isClickable = true
                setOnClickListener { open(act, url) }
            }
            row.addView(ImageView(act).apply { setImageDrawable(Icons.general(act, icon)) }, Ui.lp(dp(26f), dp(26f)).apply { rightMargin = dp(12f) })
            val t = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            t.addView(Ui.text(act, label, 14f, Ui.TEXT, true))
            t.addView(Ui.text(act, sub, 11.5f, Ui.DIM).apply { ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(t, Ui.lp(0, Ui.WRAP, 1f))
            row.addView(Ui.text(act, "›", 20f, Ui.DIM))
            box.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP).apply { bottomMargin = dp(6f) })
        }

        section("Credits")
        linkButton("Video", "Made by fiowwwys", "YouTube · youtube.com/@fiowwwys", YOUTUBE_URL)
        section("Updates")
        linkButton("Download", "Get the latest version", "Download from MediaFire", DOWNLOAD_URL)
        box.addView(Ui.text(act, "You have version $ver. Check the download folder for newer builds.", 11.5f, Ui.DIM).apply { isSingleLine = false; setPadding(dp(2f), dp(2f), 0, 0) })

        section("Acknowledgements")
        box.addView(Ui.text(act, "Fonts: Montserrat, Source Sans 3, Fredoka One and other SIL Open Font License fonts. " +
            "Toolbox content comes from the Roblox Creator Store. Not affiliated with Roblox Corporation.", 11.5f, Ui.DIM).apply { isSingleLine = false; setPadding(0, 0, 0, dp(6f)) })

        Dialogs.themed(act).setView(ScrollView(act).apply { addView(box) }).setPositiveButton("Close", null).show()
    }
}
