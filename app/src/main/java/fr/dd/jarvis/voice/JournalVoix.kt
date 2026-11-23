package fr.dd.jarvis.voice

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de l'écoute et de la voiture (500 derniers évènements, gardés sur le téléphone) : qui a entendu « Jarvis »,
 * qui a joué un bip, quel micro a été ouvert, où sort la voix, comment un itinéraire a été lancé, quelles erreurs.
 * Affiché dans Réglages › Écoute (Copier / Partager) : un trajet entier y tient, même si JARVIS a redémarré entre-temps.
 */
object JournalVoix {
    private const val MAX = 500
    private val lignes = ArrayDeque<String>()
    private val heure = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.FRANCE)
    @Volatile private var fichier: File? = null

    /** À l'ouverture de l'appli : reprend le journal enregistré et l'enregistre ensuite à chaque évènement. */
    @Synchronized fun brancher(dossier: File) {
        if (fichier != null) return
        val f = File(dossier, "journal_voix.txt")
        fichier = f
        runCatching { if (f.exists() && lignes.isEmpty()) f.readLines().takeLast(MAX).forEach { lignes.addLast(it) } }
    }

    @Synchronized fun noter(source: String, evt: String) {
        lignes.addLast("${heure.format(Date())}  [$source] $evt")
        while (lignes.size > MAX) lignes.removeFirst()
        fichier?.let { f -> val copie = lignes.toList(); fr.dd.jarvis.data.Ecritures.ecrire(f) { copie.joinToString("\n") } }
    }

    @Synchronized fun texte(): String = if (lignes.isEmpty()) "(rien pour l'instant)" else lignes.joinToString("\n")

    @Synchronized fun vider() {
        lignes.clear()
        fichier?.let { f -> fr.dd.jarvis.data.Ecritures.ecrire(f) { "" } }
    }
}

/**
 * Moments où JARVIS prend lui-même la main sur le son (focus audio, pause de la musique). Android Auto répond parfois en
 * mettant en pause la carte de lecture de JARVIS : cette pause-là ne doit ni couper sa voix ni fermer la conversation.
 */
object FocusJarvis {
    @Volatile var le = 0L
        private set

    fun noter() { le = System.currentTimeMillis() }

    /** Vrai si une pause reçue maintenant vient très probablement de JARVIS lui-même. */
    fun recent(ms: Long = 2500) = System.currentTimeMillis() - le < ms
}
