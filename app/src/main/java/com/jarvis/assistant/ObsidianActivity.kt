package com.jarvis.assistant

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ObsidianActivity : AppCompatActivity() {

    private lateinit var vaultPathText: TextView
    private lateinit var resultText: TextView
    private lateinit var noteInput: EditText
    private lateinit var contentInput: EditText
    private lateinit var folderInput: EditText
    private lateinit var searchInput: EditText

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = uri.path?.replace("/tree/primary:", "/sdcard/") ?: return@registerForActivityResult
            Prefs.saveObsidianVaultPath(this, path)
            vaultPathText.text = "📂 Vault : $path"
            Toast.makeText(this, "Vault déplacé vers : $path", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_obsidian)

        vaultPathText  = findViewById(R.id.obsidianVaultPathText)
        resultText     = findViewById(R.id.obsidianResultText)
        noteInput      = findViewById(R.id.obsidianNoteInput)
        contentInput   = findViewById(R.id.obsidianContentInput)
        folderInput    = findViewById(R.id.obsidianFolderInput)
        searchInput    = findViewById(R.id.obsidianSearchInput)

        // Afficher chemin du vault
        val root = ObsidianController.getVaultRoot(this)
        vaultPathText.text = "📂 Vault : ${root.absolutePath}"

        // Init automatique si vault n'existe pas
        if (!root.exists()) {
            CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) { ObsidianController.initVault(this@ObsidianActivity) }
                resultText.text = result
                vaultPathText.text = "📂 Vault : ${ObsidianController.getVaultRoot(this@ObsidianActivity).absolutePath}"
            }
        } else {
            // Afficher stats au démarrage
            CoroutineScope(Dispatchers.Main).launch {
                resultText.text = withContext(Dispatchers.IO) { ObsidianController.getVaultStats(this@ObsidianActivity) }
            }
        }

        setupButtons()
    }

    private fun setupButtons() {

        // ── Créer une note ──────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnCreateNote).setOnClickListener {
            val title   = noteInput.text.toString().trim()
            val content = contentInput.text.toString().trim()
            val folder  = folderInput.text.toString().trim().ifBlank { "Notes Rapides" }

            if (title.isBlank()) {
                Toast.makeText(this, "Entrez un titre pour la note", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runAsync { ObsidianController.createNote(this, title, content, folder) }
            noteInput.text.clear()
            contentInput.text.clear()
        }

        // ── Note du jour ────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnDailyNote).setOnClickListener {
            val extra = contentInput.text.toString().trim()
            runAsync { ObsidianController.createDailyNote(this, extra) }
        }

        // ── Recherche ───────────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnSearchNotes).setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isBlank()) {
                Toast.makeText(this, "Entrez un terme de recherche", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runAsync { ObsidianController.searchNotes(this, query) }
        }

        // ── Lister les notes ────────────────────────────────────────────────
        findViewById<TextView>(R.id.btnListNotes).setOnClickListener {
            runAsync { ObsidianController.listNotes(this) }
        }

        // ── Ouvrir dans Obsidian ────────────────────────────────────────────
        findViewById<TextView>(R.id.btnOpenObsidian).setOnClickListener {
            runAsync { ObsidianController.openInObsidian(this, "") }
        }

        // ── Init / Réparer Vault ────────────────────────────────────────────
        findViewById<TextView>(R.id.btnInitVault).setOnClickListener {
            runAsync {
                val result = ObsidianController.initVault(this)
                val path   = ObsidianController.getVaultRoot(this).absolutePath
                runOnUiThread { vaultPathText.text = "📂 Vault : $path" }
                result
            }
        }

        // ── Changer dossier vault ───────────────────────────────────────────
        findViewById<TextView>(R.id.btnChangeVaultPath).setOnClickListener {
            folderPickerLauncher.launch(null)
        }
    }

    /** Lance une coroutine IO et affiche le résultat dans resultText. */
    private fun runAsync(block: suspend () -> String) {
        resultText.text = "⏳ En cours…"
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { block() }
            resultText.text = result
        }
    }
}
