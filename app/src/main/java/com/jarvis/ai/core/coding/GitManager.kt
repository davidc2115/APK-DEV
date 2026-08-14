package com.jarvis.ai.core.coding

import java.io.File
import javax.inject.Inject

/**
 * Gestion Git locale sans terminal, via JGit (org.eclipse.jgit:org.eclipse.jgit).
 * TODO Phase 6 : ajouter la dépendance JGit et implémenter réellement init/add/commit/push
 * (JGit fonctionne bien sur Android, contrairement à un binaire git natif).
 */
class GitManager @Inject constructor() {
    fun initIfNeeded(projectDir: File) {
        // TODO: Git.init().setDirectory(projectDir).call() si .git absent
    }

    fun commitAll(projectDir: File, message: String) {
        // TODO: git.add().addFilepattern(".").call() ; git.commit().setMessage(message).call()
    }

    fun push(projectDir: File, remoteUrl: String, token: String) {
        // TODO: git.push().setRemote(remoteUrl)
        //         .setCredentialsProvider(UsernamePasswordCredentialsProvider(token, "")).call()
    }
}
