package tv.aura.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/** Ecranul alarmei: ora mare, sunet repetat, un buton de oprire. Se opreste singur dupa 5 minute. */
class AlarmActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private val beep = object : Runnable {
        override fun run() {
            try {
                tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 900)
            } catch (e: Exception) {
            }
            handler.postDelayed(this, 1_600L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val d = resources.displayMetrics.density
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(205, 95, 30), Color.rgb(120, 30, 80), Color.rgb(20, 20, 50))
        )

        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 110f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        clock.gravity = Gravity.CENTER
        box.addView(clock)

        val hello = TextView(this)
        hello.text = "Bună dimineața"
        hello.textSize = 24f
        hello.setTextColor(Color.argb(220, 255, 255, 255))
        hello.gravity = Gravity.CENTER
        hello.setPadding(0, 0, 0, (28 * d).toInt())
        box.addView(hello)

        val stop = Button(this)
        stop.text = "Oprește alarma"
        stop.textSize = 18f
        stop.setOnClickListener { finish() }
        box.addView(
            stop,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        setContentView(box)
        stop.requestFocus()

        tone = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            null
        }
        handler.post(beep)
        handler.postDelayed({ finish() }, 5 * 60_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            tone?.release()
        } catch (e: Exception) {
        }
        tone = null
        super.onDestroy()
    }
}
