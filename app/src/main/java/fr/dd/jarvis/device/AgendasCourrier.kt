package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import java.time.LocalDateTime

/**
 * Agendas proposés pour un rendez-vous reçu : ceux du téléphone où JARVIS peut écrire ET tous les agendas Google
 * modifiables des comptes connectés (même ceux que le téléphone ne synchronise pas). Un agenda Google reçoit le
 * rendez-vous par l'API Google Agenda : il apparaît tout de suite dans Google Agenda, sur tous les appareils.
 */
object AgendasCourrier {
    /** [cle] : « g:<compte>:<id Google> » ou « l:<id téléphone> » (ce qui est retenu). */
    data class Choix(val cle: String, val nom: String, val compte: String, val couleur: Int, val localId: Long?, val googleId: String?, val googleCompte: String?)

    suspend fun liste(app: JarvisApp): List<Choix> {
        val locaux = runCatching { app.calendar.calendars().filter { it.writable } }.getOrDefault(emptyList()).map { cal ->
            val m = app.calendar.infosSync(cal.id)
            val google = m != null && m.second == "com.google" && m.third.isNotBlank()
            Choix(if (google) "g:${m!!.first}:${m.third}" else "l:${cal.id}", app.calendar.displayName(cal), cal.account,
                app.calendar.calendarColor(cal), cal.id, if (google) m!!.third else null, if (google) m!!.first else null)
        }
        val distants = app.store.gmailAccounts.toList().flatMap { compte -> runCatching { GmailApi.agendasGoogle(app, compte) }.getOrDefault(emptyList()) }
            .filter { g -> locaux.none { it.googleId == g.id && it.googleCompte.equals(g.compte, true) } }
            .map { g -> Choix("g:${g.compte}:${g.id}", g.nom, g.compte, g.couleur, null, g.id, g.compte) }
        return (locaux + distants).distinctBy { it.cle }.sortedWith(compareBy({ it.compte.lowercase() }, { it.nom.lowercase() }))
    }

    /** Choix retenu : par clé, sinon par nom (ancien réglage qui retenait le nom). */
    fun retrouver(liste: List<Choix>, retenu: String): Choix? {
        val cle = retenu.substringBefore('\t'); val nom = retenu.substringAfter('\t', retenu)
        return liste.firstOrNull { it.cle == cle } ?: liste.firstOrNull { it.nom.equals(nom, true) }
    }

    /** Valeur retenue (clé + nom lisible). */
    fun memoire(c: Choix) = c.cle + "\t" + c.nom

    /** Crée le rendez-vous [e] dans l'agenda choisi ; renvoie la phrase à afficher (commence par « Événement » si réussi). */
    suspend fun creer(app: JarvisApp, c: Choix, e: Courrier.Element, debut: LocalDateTime): String {
        val a = Courrier.argsEvenement(e, debut)
        val titre = a.optString("title"); val lieu = a.optString("location"); val description = a.optString("description")
        val fin = debut.plusHours(1)
        var erreurGoogle: String? = null
        if (c.googleId != null && c.googleCompte != null) {
            erreurGoogle = GmailApi.creerEvenementGoogle(app, c.googleCompte, c.googleId, titre, debut, fin, lieu, description)
            if (erreurGoogle == null) {
                // Le téléphone (et l'agenda de JARVIS) le reçoit à la synchronisation, demandée tout de suite.
                c.localId?.let { app.calendar.synchroniser(it) }
                return "Événement « $titre » ajouté à Google Agenda · ${c.nom}."
            }
        }
        val id = c.localId?.let { runCatching { app.calendar.create(it, titre, debut, fin, lieu, description) }.getOrNull() }
        return when {
            id != null -> "Événement « $titre » ajouté à ${c.nom} (#$id)" + (erreurGoogle?.let { " — sur le téléphone ; Google Agenda : $it" } ?: ".")
            erreurGoogle != null -> "❌ Google Agenda : $erreurGoogle"
            else -> "❌ Impossible d'écrire dans « ${c.nom} »."
        }
    }
}
