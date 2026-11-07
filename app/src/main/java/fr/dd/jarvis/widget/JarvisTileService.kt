package fr.dd.jarvis.widget

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import fr.dd.jarvis.MainActivity

/** Tuile des réglages rapides « Parler à JARVIS » : ouvre l'appli directement à l'écoute. */
class JarvisTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; label = getString(fr.dd.jarvis.R.string.tuile_parler); updateTile() }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val lancer = {
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(DragonWidget.ecouter(this))
            else @Suppress("DEPRECATION") startActivityAndCollapse(
                Intent(this, MainActivity::class.java).putExtra("listen", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        if (isLocked) unlockAndRun { lancer() } else lancer()
    }
}
