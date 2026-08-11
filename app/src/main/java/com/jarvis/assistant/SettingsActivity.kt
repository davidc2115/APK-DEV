package com.jarvis.assistant

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var tabCloud: TextView
    private lateinit var tabApiKeys: TextView
    private lateinit var tabLocal: TextView
    private lateinit var panelCloud: View
    private lateinit var panelApiKeys: View
    private lateinit var panelLocal: View

    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var autoInfoText: View
    private lateinit var apiKeysContainer: LinearLayout

    private lateinit var hfTokenInput: EditText
    private lateinit var customModelUrlInput: EditText
    private lateinit var localModelPathText: TextView
    private lateinit var downloadProgressText: TextView

    private lateinit var btnDownloadLlama: TextView
    private lateinit var btnDownloadPhi: TextView
    private lateinit var btnDownloadMistral: TextView
    private lateinit var downloadNoKeyButton: TextView

    private lateinit var styleOrbPulse: TextView
    private lateinit var styleOrbNetwork: TextView
    private val colorSwatchIds = listOf(
        R.id.colorCyan, R.id.colorRed, R.id.colorBlue,
        R.id.colorPurple, R.id.colorGold, R.id.colorGreen
    )
    private lateinit var colorSwatches: List<View>

    private var selectedProvider: Provider = Provider.GROQ
    private var selectedAccentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
    private var selectedOrbStyle: String = "PULSE"
    private var isDownloading = false

    private val apiKeyFields = mutableMapOf<Provider, EditText>()

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        providerSpinner       = findViewById(R.id.providerSpinner)
        tabCloud              = findViewById(R.id.tabCloud)
        tabApiKeys            = findViewById(R.id.tabApiKeys)
        tabLocal              = findViewById(R.id.tabLocal)
        panelCloud            = findViewById(R.id.panelCloud)
        panelApiKeys          = findViewById(R.id.panelApiKeys)
        panelLocal            = findViewById(R.id.panelLocal)

        baseUrlInput          = findViewById(R.id.baseUrlInput)
        modelInput            = findViewById(R.id.modelInput)
        apiKeyInput           = findViewById(R.id.apiKeyInput)
        autoInfoText          = findViewById(R.id.autoInfoText)
        apiKeysContainer      = findViewById(R.id.apiKeysContainer)

        hfTokenInput          = findViewById(R.id.hfTokenInput)
        customModelUrlInput   = findViewById(R.id.customModelUrlInput)
        localModelPathText    = findViewById(R.id.localModelPathText)
        downloadProgressText  = findViewById(R.id.downloadProgressText)

        btnDownloadLlama      = findViewById(R.id.btnDownloadLlama)
        btnDownloadPhi        = findViewById(R.id.btnDownloadPhi)
        btnDownloadMistral    = findViewById(R.id.btnDownloadMistral)
        downloadNoKeyButton   = findViewById(R.id.downloadNoKeyButton)

        styleOrbPulse         = findViewById(R.id.styleOrbPulse)
        styleOrbNetwork       = findViewById(R.id.styleOrbNetwork)
        colorSwatches         = colorSwatchIds.map { findViewById(it) }

        setupTabs()
        setupProviderSpinner()
        setupColorSwatches()
        setupOrbStyleSelector()
        buildApiKeyFields()
        loadSavedValues()
        setupButtons()
    }

    private fun setupTabs() {
        showTab("cloud")
        tabCloud.setOnClickListener  { showTab("cloud") }
        tabApiKeys.setOnClickListener { showTab("apikeys") }
        tabLocal.setOnClickListener  { showTab("local") }
    }

    private fun showTab(tab: String) {
        panelCloud.visibility   = if (tab == "cloud")   View.VISIBLE else View.GONE
        panelApiKeys.visibility = if (tab == "apikeys") View.VISIBLE else View.GONE
        panelLocal.visibility   = if (tab == "local")   View.VISIBLE else View.GONE

        tabCloud.alpha   = if (tab == "cloud")   1f else 0.45f
        tabApiKeys.alpha = if (tab == "apikeys") 1f else 0.45f
        tabLocal.alpha   = if (tab == "local")   1f else 0.45f

        // Si l'utilisateur clique sur l'onglet Local, passer automatiquement le Provider sur LOCAL_GGUF / ON_DEVICE
        if (tab == "local" && !selectedProvider.isLocal) {
            val localFormat = Prefs.getLocalModelFormat(this)
            val newProvider = if (localFormat == "TASK") Provider.ON_DEVICE else Provider.LOCAL_GGUF
            selectedProvider = newProvider
            providerSpinner.setSelection(Provider.entries.indexOf(newProvider))
            Prefs.save(this, newProvider, "", "", "")
            Toast.makeText(this, "🧠 Mode IA Local activé !", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupProviderSpinner() {
        val currentProvider = Prefs.getProvider(this)
        selectedProvider = currentProvider

        val names = Provider.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        providerSpinner.adapter = adapter
        providerSpinner.setSelection(Provider.entries.indexOf(currentProvider))
        updateCloudSection(currentProvider)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = Provider.entries[position]
                selectedProvider = provider
                updateCloudSection(provider)
                if (!provider.isLocal && !provider.isAuto) {
                    baseUrlInput.setText(provider.defaultBaseUrl)
                    modelInput.setText(provider.defaultModel)
                    apiKeyInput.setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateCloudSection(provider: Provider) {
        autoInfoText.visibility  = if (provider.isAuto) View.VISIBLE else View.GONE
        val showCloud = !provider.isLocal && !provider.isAuto
        baseUrlInput.isEnabled = showCloud
        modelInput.isEnabled   = showCloud
        apiKeyInput.isEnabled  = showCloud && provider.needsApiKey
    }

    private fun buildApiKeyFields() {
        apiKeysContainer.removeAllViews()
        apiKeyFields.clear()

        for (provider in Provider.CLOUD_KEY_PROVIDERS) {
            val label = TextView(this).apply {
                text = "🔑 ${provider.displayName}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 4)
            }
            apiKeysContainer.addView(label)

            val field = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.input_height)
                ).also { it.bottomMargin = 4 }
                background = getDrawable(R.drawable.bg_input)
                setPadding(40, 0, 40, 0)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = "Clé API ${provider.displayName}..."
                setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
            }
            apiKeysContainer.addView(field)
            apiKeyFields[provider] = field
        }
    }

    private fun loadSavedValues() {
        hfTokenInput.setText(Prefs.getHfToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        val initialProvider = Prefs.getProvider(this)
        apiKeyInput.setText(Prefs.getApiKeyFor(this, initialProvider).ifBlank { Prefs.getApiKey(this) })
        updateLocalModelLabel()
    }

    private fun setupButtons() {
        val pickModelButton      = findViewById<TextView>(R.id.pickModelButton)
        val downloadCustomButton = findViewById<TextView>(R.id.downloadCustomButton)
        val saveButton           = findViewById<TextView>(R.id.saveButton)
        val saveApiKeysButton    = findViewById<TextView>(R.id.saveApiKeysButton)

        pickModelButton.setOnClickListener { pickModelLauncher.launch(arrayOf("*/*")) }

        // Modèle Gemma 1B (.task / MediaPipe)
        downloadNoKeyButton.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[1]
            startDownload(entry.url, entry.format, useToken = false)
        }

        // Modèle Llama 3.2 (GGUF)
        btnDownloadLlama.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[3] // Llama 3.2
            startDownload(entry.url, entry.format, useToken = false)
        }

        // Modèle Phi-3 (GGUF)
        btnDownloadPhi.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[2] // Phi-3
            startDownload(entry.url, entry.format, useToken = false)
        }

        // Modèle Mistral (GGUF)
        btnDownloadMistral.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[5] // Mistral
            startDownload(entry.url, entry.format, useToken = false)
        }

        downloadCustomButton.setOnClickListener {
            val url = customModelUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Entrez une URL de modèle", Toast.LENGTH_SHORT).show()
            } else {
                val format = if (url.endsWith(".task", ignoreCase = true)) LocalLlmManager.LocalModelFormat.TASK else LocalLlmManager.LocalModelFormat.GGUF
                startDownload(url, format, useToken = true)
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

            Toast.makeText(this, "✅ Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        }

        saveApiKeysButton.setOnClickListener {
            val keys = apiKeyFields.mapValues { (_, field) -> field.text.toString().trim() }
            Prefs.saveApiKeys(this, keys)
            Toast.makeText(this, "✅ Toutes les clés API enregistrées", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupColorSwatches() {
        selectedAccentColor = Prefs.getAccentColor(this)
        highlightSelectedSwatch()
        for ((index, _) in colorSwatchIds.withIndex()) {
            val swatch = colorSwatches[index]
            swatch.setOnClickListener {
                val bg = swatch.background
                selectedAccentColor = if (bg is android.graphics.drawable.ColorDrawable) bg.color else Prefs.DEFAULT_ACCENT_COLOR
                highlightSelectedSwatch()
            }
        }
    }

    private fun highlightSelectedSwatch() {
        for (swatch in colorSwatches) {
            val bg = swatch.background
            val isSelected = bg is android.graphics.drawable.ColorDrawable && bg.color == selectedAccentColor
            swatch.alpha  = if (isSelected) 1f else 0.45f
            swatch.scaleX = if (isSelected) 1.15f else 1f
            swatch.scaleY = if (isSelected) 1.15f else 1f
        }
    }

    private fun setupOrbStyleSelector() {
        selectedOrbStyle = Prefs.getOrbStyle(this)
        highlightOrbStyle()
        styleOrbPulse.setOnClickListener   { selectedOrbStyle = "PULSE";          highlightOrbStyle() }
        styleOrbNetwork.setOnClickListener { selectedOrbStyle = "NETWORK_SPHERE"; highlightOrbStyle() }
    }

    private fun highlightOrbStyle() {
        val pulseSelected = selectedOrbStyle == "PULSE"
        styleOrbPulse.alpha   = if (pulseSelected) 1f else 0.5f
        styleOrbNetwork.alpha = if (pulseSelected) 0.5f else 1f
    }

    private fun startDownload(url: String, format: LocalLlmManager.LocalModelFormat, useToken: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "Un téléchargement est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        val hfToken = if (useToken) hfTokenInput.text.toString().trim() else ""
        isDownloading = true
        downloadProgressText.text = "⬇ Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            ModelDownloader.download(this@SettingsActivity, url, hfToken, format) { progress ->
                runOnUiThread {
                    when (progress) {
                        is ModelDownloader.Progress.Percent -> downloadProgressText.text = "⬇ Téléchargement… ${progress.value}%"
                        is ModelDownloader.Progress.Done -> {
                            isDownloading = false
                            downloadProgressText.text = "✅ Modèle téléchargé et actif sur le téléphone !"

                            // Activer automatiquement le mode local
                            val targetProvider = if (format == LocalLlmManager.LocalModelFormat.TASK) Provider.ON_DEVICE else Provider.LOCAL_GGUF
                            selectedProvider = targetProvider
                            providerSpinner.setSelection(Provider.entries.indexOf(targetProvider))
                            Prefs.save(this@SettingsActivity, targetProvider, "", "", "")

                            updateLocalModelLabel()
                            Toast.makeText(this@SettingsActivity, "Modèle enregistré et activé ✅", Toast.LENGTH_SHORT).show()
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
        val format = LocalLlmManager.LocalModelFormat.GGUF
        val ext = "gguf"

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val destFile = File(filesDir, "local_model.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 1024 * 1024)
                    }
                }
                Prefs.saveLocalModelPath(this@SettingsActivity, destFile.absolutePath)
                Prefs.saveLocalModelFormat(this@SettingsActivity, format.name)
                LocalLlmManager.unload()

                runOnUiThread {
                    // Activer automatiquement le mode local
                    selectedProvider = Provider.LOCAL_GGUF
                    providerSpinner.setSelection(Provider.entries.indexOf(Provider.LOCAL_GGUF))
                    Prefs.save(this@SettingsActivity, Provider.LOCAL_GGUF, "", "", "")

                    updateLocalModelLabel()
                    Toast.makeText(this@SettingsActivity, "Modèle importé et activé ✅", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this@SettingsActivity, "Échec de l'import : ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateLocalModelLabel() {
        val path = Prefs.getLocalModelPath(this)
        localModelPathText.text = if (path.isBlank()) {
            "Modèle actif : Aucun"
        } else {
            "Modèle actif sur l'appareil : ${File(path).name} (${selectedProvider.displayName})"
        }
    }
}
