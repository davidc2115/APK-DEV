package fr.dd.jarvis.device

import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Mode d'alimentation du TÉLÉPHONE (économie d'énergie / équilibré / performance), réglé directement par le shell
 * (ADB intégré ou Shizuku), sans piloter l'écran. Distinct du « mode de fonctionnement » de Jarvis lui-même
 * (Store.modeEnergie, [Energie]) qui ne règle que l'activité de l'application.
 *
 *  - Économie : économiseur de batterie Android (`cmd power set-mode 1`), celui du raccourci « Économiseur de batterie ».
 *  - Performance : mode performance de Xiaomi/HyperOS (réglage système `POWER_PERFORMANCE_MODE_OPEN`), économiseur coupé.
 *  - Équilibré : ni l'un ni l'autre.
 */
object ModeTelephone {
    private const val CLE_PERF = "POWER_PERFORMANCE_MODE_OPEN"
    private const val CLE_ECO_XIAOMI = "POWER_SAVE_MODE_OPEN"

    /** « économie », « performance », « équilibré », « état » (ou null si la demande ne nomme aucun mode). */
    fun lire(texte: String): String? {
        val t = fr.dd.jarvis.societe.core.normaliser(texte)
        return when {
            Regex("\\b(etat|quel mode|statut|diagnostic)\\b").containsMatchIn(t) && !Regex("\\b(passe|mets|active|bascule)\\b").containsMatchIn(t) -> "etat"
            Regex("\\b(desactive|coupe|arrete|enleve|retire|sors? de)\\b.{0,30}\\b(economiseur|economie|eco)\\b").containsMatchIn(t) -> "equilibre"
            Regex("\\b(haute\\s+)?performances?\\b|\\bturbo\\b|\\bjeu\\b").containsMatchIn(t) -> "performance"
            Regex("\\b(eco|economie|economique|economiseur|basse consommation|econome)\\b").containsMatchIn(t) -> "economie"
            Regex("\\b(equilibre|normal|standard|balanced|par defaut)\\b").containsMatchIn(t) -> "equilibre"
            else -> null
        }
    }

    private fun xiaomi() = listOf(Build.MANUFACTURER, Build.BRAND).any { it.lowercase() in setOf("xiaomi", "redmi", "poco") }

    private fun ecoActif(ctx: Context): Boolean? =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode }.getOrNull()

    private fun sh(ctx: Context, cmd: String): String = runCatching { PhoneShell.exec(ctx, cmd) }.getOrElse { "Erreur : ${it.message}" }.trim()

    private fun shellPossible(ctx: Context) = PhoneShell.ready(ctx) || AdbShell.hasKey(ctx)

    private fun erreurShell(r: String) = r.startsWith("Erreur") || r.startsWith("ADB non connecté") || r.startsWith("Aucun accès") ||
        r.contains("Unknown command", true) || r.contains("Exception", true) || r.contains("not allowed", true) || r.contains("permission", true)

    private const val SANS_SHELL = "Pour changer le mode du téléphone sans passer par l'écran, Jarvis a besoin de l'ADB intégré ou de Shizuku " +
        "(Réglages › Téléphone › Contrôle du téléphone). Une fois l'un des deux actif, redemande-moi simplement."

    /** Applique [mode] (« economie », « equilibre », « performance ») ou décrit l'état (« etat »). */
    suspend fun appliquer(ctx: Context, mode: String): String = withContext(Dispatchers.IO) {
        val m = lire(mode) ?: mode.trim().lowercase()
        if (m == "etat") return@withContext etat(ctx)
        if (m !in setOf("economie", "equilibre", "performance")) return@withContext "Mode du téléphone inconnu : « $mode ». Choix : économie, équilibré ou performance."
        if (!shellPossible(ctx)) return@withContext "⛔ $SANS_SHELL"
        val eco = if (m == "economie") 1 else 0
        val perf = if (m == "performance") 1 else 0
        val avantPerf = sh(ctx, "settings get system $CLE_PERF")
        val avantEcoXiaomi = sh(ctx, "settings get system $CLE_ECO_XIAOMI")
        if (avantPerf.startsWith("ADB non connecté") || avantPerf.startsWith("Aucun accès")) return@withContext "⛔ $avantPerf\n$SANS_SHELL"
        val problemes = mutableListOf<String>()
        // Économiseur de batterie Android (repli : réglage global surveillé par le système).
        val r = sh(ctx, "cmd power set-mode $eco")
        if (erreurShell(r)) {
            val r2 = sh(ctx, "settings put global low_power $eco")
            if (erreurShell(r2)) problemes += "économiseur Android : ${r.take(120)}"
        }
        // Réglages Xiaomi / HyperOS : n'écrire une clé que si elle existe déjà ou si c'est un téléphone Xiaomi.
        val perfConnue = avantPerf == "0" || avantPerf == "1"
        if (perfConnue || (xiaomi() && perf == 1)) {
            val w = sh(ctx, "settings put system $CLE_PERF $perf")
            if (erreurShell(w)) problemes += "mode performance Xiaomi : ${w.take(120)}"
        }
        if (avantEcoXiaomi == "0" || avantEcoXiaomi == "1") sh(ctx, "settings put system $CLE_ECO_XIAOMI $eco")
        delay(1200)
        // Vérification réelle après coup.
        val ecoApres = ecoActif(ctx)
        val perfApres = sh(ctx, "settings get system $CLE_PERF")
        val ok = when (m) {
            "economie" -> ecoApres == true
            "performance" -> ecoApres != true && (perfApres == "1" || !xiaomi())
            else -> ecoApres != true && perfApres != "1"
        }
        buildString {
            append(
                when (m) {
                    "economie" -> if (ok) "🔋 Téléphone en mode économie d'énergie : économiseur de batterie activé." else "⚠️ Le mode économie du téléphone n'a pas pu être confirmé."
                    "performance" -> when {
                        ok && xiaomi() -> "⚡ Téléphone en mode performance (mode performance Xiaomi activé, économiseur coupé)."
                        ok -> "⚡ Économiseur coupé. Ce téléphone n'a pas de mode performance système : c'est le réglage le plus rapide disponible."
                        else -> "⚠️ Le mode performance du téléphone n'a pas pu être confirmé."
                    }
                    else -> if (ok) "⚖️ Téléphone en mode équilibré : économiseur de batterie et mode performance désactivés." else "⚠️ Le mode équilibré du téléphone n'a pas pu être confirmé."
                }
            )
            if (!ok) append(" État relevé : économiseur ${etatEco(ecoApres)}, mode performance Xiaomi ${valeur(perfApres)}.")
            if (problemes.isNotEmpty()) append("\nDétail : ").append(problemes.joinToString(" ; "))
            append("\n(Le mode de fonctionnement de Jarvis lui-même n'est pas modifié.)")
        }
    }

    private fun etatEco(b: Boolean?) = when (b) { true -> "activé"; false -> "désactivé"; null -> "inconnu" }
    private fun valeur(v: String) = when (v.trim()) { "1" -> "activé"; "0" -> "désactivé"; "null", "" -> "absent"; else -> "« ${v.take(60)} »" }

    /** État actuel + réglages système liés à l'alimentation (diagnostic, utile si HyperOS change ses clés). */
    suspend fun etat(ctx: Context): String = withContext(Dispatchers.IO) {
        val eco = ecoActif(ctx)
        val base = "Économiseur de batterie du téléphone : ${etatEco(eco)}."
        if (!shellPossible(ctx)) return@withContext "$base\n(Pour le détail et pour changer de mode : $SANS_SHELL)"
        val perf = sh(ctx, "settings get system $CLE_PERF")
        val mode = when {
            eco == true -> "économie d'énergie"
            perf == "1" -> "performance"
            else -> "équilibré"
        }
        val cles = sh(ctx, "for n in system global secure; do settings list \$n 2>/dev/null | grep -iE 'power_?(save|perf|mode)|performance|low_power|battery_saver|supersave' | sed \"s/^/\$n: /\"; done | head -25")
        buildString {
            append("📱 Mode du téléphone : $mode. $base Mode performance Xiaomi : ${valeur(perf)}.")
            if (cles.isNotBlank() && !erreurShell(cles) && cles != "(commande exécutée)") append("\nRéglages système relevés :\n").append(cles.take(1500))
        }
    }
}
