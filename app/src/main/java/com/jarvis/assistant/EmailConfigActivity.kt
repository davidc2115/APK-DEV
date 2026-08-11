package com.jarvis.assistant

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de connexion Email pour JARVIS.
 *
 * Priorité 1 : Comptes Google déjà connectés sur l'appareil (AccountManager)
 * Priorité 2 : App Password 16 lettres (si 2FA activé)
 * Priorité 3 : Mot de passe Google (tenté mais souvent bloqué par Google)
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var btnOpenWebGoogleLogin: TextView
    private lateinit var btnConnectGoogle: TextView
    private lateinit var btnOpenGooglePassGuide: TextView
    private lateinit var testResultText: TextView
    private lateinit var accountsContainer: LinearLayout
    private lateinit var deviceAccountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_config)

        emailInput           = findViewById(R.id.emailInput)
        passwordInput        = findViewById(R.id.passwordInput)
        btnOpenWebGoogleLogin = findViewById(R.id.btnOpenWebGoogleLogin)
        btnConnectGoogle     = findViewById(R.id.btnConnectGoogle)
        btnOpenGooglePassGuide = findViewById(R.id.btnOpenGooglePassGuide)
        testResultText       = findViewById(R.id.testResultText)
        accountsContainer    = findViewById(R.id.accountsContainer)
        deviceAccountsContainer = findViewById<LinearLayout?>(R.id.deviceAccountsContainer)
            ?: LinearLayout(this) // fallback si absent du layout

        // 1. Charger les comptes Google détectés sur l'appareil
        refreshDeviceAccountsList()

        // 2. Préfill email depuis comptes connus
        val discovered = AccountDiscoveryManager.getDeviceAccounts(this)
        if (discovered.isNotEmpty() && emailInput.text.isBlank()) {
            emailInput.setText(discovered.first().email)
        }

        // 3. Afficher comptes déjà configurés dans JARVIS
        refreshAccountsList()

        // Bouton : Ouvrir page Sign-In Google dans le navigateur
        btnOpenWebGoogleLogin.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val url = if (email.contains("@")) {
                "https://accounts.google.com/ServiceLogin?Email=$email"
            } else {
                "https://accounts.google.com/"
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Bouton : Guide App Password Google
        btnOpenGooglePassGuide.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords")))
        }

        // Bouton : Connexion par mot de passe / App Password
        btnConnectGoogle.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val pass  = passwordInput.text.toString().trim().replace(" ", "")

            if (email.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Veuillez entrer votre email et votre mot de passe d'application.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val account = buildGoogleEmailAccount(email, pass)
            testResultText.text = "🔄 Test de connexion IMAP Google en cours…"

            CoroutineScope(Dispatchers.Main).launch {
                val testRes = withContext(Dispatchers.IO) { EmailController.testConnection(account) }
                testResultText.text = testRes
                // Enregistrer dans tous les cas pour permettre réessai ultérieur
                Prefs.addEmailAccount(this@EmailConfigActivity, account)
                refreshAccountsList()
                if (testRes.contains("✅")) {
                    Toast.makeText(this@EmailConfigActivity, "✅ Compte connecté avec succès !", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@EmailConfigActivity,
                        "⚠️ Connexion échouée. Utilisez un App Password Google.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Comptes Google détectés sur l'appareil (AccountManager)
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshDeviceAccountsList() {
        deviceAccountsContainer.removeAllViews()

        val googleAccounts = getGoogleAccountsOnDevice()
        if (googleAccounts.isEmpty()) return

        // Titre section
        val titleView = TextView(this).apply {
            text = "📱 Comptes Google détectés sur l'appareil :"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        deviceAccountsContainer.addView(titleView)

        for (acc in googleAccounts) {
            val btnCard = TextView(this).apply {
                text = "✅ Connecter avec  ${acc.name}"
                setTextColor(getColor(R.color.cyan_accent))
                textSize = 13f
                setPadding(24, 20, 24, 20)
                background = getDrawable(R.drawable.bg_bubble_ai)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 10 }
                setOnClickListener { connectDeviceGoogleAccount(acc) }
            }
            deviceAccountsContainer.addView(btnCard)
        }
    }

    /**
     * Récupère tous les comptes Google (@gmail.com ou Google Workspace)
     * connectés sur l'appareil via AccountManager.
     */
    private fun getGoogleAccountsOnDevice(): List<Account> {
        return try {
            val am = AccountManager.get(this)
            am.getAccountsByType("com.google").toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Connecte un compte Google déjà présent sur l'appareil.
     * Utilise AccountManager pour obtenir un token d'authentification.
     */
    private fun connectDeviceGoogleAccount(account: Account) {
        testResultText.text = "🔄 Connexion avec ${account.name}…"
        emailInput.setText(account.name)

        val am = AccountManager.get(this)

        // Demande un token Google Mail (scope pour IMAP)
        am.getAuthToken(
            account,
            "oauth2:https://mail.google.com/",
            null,
            this,
            { future ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val bundle = withContext(Dispatchers.IO) { future.result }
                        val token = bundle.getString(AccountManager.KEY_AUTHTOKEN)

                        if (token != null) {
                            // Enregistrer le compte avec le token OAuth2
                            val emailAccount = Prefs.EmailAccount(
                                label    = "Gmail — ${account.name}",
                                email    = account.name,
                                password = token,
                                imapHost = "imap.gmail.com",
                                imapPort = 993,
                                imapSsl  = true,
                                smtpHost = "smtp.gmail.com",
                                smtpPort = 587,
                                smtpStartTls = true,
                                isDefault = Prefs.getEmailAccounts(this@EmailConfigActivity).isEmpty(),
                                oauthToken = token,
                                isOAuth    = true
                            )

                            Prefs.addEmailAccount(this@EmailConfigActivity, emailAccount)
                            refreshAccountsList()
                            testResultText.text = "✅ Compte ${account.name} connecté via Google !"
                            Toast.makeText(this@EmailConfigActivity,
                                "✅ ${account.name} connecté !", Toast.LENGTH_SHORT).show()
                        } else {
                            // Si Android refuse le token silencieusement → intent de grant
                            val intentExtra = bundle.getParcelable<android.content.Intent>(AccountManager.KEY_INTENT)
                            if (intentExtra != null) {
                                startActivity(intentExtra)
                            } else {
                                testResultText.text = "❌ Impossible d'obtenir le token Google. Essayez un App Password."
                            }
                        }
                    } catch (e: Exception) {
                        testResultText.text = "❌ Erreur Google AccountManager : ${e.message}"
                    }
                }
            },
            null
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGoogleEmailAccount(email: String, pass: String) = Prefs.EmailAccount(
        label        = "Gmail",
        email        = email,
        password     = pass,
        imapHost     = "imap.gmail.com",
        imapPort     = 993,
        imapSsl      = true,
        smtpHost     = "smtp.gmail.com",
        smtpPort     = 587,
        smtpStartTls = true,
        isDefault    = Prefs.getEmailAccounts(this).isEmpty()
    )

    private fun refreshAccountsList() {
        accountsContainer.removeAllViews()
        val accounts = Prefs.getEmailAccounts(this)

        if (accounts.isEmpty()) {
            accountsContainer.addView(TextView(this).apply {
                text = "Aucun compte Google connecté dans JARVIS."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            })
            return
        }

        for (acc in accounts) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(14, 14, 14, 14)
                background = getDrawable(R.drawable.bg_bubble_ai)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8 }
            }

            val textInfo = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val defTag   = if (acc.isDefault) " ⭐" else ""
                val authType = if (acc.isOAuth) "OAuth2 Google ✅" else "App Password"
                text = "📧 ${acc.label}$defTag\n${acc.email}\nAuth : $authType"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            }

            val btnDelete = TextView(this).apply {
                text = "🗑 Déconnecter"
                setTextColor(getColor(R.color.cyan_accent))
                textSize = 12f
                setPadding(16, 8, 16, 8)
                setOnClickListener {
                    Prefs.removeEmailAccount(this@EmailConfigActivity, acc.id)
                    refreshAccountsList()
                }
            }

            itemLayout.addView(textInfo)
            itemLayout.addView(btnDelete)
            accountsContainer.addView(itemLayout)
        }
    }
}
