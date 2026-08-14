package com.jarvis.ai.core.obsidian

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Synchronise périodiquement le cache Room (conversations) vers le vault Obsidian,
 * pour que l'historique reste consultable/éditable directement dans Obsidian.
 * TODO: injecter ConversationDao, lire les nouveaux messages, appeler
 * ObsidianVaultManager.writeNote("Jarvis/Conversations", date, markdown).
 */
@HiltWorker
class ObsidianSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vaultManager: ObsidianVaultManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return Result.success()
    }
}
