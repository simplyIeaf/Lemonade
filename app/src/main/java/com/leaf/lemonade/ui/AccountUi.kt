package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Recents
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.engine.Avatar
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.Color3
import com.leaf.lemonade.net.Accounts
import com.leaf.lemonade.net.AuthRequiredException
import com.leaf.lemonade.net.CloudPlace
import com.leaf.lemonade.net.ImageLoader
import com.leaf.lemonade.net.PublishException
import com.leaf.lemonade.net.RobloxApi
import com.leaf.lemonade.net.RobloxCloud
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Roblox account features: sign in (a normal Roblox login page, so 2-step verification, captchas
 * and Quick Login all work), several saved accounts and guest mode, the playtest avatar, browsing
 * your games, opening/downloading places and old versions, saving and publishing.
 */
class LogoutDrawable(private val color: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
    }
    override fun draw(c: android.graphics.Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        val x = b.left + (b.width() - s) / 2f
        val y = b.top + (b.height() - s) / 2f
        paint.color = color
        paint.strokeWidth = s * 0.09f
        val p = android.graphics.Path()
        p.moveTo(x + s * 0.45f, y + s * 0.15f); p.lineTo(x + s * 0.18f, y + s * 0.15f); p.lineTo(x + s * 0.18f, y + s * 0.85f); p.lineTo(x + s * 0.45f, y + s * 0.85f)
        p.moveTo(x + s * 0.4f, y + s * 0.5f); p.lineTo(x + s * 0.85f, y + s * 0.5f)
        p.moveTo(x + s * 0.7f, y + s * 0.32f); p.lineTo(x + s * 0.88f, y + s * 0.5f); p.lineTo(x + s * 0.7f, y + s * 0.68f)
        c.drawPath(p, paint)
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(f: android.graphics.ColorFilter?) { paint.colorFilter = f }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

object AccountUi {
    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ helpers

    /** Runs [work] off the UI thread with a small progress dialog, then [done] on the UI thread. */
    fun <T> background(act: Activity, message: String, work: () -> T, done: (T) -> Unit) = background(act, message, work, done, null)

    fun <T> background(act: Activity, message: String, work: () -> T, done: (T) -> Unit, failed: ((Throwable) -> Boolean)?) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(act, 20f), Ui.dp(act, 18f), Ui.dp(act, 20f), Ui.dp(act, 12f)) }
        box.addView(StudioSpinner(act), Ui.lp(Ui.dp(act, 28f), Ui.dp(act, 28f)))
        box.addView(Ui.text(act, message, 14f, StudioStyle.text).apply { isSingleLine = false; setPadding(Ui.dp(act, 14f), 0, 0, 0) })
        var cancelled = false
        val dlg = Dialogs.themed(act).setView(box).setWidthDp(340).setNegativeButton("Cancel") { _, _ -> cancelled = true }.create()
        dlg.setOnCancelListener { cancelled = true }
        dlg.show()
        net.execute {
            val result = try { Result.success(work()) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (act.isFinishing) return@post
                try { dlg.dismiss() } catch (_: Exception) {}
                if (cancelled) return@post
                result.fold(done) { e -> if (failed?.invoke(e) != true) showError(act, e) }
            }
        }
    }

    private fun showError(act: Activity, e: Throwable) {
        if (e is AuthRequiredException) {
            Dialogs.themed(act).setTitle("Sign in needed").setMessage(e.message).setIcon(StudioIcon.WARNING)
                .setPositiveButton("Sign in") { _, _ -> signIn(act) }.setNegativeButton("Cancel", null).show()
        } else {
            OutputLog.error("Roblox: ${e.message}")
            Dialogs.themed(act).setTitle("Something went wrong").setMessage(e.message ?: e.toString()).setIcon(StudioIcon.ERROR).setPositiveButton("OK", null).show()
        }
    }

    private fun toast(act: Activity, s: String) = StudioToast.show(act, s)

    fun signedIn(act: Activity) = RobloxApi.cookie(act).isNotEmpty()

    private val dateIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val dateOut = SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault())
    fun prettyDate(s: String): String = try { dateOut.format(dateIn.parse(s.take(19))!!) } catch (_: Exception) { s }

    /** Loads a user's round headshot into [img] (falls back to the account icon). */
    fun loadHeadshot(img: ImageView, userId: Long) {
        img.setImageDrawable(Icons.general(img.context, "Account"))
        if (userId == 0L) return
        net.execute {
            val url = RobloxCloud.headshot(userId) ?: return@execute
            val key = "url:$url"
            fun apply() { ImageLoader.getKey(key)?.let { b -> main.post { if (img.tag == userId) img.setImageBitmap(b) } } }
            img.tag = userId
            apply()
            ImageLoader.listeners.add { k -> if (k == key) apply() }
        }
    }

    // ------------------------------------------------------------------ account menu

    fun show(act: Activity, studio: Studio, actions: MainActions) {
        fun dp(v: Float) = Ui.dp(act, v)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), dp(4f), dp(14f), dp(8f)) }
        var dlg: StudioDialog? = null
        val active = Accounts.active(act)
        val rowBase = if (Ui.LIGHT) Ui.CARD else 0xFF33343A.toInt()
        fun accountRow(a: Accounts.Account?, current: Boolean, add: Boolean = false, onClick: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
                background = Ui.rippleBg(act, if (current) Ui.blend(rowBase, Ui.ACCENT, 0.35f) else rowBase, 6f)
                isClickable = true
                setOnClickListener { onClick() }
            }
            val img = ImageView(act)
            if (add) {
                img.setImageDrawable(null)
                row.addView(Ui.text(act, "+", 22f, StudioStyle.text, true).apply {
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.INPUT) }
                }, Ui.lp(dp(40f), dp(40f)).apply { rightMargin = dp(12f) })
            } else {
                row.addView(img, Ui.lp(dp(40f), dp(40f)).apply { rightMargin = dp(12f) })
                if (a != null) loadHeadshot(img, a.userId) else img.setImageDrawable(Icons.general(act, "Account"))
            }
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(act, if (add) "Add account" else a?.displayName?.ifEmpty { a.name } ?: "Guest", 14f, StudioStyle.text, true))
            texts.addView(Ui.text(act, when {
                add -> "Sign in with Roblox"
                a != null -> "@${a.name}" + (if (current) "  ·  signed in" else "")
                current -> "Not signed in"
                else -> "Use without an account"
            }, 12f, StudioStyle.dim))
            row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            if (current) row.addView(Ui.text(act, "✓", 16f, Ui.ACCENT, true).apply { setPadding(0, 0, dp(6f), 0) })
            if (a != null) row.addView(Ui.iconButton(act, LogoutDrawable(StudioStyle.text), 34f) {
                dlg?.dismiss()
                if (current) signOut(act) else Dialogs.confirm(act, "Sign out", "Remove @${a.name} from this device?", "Sign out") {
                    Accounts.remove(act, a.userId)
                    show(act, studio, actions)
                }
            }.apply { contentDescription = "Log out" })
            return row
        }
        box.addView(accountRow(active, true) {})
        for (a in Accounts.list(act).filter { it.userId != active?.userId }) {
            box.addView(accountRow(a, false) {
                dlg?.dismiss()
                Accounts.switchTo(act, a)
                Avatar.refresh(act)
                toast(act, "Switched to @${a.name}")
                background(act, "Checking @${a.name}…", { RobloxCloud.currentUser(act) }, { }) { e ->
                    if (e is AuthRequiredException) {
                        Dialogs.themed(act).setTitle("Sign in again").setMessage("The saved sign-in for @${a.name} has expired.").setIcon(StudioIcon.WARNING)
                            .setPositiveButton("Sign in") { _, _ -> signIn(act) }.setNegativeButton("Cancel", null).show()
                        true
                    } else true
                }
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        }
        if (active != null) box.addView(accountRow(null, false) {
            dlg?.dismiss(); Accounts.useGuest(act); Avatar.refresh(act); toast(act, "Using Lemonade as a guest")
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        box.addView(accountRow(null, false, add = true) {
            dlg?.dismiss(); signIn(act) { show(act, studio, actions) }
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(6f) })
        dlg = Dialogs.themed(act).setTitle("Accounts").setWidthDp(400).setView(android.widget.ScrollView(act).apply { addView(box) })
            .setNegativeButton("Close", null).show()
        if (active != null && active.name.isEmpty()) net.execute { try { RobloxCloud.currentUser(act) } catch (_: Exception) {} }
    }

    fun advanced(act: Activity, continueOnSkip: Boolean = false, then: (() -> Unit)? = null) {
        fun dp(v: Float) = Ui.dp(act, v)
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16f), dp(4f), dp(16f), dp(8f)) }
        box.addView(Ui.text(act, "Lemonade publishes places through Roblox's Open Cloud API, which needs an API key. To create one:", 12f, StudioStyle.text, true).apply { isSingleLine = false })
        box.addView(Ui.text(act, "1. Open create.roblox.com → Open Cloud → API Keys → Create API Key.\n" +
            "2. Under Access Permissions add \"universe-places\" (Write) and pick your experiences (or allow all). Add \"legacy-asset\" read access too if you want key-based downloads.\n" +
            "3. Add IP address 0.0.0.0/0 (or leave restrictions off), save, and copy the key here.", 12f, StudioStyle.text).apply { isSingleLine = false; setLineSpacing(dp(2f).toFloat(), 1f) })
        box.addView(studioButton(act, "Open the API Keys page") {
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://create.roblox.com/dashboard/credentials"))) } catch (_: Exception) {}
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10f) })
        val key = Ui.input(act, RobloxApi.apiKey(act), "Open Cloud API key").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(key, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(10f) })
        Dialogs.themed(act).setTitle("Open Cloud API key").setWidthDp(460).setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                if (key.text.toString().isBlank()) toast(act, "Enter your API key to publish")
                else {
                    RobloxApi.setApiKey(act, key.text.toString())
                    toast(act, "API key saved")
                    then?.invoke()
                }
            }
            .setNegativeButton(if (continueOnSkip) "Skip" else "Cancel") { _, _ -> if (continueOnSkip) then?.invoke() }.show()
    }

    /** Shows Roblox's own login page and keeps the session it hands back (as a new saved account). */
    @SuppressLint("SetJavaScriptEnabled")
    fun signIn(act: Activity, then: (() -> Unit)? = null) {
        val dlg = Dialog(act, android.R.style.Theme_NoTitleBar_Fullscreen)
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.PANEL) }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.TOPBAR); setPadding(Ui.dp(act, 12f), Ui.dp(act, 6f), Ui.dp(act, 8f), Ui.dp(act, 6f)) }
        bar.addView(Ui.text(act, "Sign in to Roblox", 15f, StudioStyle.text, true), Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(studioButton(act, "Cancel") { dlg.dismiss() })
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        val progress = StudioProgressBar(act)
        root.addView(progress, Ui.lp(Ui.MATCH, Ui.dp(act, 3f)))
        val web = WebView(act)
        root.addView(web, Ui.lp(Ui.MATCH, 0, 1f))
        dlg.setContentView(root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)
        cm.removeAllCookies(null)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        var done = false
        val previous = RobloxApi.cookie(act)
        fun check() {
            if (done) return
            val all = cm.getCookie("https://www.roblox.com") ?: return
            val m = Regex("""(?:^|;\s*)\.ROBLOSECURITY=([^;]+)""").find(all) ?: return
            if (m.groupValues[1] == previous) return
            done = true
            RobloxApi.setCookie(act, m.groupValues[1])
            RobloxCloud.resetSession()
            // The app keeps its own copy; clear the browser's so it isn't left in the WebView.
            cm.removeAllCookies(null); cm.flush()
            dlg.dismiss()
            background(act, "Signing in…", { RobloxCloud.currentUser(act) }) { u ->
                toast(act, "Signed in as @${u.name}")
                OutputLog.info("Signed in to Roblox as @${u.name}")
                Avatar.refresh(act)
                if (RobloxApi.apiKey(act).isEmpty()) advanced(act, true) { then?.invoke() } else then?.invoke()
            }
        }
        val poll = object : Runnable { override fun run() { check(); if (!done && dlg.isShowing) main.postDelayed(this, 800) } }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val host = u.host?.lowercase() ?: ""
                val ok = u.scheme == "https" && (host == "roblox.com" || host.endsWith(".roblox.com"))
                return !ok
            }
            override fun onPageFinished(view: WebView?, url: String?) { progress.visibility = View.GONE; check() }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { progress.visibility = View.VISIBLE }
        }
        dlg.setOnDismissListener { done = true; web.stopLoading(); web.destroy() }
        dlg.show()
        web.loadUrl("https://www.roblox.com/login")
        main.postDelayed(poll, 800)
    }

    fun signOut(act: Activity) {
        val name = RobloxApi.userName(act).ifEmpty { "your account" }
        Dialogs.confirm(act, "Sign out", "Sign out of @$name? This also ends that session on Roblox.", "Sign out") {
            val id = RobloxApi.userId(act)
            background(act, "Signing out…", { RobloxCloud.logout(act) }) {
                Accounts.remove(act, id)
                val next = Accounts.list(act).firstOrNull()
                if (next != null) Accounts.switchTo(act, next) else Accounts.useGuest(act)
                CookieManager.getInstance().removeAllCookies(null)
                Avatar.refresh(act)
                toast(act, if (next != null) "Signed out. Now using @${next.name}" else "Signed out")
            }
        }
    }

    private fun requireSignIn(act: Activity, then: () -> Unit) {
        if (signedIn(act)) then() else signIn(act, then)
    }

    // ------------------------------------------------------------------ avatar

    /** Which avatar the playtest character wears (see [AvatarUi.playtestAvatar]). */
    fun avatarDialog(act: Activity, studio: Studio? = null) = AvatarUi.playtestAvatar(act, studio)

    fun customAvatar(act: Activity) {
        val o = Avatar.custom(act)
        val colors = o.optJSONObject("colors") ?: JSONObject()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 16f), Ui.dp(act, 6f), Ui.dp(act, 16f), 0) }
        box.addView(Ui.text(act, "Body colors", 13f, Ui.TEXT, true))
        val keys = listOf("head" to "Head", "torso" to "Torso", "leftArm" to "Left Arm", "rightArm" to "Right Arm", "leftLeg" to "Left Leg", "rightLeg" to "Right Leg")
        val chosen = HashMap<String, Color3>()
        for ((k, label) in keys) {
            val c = Color3.fromHex(colors.optString(k)) ?: Avatar.CLASSIC_COLORS[label]!!
            chosen[k] = c
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(act, 3f), 0, Ui.dp(act, 3f)) }
            row.addView(Ui.text(act, label, 13f), Ui.lp(0, Ui.WRAP, 1f))
            val sw = View(act)
            fun paint() { sw.background = android.graphics.drawable.GradientDrawable().apply { setColor(chosen[k]!!.toArgb()); cornerRadius = Ui.dp(act, 4f).toFloat(); setStroke(1, 0xFF888888.toInt()) } }
            paint()
            sw.setOnClickListener { Dialogs.colorPicker(act, label, chosen[k]!!) { v -> chosen[k] = v as Color3; paint() } }
            row.addView(sw, Ui.lp(Ui.dp(act, 44f), Ui.dp(act, 26f)))
            box.addView(row)
        }
        fun field(label: String, key: String, hint: String): android.widget.EditText {
            box.addView(Ui.text(act, label, 13f, Ui.TEXT, true).apply { setPadding(0, Ui.dp(act, 10f), 0, Ui.dp(act, 3f)) })
            val e = Ui.input(act, o.optString(key), hint)
            box.addView(e, Ui.lp(Ui.MATCH, Ui.WRAP))
            return e
        }
        val shirt = field("Shirt", "shirt", "Shirt ID or catalog link")
        val pants = field("Pants", "pants", "Pants ID or catalog link")
        val tshirt = field("T-Shirt", "tshirt", "T-Shirt ID or link")
        val face = field("Face", "face", "Face ID or link")
        val acc = field("Accessories (hats, hair…)", "accessories", "IDs or links, separated by commas")
        box.addView(Ui.text(act, "Clothing and accessories are downloaded from Roblox, so they need a signed-in account.", 11f, Ui.DIM).apply { isSingleLine = false; setPadding(0, Ui.dp(act, 8f), 0, 0) })
        Dialogs.themed(act).setTitle("Create an avatar").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Wear it") { _, _ ->
                val c = JSONObject(); for ((k, v) in chosen) c.put(k, v.toHex())
                val out = JSONObject().put("colors", c).put("shirt", shirt.text.toString().trim()).put("pants", pants.text.toString().trim())
                    .put("tshirt", tshirt.text.toString().trim()).put("face", face.text.toString().trim()).put("accessories", acc.text.toString().trim())
                Avatar.setCustom(act, out)
                toast(act, "Custom avatar saved")
            }
            .setNeutralButton("Start from a user…") { _, _ ->
                Dialogs.prompt(act, "Copy colors from which username?", "") { name ->
                    background(act, "Loading @$name…", {
                        val u = RobloxCloud.userByName(name) ?: throw Exception("No Roblox user named \"$name\".")
                        com.leaf.lemonade.net.Http.json("https://avatar.roblox.com/v2/avatar/users/${u.id}/avatar")
                    }) { j ->
                        val bc = j.optJSONObject("bodyColor3s")
                        val c = JSONObject()
                        if (bc != null) for ((k, _) in keys) bc.optString("${k}Color3").takeIf { it.isNotEmpty() }?.let { c.put(k, it) }
                        val assets = j.optJSONArray("assets")
                        val next = JSONObject().put("colors", c)
                        val accs = ArrayList<String>()
                        if (assets != null) for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i); val t = a.optJSONObject("assetType")?.optInt("id") ?: 0; val id = a.optLong("id").toString()
                            when (t) { 11 -> next.put("shirt", id); 12 -> next.put("pants", id); 2 -> next.put("tshirt", id); 18 -> next.put("face", id); 8, 41, 42, 43, 44, 45, 46, 47 -> accs.add(id) }
                        }
                        next.put("accessories", accs.joinToString(", "))
                        main.post {
                            act.getSharedPreferences("avatar", android.content.Context.MODE_PRIVATE).edit().putString("custom", next.toString()).apply()
                            customAvatar(act)
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ games

    private class Row(val title: String, val sub: String, val iconUrl: String?, val iconFallback: android.graphics.drawable.Drawable?, val onClick: () -> Unit)

    /** A simple list dialog with icons loaded from URLs. */
    private fun listDialog(act: Activity, title: String, rows: List<Row>, empty: String, extra: ((StudioDialog.Builder) -> Unit)? = null): StudioDialog {
        val lv = ListView(act).apply { divider = null; selector = android.graphics.drawable.ColorDrawable(0) }
        val listeners = ArrayList<(String) -> Unit>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(p: Int) = rows[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
                val r = rows[p]
                val v = (convert as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(Ui.dp(act, 12f), Ui.dp(act, 8f), Ui.dp(act, 12f), Ui.dp(act, 8f))
                    addView(ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP }, Ui.lp(Ui.dp(act, 44f), Ui.dp(act, 44f)).apply { rightMargin = Ui.dp(act, 12f) })
                    addView(LinearLayout(act).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(Ui.text(act, "", 14f, StudioStyle.text, true))
                        addView(Ui.text(act, "", 12f, StudioStyle.dim))
                    }, Ui.lp(0, Ui.WRAP, 1f))
                }
                val img = v.getChildAt(0) as ImageView
                val texts = v.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = r.title
                (texts.getChildAt(1) as TextView).apply { text = r.sub; visibility = if (r.sub.isEmpty()) View.GONE else View.VISIBLE }
                val bmp = r.iconUrl?.let { ImageLoader.getKey("url:$it") }
                if (bmp != null) img.setImageBitmap(bmp) else img.setImageDrawable(r.iconFallback)
                return v
            }
        }
        lv.adapter = adapter
        val l: (String) -> Unit = { k -> if (k.startsWith("url:")) main.post { adapter.notifyDataSetChanged() } }
        listeners.add(l); ImageLoader.listeners.add(l)
        lateinit var dlg: StudioDialog
        lv.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); rows[pos].onClick() }
        val content: View = if (rows.isEmpty()) Ui.text(act, empty, 14f, StudioStyle.dim).apply { isSingleLine = false; setPadding(Ui.dp(act, 20f), Ui.dp(act, 16f), Ui.dp(act, 20f), Ui.dp(act, 16f)) } else lv
        val b = Dialogs.themed(act).setTitle(title).setView(content).setNegativeButton("Close", null)
        extra?.invoke(b)
        dlg = b.create()
        dlg.setOnDismissListener { listeners.forEach { ImageLoader.listeners.remove(it) } }
        dlg.show()
        return dlg
    }

    /**
     * Lists your experiences (and group experiences you can edit). With [pick] set, choosing a
     * place calls it instead of showing the open/download menu (used by Publish As).
     */
    fun myGames(act: Activity, studio: Studio, actions: MainActions, pick: ((RobloxCloud.Game, RobloxCloud.Place) -> Unit)? = null) {
        requireSignIn(act) {
            background(act, "Loading your games…", {
                val u = RobloxCloud.currentUser(act)
                val games = RobloxCloud.games(act, u)
                games to RobloxCloud.gameIcons(games.map { it.universeId })
            }) { (games, icons) ->
                val rows = games.map { g ->
                    Row(g.name, "${g.owner}${if (g.updated.isNotEmpty()) "  ·  updated " + prettyDate(g.updated) else ""}", icons[g.universeId], Icons.general(act, "Place")) {
                        places(act, studio, actions, g, pick)
                    }
                }
                listDialog(act, if (pick != null) "Publish to which game?" else "My Games", rows,
                    if (pick != null) "No games yet. Tap “Create new game…” to make one from the place you're editing."
                    else "No games found on this account. Use File → Publish to Roblox As → Create new game, or create one on create.roblox.com.") { b ->
                    if (pick == null) b.setNeutralButton("Open by place ID…") { _, _ -> openById(act, studio, actions) }
                    else b.setNeutralButton("Create new game…") { _, _ -> createNewGame(act, studio, actions) }
                }
            }
        }
    }

    fun places(act: Activity, studio: Studio, actions: MainActions, g: RobloxCloud.Game, pick: ((RobloxCloud.Game, RobloxCloud.Place) -> Unit)?) {
        background(act, "Loading places…", { RobloxCloud.places(act, g) }) { list ->
            if (list.size == 1) { if (pick != null) pick(g, list[0]) else placeActions(act, studio, actions, g, list[0]); return@background }
            listDialog(act, g.name, list.map { p ->
                Row(p.name, "Place ${p.id}${if (p.id == g.rootPlaceId) "  ·  start place" else ""}", null, Icons.general(act, "Place")) {
                    if (pick != null) pick(g, p) else placeActions(act, studio, actions, g, p)
                }
            }, "This experience has no places.")
        }
    }

    private fun safeFile(name: String) = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Place" }.take(60)

    fun placeActions(act: Activity, studio: Studio, actions: MainActions, g: RobloxCloud.Game, p: RobloxCloud.Place) {
        val items = listOf(
            "Open (edit this place)" to { openPlace(act, actions, g, p, null) },
            "Download as .rbxl…" to { downloadPlace(act, actions, p, null) },
            "Version history…" to { versionHistory(act, studio, actions, g, p) },
            "Copy place ID" to {
                (act.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Place ID", p.id.toString()))
                toast(act, "Copied ${p.id}")
            },
        )
        Dialogs.themed(act).setTitle(p.name).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Close", null).show()
    }

    /** Opens a Roblox place for editing; Save then saves it back to Roblox. */
    fun openPlace(act: Activity, actions: MainActions, g: RobloxCloud.Game?, p: RobloxCloud.Place, version: Int?) {
        background(act, "Downloading ${p.name}${version?.let { " (version $it)" } ?: ""}…", {
            val bytes = RobloxCloud.downloadPlace(act, p.id, version)
            if (!RbxFile.isPlace(RbxFile.read(bytes))) throw Exception("That asset isn't a place file.")
            val universe = g?.universeId ?: p.universeId.takeIf { it != 0L } ?: RobloxCloud.universeOf(p.id)
            bytes to universe
        }) { (bytes, universe) ->
            val name = safeFile(p.name) + (version?.let { "_v$it" } ?: "") + ".rbxl"
            // Older versions open unlinked so they can't be saved over the live game by accident.
            val cloud = if (version == null && universe != 0L) CloudPlace(universe, p.id, g?.name?.takeIf { p.id == g.rootPlaceId } ?: p.name) else null
            actions.openPlaceBytes(name, bytes, cloud)
            if (cloud != null) Recents.add(act, Recents.Entry(cloud.name, p.id, universe, userId = RobloxApi.userId(act)))
        }
    }

    private fun downloadPlace(act: Activity, actions: MainActions, p: RobloxCloud.Place, version: Int?) {
        background(act, "Downloading ${p.name}…", { RobloxCloud.downloadPlace(act, p.id, version) }) { bytes ->
            actions.saveBytesAs(safeFile(p.name) + (version?.let { "_v$it" } ?: "") + ".rbxl") { bytes }
        }
    }

    private fun versionHistory(act: Activity, studio: Studio, actions: MainActions, g: RobloxCloud.Game, p: RobloxCloud.Place) {
        background(act, "Loading version history…", { RobloxCloud.versions(act, p.id) }) { versions ->
            val rows = versions.map { v ->
                Row("Version ${v.number}${if (v.published) "  (published)" else ""}", prettyDate(v.created), null, Icons.general(act, "History")) {
                    val items = listOf("Open this version" to { openPlace(act, actions, g, p, v.number) }, "Download this version (.rbxl)…" to { downloadPlace(act, actions, p, v.number) })
                    Dialogs.themed(act).setTitle("${p.name} — version ${v.number}").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
                        .setNegativeButton("Close", null).show()
                }
            }
            listDialog(act, "${p.name} — versions", rows, "No versions found (you may not have edit access to this place).") { b ->
                b.setNeutralButton("Version number…") { _, _ ->
                    Dialogs.prompt(act, "Download which version number?", "") { s ->
                        val n = s.trim().toIntOrNull() ?: return@prompt
                        downloadPlace(act, actions, p, n)
                    }
                }
            }
        }
    }

    fun openById(act: Activity, studio: Studio, actions: MainActions) {
        Dialogs.prompt(act, "Place ID or link", "") { s ->
            val id = RobloxApi.parseAssetId(s) ?: run { toast(act, "That doesn't look like a place ID"); return@prompt }
            val p = RobloxCloud.Place(id, "Place $id", 0)
            val items = listOf(
                "Open (save goes back to Roblox)" to { openPlace(act, actions, null, p, null) },
                "Download as .rbxl…" to { downloadPlace(act, actions, p, null) },
            )
            Dialogs.themed(act).setTitle("Place $id").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
        }
    }

    // ------------------------------------------------------------------ publish / save

    fun publish(act: Activity, studio: Studio, actions: MainActions) {
        val c = studio.cloud ?: run { publishAs(act, studio, actions); return }
        upload(act, studio, c, true)
    }

    fun saveToRobloxNow(act: Activity, studio: Studio, actions: MainActions) {
        val c = studio.cloud ?: run { publishAs(act, studio, actions, false); return }
        upload(act, studio, c, false)
    }

    fun publishAs(act: Activity, studio: Studio, actions: MainActions, publish: Boolean = true) {
        requireSignIn(act) {
            val verb = if (publish) "Publish" else "Save"
            val items = listOf("Create new game…" to { createNewGame(act, studio, actions, publish) }, "Update an existing game…" to {
                myGames(act, studio, actions) { g, p ->
                    Dialogs.confirm(act, "Overwrite ${p.name}?", "This replaces “${p.name}” in “${g.name}” with the place you're editing" + (if (publish) " and publishes it." else " as a new saved version."), verb) {
                        upload(act, studio, CloudPlace(g.universeId, p.id, p.name), publish)
                    }
                }
            })
            Dialogs.themed(act).setTitle("$verb to Roblox As").setWidthDp(360).setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** Studio's "Create new game": name, description and owner (you or a group), then publish into it. */
    fun createNewGame(act: Activity, studio: Studio, actions: MainActions, publish: Boolean = true) {
        requireSignIn(act) {
            background(act, "Loading your groups…", {
                val u = RobloxCloud.cachedUser(act) ?: RobloxCloud.currentUser(act)
                try { RobloxCloud.groups(act, u) } catch (e: AuthRequiredException) { throw e } catch (_: Exception) { emptyList() }
            }) { groups ->
                val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 18f), Ui.dp(act, 8f), Ui.dp(act, 18f), 0) }
                fun label(t: String) = box.addView(Ui.text(act, t, 12f, StudioStyle.dim, true).apply { setPadding(0, Ui.dp(act, 8f), 0, Ui.dp(act, 3f)) })
                label("Name")
                val name = Ui.input(act, studio.cloud?.name ?: studio.fileName.substringBeforeLast('.').ifBlank { "My Game" }, "Game name")
                box.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP))
                label("Description")
                val desc = Ui.input(act, "", "Optional")
                box.addView(desc, Ui.lp(Ui.MATCH, Ui.WRAP))
                label("Creator")
                val owners = listOf("Me (@${RobloxApi.userName(act)})" to 0L) + groups.map { it.name to it.id }
                val ownerPicker = StudioDropdown(act, owners.map { it.first }, 0) { }
                box.addView(ownerPicker, Ui.lp(Ui.MATCH, Ui.WRAP))
                box.addView(Ui.text(act, "The new game is private until you make it public on create.roblox.com. Publishing the place into it uses your Open Cloud API key " +
                    "(it needs access to the new game).", 11f, Ui.DIM).apply { isSingleLine = false; setPadding(0, Ui.dp(act, 10f), 0, 0) })
                Dialogs.themed(act).setTitle("Create new game").setView(android.widget.ScrollView(act).apply { addView(box) })
                    .setPositiveButton("Create") { _, _ ->
                        val n = name.text.toString().trim().ifEmpty { "My Game" }.take(50)
                        val owner = owners.getOrNull(ownerPicker.selectedIndex)?.second ?: 0L
                        background(act, "Creating “$n” on Roblox…", { RobloxCloud.createGame(act, n, owner, desc.text.toString().trim()) }) { (g, p) ->
                            OutputLog.info("Created game “${g.name}” (place ${p.id}) on Roblox")
                            StartCache.forget(act)
                            upload(act, studio, CloudPlace(g.universeId, p.id, g.name), publish)
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /**
     * File → Download / Version History: works on the Roblox place that's open (like Studio's
     * File menu); with no Roblox place open, pick a game.
     */
    fun currentPlaceHistory(act: Activity, studio: Studio, actions: MainActions) {
        val c = studio.cloud ?: run { myGames(act, studio, actions); return }
        requireSignIn(act) {
            val g = RobloxCloud.Game(c.universeId, c.name, c.placeId, "", "")
            val p = RobloxCloud.Place(c.placeId, c.name, c.universeId)
            val items = listOf(
                "Download this place (latest saved version)…" to { downloadPlace(act, actions, p, null) },
                "Version history…" to { versionHistory(act, studio, actions, g, p) },
                "Other games…" to { myGames(act, studio, actions) },
            )
            Dialogs.themed(act).setTitle("“${c.name}” (place ${c.placeId})").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
                .setNegativeButton("Close", null).show()
        }
    }

    /** Studio's Save for a place opened from Roblox: a new saved (unpublished) version on Roblox. */
    fun saveToRoblox(act: Activity, studio: Studio, onSaved: (() -> Unit)? = null) {
        val c = studio.cloud ?: return
        upload(act, studio, c, false, onSaved)
    }

    fun upload(act: Activity, studio: Studio, c: CloudPlace, publish: Boolean, onDone: (() -> Unit)? = null) {
        requireSignInOrKey(act) {
            background(act, if (publish) "Publishing to Roblox…" else "Saving to Roblox…", {
                val bytes = studio.serializePlace()
                RobloxCloud.publish(act, c.universeId, c.placeId, bytes, publish) to bytes.size
            }, { (result, size) ->
                studio.cloud = c
                studio.markSavedToCloud()
                OutputLog.info("${if (publish) "Published" else "Saved"} ${c.name} to Roblox ($result, ${size / 1024} KB)")
                toast(act, if (publish) "Published to Roblox" else "Saved to Roblox")
                Recents.add(act, Recents.Entry(c.name, c.placeId, c.universeId, userId = RobloxApi.userId(act)))
                onDone?.invoke()
            }) { e ->
                if (e is PublishException && e.needsApiKey) {
                    OutputLog.warn("Roblox: ${e.message}")
                    Dialogs.themed(act).setTitle(if (publish) "Publishing needs an API key" else "Saving to Roblox needs an API key")
                        .setMessage(e.message).setIcon(StudioIcon.WARNING)
                        .setPositiveButton("Set up API key") { _, _ -> advanced(act) { upload(act, studio, c, publish, onDone) } }
                        .setNeutralButton("Save a copy to this device") { _, _ -> (act as? MainActions)?.saveBytesAs(safeFile(c.name) + ".rbxl") { studio.serializePlace() } }
                        .setNegativeButton("Cancel", null).show()
                    true
                } else false
            }
        }
    }

    private fun requireSignInOrKey(act: Activity, then: () -> Unit) {
        fun needKey() { if (RobloxApi.apiKey(act).isNotEmpty()) then() else advanced(act) { if (RobloxApi.apiKey(act).isNotEmpty()) then() } }
        if (signedIn(act)) needKey() else signIn(act) { needKey() }
    }

    // ------------------------------------------------------------------ plugins from Roblox

    /** Installs a plugin from an asset ID or any Roblox link (e.g. an assetdelivery URL). */
    fun installPluginFromRoblox(act: Activity, studio: Studio, actions: MainActions, initial: String = "") {
        Dialogs.prompt(act, "Plugin asset ID or link", initial) { s ->
            val id = RobloxApi.parseAssetId(s) ?: run { toast(act, "Paste a plugin ID or a link containing one"); return@prompt }
            installPluginId(act, studio, actions, id, "Plugin_$id")
        }
    }

    fun installPluginId(act: Activity, studio: Studio, actions: MainActions, id: Long, name: String) {
        background(act, "Downloading plugin…", {
            val bytes = RobloxApi.downloadAsset(act, id)
            studio.plugins.install(name, bytes)
        }) { f ->
            studio.plugins.load(f)
            studio.notifyPluginButtons()
            actions.pluginsChanged()
            toast(act, "Installed ${f.nameWithoutExtension}")
        }
    }
}
