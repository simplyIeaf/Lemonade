package com.leaf.lemonade.core

import android.content.Context
import com.leaf.lemonade.format.RbxFile
import com.leaf.lemonade.model.Instance
import com.leaf.lemonade.model.InstanceFactory
import java.io.File

/**
 * Plugins are model files (binary or XML) or Lua source stored in the app's private "plugins"
 * folder. The format is detected from the content, so a file downloaded straight from Roblox
 * (".bin", or no extension at all) installs the same as an .rbxm.
 */
class PluginManager(private val studio: Studio) {
    val dir: File get() = File(studio.app.filesDir, "plugins").also { it.mkdirs() }
    private val prefs by lazy { studio.app.getSharedPreferences("plugins_state", Context.MODE_PRIVATE) }

    fun list(): List<File> = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun isEnabled(f: File) = !prefs.getBoolean("off:" + f.name, false)
    fun setEnabled(f: File, on: Boolean) = prefs.edit().putBoolean("off:" + f.name, !on).apply()

    /** Figures out what [bytes] are: returns the (decompressed) data and a file extension. */
    fun detect(bytes: ByteArray): Pair<ByteArray, String> {
        val b = RbxFile.unwrap(bytes)
        if (RbxFile.isBinary(b)) return b to "rbxm"
        if (RbxFile.isXml(b)) return b to "rbxmx"
        val head = String(b, 0, minOf(b.size, 4096), Charsets.UTF_8)
        val looksText = head.none { it == '\u0000' } && head.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' } < 4
        if (looksText && Regex("""\b(local|function|end|return|plugin|game|print|require)\b""").containsMatchIn(head)) return b to "lua"
        throw IllegalArgumentException("This file isn't a Roblox plugin. Expected a model file (.rbxm/.rbxmx, or a .bin downloaded from Roblox) or Lua source.")
    }

    fun install(name: String, bytes: ByteArray): File {
        val (data, ext) = detect(bytes)
        var base = name.substringAfterLast('/').substringAfterLast('\\')
        val known = setOf("rbxm", "rbxmx", "lua", "luau", "txt", "bin", "rbxl", "rbxlx")
        if (base.substringAfterLast('.', "").lowercase() in known) base = base.substringBeforeLast('.')
        base = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "Plugin" }.take(60)
        val f = File(dir, "$base.$ext")
        f.writeBytes(data)
        setEnabled(f, true)
        return f
    }

    fun remove(f: File) { f.delete(); prefs.edit().remove("off:" + f.name).apply() }

    private var loadGen = 0

    /**
     * (Re)loads every enabled plugin. Each plugin starts in its own turn of the UI thread's
     * message loop, so the screen draws and responds between plugins instead of freezing until
     * all of them have run (big plugins like F3X or Archimedes take seconds on slow phones).
     */
    fun loadAll() {
        val files = list()
        synchronized(studio.lock) {
            // Old copies stop listening (their runtime keeps running for the open place).
            for (p in loaded) { studio.editRuntime.fire(p, "Unloading"); studio.pluginMice.remove(p) }
            loaded.clear()
            studio.deactivatePlugin(null)
            studio.pluginButtons.clear()
            studio.pluginWidgets.clear()
            studio.editModel.findService("CoreGui")?.children?.toList()?.forEach { it.destroy() }
        }
        val gen = ++loadGen
        val queue = files.filter { isEnabled(it) }
        fun next(i: Int) {
            if (gen != loadGen) return
            if (i >= queue.size) { studio.notifyPluginButtons(); return }
            load(queue[i])
            studio.notifyPluginButtons()
            studio.main.post { next(i + 1) }
        }
        studio.notifyPluginButtons()
        studio.main.post { next(0) }
    }

    private val loaded = ArrayList<Instance>()

    fun load(f: File) {
        try {
            val pluginInst = InstanceFactory.create("Plugin")
            pluginInst.name = f.nameWithoutExtension
            val scripts = ArrayList<Instance>()
            val ext = f.extension.lowercase()
            val raw = f.readBytes()
            val kind = if (ext in setOf("lua", "luau", "txt")) "lua" else try { detect(raw).second } catch (_: Exception) { ext }
            if (kind == "lua") {
                val s = InstanceFactory.create("Script")
                s.name = f.nameWithoutExtension
                s.set("Source", String(RbxFile.unwrap(raw), Charsets.UTF_8), false)
                s.attachRaw(pluginInst)
                scripts.add(s)
            } else {
                val doc = RbxFile.read(raw)
                for (r in doc.roots) r.attachRaw(pluginInst)
                // Like Studio: every enabled Script and LocalScript in the plugin runs as plugin code
                // (with the `plugin` global), wherever it sits, even inside a Tool. Building Tools by
                // F3X needs this: a LocalScript in it sets the "Loaded" flag its loader waits for.
                for (d in pluginInst.descendants()) {
                    if (d["Disabled"] == true || d["Enabled"] == false) continue
                    val ctx = (d["RunContext"] as? com.leaf.lemonade.model.EnumValue)?.value ?: 0
                    if ((d.className == "Script" && (ctx == 0 || ctx == 3)) || d.className == "LocalScript") scripts.add(d)
                }
            }
            if (scripts.isEmpty()) { OutputLog.warn("Plugin ${f.name} has no Scripts to run"); return }
            synchronized(studio.lock) {
                val rt = studio.editRuntime
                val pv = rt.wrap(pluginInst)
                loaded.add(pluginInst)
                for (s in scripts) rt.runScript(s, mapOf("plugin" to pv))
            }
            OutputLog.info("Loaded plugin ${f.nameWithoutExtension}")
            studio.main.postDelayed({
                if (studio.pluginButtons.none { it.pluginName == pluginInst.name }) {
                    OutputLog.warn("Plugin ${pluginInst.name} hasn't added a toolbar button yet. If it uses features this app doesn't have, its errors are shown above.")
                }
            }, 4000)
        } catch (e: Exception) {
            OutputLog.error("Failed to load plugin ${f.name}: ${e.message}")
        }
    }
}
