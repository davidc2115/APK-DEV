package com.jarvis.assistant

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
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
    private lateinit var hfTokenInput: EditText
    private lateinit var customModelUrlInput: EditText
    private lateinit var cloudSection: View
    private lateinit var localSection: View
    private lateinit var localModelPathText: TextView
    private lateinit var downloadProgressText: TextView
    private lateinit var styleOrbPulse: TextView
    private lateinit var styleOrbNetwork: TextView

    private var selectedProvider: Provider = Provider.GROQ
    private var selectedAccentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
    private var selectedOrbStyle: String = "PULSE"
    private var isDownloading = false

    private val colorSwatchIds = listOf(
        R.id.colorCyan, R.id.colorRed, R.id.colorBlue,
        R.id.colorPurple, R.id.colorGold, R.id.colorGreen
    )
    private lateinit var colorSwatches: List<View>

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
        hfTokenInput = findViewById(R.id.hfTokenInput)
        customModelUrlInput = findViewById(R.id.customModelUrlInput)
        cloudSection = findViewById(R.id.cloudSection)
        localSection = findViewById(R.id.localSection)
        localModelPathText = findViewById(R.id.localModelPathText)
        downloadProgressText = findViewById(R.id.downloadProgressText)
        styleOrbPulse = findViewById(R.id.styleOrbPulse)
        styleOrbNetwork = findViewById(R.id.styleOrbNetwork)
        val pickModelButton = findViewById<TextView>(R.id.pickModelButton)
        val downloadRecommendedButton = findViewById<TextView>(R.id.downloadRecommendedButton)
        val downloadNoKeyButton = findViewById<TextView>(R.id.downloadNoKeyButton)
        val downloadCustomButton = findViewById<TextView>(R.id.downloadCustomButton)
        val saveButton = findViewById<TextView>(R.id.saveButton)

        colorSwatches = colorSwatchIds.map { findViewById(it) }

        setupProviderSpinner()
        setupColorSwatches()
        setupOrbStyleSelector()

        hfTokenInput.setText(Prefs.getHfToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        apiKeyInput.setText(Prefs.getApiKey(this))
        updateLocalModelLabel()

        pickModelButton.setOnClickListener { pickModelLauncher.launch(arrayOf("*/*")) }

        downloadRecommendedButton.setOnClickListener {
            startDownload(ModelDownloader.RECOMMENDED_MODEL_URL, useToken = true)
        }

        downloadNoKeyButton.setOnClickListener {
            startDownload(ModelDownloader.NO_KEY_MODEL_URL, useToken = false)
        }

        downloadCustomButton.setOnClickListener {
            val url = customModelUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Colle d'abord une URL de modèle", Toast.LENGTH_SHORT).show()
            } else {
                startDownload(url, useToken = true)
            }
        }

        saveButton.setOnClickListener {
            Prefs.save(
                this,
                selectedProvider,
                baseUrlInput.text.toString().trim(),
                modelInput.text.toString().trim(),
                apiKeyInput.text.toString().trim()
            )
            Prefs.saveHfToken(this, hfTokenInput.text.toString().trim())
            Prefs.saveAccentColor(this, selectedAccentColor)
            Prefs.saveOrbStyle(this, selectedOrbStyle)
            Toast.makeText(this, "Paramètres enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupProviderSpinner() {
        val currentProvider = Prefs.getProvider(this)
        selectedProvider = currentProvider

        val providerNames = Provider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.setSelection(Provider.entries.indexOf(currentProvider))
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
    }

    private fun setupColorSwatches() {
        selectedAccentColor = Prefs.getAccentColor(this)
        highlightSelectedSwatch()

        for ((index, swatchId) in colorSwatchIds.withIndex()) {
            val swatch = colorSwatches[index]
            swatch.setOnClickListener {
                val bg = swatch.background
                selectedAccentColor = if (bg is android.graphics.drawable.ColorDrawable) {
                    bg.color
                } else {
                    Prefs.DEFAULT_ACCENT_COLOR
                }
                highlightSelectedSwatch()
            }
        }
    }

    private fun highlightSelectedSwatch() {
        for (swatch in colorSwatches) {
            val bg = swatch.background
            val isSelected = bg is android.graphics.drawable.ColorDrawable && bg.color == selectedAccentColor
            swatch.alpha = if (isSelected) 1f else 0.45f
            swatch.scaleX = if (isSelected) 1.15f else 1f
            swatch.scaleY = if (isSelected) 1.15f else 1f
        }
    }

    private fun setupOrbStyleSelector() {
        selectedOrbStyle = Prefs.getOrbStyle(this)
        highlightOrbStyle()

        styleOrbPulse.setOnClickListener {
            selectedOrbStyle = "PULSE"
            highlightOrbStyle()
        }
        styleOrbNetwork.setOnClickListener {
            selectedOrbStyle = "NETWORK_SPHERE"
            highlightOrbStyle()
        }
    }

    private fun highlightOrbStyle() {
        val pulseSelected = selectedOrbStyle == "PULSE"
        styleOrbPulse.alpha = if (pulseSelected) 1f else 0.5f
        styleOrbNetwork.alpha = if (pulseSelected) 0.5f else 1f
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
            "Modèle actif : ${File(path).name}"
        }
    }

    private fun startDownload(url: String, useToken: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "Un téléchargement est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        val hfToken = if (useToken) hfTokenInput.text.toString().trim() else ""
        isDownloading = true
        downloadProgressText.text = "Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            ModelDownloader.download(this@SettingsActivity, url, hfToken) { progress ->
                runOnUiThread {
                    when (progress) {
                        is ModelDownloader.Progress.Percent ->
                            downloadProgressText.text = "Téléchargement… ${progress.value}%"
                        is ModelDownloader.Progress.Done -> {
                            isDownloading = false
                            downloadProgressText.text = "✅ Modèle prêt !"
                            updateLocalModelLabel()
                            Toast.makeText(
                                this@SettingsActivity,
                                "Modèle téléchargé avec succès",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is ModelDownloader.Progress.Error -> {
                            isDownloading = false
                            downloadProgressText.text = ""
                            Toast.makeText(this@SettingsActivity, progress.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
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
