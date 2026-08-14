package com.jarvis.ai.core.coding

import com.jarvis.ai.data.settings.SettingsDataStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Crée un dépôt GitHub distant puis y pousse le projet généré par CodeAgentModule. */
class GitHubPublisher @Inject constructor(
    private val client: OkHttpClient,
    private val settings: SettingsDataStore,
    private val gitManager: GitManager
) {
    suspend fun createAndPush(projectDir: File, repoName: String, private_: Boolean = true): String {
        val token = settings.getApiKey("github")
        val body = JSONObject().put("name", repoName).put("private", private_)
        val request = Request.Builder()
            .url("https://api.github.com/user/repos")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val cloneUrl: String
        client.newCall(request).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("GitHub API error ${resp.code}: ${json.optString("message")}")
            cloneUrl = json.getString("clone_url")
        }
        gitManager.push(projectDir, cloneUrl, token)
        return cloneUrl
    }
}
