package tv.aura.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

/** Alarma zilnica: programeaza urmatoarea sunare si o reprogrameaza dupa fiecare declansare/repornire. */
object AlarmScheduler {
    const val ACTION_ALARM = "tv.aura.launcher.ALARM"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aura", Context.MODE_PRIVATE)

    fun isOn(ctx: Context): Boolean = prefs(ctx).getBoolean("alarm_on", true)

    /** Ora alarmei, in minute de la miezul noptii (implicit 07:00). */
    fun minutes(ctx: Context): Int = prefs(ctx).getInt("alarm_min", 7 * 60)

    fun set(ctx: Context, on: Boolean, minutes: Int) {
        prefs(ctx).edit().putBoolean("alarm_on", on).putInt("alarm_min", minutes).apply()
        schedule(ctx)
    }

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(
            ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx))
        if (!isOn(ctx)) return
        val m = minutes(ctx)
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, m / 60)
        c.set(Calendar.MINUTE, m % 60)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis() + 5_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        setAt(ctx, c.timeInMillis)
    }

    /** Proba: suna o singura data peste delayMs; dupa aceea revine la programul zilnic. */
    fun scheduleTest(ctx: Context, delayMs: Long) {
        setAt(ctx, System.currentTimeMillis() + delayMs)
    }

    private fun setAt(ctx: Context, time: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val show = PendingIntent.getActivity(
            ctx, 2, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(time, show), pending(ctx))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending(ctx))
        }
    }
}
