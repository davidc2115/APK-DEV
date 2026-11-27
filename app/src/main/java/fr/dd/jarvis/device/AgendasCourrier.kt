package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import java.time.LocalDateTime

/**
 * Agendas proposés pour un rendez-vous reçu : ceux du téléphone ET ceux de Google, RÉUNIS (un même agenda n'apparaît
 * qu'une fois, avec le nom choisi dans JARVIS), y compris ceux en lecture seule (affichés, mais impossibles à remplir).
 * Un agenda Google reçoit le rendez-vous par l'API Google Agenda (visible tout de suite dans Google Agenda), puis le
 * téléphone est synchronisé aussitôt pour qu'il apparaisse aussi dans JARVIS.
 */
object AgendasCourrier {
    /** Où l'agenda vit : Google et téléphone, Google seulement, téléphone seulement. */
    enum class Lieu { GOOGLE_ET_TELEPHONE, GOOGLE, TELEPHONE }

    /** [cle] : « g:<compte>:<id Google> » ou « l:<id téléphone> » (ce qui est retenu). */
    data class Choix(val cle: String, val nom: String, val compte: String, val couleur: Int, val localId: Long?, val googleId: String?,
                     val googleCompte: String?, val ecriture: Boolean = true, val lieu: Lieu = Lieu.TELEPHONE) {
        val etiquette: String get() = when {
            !ecriture -> "lecture seule : impossible d'y ajouter un rendez-vous"
            lieu == Lieu.GOOGLE_ET_TELEPHONE -> "Google Agenda + JARVIS"
            lieu == Lieu.GOOGLE -> "Google Agenda seulement (pas synchronisé sur le téléphone : absent de JARVIS)"
            else -> "téléphone seulement : n'apparaîtra pas dans Google Agenda"
        }
    }

    /** Agenda Google tel que la liste Google le donne (pour les tests : sans appel réseau). */
    data class Distant(val compte: String, val id: String, val nom: String, val couleur: Int, val ecriture: Boolean = true)

    /**
     * Réunit les agendas du téléphone et ceux de Google : un agenda Google déjà synchronisé sur le téléphone est reconnu
     * par son identifiant Google (dans _SYNC_ID, NAME ou OWNER_ACCOUNT), sinon par son compte et son nom.
     */
    internal fun reunir(locaux: List<CalendarRepo.DetailAgenda>, distants: List<Distant>): List<Choix> {
        val utilises = mutableSetOf<Long>()
        val choix = mutableListOf<Choix>()
        // Un même agenda partagé avec deux comptes reliés n'est proposé qu'une fois (celui qui peut écrire d'abord).
        val uniques = distants.sortedBy { !it.ecriture }.distinctBy { it.id.lowercase() }
        fun libre(l: CalendarRepo.DetailAgenda) = l.id !in utilises
        for (g in uniques) {
            val id = g.id.lowercase()
            val l = locaux.firstOrNull { libre(it) && it.compte.equals(g.compte, true) && id in it.identifiants }
                ?: locaux.firstOrNull { libre(it) && it.typeCompte == "com.google" && id in it.identifiants }
                ?: locaux.firstOrNull { libre(it) && it.compte.equals(g.compte, true) && normaliser(it.nom) == normaliser(g.nom) }
            // Autres copies du même agenda Google sur le téléphone (synchronisé par un 2e compte) : doublons.
            locaux.filter { libre(it) && it.typeCompte == "com.google" && id in it.identifiants && it.id != l?.id }.forEach { utilises += it.id }
            val cle = "g:${g.compte.lowercase()}:${g.id}"
            if (l != null) {
                utilises += l.id
                choix += Choix(cle, l.nom, g.compte, l.couleur, l.id, g.id, g.compte, g.ecriture || l.ecriture,
                    if (l.synchronise) Lieu.GOOGLE_ET_TELEPHONE else Lieu.GOOGLE)
            } else choix += Choix(cle, g.nom, g.compte, g.couleur, null, g.id, g.compte, g.ecriture, Lieu.GOOGLE)
        }
        for (l in locaux.filter { libre(it) }) {
            // Agenda Google d'un compte non relié à JARVIS : écrit par le téléphone, qui le synchronise ensuite vers Google.
            val google = l.typeCompte == "com.google"
            choix += Choix("l:${l.id}", l.nom, l.compte, l.couleur, l.id, null, null, l.ecriture,
                if (google && l.synchronise) Lieu.GOOGLE_ET_TELEPHONE else Lieu.TELEPHONE)
        }
        return choix.distinctBy { it.cle }.sortedWith(compareBy({ !it.ecriture }, { it.compte.lowercase() }, { normaliser(it.nom) }))
    }

    suspend fun liste(app: JarvisApp): List<Choix> {
        val locaux = runCatching { app.calendar.detailAgendas() }.getOrDefault(emptyList())
        val comptes = app.store.gmailAccounts.toList()
        val distants = comptes.flatMap { compte -> runCatching { GmailApi.agendasGoogle(app, compte) }.getOrDefault(emptyList()) }
            .map { Distant(it.compte, it.id, it.nom, it.couleur, it.ecriture) }
        return reunir(locaux, distants)
    }

    /** Choix retenu : par clé, sinon par nom (ancien réglage qui retenait le nom). */
    fun retrouver(liste: List<Choix>, retenu: String): Choix? {
        val cle = retenu.substringBefore('\t'); val nom = retenu.substringAfter('\t', retenu)
        return liste.firstOrNull { it.cle == cle && it.ecriture } ?: liste.firstOrNull { it.ecriture && it.nom.equals(nom, true) }
    }

    /** Valeur retenue (clé + nom lisible). */
    fun memoire(c: Choix) = c.cle + "\t" + c.nom

    /** Crée le rendez-vous [e] dans l'agenda choisi ; renvoie la phrase à afficher (commence par « Événement » si réussi). */
    suspend fun creer(app: JarvisApp, c: Choix, e: Courrier.Element, debut: LocalDateTime): String {
        if (!c.ecriture) return "❌ « ${c.nom} » est en lecture seule : Google n'accepte pas d'y ajouter un rendez-vous."
        val a = Courrier.argsEvenement(e, debut)
        val titre = a.optString("title"); val lieu = a.optString("location"); val description = a.optString("description")
        val fin = debut.plusHours(1)
        var erreurGoogle: String? = null
        if (c.googleId != null && c.googleCompte != null) {
            erreurGoogle = GmailApi.creerEvenementGoogle(app, c.googleCompte, c.googleId, titre, debut, fin, lieu, description)
            if (erreurGoogle == null) {
                // Google l'a ; le téléphone (et donc JARVIS) le reçoit à la synchronisation, demandée tout de suite.
                // Agenda présent sur le téléphone mais non synchronisé : sa synchro est activée pour qu'il arrive aussi dans JARVIS.
                val dansJarvis = when {
                    c.localId == null -> { app.calendar.synchroniserCompte(c.googleCompte); false }
                    c.lieu == Lieu.GOOGLE -> app.calendar.activerSynchro(c.localId)
                    else -> { app.calendar.synchroniser(c.localId); true }
                }
                return "Événement « $titre » ajouté à ${c.nom} · " + if (dansJarvis) "Google Agenda et JARVIS (dans quelques secondes)."
                else "Google Agenda (cet agenda n'est pas synchronisé sur le téléphone : active-le dans l'appli Google Agenda › Paramètres pour le voir dans JARVIS)."
            }
        }
        val id = c.localId?.let { runCatching { app.calendar.create(it, titre, debut, fin, lieu, description) }.getOrNull() }
        return when {
            id != null -> "Événement « $titre » ajouté à ${c.nom} (#$id)" + when {
                erreurGoogle != null -> " — dans JARVIS ; Google Agenda : $erreurGoogle"
                c.lieu == Lieu.GOOGLE_ET_TELEPHONE -> " · JARVIS, puis Google Agenda à la synchronisation (demandée)."
                else -> " · téléphone seulement."
            }
            erreurGoogle != null -> "❌ Google Agenda : $erreurGoogle"
            else -> "❌ Impossible d'écrire dans « ${c.nom} »."
        }
    }
}
