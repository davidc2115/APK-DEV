package com.jarvis.assistant

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Écran de configuration complète des comptes Email IMAP / SMTP & Comptes Google Android.
 */
class EmailConfigActivity : AppCompatActivity() {

    private lateinit var presetSpinner: Spinner
    private lateinit var accountLabelInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var imapHostInput: EditText
    private lateinit var imapPortInput: EditText
    private lateinit var smtpHostInput: EditText
    private lateinit var smtpPortInput: EditText
    private lateinit var useSslCheck: CheckBox

    private lateinit var btnTestConnection: TextView
    private lateinit var btnSaveAccount: TextView
    private lateinit var testResultText: TextView
    private lateinit var discoveredAccountsContainer: LinearLayout
    private lateinit var accountsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_config)

        presetSpinner = findViewById(R.id.presetSpinner)
        accountLabelInput = findViewById(R.id.accountLabelInput)
        emailInput = findViewById(R.id.emailInput)
        passwordInput = findViewById(R.id.passwordInput)
        imapHostInput = findViewById(R.id.imapHostInput)
        imapPortInput = findViewById(R.id.imapPortInput)
        smtpHostInput = findViewById(R.id.smtpHostInput)
        smtpPortInput = findViewById(R.id.smtpPortInput)
        useSslCheck = findViewById(R.id.useSslCheck)

        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSaveAccount = findViewById(R.id.btnSaveAccount)
        testResultText = findViewById(R.id.testResultText)
        discoveredAccountsContainer = findViewById(R.id.discoveredAccountsContainer)
        accountsContainer = findViewById(R.id.accountsContainer)

        setupPresets()
        refreshDiscoveredAccounts()
        refreshAccountsList()

        btnTestConnection.setOnClickListener { testEmailConnection() }
        btnSaveAccount.setOnClickListener { saveEmailAccount() }
    }

    private fun setupPresets() {
        val presets = listOf("Gmail", "Outlook / Hotmail", "Yahoo", "iCloud", "Personnalisé / Custom")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presets)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    0 -> applyPreset("Gmail", "imap.gmail.com", "993", "smtp.gmail.com", "587")
                    1 -> applyPreset("Outlook", "outlook.office365.com", "993", "smtp.office365.com", "587")
                    2 -> applyPreset("Yahoo", "imap.mail.yahoo.com", "993", "smtp.mail.yahoo.com", "587")
                    3 -> applyPreset("iCloud", "imap.mail.me.com", "993", "smtp.mail.me.com", "587")
                    else -> {}
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshDiscoveredAccounts() {
        discoveredAccountsContainer.removeAllViews()
        val discovered = AccountDiscoveryManager.getDeviceAccounts(this)

        if (discovered.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucun compte email trouvé dans Android."
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
                    // Connexion automatique 1-clic pour les comptes Google/Outlook système
                    val googleAcc = Prefs.EmailAccount(
                        label = acc.providerPreset,
                        email = acc.email,
                        password = "ANDROID_SYSTEM_AUTH",
                        imapHost = if (acc.providerPreset == "Gmail") "imap.gmail.com" else "outlook.office365.com",
                        imapPort = 993,
                        imapSsl = true,
                        smtpHost = if (acc.providerPreset == "Gmail") "smtp.gmail.com" else "smtp.office365.com",
                        smtpPort = 587,
                        smtpStartTls = true,
                        isDefault = Prefs.getEmailAccounts(this@EmailConfigActivity).isEmpty()
                    )
                    Prefs.addEmailAccount(this@EmailConfigActivity, googleAcc)
                    refreshAccountsList()
                    Toast.makeText(this@EmailConfigActivity, "✅ Compte Google ${acc.email} connecté avec succès !", Toast.LENGTH_LONG).show()
                }
            }
            discoveredAccountsContainer.addView(btn)
        }
    }

    private fun applyPreset(label: String, imapHost: String, imapPort: String, smtpHost: String, smtpPort: String) {
        if (accountLabelInput.text.isBlank()) accountLabelInput.setText(label)
        imapHostInput.setText(imapHost)
        imapPortInput.setText(imapPort)
        smtpHostInput.setText(smtpHost)
        smtpPortInput.setText(smtpPort)
        useSslCheck.isChecked = true
    }

    private fun testEmailConnection() {
        val account = buildAccountFromInputs() ?: return
        testResultText.text = "🔄 Test de connexion IMAP en cours…"

        CoroutineScope(Dispatchers.Main).launch {
            val result = EmailController.testConnection(account)
            testResultText.text = result
        }
    }

    private fun saveEmailAccount() {
        val account = buildAccountFromInputs() ?: return
        Prefs.addEmailAccount(this, account)
        Toast.makeText(this, "✅ Compte email enregistré !", Toast.LENGTH_SHORT).show()
        refreshAccountsList()
    }

    private fun buildAccountFromInputs(): Prefs.EmailAccount? {
        val email = emailInput.text.toString().trim()
        val pass = passwordInput.text.toString().trim()

        if (email.isBlank() || pass.isBlank()) {
            Toast.makeText(this, "Veuillez entrer une adresse email et un mot de passe.", Toast.LENGTH_SHORT).show()
            return null
        }

        val label = accountLabelInput.text.toString().trim().ifBlank { email }
        val imapHost = imapHostInput.text.toString().trim().ifBlank { "imap.gmail.com" }
        val imapPort = imapPortInput.text.toString().trim().toIntOrNull() ?: 993
        val smtpHost = smtpHostInput.text.toString().trim().ifBlank { "smtp.gmail.com" }
        val smtpPort = smtpPortInput.text.toString().trim().toIntOrNull() ?: 587

        return Prefs.EmailAccount(
            label = label,
            email = email,
            password = pass,
            imapHost = imapHost,
            imapPort = imapPort,
            imapSsl = true,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpStartTls = true,
            isDefault = Prefs.getEmailAccounts(this).isEmpty()
        )
    }

    private fun refreshAccountsList() {
        accountsContainer.removeAllViews()
        val accounts = Prefs.getEmailAccounts(this)

        if (accounts.isEmpty()) {
            val emptyText = TextView(this).apply {
                text = "Aucun compte email configuré."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            }
            accountsContainer.addView(emptyText)
            return
        }

        for (acc in accounts) {
            val itemLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 12, 12, 12)
                background = getDrawable(R.drawable.bg_bubble_ai)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8 }
            }

            val textInfo = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                val defTag = if (acc.isDefault) " ⭐ [Par défaut]" else ""
                text = "📧 ${acc.label}$defTag\n${acc.email}\nAuth: ${if (acc.password == "ANDROID_SYSTEM_AUTH") "Compte Système Android" else "IMAP Pass"}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
            }

            val btnDelete = TextView(this).apply {
                text = "🗑 Supprimer"
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
