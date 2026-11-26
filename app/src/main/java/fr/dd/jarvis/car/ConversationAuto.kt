package fr.dd.jarvis.car

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.service.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import kotlin.coroutines.resume

/**
 * « Jarvis » dit en voiture (mot d'éveil du téléphone, ou ▶ / micro de la carte média) : la conversation passe par le
 * MICRO DE LA VOITURE (Android Auto) et la voix sort sur les HAUT-PARLEURS de la voiture, sans changer d'écran (le GPS
 * reste affiché). Possible tant que JARVIS a été ouvert une fois dans Android Auto pendant le trajet (contexte voiture)
 * et qu'une clé Groq sert à la transcription ; sinon l'écoute habituelle du téléphone prend le relais.
 * Comme pour le mot d'éveil, JARVIS ne réécoute que s'il vient de poser une question (pas de radio prise pour une demande).
 */
object ConversationAuto {
    @Volatile private var enCours = false
    private val arret = Regex("^(merci|c'est tout|c est tout|stop|arrete|termine|fin|au revoir|laisse tomber|ca suffit)\\b.*")

    fun possible(app: JarvisApp): Boolean = app.enVoiture && !enCours && NavigationVoiture.contexte != null &&
        runCatching { app.llm.groq.disponible() }.getOrDefault(false)

    /** Lance la conversation ; [fin] est appelé (fil principal) quand elle se termine, quelle qu'en soit la raison. */
    fun demarrer(app: JarvisApp, fin: () -> Unit) {
        val ctx = NavigationVoiture.contexte
        if (ctx == null || enCours) { fin(); return }
        enCours = true
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try { boucle(app, ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { } finally {
                enCours = false; fin()
                // Musique demandée pendant la conversation : elle doit jouer maintenant que micro et voix sont libérés.
                runCatching { fr.dd.jarvis.device.LectureGarantie.surveiller(app) }
            }
        }
    }

    /**
     * Dit [texte] et revient quand la voix a fini. Si la voix est coupée sans prévenir (pause de la voiture, autre son),
     * on n'attend plus la fin qui ne viendra pas : avant, la conversation restait bloquée et « Jarvis » n'était plus entendu.
     */
    private suspend fun parler(app: JarvisApp, texte: String) {
        val fini = java.util.concurrent.atomic.AtomicBoolean(false)
        VoixVoiture.parler(app, texte) { fini.set(true) }
        // Garde-fou proportionnel à la longueur (une longue réponse dure plus de 90 s).
        val limite = System.currentTimeMillis() + 90_000L + texte.length * 110L
        try {
            kotlinx.coroutines.delay(1500)
            var calme = 0
            while (!fini.get() && System.currentTimeMillis() < limite) {
                if (!VoixVoiture.occupee()) {
                    if (++calme >= 3) { fr.dd.jarvis.voice.JournalVoix.noter("android-auto", "voix coupée avant la fin de la réponse : on continue"); return }
                } else calme = 0
                kotlinx.coroutines.delay(400)
            }
            if (!fini.get()) VoixVoiture.taire()
        } catch (e: kotlinx.coroutines.CancellationException) { VoixVoiture.taire(); throw e }
    }

    private suspend fun boucle(app: JarvisApp, ctx: androidx.car.app.CarContext) {
        val silence = app.store.silenceSec.value.coerceIn(4, 30) * 1000L
        var premiere = true
        var incompris = 0
        if (!app.chatLoaded) app.chatCharger()
        if (CarBridge.contexte().isEmpty()) app.chat.toList().takeLast(10).forEach { CarBridge.ajouter(it) }
        // Prépare le moteur et ses outils une seule fois par conversation voiture, pas à chaque phrase.
        val tools = ToolBox(app) { _, force -> !force }
        val brain = Brain(app.llm, tools, app) { app.store.userName.value }
        val journal = fr.dd.jarvis.voice.JournalVoix
        while (true) {
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.ECOUTE)
            val micro = MicroVoiture(ctx)
            val debutEcoute = System.currentTimeMillis()
            val res = withContext(Dispatchers.IO) { micro.ecouter(if (premiere) maxOf(silence, 8000L) else silence) }
            journal.noter("android-auto", "écoute terminée en ${System.currentTimeMillis() - debutEcoute} ms")
            premiere = false
            val pcm = when (res) {
                is Ecoute.Phrase -> res.pcm
                is Ecoute.Erreur -> {
                    val raison = res.message
                    val message = when {
                        raison.contains("autorisation", ignoreCase = true) || raison.contains("permission", ignoreCase = true) ->
                            "L'accès au microphone de JARVIS n'est pas autorisé. Ouvre JARVIS sur le téléphone, autorise le microphone dans les permissions de l'application, puis relance Android Auto."
                        else -> "Je n'arrive pas à utiliser le micro : $raison."
                    }
                    parler(app, message)
                    return
                }
                else -> return
            }
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REFLEXION, "Transcription de ta demande…")
            val debutTranscription = System.currentTimeMillis()
            val texte = try {
                withTimeoutOrNull(20_000) {
                    withContext(Dispatchers.IO) { Pcm.nettoyerTranscription(app.llm.groq.transcrire(Pcm.wav(pcm))) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
            journal.noter("android-auto", "transcription Groq en ${System.currentTimeMillis() - debutTranscription} ms")
            if (texte == null) { parler(app, "La transcription a pris trop de temps. Réessaie."); return }
            if (texte.isBlank()) { if (++incompris < 3) continue else { parler(app, "Je n'ai pas compris."); return } }
            incompris = 0
            val norm = Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            if (arret.matches(norm)) { parler(app, "D'accord."); return }

            // Les commandes musicales restent déterministes même avec le micro natif Android Auto :
            // ne pas les envoyer au Brain qui peut seulement ouvrir l'application sans lancer la lecture.
            val media = fr.dd.jarvis.device.CommandeMedia.intention(texte) {
                fr.dd.jarvis.device.CommandeMedia.musiqueOuverte(app)
            }
            if (media != null) {
                ActiviteVoiture.signaler(ActiviteVoiture.Etape.REFLEXION, texte)
                val rep = withContext(Dispatchers.IO) {
                    fr.dd.jarvis.device.CommandeMedia.executer(app, media, voiture = true)
                }
                val question = ChatMsg(true, texte)
                val reponse = ChatMsg(false, rep)
                CarBridge.ajouter(question); CarBridge.ajouter(reponse)
                app.chatAjouter(question); app.chatAjouter(reponse)
                ActiviteVoiture.signaler(ActiviteVoiture.Etape.REPONSE, texte, rep)
                if (!rep.startsWith("▶️")) parler(app, rep)
                // La musique repart (ou continue) : la conversation s'arrête ici. Avant, le micro de la voiture se
                // rouvrait aussitôt pour la phrase suivante, et Android Auto remettait la musique en pause — le titre
                // s'affichait, mais rien ne jouait.
                if (media != fr.dd.jarvis.device.CommandeMedia.Action.PAUSE) {
                    journal.noter("android-auto", "commande de musique : fin de la conversation pour laisser jouer la musique")
                    return
                }
                continue
            }

            if (arret.matches(norm)) { parler(app, "D'accord."); return }
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REFLEXION, texte)
            val question = ChatMsg(true, texte)
            CarBridge.ajouter(question); app.chatAjouter(question)
            val debutTraitement = System.currentTimeMillis()
            val rep = withTimeoutOrNull(90_000) {
                withContext(Dispatchers.IO) {
                    try { brain.reply(CarBridge.contexte(), vocal = true) { } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
                }
            } ?: run {
                journal.noter("android-auto", "traitement interrompu après ${System.currentTimeMillis() - debutTraitement} ms")
                parler(app, "Le traitement a pris trop de temps. Tu peux redemander.")
                return
            }
            journal.noter("android-auto", "compréhension + action en ${System.currentTimeMillis() - debutTraitement} ms")
            val propre = rep.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace(Regex("[#*_`>|]+"), "")
                .replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            val reponse = ChatMsg(false, propre)
            CarBridge.ajouter(reponse); app.chatAjouter(reponse)
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REPONSE, texte, propre)
            parler(app, propre)
            // Musique lancée ou reprise par cette demande : on ne rouvre pas le micro (il la remettrait en pause), sauf
            // si JARVIS vient de poser une question.
            if (fr.dd.jarvis.device.LectureGarantie.recente(60_000) && !propre.trimEnd().endsWith("?")) {
                journal.noter("android-auto", "musique lancée : fin de la conversation pour la laisser jouer")
                return
            }
            // En mode Android Auto, on garde la session ouverte après chaque réponse :
            // l'utilisateur peut enchaîner sans réactiver Jarvis ni redire le mot d'éveil.
            continue
        }
    }
}
