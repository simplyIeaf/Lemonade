package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.model.ClassDb
import com.leaf.lemonade.model.DataModel
import com.leaf.lemonade.model.Instance
import java.util.Collections
import java.util.IdentityHashMap

@SuppressLint("ViewConstructor")
class ExplorerPanel(ctx: Context, private val studio: Studio, private val actions: MainActions) : LinearLayout(ctx), Studio.Listener {
    private class Row(val inst: Instance, val depth: Int, val hasChildren: Boolean, val expanded: Boolean, val label: String)

    /** What's being dragged out of the Explorer (the Toolbox accepts it too). */
    class DragPayload(val items: List<Instance>)

    private var dragRow: Row? = null
    private var dragAnchor: View? = null
    private var dragStart: Pair<Float, Float>? = null
    private var dragMoved = false
    private var dropTarget: Instance? = null

    private val expanded = Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
    private var rows = listOf<Row>()
    private val list = RecyclerView(ctx)
    private val adapter = Adapter()
    private var filter = ""
    /** Shared with the viewport's multi-select button. */
    var multiSelect: Boolean
        get() = studio.multiSelect
        set(v) { studio.multiSelect = v }
    private val multiBtn: android.widget.ImageView
    private var lastClick: Pair<Instance, Long>? = null
    /** Anchor row for range selection (long-press "Select to here"). */
    private var rangeAnchor: Instance? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        addView(Ui.header(ctx, "Explorer", null), Ui.lp(Ui.MATCH, Ui.WRAP))
        multiBtn = Ui.iconButton(ctx, Icons.general(ctx, "SelectAll", "Multi"), 30f) {
            multiSelect = !multiSelect; updateMultiBtn()
            studio.toast(if (multiSelect) "Multi-select on: tap objects to add or remove them" else "Multi-select off")
        }
        val search = Ui.input(ctx, "", "Search")
        search.textSize = 13f
        search.setPadding(Ui.dp(ctx, 10f), Ui.dp(ctx, 4f), Ui.dp(ctx, 10f), Ui.dp(ctx, 4f))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { filter = s?.toString()?.trim() ?: ""; rebuild() }
        })
        val moreBtn = Ui.iconButton(ctx, MoreDots(), 30f) { v ->
            StudioMenu(ctx, v)
                .add("Insert Object…") { showInsertDialog(studio.selection.firstOrNull()) }
                .add("Collapse All") { collapseAll() }
                .show()
        }
        val searchRow = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        searchRow.addView(search, Ui.lp(0, Ui.dp(ctx, 30f), 1f).apply { rightMargin = Ui.dp(ctx, 2f) })
        searchRow.addView(multiBtn)
        searchRow.addView(moreBtn)
        addView(searchRow, Ui.lp(Ui.MATCH, Ui.WRAP).apply { setMargins(Ui.dp(ctx, 6f), Ui.dp(ctx, 5f), Ui.dp(ctx, 4f), Ui.dp(ctx, 5f)) })
        list.layoutManager = LinearLayoutManager(ctx)
        list.adapter = adapter
        list.itemAnimator = null
        addView(list, Ui.lp(Ui.MATCH, 0, 1f))
        list.setOnDragListener { _, e -> onDrag(e) }
        expandDefaults()
        rebuild()
    }

    // ------------------------------------------------------------------ drag & drop

    private fun rowAt(x: Float, y: Float): Row? {
        val v = list.findChildViewUnder(x, y) ?: return null
        val pos = list.getChildAdapterPosition(v)
        return rows.getOrNull(pos)
    }

    private fun startDrag(v: View, r: Row) {
        if (studio.selection.none { it === r.inst }) studio.select(listOf(r.inst))
        val items = synchronized(studio.lock) { studio.selection.filter { !it.isService } }
        dragRow = r; dragAnchor = v; dragStart = null; dragMoved = false
        if (items.isEmpty()) { showContextMenu(v, r); return }
        val ok = try {
            v.startDragAndDrop(android.content.ClipData.newPlainText("instances", items.joinToString { it.name }), View.DragShadowBuilder(v), DragPayload(items), 0)
        } catch (_: Exception) { false }
        if (!ok) showContextMenu(v, r)
    }

    private fun onDrag(e: android.view.DragEvent): Boolean {
        val payload = e.localState as? DragPayload
        when (e.action) {
            android.view.DragEvent.ACTION_DRAG_STARTED -> return payload != null
            android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                val s = dragStart
                if (s == null) dragStart = e.x to e.y
                else if (kotlin.math.hypot(e.x - s.first, e.y - s.second) > 22f * resources.displayMetrics.density) dragMoved = true
                val t = if (dragMoved) rowAt(e.x, e.y)?.inst?.takeIf { canDropInto(it, payload) } else null
                if (t !== dropTarget) { dropTarget = t; adapter.notifyDataSetChanged() }
            }
            android.view.DragEvent.ACTION_DRAG_EXITED -> { dragMoved = true; if (dropTarget != null) { dropTarget = null; adapter.notifyDataSetChanged() } }
            android.view.DragEvent.ACTION_DROP -> {
                if (!dragMoved || payload == null) return false
                val target = rowAt(e.x, e.y)?.inst ?: return false
                if (!canDropInto(target, payload)) return false
                synchronized(studio.lock) {
                    studio.undo.begin("Reparent")
                    for (i in payload.items) if (i !== target && !target.isDescendantOf(i) && i.parent !== target) studio.reparent(i, target)
                    studio.undo.commit()
                    expanded.add(target)
                }
                studio.select(payload.items)
                return true
            }
            android.view.DragEvent.ACTION_DRAG_ENDED -> {
                if (dropTarget != null) { dropTarget = null; adapter.notifyDataSetChanged() }
                val r = dragRow; val a = dragAnchor
                if (!dragMoved && !e.result && r != null && a != null && a.isAttachedToWindow) showContextMenu(a, r)
                dragRow = null; dragAnchor = null
            }
        }
        return true
    }

    private fun canDropInto(target: Instance, payload: DragPayload?): Boolean {
        val items = payload?.items ?: return false
        return items.none { it === target || target.isDescendantOf(it) }
    }

    private fun updateMultiBtn() {
        multiBtn.background = if (multiSelect) android.graphics.drawable.GradientDrawable().apply { setColor(Ui.SELECT); cornerRadius = Ui.dp(context, 4f).toFloat() }
        else Ui.rippleBg(context, android.graphics.Color.TRANSPARENT)
    }

    private fun expandDefaults() {
        expanded.clear()
        synchronized(studio.lock) { studio.model.findService("Workspace")?.let { expanded.add(it) } }
    }

    private fun collapseAll() { expanded.clear(); rebuild() }

    private fun visibleChildren(i: Instance): List<Instance> {
        if (i is DataModel) {
            val order = ClassDb.serviceNames
            return i.children.filter { it.archivable || it.className in order }
                .sortedBy { order.indexOf(it.className).let { x -> if (x < 0) 999 else x } }
        }
        // A union's source parts are internal (Studio doesn't list them either).
        if (i.isA("PartOperation")) return i.children.filter { !it.isA("BasePart") }
        return i.children
    }

    fun rebuild() {
        val out = ArrayList<Row>()
        synchronized(studio.lock) {
            val root = studio.model
            if (filter.isNotEmpty()) {
                val f = filter.lowercase()
                for (d in root.descendants()) {
                    if (out.size > 2000) break
                    if (d.name.lowercase().contains(f) || d.className.lowercase() == f)
                        out.add(Row(d, 0, false, false, d.name + "  ·  " + (d.parent?.fullName() ?: "")))
                }
            } else {
                fun walk(i: Instance, depth: Int) {
                    for (c in visibleChildren(i)) {
                        val open = c in expanded
                        out.add(Row(c, depth, visibleChildren(c).isNotEmpty(), open, c.name))
                        if (open) walk(c, depth + 1)
                    }
                }
                walk(root, 0)
            }
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun revealSelection() {
        val first = studio.selection.firstOrNull() ?: return
        synchronized(studio.lock) {
            var p = first.parent
            while (p != null && p !is DataModel) { expanded.add(p); p = p.parent }
        }
        rebuild()
        val idx = rows.indexOfFirst { it.inst === first }
        if (idx >= 0) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, Ui.dp(context, 60f))
    }

    override fun onTreeChanged() = rebuild()
    override fun onSelectionChanged() = revealSelection()
    override fun onModelReplaced() { expandDefaults(); rebuild() }
    override fun onToolChanged() = updateMultiBtn()

    private fun onRowClick(r: Row) {
        val now = System.currentTimeMillis()
        val lc = lastClick
        if (lc != null && lc.first === r.inst && now - lc.second < 350 && r.inst.isA("LuaSourceContainer")) {
            studio.openScript(r.inst); lastClick = null; return
        }
        lastClick = r.inst to now
        if (multiSelect || studio.modifierHeld) studio.toggleSelect(r.inst) else studio.select(listOf(r.inst))
        rangeAnchor = r.inst
    }

    /** Selects every row between the last clicked row and [r] (Shift-click in Studio). */
    private fun selectRange(r: Row) {
        val a = rangeAnchor ?: run { studio.select(listOf(r.inst)); return }
        val i0 = rows.indexOfFirst { it.inst === a }; val i1 = rows.indexOfFirst { it.inst === r.inst }
        if (i0 < 0 || i1 < 0) { studio.select(listOf(r.inst)); return }
        val range = rows.subList(minOf(i0, i1), maxOf(i0, i1) + 1).map { it.inst }
        studio.select(if (multiSelect) studio.selection.toList() + range.filter { n -> studio.selection.none { it === n } } else range)
    }

    private fun toggleExpand(r: Row) {
        if (!r.hasChildren) return
        if (r.inst in expanded) expanded.remove(r.inst) else expanded.add(r.inst)
        rebuild()
    }

    private fun showContextMenu(anchor: View, r: Row) {
        if (studio.selection.none { it === r.inst }) studio.select(listOf(r.inst))
        val m = StudioMenu(context, anchor)
        val i = r.inst
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add("Insert Object…" to { showInsertDialog(i) })
        val ra = rangeAnchor
        if (ra != null && ra !== i) items.add("Select from “${ra.name}” to here" to { selectRange(r) })
        if (i.children.isNotEmpty()) items.add("Select Children" to { studio.select(visibleChildren(i)) })
        if (studio.selection.size > 1) items.add("Select Group (parent Model)" to {
            val models = studio.selection.mapNotNull { s -> generateSequence(s.parent) { it.parent }.lastOrNull { it.className == "Model" && it.parent?.isService == true } }.distinct()
            if (models.isNotEmpty()) studio.select(models)
        })
        if (i.isA("LuaSourceContainer")) items.add("Open Script" to { studio.openScript(i) })
        if (!i.isService) {
            items.add("Rename" to { rename(i) })
            items.add("Duplicate" to { studio.duplicateSelection() })
            items.add("Copy" to { studio.copySelection() })
            items.add("Cut" to { studio.cutSelection() })
        }
        if (studio.hasClipboard) items.add("Paste Into" to { studio.paste(i) })
        if (!i.isService) {
            items.add("Delete" to { studio.deleteSelection() })
            items.add("Group (Model)" to { studio.groupSelection() })
            if (i.className == "Model" || i.className == "Folder") items.add("Ungroup" to { studio.ungroupSelection() })
            items.add("Zoom To" to { studio.focusSelection() })
            items.add("Save to File (.rbxm)…" to { actions.exportSelection() })
            items.add("Add to Toolbox" to { actions.addSelectionToToolbox() })
        }
        if (i.children.isNotEmpty()) items.add("Expand All" to { expandAll(i) })
        val groupEnds = setOf("Open Script", "Select Group (parent Model)", "Cut", "Paste Into", "Zoom To", "Add to Toolbox")
        for ((label, action) in items) { m.add(label, true, action); if (label in groupEnds) m.separator() }
        m.show()
    }

    private fun expandAll(i: Instance) {
        synchronized(studio.lock) {
            expanded.add(i)
            var n = 0
            for (d in i.descendants()) { if (d.children.isNotEmpty()) { expanded.add(d); if (++n > 500) break } }
        }
        rebuild()
    }

    private fun rename(i: Instance) {
        val e = Ui.input(context, i.name)
        Dialogs.themed(context).setTitle("Rename").setView(e)
            .setPositiveButton("OK") { _, _ -> studio.setProp(i, "Name", e.text.toString()) }
            .setNegativeButton("Cancel", null).show()
        e.requestFocus(); e.selectAll()
    }

    fun showInsertDialog(parent: Instance?) {
        Dialogs.pickClass(context, "Insert Object") { cls ->
            studio.insertObject(cls, parent?.takeIf { !it.isA("BasePart") || !ClassDb.isA(cls, "BasePart") })
        }
    }

    /** The "…" glyph of Studio's search row. */
    private class MoreDots : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds; p.color = Ui.TEXT
            val r = b.width() * 0.07f
            for (i in -1..1) c.drawCircle(b.exactCenterX() + i * b.width() * 0.22f, b.exactCenterY(), r, p)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    private inner class VH(val row: LinearLayout, val spacer: View, val arrow: android.widget.ImageView, val icon: android.widget.ImageView, val name: TextView) : RecyclerView.ViewHolder(row)

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(ctx, 26f)
                layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
            }
            val spacer = View(ctx)
            val arrow = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE }
            val icon = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_CENTER }
            val name = Ui.text(ctx, "", 13.5f).apply { setPadding(Ui.dp(ctx, 6f), 0, Ui.dp(ctx, 4f), 0) }
            row.addView(spacer, LayoutParams(0, 1))
            row.addView(arrow, LayoutParams(Ui.dp(ctx, 20f), Ui.dp(ctx, 26f)))
            row.addView(icon, LayoutParams(Ui.dp(ctx, 16f), Ui.dp(ctx, 16f)))
            row.addView(name, LayoutParams(0, Ui.WRAP, 1f))
            return VH(row, spacer, arrow, icon, name)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            h.spacer.layoutParams = LayoutParams(Ui.dp(h.row, 14f) * r.depth + Ui.dp(h.row, 2f), 1)
            h.arrow.setImageDrawable(if (!r.hasChildren) null else Ui.ArrowDrawable(r.expanded))
            h.icon.setImageDrawable(Ui.icon(h.row.context, r.inst))
            h.name.text = r.label.ifEmpty { " " }
            val selected = studio.selection.any { it === r.inst }
            val target = dropTarget === r.inst
            h.row.setBackgroundColor(when { target -> 0x663B8BEB; selected -> Ui.SELECT; else -> 0 })
            h.name.setTextColor(if (selected && !target) Ui.SELECT_TEXT else Ui.TEXT)
            h.row.setOnClickListener { onRowClick(r) }
            h.arrow.setOnClickListener { toggleExpand(r) }
            h.row.setOnLongClickListener { startDrag(h.row, r); true }
        }
    }
}
