package com.leaf.lemonade.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.leaf.lemonade.model.*

object Dialogs {
    private fun theme(ctx: Context) = StudioDialog.Builder(ctx)

    fun pickClass(ctx: Context, title: String, onPick: (String) -> Unit) {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 12f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f), 0) }
        val search = Ui.input(ctx, "", "Search objects")
        box.addView(search)
        val all = ClassDb.insertable
        val shown = ArrayList(all)
        val lv = ListView(ctx)
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_1, shown) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val cls = getItem(position)!!
                v.text = cls
                v.setTextColor(StudioStyle.text)
                v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f)
                val s = Ui.dp(ctx, 18f)
                v.setCompoundDrawables(Ui.iconFor(ctx, cls).apply { setBounds(0, 0, s, s) }, null, null, null)
                v.compoundDrawablePadding = Ui.dp(ctx, 10f)
                return v
            }
        }
        lv.adapter = adapter
        lv.divider = null
        lv.selector = android.graphics.drawable.ColorDrawable(0)
        val listH = minOf(Ui.dp(ctx, 380f), (ctx.resources.displayMetrics.heightPixels * 0.9f).toInt() - Ui.dp(ctx, 200f)).coerceAtLeast(Ui.dp(ctx, 120f))
        box.addView(lv, Ui.lp(Ui.MATCH, listH))
        val dlg = theme(ctx).setTitle(title).setView(box).setNegativeButton("Cancel", null).create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                shown.clear(); shown.addAll(all.filter { it.lowercase().contains(q) })
                adapter.notifyDataSetChanged()
            }
        })
        lv.setOnItemClickListener { _, _, pos, _ -> onPick(shown[pos]); dlg.dismiss() }
        dlg.show()
    }

    private fun numField(ctx: Context, label: String, v: Float): Pair<LinearLayout, EditText> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Ui.text(ctx, label, 13f, Ui.DIM), Ui.lp(Ui.dp(ctx, 28f), Ui.WRAP))
        val e = Ui.input(ctx, fmtNum(v), numeric = true)
        row.addView(e, Ui.lp(0, Ui.WRAP, 1f))
        return row to e
    }

    private fun f(e: EditText, d: Float = 0f) = e.text.toString().trim().toFloatOrNull() ?: d

    fun editValue(ctx: Context, name: String, value: Any?, type: Int, enumType: String?, onSet: (Any?) -> Unit) {
        if (enumType != null && (value is EnumValue || type == PropType.ENUM)) {
            val items = Enums.types[enumType]?.entries?.toList() ?: emptyList()
            if (items.isNotEmpty()) {
                val names = items.map { it.key }.toTypedArray()
                val cur = items.indexOfFirst { it.value == (value as? EnumValue)?.value }
                theme(ctx).setTitle("$name ($enumType)").setSingleChoiceItems(names, cur) { d, which ->
                    onSet(EnumValue(items[which].value)); d.dismiss()
                }.setNegativeButton("Cancel", null).show()
                return
            }
        }
        when (value) {
            is Color3 -> { colorPicker(ctx, name, value, onSet); return }
            is BrickColorV -> { colorPicker(ctx, name, value.color) { c -> onSet(BrickColorV(BrickColors.closest(c as Color3))) }; return }
        }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 8f), Ui.dp(ctx, 16f), 0) }
        val result: () -> Any?
        when (value) {
            is Vector3 -> {
                val fields = listOf("X" to value.x, "Y" to value.y, "Z" to value.z).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = { Vector3(f(fields[0]), f(fields[1]), f(fields[2])) }
            }
            is Vector2 -> {
                val fields = listOf("X" to value.x, "Y" to value.y).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = { Vector2(f(fields[0]), f(fields[1])) }
            }
            is UDim2 -> {
                val fields = listOf("XS" to value.xs, "XO" to value.xo.toFloat(), "YS" to value.ys, "YO" to value.yo.toFloat())
                    .map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = { UDim2(f(fields[0]), f(fields[1]).toInt(), f(fields[2]), f(fields[3]).toInt()) }
            }
            is UDim -> {
                val fields = listOf("S" to value.scale, "O" to value.offset.toFloat()).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = { UDim(f(fields[0]), f(fields[1]).toInt()) }
            }
            is CFrame -> {
                val o = value.toOrientation() * (180f / Math.PI.toFloat())
                box.addView(Ui.text(ctx, "Position", 12f, Ui.DIM))
                val p = listOf("X" to value.x, "Y" to value.y, "Z" to value.z).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                box.addView(Ui.text(ctx, "Orientation (degrees)", 12f, Ui.DIM))
                val r = listOf("X" to o.x, "Y" to o.y, "Z" to o.z).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = {
                    val k = Math.PI.toFloat() / 180f
                    CFrame.fromOrientation(f(r[0]) * k, f(r[1]) * k, f(r[2]) * k).withPosition(Vector3(f(p[0]), f(p[1]), f(p[2])))
                }
            }
            is NumberRange -> {
                val fields = listOf("Min" to value.min, "Max" to value.max).map { (l, v) -> numField(ctx, l, v).also { box.addView(it.first) }.second }
                result = { NumberRange(f(fields[0]), f(fields[1])) }
            }
            is EnumValue -> {
                val e = Ui.input(ctx, value.value.toString(), numeric = true); box.addView(e)
                result = { EnumValue(f(e).toInt()) }
            }
            is Float, is Double, is Int, is Long -> {
                val e = Ui.input(ctx, if (value is Float) fmtNum(value) else if (value is Double) fmtNum(value) else value.toString(), numeric = true)
                box.addView(e)
                result = {
                    val d = e.text.toString().trim().toDoubleOrNull() ?: 0.0
                    when (value) { is Float -> d.toFloat(); is Double -> d; is Int -> d.toInt(); else -> d.toLong() }
                }
            }
            is String, null -> {
                val multi = name == "Text" || name == "Value" || (value as? String)?.contains('\n') == true
                val e = Ui.input(ctx, value as? String ?: "", multiLine = multi)
                if (multi) e.minLines = 3
                box.addView(e)
                result = { e.text.toString() }
            }
            else -> {
                theme(ctx).setTitle(name).setMessage("Editing ${value.javaClass.simpleName} values isn't supported yet.\n\n$value").setPositiveButton("OK", null).show()
                return
            }
        }
        val dlg = theme(ctx).setTitle(name).setView(ScrollView(ctx).apply { addView(box) })
            .setPositiveButton("Set") { _, _ -> onSet(result()) }
            .setNegativeButton("Cancel", null).create()
        dlg.show()
    }

    /** The colour picker (macOS-style: wheel, sliders, BrickColor palette, pencils, saved colours). */
    fun colorPicker(ctx: Context, title: String, initial: Color3, onSet: (Any?) -> Unit) {
        ColorPicker.show(ctx, title, initial) { onSet(it) }
    }

    fun confirm(ctx: Context, title: String, msg: String, ok: String, onOk: () -> Unit) {
        theme(ctx).setTitle(title).setMessage(msg).setIcon(if (title.startsWith("Overwrite") || title.startsWith("Sign out") || title.startsWith("Remove")) StudioIcon.WARNING else StudioIcon.NONE)
            .setPositiveButton(ok) { _, _ -> onOk() }.setNegativeButton("Cancel", null).show()
    }

    fun prompt(ctx: Context, title: String, initial: String, onOk: (String) -> Unit) {
        val e = Ui.input(ctx, initial)
        val wrap = LinearLayout(ctx).apply { setPadding(Ui.dp(ctx, 16f), Ui.dp(ctx, 8f), Ui.dp(ctx, 16f), 0); addView(e, Ui.lp(Ui.MATCH, Ui.WRAP)) }
        theme(ctx).setTitle(title).setView(wrap).setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }.setNegativeButton("Cancel", null).show()
    }

    fun themed(ctx: Context) = theme(ctx)
}
