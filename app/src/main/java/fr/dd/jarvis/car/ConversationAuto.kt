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
            try { boucle(app, ctx) } catch (_: Throwable) { } finally { enCours = false; fin() }
        }
    }

    private suspend fun parler(app: JarvisApp, texte: String) {
        withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine { c -> VoixVoiture.parler(app, texte.take(1500)) { if (c.isActive) c.resume(Unit) } }
        }
    }

    private suspend fun boucle(app: JarvisApp, ctx: androidx.car.app.CarContext) {
        val silence = app.store.silenceSec.value.coerceIn(4, 30) * 1000L
        var premiere = true
        var incompris = 0
        app.chatCharger()
        if (CarBridge.contexte().isEmpty()) app.chat.toList().takeLast(10).forEach { CarBridge.ajouter(it) }
        while (true) {
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.ECOUTE)
            val micro = MicroVoiture(ctx)
            val res = withContext(Dispatchers.IO) { micro.ecouter(if (premiere) maxOf(silence, 8000L) else silence) }
            premiere = false
            val pcm = when (res) {
                is Ecoute.Phrase -> res.pcm
                is Ecoute.Erreur -> { parler(app, "Je n'arrive pas à utiliser le micro."); return }
                else -> return
            }
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REFLEXION, "…")
            val texte = try { Pcm.nettoyerTranscription(app.llm.groq.transcrire(Pcm.wav(pcm))) }
                catch (e: Throwable) { parler(app, "Je n'ai pas pu transcrire ta demande."); return }
            if (texte.isBlank()) { if (++incompris < 3) continue else { parler(app, "Je n'ai pas compris."); return } }
            incompris = 0
            val norm = Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            if (arret.matches(norm)) { parler(app, "D'accord."); return }
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REFLEXION, texte)
            val question = ChatMsg(true, texte)
            CarBridge.ajouter(question); app.chatAjouter(question)
            val rep = withContext(Dispatchers.IO) {
                try { Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { } }
                catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val propre = rep.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace(Regex("[#*_`>|]+"), "")
                .replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            val reponse = ChatMsg(false, propre)
            CarBridge.ajouter(reponse); app.chatAjouter(reponse)
            ActiviteVoiture.signaler(ActiviteVoiture.Etape.REPONSE, texte, propre)
            parler(app, propre)
            if (!propre.trimEnd().endsWith("?")) return
        }
    }
}
