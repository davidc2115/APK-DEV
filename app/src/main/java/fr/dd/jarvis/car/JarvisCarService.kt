package fr.dd.jarvis.car

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.service.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Écran JARVIS sur Android Auto : un écran avec un bouton « Parler » (le micro de la voiture dicte la demande), la réponse s'affiche
 * et est lue à voix haute. Les actions s'exécutent sur le téléphone (Waze, agenda, mails, musique…).
 * Android Auto n'affiche les applications hors Play Store que si « Sources inconnues » est activé dans ses réglages de développeur.
 */
class JarvisCarService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    override fun onCreateSession(): Session = JarvisCarSession()
}

class JarvisCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = JarvisCarScreen(carContext)
}

class JarvisCarScreen(ctx: CarContext) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var texte = "Appuie sur « Parler » et dis ta demande."
    private var occupe = false
    private var tts: TextToSpeech? = null
    private var ttsPret = false

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) { tts?.language = Locale.FRENCH; ttsPret = true }
        }
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) { scope.cancel(); runCatching { tts?.stop(); tts?.shutdown() } }
        })
    }

    private fun demander(question: String) {
        if (question.isBlank() || occupe) return
        occupe = true
        texte = "« ${question.take(120)} »"
        invalidate()
        scope.launch {
            val rep = withContext(Dispatchers.IO) {
                try {
                    CarBridge.ajouter(fr.dd.jarvis.ai.ChatMsg(true, question))
                    // En voiture : les actions sensibles (envoi de SMS, appel…) qui exigent une confirmation forcée sont refusées.
                    Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { }
                } catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            CarBridge.ajouter(fr.dd.jarvis.ai.ChatMsg(false, propre))
            texte = propre.take(450)
            occupe = false
            invalidate()
            if (ttsPret) runCatching { tts?.speak(propre.take(1500), TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
        }
    }

    override fun onGetTemplate(): Template {
        val parler = Action.Builder().setTitle("🎤 Parler").setOnClickListener {
            screenManager.push(JarvisSearchScreen(carContext) { demander(it) })
        }.build()
        val planning = Action.Builder().setTitle("📅 Planning").setOnClickListener { demander("Montre-moi mon planning d'aujourd'hui") }.build()
        return MessageTemplate.Builder(texte).setTitle("JARVIS").setHeaderAction(Action.APP_ICON)
            .setLoading(occupe).apply { if (!occupe) { addAction(parler); addAction(planning) } }.build()
    }
}

/** Écran de saisie : Android Auto fournit le micro (dictée vocale) et le clavier. */
class JarvisSearchScreen(ctx: CarContext, private val onTexte: (String) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) {
                screenManager.pop()
                onTexte(searchText)
            }
        }).setHeaderAction(Action.BACK).setShowKeyboardByDefault(false).setSearchHint("Parle à JARVIS…").build()
}
