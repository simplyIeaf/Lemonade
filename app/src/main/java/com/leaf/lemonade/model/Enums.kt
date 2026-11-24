package com.leaf.lemonade.model

object Enums {
    val types: Map<String, LinkedHashMap<String, Int>> = HashMap<String, LinkedHashMap<String, Int>>().apply {
        fun e(name: String, vararg items: Pair<String, Int>) { put(name, linkedMapOf(*items)) }
        fun seq(name: String, vararg items: String) { put(name, LinkedHashMap<String, Int>().apply { items.forEachIndexed { i, s -> put(s, i) } }) }
        e("Material", "Plastic" to 256, "SmoothPlastic" to 272, "Neon" to 288, "Wood" to 512, "WoodPlanks" to 528,
            "Marble" to 784, "Basalt" to 788, "Slate" to 800, "CrackedLava" to 804, "Concrete" to 816, "Limestone" to 820,
            "Granite" to 832, "Pavement" to 836, "Brick" to 848, "Pebble" to 864, "Cobblestone" to 880, "Rock" to 896,
            "Sandstone" to 912, "CorrodedMetal" to 1040, "DiamondPlate" to 1056, "Foil" to 1072, "Metal" to 1088,
            "Grass" to 1280, "LeafyGrass" to 1284, "Sand" to 1296, "Fabric" to 1312, "Snow" to 1328, "Mud" to 1344,
            "Ground" to 1360, "Asphalt" to 1376, "Salt" to 1392, "Ice" to 1536, "Glacier" to 1552, "Glass" to 1568,
            "ForceField" to 1584, "Air" to 1792, "Water" to 2048, "Cardboard" to 2304, "Carpet" to 2305,
            "CeramicTiles" to 2306, "ClayRoofTiles" to 2307, "RoofShingles" to 2308, "Leather" to 2309, "Plaster" to 2310,
            "Rubber" to 2311)
        e("PartType", "Ball" to 0, "Block" to 1, "Cylinder" to 2, "Wedge" to 3, "CornerWedge" to 4)
        e("MaterialPattern", "Regular" to 0, "Organic" to 1)
        e("SurfaceType", "Smooth" to 0, "Glue" to 1, "Weld" to 2, "Studs" to 3, "Inlet" to 4, "Universal" to 5,
            "Hinge" to 6, "Motor" to 7, "SteppingMotor" to 8, "SmoothNoOutlines" to 10)
        seq("EasingStyle", "Linear", "Sine", "Back", "Quad", "Quart", "Quint", "Bounce", "Elastic", "Exponential", "Circular", "Cubic")
        seq("EasingDirection", "In", "Out", "InOut")
        seq("TextXAlignment", "Left", "Right", "Center")
        seq("TextYAlignment", "Top", "Center", "Bottom")
        seq("FillDirection", "Horizontal", "Vertical")
        seq("HorizontalAlignment", "Center", "Left", "Right")
        seq("VerticalAlignment", "Center", "Top", "Bottom")
        e("SortOrder", "Name" to 0, "Custom" to 1, "LayoutOrder" to 2)
        seq("ZIndexBehavior", "Global", "Sibling")
        seq("ScaleType", "Stretch", "Slice", "Tile", "Fit", "Crop")
        seq("AutomaticSize", "None", "X", "Y", "XY")
        seq("NormalId", "Right", "Top", "Back", "Left", "Bottom", "Front")
        e("RunContext", "Legacy" to 0, "Server" to 1, "Client" to 2, "Plugin" to 3)
        e("RaycastFilterType", "Exclude" to 0, "Include" to 1, "Blacklist" to 0, "Whitelist" to 1)
        e("HumanoidStateType", "FallingDown" to 0, "Ragdoll" to 1, "GettingUp" to 2, "Jumping" to 3, "Swimming" to 4,
            "Freefall" to 5, "Flying" to 6, "Landed" to 7, "Running" to 8, "RunningNoPhysics" to 10, "StrafingNoPhysics" to 11,
            "Climbing" to 12, "Seated" to 13, "PlatformStanding" to 14, "Dead" to 15, "Physics" to 16, "None" to 18)
        e("UserInputType", "MouseButton1" to 0, "MouseButton2" to 1, "MouseButton3" to 2, "MouseWheel" to 3,
            "MouseMovement" to 4, "Touch" to 7, "Keyboard" to 8, "Focus" to 9, "Gamepad1" to 12, "None" to 18)
        e("UserInputState", "Begin" to 0, "Change" to 1, "End" to 2, "Cancel" to 3, "None" to 4)
        put("KeyCode", LinkedHashMap<String, Int>().apply {
            put("Unknown", 0); put("Backspace", 8); put("Tab", 9); put("Clear", 12); put("Return", 13); put("Pause", 19); put("Escape", 27)
            val punct = listOf("Space", "QuotedDouble", "Hash", "Dollar", "Percent", "Ampersand", "Quote", "LeftParenthesis", "RightParenthesis",
                "Asterisk", "Plus", "Comma", "Minus", "Period", "Slash")
            punct.forEachIndexed { i, s -> put(s, 32 + i) }
            listOf("Zero", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine").forEachIndexed { i, s -> put(s, 48 + i) }
            listOf("Colon", "Semicolon", "LessThan", "Equals", "GreaterThan", "Question", "At").forEachIndexed { i, s -> put(s, 58 + i) }
            listOf("LeftBracket", "BackSlash", "RightBracket", "Caret", "Underscore", "Backquote").forEachIndexed { i, s -> put(s, 91 + i) }
            for (c in 'A'..'Z') put(c.toString(), 97 + (c - 'A'))
            listOf("LeftCurly", "Pipe", "RightCurly", "Tilde", "Delete").forEachIndexed { i, s -> put(s, 123 + i) }
            listOf("KeypadZero", "KeypadOne", "KeypadTwo", "KeypadThree", "KeypadFour", "KeypadFive", "KeypadSix", "KeypadSeven", "KeypadEight",
                "KeypadNine", "KeypadPeriod", "KeypadDivide", "KeypadMultiply", "KeypadMinus", "KeypadPlus", "KeypadEnter", "KeypadEquals",
                "Up", "Down", "Right", "Left", "Insert", "Home", "End", "PageUp", "PageDown").forEachIndexed { i, s -> put(s, 256 + i) }
            for (f in 1..15) put("F$f", 281 + f)
            listOf("NumLock", "CapsLock", "ScrollLock", "RightShift", "LeftShift", "RightControl", "LeftControl", "RightAlt", "LeftAlt",
                "RightMeta", "LeftMeta", "LeftSuper", "RightSuper", "Mode", "Compose", "Help", "Print", "SysReq", "Break", "Menu", "Power",
                "Euro", "Undo").forEachIndexed { i, s -> put(s, 300 + i) }
            listOf("ButtonX", "ButtonY", "ButtonA", "ButtonB", "ButtonR1", "ButtonL1", "ButtonR2", "ButtonL2", "ButtonR3", "ButtonL3",
                "ButtonStart", "ButtonSelect", "DPadLeft", "DPadRight", "DPadUp", "DPadDown", "Thumbstick1", "Thumbstick2").forEachIndexed { i, s -> put(s, 1000 + i) }
        })
        e("CameraType", "Fixed" to 0, "Attach" to 1, "Watch" to 2, "Track" to 3, "Follow" to 4, "Custom" to 5, "Scriptable" to 6, "Orbital" to 7)
        e("Font", "Legacy" to 0, "Arial" to 1, "ArialBold" to 2, "SourceSans" to 3, "SourceSansBold" to 4, "SourceSansLight" to 5,
            "SourceSansItalic" to 6, "Bodoni" to 7, "Garamond" to 8, "Cartoon" to 9, "Code" to 10, "Highway" to 11, "SciFi" to 12,
            "Arcade" to 13, "Fantasy" to 14, "Antique" to 15, "SourceSansSemibold" to 16, "Gotham" to 17, "GothamMedium" to 18,
            "GothamBold" to 19, "GothamBlack" to 20, "AmaticSC" to 21, "Bangers" to 22, "Creepster" to 23, "DenkOne" to 24,
            "Fondamento" to 25, "FredokaOne" to 26, "GrenzeGotisch" to 27, "IndieFlower" to 28, "JosefinSans" to 29,
            "Jura" to 30, "Kalam" to 31, "LuckiestGuy" to 32, "Merriweather" to 33, "Michroma" to 34, "Nunito" to 35,
            "Oswald" to 36, "PatrickHand" to 37, "PermanentMarker" to 38, "Roboto" to 39, "RobotoCondensed" to 40,
            "RobotoMono" to 41, "Sarpanch" to 42, "SpecialElite" to 43, "TitilliumWeb" to 44, "Ubuntu" to 45, "BuilderSans" to 46)
        e("MeshType", "Head" to 0, "Torso" to 1, "Wedge" to 2, "Sphere" to 3, "Cylinder" to 4, "FileMesh" to 5, "Brick" to 6, "Prism" to 7, "Pyramid" to 8)
        e("FormFactor", "Symmetric" to 0, "Brick" to 1, "Plate" to 2, "Custom" to 3)
        e("SizeConstraint", "RelativeXY" to 0, "RelativeXX" to 1, "RelativeYY" to 2)
        e("BorderMode", "Outline" to 0, "Middle" to 1, "Inset" to 2)
        e("ApplyStrokeMode", "Contextual" to 0, "Border" to 1)
        e("Technology", "Legacy" to 0, "Voxel" to 1, "Compatibility" to 2, "ShadowMap" to 3, "Future" to 4)
        e("ScrollingDirection", "X" to 1, "Y" to 2, "XY" to 4)
        e("HighlightDepthMode", "AlwaysOnTop" to 0, "Occluded" to 1)
        e("ScreenInsets", "None" to 0, "DeviceSafeInsets" to 1, "CoreUISafeInsets" to 2, "TopbarSafeInsets" to 3)
        e("ResamplerMode", "Default" to 0, "Pixelated" to 1)
        e("Limb", "Head" to 0, "Torso" to 1, "LeftArm" to 2, "RightArm" to 3, "LeftLeg" to 4, "RightLeg" to 5, "Unknown" to 6)
        e("HumanoidRigType", "R6" to 0, "R15" to 1)
        e("GameAvatarType", "R6" to 0, "R15" to 1, "PlayerChoice" to 2)
        e("R15CollisionType", "OuterBox" to 0, "InnerBox" to 1)
        e("AnimationPriority", "Idle" to 0, "Movement" to 1, "Action" to 2, "Core" to 1000)
        e("CoreGuiType", "PlayerList" to 0, "Health" to 1, "Backpack" to 2, "Chat" to 3, "All" to 4, "EmotesMenu" to 5, "SelfView" to 6, "Captures" to 7)
        e("CameraMode", "Classic" to 0, "LockFirstPerson" to 1)
        e("TextChatMessageStatus", "Unknown" to 0, "Success" to 1, "Sending" to 2)
        e("CollisionFidelity", "Default" to 0, "Hull" to 1, "Box" to 2, "PreciseConvexDecomposition" to 3)
        e("RenderFidelity", "Automatic" to 0, "Precise" to 1, "Performance" to 2)
        e("FluidFidelity", "Automatic" to 0, "UseCollisionGeometry" to 1, "UsePreciseGeometry" to 2)
    }

    /** Which enum type a property uses, for display and Lua conversion. */
    fun typeForProp(cls: String, prop: String): String? = when (prop) {
        "Material", "BaseMaterial" -> "Material"
        "MaterialPattern" -> "MaterialPattern"
        "shape", "Shape" -> "PartType"
        "TopSurface", "BottomSurface", "LeftSurface", "RightSurface", "FrontSurface", "BackSurface" -> "SurfaceType"
        "TextXAlignment" -> "TextXAlignment"
        "TextYAlignment" -> "TextYAlignment"
        "Font" -> "Font"
        "RunContext" -> "RunContext"
        "FillDirection" -> "FillDirection"
        "HorizontalAlignment" -> "HorizontalAlignment"
        "VerticalAlignment" -> "VerticalAlignment"
        "SortOrder" -> "SortOrder"
        "ZIndexBehavior" -> "ZIndexBehavior"
        "ScaleType" -> "ScaleType"
        "AutomaticSize" -> "AutomaticSize"
        "Face" -> "NormalId"
        "CameraType" -> "CameraType"
        "MeshType" -> "MeshType"
        "formFactorRaw", "FormFactor" -> "FormFactor"
        "Technology" -> "Technology"
        "RigType" -> "HumanoidRigType"
        "GameSettingsAvatar" -> "GameAvatarType"
        "GameSettingsR15Collision" -> "R15CollisionType"
        "ApplyStrokeMode" -> "ApplyStrokeMode"
        "ScrollingDirection" -> "ScrollingDirection"
        "DepthMode" -> "HighlightDepthMode"
        "CameraMode" -> "CameraMode"
        "ScreenInsets" -> "ScreenInsets"
        "BorderMode" -> "BorderMode"
        "CollisionFidelity" -> "CollisionFidelity"
        "RenderFidelity" -> "RenderFidelity"
        "FluidFidelity" -> "FluidFidelity"
        else -> null
    }

    fun itemName(type: String?, value: Int): String? {
        val t = types[type ?: return null] ?: return null
        return t.entries.firstOrNull { it.value == value }?.key
    }
}

/** Helpers for reading part-like data regardless of how it was serialized. */
object PartData {
    fun cframe(p: Instance): CFrame = p["CFrame"] as? CFrame ?: CFrame.IDENTITY
    fun size(p: Instance): Vector3 = (p["size"] ?: p["Size"]) as? Vector3 ?: Vector3(4f, 1f, 2f)
    fun color(p: Instance): Color3 {
        (p["Color3uint8"] as? Color3)?.let { return it }
        (p["Color"] as? Color3)?.let { return it }
        (p["BrickColor"] as? BrickColorV)?.let { return it.color }
        return Color3.fromRGB(163, 162, 165)
    }
    fun transparency(p: Instance): Float = (p["Transparency"] as? Number)?.toFloat() ?: 0f
    fun material(p: Instance): Int = (p["Material"] as? EnumValue)?.value ?: 256

    /**
     * The first non-empty content string among [keys]. Newer files store MeshPart meshes and
     * textures in MeshContent/TextureContent (and SurfaceAppearance in ColorMapContent) while the
     * old MeshId/TextureID property can still be present but empty, so `a ?: b` isn't enough.
     */
    fun content(p: Instance, vararg keys: String): String? {
        for (k in keys) p[k]?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }
    fun meshContent(p: Instance): String? = content(p, "MeshId", "MeshID", "MeshContent")
    fun textureContent(p: Instance): String? = content(p, "TextureID", "TextureId", "TextureContent")

    /** 0 ball, 1 block, 2 cylinder, 3 wedge, 4 cornerwedge */
    fun shape(p: Instance): Int {
        when (p.className) {
            "WedgePart" -> return 3
            "CornerWedgePart" -> return 4
        }
        val s = (p["shape"] ?: p["Shape"]) as? EnumValue
        if (s != null) return s.value
        val mesh = p.children.firstOrNull { it.className == "SpecialMesh" }
        if (mesh != null) {
            when ((mesh["MeshType"] as? EnumValue)?.value) {
                3 -> return 0
                2 -> return 3
            }
        }
        return 1
    }

    fun setCFrame(p: Instance, cf: CFrame) = p.set("CFrame", cf)
    fun setSize(p: Instance, s: Vector3) {
        if (p.props.containsKey("Size") && !p.props.containsKey("size")) p.set("Size", s) else p.set("size", s)
    }
    fun setColor(p: Instance, c: Color3) {
        when {
            p.props.containsKey("Color3uint8") -> p.set("Color3uint8", c)
            p.props.containsKey("Color") -> p.set("Color", c)
            else -> { p.set("Color3uint8", c); p.propTypes["Color3uint8"] = PropType.COLOR3U8 }
        }
        if (p.props.containsKey("BrickColor")) p.set("BrickColor", BrickColorV(BrickColors.closest(c)))
    }
}
