package com.leaf.lemonade.ui

import android.content.res.AssetManager
import android.graphics.Typeface
import android.os.Build
import com.leaf.lemonade.model.EnumValue
import com.leaf.lemonade.model.FontValue
import com.leaf.lemonade.model.Instance

/**
 * Maps Roblox fonts (the Font enum and FontFace) to bundled open-licence typefaces.
 * Proprietary Roblox fonts use close stand-ins: Gotham -> Montserrat, Builder Sans -> system sans.
 */
object Fonts {
    private var assets: AssetManager? = null
    private val cache = HashMap<String, Typeface>()

    fun init(a: AssetManager) { assets = a }

    class Face(val typeface: Typeface, val fakeBold: Boolean, val italic: Boolean)

    private fun asset(name: String): Typeface? = synchronized(cache) {
        cache[name]?.let { return it }
        val tf = try { assets?.let { Typeface.createFromAsset(it, "fonts/$name.ttf") } } catch (_: Exception) { null } ?: return null
        cache[name] = tf
        tf
    }

    private fun system(weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.SANS_SERIF, weight.coerceIn(100, 900), italic)
        else Typeface.create(Typeface.SANS_SERIF, (if (weight >= 600) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))

    /** Weighted file choice: picks the closest available weight; fake-bolds when only lighter ones exist. */
    private fun weighted(weight: Int, italic: Boolean, files: List<Pair<Int, String>>): Face {
        val best = files.minByOrNull { kotlin.math.abs(it.first - weight) }!!
        val tf = asset(best.second) ?: return Face(system(weight, italic), false, italic)
        return Face(tf, weight >= 600 && best.first < 600, italic)
    }

    fun forFamily(familyRaw: String, weight: Int, italic: Boolean): Face {
        val fam = familyRaw.substringAfterLast('/').substringBefore('.').lowercase()
        fun one(file: String) = asset(file)?.let { Face(it, weight >= 600, italic) } ?: Face(system(weight, italic), false, italic)
        return when (fam) {
            "gothamssm", "gotham", "montserrat" -> weighted(weight, italic, listOf(400 to "Montserrat-Regular", 500 to "Montserrat-Medium", 700 to "Montserrat-Bold", 900 to "Montserrat-Black"))
            "sourcesanspro", "sourcesans" -> if (italic) one("SourceSans-Italic") else weighted(weight, false, listOf(300 to "SourceSans-Light", 400 to "SourceSans-Regular", 600 to "SourceSans-Semibold", 700 to "SourceSans-Bold"))
            "buildersans", "roboto" -> Face(system(weight, italic), false, italic)
            "arial", "arimo", "legacyarial" -> weighted(if (fam == "legacyarial") 700 else weight, italic, listOf(400 to "Arimo-Regular", 700 to "Arimo-Bold"))
            "fredokaone" -> Face(asset("FredokaOne") ?: system(700, italic), false, italic)
            "luckiestguy" -> Face(asset("LuckiestGuy") ?: system(900, italic), false, italic)
            "bangers" -> one("Bangers")
            "pressstart2p" -> one("PressStart2P")
            "permanentmarker" -> one("PermanentMarker")
            "oswald" -> one("Oswald")
            "nunito" -> weighted(weight, italic, listOf(400 to "Nunito", 700 to "Nunito-Bold"))
            "ubuntu" -> weighted(weight, italic, listOf(400 to "Ubuntu", 700 to "Ubuntu-Bold"))
            "merriweather", "accanthisadfstd", "balthazar" -> one("Merriweather")
            "michroma", "zekton" -> one("Michroma")
            "creepster" -> one("Creepster")
            "indieflower" -> one("IndieFlower")
            "amaticsc" -> one("AmaticSC")
            "denkone" -> one("DenkOne")
            "patrickhand" -> one("PatrickHand")
            "specialelite" -> one("SpecialElite")
            "titilliumweb" -> weighted(weight, italic, listOf(400 to "TitilliumWeb", 700 to "TitilliumWeb-Bold"))
            "kalam" -> one("Kalam")
            "jura" -> one("Jura")
            "sarpanch" -> one("Sarpanch")
            "grenzegotisch" -> one("GrenzeGotisch")
            "fondamento", "guru" -> one("Fondamento")
            "josefinsans" -> one("JosefinSans")
            "robotomono" -> one("RobotoMono")
            "inconsolata" -> one("Inconsolata")
            "comicneueangular", "comicneue" -> one("ComicNeue")
            "robotocondensed", "highwaygothic" -> weighted(weight, italic, listOf(400 to "RobotoCondensed", 700 to "RobotoCondensed-Bold"))
            "notosans" -> Face(system(weight, italic), false, italic)
            else -> Face(system(weight, italic), false, italic)
        }
    }

    /** Font enum value -> (family, weight, italic). */
    private fun fromEnum(v: Int): Triple<String, Int, Boolean> = when (v) {
        0 -> Triple("LegacyArial", 700, false); 1 -> Triple("Arial", 400, false); 2 -> Triple("Arial", 700, false)
        3 -> Triple("SourceSansPro", 400, false); 4 -> Triple("SourceSansPro", 700, false); 5 -> Triple("SourceSansPro", 300, false)
        6 -> Triple("SourceSansPro", 400, true); 7 -> Triple("AccanthisADFStd", 400, false); 8 -> Triple("Merriweather", 400, false)
        9 -> Triple("ComicNeueAngular", 700, false); 10 -> Triple("Inconsolata", 400, false); 11 -> Triple("HighwayGothic", 400, false)
        12 -> Triple("Zekton", 400, false); 13 -> Triple("PressStart2P", 400, false); 14 -> Triple("Guru", 400, false)
        15 -> Triple("Balthazar", 400, false); 16 -> Triple("SourceSansPro", 600, false); 17 -> Triple("GothamSSm", 400, false)
        18 -> Triple("GothamSSm", 500, false); 19 -> Triple("GothamSSm", 700, false); 20 -> Triple("GothamSSm", 900, false)
        21 -> Triple("AmaticSC", 400, false); 22 -> Triple("Bangers", 400, false); 23 -> Triple("Creepster", 400, false)
        24 -> Triple("DenkOne", 400, false); 25 -> Triple("Fondamento", 400, false); 26 -> Triple("FredokaOne", 400, false)
        27 -> Triple("GrenzeGotisch", 400, false); 28 -> Triple("IndieFlower", 400, false); 29 -> Triple("JosefinSans", 400, false)
        30 -> Triple("Jura", 400, false); 31 -> Triple("Kalam", 400, false); 32 -> Triple("LuckiestGuy", 400, false)
        33 -> Triple("Merriweather", 400, false); 34 -> Triple("Michroma", 400, false); 35 -> Triple("Nunito", 400, false)
        36 -> Triple("Oswald", 400, false); 37 -> Triple("PatrickHand", 400, false); 38 -> Triple("PermanentMarker", 400, false)
        39 -> Triple("Roboto", 400, false); 40 -> Triple("RobotoCondensed", 400, false); 41 -> Triple("RobotoMono", 400, false)
        42 -> Triple("Sarpanch", 400, false); 43 -> Triple("SpecialElite", 400, false); 44 -> Triple("TitilliumWeb", 400, false)
        45 -> Triple("Ubuntu", 400, false); 46 -> Triple("BuilderSans", 400, false); 47 -> Triple("BuilderSans", 500, false)
        48 -> Triple("BuilderSans", 700, false); 49 -> Triple("BuilderSans", 800, false); 50 -> Triple("Arimo", 400, false)
        51 -> Triple("Arimo", 700, false)
        else -> Triple("SourceSansPro", 400, false)
    }

    private val faceCache = HashMap<Any, Face>()

    /** Typeface for a text GUI object, preferring FontFace (modern files) over the legacy Font enum. */
    fun of(c: Instance): Face {
        val ff = c["FontFace"] as? FontValue
        val key: Any = ff ?: ((c["Font"] as? EnumValue)?.value ?: 3)
        synchronized(faceCache) {
            faceCache[key]?.let { return it }
            val face = if (ff != null && ff.family.isNotEmpty()) forFamily(ff.family, ff.weight, ff.style == 1)
            else { val (f, w, i) = fromEnum(key as Int); forFamily(f, w, i) }
            faceCache[key] = face
            return face
        }
    }
}
