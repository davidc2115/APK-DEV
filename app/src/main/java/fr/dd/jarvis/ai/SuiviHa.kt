package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Suivi en arrière-plan d'une commande Home Assistant pas encore confirmée sur un appareil LENT (poêle à granulés,
 * chauffe-eau, volet, télé, serrure…) : relectures à +30 s, +1 min 30 et +4 min (après avoir demandé à Home Assistant
 * de relire l'appareil), puis une notification avec le résultat réel — confirmé, appareil devenu indisponible, ou
 * toujours pas suivi. Plus de « l'appareil n'a peut-être pas suivi » affiché trop tôt.
 */
object SuiviHa {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val numeros = AtomicInteger(7_300)
    /** Un seul suivi par entité : une nouvelle commande remplace le suivi de la précédente. */
    private val enCours = ConcurrentHashMap<String, Int>()

    /** Pauses entre les relectures (ms). */
    internal val PAUSES = listOf(30_000L, 60_000L, 150_000L)

    internal fun duree(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s s" else if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60}"
    }

    fun planifier(app: JarvisApp, domaine: String, service: String, id: String, demandes: JSONObject, avant: JSONObject?, nom: String) {
        val notif = numeros.incrementAndGet()
        enCours[id] = notif
        scope.launch {
            val debut = System.currentTimeMillis()
            var dernier: JSONObject? = null
            for (pause in PAUSES) {
                delay(pause)
                if (enCours[id] != notif) return@launch   // une commande plus récente sur le même appareil a pris le relais
                runCatching { Serveur.ha(app, "POST", "/api/services/homeassistant/update_entity", JSONObject().put("entity_id", id)) }
                delay(2_000)
                val o = runCatching { JSONObject(Serveur.ha(app, "GET", "/api/states/$id")) }.getOrNull() ?: continue
                dernier = o
                val ecoule = duree(System.currentTimeMillis() - debut + 2_500)
                if (Serveur.confirmeService(domaine, service, demandes, o, avant)) {
                    fin(app, id, notif, "✅ $nom", "$domaine.$service confirmé par Home Assistant au bout de $ecoule — état « ${o.optString("state")} ».")
                    return@launch
                }
                if (o.optString("state") == "unavailable") {
                    fin(app, id, notif, "⚠️ $nom indisponible", "Après la commande $domaine.$service, l'appareil est devenu indisponible dans Home Assistant (hors ligne ou intégration déconnectée).")
                    return@launch
                }
            }
            val etat = dernier?.optString("state") ?: "illisible"
            fin(app, id, notif, "⚠️ $nom n'a pas suivi",
                "$domaine.$service envoyé il y a ${duree(System.currentTimeMillis() - debut + 2_500)}, mais l'état reste « $etat ». Vérifie l'appareil (ou son intégration) : la commande n'a pas été appliquée.")
        }
    }

    private fun fin(app: JarvisApp, id: String, notif: Int, titre: String, texte: String) {
        enCours.remove(id, notif)
        runCatching { fr.dd.jarvis.voice.JournalVoix.noter("maison", "$titre : $texte") }
        runCatching { fr.dd.jarvis.work.SuiviTaches.termine(app, notif, titre, texte) }
    }
}
