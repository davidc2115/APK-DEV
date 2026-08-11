package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhoneControlActivity : AppCompatActivity() {

    private lateinit var permissionsReportText: TextView
    private lateinit var actionOutputText: TextView

    private val requestSmsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map.values.all { it }
        if (granted) {
            Toast.makeText(this, "✅ Permission SMS accordée !", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "⚠️ Permission SMS bloquée par Android. Utilisez le bouton '⚙️ OUVRIR PARAMÈTRES ANDROID' pour l'activer manuellement.", Toast.LENGTH_LONG).show()
        }
        updateReport()
    }

    private val requestContactsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { map ->
        val granted = map.values.all { it }
        if (granted) Toast.makeText(this, "✅ Permission Contacts accordée !", Toast.LENGTH_SHORT).show()
        else Toast.makeText(this, "⚠️ Permission Contacts bloquée par Android.", Toast.LENGTH_LONG).show()
        updateReport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_control)

        permissionsReportText = findViewById(R.id.permissionsReportText)
        actionOutputText = findViewById(R.id.actionOutputText)

        val btnGrantPermissions = findViewById<TextView>(R.id.btnGrantPermissions)
        val btnRequestSms = findViewById<TextView>(R.id.btnRequestSms)
        val btnRequestContacts = findViewById<TextView>(R.id.btnRequestContacts)
        val btnNotifAccess = findViewById<TextView>(R.id.btnNotifAccess)
        val btnAppDetailsSettings = findViewById<TextView>(R.id.btnAppDetailsSettings)
        val btnStorageAccess = findViewById<TextView>(R.id.btnStorageAccess)
        val btnEmailConfig = findViewById<TextView>(R.id.btnEmailConfig)

        val btnRecentCalls = findViewById<TextView>(R.id.btnRecentCalls)
        val btnReadSms = findViewById<TextView>(R.id.btnReadSms)
        val btnReadEmails = findViewById<TextView>(R.id.btnReadEmails)
        val btnReadEvents = findViewById<TextView>(R.id.btnReadEvents)
        val btnGetLocation = findViewById<TextView>(R.id.btnGetLocation)
        val btnGetNotifs = findViewById<TextView>(R.id.btnGetNotifs)

        updateReport()

        btnGrantPermissions.setOnClickListener {
            PermissionsManager.requestMissingPermissions(this, PermissionsManager.REQUEST_ALL)
        }

        btnRequestSms.setOnClickListener {
            requestSmsLauncher.launch(
                arrayOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS,
                    Manifest.permission.RECEIVE_MMS,
                    Manifest.permission.READ_PHONE_STATE
                )
            )
        }

        btnRequestContacts.setOnClickListener {
            requestContactsLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.WRITE_CONTACTS
                )
            )
        }

        btnAppDetailsSettings.setOnClickListener {
            Toast.makeText(this, "1. Touchez 'Autorisations' -> SMS -> Toujours autoriser\n2. (Si grisé) Touchez les 3 points (⋮) en haut -> Autoriser les paramètres restreints", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        btnNotifAccess.setOnClickListener {
            PermissionsManager.openNotificationListenerSettings(this)
        }

        btnStorageAccess.setOnClickListener {
            PermissionsManager.requestManageStoragePermission(this)
        }

        btnEmailConfig.setOnClickListener {
            startActivity(Intent(this, EmailConfigActivity::class.java))
        }

        btnRecentCalls.setOnClickListener {
            actionOutputText.text = PhoneController.getRecentCalls(this)
        }

        btnReadSms.setOnClickListener {
            actionOutputText.text = SmsController.readInboxSms(this)
        }

        btnReadEmails.setOnClickListener {
            actionOutputText.text = "Chargement des emails..."
            CoroutineScope(Dispatchers.Main).launch {
                val res = EmailController.readInbox(this@PhoneControlActivity)
                actionOutputText.text = res
            }
        }

        btnReadEvents.setOnClickListener {
            actionOutputText.text = CalendarController.getTodayEvents(this)
        }

        btnGetLocation.setOnClickListener {
            actionOutputText.text = "Recherche de position..."
            LocationController.getLastKnownLocation(this) { res ->
                runOnUiThread { actionOutputText.text = res }
            }
        }

        btnGetNotifs.setOnClickListener {
            actionOutputText.text = JarvisNotificationListenerService.getRecent(10)
        }
    }

    override fun onResume() {
        super.onResume()
        updateReport()
    }

    private fun updateReport() {
        permissionsReportText.text = PermissionsManager.getPermissionsReport(this)
    }
}
