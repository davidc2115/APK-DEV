package fr.dd.jarvis.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal de l'écoute (80 derniers évènements, en mémoire) : qui a entendu « Jarvis », qui a joué un bip, qui a ouvert le
 * micro, quelles erreurs. Affiché dans Réglages › Écoute : quand des bips arrivent en double, il dit QUEL système les a
 * produits (détecteur, appli, service, reconnaissance de Google, coupure de parole…).
 */
object JournalVoix {
    private val lignes = ArrayDeque<String>()
    private val heure = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)

    @Synchronized fun noter(source: String, evt: String) {
        lignes.addLast("${heure.format(Date())}  [$source] $evt")
        while (lignes.size > 80) lignes.removeFirst()
    }

    @Synchronized fun texte(): String = if (lignes.isEmpty()) "(rien pour l'instant)" else lignes.joinToString("\n")

    @Synchronized fun vider() = lignes.clear()
}
