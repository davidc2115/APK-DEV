package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.EventInfo
import fr.dd.jarvis.societe.core.normaliser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * « Lance la navigation vers mon rendez-vous », « emmène-moi à mon 1er rendez-vous d'aujourd'hui », « GPS vers le
 * rendez-vous de 14 h », « navigation chez Dupont » (client de l'agenda) : le bon rendez-vous est choisi dans l'agenda
 * et le guidage part vers SON adresse — sans IA.
 *
 * Avant, le modèle consultait le planning, dont le résultat (rendu tel quel) terminait la demande : seul le planning
 * s'affichait, la navigation ne partait jamais.
 */
object NavigationRdv {
    data class Demande(
        val demain: Boolean = false,
        /** 1 = premier… ; -1 = dernier ; null = pas de rang demandé. */
        val rang: Int? = null,
        val heure: Int? = null,
        val minute: Int? = null,
        val prochain: Boolean = false,
        /** Mots désignant le client / le rendez-vous (« Dupont », « onduleur »). */
        val mots: List<String> = emptyList(),
        /** « chez Dupont » : le nom est explicite (pas de repli s'il n'est dans aucun rendez-vous). */
        val chez: Boolean = false,
    )

    private val NAV = Regex("\\b(navigation|navigue|naviguer|nav|gps|itineraire|emmene|emmener|amene|amener|guide|guider|conduis|conduire|trajet|direction|waze|maps|" +
        "y aller|m y rendre|me rendre|aller (a|au|chez|vers|jusqu)|je vais (a|au|chez)|on va (a|au|chez)|route (vers|pour|jusqu))\\b")
    private val RDV = Regex("\\b(rendez vous|rendez|rdv|rendezvous|chantier|intervention|mission|visite|depannage|maintenance|client|cliente|prochain|suivant|premier|1er|deuxieme|second|2e|troisieme|3e|dernier)\\b")
    private val RANGS = listOf(
        Regex("\\b(premier|premiere|1er|1ere|1)\\b") to 1, Regex("\\b(deuxieme|second|seconde|2e|2eme|2)\\b") to 2,
        Regex("\\b(troisieme|3e|3eme|3)\\b") to 3, Regex("\\b(quatrieme|4e|4eme|4)\\b") to 4, Regex("\\b(cinquieme|5e|5eme|5)\\b") to 5,
        Regex("\\b(dernier|derniere)\\b") to -1,
    )
    private val HEURE = Regex("\\b(?:de|a|pour|vers)? ?(\\d{1,2}) ?(?:h|heures?)(?: ?(\\d{2}))?\\b")
    private val VIDES = setOf(
        "lance", "lancer", "mets", "met", "demarre", "demarrer", "ouvre", "ouvrir", "fais", "peux", "tu", "moi", "me", "m", "la", "le", "les", "l", "un", "une",
        "mon", "ma", "mes", "de", "du", "des", "d", "a", "au", "aux", "en", "vers", "pour", "sur", "avec", "chez", "jusqu", "jusque", "et", "y", "aller", "rendre",
        "navigation", "navigue", "naviguer", "nav", "gps", "itineraire", "emmene", "emmener", "amene", "amener", "guide", "guider", "conduis", "conduire",
        "trajet", "direction", "route", "waze", "maps", "google", "rendez", "vous", "rdv", "rendezvous", "chantier", "intervention", "mission", "visite",
        "depannage", "maintenance", "client", "cliente", "prochain", "prochaine", "suivant", "suivante", "premier", "premiere", "1er", "1ere", "deuxieme",
        "second", "seconde", "2e", "2eme", "troisieme", "3e", "3eme", "quatrieme", "4e", "cinquieme", "5e", "dernier", "derniere", "aujourd", "hui",
        "demain", "ce", "matin", "soir", "apres", "midi", "cet", "jour", "journee", "h", "heure", "heures", "stp", "svp", "plait", "il", "jarvis", "je", "vais",
        "on", "va", "nom", "qui", "est", "dans", "agenda", "planning", "calendrier", "s", "maintenant", "actuel", "en", "cours", "prevu", "suis", "dois",
    )

    /** Demande de navigation vers un rendez-vous de l'agenda (texte brut), ou null. */
    fun demande(texte: String): Demande? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (n.split(' ').size > 20 || !NAV.containsMatchIn(n)) return null
        val chezClient = Regex("\\bchez (?!moi\\b|nous\\b)").containsMatchIn(n)
        if (!RDV.containsMatchIn(n) && !chezClient) return null
        val h = HEURE.find(n)
        val rang = RANGS.firstOrNull { (r, _) -> r.containsMatchIn(n.replace(h?.value ?: "\u0000", " ")) }?.second
        val mots = n.replace(h?.value ?: "\u0000", " ").split(' ').filter { it.length >= 3 && it !in VIDES && !it.all { c -> c.isDigit() } }
        return Demande(
            demain = Regex("\\bdemain\\b").containsMatchIn(n),
            rang = rang,
            heure = h?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..23 },
            minute = h?.groupValues?.get(2)?.toIntOrNull(),
            prochain = Regex("\\b(prochain|prochaine|suivant|suivante)\\b").containsMatchIn(n),
            mots = mots,
            chez = chezClient,
        )
    }

    sealed class Choix {
        data class Trouve(val e: EventInfo) : Choix()
        data class Echec(val message: String) : Choix()
    }

    private fun texte(e: EventInfo) = " " + normaliser("${e.title} ${e.location} ${e.description.take(400)}") + " "

    /**
     * Le rendez-vous visé parmi [evenements] (triés ou non), à l'instant [maintenant]. Jour : aujourd'hui (ou demain) ;
     * « prochain » sans jour → le prochain des 7 jours.
     */
    internal fun choisir(evenements: List<EventInfo>, d: Demande, maintenant: Long, zone: ZoneId = ZoneId.systemDefault()): Choix {
        val aujourdhui = Instant.ofEpochMilli(maintenant).atZone(zone).toLocalDate()
        fun jour(e: EventInfo): LocalDate = Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone).toLocalDate()
        val tous = evenements.sortedBy { it.begin }
        val jourVise = if (d.demain) aujourdhui.plusDays(1) else aujourdhui
        var c = if (d.prochain && !d.demain && d.rang == null) tous.filter { it.end > maintenant } else tous.filter { jour(it) == jourVise }
        if (d.mots.isNotEmpty()) {
            // Un nom (client, lieu…) : le rendez-vous qui le porte — le jour demandé, sinon dans les 7 jours. Des mots
            // qui ne correspondent à rien (dictée approximative) sont ignorés, sauf un « chez … » explicite.
            val fenetre = if (d.demain || d.rang != null || d.heure != null) c else tous.filter { jour(it) >= aujourdhui && it.end > maintenant }
            val scores = fenetre.associateWith { e -> val t = texte(e); d.mots.count { m -> t.contains(" $m") } }
            val meilleur = scores.values.maxOrNull() ?: 0
            if (meilleur > 0) c = fenetre.filter { scores[it] == meilleur }.let { l -> l.filter { jour(it) == jourVise }.ifEmpty { l } }
            else if (d.chez) return Choix.Echec("Je ne trouve aucun rendez-vous à venir chez « ${d.mots.joinToString(" ")} » dans ton agenda.")
        }
        if (d.heure != null) {
            c = c.filter { e -> val z = Instant.ofEpochMilli(e.begin).atZone(zone); !e.allDay && z.hour == d.heure && (d.minute == null || z.minute == d.minute) }
            if (c.isEmpty()) return Choix.Echec("Aucun rendez-vous à ${d.heure} h${d.minute?.let { "%02d".format(it) }.orEmpty()} ${if (d.demain) "demain" else "aujourd'hui"}.")
        }
        // Les événements « journée entière » sans adresse (congés, anniversaires…) ne sont pas des rendez-vous où aller.
        val rdv = c.filter { !(it.allDay && it.location.isBlank()) }.ifEmpty { c }
        if (rdv.isEmpty()) return Choix.Echec(if (d.demain) "Aucun rendez-vous demain dans ton agenda." else "Aucun rendez-vous ${if (d.prochain) "à venir" else "aujourd'hui"} dans ton agenda.")
        d.rang?.let { r ->
            val e = if (r == -1) rdv.last() else rdv.getOrNull(r - 1)
                ?: return Choix.Echec("Tu n'as que ${rdv.size} rendez-vous ${if (d.demain) "demain" else "aujourd'hui"}.")
            return Choix.Trouve(e)
        }
        if (rdv.size == 1 || d.heure != null) return Choix.Trouve(rdv.first())
        // « Mon rendez-vous » : celui en cours, sinon le prochain ; la journée passée → le dernier de la journée.
        return Choix.Trouve(rdv.firstOrNull { it.end > maintenant && !it.allDay } ?: rdv.firstOrNull { it.end > maintenant } ?: rdv.last())
    }

    /** Adresse où aller : celle du rendez-vous, sinon celle du client de la Société nommé dans le rendez-vous. */
    internal fun adresse(app: JarvisApp, e: EventInfo): String? {
        e.location.trim().takeIf { it.isNotBlank() }?.let { return it }
        val t = texte(e)
        val client = runCatching { app.societe.data.clients }.getOrDefault(emptyList()).firstOrNull { c ->
            val n = normaliser(c.nom); n.length >= 3 && t.contains(" $n")
        } ?: return null
        return client.adresse.ifBlank { client.sites.firstOrNull { it.adresse.isNotBlank() }?.adresse.orEmpty() }.takeIf { it.isNotBlank() }
    }

    /** (adresse, libellé du rendez-vous) ou un message d'échec à dire tel quel. Null : pas une demande de ce type. */
    fun preparer(app: JarvisApp, texteUtilisateur: String, maintenant: Long = System.currentTimeMillis()): Pair<Pair<String, String>?, String?>? {
        val d = demande(texteUtilisateur) ?: return null
        if (!app.calendar.hasPermission()) return null to "Autorise l'agenda pour que je trouve tes rendez-vous."
        val evenements = app.calendar.events(LocalDate.now(), if (d.demain) 2 else 7)
        return when (val c = choisir(evenements, d, maintenant)) {
            is Choix.Echec -> null to "📅 ${c.message}"
            is Choix.Trouve -> {
                val f = java.time.format.DateTimeFormatter.ofPattern("HH'h'mm", java.util.Locale.FRENCH)
                val heure = if (c.e.allDay) "" else Instant.ofEpochMilli(c.e.begin).atZone(ZoneId.systemDefault()).format(f) + " · "
                val libelle = heure + c.e.title
                val adr = adresse(app, c.e) ?: return null to "📅 $libelle : ce rendez-vous n'a pas d'adresse dans l'agenda (ni de client avec une adresse). Dis-moi où aller."
                (adr to libelle) to null
            }
        }
    }
}
