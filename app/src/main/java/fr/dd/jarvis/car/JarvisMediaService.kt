package fr.dd.jarvis.car

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.service.media.MediaBrowserService
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.service.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JARVIS dans Android Auto en tant qu'appli « média ». Depuis Android Auto 17, une appli installée hors Play Store
 * (« Sources inconnues ») n'est acceptée dans le lanceur QUE si c'est une appli média : les applis à modèles (écrans
 * de la Car App Library) restent cachées. Ce service fait donc apparaître JARVIS dans le lanceur et dans les applis
 * média de la voiture, avec :
 *  - 🎙️ la recherche vocale de la voiture (« micro » de l'appli, ou « Ok Google, … sur JARVIS ») → la phrase va à
 *    JARVIS, qui répond à voix haute ;
 *  - les raccourcis de la voiture (point de la journée, prochain rendez-vous, mails, SMS, météo…) ;
 *  - relire la dernière réponse, reprendre la musique de YT Music Morphe.
 */
class JarvisMediaService : MediaBrowserService() {
    companion object {
        private const val RACINE = "racine"
        private const val PARLER = "parler"
        private const val RELIRE = "relire"
        private const val MUSIQUE = "musique"
        private const val RACCOURCI = "raccourci:"
        private const val RECHERCHE = "demande:"
    }

    private lateinit var session: MediaSession
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as JarvisApp
    @Volatile private var derniere = ""

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "JARVIS").apply {
            setCallback(Rappels())
            setPlaybackState(etat(PlaybackState.STATE_STOPPED))
            setMetadata(meta("JARVIS", "Touche 🎙️ ou un raccourci : JARVIS répond à voix haute."))
            isActive = true
        }
        sessionToken = session.sessionToken
    }

    override fun onDestroy() {
        portee.cancel()
        runCatching { session.release() }
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(RACINE, Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)        // bouton micro / recherche dans la voiture
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)    // liste (pas de grille)
        })

    private fun element(id: String, titre: String, sousTitre: String = "", jouable: Boolean = true): MediaBrowser.MediaItem =
        MediaBrowser.MediaItem(MediaDescription.Builder().setMediaId(id).setTitle(titre).setSubtitle(sousTitre)
            .setIconUri(Uri.parse("android.resource://$packageName/drawable/ic_launcher")).build(),
            if (jouable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        if (parentId != RACINE) { result.sendResult(mutableListOf()); return }
        val l = mutableListOf(
            element(PARLER, "🎙️ Parler à JARVIS", "Utilise le micro de la voiture (recherche vocale) : ta phrase va à JARVIS"),
        )
        RaccourcisVoiture.liste(app).forEachIndexed { i, r -> l += element(RACCOURCI + i, "⚡ $r") }
        l += element(RELIRE, "🔁 Relire la dernière réponse", derniere.take(80))
        l += element(MUSIQUE, "🎵 Reprendre la musique", "YT Music Morphe")
        result.sendResult(l)
    }

    private fun etat(e: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
        .setState(e, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()

    private fun meta(titre: String, texte: String) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, titre)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, texte.take(200))
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titre)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, texte.take(200)).build()

    /** Envoie [demande] à JARVIS (comme dans la voiture : actions sensibles refusées), affiche et lit la réponse. */
    private fun demander(demande: String) {
        if (demande.isBlank()) return
        session.setMetadata(meta("🎙️ $demande", "JARVIS réfléchit…"))
        session.setPlaybackState(etat(PlaybackState.STATE_BUFFERING))
        portee.launch {
            val question = ChatMsg(true, demande)
            CarBridge.ajouter(question); app.chatAjouter(question)
            val rep = withContext(Dispatchers.IO) {
                try { Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { } }
                catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val propre = rep.replace(Regex("!?\\[[^\\]]*]\\([^)]*\\)"), "").replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            val reponse = ChatMsg(false, propre)
            CarBridge.ajouter(reponse); app.chatAjouter(reponse)
            derniere = propre
            lire(demande, propre)
            notifyChildrenChanged(RACINE)
        }
    }

    private fun lire(titre: String, texte: String) {
        session.setMetadata(meta(titre, texte))
        session.setPlaybackState(etat(PlaybackState.STATE_PLAYING))
        VoixVoiture.parler(this, texte.take(1500)) { session.setPlaybackState(etat(PlaybackState.STATE_PAUSED)) }
    }

    private inner class Rappels : MediaSession.Callback() {
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val q = query.orEmpty().trim()
            if (q.isBlank()) lire("JARVIS", "Je t'écoute : dis « Ok Google, demande à JARVIS » suivi de ta question, ou touche le micro de l'appli.")
            else demander(q)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId.orEmpty()
            when {
                id == PARLER -> lire("🎙️ JARVIS", "Touche la loupe ou le micro de l'appli et dis ta demande : je te réponds à voix haute.")
                id == RELIRE -> if (derniere.isNotBlank()) lire("🔁 Dernière réponse", derniere) else lire("JARVIS", "Pas encore de réponse à relire.")
                id == MUSIQUE -> {
                    val c = fr.dd.jarvis.device.MusiqueBibliotheque.controleur(this@JarvisMediaService, fr.dd.jarvis.device.Musique.MORPHE_PACKAGE)
                    if (c != null) c.transportControls.play() else lire("🎵 Musique", "Aucune musique de YT Music Morphe à reprendre : demande-moi un titre.")
                }
                id.startsWith(RACCOURCI) -> RaccourcisVoiture.liste(app).getOrNull(id.removePrefix(RACCOURCI).toIntOrNull() ?: -1)?.let { demander(it) }
                id.startsWith(RECHERCHE) -> demander(id.removePrefix(RECHERCHE))
            }
        }

        override fun onPlay() { if (derniere.isNotBlank()) lire("🔁 Dernière réponse", derniere) }
        override fun onPause() { VoixVoiture.taire(); session.setPlaybackState(etat(PlaybackState.STATE_PAUSED)) }
        override fun onStop() { VoixVoiture.taire(); session.setPlaybackState(etat(PlaybackState.STATE_STOPPED)) }
    }
}
