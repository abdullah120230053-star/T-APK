package com.dama.tahany.service

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class ClipboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val intent = Intent(this, TahanyService::class.java)
            .setAction(TahanyService.ACTION_SEND_CLIPBOARD)
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (_: Exception) {
            return
        }
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            updateTile()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                updateTile()
            }
        }, 1_500L)
    }
}
