package fr.dd.jarvis.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.media.session.MediaSessionManager
import android.net.Uri
import fr.dd.jarvis.service.JarvisNotificationListener
import java.net.URLEncoder

/** Détection et pilotage des sessions multimédias Android, dont YT Music Morphe. */
object Musique {
    const val MORPHE_PACKAGE = "app.morphe.android.apps.youtube.music"
    private val cibles = setOf(MORPHE_PACKAGE, "com.google.android.apps.youtube.music")

    private fun sessions(ctx: Context): List<MediaController> {
        val manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return manager.getActiveSessions(ComponentName(ctx, JarvisNotificationListener::class.java))
            .filter { it.packageName in cibles }
    }

    fun etat(ctx: Context): String {
        val all = sessions(ctx)
        if (all.isEmpty()) return "Aucune session YT Music Morphe active détectée. Vérifie que la musique a été lancée et que l'accès aux notifications de JARVIS est autorisé."
        return all.joinToString("\n") { c ->
            val m = c.metadata
            val s = c.playbackState
            val titre = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Titre inconnu" }
            val artiste = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank { "Artiste inconnu" }
            val album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
            val lecture = when (s?.state) {
                PlaybackState.STATE_PLAYING -> "En lecture"
                PlaybackState.STATE_PAUSED -> "En pause"
                PlaybackState.STATE_BUFFERING -> "Chargement"
                PlaybackState.STATE_NONE, null -> "État indisponible"
                else -> "Arrêtée / inactive"
            }
            "YT Music Morphe — $lecture\n🎵 $titre — $artiste" + if (album.isNotBlank()) "\nAlbum : $album" else ""
        }
    }

    fun commander(ctx: Context, action: String): String {
        val controller = sessions(ctx).firstOrNull()
            ?: return "Aucune session YT Music Morphe active. Lance d'abord un morceau dans Morph et vérifie l'accès aux notifications de JARVIS."
        val controls = controller.transportControls
        when (action.lowercase()) {
            "lecture", "play", "reprendre" -> controls.play()
            "pause", "stop", "arreter", "arrêter" -> controls.pause()
            "suivant", "next" -> controls.skipToNext()
            "precedent", "précédent", "previous" -> controls.skipToPrevious()
            else -> return "Commande inconnue : $action. Utilise lecture, pause, suivant ou precedent."
        }
        return "Commande « $action » envoyée à YT Music Morphe. L'état réel sera vérifié à la prochaine lecture de la session."
    }

    suspend fun rechercher(ctx: Context, requete: String): String {
        if (requete.isBlank()) return "Précise le titre ou l'artiste à rechercher."
        val url = "https://music.youtube.com/search?q=" + URLEncoder.encode(requete, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(MORPHE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            ctx.startActivity(intent)
            kotlinx.coroutines.delay(2800)
            val access = fr.dd.jarvis.service.JarvisAccessibilityService.instance
                ?: return "Recherche ouverte dans YT Music Morphe, mais la sélection automatique nécessite d'activer le service Accessibilité de JARVIS. Aucun morceau n'a été lancé."
            val selection = access.clickBestMatch(requete)
            if (!selection.startsWith("Résultat sélectionné :")) return "Recherche ouverte dans YT Music Morphe. " + selection
            kotlinx.coroutines.delay(1400)
            val verification = etat(ctx)
            if (verification.contains("En lecture")) selection + "\n✅ Lecture détectée :\n" + verification
            else selection + "\nLe résultat a été sélectionné, mais la lecture n'est pas confirmée. État : " + verification
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(fallback)
                "Lien de recherche ouvert. Android peut proposer le navigateur ou une application musicale."
            } catch (e: Exception) {
                "Impossible d'ouvrir la recherche musicale : ${e.message ?: "application indisponible"}"
            }
        }
    }
}
