package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.lua.LuauTranspiler
import com.leaf.lemonade.model.Instance
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.lib.jse.JsePlatform

@SuppressLint("ViewConstructor")
class ScriptEditor(ctx: Context, private val studio: Studio, val script: Instance) : LinearLayout(ctx) {
    private val editor: CodeEdit
    private val lines: TextView
    private val status: TextView
    private val scroll: ScrollView
    private val hsv: HorizontalScrollView
    private var lineCount = 0
    private var highlighting = false
    private var dirtyText = false
    private val highlightRunnable = Runnable { highlight() }
    private val commitRunnable = Runnable { commit() }
    private val completeRunnable = Runnable { updateCompletion(false) }

    // autocomplete
    private var popup: PopupWindow? = null
    private val popupList: ListView
    private var completion: Autocomplete.Result? = null
    private var selectedIdx = 0
    private var suppressCompletion = false
    /** Closing characters we auto-inserted (position -> char), so typing them again just steps over. */
    private var autoClosedAt = -1

    companion object {
        private val keywords = setOf("and", "break", "do", "else", "elseif", "end", "false", "for", "function", "if", "in", "local", "nil",
            "not", "or", "repeat", "return", "then", "true", "until", "while", "continue", "export", "type", "self")
        private val builtins = setOf("game", "workspace", "script", "plugin", "Instance", "Vector3", "Vector2", "CFrame", "Color3", "BrickColor",
            "UDim", "UDim2", "Enum", "TweenInfo", "Ray", "task", "wait", "print", "warn", "error", "pairs", "ipairs", "math", "string", "table",
            "require", "typeof", "type", "tostring", "tonumber", "pcall", "xpcall", "spawn", "delay", "tick", "time", "os", "coroutine",
            "setmetatable", "getmetatable", "rawget", "rawset", "select", "next", "unpack", "assert", "shared", "_G", "NumberRange",
            "NumberSequence", "ColorSequence", "Random", "RaycastParams", "OverlapParams", "utf8", "bit32", "debug", "buffer", "DateTime")
        private val syntaxCheck: Globals by lazy { JsePlatform.standardGlobals() }
        private val pairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'')
    }

    /** EditText that reports cursor moves (to hide autocomplete) and hardware keys (to accept it). */
    private inner class CodeEdit(c: Context) : EditText(c) {
        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            val r = completion
            if (r != null && (selStart != selEnd || selStart < r.start || selStart > r.end + 1)) hidePopup()
        }
        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            if (popup?.isShowing == true) {
                when (keyCode) {
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ENTER -> { acceptCompletion(selectedIdx); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveSelection(1); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { moveSelection(-1); return true }
                    KeyEvent.KEYCODE_ESCAPE -> { hidePopup(); return true }
                }
            } else if (keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed) { updateCompletion(true); return true }
            else if (keyCode == KeyEvent.KEYCODE_TAB) { insert("    "); return true }
            return super.onKeyDown(keyCode, event)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.EDITOR_BG)
        val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.HEADER) }
        status = Ui.text(ctx, "", 11f, Ui.DIM).apply { setPadding(Ui.dp(ctx, 8f), 0, Ui.dp(ctx, 8f), 0) }
        bar.addView(android.widget.ImageView(ctx).apply { setImageDrawable(Ui.icon(ctx, script)) }, Ui.lp(Ui.dp(ctx, 16f), Ui.dp(ctx, 16f)).apply { leftMargin = Ui.dp(ctx, 8f) })
        bar.addView(Ui.text(ctx, script.fullName(), 12f).apply { setPadding(Ui.dp(ctx, 6f), 0, 0, 0) }, Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(status)
        bar.addView(Ui.iconTextButton(ctx, Icons.general(ctx, "ScriptAnalysis", "Chk"), "Check", 0) { checkSyntax() })
        bar.addView(Ui.iconButton(ctx, Icons.general(ctx, "FindAll", "Find")) { find() })
        addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))

        val fontSize = Settings.editorFontSize
        lines = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSize
            setTextColor(Ui.blend(Ui.EDITOR_GUTTER, Ui.EDITOR_TEXT, 0.45f))
            gravity = Gravity.END
            setPadding(Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 6f), Ui.dp(ctx, 8f))
            setBackgroundColor(Ui.EDITOR_GUTTER)
        }
        editor = CodeEdit(ctx).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSize
            setTextColor(Ui.EDITOR_TEXT)
            background = null
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setHorizontallyScrolling(false)
            setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 8f), Ui.dp(ctx, 24f), Ui.dp(ctx, 200f))
            minWidth = resources.displayMetrics.widthPixels
            setText(synchronized(studio.lock) { script["Source"] as? String ?: "" })
        }
        hsv = HorizontalScrollView(ctx).apply { isFillViewport = true; addView(editor) }
        val inner = LinearLayout(ctx).apply { orientation = HORIZONTAL; addView(lines, Ui.lp(Ui.WRAP, Ui.MATCH)); addView(hsv, Ui.lp(0, Ui.WRAP, 1f)) }
        scroll = ScrollView(ctx).apply { isFillViewport = true; addView(inner) }
        addView(scroll, Ui.lp(Ui.MATCH, 0, 1f))
        scroll.viewTreeObserver.addOnScrollChangedListener { if (popup?.isShowing == true) positionPopup() }

        val symbols = LinearLayout(ctx).apply { orientation = HORIZONTAL; setBackgroundColor(Ui.HEADER) }
        for (s in listOf("Tab", "(", ")", "{", "}", "[", "]", "=", "\"", ":", ".", ",", "~", "#", "end", "local", "function", "then")) {
            symbols.addView(Ui.smallIconButton(ctx, s) { insert(if (s == "Tab") "    " else s) })
        }
        addView(HorizontalScrollView(ctx).apply { addView(symbols) }, Ui.lp(Ui.MATCH, Ui.WRAP))

        // Autocomplete list
        popupList = ListView(ctx).apply {
            divider = null
            background = GradientDrawable().apply { setColor(Ui.blend(Ui.EDITOR_BG, if (Ui.LIGHT) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.07f)); setStroke(Ui.dp(ctx, 1f), Ui.BORDER); cornerRadius = Ui.dp(ctx, 4f).toFloat() }
            adapter = CompletionAdapter()
            setOnItemClickListener { _, _, pos, _ -> acceptCompletion(pos) }
        }

        editor.addTextChangedListener(object : TextWatcher {
            private var autoIndent = false
            private var typed: Char? = null
            private var typedAt = -1
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                autoIndent = count == 1 && before == 0 && s != null && start < s.length && s[start] == '\n'
                typed = if (count == 1 && before == 0 && s != null && start < s.length) s[start] else null
                typedAt = start
            }
            override fun afterTextChanged(s: Editable?) {
                if (highlighting || s == null) return
                if (autoIndent && Settings.autoIndent) {
                    autoIndent = false
                    handleNewline(s)
                } else if (typed != null && Settings.autoCloseBrackets) {
                    handleTypedChar(s, typed!!, typedAt)
                }
                typed = null
                dirtyText = true
                updateLines()
                removeCallbacks(highlightRunnable); postDelayed(highlightRunnable, 250)
                removeCallbacks(commitRunnable); postDelayed(commitRunnable, 800)
                if (Settings.autocomplete && !suppressCompletion) { removeCallbacks(completeRunnable); postDelayed(completeRunnable, 70) }
            }
        })
        updateLines()
        highlight()
    }

    // ------------------------------------------------------------------ typing helpers

    /** Keeps indentation, indents after block openers, and adds a matching `end` like Studio. */
    private fun handleNewline(s: Editable) {
        val pos = editor.selectionStart
        if (pos <= 0) return
        val prevStart = s.lastIndexOf('\n', pos - 2).let { if (it < 0) 0 else it + 1 }
        val prevLine = s.substring(prevStart, maxOf(prevStart, pos - 1))
        val baseIndent = prevLine.takeWhile { it == ' ' || it == '\t' }
        val t = prevLine.trim()
        val opensFunction = Regex("""(^|[^\w])function\s*[\w.:]*\s*\([^)]*\)\s*$""").containsMatchIn(t)
        val opensBlock = t.endsWith(" then") || t == "then" || t.endsWith(" do") || t == "do" || opensFunction || t == "repeat" || t.endsWith("{") || t == "else" || t.startsWith("elseif") && t.endsWith("then")
        var indent = baseIndent
        if (opensBlock) indent += "    "
        suppressCompletion = true
        try {
            if (indent.isNotEmpty()) s.insert(pos, indent)
            val cur = pos + indent.length
            // Auto "end": only when the block isn't already closed on the following line.
            val needsEnd = (t.endsWith(" then") && !t.startsWith("elseif")) || t.endsWith(" do") || t == "do" || opensFunction
            if (needsEnd && t != "else") {
                val nextStart = cur
                val restLineEnd = s.indexOf('\n', nextStart).let { if (it < 0) s.length else it }
                val afterCursor = s.substring(nextStart, restLineEnd)
                val nextLineStart = if (restLineEnd < s.length) restLineEnd + 1 else -1
                val nextLine = if (nextLineStart >= 0) s.substring(nextLineStart, s.indexOf('\n', nextLineStart).let { if (it < 0) s.length else it }) else ""
                val nextIndent = nextLine.takeWhile { it == ' ' || it == '\t' }.length
                val nt = nextLine.trim()
                val closed = nt.startsWith("end") || nt.startsWith("else") || nt.startsWith("until") || nt.startsWith(")") ||
                    (nt.isNotEmpty() && nextIndent > baseIndent.length) || afterCursor.trim().isNotEmpty() || unbalancedEnds(s.toString()) <= 0
                if (!closed) {
                    val close = if (opensFunction && Regex("""\(\s*function""").containsMatchIn(t) || t.endsWith("function()") && prevLine.contains("(")) "end)" else "end"
                    s.insert(cur, "\n" + baseIndent + close)
                    editor.setSelection(cur)
                }
            }
        } finally { suppressCompletion = false }
    }

    /** Positive when more blocks are opened than closed in the text (so a new block needs an `end`). */
    private fun unbalancedEnds(text: String): Int {
        var opens = 0; var ends = 0
        val noStrings = text.replace(Regex("""--\[(=*)\[[\s\S]*?]\1]|--[^\n]*|"(\\.|[^"\\\n])*"|'(\\.|[^'\\\n])*'|\[(=*)\[[\s\S]*?]\5]"""), " ")
        for (m in Regex("""\b(function|do|then|end|repeat|until|elseif)\b""").findAll(noStrings)) {
            when (m.value) { "function", "do", "repeat" -> opens++; "then" -> opens++; "elseif" -> opens--; "end", "until" -> ends++ }
        }
        // "while x do" / "for ... do" count once (do), "if ... then" once (then).
        return opens - ends
    }

    /** Auto-closes brackets/quotes, and steps over a closer we inserted ourselves. */
    private fun handleTypedChar(s: Editable, c: Char, at: Int) {
        suppressCompletion = true
        try {
            if (autoClosedAt == at && at + 1 < s.length && s[at + 1] == c && c in setOf(')', ']', '}', '"', '\'')) {
                s.delete(at + 1, at + 2)
                autoClosedAt = -1
                return
            }
            // Typing inside the pair pushes our closer along.
            if (autoClosedAt >= 0 && at <= autoClosedAt) autoClosedAt++
            if (c == '\n') autoClosedAt = -1
            val close = pairs[c] ?: return
            val next = if (at + 1 < s.length) s[at + 1] else ' '
            if (next.isLetterOrDigit() || next == '_') return
            if ((c == '"' || c == '\'') && at > 0 && (s[at - 1].isLetterOrDigit() || s[at - 1] == c)) return
            s.insert(at + 1, close.toString())
            editor.setSelection(at + 1)
            autoClosedAt = at + 1
        } finally { suppressCompletion = false }
    }

    private fun insert(s: String) {
        val st = editor.selectionStart.coerceAtLeast(0)
        val en = editor.selectionEnd.coerceAtLeast(0)
        editor.text.replace(minOf(st, en), maxOf(st, en), s)
    }

    // ------------------------------------------------------------------ autocomplete

    private fun updateCompletion(explicit: Boolean) {
        if (!Settings.autocomplete && !explicit) { hidePopup(); return }
        val cur = editor.selectionStart
        if (cur != editor.selectionEnd || cur < 0) { hidePopup(); return }
        val r = try { Autocomplete.complete(studio, script, editor.text.toString(), cur, explicit) } catch (_: Exception) { null }
        if (r == null || r.items.isEmpty()) { hidePopup(); return }
        completion = r
        selectedIdx = 0
        (popupList.adapter as BaseAdapter).notifyDataSetChanged()
        popupList.setSelection(0)
        showPopup()
    }

    private fun popupSize(): Pair<Int, Int> {
        val n = completion?.items?.size ?: 0
        val rowH = Ui.dp(context, 30f)
        return Ui.dp(context, 280f) to (minOf(n, 7) * rowH + Ui.dp(context, 4f))
    }

    private fun showPopup() {
        val (w, h) = popupSize()
        val p = popup ?: PopupWindow(popupList, w, h, false).apply {
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            elevation = 8f
            setOnDismissListener { completion = null }
            popup = this
        }
        val (x, y) = cursorScreenPos(h)
        if (p.isShowing) p.update(x, y, w, h) else p.showAtLocation(editor, Gravity.NO_GRAVITY, x, y)
    }

    private fun positionPopup() {
        val p = popup ?: return
        val (w, h) = popupSize()
        val (x, y) = cursorScreenPos(h)
        p.update(x, y, w, h)
    }

    /** Where the popup goes: just under the cursor's line, or above it if the keyboard is in the way. */
    private fun cursorScreenPos(popupH: Int): Pair<Int, Int> {
        val layout = editor.layout ?: return 0 to 0
        val cur = editor.selectionStart.coerceIn(0, editor.text.length)
        val line = layout.getLineForOffset(cur)
        val loc = IntArray(2); editor.getLocationOnScreen(loc)
        val x = loc[0] + editor.totalPaddingLeft + layout.getPrimaryHorizontal(cur).toInt()
        val below = loc[1] + editor.totalPaddingTop + layout.getLineBottom(line)
        val above = loc[1] + editor.totalPaddingTop + layout.getLineTop(line) - popupH
        val frame = Rect(); editor.getWindowVisibleDisplayFrame(frame)
        val (w, _) = popupSize()
        val cx = x.coerceIn(frame.left, maxOf(frame.left, frame.right - w))
        val cy = if (below + popupH > frame.bottom && above > frame.top) above else below
        return cx to cy
    }

    /** Closes the list. A refresh already scheduled by typing still runs (fast typing moves the cursor first). */
    private fun hidePopup() {
        completion = null
        popup?.let { if (it.isShowing) it.dismiss() }
    }

    private fun moveSelection(delta: Int) {
        val n = completion?.items?.size ?: return
        selectedIdx = (selectedIdx + delta).coerceIn(0, n - 1)
        (popupList.adapter as BaseAdapter).notifyDataSetChanged()
        popupList.smoothScrollToPosition(selectedIdx)
    }

    private fun acceptCompletion(index: Int) {
        val r = completion ?: return
        val item = r.items.getOrNull(index) ?: return
        val text = editor.text
        val end = editor.selectionStart.coerceIn(r.start, text.length)
        // Replace the whole identifier under the cursor, not just the typed part.
        var wordEnd = end
        while (wordEnd < text.length && (text[wordEnd].isLetterOrDigit() || text[wordEnd] == '_')) wordEnd++
        removeCallbacks(completeRunnable)
        hidePopup()
        suppressCompletion = true
        try {
            text.replace(r.start, wordEnd, item.insert)
            editor.setSelection((r.start + item.insert.length).coerceAtMost(editor.text.length))
        } finally { suppressCompletion = false }
    }

    private inner class CompletionAdapter : BaseAdapter() {
        override fun getCount() = completion?.items?.size ?: 0
        override fun getItem(p: Int) = completion?.items?.get(p)
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val ctx = parent.context
            val row = (convert as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 8f), 0)
                minimumHeight = Ui.dp(ctx, 30f)
                addView(TextView(ctx).apply { textSize = 9f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF1E1E1E.toInt()) }, LayoutParams(Ui.dp(ctx, 22f), Ui.dp(ctx, 16f)).apply { rightMargin = Ui.dp(ctx, 8f) })
                addView(TextView(ctx).apply { textSize = 13f; typeface = Typeface.MONOSPACE; isSingleLine = true }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(ctx).apply { textSize = 10f; isSingleLine = true; setTextColor(Ui.DIM) })
            }
            val item = completion?.items?.getOrNull(p) ?: return row
            (row.getChildAt(0) as TextView).apply {
                text = item.kind.badge
                background = GradientDrawable().apply { setColor(item.kind.color); cornerRadius = Ui.dp(ctx, 3f).toFloat() }
            }
            (row.getChildAt(1) as TextView).apply {
                val pre = completion?.prefix ?: ""
                val sp = android.text.SpannableString(item.label)
                val i = item.label.lowercase().indexOf(pre.lowercase())
                if (pre.isNotEmpty() && i >= 0) sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), i, i + pre.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                text = sp
                setTextColor(Ui.EDITOR_TEXT)
            }
            (row.getChildAt(2) as TextView).text = item.detail
            row.setBackgroundColor(if (p == selectedIdx) Ui.blend(Ui.ACCENT, Ui.EDITOR_BG, 0.55f) else 0)
            return row
        }
    }

    // ------------------------------------------------------------------ editor basics

    private fun updateLines() {
        val n = editor.text.count { it == '\n' } + 1
        if (n == lineCount) return
        lineCount = n
        val sb = StringBuilder()
        for (i in 1..n) { sb.append(i); if (i < n) sb.append('\n') }
        lines.text = sb
        status.text = "$n lines"
    }

    fun commit() {
        if (!dirtyText) return
        dirtyText = false
        val text = editor.text.toString()
        synchronized(studio.lock) {
            if (script["Source"] != text) {
                script.set("Source", text)
                studio.markDirty()
            }
        }
    }

    private fun checkSyntax() {
        commit()
        try {
            syntaxCheck.load(LuauTranspiler.transpile(editor.text.toString()), "=" + script.name)
            status.text = "No syntax errors"
            status.setTextColor(Ui.PLAY)
        } catch (e: LuaError) {
            status.text = e.message ?: "Syntax error"
            status.setTextColor(Ui.ERROR)
            Regex(":(\\d+):").find(e.message ?: "")?.groupValues?.get(1)?.toIntOrNull()?.let { goToLine(it) }
        }
    }

    private fun goToLine(line: Int) {
        val t = editor.text
        var idx = 0
        repeat(line - 1) { val n = t.indexOf('\n', idx); if (n >= 0) idx = n + 1 }
        editor.requestFocus()
        editor.setSelection(idx.coerceIn(0, t.length))
    }

    private fun find() {
        Dialogs.prompt(context, "Find", "") { q ->
            if (q.isEmpty()) return@prompt
            val t = editor.text.toString()
            var i = t.indexOf(q, editor.selectionEnd.coerceAtLeast(0))
            if (i < 0) i = t.indexOf(q)
            if (i >= 0) { editor.requestFocus(); editor.setSelection(i, i + q.length) } else status.text = "\"$q\" not found"
        }
    }

    private fun highlight() {
        val s = editor.text
        val th = Ui.theme
        val kw = th.synKeyword; val str = th.synString; val num = th.synNumber; val comment = th.synComment; val builtin = th.synBuiltin
        highlighting = true
        try {
            for (sp in s.getSpans(0, s.length, ForegroundColorSpan::class.java)) s.removeSpan(sp)
            val text = s.toString()
            val n = text.length
            var i = 0
            fun span(a: Int, b: Int, c: Int) { if (b > a) s.setSpan(ForegroundColorSpan(c), a, minOf(b, n), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            fun longClose(from: Int, lvl: Int): Int { val close = "]" + "=".repeat(lvl) + "]"; val e = text.indexOf(close, from); return if (e < 0) n else e + close.length }
            fun longLevel(at: Int): Int {
                if (at >= n || text[at] != '[') return -1
                var j = at + 1; var l = 0
                while (j < n && text[j] == '=') { l++; j++ }
                return if (j < n && text[j] == '[') l else -1
            }
            while (i < n) {
                val c = text[i]
                when {
                    c == '-' && i + 1 < n && text[i + 1] == '-' -> {
                        val lvl = longLevel(i + 2)
                        val e = if (lvl >= 0) longClose(i + 4 + lvl, lvl) else text.indexOf('\n', i).let { if (it < 0) n else it }
                        span(i, e, comment); i = e
                    }
                    c == '"' || c == '\'' || c == '`' -> {
                        var j = i + 1
                        while (j < n && text[j] != c && text[j] != '\n') { if (text[j] == '\\') j++; j++ }
                        j = minOf(j + 1, n)
                        span(i, j, str); i = j
                    }
                    c == '[' && longLevel(i) >= 0 -> { val l = longLevel(i); val e = longClose(i + 2 + l, l); span(i, e, str); i = e }
                    c.isDigit() -> {
                        var j = i
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                        span(i, j, num); i = j
                    }
                    c.isLetter() || c == '_' -> {
                        var j = i
                        while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                        val w = text.substring(i, j)
                        val prevDot = i > 0 && (text[i - 1] == '.' || text[i - 1] == ':')
                        if (!prevDot && w in keywords) span(i, j, kw)
                        else if (!prevDot && w in builtins) span(i, j, builtin)
                        i = j
                    }
                    else -> i++
                }
            }
        } finally {
            highlighting = false
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(completeRunnable)
        hidePopup()
        commit()
        super.onDetachedFromWindow()
    }
}
