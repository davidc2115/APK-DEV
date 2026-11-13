package fr.dd.jarvis.device

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import fr.dd.jarvis.societe.core.normaliser

/**
 * Mode sonore du téléphone, à la voix et sans IA : « ne pas déranger » (NPD / silencieux), « vibreur », « sonnerie »
 * (sonnerie + vibreur). Utilise l'accès « Ne pas déranger » accordé à JARVIS, sinon le shell du téléphone (Shizuku / ADB).
 */
object ModeSonore {
    const val NPD = "npd"
    const val NPD_FIN = "npd_off"
    const val VIBREUR = "vibreur"
    const val SONNERIE = "sonnerie"
    const val ETAT = "etat"

    private val AUTRE_CIBLE = Regex("\\b(tele|tv|enceintes?|musique|chanson|alarme|home assistant|camera|sonnette|voiture|discord|serveur|bot|groupe|conversation|whatsapp|notifications? de)\\b")
    private val FIN = Regex("\\b(desactive[rsz]?|enleve[rsz]?|coupe[rsz]?|retire[rsz]?|quitte[rsz]?|sors?|sortir|arrete[rsz]?|stoppe|stop|fin du|plus de|eteins?)\\b")
    private val R_NPD = Regex("\\b(ne pas deranger|ne plus deranger|npd|silencieux|en silence|mode silence|sans bruit)\\b")
    private val R_VIBREUR = Regex("\\b(vibreur|mode vibration|en vibration|que vibrer|vibre seulement)\\b")
    private val R_SONNERIE = Regex("\\b(mode sonnerie|mode sonore|mode normal|(mets|met|remets|active|reactive|rallume|retablis|passe en|passe le telephone en) (la |le |en )?(sonnerie|son)|sonnerie (et|plus|\\+) vibreur|sonnerie avec vibreur|remets le son|rends le son)\\b")
    private val R_VOLUME = Regex("\\b(a fond|au maximum|au max|plus fort|moins fort|monte|baisse|augmente|diminue|\\d+ ?%|pour cent|volume)\\b")
    private val R_ETAT = Regex("^(quel est le |quel |c est quoi le |dans quel )?mode (sonore|de sonnerie)|^(est ce que )?(je suis|le telephone est|mon telephone est) (en )?(silencieux|vibreur|ne pas deranger|npd|sonnerie)")

    /** Mode demandé (npd, npd_off, vibreur, sonnerie, etat) ou null si la phrase ne parle pas du mode sonore du téléphone. */
    fun intention(texte: String): String? {
        val n = normaliser(texte).replace(Regex("[?!.,;:]+"), " ").replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^(jarvis |ok |alors |est ce que tu peux |tu peux |peux tu |s il te plait |stp )+"), "")
        if (n.isBlank() || n.split(' ').size > 14 || AUTRE_CIBLE.containsMatchIn(n)) return null
        if (R_ETAT.containsMatchIn(n)) return ETAT
        val fin = FIN.containsMatchIn(n)
        return when {
            R_NPD.containsMatchIn(n) -> if (fin) NPD_FIN else NPD
            R_SONNERIE.containsMatchIn(n) && !R_VOLUME.containsMatchIn(n) -> if (fin) VIBREUR else SONNERIE   // « sonnerie et vibreur » compris
            R_VIBREUR.containsMatchIn(n) -> if (fin) SONNERIE else VIBREUR
            else -> null
        }
    }

    fun etat(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val am = ctx.getSystemService(AudioManager::class.java)
        val npd = nm.currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        return when {
            npd -> "🔕 Le téléphone est en Ne pas déranger."
            am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> "📳 Le téléphone est en vibreur."
            am.ringerMode == AudioManager.RINGER_MODE_SILENT -> "🔇 Le téléphone est en silencieux."
            else -> "🔔 Le téléphone est en mode sonnerie (volume ${am.getStreamVolume(AudioManager.STREAM_RING)}/${am.getStreamMaxVolume(AudioManager.STREAM_RING)})."
        }
    }

    private fun shell(ctx: Context, cmd: String): Boolean =
        PhoneShell.ready(ctx) && runCatching { PhoneShell.exec(ctx, cmd); true }.getOrDefault(false)

    private fun npd(ctx: Context, actif: Boolean): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.isNotificationPolicyAccessGranted) return runCatching {
            nm.setInterruptionFilter(if (actif) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL); true
        }.getOrDefault(false)
        return shell(ctx, "cmd notification set_dnd " + if (actif) "priority" else "off")
    }

    private fun sonnerie(ctx: Context, mode: Int): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        if (runCatching { am.ringerMode = mode; am.ringerMode == mode }.getOrDefault(false)) return true
        return shell(ctx, "cmd audio set-ringer-mode " + when (mode) { AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"; AudioManager.RINGER_MODE_SILENT -> "SILENT"; else -> "NORMAL" })
    }

    /** « Vibrer pendant la sonnerie » (sonnerie + vibreur). */
    private fun vibrerAvecSonnerie(ctx: Context): Boolean =
        (Settings.System.canWrite(ctx) && runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.VIBRATE_WHEN_RINGING, 1) }.getOrDefault(false)) ||
            shell(ctx, "settings put system vibrate_when_ringing 1")

    private fun demanderAcces(ctx: Context): String {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        return "⚠️ Il me faut l'accès « Ne pas déranger » : je t'ouvre la page, active JARVIS puis redemande-moi."
    }

    fun appliquer(ctx: Context, mode: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        return when (mode) {
            ETAT -> etat(ctx)
            NPD -> if (npd(ctx, true)) "🔕 Ne pas déranger activé (les alarmes et les contacts prioritaires passent toujours)." else demanderAcces(ctx)
            NPD_FIN -> if (npd(ctx, false)) "🔔 Ne pas déranger désactivé. " + etat(ctx).substringAfter(" ") else demanderAcces(ctx)
            VIBREUR -> {
                if (!npd(ctx, false)) return demanderAcces(ctx)
                if (sonnerie(ctx, AudioManager.RINGER_MODE_VIBRATE)) "📳 Téléphone en vibreur." else demanderAcces(ctx)
            }
            SONNERIE -> {
                if (!npd(ctx, false)) return demanderAcces(ctx)
                if (!sonnerie(ctx, AudioManager.RINGER_MODE_NORMAL)) return demanderAcces(ctx)
                // Sonnerie à zéro : remise à un niveau audible (60 %).
                runCatching {
                    if (am.getStreamVolume(AudioManager.STREAM_RING) == 0)
                        am.setStreamVolume(AudioManager.STREAM_RING, (am.getStreamMaxVolume(AudioManager.STREAM_RING) * 0.6).toInt().coerceAtLeast(1), 0)
                }
                if (vibrerAvecSonnerie(ctx)) return "🔔📳 Téléphone en mode sonnerie + vibreur."
                // Une seule fois : la page d'Android pour autoriser JARVIS à régler « vibrer pendant la sonnerie ».
                val prefs = ctx.getSharedPreferences("jarvis_mode_sonore", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("autorisation_demandee", false)) {
                    prefs.edit().putBoolean("autorisation_demandee", true).apply()
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    "🔔 Téléphone en mode sonnerie. Pour qu'il vibre aussi pendant la sonnerie, autorise « Modifier les paramètres système » à JARVIS (page ouverte), puis redemande « mode sonnerie »."
                } else "🔔 Téléphone en mode sonnerie. (Vibreur pendant la sonnerie : autorise « Modifier les paramètres système » à JARVIS, ou active « Vibrer pour les appels » dans les réglages du son.)"
            }
            else -> "Mode inconnu : ne pas déranger, vibreur ou sonnerie."
        }
    }
}
