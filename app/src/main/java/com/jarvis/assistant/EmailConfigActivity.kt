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
 * Écran de connexion directe par Compte Google avec Mot de passe d'Application (16 caractères).
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var btnConnectGoogle: TextView
    private lateinit var btnOpenGooglePassGuide: TextView
    private lateinit var testResultText: TextView
    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_config)

        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
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

        btnOpenGooglePassGuide.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com/apppasswords"))
            startActivity(intent)
        }

        btnConnectGoogle.setOnClickListener {
            val email = emailInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim().replace(" ", "")

            if (email.isBlank() || pass.isBlank()) {
                Toast.makeText(this, "Entrez votre email Google et le mot de passe d'application 16 lettres.", Toast.LENGTH_LONG).show()
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
                text = "📧 ${acc.label}$defTag\n${acc.email}\nAuthentification: Google App Password"
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
