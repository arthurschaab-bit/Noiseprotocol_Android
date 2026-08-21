package com.example.lrmprotokoll.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.lrmprotokoll.audio.AudioRecordingService
import com.example.lrmprotokoll.audio.EXTRA_START_AUDIO_MONITORING
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * F14: Quick Settings Tile zum schnellen Starten/Stoppen der Lärmüberwachung.
 */
class NoiseMonitoringTileService : TileService() {

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            AudioRecordingService.laeuft.collectLatest { running ->
                updateTileState(running)
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val isRunning = AudioRecordingService.laeuft.value
        if (isRunning) {
            val intent = Intent(this, AudioRecordingService::class.java).apply {
                action = "STOP_SERVICE"
            }
            startService(intent)
        } else {
            val intent = Intent(this, AudioRecordingService::class.java).apply {
                putExtra(EXTRA_START_AUDIO_MONITORING, true)
            }
            startForegroundService(intent)
        }
    }

    private fun updateTileState(isRunning: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Lärmprotokoll"
        tile.subtitle = if (isRunning) "Überwachung aktiv" else "Inaktiv"
        tile.updateTile()
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }
}
