package com.leaf.lemonade.core

import com.leaf.lemonade.model.Instance

class UndoManager(private val studio: Studio) {
    sealed class Op {
        class Prop(val inst: Instance, val name: String, val old: Any?, val new: Any?, val hadOld: Boolean) : Op()
        class Parent(val inst: Instance, val oldParent: Instance?, val oldIndex: Int, val newParent: Instance?) : Op()
    }

    private class Step(val label: String, val ops: List<Op>)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()
    private var batch: ArrayList<Op>? = null
    private var batchLabel = ""
    var suspended = false

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun begin(label: String) {
        if (batch == null) { batch = ArrayList(); batchLabel = label }
    }

    fun commit() {
        val b = batch ?: return
        batch = null
        if (b.isNotEmpty()) push(Step(batchLabel, b))
    }

    fun record(op: Op) {
        if (suspended || studio.isPlaying) return
        val b = batch
        if (b != null) b.add(op) else push(Step("Edit", listOf(op)))
    }

    private fun push(s: Step) {
        undoStack.addLast(s)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
        studio.markDirty()
    }

    fun clear() { undoStack.clear(); redoStack.clear(); batch = null }

    fun undo() {
        val s = synchronized(studio.lock) { undoStack.removeLastOrNull() } ?: return
        apply(s, true)
        redoStack.addLast(s)
        OutputLog.info("Undo: ${s.label}")
        notifyPlugins("OnUndo", s.label)
    }

    fun redo() {
        val s = synchronized(studio.lock) { redoStack.removeLastOrNull() } ?: return
        apply(s, false)
        undoStack.addLast(s)
        OutputLog.info("Redo: ${s.label}")
        notifyPlugins("OnRedo", s.label)
    }

    /** ChangeHistoryService.OnUndo / OnRedo for plugins (they reset their tools on undo). */
    private fun notifyPlugins(event: String, label: String) {
        synchronized(studio.lock) {
            val chs = studio.editModel.findService("ChangeHistoryService") ?: return
            try { studio.editRuntime.fire(chs, event, org.luaj.vm2.LuaValue.valueOf(label)) } catch (_: Exception) {}
        }
    }

    private fun apply(s: Step, reverse: Boolean) {
        synchronized(studio.lock) {
            suspended = true
            try {
                val ops = if (reverse) s.ops.asReversed() else s.ops
                for (op in ops) when (op) {
                    is Op.Prop -> {
                        if (reverse) {
                            if (op.hadOld) op.inst.set(op.name, op.old) else { op.inst.props.remove(op.name); op.inst.root()?.firePropChanged(op.inst, op.name) }
                        } else op.inst.set(op.name, op.new)
                    }
                    is Op.Parent -> {
                        if (reverse) {
                            op.inst.destroyed = false
                            op.inst.setParent(op.oldParent, op.oldIndex)
                        } else op.inst.setParent(op.newParent)
                    }
                }
            } finally {
                suspended = false
            }
        }
        studio.markDirty()
        studio.pruneSelection()
    }
}
