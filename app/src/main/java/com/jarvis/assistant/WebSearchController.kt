package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Recherche web générique — utilisé pour les horaires, avis, infos pratiques,
 * ou toute question factuelle sur un lieu/sujet. À NE PAS confondre avec
 * LocationController.openMaps() qui sert uniquement à obtenir un itinéraire.
 */
object WebSearchController {

    fun search(context: Context, query: String): String {
        return try {
            val searchUri = Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))
            val intent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "🔍 Recherche lancée pour « $query »..."
        } catch (e: Exception) {
            "❌ Échec de la recherche : ${e.message}"
        }
    }
}
