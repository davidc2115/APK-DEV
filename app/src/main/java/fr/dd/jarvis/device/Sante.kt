package fr.dd.jarvis.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale

/**
 * Tableau de santé de JARVIS : Hermès, quotas des clés Gemini et Groq, voix, serveur, Freebox, Home Assistant,
 * téléphone, Ollama. Les points « à surveiller » préviennent AVANT la panne (quotas qui s'épuisent, disque qui se
 * remplit, optimisation de batterie qui peut couper Jarvis…) ; la veille envoie une notification pour chaque nouveau
 * problème (au plus une fois toutes les 6 h par problème).
 */
object Sante {
    enum class Niveau { OK, ATTENTION, PANNE, INACTIF }

    data class Indicateur(val cle: String, val icone: String, val nom: String, val niveau: Niveau, val detail: String, val conseil: String = "")

    /** Niveau d'un trousseau de clés (testable) : (clés, en pause, refus de quota en 1 h). */
    internal fun niveauCles(total: Int, pause: Int, quotas: Int): Niveau = when {
        total == 0 -> Niveau.INACTIF
        pause >= total -> Niveau.PANNE
        pause > 0 || quotas >= 5 -> Niveau.ATTENTION
        else -> Niveau.OK
    }

    /** Niveau du disque (testable) : alerte sous 10 % ou 20 Go libres, panne sous 2 %. */
    internal fun niveauDisque(libre: Long, total: Long): Niveau = when {
        libre < 0 || total <= 0 -> Niveau.INACTIF
        libre * 50 < total -> Niveau.PANNE
        libre * 10 < total || libre < 20L * 1_073_741_824 -> Niveau.ATTENTION
        else -> Niveau.OK
    }

    private fun go(o: Long) = String.format(Locale.FRENCH, "%.1f Go", o / 1_073_741_824.0)

    suspend fun mesurer(app: JarvisApp): List<Indicateur> = withContext(Dispatchers.IO) {
        val l = mutableListOf<Indicateur>()
        suspend fun ajouter(f: suspend () -> Indicateur?) { runCatching { withTimeoutOrNull(12_000) { f() } }.getOrNull()?.let { l += it } }

        ajouter {
            val h = fr.dd.jarvis.hermes.Hermes
            when {
                app.store.hermesMode.value == "off" -> Indicateur("hermes", "🪽", "Hermès", Niveau.INACTIF, "désactivé (Jarvis seul)")
                !h.enLigne() -> Indicateur("hermes", "🪽", "Hermès", Niveau.PANNE, "hors ligne : Jarvis répond à sa place", "Jarvis le relance tout seul ; sinon Réglages › IA › Hermès › Démarrer.")
                h.degrade() -> Indicateur("hermes", "🪽", "Hermès", Niveau.ATTENTION, "saturé encore ${h.minutesSaturation()} min : ${h.derniereRaison.take(90)}", "Ajoute des clés Gemini/Groq pour répartir la charge.")
                else -> Indicateur("hermes", "🪽", "Hermès", Niveau.OK, "en ligne")
            }
        }
        ajouter {
            val (n, p, q) = app.llm.etatGemini()
            Indicateur("gemini", "🔑", "Clés Gemini", niveauCles(n, p, q), if (n == 0) "aucune clé" else "$n clé(s) · ${p} en pause · $q refus de quota en 1 h",
                if (p > 0 || q >= 5) "Le quota gratuit s'épuise : ajoute une clé Gemini (gratuite, Google AI Studio) pour l'étaler." else "")
        }
        ajouter {
            val (n, p, q) = app.llm.etatGroq()
            Indicateur("groq", "🔑", "Clés Groq", niveauCles(n, p, q), if (n == 0) "aucune clé" else "$n clé(s) · ${p} en pause · $q refus de quota en 1 h",
                if (p > 0 || q >= 5) "Le quota Groq s'épuise : ajoute une clé Groq pour l'étaler." else "")
        }
        ajouter {
            val v = fr.dd.jarvis.voice.VoixNaturelle
            when {
                !v.configuree(app) -> Indicateur("voix", "🎙️", "Voix naturelle", Niveau.INACTIF, v.etat(app))
                !v.active(app) -> Indicateur("voix", "🎙️", "Voix naturelle", Niveau.ATTENTION, v.etat(app), "Réglages › Voix › Voix › « Diagnostiquer la voix naturelle ».")
                else -> Indicateur("voix", "🎙️", "Voix naturelle", Niveau.OK, v.etat(app))
            }
        }
        ajouter {
            val hote = app.store.sshHost.value.trim()
            if (hote.isBlank()) Indicateur("serveur", "🖥️", "Serveur", Niveau.INACTIF, "non configuré")
            else {
                val port = app.store.sshPort.value.toIntOrNull() ?: 22
                val ok = runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(hote, port), 2500) } }.isSuccess
                if (ok) Indicateur("serveur", "🖥️", "Serveur", Niveau.OK, "$hote joignable")
                else Indicateur("serveur", "🖥️", "Serveur", Niveau.PANNE, "$hote:$port ne répond pas", "Hors du réseau de la maison, c'est normal ; sinon vérifie que le serveur est allumé.")
            }
        }
        ajouter {
            val e = fr.dd.jarvis.ai.FreeboxOs.etat(app) ?: return@ajouter Indicateur("freebox", "📶", "Freebox", Niveau.INACTIF, "non associée")
            val (enLigne, libre, total) = e
            val nd = niveauDisque(libre, total)
            when {
                !enLigne -> Indicateur("freebox", "📶", "Freebox", Niveau.PANNE, "connexion internet coupée")
                nd == Niveau.PANNE || nd == Niveau.ATTENTION -> Indicateur("freebox", "📶", "Freebox", nd, "disque presque plein : ${go(libre)} libres sur ${go(total)}", "Demande-moi de faire le ménage dans Téléchargements.")
                else -> Indicateur("freebox", "📶", "Freebox", Niveau.OK, "en ligne" + if (total > 0) " · disque ${go(libre)} libres" else "")
            }
        }
        ajouter {
            if (!fr.dd.jarvis.ai.Serveur.haConfigure(app)) Indicateur("maison", "🏠", "Home Assistant", Niveau.INACTIF, "non configuré")
            else {
                val r = runCatching { org.json.JSONArray(fr.dd.jarvis.ai.Serveur.ha(app, "GET", "/api/states")) }
                r.fold({ a ->
                    val ko = (0 until a.length()).count { a.getJSONObject(it).optString("state") == "unavailable" }
                    if (ko > 10) Indicateur("maison", "🏠", "Home Assistant", Niveau.ATTENTION, "$ko appareils injoignables", "Une intégration est peut-être déconnectée (box, Wi-Fi, piles).")
                    else Indicateur("maison", "🏠", "Home Assistant", Niveau.OK, "${a.length()} entités · $ko injoignable(s)")
                }, { Indicateur("maison", "🏠", "Home Assistant", Niveau.PANNE, "ne répond pas (${it.message?.take(60)})", "Vérifie le conteneur Home Assistant sur le serveur.") })
            }
        }
        ajouter {
            val d = PhoneHealth.dashboard(app)
            val pb = listOfNotNull(
                if ((d.battery ?: 100) < 15 && !d.charging) "batterie ${d.battery} %" else null,
                d.storageFreeGb?.takeIf { it < 5 }?.let { "stockage presque plein (${String.format(Locale.FRENCH, "%.1f", it)} Go)" },
                if (!d.unrestrictedBattery) "optimisation de batterie active (Android peut arrêter Jarvis)" else null,
                if (!d.accessibility) "service d'accessibilité coupé (pilotage de l'écran impossible)" else null,
                if (!d.notificationsAllowed) "notifications de Jarvis bloquées" else null,
            )
            if (pb.isEmpty()) Indicateur("telephone", "📱", "Téléphone", Niveau.OK, "batterie ${d.battery ?: "?"} %" + (d.storageFreeGb?.let { " · ${String.format(Locale.FRENCH, "%.0f", it)} Go libres" } ?: ""))
            else Indicateur("telephone", "📱", "Téléphone", Niveau.ATTENTION, pb.joinToString(" · "), "Réglages Android › Applications › JARVIS (batterie : sans restriction ; notifications) et Accessibilité.")
        }
        l
    }

    /** Résumé d'une ligne (bandeau de l'accueil). */
    fun resume(l: List<Indicateur>): String {
        val pb = l.filter { it.niveau == Niveau.PANNE || it.niveau == Niveau.ATTENTION }
        return if (pb.isEmpty()) "✅ Tous les systèmes fonctionnent" else "⚠️ ${pb.size} point(s) à surveiller : " + pb.joinToString(", ") { it.nom }
    }

    private const val CANAL = "sante_jarvis"
    private const val DELAI = 6 * 3_600_000L

    /** Veille : notifie les problèmes nouveaux (ou revenus après 6 h) ; oublie ceux qui sont réglés. */
    suspend fun verifier(app: JarvisApp) {
        val l = mesurer(app)
        val deja = runCatching { JSONObject(app.store.santeAlertes.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val now = System.currentTimeMillis()
        val nouveaux = l.filter { (it.niveau == Niveau.PANNE || it.niveau == Niveau.ATTENTION) && now - deja.optLong(it.cle, 0) > DELAI }
        val maj = JSONObject()
        l.filter { it.niveau == Niveau.PANNE || it.niveau == Niveau.ATTENTION }.forEach { maj.put(it.cle, if (it in nouveaux) now else deja.optLong(it.cle, now)) }
        app.store.santeAlertes.set(maj.toString())
        if (nouveaux.isEmpty() || Contexte.actuel(app).mode == Contexte.Mode.NUIT) return
        notifier(app, nouveaux)
    }

    private fun notifier(ctx: Context, l: List<Indicateur>) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Santé de JARVIS", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Alertes avant panne : quotas, disque, Hermès, serveur…" })
        val pi = PendingIntent.getActivity(ctx, 7700, Intent(ctx, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "sante")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val texte = l.joinToString("\n") { "${it.icone} ${it.nom} : ${it.detail}" + if (it.conseil.isNotBlank()) "\n   → ${it.conseil}" else "" }
        runCatching {
            NotificationManagerCompat.from(ctx).notify(7700, NotificationCompat.Builder(ctx, CANAL).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(if (l.any { it.niveau == Niveau.PANNE }) "JARVIS : problème détecté" else "JARVIS : à surveiller")
                .setContentText(l.joinToString(", ") { it.nom }).setStyle(NotificationCompat.BigTextStyle().bigText(texte))
                .setContentIntent(pi).setAutoCancel(true).build())
        }
    }
}
