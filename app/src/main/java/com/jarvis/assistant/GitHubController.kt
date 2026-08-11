package com.jarvis.assistant

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Permet à JARVIS de créer/modifier des projets sur GitHub et de committer,
 * pousser, ouvrir des pull requests — via l'API REST GitHub (pas de git
 * embarqué : plus simple et fiable sur mobile, et c'est ce que fait GitHub
 * lui-même en coulisses pour toute édition faite depuis son site web).
 *
 * Nécessite un jeton personnel (⚙ Paramètres → Clés API → Codage GitHub).
 */
object GitHubController {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val NO_TOKEN = "❌ Aucun jeton GitHub configuré. Ajoute-le dans ⚙ Paramètres → onglet « Clés API » → section Codage."

    private fun authBuilder(context: Context, url: String): Request.Builder? {
        val token = Prefs.getGithubToken(context)
        if (token.isBlank()) return null
        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
    }

    fun listRepos(context: Context): String {
        val builder = authBuilder(context, "https://api.github.com/user/repos?sort=updated&per_page=15") ?: return NO_TOKEN
        return try {
            client.newCall(builder.get().build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Erreur GitHub (${resp.code}) : $body"
                val arr = JSONArray(body)
                if (arr.length() == 0) return "Aucun dépôt trouvé sur ce compte."
                val sb = StringBuilder("📦 Dépôts récents :\n\n")
                for (i in 0 until arr.length()) {
                    val repo = arr.getJSONObject(i)
                    val visibility = if (repo.optBoolean("private")) "privé" else "public"
                    sb.append("• ${repo.optString("full_name")} ($visibility)\n")
                }
                sb.toString().trim()
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun createRepo(context: Context, name: String, description: String, isPrivate: Boolean): String {
        val builder = authBuilder(context, "https://api.github.com/user/repos") ?: return NO_TOKEN
        return try {
            val body = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("private", isPrivate)
                .put("auto_init", true)
                .toString()
                .toRequestBody(JSON)
            client.newCall(builder.post(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création du dépôt (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                "✅ Dépôt créé : ${json.optString("html_url")}"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    /** Crée un fichier, ou le met à jour s'il existe déjà (même endpoint GitHub pour les deux cas). */
    fun createOrUpdateFile(
        context: Context,
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String
    ): String {
        if (Prefs.getGithubToken(context).isBlank()) return NO_TOKEN

        // Récupère le sha existant si le fichier existe déjà (requis par l'API pour une mise à jour)
        var existingSha: String? = null
        try {
            val getBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch")
            if (getBuilder != null) {
                client.newCall(getBuilder.get().build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        existingSha = JSONObject(resp.body?.string() ?: "").optString("sha").ifBlank { null }
                    }
                }
            }
        } catch (_: Exception) {
            // Le fichier n'existe probablement pas encore — on continue en création.
        }

        val putBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path") ?: return NO_TOKEN
        return try {
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val bodyJson = JSONObject()
                .put("message", commitMessage)
                .put("content", encoded)
                .put("branch", branch)
            existingSha?.let { bodyJson.put("sha", it) }

            val body = bodyJson.toString().toRequestBody(JSON)
            client.newCall(putBuilder.put(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de l'écriture du fichier (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                val commitUrl = json.optJSONObject("commit")?.optString("html_url") ?: ""
                val verb = if (existingSha != null) "mis à jour" else "créé"
                "✅ Fichier $path $verb dans $owner/$repo.\n🔗 $commitUrl"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun readFile(context: Context, owner: String, repo: String, path: String, branch: String): String {
        val builder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch") ?: return NO_TOKEN
        return try {
            client.newCall(builder.get().build()).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Fichier introuvable (${resp.code}) : $body"
                val json = JSONObject(body)
                val content = json.optString("content", "")
                String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun createBranch(context: Context, owner: String, repo: String, newBranch: String, fromBranch: String): String {
        val getRefBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/ref/heads/$fromBranch") ?: return NO_TOKEN
        val baseSha = try {
            client.newCall(getRefBuilder.get().build()).execute().use { resp ->
                if (!resp.isSuccessful) return "❌ Branche de base « $fromBranch » introuvable : ${resp.body?.string()}"
                JSONObject(resp.body?.string() ?: "").getJSONObject("object").getString("sha")
            }
        } catch (e: Exception) {
            return "❌ Erreur réseau : ${e.message}"
        }

        val postBuilder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/git/refs") ?: return NO_TOKEN
        return try {
            val body = JSONObject()
                .put("ref", "refs/heads/$newBranch")
                .put("sha", baseSha)
                .toString()
                .toRequestBody(JSON)
            client.newCall(postBuilder.post(body).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création de branche (${resp.code}) : $respBody"
                "✅ Branche « $newBranch » créée à partir de « $fromBranch »."
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }

    fun createPullRequest(
        context: Context,
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String,
        body: String
    ): String {
        val builder = authBuilder(context, "https://api.github.com/repos/$owner/$repo/pulls") ?: return NO_TOKEN
        return try {
            val bodyJson = JSONObject()
                .put("title", title)
                .put("head", head)
                .put("base", base)
                .put("body", body)
                .toString()
                .toRequestBody(JSON)
            client.newCall(builder.post(bodyJson).build()).execute().use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return "❌ Échec de la création de la pull request (${resp.code}) : $respBody"
                val json = JSONObject(respBody)
                "✅ Pull request créée : ${json.optString("html_url")}"
            }
        } catch (e: Exception) {
            "❌ Erreur réseau : ${e.message}"
        }
    }
}
