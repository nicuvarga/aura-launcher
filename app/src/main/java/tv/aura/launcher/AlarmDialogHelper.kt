package tv.aura.launcher

import android.app.AlertDialog
import android.content.Context

object AlarmDialogHelper {
    fun show(ctx: Context, title: String, items: Array<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items) { _, which -> onPick(which) }
            .show()
    }
}
