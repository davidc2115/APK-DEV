package fr.dd.jarvis.device

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import fr.dd.jarvis.service.JarvisNotificationListener

/**
 * Contrôle de la musique en réflexe, sans IA ni Hermès : « reprends la musique », « pause », « chanson suivante »,
 * « reviens à la précédente », « recommence le morceau », « monte le son », « c'est quoi cette chanson ? »…
 *
 * La commande vise directement la session de l'appli de musique (Spotify, YT Music, Deezer…) — jamais par une touche
 * média « à l'aveugle » : en voiture, celle-ci arrivait à la session de JARVIS lui-même (Android Auto), qui ouvrait le micro
 * au lieu de relancer la musique.
 */
object CommandeMedia {
    enum class Action { PLAY, PAUSE, NEXT, PREVIOUS, RESTART, VOLUME_UP, VOLUME_DOWN, INFO }

    private val PREFIXE = Regex("^(?:(?:jarvis|ok|okay|hey|he|eh|dis|dis moi|bon|alors|allez|vas y|s il te plait|stp|svp)\\s+)+")
    private val POLITESSE = Regex("^(?:(?:est ce que )?(?:tu peux|tu pourrais|peux tu|pourrais tu|tu veux bien|je veux que tu|j aimerais que tu|je voudrais que tu)\\s+)")
    private val FIN = Regex("(?:\\s+(?:s il te plait|stp|svp|merci|jarvis|maintenant|tout de suite|steuplait))+$")
    private val AUTRE_APPAREIL = Regex("\\b(tele|tv|television|televiseur|enceintes?|sonos|chaine|ampli|barre de son|home ?assistant|chromecast|ordinateur|pc|salon|chambre|cuisine|bureau|garage|alarme|reveil|sonnerie|video|film|serie|podcast de)\\b")
    private const val MUS = "(?:la musique|ma musique|la lecture|ma lecture|l ecoute|la chanson|ma chanson|le morceau|mon morceau|le titre|le son|la playlist|ma playlist|spotify|deezer|youtube music|yt music|la radio)"
    /**
     * Ce qui suit souvent « reprends la lecture » : « en cours », « là où elle en était », « que j'écoutais »… — avant, la
     * phrase n'était plus reconnue comme une commande de musique et partait à l'IA, qui disait le titre sans relancer.
     */
    private const val SUITE = "(?: (?:(?:qui )?(?:etait |est )?(?:en cours|en pause|mise? en pause|interrompue?|arretee?)|qui jouait|qui passait|de tout a l heure|d il y a un instant|(?:la )?ou (?:elle|il|on|j|tu) en etai[st]|ou je l avais (?:laisse|arrete)e?|que j ecoutais|que j etais en train d ecouter|sur (?:le telephone|android auto|la voiture|l autoradio)))?"

    private val R_PAUSE = Regex("^(?:(?:mets?|fais) (?:$MUS |ca |la )?(?:en )?pause|pause (?:$MUS|musique)|(?:stop|stoppe|arrete|suspends?|interromps?) $MUS|(?:mets|met) $MUS en pause)$")
    private val R_PLAY = Regex("^(?:(?:reprends?|reprendre|relance[rz]?|continue[rz]?|poursuis|poursuivre|redemarre[rz]?|rejoue|remets?|remettre|relancer|demarre[rz]?|lance[rz]?|joue[rz]?) $MUS$SUITE|(?:reprends?|relance|remets?|redemarre) (?:la |le |ma |mon )?(?:lecture|musique|morceau|chanson|son) (?:qui )?(?:etait )?en cours|(?:lance[rz]?|demarre[rz]?|mets?|joue[rz]?|remets?) (?:de la |la |ma |une )musique|(?:remets?|relance|lance[rz]?|demarre[rz]?) (?:en route|la lecture de) $MUS|(?:enleve|retire|sors) (?:$MUS )?(?:de la |la )?pause)$")
    private val R_NEXT = Regex("^(?:(?:chanson|morceau|titre|piste|musique|son) (?:suivante?|d apres)|(?:passe|va) (?:a la |au |la |le )?(?:chanson |morceau |titre |piste )?(?:suivante?|d apres)|(?:passe|change|zappe|saute) (?:la chanson|le morceau|le titre|de chanson|de morceau|de titre|celle ci|celui ci|ca|cette chanson|ce morceau)|(?:mets|joue) (?:la |le )?(?:chanson|morceau|titre) (?:suivante?|d apres))$")
    private val R_PREV = Regex("^(?:(?:chanson|morceau|titre|piste|musique) (?:precedente?|d avant)|(?:reviens|retourne|repasse|va) (?:a la |au )?(?:chanson |morceau |titre )?(?:precedente?|d avant|en arriere)|(?:remets|rejoue|mets) (?:la |le )?(?:chanson|morceau|titre) (?:precedente?|d avant))$")
    private val R_RESTART = Regex("^(?:recommence|reprends?|rejoue|relance) (?:la chanson|le morceau|le titre|ce morceau|cette chanson|ce titre)(?: (?:du|depuis le|au) debut)?$")
    /** « Reprends du début » : aussi une demande de conversation — seulement si une musique est ouverte. */
    private val R_DEBUT = Regex("^(?:recommence|reprends?) (?:du|depuis le|au) debut$")
    private val R_PLUS = Regex("^(?:(?:monte|augmente|hausse|remonte|pousse) (?:le son|le volume|la musique)(?: un peu| un peu plus)?|(?:un peu )?plus fort|monte|plus de son)$")
    private val R_MOINS = Regex("^(?:(?:baisse|diminue|descends|reduis) (?:le son|le volume|la musique)(?: un peu| un peu plus)?|(?:un peu )?moins fort|moins de son)$")
    private val R_INFO = Regex("^(?:(?:quelle est|c est quoi|quel est) (?:cette chanson|ce morceau|ce titre|ce son|la chanson|le morceau|le titre)(?: qui (?:passe|joue))?|qu est ce qu on ecoute|qu est ce qui passe|qu est ce qui joue|quelle chanson (?:passe|joue|est ce)|qui chante(?: ca| cette chanson)?|c est qui qui chante|on ecoute quoi|c est quoi ce que j ecoute|(?:quelle est|quel est|c est quoi|dis moi) (?:la lecture|la musique|la chanson|le morceau|le titre) (?:en cours|actuelle?)|qu est ce qui est en (?:cours de )?lecture)$")

    /** Un seul mot (« suivant », « pause », « lecture »…) : seulement si une musique est ouverte (sinon c'est peut-être autre chose). */
    private val R_MOT = Regex("^(?:suivante?|next|skip|zappe|precedente?|previous|lecture|play|pause|reprends?|reprendre)$")

    /**
     * Demande de contrôle de la musique, ou null (tout le reste continue vers Jarvis / Hermès). [musiqueOuverte] n'est
     * consulté que pour un mot seul (« suivant », « pause »…), ambigu hors musique.
     */
    fun intention(texte: String, musiqueOuverte: () -> Boolean = { true }): Action? {
        var n = fr.dd.jarvis.societe.core.normaliser(texte)
        if (n.isBlank() || Regex("\\d").containsMatchIn(n) || AUTRE_APPAREIL.containsMatchIn(n)) return null
        repeat(2) { n = n.replace(PREFIXE, "").replace(POLITESSE, "").replace(FIN, "").trim() }
        if (n.isBlank() || n.split(' ').size > 9) return null
        if (R_DEBUT.matches(n)) return if (musiqueOuverte()) Action.RESTART else null
        if (R_MOT.matches(n)) {
            if (!musiqueOuverte()) return null
            return when {
                n.startsWith("suiv") || n == "next" || n == "skip" || n == "zappe" -> Action.NEXT
                n.startsWith("prec") || n == "previous" -> Action.PREVIOUS
                n == "pause" -> Action.PAUSE
                else -> Action.PLAY
            }
        }
        return when {
            R_RESTART.matches(n) -> Action.RESTART
            R_PAUSE.matches(n) -> Action.PAUSE
            R_NEXT.matches(n) -> Action.NEXT
            R_PREV.matches(n) -> Action.PREVIOUS
            R_PLAY.matches(n) -> Action.PLAY
            R_PLUS.matches(n) -> Action.VOLUME_UP
            R_MOINS.matches(n) -> Action.VOLUME_DOWN
            R_INFO.matches(n) -> Action.INFO
            else -> null
        }
    }

    /** Correspondance avec les actions de l'outil controle_media (play, pause, next, previous…). */
    fun depuisOutil(action: String): Action? = when (action.lowercase().replace("-", "_").trim()) {
        "play", "lecture", "reprendre", "resume", "play_pause" -> Action.PLAY
        "pause", "stop", "arreter", "arrêter" -> Action.PAUSE
        "next", "suivant" -> Action.NEXT
        "previous", "precedent", "précédent" -> Action.PREVIOUS
        "restart", "debut", "début" -> Action.RESTART
        "volume_up", "plus_fort" -> Action.VOLUME_UP
        "volume_down", "moins_fort" -> Action.VOLUME_DOWN
        "info", "etat", "état", "now_playing" -> Action.INFO
        else -> null
    }

    /** Sessions des applis de musique (jamais celle de JARVIS), les plus pertinentes d'abord. */
    fun sessions(ctx: Context): List<MediaController> = runCatching {
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        m.getActiveSessions(ComponentName(ctx, JarvisNotificationListener::class.java)).filter { it.packageName != ctx.packageName }
    }.getOrDefault(emptyList())

    /** Une appli de musique a une session ouverte (en lecture ou en pause, avec un morceau). */
    fun musiqueOuverte(ctx: Context) = sessions(ctx).any { it.metadata != null || joue(it) }

    private fun joue(c: MediaController) = c.playbackState?.state in setOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING)

    private fun cible(l: List<MediaController>, a: Action): MediaController? = when (a) {
        Action.PAUSE, Action.NEXT, Action.PREVIOUS, Action.RESTART, Action.INFO -> l.firstOrNull { joue(it) } ?: l.firstOrNull { it.metadata != null } ?: l.firstOrNull()
        else -> l.firstOrNull { it.metadata != null && !joue(it) } ?: l.firstOrNull { it.metadata != null } ?: l.firstOrNull()
    }

    private fun nomAppli(ctx: Context, c: MediaController) = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString()
    }.getOrDefault(c.packageName)

    private fun morceau(c: MediaController?): String {
        val m = c?.metadata ?: return ""
        val t = m.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val a = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty().trim()
        return listOf(t, a).filter { it.isNotBlank() }.joinToString(" — ")
    }

    /**
     * La musique doit JOUER une fois JARVIS silencieux : sa voix ne fera que la baisser, le micro ne se rouvre pas derrière,
     * et si elle est encore en pause quand plus rien ne parle ni n'écoute (Android Auto : la voix ou le micro de la
     * voiture l'avaient arrêtée), la lecture est relancée.
     */
    private fun garantir(ctx: Context, c: MediaController) {
        LectureGarantie.noter(c.packageName)
        runCatching { LectureGarantie.surveiller(ctx) }
    }

    /** Exécute la commande ; réponse courte (lue à voix haute). [voiture] : jamais de touche média de repli. */
    fun executer(ctx: Context, a: Action, voiture: Boolean = false): String {
        // « Pause » : la musique lancée par JARVIS ne doit plus être relancée en fin de conversation.
        if (a == Action.PAUSE) LectureGarantie.oublier()
        if (a == Action.VOLUME_UP || a == Action.VOLUME_DOWN) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            repeat(2) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (a == Action.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
            val pct = (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1))
            return (if (a == Action.VOLUME_UP) "🔊" else "🔉") + " Volume à $pct %."
        }
        val l = sessions(ctx)
        val c = cible(l, a)
        if (c == null) {
            if (a == Action.INFO) return "🎵 Aucune musique en cours."
            // Pas d'accès aux sessions (accès aux notifications refusé) ou aucune appli de musique ouverte.
            if (voiture) return "🎵 Je ne trouve pas d'appli de musique active : lance-la une fois depuis Android Auto, puis redemande-moi."
            val key = when (a) { Action.PLAY -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY; Action.PAUSE -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                Action.NEXT -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT; Action.PREVIOUS, Action.RESTART -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> return "" }
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, key))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, key))
            return when (a) { Action.PAUSE -> "⏸️ Pause."; Action.NEXT -> "⏭️ Morceau suivant."; Action.PREVIOUS, Action.RESTART -> "⏮️ Morceau précédent."; else -> "▶️ Lecture." }
        }
        val tc = c.transportControls
        val appli = nomAppli(ctx, c)
        return when (a) {
            Action.PAUSE -> {
                // Envoyer pause à toutes les sessions actives : plusieurs lecteurs peuvent continuer simultanément.
                val actives = l.filter { joue(it) }
                if (actives.isEmpty()) {
                    "ℹ️ Aucune lecture musicale active à mettre en pause."
                } else {
                    val echecs = actives.count { controller ->
                        runCatching { controller.transportControls.pause() }.isFailure
                    }
                    // PlaybackState est actualisé de façon asynchrone par l'application musicale.
                    val limite = android.os.SystemClock.elapsedRealtime() + 1200L
                    while (actives.any { joue(it) } && android.os.SystemClock.elapsedRealtime() < limite) {
                        android.os.SystemClock.sleep(100L)
                    }
                    val encoreActives = actives.filter { joue(it) }
                    when {
                        encoreActives.isEmpty() -> "⏸️ Musique mise en pause ($appli)."
                        echecs == actives.size -> "⚠️ L'application musicale ($appli) n'a pas accepté la commande pause. La lecture est peut-être toujours active."
                        else -> "⚠️ La commande pause a été envoyée, mais ${encoreActives.size} session(s) musicale(s) signalent encore une lecture. Je ne confirme pas la pause."
                    }
                }
            }
            Action.PLAY -> { tc.play(); garantir(ctx, c); "▶️ Lecture reprise" + morceau(c).let { if (it.isBlank()) " ($appli)." else " : $it." } }
            Action.NEXT -> { val enPause = !joue(c); tc.skipToNext(); if (enPause) tc.play(); garantir(ctx, c); "⏭️ Morceau suivant." }
            Action.PREVIOUS -> { val enPause = !joue(c); tc.skipToPrevious(); if (enPause) tc.play(); garantir(ctx, c); "⏮️ Morceau précédent." }
            Action.RESTART -> { val enPause = !joue(c); tc.seekTo(0); if (enPause) tc.play(); garantir(ctx, c); "🔁 Morceau repris du début." }
            Action.INFO -> morceau(c).let { m -> if (m.isBlank()) "🎵 $appli joue un titre sans information." else "🎵 $m ($appli${if (joue(c)) "" else ", en pause"})." }
            else -> ""
        }
    }
}
