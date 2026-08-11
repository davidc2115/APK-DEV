package com.jarvis.assistant

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

/**
 * Écran de connexion par Compte Google (Page Web Sign-In, Mot de passe Google ou App-Password).
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var btnOpenWebGoogleLogin: TextView
    private lateinit var btnConnectGoogle: TextView
    private lateinit var btnOpenGooglePassGuide: TextView
    private lateinit var testResultText: TextView
    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_config)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        btnOpenWebGoogleLogin = findViewById(R.id.btnOpenWebGoogleLogin)
        btnConnectGoogle = findViewById(R.id.btnConnectGoogle)
        btnOpenGooglePassGuide = findViewById(R.id.btnOpenGooglePassGuide)
        testResultText = findViewById(R.id.testResultText)
        accountsContainer = findViewById(R.id.accountsContainer)

        // Préremplir si un compte est déjà détecté
        val discovered = AccountDiscoveryManager.getDeviceAccounts(this)
        if (discovered.isNotEmpty() && emailInput.text.isBlank()) {
            emailInput.setText(discovered.first().email)
        }

        refreshAccountsList()

        // Bouton 1: Ouvrir la page de connexion Google Web Sign-In
        btnOpenWebGoogleLogin.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val url = if (email.contains("@")) {
                "https://accounts.google.com/ServiceLogin?Email=$email"
            } else {
                "https://accounts.google.com/"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        }

        // Bouton 2: Générateur de mot de passe d'application 16 caractères
        btnOpenGooglePassGuide.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))
            startActivity(intent)
        }

        // Bouton 3: Connexion et test direct avec mot de passe
        btnConnectGoogle.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim().replace(" ", "")

            if (email.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Veuillez entrer votre email Google et votre mot de passe.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val account = Prefs.EmailAccount(
                label = "Gmail Google",
                email = email,
                password = pass,
                imapHost = "imap.gmail.com",
                imapPort = 993,
                imapSsl = true,
                smtpHost = "smtp.gmail.com",
                smtpPort = 587,
                smtpStartTls = true,
                isDefault = Prefs.getEmailAccounts(this).isEmpty()
            )

            testResultText.text = "🔄 Connexion au serveur Google IMAP en cours…"

            CoroutineScope(Dispatchers.Main).launch {
                val testRes = EmailController.testConnection(account)
                testResultText.text = testRes
                if (testRes.contains("succès", ignoreCase = true) || testRes.contains("✅", ignoreCase = true)) {
                    Prefs.addEmailAccount(this@EmailConfigActivity, account)
                    refreshAccountsList()
                    Toast.makeText(this@EmailConfigActivity, "✅ Compte Google connecté !", Toast.LENGTH_SHORT).show()
                } else {
                    // Enregistrer quand même pour que l'utilisateur puisse tester avec l'un ou l'autre mot de passe
                    Prefs.addEmailAccount(this@EmailConfigActivity, account)
                    refreshAccountsList()
                }
            }
        }
    }

    private fun refreshAccountsList() {
        accountsContainer.removeAllViews()
        val accounts = Prefs.getEmailAccounts(this)

        if (accounts.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucun compte Google connecté."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
            accountsContainer.addView(emptyText)
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
                val defTag = if (acc.isDefault) " ⭐ [Par défaut]" else ""
                text = "📧 ${acc.label}$defTag\n${acc.email}\nAuthentification: Compte Google"
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
