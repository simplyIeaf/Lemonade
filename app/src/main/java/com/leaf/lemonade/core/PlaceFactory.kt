package com.leaf.lemonade.core

import com.leaf.lemonade.engine.TerrainGenerator
import com.leaf.lemonade.engine.TerrainMaterials
import com.leaf.lemonade.engine.TerrainOps
import com.leaf.lemonade.engine.TerrainStore
import com.leaf.lemonade.format.RbxDocument
import com.leaf.lemonade.model.*

object PlaceFactory {
    private val required = listOf(
        "Workspace", "Players", "Lighting", "MaterialService", "ReplicatedFirst", "ReplicatedStorage", "ServerScriptService",
        "ServerStorage", "StarterGui", "StarterPack", "StarterPlayer", "Teams", "SoundService", "TextChatService",
    )

    /** Start-page templates, like Studio's New tab ("All Templates" / "Gameplay" / "Theme"). */
    enum class Template(val title: String, val subtitle: String, val category: String) {
        BASEPLATE("Baseplate", "A large baseplate, a spawn and Studio's current lighting", "All Templates"),
        CLASSIC("Classic Baseplate", "The classic studded baseplate and lighting", "All Templates"),
        FLAT_TERRAIN("Flat Terrain", "A big flat grass terrain to build on", "All Templates"),
        EMPTY("Empty Place", "Just a spawn location, no ground", "All Templates"),
        OBBY("Obby", "Jumps, kill bricks, checkpoints, moving and spinning obstacles", "Gameplay"),
        ARENA("Team/FFA Arena", "Two teams, an arena with cover, and a kill leaderboard", "Gameplay"),
        ISLAND("Island", "A generated terrain island in the sea", "Theme"),
        VILLAGE("Village", "Houses, trees and paths on grass terrain", "Theme"),
    }

    fun newPlace(template: Template = Template.BASEPLATE): DataModel {
        val dm = DataModel()
        dm.meta["ExplicitAutoJoints"] = "true"
        for (s in required) service(dm, s)
        val ws = dm.findService("Workspace")!!
        val cam = InstanceFactory.create("Camera")
        cam.set("CFrame", CFrame.lookAt(Vector3(0f, 20f, 40f), Vector3.ZERO), false)
        cam.attachRaw(ws)
        ws.set("CurrentCamera", cam, false)
        ensureTerrain(ws)
        val sp = dm.findService("StarterPlayer")!!
        InstanceFactory.create("StarterPlayerScripts").also { it.attachRaw(sp) }
        InstanceFactory.create("StarterCharacterScripts").also { it.attachRaw(sp) }

        if (template == Template.CLASSIC) classicLighting(dm.findService("Lighting")!!) else modernLighting(dm.findService("Lighting")!!)
        when (template) {
            Template.BASEPLATE -> { baseplate(ws); spawn(ws, Vector3(0f, 0.5f, 0f)) }
            Template.CLASSIC -> {
                part(ws, "Baseplate", Vector3(512f, 20f, 512f), CFrame(0f, -10f, 0f), Color3.fromRGB(99, 95, 98)).apply { set("TopSurface", EnumValue(3), false); set("Locked", true, false) }
                spawn(ws, Vector3(0f, 0.5f, 0f))
            }
            Template.EMPTY -> spawn(ws, Vector3(0f, 0.5f, 0f))
            Template.FLAT_TERRAIN -> { flatTerrain(ws, 512f); spawn(ws, Vector3(0f, 0.5f, 0f)) }
            Template.OBBY -> obby(dm, ws)
            Template.ARENA -> arena(dm, ws)
            Template.ISLAND -> island(ws, cam)
            Template.VILLAGE -> village(ws)
        }
        return dm
    }

    // ------------------------------------------------------------------ building blocks

    private fun part(parent: Instance, name: String, size: Vector3, cf: CFrame, color: Color3, material: Int = 256, cls: String = "Part"): Instance {
        val p = InstanceFactory.create(cls)
        p.name = name
        p.set("Anchored", true, false)
        p.set("size", size, false)
        p.set("CFrame", cf, false)
        p.set("Color3uint8", color, false)
        p.set("Material", EnumValue(material), false)
        p.attachRaw(parent)
        return p
    }

    private fun model(parent: Instance, name: String): Instance = InstanceFactory.create("Model").also { it.name = name; it.attachRaw(parent) }

    private fun script(parent: Instance, name: String, source: String, cls: String = "Script"): Instance =
        InstanceFactory.create(cls).also { it.name = name; it.set("Source", source.trimIndent() + "\n", false); it.attachRaw(parent) }

    private fun spawn(parent: Instance, at: Vector3, color: Color3 = Color3.fromRGB(163, 162, 165)): Instance {
        val s = InstanceFactory.create("SpawnLocation")
        s.set("CFrame", CFrame.at(at), false)
        s.set("Color3uint8", color, false)
        spawnDecal(s)
        s.attachRaw(parent)
        return s
    }

    /** The decal Studio puts on top of every new SpawnLocation. */
    fun spawnDecal(spawn: Instance) {
        if (spawn.children.any { it.className == "Decal" }) return
        InstanceFactory.create("Decal").apply {
            name = "Decal"
            set("Texture", "rbxasset://textures/SpawnLocation.png", false)
            set("Face", EnumValue(1), false)
        }.attachRaw(spawn)
    }

    /** Studio's Baseplate template ground: 2048 studs with the grid Texture on top. */
    private fun baseplate(ws: Instance) {
        val base = part(ws, "Baseplate", Vector3(2048f, 16f, 2048f), CFrame(0f, -8f, 0f), Color3.fromRGB(91, 93, 105))
        base.set("Locked", true, false)
        InstanceFactory.create("Texture").apply {
            name = "Texture"
            set("Texture", "rbxassetid://6372755229", false)
            set("Face", EnumValue(1), false)
            set("StudsPerTileU", 8f, false)
            set("StudsPerTileV", 8f, false)
            set("Transparency", 0.8f, false)
        }.attachRaw(base)
    }

    /** The current Baseplate template's Lighting: brighter sun, Atmosphere, Bloom, DepthOfField (off), SunRays. */
    private fun modernLighting(l: Instance) {
        l.set("Ambient", Color3.fromRGB(70, 70, 70), false)
        l.set("OutdoorAmbient", Color3.fromRGB(70, 70, 70), false)
        l.set("Brightness", 3f, false)
        l.set("ClockTime", 14.5f, false)
        l.set("EnvironmentDiffuseScale", 1f, false)
        l.set("EnvironmentSpecularScale", 1f, false)
        l.set("GlobalShadows", true, false)
        l.set("ShadowSoftness", 0.2f, false)
        l.set("Technology", EnumValue(3), false)
        InstanceFactory.create("Sky").also { it.name = "Sky" }.attachRaw(l)
        InstanceFactory.create("Atmosphere").also { a -> a.name = "Atmosphere"; a.set("Density", 0.3f, false); a.set("Offset", 0.25f, false) }.attachRaw(l)
        InstanceFactory.create("BloomEffect").also { it.name = "Bloom"; it.set("Intensity", 1f, false); it.set("Size", 24f, false); it.set("Threshold", 2f, false) }.attachRaw(l)
        InstanceFactory.create("DepthOfFieldEffect").also { it.name = "DepthOfField"; it.set("Enabled", false, false) }.attachRaw(l)
        InstanceFactory.create("SunRaysEffect").also { it.name = "SunRays"; it.set("Intensity", 0.01f, false); it.set("Spread", 0.1f, false) }.attachRaw(l)
    }

    /** The classic template's Lighting (before Atmosphere and post effects). */
    private fun classicLighting(l: Instance) {
        l.set("Ambient", Color3.fromRGB(0, 0, 0), false)
        l.set("OutdoorAmbient", Color3.fromRGB(127, 127, 127), false)
        l.set("Brightness", 2f, false)
        l.set("ClockTime", 14f, false)
        l.set("GlobalShadows", true, false)
        l.set("Technology", EnumValue(1), false)
        InstanceFactory.create("Sky").also { it.name = "Sky" }.attachRaw(l)
    }

    private fun terrain(ws: Instance): Instance = ws.findFirstChildOfClass("Terrain")!!

    /** Flat grass, its top at y = 0, [size] studs across. */
    private fun flatTerrain(ws: Instance, size: Float) {
        val t = terrain(ws)
        val g = TerrainStore.of(t)
        TerrainOps.fillBlock(g, CFrame(0f, -8f, 0f), Vector3(size, 16f, size), TerrainMaterials.fromName("Grass"))
        TerrainStore.flush(ws)
    }

    // ------------------------------------------------------------------ Obby

    private fun obby(dm: DataModel, ws: Instance) {
        val course = model(ws, "Obby")
        val stone = Color3.fromRGB(99, 95, 98)
        val green = Color3.fromRGB(75, 151, 75)
        val yellow = Color3.fromRGB(245, 205, 48)
        val start = part(course, "Start", Vector3(24f, 2f, 24f), CFrame(0f, -1f, 0f), stone, 816)
        start.set("TopSurface", EnumValue(0), false)
        spawn(course, Vector3(0f, 0.5f, 0f), green).also { it.name = "Checkpoint0"; it.set("Duration", 0, false) }
        // Lava below everything.
        val lava = part(course, "Lava", Vector3(400f, 2f, 600f), CFrame(0f, -40f, 200f), Color3.fromRGB(255, 89, 0), 288)
        lava.set("CanCollide", false, false)
        InstanceFactory.create("BoolValue").also { it.name = "KillPart"; it.attachRaw(lava) }
        // Stage 1: jumps.
        var z = 20f
        val colors = listOf(Color3.fromRGB(13, 105, 172), Color3.fromRGB(196, 40, 28), Color3.fromRGB(245, 205, 48), Color3.fromRGB(75, 151, 75))
        for (i in 0 until 6) {
            z += 9f
            part(course, "Jump$i", Vector3(6f, 1f, 6f), CFrame(((i % 2) * 2 - 1) * 4f, i * 1.5f, z), colors[i % colors.size], 272)
        }
        z += 14f
        // Checkpoint 1 on a landing platform.
        part(course, "Landing1", Vector3(16f, 2f, 16f), CFrame(0f, 7f, z), stone, 816)
        spawn(course, Vector3(0f, 8.5f, z), green).also { it.name = "Checkpoint1"; it.set("Duration", 0, false) }
        // Stage 2: a bridge with kill bricks across it.
        z += 8f
        part(course, "Bridge", Vector3(10f, 1f, 40f), CFrame(0f, 7.5f, z + 20f), Color3.fromRGB(163, 162, 165), 512)
        for (i in 0 until 4) {
            val kb = part(course, "KillBrick$i", Vector3(10f, 0.4f, 2f), CFrame(0f, 8.2f, z + 6f + i * 9f), Color3.fromRGB(255, 0, 0), 288)
            InstanceFactory.create("BoolValue").also { it.name = "KillPart"; it.attachRaw(kb) }
        }
        z += 46f
        part(course, "Landing2", Vector3(16f, 2f, 16f), CFrame(0f, 7f, z), stone, 816)
        spawn(course, Vector3(0f, 8.5f, z), green).also { it.name = "Checkpoint2"; it.set("Duration", 0, false) }
        // Stage 3: a moving platform (TweenService).
        z += 12f
        val mover = part(course, "MovingPlatform", Vector3(8f, 1f, 8f), CFrame(0f, 7.5f, z + 4f), Color3.fromRGB(13, 105, 172), 272)
        script(mover, "Move", """
            -- Moves the platform back and forth forever.
            local TweenService = game:GetService("TweenService")
            local part = script.Parent
            local startCF = part.CFrame
            local info = TweenInfo.new(3, Enum.EasingStyle.Sine, Enum.EasingDirection.InOut, -1, true)
            TweenService:Create(part, info, { CFrame = startCF + Vector3.new(0, 0, 24) }):Play()
        """)
        z += 36f
        part(course, "Landing3", Vector3(16f, 2f, 16f), CFrame(0f, 7f, z), stone, 816)
        spawn(course, Vector3(0f, 8.5f, z), green).also { it.name = "Checkpoint3"; it.set("Duration", 0, false) }
        // Stage 4: a spinning kill bar over a round platform.
        z += 20f
        part(course, "SpinFloor", Vector3(2f, 30f, 30f), CFrame.at(Vector3(0f, 7f, z)) * CFrame.rz((Math.PI / 2).toFloat()), Color3.fromRGB(163, 162, 165), 816).set("shape", EnumValue(2), false)
        val bar = part(course, "Spinner", Vector3(28f, 1.2f, 1.2f), CFrame(0f, 9f, z), Color3.fromRGB(255, 0, 0), 288)
        InstanceFactory.create("BoolValue").also { it.name = "KillPart"; it.attachRaw(bar) }
        script(bar, "Spin", """
            -- Spins the bar around its center.
            local RunService = game:GetService("RunService")
            local bar = script.Parent
            local center = bar.CFrame
            local angle = 0
            RunService.Heartbeat:Connect(function(dt)
                angle = angle + dt * 2
                bar.CFrame = center * CFrame.Angles(0, angle, 0)
            end)
        """)
        z += 22f
        // Stage 5: climb a truss to the finish.
        part(course, "Landing4", Vector3(16f, 2f, 16f), CFrame(0f, 7f, z), stone, 816)
        spawn(course, Vector3(0f, 8.5f, z), green).also { it.name = "Checkpoint4"; it.set("Duration", 0, false) }
        part(course, "Truss", Vector3(2f, 24f, 2f), CFrame(0f, 20f, z + 7f), Color3.fromRGB(163, 162, 165), 1088, "TrussPart")
        val finish = part(course, "Finish", Vector3(16f, 2f, 16f), CFrame(0f, 31f, z + 15f), yellow, 288)
        script(finish, "Win", """
            -- Tells the player they won (once per life).
            local finish = script.Parent
            local won = {}
            finish.Touched:Connect(function(hit)
                local player = game.Players:GetPlayerFromCharacter(hit.Parent)
                if player and not won[player] then
                    won[player] = true
                    print(player.Name .. " finished the obby!")
                    local gui = Instance.new("ScreenGui")
                    gui.Name = "WinGui"
                    local label = Instance.new("TextLabel")
                    label.Size = UDim2.new(1, 0, 0, 60)
                    label.Position = UDim2.new(0, 0, 0.2, 0)
                    label.BackgroundTransparency = 1
                    label.Text = "You beat the obby!"
                    label.TextScaled = true
                    label.TextColor3 = Color3.fromRGB(255, 255, 255)
                    label.TextStrokeTransparency = 0
                    label.Font = Enum.Font.FredokaOne
                    label.Parent = gui
                    gui.Parent = player.PlayerGui
                    task.delay(4, function() gui:Destroy() end)
                end
            end)
        """)
        // Game logic: checkpoints, kill parts and the Stage leaderstat.
        script(dm.findService("ServerScriptService")!!, "ObbyScript", """
            -- Checkpoints: touching CheckpointN makes it your respawn point and sets your Stage.
            -- Kill parts: anything with a "KillPart" value inside resets your character.
            local Players = game:GetService("Players")
            local course = workspace:WaitForChild("Obby")

            Players.PlayerAdded:Connect(function(player)
                local stats = Instance.new("Folder")
                stats.Name = "leaderstats"
                local stage = Instance.new("IntValue")
                stage.Name = "Stage"
                stage.Value = 0
                stage.Parent = stats
                stats.Parent = player
            end)

            for _, part in ipairs(course:GetDescendants()) do
                if part:IsA("SpawnLocation") and part.Name:match("^Checkpoint") then
                    local number = tonumber(part.Name:match("%d+")) or 0
                    part.Touched:Connect(function(hit)
                        local player = Players:GetPlayerFromCharacter(hit.Parent)
                        if player and player:FindFirstChild("leaderstats") then
                            local stage = player.leaderstats.Stage
                            if number > stage.Value then
                                stage.Value = number
                                player.RespawnLocation = part
                            end
                        end
                    end)
                elseif part:IsA("BasePart") and part:FindFirstChild("KillPart") then
                    part.Touched:Connect(function(hit)
                        local humanoid = hit.Parent and hit.Parent:FindFirstChildOfClass("Humanoid")
                        if humanoid then humanoid.Health = 0 end
                    end)
                end
            end
        """)
    }

    // ------------------------------------------------------------------ Team/FFA Arena

    private fun arena(dm: DataModel, ws: Instance) {
        baseplate(ws)
        val teams = dm.findService("Teams")!!
        for ((name, color) in listOf("Red" to 21, "Blue" to 23)) {
            InstanceFactory.create("Team").also { it.name = name; it.set("TeamColor", BrickColorV(color), false); it.set("AutoAssignable", true, false); it.attachRaw(teams) }
        }
        val map = model(ws, "Arena")
        val wall = Color3.fromRGB(99, 95, 98)
        val size = 160f
        for ((n, cf, s) in listOf(
            Triple("NorthWall", CFrame(0f, 10f, -size / 2), Vector3(size, 20f, 4f)), Triple("SouthWall", CFrame(0f, 10f, size / 2), Vector3(size, 20f, 4f)),
            Triple("EastWall", CFrame(size / 2, 10f, 0f), Vector3(4f, 20f, size)), Triple("WestWall", CFrame(-size / 2, 10f, 0f), Vector3(4f, 20f, size)),
        )) part(map, n, s, cf, wall, 848)
        // Cover: crates and low walls in a symmetric layout.
        val rng = java.util.Random(7)
        for (i in 0 until 10) {
            val x = (rng.nextFloat() - 0.5f) * (size - 40f); val z = (rng.nextFloat() - 0.5f) * (size - 60f)
            val h = 4f + rng.nextInt(3) * 2f
            for (mirror in listOf(1f, -1f)) part(map, "Cover", Vector3(6f, h, 6f), CFrame(x * mirror, h / 2, z * mirror), Color3.fromRGB(124, 92, 70), 528)
        }
        part(map, "CenterTower", Vector3(14f, 10f, 14f), CFrame(0f, 5f, 0f), Color3.fromRGB(163, 162, 165), 816)
        // Team spawns at each end.
        for ((i, z) in listOf(-size / 2 + 14f, size / 2 - 14f).withIndex()) {
            val s = spawn(map, Vector3(0f, 0.5f, z), if (i == 0) Color3.fromRGB(196, 40, 28) else Color3.fromRGB(13, 105, 172))
            s.name = if (i == 0) "RedSpawn" else "BlueSpawn"
            s.set("Neutral", false, false)
            s.set("TeamColor", BrickColorV(if (i == 0) 21 else 23), false)
            s.set("Duration", 3, false)
        }
        // A simple sword in every backpack.
        val sword = InstanceFactory.create("Tool").also { it.name = "Sword"; it.set("Grip", CFrame(0f, -1.6f, 0f) * CFrame.rx(-(Math.PI / 2).toFloat()), false); it.attachRaw(dm.findService("StarterPack")!!) }
        val handle = InstanceFactory.create("Part").also { h ->
            h.name = "Handle"; h.set("size", Vector3(0.5f, 4f, 0.5f), false); h.set("Color3uint8", Color3.fromRGB(163, 162, 165), false)
            h.set("Material", EnumValue(1088), false); h.set("CanCollide", false, false); h.attachRaw(sword)
        }
        handle.set("Anchored", false, false)
        script(sword, "SwordScript", """
            -- Click (tap) to swing; hitting someone on the other team (or anyone, with no teams) does damage.
            local tool = script.Parent
            local handle = tool:WaitForChild("Handle")
            local swinging = false
            tool.Activated:Connect(function()
                if swinging then return end
                swinging = true
                task.wait(0.4)
                swinging = false
            end)
            handle.Touched:Connect(function(hit)
                if not swinging then return end
                local humanoid = hit.Parent and hit.Parent:FindFirstChildOfClass("Humanoid")
                local owner = game.Players:GetPlayerFromCharacter(tool.Parent)
                local victim = game.Players:GetPlayerFromCharacter(hit.Parent)
                if humanoid and owner and victim ~= owner and (not victim or victim.Team ~= owner.Team or owner.Neutral) then
                    humanoid:TakeDamage(25)
                end
            end)
        """)
        script(dm.findService("ServerScriptService")!!, "Leaderboard", """
            -- Kills and Deaths on the leaderboard.
            game.Players.PlayerAdded:Connect(function(player)
                local stats = Instance.new("Folder")
                stats.Name = "leaderstats"
                local kills = Instance.new("IntValue"); kills.Name = "Kills"; kills.Parent = stats
                local deaths = Instance.new("IntValue"); deaths.Name = "Deaths"; deaths.Parent = stats
                stats.Parent = player
                player.CharacterAdded:Connect(function(character)
                    character:WaitForChild("Humanoid").Died:Connect(function()
                        deaths.Value = deaths.Value + 1
                    end)
                end)
            end)
        """)
    }

    // ------------------------------------------------------------------ Island

    private fun island(ws: Instance, cam: Instance) {
        val t = terrain(ws)
        val g = TerrainStore.of(t)
        TerrainGenerator.generate(g, TerrainGenerator.Options(size = 512, seed = 20260412,
            biomes = setOf(TerrainGenerator.Biome.WATER, TerrainGenerator.Biome.PLAINS, TerrainGenerator.Biome.HILLS), biomeSize = 220, caves = false))
        // Make sure there's land in the middle (a sand dome under whatever was generated).
        TerrainOps.fillBall(g, Vector3(0f, -40f, 0f), 44f, TerrainMaterials.fromName("Sand"))
        // The generated hills decide how high the ground is there: find it, then flatten a round
        // grass clearing at that height so the spawn sits on the ground (not buried in a hill).
        val colors = com.leaf.lemonade.engine.TerrainMeshes.colorsFor(t)
        val hit = com.leaf.lemonade.engine.TerrainMeshes.raycast(g, colors, Vector3(0f, 400f, 0f), Vector3(0f, -1f, 0f), 800f)
        val ground = if (hit != null) 400f - hit.first else 4f
        val level = kotlin.math.ceil(ground / 4f) * 4f
        TerrainOps.fillCylinder(g, CFrame(0f, level + 40f, 0f), 80f, 24f, TerrainMaterials.AIR)
        TerrainOps.fillCylinder(g, CFrame(0f, level - 6f, 0f), 12f, 22f, TerrainMaterials.fromName("Grass"))
        TerrainStore.flush(ws)
        spawn(ws, Vector3(0f, level + 0.5f, 0f))
        cam.set("CFrame", CFrame.lookAt(Vector3(0f, level + 70f, 140f), Vector3(0f, level, 0f)), false)
    }

    // ------------------------------------------------------------------ Village

    private fun village(ws: Instance) {
        flatTerrain(ws, 512f)
        val t = terrain(ws)
        val g = TerrainStore.of(t)
        // Cobblestone paths in a cross.
        TerrainOps.fillBlock(g, CFrame(0f, -1f, 0f), Vector3(10f, 2f, 220f), TerrainMaterials.fromName("Cobblestone"))
        TerrainOps.fillBlock(g, CFrame(0f, -1f, 0f), Vector3(220f, 2f, 10f), TerrainMaterials.fromName("Cobblestone"))
        TerrainStore.flush(ws)
        val village = model(ws, "Village")
        val rng = java.util.Random(11)
        val wallColors = listOf(Color3.fromRGB(234, 184, 146), Color3.fromRGB(204, 142, 105), Color3.fromRGB(239, 184, 56), Color3.fromRGB(248, 248, 248))
        val roofColors = listOf(Color3.fromRGB(124, 92, 70), Color3.fromRGB(151, 0, 0), Color3.fromRGB(91, 93, 105))
        val lots = listOf(-1 to -1, -1 to 1, 1 to -1, 1 to 1, -1 to -2, 1 to 2, 2 to -1, -2 to 1)
        for ((i, lot) in lots.withIndex()) {
            val (lx, lz) = lot
            val cx = lx * 34f; val cz = lz * 34f
            val faceZ = if (lz > 0) -1f else 1f
            house(village, "House$i", Vector3(cx, 0f, cz), faceZ, wallColors[i % wallColors.size], roofColors[rng.nextInt(roofColors.size)])
        }
        for (i in 0 until 26) {
            val a = rng.nextFloat() * Math.PI.toFloat() * 2; val r = 70f + rng.nextFloat() * 120f
            tree(village, Vector3(kotlin.math.cos(a) * r, 0f, kotlin.math.sin(a) * r), 0.8f + rng.nextFloat() * 0.6f)
        }
        // A well in the middle.
        val well = model(village, "Well")
        part(well, "Base", Vector3(3f, 8f, 8f), CFrame.at(Vector3(0f, 1.5f, 0f)) * CFrame.rz((Math.PI / 2).toFloat()), Color3.fromRGB(163, 162, 165), 880).set("shape", EnumValue(2), false)
        part(well, "Water", Vector3(0.6f, 6f, 6f), CFrame.at(Vector3(0f, 2.8f, 0f)) * CFrame.rz((Math.PI / 2).toFloat()), Color3.fromRGB(33, 84, 185), 272).set("shape", EnumValue(2), false)
        for (x in listOf(-3.2f, 3.2f)) part(well, "Post", Vector3(0.6f, 6f, 0.6f), CFrame(x, 6f, 0f), Color3.fromRGB(105, 64, 40), 512)
        part(well, "Roof", Vector3(8f, 1.5f, 5f), CFrame(0f, 9.5f, 0f), Color3.fromRGB(124, 92, 70), 528, "WedgePart")
        spawn(ws, Vector3(0f, 0.5f, 16f))
    }

    private fun house(parent: Instance, name: String, at: Vector3, faceZ: Float, wall: Color3, roof: Color3) {
        val m = model(parent, name)
        val w = 18f; val d = 16f; val h = 10f
        fun at(x: Float, y: Float, z: Float) = CFrame(at.x + x, at.y + y, at.z + z * faceZ)
        part(m, "Floor", Vector3(w, 1f, d), at(0f, 0.5f, 0f), Color3.fromRGB(105, 64, 40), 528)
        part(m, "BackWall", Vector3(w, h, 1f), at(0f, h / 2 + 1f, -d / 2 + 0.5f), wall, 848)
        part(m, "LeftWall", Vector3(1f, h, d), at(-w / 2 + 0.5f, h / 2 + 1f, 0f), wall, 848)
        part(m, "RightWall", Vector3(1f, h, d), at(w / 2 - 0.5f, h / 2 + 1f, 0f), wall, 848)
        // Front wall with a door gap.
        part(m, "FrontLeft", Vector3(7f, h, 1f), at(-5.5f, h / 2 + 1f, d / 2 - 0.5f), wall, 848)
        part(m, "FrontRight", Vector3(7f, h, 1f), at(5.5f, h / 2 + 1f, d / 2 - 0.5f), wall, 848)
        part(m, "FrontTop", Vector3(4f, 3f, 1f), at(0f, h - 0.5f, d / 2 - 0.5f), wall, 848)
        for (x in listOf(-5.5f, 5.5f)) part(m, "Window", Vector3(3f, 3f, 1.1f), at(x, 6f, d / 2 - 0.5f), Color3.fromRGB(128, 187, 219), 1568).set("Transparency", 0.4f, false)
        // Gable roof: two wedges meeting at the ridge.
        val rise = 6f
        val half = w / 2 + 1f
        for (side in listOf(-1f, 1f)) {
            // A wedge's high edge is its back (+Z); turn it so that edge is the ridge in the middle.
            val turn = if (side < 0) Math.PI / 2 else -Math.PI / 2
            part(m, "Roof", Vector3(d + 2f, rise, half), CFrame.at(Vector3(at.x + side * half / 2, at.y + h + 1f + rise / 2, at.z)) * CFrame.ry(turn.toFloat()), roof, 2308, "WedgePart")
        }
        // A lamp by the door (lights up at night).
        val lamp = part(m, "Lamp", Vector3(0.8f, 0.8f, 0.8f), at(3f, 4f, d / 2 + 0.6f), Color3.fromRGB(255, 220, 150), 288)
        InstanceFactory.create("PointLight").also { it.set("Range", 14f, false); it.set("Brightness", 1.2f, false); it.set("Color", Color3.fromRGB(255, 214, 160), false); it.attachRaw(lamp) }
    }

    private fun tree(parent: Instance, at: Vector3, s: Float) {
        val m = model(parent, "Tree")
        part(m, "Trunk", Vector3(1.6f * s, 8f * s, 1.6f * s), CFrame(at.x, 4f * s, at.z), Color3.fromRGB(105, 64, 40), 512)
        val leaves = part(m, "Leaves", Vector3(9f * s, 9f * s, 9f * s), CFrame(at.x, 10f * s, at.z), Color3.fromRGB(75, 151, 75), 1284)
        leaves.set("shape", EnumValue(0), false)
    }

    private fun service(dm: DataModel, cls: String): Instance {
        dm.findService(cls)?.let { return it }
        val s = InstanceFactory.create(cls)
        s.isService = true
        s.attachRaw(dm)
        return s
    }

    fun fromDocument(doc: RbxDocument): DataModel {
        val dm = DataModel()
        dm.meta.putAll(doc.meta)
        for (r in doc.roots) {
            if (ClassDb.isServiceName(r.className)) r.isService = true
            r.attachRaw(dm)
        }
        for (s in required) service(dm, s)
        val ws = dm.findService("Workspace")!!
        if (ws["CurrentCamera"] == null) {
            val cam = ws.findFirstChildOfClass("Camera") ?: InstanceFactory.create("Camera").also { it.attachRaw(ws) }
            ws.set("CurrentCamera", cam, false)
        }
        ensureTerrain(ws)
        return dm
    }

    /** Every Roblox place has Workspace.Terrain (scripts and plugins use workspace.Terrain). */
    private fun ensureTerrain(ws: Instance) {
        if (ws.findFirstChildOfClass("Terrain") != null) return
        val t = InstanceFactory.create("Terrain")
        t.name = "Terrain"
        t.set("Anchored", true, false); t.set("Locked", true, false)
        t.set("WaterColor", Color3.fromRGB(12, 84, 92), false)
        t.set("WaterTransparency", 0.3f, false); t.set("WaterReflectance", 1f, false)
        t.set("WaterWaveSize", 0.15f, false); t.set("WaterWaveSpeed", 10f, false)
        t.isService = false
        t.attachRaw(ws)
    }
}
