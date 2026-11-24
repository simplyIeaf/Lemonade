package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
object AccountUi {
    private val net = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ helpers

    /** Runs [work] off the UI thread with a small progress dialog, then [done] on the UI thread. */
    fun <T> background(act: Activity, message: String, work: () -> T, done: (T) -> Unit) = background(act, message, work, done, null)

    fun <T> background(act: Activity, message: String, work: () -> T, done: (T) -> Unit, failed: ((Throwable) -> Boolean)?) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(act, 20f), Ui.dp(act, 18f), Ui.dp(act, 20f), Ui.dp(act, 18f)) }
        box.addView(ProgressBar(act).apply { isIndeterminate = true }, Ui.lp(Ui.dp(act, 32f), Ui.dp(act, 32f)))
        box.addView(Ui.text(act, message, 14f).apply { isSingleLine = false; setPadding(Ui.dp(act, 14f), 0, 0, 0) })
        var cancelled = false
        val dlg = Dialogs.themed(act).setView(box).setNegativeButton("Cancel") { _, _ -> cancelled = true }.create()
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
            Dialogs.themed(act).setTitle("Sign in needed").setMessage(e.message)
                .setPositiveButton("Sign in") { _, _ -> signIn(act) }.setNegativeButton("Cancel", null).show()
        } else {
            OutputLog.error("Roblox: ${e.message}")
            Dialogs.themed(act).setTitle("Something went wrong").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show()
        }
    }

    private fun toast(act: Activity, s: String) = Toast.makeText(act, s, Toast.LENGTH_SHORT).show()

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

    /** The account switcher (top-right account button): accounts, guest, sign out, avatar. */
    fun show(act: Activity, studio: Studio, actions: MainActions) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 16f), Ui.dp(act, 8f), Ui.dp(act, 16f), Ui.dp(act, 4f)) }
        var dlg: AlertDialog? = null
        val active = Accounts.active(act)
        fun accountRow(a: Accounts.Account?, current: Boolean, onClick: () -> Unit): View {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(act, 6f), Ui.dp(act, 6f), Ui.dp(act, 6f), Ui.dp(act, 6f))
                background = Ui.rippleBg(act, if (current) Ui.blend(Ui.PANEL, Ui.ACCENT, 0.18f) else Ui.PANEL, 6f)
                setOnClickListener { onClick() }
            }
            val img = ImageView(act)
            row.addView(img, Ui.lp(Ui.dp(act, 40f), Ui.dp(act, 40f)).apply { rightMargin = Ui.dp(act, 12f) })
            if (a != null) loadHeadshot(img, a.userId) else img.setImageDrawable(Icons.general(act, "Account"))
            val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(Ui.text(act, a?.displayName?.ifEmpty { a.name } ?: "Guest", 15f, Ui.TEXT, true))
            texts.addView(Ui.text(act, if (a != null) "@${a.name}" + (if (current) "  ·  signed in" else "") else if (current) "Not signed in" else "Use without an account", 12f, Ui.DIM))
            row.addView(texts, Ui.lp(0, Ui.WRAP, 1f))
            if (current) row.addView(Ui.text(act, "✓", 16f, Ui.ACCENT, true))
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
                        Dialogs.themed(act).setTitle("Sign in again").setMessage("The saved sign-in for @${a.name} has expired.")
                            .setPositiveButton("Sign in") { _, _ -> signIn(act) }.setNegativeButton("Cancel", null).show()
                        true
                    } else true
                }
            }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 4f) })
        }
        if (active != null) box.addView(accountRow(null, false) {
            dlg?.dismiss(); Accounts.useGuest(act); Avatar.refresh(act); toast(act, "Using Lemonade as a guest")
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 4f) })
        fun action(label: String, fn: () -> Unit) {
            box.addView(Ui.button(act, label) { dlg?.dismiss(); fn() }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START },
                Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 6f) })
        }
        box.addView(Ui.divider(act), Ui.lp(Ui.MATCH, Ui.dp(act, 1f)).apply { topMargin = Ui.dp(act, 10f); bottomMargin = Ui.dp(act, 4f) })
        action(if (active == null) "Sign in with Roblox…" else "Add another account…") { signIn(act) }
        action("Playtest avatar: ${Avatar.describe(act)}…") { avatarDialog(act, studio) }
        if (active != null) {
            action("My Games…") { myGames(act, studio, actions) }
            action("Sign out of @${active.name}") { signOut(act) }
        }
        action("Open Cloud API key (for publishing)…") { advanced(act) }
        box.addView(Ui.text(act, "Sign-ins are stored only in this app's private storage on this device.", 11f, Ui.DIM).apply { isSingleLine = false; setPadding(0, Ui.dp(act, 10f), 0, 0) })
        dlg = Dialogs.themed(act).setTitle("Roblox Account").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Close", null).show()
        if (active != null && active.name.isEmpty()) net.execute { try { RobloxCloud.currentUser(act) } catch (_: Exception) {} }
    }

    /** Open Cloud API key / manual cookie entry. */
    fun advanced(act: Activity, then: (() -> Unit)? = null) {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 18f), Ui.dp(act, 8f), Ui.dp(act, 18f), 0) }
        box.addView(Ui.text(act, "Roblox now only accepts place uploads (Publish / Save to Roblox) through Open Cloud. Create a key once:\n\n" +
            "1. Open create.roblox.com → Open Cloud → API Keys → Create API Key.\n" +
            "2. Under Access Permissions add \"universe-places\" (Write) and pick your experiences (or allow all). Add \"legacy-asset\" read access too if you want key-based downloads.\n" +
            "3. Add IP address 0.0.0.0/0 (or leave restrictions off), save, and copy the key here.", 12f).apply { isSingleLine = false })
        box.addView(Ui.button(act, "Open the API Keys page") {
            try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://create.roblox.com/dashboard/credentials"))) } catch (_: Exception) {}
        }, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 8f) })
        val key = Ui.input(act, RobloxApi.apiKey(act), "Open Cloud API key").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(key, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 8f) })
        box.addView(Ui.text(act, "Or paste a .ROBLOSECURITY cookie instead of signing in. Anyone with it can use your account, so only do this on your own device.", 12f, Ui.DIM).apply { isSingleLine = false; setPadding(0, Ui.dp(act, 12f), 0, 0) })
        val cookie = Ui.input(act, "", if (signedIn(act)) "(signed in — leave empty to keep)" else ".ROBLOSECURITY cookie").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(cookie, Ui.lp(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(act, 8f) })
        Dialogs.themed(act).setTitle("Open Cloud API key").setView(android.widget.ScrollView(act).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                RobloxApi.setApiKey(act, key.text.toString())
                val c = cookie.text.toString().trim()
                if (c.isNotEmpty()) {
                    RobloxApi.setCookie(act, c)
                    background(act, "Checking your account…", { RobloxCloud.currentUser(act) }) { u -> toast(act, "Signed in as @${u.name}"); Avatar.refresh(act); then?.invoke() }
                } else then?.invoke()
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Shows Roblox's own login page and keeps the session it hands back (as a new saved account). */
    @SuppressLint("SetJavaScriptEnabled")
    fun signIn(act: Activity, then: (() -> Unit)? = null) {
        val dlg = Dialog(act, if (Ui.LIGHT) android.R.style.Theme_Material_Light_NoActionBar_Fullscreen else android.R.style.Theme_Material_NoActionBar_Fullscreen)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.PANEL) }
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.TOPBAR); setPadding(Ui.dp(act, 12f), Ui.dp(act, 6f), Ui.dp(act, 6f), Ui.dp(act, 6f)) }
        bar.addView(Ui.text(act, "Sign in to Roblox", 15f, Ui.TEXT, true), Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(Ui.button(act, "Cancel") { dlg.dismiss() })
        root.addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        val progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(progress, Ui.lp(Ui.MATCH, Ui.dp(act, 4f)))
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
                then?.invoke()
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
    private fun listDialog(act: Activity, title: String, rows: List<Row>, empty: String, extra: ((AlertDialog.Builder) -> Unit)? = null): AlertDialog {
        val lv = ListView(act)
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
                        addView(Ui.text(act, "", 14f, Ui.TEXT, true))
                        addView(Ui.text(act, "", 12f, Ui.DIM))
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
        lateinit var dlg: AlertDialog
        lv.setOnItemClickListener { _, _, pos, _ -> dlg.dismiss(); rows[pos].onClick() }
        val content: View = if (rows.isEmpty()) Ui.text(act, empty, 14f, Ui.DIM).apply { isSingleLine = false; setPadding(Ui.dp(act, 20f), Ui.dp(act, 16f), Ui.dp(act, 20f), Ui.dp(act, 16f)) } else lv
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

    /** File → Publish to Roblox. */
    fun publish(act: Activity, studio: Studio, actions: MainActions) {
        val c = studio.cloud ?: run { publishAs(act, studio, actions); return }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Publish (players get this version)" to { upload(act, studio, c, true) })
        items.add("Save to Roblox (new version, not published)" to { upload(act, studio, c, false) })
        items.add("Publish to a different place…" to { publishAs(act, studio, actions) })
        Dialogs.themed(act).setTitle("“${c.name}” (place ${c.placeId})")
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton("Cancel", null).show()
    }

    fun publishAs(act: Activity, studio: Studio, actions: MainActions) {
        requireSignIn(act) {
            // Like Studio's Publish As: a new game, or overwrite a place in one of yours.
            val items = listOf("Create new game…" to { createNewGame(act, studio, actions) }, "Update an existing game…" to {
                myGames(act, studio, actions) { g, p ->
                    Dialogs.confirm(act, "Overwrite ${p.name}?", "This replaces “${p.name}” in “${g.name}” with the place you're editing and publishes it.", "Publish") {
                        upload(act, studio, CloudPlace(g.universeId, p.id, p.name), true)
                    }
                }
            })
            Dialogs.themed(act).setTitle("Publish to Roblox As").setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    /** Studio's "Create new game": name, description and owner (you or a group), then publish into it. */
    fun createNewGame(act: Activity, studio: Studio, actions: MainActions) {
        requireSignIn(act) {
            background(act, "Loading your groups…", {
                val u = RobloxCloud.cachedUser(act) ?: RobloxCloud.currentUser(act)
                try { RobloxCloud.groups(act, u) } catch (e: AuthRequiredException) { throw e } catch (_: Exception) { emptyList() }
            }) { groups ->
                val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(act, 18f), Ui.dp(act, 8f), Ui.dp(act, 18f), 0) }
                fun label(t: String) = box.addView(Ui.text(act, t, 12f, Ui.DIM, true).apply { setPadding(0, Ui.dp(act, 8f), 0, Ui.dp(act, 3f)) })
                label("Name")
                val name = Ui.input(act, studio.cloud?.name ?: studio.fileName.substringBeforeLast('.').ifBlank { "My Game" }, "Game name")
                box.addView(name, Ui.lp(Ui.MATCH, Ui.WRAP))
                label("Description")
                val desc = Ui.input(act, "", "Optional")
                box.addView(desc, Ui.lp(Ui.MATCH, Ui.WRAP))
                label("Creator")
                val owners = listOf("Me (@${RobloxApi.userName(act)})" to 0L) + groups.map { it.name to it.id }
                val spinner = android.widget.Spinner(act)
                spinner.adapter = android.widget.ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, owners.map { it.first })
                box.addView(spinner, Ui.lp(Ui.MATCH, Ui.WRAP))
                box.addView(Ui.text(act, "The new game is private until you make it public on create.roblox.com. Publishing the place into it uses your Open Cloud API key " +
                    "if you've added one (it needs access to the new game).", 11f, Ui.DIM).apply { isSingleLine = false; setPadding(0, Ui.dp(act, 10f), 0, 0) })
                Dialogs.themed(act).setTitle("Create new game").setView(android.widget.ScrollView(act).apply { addView(box) })
                    .setPositiveButton("Create") { _, _ ->
                        val n = name.text.toString().trim().ifEmpty { "My Game" }.take(50)
                        val owner = owners.getOrNull(spinner.selectedItemPosition)?.second ?: 0L
                        background(act, "Creating “$n” on Roblox…", { RobloxCloud.createGame(act, n, owner, desc.text.toString().trim()) }) { (g, p) ->
                            OutputLog.info("Created game “${g.name}” (place ${p.id}) on Roblox")
                            StartCache.forget(act)
                            upload(act, studio, CloudPlace(g.universeId, p.id, g.name), true)
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
                        .setMessage(e.message)
                        .setPositiveButton("Set up API key") { _, _ -> advanced(act) { upload(act, studio, c, publish, onDone) } }
                        .setNeutralButton("Save a copy to this device") { _, _ -> (act as? MainActions)?.saveBytesAs(safeFile(c.name) + ".rbxl") { studio.serializePlace() } }
                        .setNegativeButton("Cancel", null).show()
                    true
                } else false
            }
        }
    }

    private fun requireSignInOrKey(act: Activity, then: () -> Unit) {
        if (signedIn(act) || RobloxApi.apiKey(act).isNotEmpty()) then() else signIn(act, then)
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
