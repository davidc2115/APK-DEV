package com.jarvis.assistant

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

    // ── Vues globales ─────────────────────────────────────────────────────────
    private lateinit var providerSpinner: Spinner
    private lateinit var tabCloud: TextView
    private lateinit var tabApiKeys: TextView
    private lateinit var tabLocal: TextView
    private lateinit var panelCloud: View
    private lateinit var panelApiKeys: View
    private lateinit var panelLocal: View

    // ── Onglet Cloud (provider actif) ─────────────────────────────────────────
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var autoInfoText: View

    // ── Onglet Clés API (multi-provider) ──────────────────────────────────────
    private lateinit var apiKeysContainer: LinearLayout

    // ── Onglet Local ──────────────────────────────────────────────────────────
    private lateinit var hfTokenInput: EditText
    private lateinit var customModelUrlInput: EditText
    private lateinit var localModelPathText: TextView
    private lateinit var downloadProgressText: TextView
    private lateinit var modelFormatSpinner: Spinner

    // ── Style / couleur ───────────────────────────────────────────────────────
    private lateinit var styleOrbPulse: TextView
    private lateinit var styleOrbNetwork: TextView
    private val colorSwatchIds = listOf(
        R.id.colorCyan, R.id.colorRed, R.id.colorBlue,
        R.id.colorPurple, R.id.colorGold, R.id.colorGreen
    )
    private lateinit var colorSwatches: List<View>

    // ── État interne ──────────────────────────────────────────────────────────
    private var selectedProvider: Provider = Provider.GROQ
    private var selectedAccentColor: Int = Prefs.DEFAULT_ACCENT_COLOR
    private var selectedOrbStyle: String = "PULSE"
    private var isDownloading = false

    /** Map Provider → EditText (onglet Clés API) */
    private val apiKeyFields = mutableMapOf<Provider, EditText>()

    private val pickModelLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importModelFile(uri)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cycle de vie
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Vues principales
        providerSpinner       = findViewById(R.id.providerSpinner)
        tabCloud              = findViewById(R.id.tabCloud)
        tabApiKeys            = findViewById(R.id.tabApiKeys)
        tabLocal              = findViewById(R.id.tabLocal)
        panelCloud            = findViewById(R.id.panelCloud)
        panelApiKeys          = findViewById(R.id.panelApiKeys)
        panelLocal            = findViewById(R.id.panelLocal)

        // Cloud
        baseUrlInput          = findViewById(R.id.baseUrlInput)
        modelInput            = findViewById(R.id.modelInput)
        apiKeyInput           = findViewById(R.id.apiKeyInput)
        autoInfoText          = findViewById(R.id.autoInfoText)

        // Clés API
        apiKeysContainer      = findViewById(R.id.apiKeysContainer)

        // Local
        hfTokenInput          = findViewById(R.id.hfTokenInput)
        customModelUrlInput   = findViewById(R.id.customModelUrlInput)
        localModelPathText    = findViewById(R.id.localModelPathText)
        downloadProgressText  = findViewById(R.id.downloadProgressText)
        modelFormatSpinner    = findViewById(R.id.modelFormatSpinner)

        // Style
        styleOrbPulse         = findViewById(R.id.styleOrbPulse)
        styleOrbNetwork       = findViewById(R.id.styleOrbNetwork)
        colorSwatches         = colorSwatchIds.map { findViewById(it) }

        setupTabs()
        setupProviderSpinner()
        setupModelFormatSpinner()
        setupColorSwatches()
        setupOrbStyleSelector()
        buildApiKeyFields()
        loadSavedValues()
        setupButtons()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Onglets
    // ─────────────────────────────────────────────────────────────────────────

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

        val activeAlpha = 1f
        val inactiveAlpha = 0.45f
        tabCloud.alpha   = if (tab == "cloud")   activeAlpha else inactiveAlpha
        tabApiKeys.alpha = if (tab == "apikeys") activeAlpha else inactiveAlpha
        tabLocal.alpha   = if (tab == "local")   activeAlpha else inactiveAlpha
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Spinner provider actif
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Spinner format modèle local
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupModelFormatSpinner() {
        val formats = listOf("TASK (.task — MediaPipe)", "GGUF (.gguf — llama.cpp)", "ONNX (.onnx — ONNX Runtime)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, formats)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelFormatSpinner.adapter = adapter

        val saved = Prefs.getLocalModelFormat(this)
        modelFormatSpinner.setSelection(when (saved) { "GGUF" -> 1; "ONNX" -> 2; else -> 0 })
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Onglet Clés API — construction dynamique
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildApiKeyFields() {
        apiKeysContainer.removeAllViews()
        apiKeyFields.clear()

        for (provider in Provider.CLOUD_KEY_PROVIDERS) {
            // Label
            val label = TextView(this).apply {
                text = "🔑 ${provider.displayName}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 4)
            }
            apiKeysContainer.addView(label)

            // Champ de saisie
            val field = EditText(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.input_height)
                ).also { it.bottomMargin = 4 }
                background = getDrawable(R.drawable.bg_input)
                setPadding(40, 0, 40, 0)
                setTextColor(getColor(R.color.text_primary))
                setHintTextColor(getColor(R.color.text_secondary))
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                hint = when (provider) {
                    Provider.GROQ        -> "gsk_..."
                    Provider.OPENAI      -> "sk-..."
                    Provider.CLAUDE      -> "sk-ant-..."
                    Provider.GEMINI      -> "AIza..."
                    Provider.MISTRAL     -> "..."
                    Provider.DEEPSEEK    -> "sk-..."
                    Provider.PERPLEXITY  -> "pplx-..."
                    Provider.TOGETHER    -> "..."
                    Provider.OPENROUTER  -> "sk-or-..."
                    Provider.SERPAPI     -> "..."
                    else                 -> "Clé API..."
                }
                // Pré-remplir avec la clé sauvegardée
                setText(Prefs.getApiKeyFor(this@SettingsActivity, provider))
            }
            apiKeysContainer.addView(field)
            apiKeyFields[provider] = field
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Chargement des valeurs sauvegardées
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadSavedValues() {
        hfTokenInput.setText(Prefs.getHfToken(this))
        baseUrlInput.setText(Prefs.getBaseUrl(this))
        modelInput.setText(Prefs.getModel(this))
        val initialProvider = Prefs.getProvider(this)
        apiKeyInput.setText(Prefs.getApiKeyFor(this, initialProvider).ifBlank { Prefs.getApiKey(this) })
        updateLocalModelLabel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Boutons
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        val pickModelButton          = findViewById<TextView>(R.id.pickModelButton)
        val downloadRecommendedButton = findViewById<TextView>(R.id.downloadRecommendedButton)
        val downloadNoKeyButton      = findViewById<TextView>(R.id.downloadNoKeyButton)
        val downloadCustomButton     = findViewById<TextView>(R.id.downloadCustomButton)
        val saveButton               = findViewById<TextView>(R.id.saveButton)
        val saveApiKeysButton        = findViewById<TextView>(R.id.saveApiKeysButton)

        pickModelButton.setOnClickListener { pickModelLauncher.launch(arrayOf("*/*")) }

        downloadRecommendedButton.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[0]
            startDownload(entry.url, entry.format, useToken = true)
        }

        downloadNoKeyButton.setOnClickListener {
            val entry = ModelDownloader.MODEL_CATALOG[1]
            startDownload(entry.url, entry.format, useToken = false)
        }

        downloadCustomButton.setOnClickListener {
            val url = customModelUrlInput.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "Colle d'abord une URL de modèle", Toast.LENGTH_SHORT).show()
            } else {
                val formatIndex = modelFormatSpinner.selectedItemPosition
                val format = when (formatIndex) {
                    1 -> LocalLlmManager.LocalModelFormat.GGUF
                    2 -> LocalLlmManager.LocalModelFormat.ONNX
                    else -> LocalLlmManager.LocalModelFormat.TASK
                }
                startDownload(url, format, useToken = true)
            }
        }

        // Enregistrer le provider actif + sa config
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

            // Sauvegarder aussi le format du modèle local
            val formatIndex = modelFormatSpinner.selectedItemPosition
            val formatKey = when (formatIndex) { 1 -> "GGUF"; 2 -> "ONNX"; else -> "TASK" }
            Prefs.saveLocalModelFormat(this, formatKey)

            Toast.makeText(this, "✅ Paramètres enregistrés", Toast.LENGTH_SHORT).show()
        }

        // Enregistrer toutes les clés API individuelles
        saveApiKeysButton.setOnClickListener {
            val keys = apiKeyFields.mapValues { (_, field) -> field.text.toString().trim() }
            Prefs.saveApiKeys(this, keys)
            Toast.makeText(this, "✅ Toutes les clés API enregistrées", Toast.LENGTH_SHORT).show()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Couleurs & style
    // ─────────────────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────────────────
    // Téléchargement de modèle
    // ─────────────────────────────────────────────────────────────────────────

    private fun startDownload(
        url: String,
        format: LocalLlmManager.LocalModelFormat,
        useToken: Boolean
    ) {
        if (isDownloading) {
            Toast.makeText(this, "Un téléchargement est déjà en cours…", Toast.LENGTH_SHORT).show()
            return
        }
        val hfToken = if (useToken) hfTokenInput.text.toString().trim() else ""
        isDownloading = true
        downloadProgressText.text = "Démarrage du téléchargement…"

        CoroutineScope(Dispatchers.Main).launch {
            ModelDownloader.download(this@SettingsActivity, url, hfToken, format) { progress ->
                runOnUiThread {
                    when (progress) {
                        is ModelDownloader.Progress.Percent ->
                            downloadProgressText.text = "⬇ Téléchargement… ${progress.value}%"
                        is ModelDownloader.Progress.Done -> {
                            isDownloading = false
                            downloadProgressText.text = "✅ Modèle prêt !"
                            updateLocalModelLabel()
                            // Sync le spinner de format
                            val idx = when (format) {
                                LocalLlmManager.LocalModelFormat.GGUF -> 1
                                LocalLlmManager.LocalModelFormat.ONNX -> 2
                                LocalLlmManager.LocalModelFormat.TASK -> 0
                            }
                            modelFormatSpinner.setSelection(idx)
                            Toast.makeText(this@SettingsActivity, "Modèle téléchargé ✅", Toast.LENGTH_SHORT).show()
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

    // ─────────────────────────────────────────────────────────────────────────
    // Import de fichier local
    // ─────────────────────────────────────────────────────────────────────────

    private fun importModelFile(uri: Uri) {
        Toast.makeText(this, "Import du modèle en cours…", Toast.LENGTH_LONG).show()
        val formatIndex = modelFormatSpinner.selectedItemPosition
        val format = when (formatIndex) {
            1 -> LocalLlmManager.LocalModelFormat.GGUF
            2 -> LocalLlmManager.LocalModelFormat.ONNX
            else -> LocalLlmManager.LocalModelFormat.TASK
        }
        val ext = when (format) { LocalLlmManager.LocalModelFormat.GGUF -> "gguf"; LocalLlmManager.LocalModelFormat.ONNX -> "onnx"; else -> "task" }

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
                    updateLocalModelLabel()
                    Toast.makeText(this@SettingsActivity, "Modèle importé ✅", Toast.LENGTH_SHORT).show()
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
        val format = Prefs.getLocalModelFormat(this)
        localModelPathText.text = if (path.isBlank()) {
            "Aucun modèle importé"
        } else {
            "[$format] ${File(path).name}"
        }
    }
}
