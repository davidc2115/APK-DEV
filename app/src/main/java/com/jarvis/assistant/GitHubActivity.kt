package com.jarvis.assistant

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GitHubActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_github)

        BottomNav.setup(this, NavDestination.GITHUB)
        EdgeToEdgeHelper.applyTopInset(findViewById(R.id.rootLayout))
        EdgeToEdgeHelper.applyBottomInset(findViewById(R.id.bottomNavRoot))

        val githubTokenInput = findViewById<EditText>(R.id.githubTokenInput)
        val saveGithubTokenButton = findViewById<TextView>(R.id.saveGithubTokenButton)
        val listReposButton = findViewById<TextView>(R.id.listReposButton)
        val reposResultText = findViewById<TextView>(R.id.reposResultText)
        val newRepoNameInput = findViewById<EditText>(R.id.newRepoNameInput)
        val newRepoDescInput = findViewById<EditText>(R.id.newRepoDescInput)
        val createRepoButton = findViewById<TextView>(R.id.createRepoButton)

        githubTokenInput.setText(Prefs.getGithubToken(this))

        saveGithubTokenButton.setOnClickListener {
            Prefs.saveGithubToken(this, githubTokenInput.text.toString().trim())
            Toast.makeText(this, "✅ Jeton GitHub enregistré", Toast.LENGTH_SHORT).show()
        }

        listReposButton.setOnClickListener {
            reposResultText.visibility = View.VISIBLE
            reposResultText.text = "Chargement…"
            CoroutineScope(Dispatchers.Main).launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    GitHubController.listRepos(this@GitHubActivity)
                }
                reposResultText.text = result
            }
        }

        createRepoButton.setOnClickListener {
            val name = newRepoNameInput.text.toString().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Donne un nom au dépôt d'abord", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val description = newRepoDescInput.text.toString().trim()
            createRepoButton.text = "Création en cours…"

            CoroutineScope(Dispatchers.Main).launch {
                val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    GitHubController.createRepo(this@GitHubActivity, name, description, false)
                }
                createRepoButton.text = "＋ CRÉER LE DÉPÔT (PUBLIC)"
                Toast.makeText(this@GitHubActivity, result, Toast.LENGTH_LONG).show()
                if (result.startsWith("✅")) {
                    newRepoNameInput.text.clear()
                    newRepoDescInput.text.clear()
                }
            }
        }
    }
}
