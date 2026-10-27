package io.github.jackwener.dsh.service

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import io.github.jackwener.dsh.feature.quick.QuickActivity

class QuickTileService : TileService() {
    override fun onClick() {
        val i = Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
    }
}
