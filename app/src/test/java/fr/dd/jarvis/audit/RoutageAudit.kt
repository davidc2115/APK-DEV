package fr.dd.jarvis.audit

import fr.dd.jarvis.ai.*
import fr.dd.jarvis.societe.core.normaliser

/** Qui prend une phrase, dans l'ordre des réflexes du cerveau (détecteurs purs ; les autres sont laissés à l'IA). */
object RoutageAudit {
    private val modes = listOf(fr.dd.jarvis.securite.Systemes.Systeme("1", "Mode nuit"), fr.dd.jarvis.securite.Systemes.Systeme("2", "Mode absence"))

    fun route(t: String): String {
        val parties = Brain.decouperActions(t.trim())
        val comb = if (parties.size in 2..5) " [combinée:${parties.size}]" else ""
        fr.dd.jarvis.device.CommandeMedia.intention(t) { true }?.let { return "Media:$it$comb" }
        GestionConsignes.demande(t)?.let { return "Consignes:${it::class.simpleName}$comb" }
        Capacites.demande(t)?.let { return "Capacites$comb" }
        if (Annulation.demande(t)) return "Annuler$comb"
        Calculs.repondre(t, java.time.LocalDate.of(2026, 11, 20))?.let { return "Calcul$comb" }
        fr.dd.jarvis.societe.Frais.intention(t)?.let { return "Frais:${it::class.simpleName}$comb" }
        fr.dd.jarvis.device.RappelsLieu.intention(t)?.let { return "RappelLieu$comb" }
        fr.dd.jarvis.device.Courrier.intention(t)?.let { return "Courrier:${it.javaClass.simpleName}$comb" }
        fr.dd.jarvis.device.Anniversaires.intention(t)?.let { return "Anniv:${it::class.simpleName}$comb" }
        fr.dd.jarvis.device.RdvTermines.intention(t, java.time.LocalDate.of(2026, 11, 20))?.let { return "RdvFini:${it::class.simpleName}$comb" }
        fr.dd.jarvis.device.SilenceRdv.intention(t)?.let { return "ModeRdv:${it::class.simpleName}$comb" }
        fr.dd.jarvis.device.Listes.intention(t)?.let { return "Liste:${it::class.simpleName}$comb" }
        fr.dd.jarvis.device.Stationnement.intention(t)?.let { return "Parking:${it.javaClass.simpleName}$comb" }
        MailCommandes.intention(t)?.let { return "Mail:${it.first}$comb" }
        fr.dd.jarvis.device.ModeSonore.intention(t)?.let { return "ModeSonore:$it$comb" }
        PersonneOu.personne(t)?.let { return "PersonneOu:$it$comb" }
        PlexTools.intention(t)?.let { return "Plex:${it.first}$comb" }
        fr.dd.jarvis.securite.Systemes.parPhrase(t, modes)?.let { return "ModeJarvis:${modes[it.first].nom}/${it.second}$comb" }
        val soc = MaisonDirecte.societe(t)
        if (!soc && MaisonDirecte.analyser(t) != null) return "MaisonTele$comb"
        if (!soc && MaisonDirecte.analyserLumiere(t) != null) return "MaisonLumiere$comb"
        if (!soc && PoeleDirect.lecture(t) != null) return "PoeleLecture$comb"
        if (NavigationRdv.demande(t) != null) return "NavRdv$comb"
        fr.dd.jarvis.device.ReseauMobile.intention(t)?.let { return "Reseau:$it$comb" }
        CommandesDirectes.intention(t)?.let { i -> return "CD:${i.outil ?: i.local ?: i.appli}${if (i.texteLibre) "(libre)" else ""}$comb" }
        if (!soc && PoeleDirect.demande(t) != null) return "PoeleAction$comb"
        DomotiqueDirecte.commande(normaliser(t))?.let { return "Domo${if (it.libre) "(libre)" else ""}$comb" }
        Brain.actionImmediate(t)?.let { return "Immediat:${it.first}/${it.second}$comb" }
        return "IA$comb"
    }
}
