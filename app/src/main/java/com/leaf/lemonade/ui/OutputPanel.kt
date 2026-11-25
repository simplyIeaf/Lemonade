package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("ViewConstructor")
class OutputPanel(ctx: Context, private val studio: Studio) : LinearLayout(ctx) {
    private val entries = ArrayList<OutputLog.Entry>()
    private val list = RecyclerView(ctx)
    private val adapter = Adapter()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var pending = false
    private val history = ArrayList<String>()
    private var historyIdx = 0

    private val listener: (OutputLog.Entry?) -> Unit = { schedule() }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        addView(Ui.header(ctx, "Output", null), Ui.lp(Ui.MATCH, Ui.WRAP))
        list.layoutManager = LinearLayoutManager(ctx).apply { stackFromEnd = true }
        list.adapter = adapter
        list.itemAnimator = null
        addView(list, Ui.lp(Ui.MATCH, 0, 1f))

        val bar = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.HEADER) }
        val cmd = Ui.input(ctx, "", "Run a command (Lua)")
        cmd.typeface = Typeface.MONOSPACE
        cmd.textSize = 12f
        cmd.imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        fun run() {
            val code = cmd.text.toString()
            if (code.isBlank()) return
            history.add(code); historyIdx = history.size
            cmd.setText("")
            studio.runCommand(code)
        }
        cmd.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || (ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)) { run(); true } else false
        }
        bar.addView(cmd, Ui.lp(0, Ui.WRAP, 1f))
        bar.addView(Ui.iconButton(ctx, Icons.general(ctx, "Back", "Prev")) {
            if (history.isNotEmpty()) { historyIdx = (historyIdx - 1).coerceAtLeast(0); cmd.setText(history[historyIdx]); cmd.setSelection(cmd.text.length) }
        })
        bar.addView(Ui.iconButton(ctx, Icons.general(ctx, "RunScript", "Run")) { run() })
        bar.addView(Ui.iconButton(ctx, Icons.general(ctx, "Cleanup", "Clr")) { OutputLog.clear() })
        addView(bar, Ui.lp(Ui.MATCH, Ui.WRAP))
        reload()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); OutputLog.listeners.add(listener); reload() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); OutputLog.listeners.remove(listener) }

    private fun schedule() {
        if (pending) return
        pending = true
        postDelayed({ pending = false; reload() }, 100)
    }

    private fun reload() {
        entries.clear()
        entries.addAll(OutputLog.snapshot())
        adapter.notifyDataSetChanged()
        if (entries.isNotEmpty()) list.scrollToPosition(entries.size - 1)
    }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val t = Ui.text(parent.context, "", 12f).apply {
                isSingleLine = false
                typeface = Typeface.MONOSPACE
                setPadding(Ui.dp(parent.context, 8f), Ui.dp(parent.context, 1f), Ui.dp(parent.context, 8f), Ui.dp(parent.context, 1f))
                setTextIsSelectable(true)
                layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
            }
            return object : RecyclerView.ViewHolder(t) {}
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            val e = entries[position]
            val t = h.itemView as TextView
            t.text = "${fmt.format(Date(e.time))}  ${e.text}"
            t.setTextColor(when (e.level) {
                OutputLog.Level.PRINT -> Ui.TEXT
                OutputLog.Level.INFO -> Ui.INFO
                OutputLog.Level.WARN -> Ui.WARN
                OutputLog.Level.ERROR -> Ui.ERROR
            })
        }
    }
}
