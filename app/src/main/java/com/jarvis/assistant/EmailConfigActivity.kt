package com.jarvis.assistant

import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Écran de connexion directe par Compte Google Android (Sans IMAP).
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var emailInput: EditText
    private lateinit var btnConnectGoogle: TextView
    private lateinit var discoveredAccountsContainer: LinearLayout
    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_config)

        emailInput = findViewById(R.id.emailInput)
        btnConnectGoogle = findViewById(R.id.btnConnectGoogle)
        discoveredAccountsContainer = findViewById(R.id.discoveredAccountsContainer)
        accountsContainer = findViewById(R.id.accountsContainer)

        refreshDiscoveredAccounts()
        refreshAccountsList()

        btnConnectGoogle.setOnClickListener {
            val email = emailInput.text.toString().trim()
            if (email.isBlank()) {
                Toast.makeText(this, "Veuillez entrer une adresse email Google.", Toast.LENGTH_SHORT).show()
            } else {
                connectGoogleAccount(email, "Compte Google")
            }
        }
    }

    private fun refreshDiscoveredAccounts() {
        discoveredAccountsContainer.removeAllViews()
        val discovered = AccountDiscoveryManager.getDeviceAccounts(this)

        if (discovered.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucun compte Google trouvé sur cet appareil."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
            }
            discoveredAccountsContainer.addView(emptyText)
            return
        }

        for (acc in discovered) {
            val btn = TextView(this).apply {
                text = "🌐 Connecter ${acc.email} (${acc.providerPreset})"
                setTextColor(getColor(R.color.cyan_accent))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(16, 14, 16, 14)
                background = getDrawable(R.drawable.bg_input)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8 }

                setOnClickListener {
                    connectGoogleAccount(acc.email, acc.providerPreset)
                }
            }
            discoveredAccountsContainer.addView(btn)
        }
    }

    private fun connectGoogleAccount(email: String, label: String) {
        val googleAcc = Prefs.EmailAccount(
            label = label,
            email = email,
            password = "GOOGLE_ACCOUNT_AUTH",
            imapHost = "imap.gmail.com",
            imapPort = 993,
            imapSsl = true,
            smtpHost = "smtp.gmail.com",
            smtpPort = 587,
            smtpStartTls = true,
            isDefault = Prefs.getEmailAccounts(this).isEmpty()
        )
        Prefs.addEmailAccount(this, googleAcc)
        refreshAccountsList()
        Toast.makeText(this, "✅ Compte Google $email connecté à JARVIS !", Toast.LENGTH_LONG).show()
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
                text = "📧 ${acc.label}$defTag\n${acc.email}\nAuthentification: Compte Google Système"
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
