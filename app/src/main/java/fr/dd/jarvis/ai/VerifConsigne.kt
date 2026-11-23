package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray

/**
 * Vérification d'une consigne au moment où elle est enregistrée : chaque appareil qu'elle désigne (switch.…, climate.…)
 * existe-t-il dans Home Assistant, et son nom ne dit-il pas une autre pièce que celle de la consigne ? Une erreur est
 * signalée tout de suite (« voulais-tu dire … ? ») au lieu d'être découverte pendant une routine.
 */
object VerifConsigne {
    private val ENTITE = Regex("\\b(climate|switch|select|input_[a-z]+|light|cover|fan|button|script|scene|sensor|binary_sensor|media_player|number|automation|lock|vacuum|water_heater|humidifier|siren|alarm_control_panel)\\.[a-z0-9_]+")

    /** Remarques sur les appareils de [texte] ; [etats] : entity_id → nom affiché. Vide si tout va bien. */
    internal fun remarques(texte: String, etats: Map<String, String>): List<String> {
        val ids = ENTITE.findAll(texte.lowercase()).map { it.value }.distinct().toList()
        if (ids.isEmpty() || etats.isEmpty()) return emptyList()
        // Pièces dites par la consigne elle-même (sans les identifiants, qui en contiennent souvent).
        val mots = normaliser(ENTITE.replace(texte.lowercase(), " ")).split(' ').filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        for (id in ids) {
            val nom = etats[id]
            if (nom == null) {
                val proche = plusProche(id, etats, mots)
                out += "⚠️ « $id » n'existe pas dans Home Assistant" + (proche?.let { " — voulais-tu dire « $it » (« ${etats[it]} ») ?" } ?: " : vérifie l'identifiant.")
                continue
            }
            if (PoeleDirect.lieuContredit(PoeleDirect.texteEntite(id, nom), mots))
                out += "⚠️ « $id » (« $nom ») porte le nom d'une autre pièce que celle de la consigne : vérifie que c'est le bon appareil."
        }
        return out
    }

    /** Identifiant existant le plus proche (même domaine, pièce compatible), ou null. */
    private fun plusProche(id: String, etats: Map<String, String>, mots: List<String>): String? {
        fun jetons(x: String) = x.substringAfter('.').split('_').filter { it.length >= 2 }.toSet()
        val j = jetons(id)
        if (j.isEmpty()) return null
        return etats.keys.filter { it.substringBefore('.') == id.substringBefore('.') && !PoeleDirect.lieuContredit(PoeleDirect.texteEntite(it, etats.getValue(it)), mots) }
            .map { it to jetons(it).intersect(j).size.toDouble() / maxOf(jetons(it).size, j.size) }
            .filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }

    /** Remarques à montrer à l'utilisateur (une par ligne), ou null : rien à signaler, ou Home Assistant injoignable. */
    suspend fun avertissement(app: JarvisApp, texte: String): String? {
        if (!ENTITE.containsMatchIn(texte.lowercase()) || !Serveur.haConfigure(app)) return null
        val etats = kotlinx.coroutines.withTimeoutOrNull(4_000) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val a = JSONArray(Serveur.ha(app, "GET", "/api/states"))
                    (0 until a.length()).map { a.getJSONObject(it) }
                        .associate { it.optString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
                }.getOrNull()
            }
        } ?: return null
        return remarques(texte, etats).takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
