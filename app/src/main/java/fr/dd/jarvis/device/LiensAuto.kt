package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Liaison AUTOMATIQUE du coffre : aucun fichier sans lien dans le graphe.
 *
 * Dès que JARVIS crée un fichier dans le coffre (note, image, vidéo, PDF, JSON…), et quand le graphe montre encore des
 * fichiers orphelins, un passage « Tout relier » SANS modification de tes notes range chaque note ou fichier isolé dans
 * l'index de son dossier (« Index — dossier », relié depuis « Index du coffre »). Les rafales d'écritures sont
 * regroupées en un seul passage. Transformer les mentions en clair en liens (qui modifie le texte de tes notes) reste
 * manuel (« Tout relier » du graphe).
 */
object LiensAuto {
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verrou = Mutex()
    @Volatile private var attente: Job? = null

    /** Dernier passage automatique : heure (ms) et compte rendu court (pour le graphe). */
    @Volatile var dernierLe = 0L
        private set
    @Volatile var dernierBilan = ""
        private set

    fun actif(app: JarvisApp) = app.store.liensAuto.value

    /** Fichier à ignorer : index générés (ils SONT les liens) et dossiers cachés (.obsidian, .trash…). */
    internal fun ignore(chemin: String): Boolean =
        LiensCoffre.estIndex(chemin) || chemin.trim('/').split('/').any { it.startsWith(".") }

    /** Un fichier vient d'être créé (ou le graphe a vu des orphelins) : un passage dans [delaiMs], rafales regroupées. */
    fun signaler(app: JarvisApp, chemin: String = "", delaiMs: Long = 20_000) {
        if (!actif(app)) return
        if (chemin.isNotBlank() && ignore(chemin)) return
        attente?.cancel()
        attente = portee.launch {
            delay(delaiMs)
            passer(app)
        }
    }

    /** Démarrage de l'appli : rattrape les orphelins (au plus toutes les 6 h, pas dans les premières secondes). */
    fun auDemarrage(app: JarvisApp) {
        if (!actif(app)) return
        val dernier = app.store.liensAutoLe.value.toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - dernier < 6 * 3_600_000L) return
        signaler(app, delaiMs = 45_000)
    }

    /** Un passage : index régénérés, aucune note de l'utilisateur modifiée. */
    suspend fun passer(app: JarvisApp): LiensCoffre.Bilan? = verrou.withLock {
        if (!actif(app)) return@withLock null
        if (app.obsidian.coffreConfigure() && !app.obsidian.coffreDisponible()) return@withLock null
        val b = runCatching { LiensCoffre.relier(app, appliquer = true, mentions = false) }.getOrNull() ?: return@withLock null
        dernierLe = System.currentTimeMillis()
        app.store.liensAutoLe.set(dernierLe.toString())
        dernierBilan = if (b.plan.rienAFaire) "tout est relié" else "${b.plan.notesIndexees} note(s) et ${b.plan.fichiersIndexes} fichier(s) reliés par les index" +
            (if (b.erreurs.isNotEmpty()) " · ${b.erreurs.size} écriture(s) impossible(s)" else "")
        if (b.indexEcrits > 0 || b.erreurs.isNotEmpty()) runCatching { fr.dd.jarvis.voice.JournalVoix.noter("obsidian", "liaison automatique : $dernierBilan") }
        b
    }
}
