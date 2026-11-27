package fr.dd.jarvis.device

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Autorisations propres à Xiaomi / HyperOS. En plus d'Android, HyperOS bloque EN SILENCE toute appli qui ouvre une autre
 * appli depuis l'arrière-plan tant que « Afficher des fenêtres pop-up lors de l'exécution en arrière-plan » n'est pas
 * autorisé (Paramètres › Applications › JARVIS › Autres autorisations) — même avec « Afficher par-dessus les autres
 * applis » accordé. C'est ce qui empêchait JARVIS d'ouvrir Waze en voiture.
 */
object Xiaomi {
    /** Opérations privées de MIUI / HyperOS (AppOps). */
    const val OP_ARRIERE_PLAN = 10021
    const val OP_ECRAN_VERROUILLE = 10020
    /** « SMS de notification » : sans elle, HyperOS cache aux applis les SMS des expéditeurs de service (colis, rendez-vous, factures). */
    const val OP_SMS_NOTIFICATION = 10018

    fun est(): Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.orEmpty().lowercase() in setOf("xiaomi", "redmi", "poco") }

    /** Autorisation accordée ? null = pas un Xiaomi, ou état illisible. */
    fun autorise(ctx: Context, op: Int): Boolean? {
        if (!est()) return null
        return runCatching {
            val m = AppOpsManager::class.java.getDeclaredMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
            (m.invoke(ctx.getSystemService(AppOpsManager::class.java), op, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    fun arrierePlan(ctx: Context) = autorise(ctx, OP_ARRIERE_PLAN)
    fun ecranVerrouille(ctx: Context) = autorise(ctx, OP_ECRAN_VERROUILLE)
    fun smsNotification(ctx: Context) = autorise(ctx, OP_SMS_NOTIFICATION)

    /** Phrase d'aide si une autorisation Xiaomi manque (vide sinon, ou si l'état est inconnu hors Xiaomi). */
    fun conseilLancement(ctx: Context): String {
        if (!est()) return ""
        val manque = buildList {
            if (arrierePlan(ctx) != true) add("« Afficher des fenêtres pop-up lors de l'exécution en arrière-plan »")
            if (ecranVerrouille(ctx) == false) add("« Afficher sur l'écran de verrouillage »")
        }
        if (manque.isEmpty()) return ""
        return "Sur ton Xiaomi, autorise " + manque.joinToString(" et ") + " pour JARVIS (Paramètres › Applications › JARVIS › Autres autorisations, " +
            "ou Réglages de JARVIS › Fonctionnement en arrière-plan) : sans ça HyperOS bloque l'ouverture de Waze, même avec « par-dessus les autres applis »."
    }

    /** Ouvre l'écran « Autres autorisations » de JARVIS (sinon la fiche de l'appli). */
    fun ouvrirAutorisations(ctx: Context) {
        val p = ctx.packageName
        val essais = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")).putExtra("extra_pkgname", p),
            Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")).putExtra("extra_pkgname", p),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", p),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p")),
        )
        for (i in essais) {
            if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
