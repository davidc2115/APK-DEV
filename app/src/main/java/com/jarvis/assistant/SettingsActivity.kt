package com.jarvis.assistant

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val baseUrlInput = findViewById<EditText>(R.id.baseUrlInput)
        val modelInput = findViewById<EditText>(R.id.modelInput)
        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val saveButton = findViewById<TextView>(R.id.saveButton)

        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        apiKeyInput.setText(Prefs.getApiKey(this))

        saveButton.setOnClickListener {
            Prefs.save(
                this,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                apiKeyInput.text.toString().trim()
            )
            Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
