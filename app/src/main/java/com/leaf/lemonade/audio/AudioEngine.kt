package com.leaf.lemonade.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import com.leaf.lemonade.core.OutputLog
import com.leaf.lemonade.core.Settings
import com.leaf.lemonade.core.Studio
import com.leaf.lemonade.model.*
import com.leaf.lemonade.net.AssetFetcher
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plays Roblox Sounds. During a playtest every audible Sound (Workspace, SoundService, the
 * player's GUI) is mirrored by an Android MediaPlayer: Playing, Looped, Volume, PlaybackSpeed,
 * TimePosition, SoundGroups and 3D roll-off from the camera all apply, and Loaded/Ended fire.
 * The Toolbox and Properties panel use [preview] to listen to an audio asset.
 *
 * Audio files are downloaded through [AssetFetcher] (Roblox needs a sign-in for that).
 */
object AudioEngine {
    private val thread = HandlerThread("audio").also { it.start() }
    private val h = Handler(thread.looper)
    private const val MAX_VOICES = 28

    /** One MediaPlayer per playing Sound. Fields marked "audio" are only touched on the audio thread. */
    private class Voice(val sound: Instance, val id: Long) {
        var mp: MediaPlayer? = null           // audio
        var prepared = false                   // audio
        var lastL = -1f; var lastR = -1f; var lastSpeed = -1f; var lastLoop: Boolean? = null; var wantPlay = false
        @Volatile var position = 0.0
        @Volatile var length = 0.0
        @Volatile var ended = false
        @Volatile var loadedNotified = false
        @Volatile var failed = false
        var lastPushedPos = 0.0
        var seek = -1.0
        var seq = (sound.attributes["__playSeq"] as? Int) ?: 0
    }

    private val voices = IdentityHashMap<Instance, Voice>()
    private val missing = HashSet<Long>()
    /** Called (render thread, studio lock held) when a sound loads or ends: fire Lua events. */
    var onLoaded: ((Instance) -> Unit)? = null
    var onEnded: ((Instance) -> Unit)? = null

    private fun soundId(s: Instance): Long? = AssetFetcher.idOf(s["SoundId"])

    private fun num(i: Instance?, k: String, d: Float) = (i?.get(k) as? Number)?.toFloat() ?: d

    /** Per-frame sync with the playtest (render thread, studio lock held). */
    fun update(studio: Studio) {
        val play = studio.play
        if (play == null) { if (voices.isNotEmpty()) stopAll(); return }
        val sounds = studio.audibleSounds()
        val alive = java.util.Collections.newSetFromMap(IdentityHashMap<Instance, Boolean>())
        val cam = studio.camera
        val master = Settings.volume / 10f
        for (s in sounds) {
            if (s.parent == null) continue
            alive.add(s)
            val playing = s["Playing"] == true || s["IsPlaying"] == true
            var v = voices[s]
            val id = soundId(s)
            if (v != null && v.id != id) { release(v); voices.remove(s); v = null }
            if (!playing) {
                if (v != null && v.wantPlay) {
                    v.wantPlay = false
                    val stopped = num(s, "TimePosition", 0f) == 0f
                    val vv = v
                    h.post { try { vv.mp?.let { if (it.isPlaying) it.pause(); if (stopped && vv.prepared) it.seekTo(0) } } catch (_: Exception) {} }
                }
                continue
            }
            if (id == null) continue
            if (v == null) {
                if (voices.size >= MAX_VOICES) continue
                val bytes = AssetFetcher.isCached(id)
                if (!bytes) {
                    AssetFetcher.request(id)
                    if (id !in missing && AssetFetcher.lastError(id) != null) {
                        missing.add(id)
                        OutputLog.warn("Couldn't load sound ${s.fullName()} (rbxassetid://$id): ${AssetFetcher.lastError(id)}")
                    }
                    continue
                }
                v = Voice(s, id)
                voices[s] = v
                val start = num(s, "TimePosition", 0f).toDouble()
                v.seek = start
                create(v)
            }
            if (v.failed) continue
            // Volume: Sound.Volume x SoundGroup x master x 3D roll-off, panned left/right.
            var vol = num(s, "Volume", 0.5f).coerceIn(0f, 10f)
            (s["SoundGroup"] as? Instance)?.let { g -> vol *= num(g, "Volume", 1f).coerceIn(0f, 10f) }
            vol *= master
            var left = 1f; var right = 1f
            val emitter: Vector3? = when {
                s.parent?.isA("BasePart") == true -> PartData.cframe(s.parent!!).position
                s.parent?.className == "Attachment" -> s.parent?.parent?.takeIf { it.isA("BasePart") }?.let { PartData.cframe(it) * ((s.parent!!["CFrame"] as? CFrame)?.position ?: Vector3.ZERO) }
                else -> null
            }
            if (emitter != null) {
                val d = (emitter - cam.pos).magnitude
                val mn = num(s, "RollOffMinDistance", num(s, "xmlRead_MinDistance_3", 10f)).coerceAtLeast(0.1f)
                val mx = num(s, "RollOffMaxDistance", num(s, "xmlRead_MaxDistance_3", 10000f)).coerceAtLeast(mn)
                val mode = (s["RollOffMode"] as? EnumValue)?.value ?: 0
                val gain = when {
                    d <= mn -> 1f
                    d >= mx -> 0f
                    mode == 1 || mode == 3 -> 1f - (d - mn) / (mx - mn) // Linear / LinearSquare
                    else -> mn / d // Inverse
                }
                vol *= gain
                val dir = (emitter - cam.pos)
                if (d > 0.5f) {
                    val pan = (dir.unit.dot(cam.right)).coerceIn(-1f, 1f) * 0.7f
                    left = 1f - maxOf(0f, pan); right = 1f + minOf(0f, pan)
                }
            }
            val l = (vol * left).coerceIn(0f, 1f); val r = (vol * right).coerceIn(0f, 1f)
            val speed = num(s, "PlaybackSpeed", num(s, "Pitch", 1f)).coerceIn(0.05f, 8f)
            val loop = s["Looped"] == true
            val vv = v
            // Sound:Play() on a sound that's already playing starts it over.
            val seq = (s.attributes["__playSeq"] as? Int) ?: 0
            if (seq != vv.seq) {
                vv.seq = seq
                if (vv.wantPlay) h.post { try { if (vv.prepared) vv.mp?.seekTo(0) } catch (_: Exception) {} }
            }
            if (!vv.wantPlay ||kotlin.math.abs(l - vv.lastL) > 0.01f || kotlin.math.abs(r - vv.lastR) > 0.01f || speed != vv.lastSpeed || loop != vv.lastLoop) {
                val restart = !vv.wantPlay
                vv.wantPlay = true; vv.lastL = l; vv.lastR = r; vv.lastSpeed = speed; vv.lastLoop = loop
                h.post { apply(vv, l, r, speed, loop, restart) }
            }
            // Mirror state back into the Sound.
            if (vv.length > 0 && !vv.loadedNotified) {
                vv.loadedNotified = true
                s.set("TimeLength", vv.length, false)
                s.set("IsLoaded", true, false)
                onLoaded?.invoke(s)
            }
            if (kotlin.math.abs(vv.position - vv.lastPushedPos) > 0.2) { vv.lastPushedPos = vv.position; s.set("TimePosition", vv.position, false) }
            if (vv.ended) {
                vv.ended = false; vv.wantPlay = false
                s.set("TimePosition", 0.0, false)
                s.set("Playing", false)
                onEnded?.invoke(s)
            }
        }
        if (voices.size > alive.size || voices.keys.any { it !in alive }) {
            val it = voices.entries.iterator()
            while (it.hasNext()) { val e = it.next(); if (e.key !in alive) { release(e.value); it.remove() } }
        }
    }

    private fun create(v: Voice) {
        h.post {
            try {
                val f = File(AssetFetcher.cacheFile(v.id) ?: return@post)
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(f.path)
                mp.setOnPreparedListener {
                    v.prepared = true
                    v.length = it.duration / 1000.0
                    if (v.seek > 0) try { it.seekTo((v.seek * 1000).toInt()) } catch (_: Exception) {}
                    if (v.wantPlay) {
                        it.setVolume(v.lastL.coerceAtLeast(0f), v.lastR.coerceAtLeast(0f))
                        it.isLooping = v.lastLoop == true
                        setSpeed(it, v.lastSpeed)
                        it.start()
                    }
                    tick(v)
                }
                mp.setOnCompletionListener { if (!it.isLooping) { v.ended = true; v.position = 0.0 } }
                mp.setOnErrorListener { _, what, extra -> v.failed = true; OutputLog.warn("Sound rbxassetid://${v.id} can't be played on this device ($what/$extra)"); true }
                v.mp = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                v.failed = true
                OutputLog.warn("Sound rbxassetid://${v.id}: ${e.message}")
            }
        }
    }

    private fun setSpeed(mp: MediaPlayer, speed: Float) {
        if (speed <= 0f || kotlin.math.abs(speed - 1f) < 0.001f) return
        try { mp.playbackParams = mp.playbackParams.setSpeed(speed).setPitch(speed) } catch (_: Exception) {}
    }

    private fun apply(v: Voice, l: Float, r: Float, speed: Float, loop: Boolean, restart: Boolean) {
        val mp = v.mp ?: return
        if (!v.prepared) return
        try {
            mp.setVolume(l, r)
            mp.isLooping = loop
            setSpeed(mp, speed)
            if (restart || !mp.isPlaying) { mp.start(); tick(v) }
        } catch (_: Exception) {}
    }

    /** Polls the playback position while a voice plays. */
    private fun tick(v: Voice) {
        h.postDelayed(object : Runnable {
            override fun run() {
                val mp = v.mp ?: return
                try { if (v.prepared) v.position = mp.currentPosition / 1000.0 } catch (_: Exception) { return }
                if (v.wantPlay) h.postDelayed(this, 200)
            }
        }, 200)
    }

    private fun release(v: Voice) {
        v.wantPlay = false
        h.post { try { v.mp?.release() } catch (_: Exception) {}; v.mp = null }
    }

    fun stopAll() {
        for (v in voices.values) release(v)
        voices.clear()
        missing.clear()
    }

    // ------------------------------------------------------------------ previews (Toolbox / Properties)

    /** STOPPED with the preview still current means it played to the end (it can be replayed or seeked). */
    enum class PreviewState { LOADING, PLAYING, PAUSED, STOPPED, ERROR }
    class Preview(val id: Long) {
        @Volatile var state = PreviewState.LOADING
        @Volatile var position = 0f
        @Volatile var length = 0f
        @Volatile var error: String? = null
    }

    @Volatile var preview: Preview? = null
        private set
    private var previewPlayer: MediaPlayer? = null
    val previewListeners = CopyOnWriteArrayList<() -> Unit>()

    private fun notifyPreview() = previewListeners.forEach { it() }

    /**
     * Starts the preview of an audio asset. Tapping the same one again pauses it, then resumes
     * (from the start once it has finished); loading ones are cancelled.
     */
    fun togglePreview(id: Long, volume: Float = 0.5f) {
        val cur = preview
        if (cur != null && cur.id == id) when (cur.state) {
            PreviewState.PLAYING -> { pausePreview(); return }
            PreviewState.PAUSED -> { resumePreview(); return }
            PreviewState.STOPPED -> if (cur.length > 0f) { resumePreview(); return }
            PreviewState.LOADING -> { stopPreview(); return }
            PreviewState.ERROR -> {}
        }
        stopPreview()
        val p = Preview(id)
        preview = p
        notifyPreview()
        Thread {
            try {
                AssetFetcher.load(id)
                h.post {
                    if (preview !== p) return@post
                    try {
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        mp.setDataSource(AssetFetcher.cacheFile(id)!!)
                        mp.setOnPreparedListener {
                            if (preview !== p) { it.release(); return@setOnPreparedListener }
                            p.length = it.duration / 1000f
                            val vol = (volume * 2f).coerceIn(0.05f, 1f)
                            it.setVolume(vol, vol)
                            it.start()
                            p.state = PreviewState.PLAYING
                            notifyPreview()
                            tick(p, it)
                        }
                        mp.setOnCompletionListener { p.state = PreviewState.STOPPED; p.position = 0f; notifyPreview() }
                        mp.setOnErrorListener { _, _, _ -> p.state = PreviewState.ERROR; p.error = "This audio can't be played on this device"; notifyPreview(); true }
                        previewPlayer = mp
                        mp.prepareAsync()
                    } catch (e: Exception) { p.state = PreviewState.ERROR; p.error = e.message; notifyPreview() }
                }
            } catch (e: Exception) {
                p.state = PreviewState.ERROR
                p.error = e.message ?: "Couldn't download the audio"
                notifyPreview()
            }
        }.start()
    }

    /** Keeps [Preview.position] current while it plays. */
    private fun tick(p: Preview, mp: MediaPlayer) {
        h.postDelayed(object : Runnable {
            override fun run() {
                if (preview !== p || previewPlayer !== mp || p.state != PreviewState.PLAYING) return
                try { p.position = mp.currentPosition / 1000f } catch (_: Exception) { return }
                notifyPreview()
                h.postDelayed(this, 150)
            }
        }, 150)
    }

    fun pausePreview() {
        val p = preview ?: return
        h.post {
            val mp = previewPlayer ?: return@post
            if (preview !== p || p.state != PreviewState.PLAYING) return@post
            try { mp.pause(); p.position = mp.currentPosition / 1000f } catch (_: Exception) {}
            p.state = PreviewState.PAUSED
            notifyPreview()
        }
    }

    fun resumePreview() {
        val p = preview ?: return
        h.post {
            val mp = previewPlayer ?: return@post
            if (preview !== p) return@post
            try {
                if (p.state == PreviewState.STOPPED) { mp.seekTo(0); p.position = 0f }
                mp.start()
            } catch (_: Exception) { return@post }
            p.state = PreviewState.PLAYING
            notifyPreview()
            tick(p, mp)
        }
    }

    /** Jumps the preview of [id] to [seconds] (the Toolbox's audio seek bar); it keeps playing or stays paused. */
    fun seekPreview(id: Long, seconds: Float) {
        val p = preview?.takeIf { it.id == id } ?: return
        h.post {
            val mp = previewPlayer ?: return@post
            if (preview !== p || p.length <= 0f) return@post
            val t = seconds.coerceIn(0f, p.length)
            try { mp.seekTo((t * 1000f).toInt()) } catch (_: Exception) { return@post }
            p.position = t
            // Seeking a finished preview leaves it ready to play from there.
            if (p.state == PreviewState.STOPPED) p.state = PreviewState.PAUSED
            notifyPreview()
        }
    }

    fun stopPreview() {
        val p = preview
        preview = null
        p?.state = PreviewState.STOPPED
        h.post { try { previewPlayer?.release() } catch (_: Exception) {}; previewPlayer = null }
        notifyPreview()
    }
}
