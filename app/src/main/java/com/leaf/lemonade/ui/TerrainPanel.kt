package com.leaf.lemonade.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.engine.TerrainGenerator
import com.leaf.lemonade.engine.TerrainMaterials
import com.leaf.lemonade.engine.TerrainOps
import com.leaf.lemonade.engine.TerrainStore
import com.leaf.lemonade.model.Vector3

/**
 * Studio's Terrain Editor as a dock panel: Create (Generate with map size, seed, biomes, biome
 * size and caves; Clear) and Edit (Add, Subtract, Grow, Erode, Smooth, Flatten, Paint, Sea Level
 * brushes with size, strength, shape and material). Brushes sculpt with one finger in the 3D view.
 */
@SuppressLint("ViewConstructor")
class TerrainPanel(ctx: Context, private val studio: Studio) : LinearLayout(ctx) {
    private val body = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(Ui.dp(ctx, 8f), Ui.dp(ctx, 6f), Ui.dp(ctx, 8f), Ui.dp(ctx, 12f)) }
    private var page = "Edit"
    private val tabs = LinearLayout(ctx).apply { orientation = HORIZONTAL }

    // Generate options (kept while the panel lives).
    private var mapSize = 512
    private var seed = (System.currentTimeMillis() % 1_000_000_000L).toInt()
    private val biomes = linkedSetOf(TerrainGenerator.Biome.WATER, TerrainGenerator.Biome.PLAINS, TerrainGenerator.Biome.HILLS, TerrainGenerator.Biome.MOUNTAINS)
    private var biomeSize = 100
    private var caves = true
    @Volatile private var generating = false

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.PANEL)
        addView(Ui.header(ctx, "Terrain Editor", null), Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(tabs, Ui.lp(Ui.MATCH, Ui.WRAP))
        addView(ScrollView(ctx).apply { addView(body) }, Ui.lp(Ui.MATCH, 0, 1f))
        build()
    }

    /** Deactivates the brush when the panel is hidden. */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && changedView === this) studio.terrainTool = null
    }

    private fun build() {
        tabs.removeAllViews()
        for (p in listOf("Create", "Edit")) {
            tabs.addView(Ui.text(context, p, 13f, if (p == page) Ui.TEXT else Ui.DIM, p == page).apply {
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 8f))
                background = if (p == page) GradientDrawable().apply { setColor(Ui.HOVER) } else null
                setOnClickListener { page = p; if (p == "Create") studio.terrainTool = null; build() }
            }, Ui.lp(0, Ui.WRAP, 1f))
        }
        body.removeAllViews()
        if (page == "Create") buildCreate() else buildEdit()
    }

    private fun label(s: String) = body.addView(Ui.text(context, s, 12f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 8f), 0, Ui.dp(context, 3f)) })

    private fun chips(options: List<String>, selected: (String) -> Boolean, onPick: (String) -> Unit) {
        val wrap = FlowLayout(context)
        for (o in options) {
            val on = selected(o)
            wrap.addView(Ui.text(context, o, 12f, if (on) 0xFFFFFFFF.toInt() else Ui.TEXT).apply {
                setPadding(Ui.dp(context, 10f), Ui.dp(context, 6f), Ui.dp(context, 10f), Ui.dp(context, 6f))
                background = GradientDrawable().apply { setColor(if (on) Ui.ACCENT else Ui.HOVER); cornerRadius = Ui.dp(context, 14f).toFloat() }
                setOnClickListener { onPick(o) }
            })
        }
        body.addView(wrap, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    private fun slider(name: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val lbl = Ui.text(context, "$name: ${fmt(value)}", 12f)
        row.addView(lbl, Ui.lp(Ui.dp(context, 118f), Ui.WRAP))
        row.addView(StudioSlider(context, min.toFloat(), max.toFloat(), value.toFloat(), 1f) { v, _ ->
            lbl.text = "$name: ${fmt(v.toInt())}"; onChange(v.toInt())
        }, Ui.lp(0, Ui.WRAP, 1f))
        body.addView(row, Ui.lp(Ui.MATCH, Ui.WRAP))
    }

    // ------------------------------------------------------------------ Create

    private fun buildCreate() {
        label("Map size")
        val sizes = listOf("Small" to 512, "Medium" to 1024, "Large" to 2048, "Massive" to 4096)
        chips(sizes.map { it.first }, { n -> sizes.first { it.first == n }.second == mapSize }) { n -> mapSize = sizes.first { it.first == n }.second; build() }
        body.addView(Ui.text(context, "${mapSize} x $mapSize studs" + if (mapSize >= 4096) " — big maps take a while and use a lot of memory on a phone" else "", 11f, Ui.DIM))
        label("Biomes")
        chips(TerrainGenerator.Biome.values().map { it.label }, { n -> biomes.any { it.label == n } }) { n ->
            val b = TerrainGenerator.Biome.values().first { it.label == n }
            if (b in biomes) { if (biomes.size > 1) biomes.remove(b) } else biomes.add(b)
            build()
        }
        slider("Biome size", 50, 500, biomeSize, { "$it studs" }) { biomeSize = it }
        body.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(StudioCheckBox(context, caves) { caves = it }, Ui.lp(Ui.dp(context, 24f), Ui.dp(context, 24f)))
            addView(Ui.text(context, "Caves", 13f).apply { setPadding(Ui.dp(context, 6f), 0, 0, 0) })
        })
        label("Seed")
        val seedRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val seedIn = Ui.input(context, seed.toString(), "Seed", numeric = true)
        seedRow.addView(seedIn, Ui.lp(0, Ui.WRAP, 1f))
        seedRow.addView(Ui.button(context, "Random") { seed = (Math.random() * 1_000_000_000).toInt(); seedIn.setText(seed.toString()) })
        body.addView(seedRow, Ui.lp(Ui.MATCH, Ui.WRAP))
        val status = Ui.text(context, "", 12f, Ui.DIM)
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; setPadding(0, Ui.dp(context, 12f), 0, 0) }
        actions.addView(Ui.button(context, "Generate", Ui.ACCENT) {
            seed = seedIn.text.toString().toIntOrNull() ?: seed
            generate(status)
        }.apply { setTextColor(0xFFFFFFFF.toInt()) }, Ui.lp(0, Ui.WRAP, 1f).apply { marginEnd = Ui.dp(context, 6f) })
        actions.addView(Ui.button(context, "Clear") { confirmClear() }, Ui.lp(0, Ui.WRAP, 1f))
        body.addView(actions, Ui.lp(Ui.MATCH, Ui.WRAP))
        body.addView(status)
        body.addView(Ui.text(context, "Generate replaces the place's terrain (Undo brings the old terrain back). Saved and published with the place, like Roblox Studio.", 11f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 10f), 0, 0) })
    }

    private fun generate(status: TextView) {
        if (generating || studio.isPlaying) return
        generating = true
        status.text = "Generating…"
        val opts = TerrainGenerator.Options(size = mapSize, seed = seed, biomes = biomes.toSet(), biomeSize = biomeSize, caves = caves)
        val t = studio.editTerrain()
        val g = TerrainStore.of(t)
        Thread {
            val t0 = System.currentTimeMillis()
            try {
                val n = TerrainGenerator.generate(g, opts) { f -> post { status.text = "Generating… ${(f * 100).toInt()}%" } }
                post {
                    studio.terrainCommit("Terrain: Generate")
                    status.text = "Generated $n voxels in ${(System.currentTimeMillis() - t0) / 1000.0}s"
                    // Look at the new map from above its middle.
                    synchronized(studio.lock) {
                        studio.camera.pos = Vector3(0f, (mapSize * 0.25f).coerceIn(60f, 400f), (mapSize * 0.35f).coerceIn(80f, 600f))
                        studio.camera.yaw = 0f; studio.camera.pitch = -0.6f
                    }
                    generating = false
                }
            } catch (e: Throwable) {
                OutputLog.error("Terrain generation failed: ${e.message ?: e}")
                post { status.text = "Failed: ${e.message ?: e}"; generating = false }
            }
        }.apply { priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private fun confirmClear() {
        Dialogs.themed(context).setTitle("Clear terrain?").setMessage("Removes all terrain from the place. You can undo it.")
            .setPositiveButton("Clear") { _, _ ->
                synchronized(studio.lock) { TerrainStore.of(studio.editTerrain()).clear() }
                studio.terrainCommit("Terrain: Clear")
            }.setNegativeButton("Cancel", null).show()
    }

    // ------------------------------------------------------------------ Edit

    private fun buildEdit() {
        label("Tools (drag on the 3D view with one finger; two fingers move the camera)")
        val tools = listOf(
            TerrainOps.Brush.ADD to "Add", TerrainOps.Brush.SUBTRACT to "Subtract", TerrainOps.Brush.GROW to "Grow", TerrainOps.Brush.ERODE to "Erode",
            TerrainOps.Brush.SMOOTH to "Smooth", TerrainOps.Brush.FLATTEN to "Flatten", TerrainOps.Brush.PAINT to "Paint", TerrainOps.Brush.SEA_LEVEL to "Sea Level",
        )
        chips(tools.map { it.second }, { n -> tools.firstOrNull { it.second == n }?.first == studio.terrainTool }) { n ->
            val b = tools.first { it.second == n }.first
            studio.terrainTool = if (studio.terrainTool == b) null else b
            if (studio.terrainTool != null) studio.select(emptyList())
            build()
        }
        label("Brush")
        slider("Size", 2, 64, studio.terrainBrushSize.toInt(), { "$it studs" }) { studio.terrainBrushSize = it.toFloat() }
        slider("Strength", 5, 100, (studio.terrainBrushStrength * 100).toInt(), { "$it%" }) { studio.terrainBrushStrength = it / 100f }
        chips(listOf("Sphere", "Cube"), { (it == "Cube") == studio.terrainCube }) { studio.terrainCube = it == "Cube"; build() }
        label("Material")
        val grid = FlowLayout(context)
        val colors = TerrainMaterials.colors(studio.model.workspace.findFirstChildOfClass("Terrain"))
        for (m in TerrainMaterials.SOLID + TerrainMaterials.WATER) {
            val on = m == studio.terrainMaterial
            val cell = LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(Ui.dp(context, 4f), Ui.dp(context, 4f), Ui.dp(context, 4f), Ui.dp(context, 4f))
                background = GradientDrawable().apply { setColor(if (on) Ui.SELECT else 0); cornerRadius = Ui.dp(context, 4f).toFloat() }
                setOnClickListener { studio.terrainMaterial = m; build() }
            }
            val c = colors[m]
            cell.addView(View(context).apply {
                background = GradientDrawable().apply { setColor(android.graphics.Color.rgb((c.r * 255).toInt(), (c.g * 255).toInt(), (c.b * 255).toInt())); cornerRadius = Ui.dp(context, 4f).toFloat(); setStroke(Ui.dp(context, 1f), 0x55FFFFFF) }
            }, LinearLayout.LayoutParams(Ui.dp(context, 34f), Ui.dp(context, 34f)))
            cell.addView(Ui.text(context, TerrainMaterials.NAMES[m], 9.5f).apply { gravity = Gravity.CENTER; maxLines = 1 }, LinearLayout.LayoutParams(Ui.dp(context, 58f), Ui.WRAP))
            grid.addView(cell)
        }
        body.addView(grid, Ui.lp(Ui.MATCH, Ui.WRAP))
        body.addView(Ui.text(context, "Add and Paint use the material; Flatten and Sea Level use the height where your stroke starts. Each stroke is one undo.", 11f, Ui.DIM).apply { setPadding(0, Ui.dp(context, 8f), 0, 0) })
    }
}

