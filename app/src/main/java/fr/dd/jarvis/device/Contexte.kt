package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.Geo

/**
 * Modes automatiques : Jarvis adapte ses réponses, sa voix et ses notifications à la situation.
 *  - VOITURE  : Android Auto connecté → réponses très courtes, rien à lire ;
 *  - CHANTIER : à moins de 250 m d'une installation cliente → réponses courtes et actionnables, fiche du client à portée ;
 *  - NUIT     : plage horaire de nuit → réponses brèves, voix plus douce, notifications de résultats silencieuses ;
 *  - MAISON   : près du siège / domicile ;
 *  - NORMAL   : le reste du temps.
 * Un mode peut être forcé dans les réglages ou depuis l'accueil (il redevient automatique au bout de 4 h).
 */
object Contexte {
    enum class Mode(val icone: String, val libelle: String) {
        NORMAL("🟢", "Normal"), VOITURE("🚗", "Voiture"), CHANTIER("👷", "Chantier"), NUIT("🌙", "Nuit"), MAISON("🏠", "Maison")
    }

    data class Etat(val mode: Mode, val detail: String = "", val clientId: String? = null, val siteId: String? = null, val force: Boolean = false)

    /** Décision pure (testable) à partir des signaux. */
    internal fun decider(enVoiture: Boolean, heure: Int, nuitDebut: Int, nuitFin: Int, siteProche: Triple<String, String, String>?,
                         kmMaison: Double?): Etat {
        if (enVoiture) return Etat(Mode.VOITURE, "Android Auto")
        if (siteProche != null) return Etat(Mode.CHANTIER, siteProche.third, siteProche.first, siteProche.second)
        val nuit = if (nuitDebut > nuitFin) heure >= nuitDebut || heure < nuitFin else heure in nuitDebut until nuitFin
        if (nuit) return Etat(Mode.NUIT, "${nuitDebut}h – ${nuitFin}h")
        if (kmMaison != null && kmMaison < 0.15) return Etat(Mode.MAISON, "au siège")
        return Etat(Mode.NORMAL)
    }

    fun actuel(app: JarvisApp): Etat {
        val s = app.store
        if (s.modeForce.value.isNotBlank() && System.currentTimeMillis() < s.modeForceJusqua.value.toLongOrNull() ?: 0L) {
            Mode.values().firstOrNull { it.name == s.modeForce.value }?.let { return Etat(it, "choisi à la main", force = true) }
        }
        if (!s.modesAuto.value) return Etat(Mode.NORMAL)
        val ici = runCatching { Locator.point(app) }.getOrNull()
        val proche = ici?.let { p ->
            app.societe.data.clients.asSequence().flatMap { c -> c.sites.asSequence().map { site -> Triple(c, site, site.lat?.let { la -> site.lon?.let { lo -> Geo.haversine(p, Geo.Point(la, lo)) } }) } }
                .filter { it.third != null && it.third!! < 0.25 }.minByOrNull { it.third!! }
                ?.let { (c, site, _) -> Triple(c.id, site.id, c.nom + if (c.sites.size > 1) " · ${site.nom}" else "") }
        }
        val hq = app.societe.data.societe
        val kmMaison = if (ici != null && hq.lat != null && hq.lon != null) Geo.haversine(ici, Geo.Point(hq.lat, hq.lon)) else null
        return decider(app.enVoiture, java.time.LocalTime.now().hour, s.nuitDebut.value.toIntOrNull() ?: 22, s.nuitFin.value.toIntOrNull() ?: 7, proche, kmMaison)
    }

    /** Nuit en ce moment (mode forcé ou plage horaire), sans localisation : assez léger pour être appelé à chaque phrase. */
    fun nuitMaintenant(app: JarvisApp): Boolean {
        val s = app.store
        if (s.modeForce.value.isNotBlank() && System.currentTimeMillis() < (s.modeForceJusqua.value.toLongOrNull() ?: 0L)) return s.modeForce.value == Mode.NUIT.name
        if (!s.modesAuto.value) return false
        val h = java.time.LocalTime.now().hour
        val d = s.nuitDebut.value.toIntOrNull() ?: 22
        val f = s.nuitFin.value.toIntOrNull() ?: 7
        return if (d > f) h >= d || h < f else h in d until f
    }

    /** Consigne ajoutée aux deux cerveaux (vide en mode normal). */
    fun consigne(e: Etat): String = when (e.mode) {
        Mode.VOITURE -> "\n\nMODE VOITURE (l'utilisateur conduit) : réponds en 1 ou 2 phrases courtes, sans liste, tableau ni lien ; rien à lire à l'écran ; demande confirmation à l'oral avant toute action."
        Mode.CHANTIER -> "\n\nMODE CHANTIER : l'utilisateur est chez le client ${e.detail}. Réponses courtes et directement actionnables, sécurité électrique d'abord (consignation DC/AC) ; utilise la fiche et l'historique de cette installation quand c'est utile."
        Mode.NUIT -> "\n\nMODE NUIT : réponses brèves et calmes ; ne propose rien de bruyant ; reporte au lendemain ce qui peut attendre."
        Mode.MAISON, Mode.NORMAL -> ""
    }

    /** Résultats de tâches sans son ni bandeau (nuit, chantier, voiture). */
    fun notificationsDiscretes(app: JarvisApp) = actuel(app).mode in setOf(Mode.NUIT, Mode.CHANTIER, Mode.VOITURE)

    /** Volume relatif de la voix de Jarvis (plus douce la nuit). */
    fun volumeVoix(app: JarvisApp): Float = if (actuel(app).mode == Mode.NUIT) 0.55f else 1f

    fun forcer(app: JarvisApp, mode: Mode?) {
        app.store.modeForce.set(mode?.name.orEmpty())
        app.store.modeForceJusqua.set(if (mode == null) "0" else (System.currentTimeMillis() + 4 * 3_600_000L).toString())
    }
}
