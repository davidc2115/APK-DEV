package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.IsoFields

/**
 * Idées de vidéos TikTok à poster, fondées sur des DONNÉES RÉELLES : statistiques de ton compte (API officielle),
 * tendances France, ta niche et tes concurrents (tikwm). Chaque lundi matin (si activé), 3 idées avec script arrivent
 * dans le chat, en notification et dans le coffre (TikTok/Propositions).
 */
object TikTokPropositions {
    private const val NOTIF = 7330

    /** « @a, b ; https://www.tiktok.com/@c » → [a, b, c]. */
    fun comptes(s: String): List<String> = s.split(',', ';', '\n', ' ').map { TikWm.pseudo(it) }.filter { it.isNotBlank() }.distinct()

    /** Données réunies pour l'IA ; chaque source manquante est signalée au lieu d'être inventée. */
    suspend fun donnees(app: JarvisApp, niche: String, maxConcurrents: Int = 3): String {
        val parties = mutableListOf<String>()
        if (TikTok.connecte(app)) runCatching {
            val v = TikTok.mesVideos(app, 40)
            parties += "## Mon compte\n" + TikTokAnalyse.bilan((0 until v.length()).map { TikTokAnalyse.depuisApi(v.getJSONObject(it)) })
        }.onFailure { parties += "## Mon compte\n(statistiques indisponibles : ${it.message?.take(120)})" }
        else parties += "## Mon compte\n(compte TikTok non connecté à JARVIS)"
        runCatching { parties += TikTokAnalyse.tendances(TikWm.tendances("FR", 30), "Tendances France") }
            .onFailure { parties += "## Tendances\n(indisponibles : ${it.message?.take(120)})" }
        if (niche.isNotBlank()) runCatching { parties += TikTokAnalyse.niche(niche, TikWm.hashtags(niche, 10), TikWm.recherche(niche, 30)) }
            .onFailure { parties += "## Niche\n(indisponible : ${it.message?.take(120)})" }
        comptes(app.store.tiktokConcurrents.value).take(maxConcurrents).forEach { c ->
            runCatching { parties += TikTokAnalyse.concurrent(TikWm.compte(c), TikWm.videosDe(c, 15)) }
                .onFailure { parties += "## @$c\n(indisponible : ${it.message?.take(120)})" }
        }
        return parties.joinToString("\n\n")
    }

    /** [nombre] idées avec script, enregistrées dans le coffre ; renvoie le texte à afficher. */
    suspend fun generer(app: JarvisApp, nombre: Int = 3, theme: String = ""): String {
        val niche = theme.ifBlank { app.store.tiktokNiche.value }.trim()
        val d = donnees(app, niche)
        val texte = app.llm.agent(Prompts.TIKTOK_PROPOSITIONS,
            "Nombre d'idées : ${nombre.coerceIn(1, 6)}\nNiche : ${niche.ifBlank { "non précisée" }}\nDate : ${LocalDate.now()}\n\nDONNÉES RÉELLES :\n$d")
        if (texte.isBlank()) return "❌ L'IA n'a pas pu écrire les propositions (quota du jour atteint ?). Les données sont prêtes : réessaie plus tard."
        val loc = runCatching { app.obsidian.saveIn("TikTok/Propositions", "Propositions ${LocalDate.now()}", texte + "\n\n---\n# Données utilisées\n" + d) }.getOrNull()
        return texte.trim() + (loc?.let { "\n\n📁 $it" } ?: "") + "\n\n🎬 Pour en monter une : « monte l'idée 2 en vidéo TikTok »."
    }

    /** Semaine ISO (« 2026-W48 »). */
    fun semaine(d: LocalDate): String = "${d.get(IsoFields.WEEK_BASED_YEAR)}-W${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

    /** À lancer : lundi à partir de 9 h (ou plus tard dans la semaine si le téléphone était éteint), une fois par semaine. */
    fun aLancer(maintenant: LocalDateTime, derniere: String, active: Boolean): Boolean =
        active && derniere != semaine(maintenant.toLocalDate()) &&
            (maintenant.dayOfWeek != DayOfWeek.MONDAY || maintenant.hour >= 9)

    /** Appelé par la veille de JARVIS (toutes les 30 min environ). */
    suspend fun hebdo(app: JarvisApp) {
        val st = app.store
        val utile = st.tiktokNiche.value.isNotBlank() || st.tiktokConcurrents.value.isNotBlank() || TikTok.connecte(app)
        val maintenant = LocalDateTime.now()
        if (!aLancer(maintenant, st.tiktokHebdoFait.value, st.tiktokHebdo.value && utile)) return
        st.tiktokHebdoFait.set(semaine(maintenant.toLocalDate()))
        val texte = runCatching { generer(app, 3) }.getOrElse { "❌ Propositions TikTok de la semaine impossibles : ${it.message}" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { app.chatAjouter(ChatMsg(false, "# 💡 Tes idées TikTok de la semaine\n\n$texte")) }
        fr.dd.jarvis.work.SuiviTaches.termine(app, NOTIF, "💡 Tes idées TikTok de la semaine", texte.lineSequence().firstOrNull { it.startsWith("## ") }?.removePrefix("## ") ?: "3 idées avec script dans JARVIS")
    }
}
