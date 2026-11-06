package fr.dd.jarvis.societe

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import java.io.File

/** Données Société persistées dans le stockage privé et observables par Compose. */
class SocieteRepo(ctx: Context) {
    private val dir = File(ctx.filesDir, "societe").apply { mkdirs() }
    private val file = File(dir, "data.json")
    private val backup = File(dir, "data.json.bak")

    var data by mutableStateOf(load())
        private set

    private fun decode(source: File): SocieteData =
        jsonSociete.decodeFromString(SocieteData.serializer(), source.readText())

    /**
     * Ne jamais transformer silencieusement une base Société illisible en base vide :
     * cela faisait disparaître tous les clients de l'onglet alors que leurs fiches Markdown
     * existaient encore dans Obsidian. On conserve le fichier défectueux et restaure le dernier
     * instantané JSON valide avant de laisser l'application continuer.
     */
    private fun load(): SocieteData {
        if (file.exists()) {
            runCatching { return decode(file) }
            // Le fichier principal est invalide : le mettre de côté sans toucher à la sauvegarde.
            val damaged = File(dir, "data.corrompu.${System.currentTimeMillis()}.json")
            runCatching { file.copyTo(damaged, overwrite = false) }
        }

        if (backup.exists()) {
            val restored = runCatching { decode(backup) }.getOrNull()
            if (restored != null) {
                // Restaurer immédiatement le fichier principal : les prochaines écritures repartiront
                // d'une base saine et ne recopieront pas le JSON corrompu dans le backup.
                runCatching {
                    val tmp = File(dir, "data.json.restore.tmp")
                    backup.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                    if (decode(file) != restored) throw java.io.IOException("Vérification de restauration impossible.")
                }
                return restored
            }
        }

        // Aucun fichier exploitable : conserver les fichiers pour diagnostic et démarrer vide.
        return SocieteData()
    }

    @Synchronized
    fun update(f: (SocieteData) -> SocieteData) {
        val nouvelle = f(data)
        val tmp = File(dir, "data.json.tmp")
        val contenu = jsonSociete.encodeToString(SocieteData.serializer(), nouvelle)
        tmp.writeText(contenu)
        if (!tmp.exists() || tmp.length() == 0L) throw java.io.IOException("Écriture de la base Société impossible.")
        // La sauvegarde ne remplace jamais un instantané valide par un fichier principal illisible.
        if (file.exists()) {
            runCatching { decode(file) }.onSuccess {
                file.copyTo(backup, overwrite = true)
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        val relu = decode(file)
        if (jsonSociete.encodeToString(SocieteData.serializer(), relu) != contenu)
            throw java.io.IOException("La base Société n'a pas été enregistrée intégralement.")
        data = relu
    }
}
