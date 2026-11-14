package tv.aura.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == AlarmScheduler.ACTION_ALARM) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "aura:alarm"
                )
                wl.acquire(20_000L)
            } catch (e: Exception) {
            }
            try {
                ctx.startActivity(
                    Intent(ctx, AlarmActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            } catch (e: Exception) {
            }
        }
        // dupa declansare, repornire, schimbare de ora sau actualizare: programam urmatoarea sunare
        AlarmScheduler.schedule(ctx)
    }
}
