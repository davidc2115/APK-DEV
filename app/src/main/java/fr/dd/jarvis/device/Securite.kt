package fr.dd.jarvis.device

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Actions sensibles (suppression, envoi de mails/SMS, paiement, redémarrage…) : après le « oui », l'empreinte (ou le
 * code du téléphone) est demandée. À la voix, elles ne passent que si le téléphone est déverrouillé.
 */
object Securite {
    // Comparé au texte sans accents ni majuscules (« REDÉMARRER » → « redemarrer »).
    private val SENSIBLE = Regex(
        "supprim|effac|vider|corbeille|envoi|envoy|mail|\\bsms\\b|message|\\bpay|\\bpaie|paiement|virement|\\bregl|" +
            "redemarr|reboot|formater|ecras|remplac|desinstall|reinitialis|couper le wi")

    fun sensible(question: String) = SENSIBLE.containsMatchIn(fr.dd.jarvis.societe.core.normaliser(question))

    /** Le téléphone a un verrouillage (empreinte, code, schéma) : sinon, rien à demander. */
    fun protege(ctx: Context) = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure }.getOrDefault(false)

    fun verrouille(ctx: Context) = runCatching { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked }.getOrDefault(false)

    /** Demande l'empreinte (ou le code) ; vrai si l'utilisateur est authentifié (ou si le téléphone n'est pas protégé). */
    suspend fun authentifier(ctx: Context, titre: String, detail: String): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !protege(ctx)) return true
        return suspendCancellableCoroutine { cont ->
            val annulation = CancellationSignal()
            val exe = ctx.mainExecutor
            val b = BiometricPrompt.Builder(ctx).setTitle(titre).setDescription(detail.take(240))
            when {
                Build.VERSION.SDK_INT >= 30 -> b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                Build.VERSION.SDK_INT >= 29 -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
                else -> b.setNegativeButton("Annuler", exe) { _, _ -> if (cont.isActive) cont.resume(false) }
            }
            runCatching {
                b.build().authenticate(annulation, exe, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { if (cont.isActive) cont.resume(true) }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) { if (cont.isActive) cont.resume(false) }
                })
            }.onFailure { if (cont.isActive) cont.resume(false) }
            cont.invokeOnCancellation { annulation.cancel() }
        }
    }
}
