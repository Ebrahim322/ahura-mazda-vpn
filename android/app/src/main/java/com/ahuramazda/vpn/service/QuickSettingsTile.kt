package com.ahuramazda.vpn.service

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ahuramazda.vpn.R

/** Connect / disconnect straight from the quick settings shade (API 24+). */
class QuickSettingsTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (AhuraVpnService.connected) {
            AhuraVpnService.stop(this)
        } else {
            AhuraVpnService.start(this)
        }
        tile?.let {
            it.state = if (AhuraVpnService.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.updateTile()
        }
    }

    private fun refresh() {
        val tile = tile ?: return
        tile.state = if (AhuraVpnService.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_notification)
        tile.label = getString(R.string.app_name)
        tile.updateTile()
    }
}
