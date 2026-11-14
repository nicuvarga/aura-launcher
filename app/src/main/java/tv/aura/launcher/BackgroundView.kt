package tv.aura.launcher

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File
import kotlin.concurrent.thread

/**
 * Fundalul launcher-ului. Straturi, de jos in sus:
 * tema animata (AuroraView) -> video -> doua imagini (pentru crossfade) -> umbra pentru lizibilitate.
 * Daca in folderul de fundaluri exista poze/video, le reda pe rand; altfel ramane tema animata.
 */
class BackgroundView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    companion object {
        const val IMAGE_MS = 40_000L
        const val FADE_MS = 1_800L
        const val SKIP_START_MS = 10_000
        const val SKIP_END_MS = 10_000
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
        val VIDEO_EXT = setOf("mp4", "mkv", "mov", "webm", "m4v")
    }

    val aurora = AuroraView(context)
    private val video = TextureView(context)
    private val imgA = ImageView(context)
    private val imgB = ImageView(context)
    private val scrim = View(context)

    private var front: ImageView = imgA
    private var back: ImageView = imgB
    private var media: List<File> = emptyList()
    private var index = -1
    private var failures = 0
    private var active = false
    private var showing = false
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pendingVideo: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val nextRunnable = Runnable { showNext() }
    private val videoEndRunnable = Runnable { showNext() }

    init {
        val lp = { FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
        addView(aurora, lp())
        video.alpha = 0f
        video.surfaceTextureListener = this
        addView(video, lp())
        for (v in listOf(imgA, imgB)) {
            v.scaleType = ImageView.ScaleType.CENTER_CROP
            v.alpha = 0f
            addView(v, lp())
        }
        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(30, 0, 0, 0), Color.argb(175, 0, 0, 0))
        )
        scrim.alpha = 0f
        addView(scrim, lp())
    }

    fun hasMedia(): Boolean = media.isNotEmpty()

    /** mode: "auto" = poze/video din folder (daca exista), altfel numele unei teme animate. */
    fun reload(dir: File?, mode: String) {
        val files: List<File> = if (mode == "auto" && dir != null) {
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && (isImage(it) || isVideo(it)) }
                .sortedBy { it.name.lowercase() }
        } else {
            emptyList()
        }
        aurora.setPalette(if (mode == "auto") "aurora" else mode)
        val same = files.map { it.absolutePath } == media.map { it.absolutePath }
        if (same) return
        media = files
        index = -1
        failures = 0
        clearMedia()
        if (active) start()
    }

    fun start() {
        active = true
        if (media.isEmpty()) {
            aurora.running = true
        } else if (!showing) {
            showNext()
        }
    }

    fun stop() {
        active = false
        aurora.running = false
        handler.removeCallbacks(nextRunnable)
        releasePlayer()
        showing = false
    }

    private fun clearMedia() {
        handler.removeCallbacks(nextRunnable)
        releasePlayer()
        showing = false
        for (v in listOf<View>(imgA, imgB, video, scrim)) {
            v.animate().cancel()
            v.alpha = 0f
        }
    }

    private fun isImage(f: File) = f.extension.lowercase() in IMAGE_EXT
    private fun isVideo(f: File) = f.extension.lowercase() in VIDEO_EXT

    private fun scheduleNext(ms: Long) {
        handler.removeCallbacks(nextRunnable)
        handler.postDelayed(nextRunnable, ms)
    }

    private fun failed() {
        failures++
        if (failures >= media.size) {
            // nimic nu a putut fi redat: revenim la tema animata
            clearMedia()
            media = emptyList()
            if (active) aurora.running = true
        } else {
            scheduleNext(400)
        }
    }

    private fun showNext() {
        handler.removeCallbacks(nextRunnable)
        handler.removeCallbacks(videoEndRunnable)
        if (!active || media.isEmpty()) return
        index = (index + 1) % media.size
        val f = media[index]
        showing = true
        if (isVideo(f)) playVideo(f) else showImage(f)
    }

    // ---------- imagini ----------

    private fun decode(f: File): Bitmap? {
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, o)
            var sample = 1
            while (o.outWidth / sample > 2560 || o.outHeight / sample > 1440) sample *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = sample
            BitmapFactory.decodeFile(f.absolutePath, o2)
        } catch (t: Throwable) {
            null
        }
    }

    private fun showImage(f: File) {
        val expected = index
        thread {
            val bmp = decode(f)
            handler.post {
                if (!active || expected != index) return@post
                if (bmp == null) {
                    failed()
                    return@post
                }
                failures = 0
                val v = back
                (v.tag as? AnimatorSet)?.cancel()
                v.setImageBitmap(bmp)
                v.scaleX = 1f
                v.scaleY = 1f
                v.translationX = 0f
                v.animate().alpha(1f).setDuration(FADE_MS).start()
                val old = front
                old.animate().alpha(0f).setDuration(FADE_MS)
                    .withEndAction { (old.tag as? AnimatorSet)?.cancel() }.start()
                video.animate().alpha(0f).setDuration(FADE_MS).withEndAction { releasePlayer() }.start()
                scrim.animate().alpha(1f).setDuration(FADE_MS).start()
                aurora.running = false

                // miscare lenta (zoom + deplasare), dus-intors
                val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1.0f, 1.14f)
                val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1.0f, 1.14f)
                val dir = if (index % 2 == 0) 1f else -1f
                val tx = ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dir * width * 0.03f)
                for (a in listOf(sx, sy, tx)) {
                    a.repeatMode = ValueAnimator.REVERSE
                    a.repeatCount = ValueAnimator.INFINITE
                }
                val set = AnimatorSet()
                set.playTogether(sx, sy, tx)
                set.duration = IMAGE_MS + 5_000L
                set.interpolator = LinearInterpolator()
                set.start()
                v.tag = set

                back = front
                front = v
                if (media.size > 1) scheduleNext(IMAGE_MS)
            }
        }
    }

    // ---------- video ----------

    private fun releasePlayer() {
        handler.removeCallbacks(videoEndRunnable)
        val p = player ?: return
        player = null
        try {
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.reset()
            p.release()
        } catch (e: Exception) {
        }
    }

    private fun playVideo(f: File) {
        val s = surface
        if (s == null) {
            pendingVideo = f
            return
        }
        if (player != null) {
            // stingem intai clipul vechi, apoi pornim clipul nou
            video.animate().alpha(0f).setDuration(600L).withEndAction { startVideo(f, s) }.start()
        } else {
            video.alpha = 0f
            startVideo(f, s)
        }
    }

    private fun startVideo(f: File, s: Surface) {
        releasePlayer()
        if (!active) return
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setDataSource(f.absolutePath)
            mp.setSurface(s)
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> applyCrop(w, h) }
            mp.setOnPreparedListener { p ->
                // sarim peste inceputul clipului (titluri, decolare), daca e destul de lung
                // si oprim clipul cu 10 secunde inainte de final
                val d = p.duration
                if (d > SKIP_START_MS + SKIP_END_MS + 15_000) {
                    p.setOnSeekCompleteListener { sp ->
                        sp.setOnSeekCompleteListener(null)
                        sp.start()
                    }
                    p.seekTo(SKIP_START_MS.toLong(), MediaPlayer.SEEK_CLOSEST)
                    handler.removeCallbacks(videoEndRunnable)
                    handler.postDelayed(videoEndRunnable, (d - SKIP_START_MS - SKIP_END_MS).toLong())
                } else {
                    p.start()
                }
            }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    failures = 0
                    aurora.running = false
                    video.animate().alpha(1f).setDuration(1_500L).start()
                    imgA.animate().alpha(0f).setDuration(1_500L).start()
                    imgB.animate().alpha(0f).setDuration(1_500L).start()
                    scrim.animate().alpha(1f).setDuration(1_500L).start()
                }
                false
            }
            mp.setOnCompletionListener { showNext() }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                failed()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            failed()
        }
    }

    /** Umple ecranul pastrand proportiile (center-crop). */
    private fun applyCrop(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val vr = vw.toFloat() / vh
        val wr = width.toFloat() / height
        val m = Matrix()
        if (vr > wr) {
            m.setScale(vr / wr, 1f, width / 2f, height / 2f)
        } else {
            m.setScale(1f, wr / vr, width / 2f, height / 2f)
        }
        video.setTransform(m)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        val f = pendingVideo
        pendingVideo = null
        if (f != null && active) playVideo(f)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {
    }
}
