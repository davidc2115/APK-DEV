package com.jarvis.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Écran Réglages : chaque section correspond à un module (voir docs/ARCHITECTURE.md).
 * Toutes les valeurs sont lues/écrites dans SettingsDataStore (chiffré), rien n'est en dur.
 */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Réglages Jarvis", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

        Text("Fournisseurs IA")
        ApiKeyField(viewModel, "claude", "Clé Claude (Anthropic)")
        ApiKeyField(viewModel, "openai", "Clé OpenAI (GPT / DALL·E)")
        ApiKeyField(viewModel, "gemini", "Clé Google Gemini")
        ApiKeyField(viewModel, "groq", "Clé Groq")
        ApiKeyField(viewModel, "perplexity", "Clé Perplexity")
        ApiKeyField(viewModel, "serpapi", "Clé SerpAPI (recherche web)")
        ApiKeyField(viewModel, "github", "Token GitHub (module codage)")

        Divider()
        Text("IA serveur local (Ollama, PC/NAS)")
        UrlField(
            value = viewModel.getOllamaBaseUrl(),
            label = "URL Ollama (ex: http://192.168.1.50:11434)",
            onSave = viewModel::setOllamaBaseUrl
        )

        Divider()
        Text("Home Assistant")
        UrlField(value = viewModel.getHomeAssistantUrl(), label = "URL Home Assistant", onSave = viewModel::setHomeAssistantUrl)
        ApiKeyField(viewModel, "ha_token_display_only", "Long-Lived Access Token")

        Divider()
        Text("Freebox")
        UrlField(value = viewModel.getFreeboxUrl(), label = "URL Freebox (locale ou .fbxos.fr)", onSave = viewModel::setFreeboxUrl)

        Divider()
        Text("Vault Obsidian : sélection du dossier via le bouton dédié (SAF) — à ajouter à l'écran de lancement.")
    }
}

@Composable
private fun ApiKeyField(viewModel: SettingsViewModel, provider: String, label: String) {
    var value by remember { mutableStateOf(viewModel.getApiKey(provider)) }
    OutlinedTextField(
        value = value,
        onValueChange = {
            value = it
            viewModel.setApiKey(provider, it)
        },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun UrlField(value: String, label: String, onSave: (String) -> Unit) {
    var current by remember { mutableStateOf(value) }
    OutlinedTextField(
        value = current,
        onValueChange = { current = it; onSave(it) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth()
    )
}
