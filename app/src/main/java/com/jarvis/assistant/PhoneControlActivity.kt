package com.jarvis.assistant

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhoneControlActivity : AppCompatActivity() {

    private lateinit var permissionsReportText: TextView
    private lateinit var actionOutputText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_phone_control)

        permissionsReportText = findViewById(R.id.permissionsReportText)
        actionOutputText = findViewById(R.id.actionOutputText)

        val btnGrantPermissions = findViewById<TextView>(R.id.btnGrantPermissions)
        val btnNotifAccess = findViewById<TextView>(R.id.btnNotifAccess)
        val btnStorageAccess = findViewById<TextView>(R.id.btnStorageAccess)

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

        btnNotifAccess.setOnClickListener {
            PermissionsManager.openNotificationListenerSettings(this)
        }

        btnStorageAccess.setOnClickListener {
            PermissionsManager.requestManageStoragePermission(this)
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
