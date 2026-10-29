package fr.dd.jarvis.societe

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import java.io.File

/** Données de la société, en JSON dans le stockage privé de l'application, observables par Compose. */
class SocieteRepo(ctx: Context) {
    private val dir = File(ctx.filesDir, "societe").apply { mkdirs() }
    private val file = File(dir, "data.json")

    var data by mutableStateOf(load())
        private set

    private fun load(): SocieteData = try {
        if (file.exists()) jsonSociete.decodeFromString(SocieteData.serializer(), file.readText()) else SocieteData()
    } catch (e: Exception) {
        // fichier illisible : on le garde de côté plutôt que de l'écraser
        runCatching { file.copyTo(File(dir, "data.corrompu.json"), overwrite = true) }
        SocieteData()
    }

    @Synchronized
    fun update(f: (SocieteData) -> SocieteData) {
        data = f(data)
        val tmp = File(dir, "data.json.tmp")
        tmp.writeText(jsonSociete.encodeToString(SocieteData.serializer(), data))
        if (file.exists()) runCatching { file.copyTo(File(dir, "data.json.bak"), overwrite = true) }
        tmp.renameTo(file)
    }
}
