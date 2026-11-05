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
        val nouvelle = f(data)
        val tmp = File(dir, "data.json.tmp")
        val contenu = jsonSociete.encodeToString(SocieteData.serializer(), nouvelle)
        tmp.writeText(contenu)
        if (!tmp.exists() || tmp.length() == 0L) throw java.io.IOException("Écriture de la base Société impossible.")
        if (file.exists()) runCatching { file.copyTo(File(dir, "data.json.bak"), overwrite = true) }
        // Ne publie le nouvel état observable qu'après avoir garanti sa persistance sur disque.
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        val relu = jsonSociete.decodeFromString(SocieteData.serializer(), file.readText())
        if (jsonSociete.encodeToString(SocieteData.serializer(), relu) != contenu)
            throw java.io.IOException("La base Société n'a pas été enregistrée intégralement.")
        data = relu
    }
}
