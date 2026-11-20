package fr.dd.jarvis.data

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Écritures de fichiers HORS du fil de l'écran (conversation en cours, archives des conversations).
 *
 * Avant : chaque message ajouté réécrivait chat.json ET l'archive complète de la conversation sur le fil principal —
 * un à-coup de l'interface (et de l'orbe) à chaque réponse, qui grandissait avec la longueur de la conversation.
 *
 * Ici : un seul fil d'écriture, dans l'ordre des demandes ; pour un même fichier, seule la dernière version en attente
 * est écrite (les versions intermédiaires sont sautées) ; écriture atomique (fichier temporaire dans un sous-dossier,
 * puis renommage) : jamais de conversation à moitié écrite si le téléphone coupe JARVIS en plein enregistrement.
 */
object Ecritures {
    private val fil = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-ecritures").apply { isDaemon = true } }
    private val enAttente = ConcurrentHashMap<String, () -> String>()

    /** Écrit [contenu] (calculé sur le fil d'écriture) dans [f]. Revient tout de suite. */
    fun ecrire(f: File, contenu: () -> String) {
        val cle = f.absolutePath
        // Une écriture de ce fichier est déjà prévue et pas commencée : elle prendra cette version-ci.
        if (enAttente.put(cle, contenu) != null) return
        fil.execute {
            val c = enAttente.remove(cle) ?: return@execute
            runCatching { atomique(f, c()) }
        }
    }

    /** Annule l'écriture en attente de [f] (fichier supprimé juste après). */
    fun oublier(f: File) { enAttente.remove(f.absolutePath) }

    /** Annule les écritures en attente des fichiers du dossier [d]. */
    fun oublierDossier(d: File) {
        val prefixe = d.absolutePath.trimEnd(File.separatorChar) + File.separator
        enAttente.keys.removeIf { it.startsWith(prefixe) }
    }

    /** Attend que les écritures déjà demandées soient faites (avant de relire un de ces fichiers). */
    fun attendre(ms: Long = 1500) { runCatching { fil.submit {}.get(ms, TimeUnit.MILLISECONDS) } }

    /** Écrit [texte] dans [f] d'un bloc : temporaire dans un sous-dossier caché (hors des listes), puis renommage. */
    fun atomique(f: File, texte: String) {
        val dossier = f.absoluteFile.parentFile ?: return f.writeText(texte)
        val tmpDir = File(dossier, ".ecritures").apply { mkdirs() }
        val tmp = File(tmpDir, f.name + ".tmp")
        tmp.writeText(texte)
        if (!tmp.renameTo(f)) { f.writeText(texte); tmp.delete() }
    }
}
