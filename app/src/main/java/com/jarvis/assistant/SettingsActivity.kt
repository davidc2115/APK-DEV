package com.jarvis.assistant

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : AppCompatActivity() {

    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var cloudSection: View
    private lateinit var localSection: View
    private lateinit var localModelPathText: TextView

    private var selectedProvider: Provider = Provider.GROQ

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        providerSpinner = findViewById(R.id.providerSpinner)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        modelInput = findViewById(R.id.modelInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        cloudSection = findViewById(R.id.cloudSection)
        localSection = findViewById(R.id.localSection)
        localModelPathText = findViewById(R.id.localModelPathText)
        val pickModelButton = findViewById<TextView>(R.id.pickModelButton)
        val saveButton = findViewById<TextView>(R.id.saveButton)

        val currentProvider = Prefs.getProvider(this)
        selectedProvider = currentProvider

        val providerNames = Provider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.setSelection(Provider.entries.indexOf(currentProvider))

        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        apiKeyInput.setText(Prefs.getApiKey(this))
        updateLocalModelLabel()
        updateSectionsVisibility(currentProvider)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = Provider.entries[position]
                selectedProvider = provider
                updateSectionsVisibility(provider)
                if (!provider.isLocal) {
                    baseUrlInput.setText(provider.defaultBaseUrl)
                    modelInput.setText(provider.defaultModel)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        pickModelButton.setOnClickListener {
            pickModelLauncher.launch(arrayOf("*/*"))
        }

        saveButton.setOnClickListener {
            Prefs.save(
                this,
                selectedProvider,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                apiKeyInput.text.toString().trim()
            )
            Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateSectionsVisibility(provider: Provider) {
        cloudSection.visibility = if (provider.isLocal) View.GONE else View.VISIBLE
        localSection.visibility = if (provider.isLocal) View.VISIBLE else View.GONE
    }

    private fun updateLocalModelLabel() {
        val path = Prefs.getLocalModelPath(this)
        localModelPathText.text = if (path.isBlank()) {
            "Aucun modèle importé"
        } else {
            "Modèle importé : ${File(path).name}"
        }
    }

    private fun importModelFile(uri: Uri) {
        Toast.makeText(this, "Import du modèle en cours…", Toast.LENGTH_LONG).show()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val destFile = File(filesDir, "local_model.task")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                Prefs.saveLocalModelPath(this@SettingsActivity, destFile.absolutePath)
                LocalLlmManager.unload()

                runOnUiThread {
                    updateLocalModelLabel()
                    Toast.makeText(
                        this@SettingsActivity,
                        "Modèle importé avec succès ✅",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Échec de l'import : ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
