package fr.dd.jarvis.car

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.messaging.model.CarMessage
import androidx.car.app.messaging.model.ConversationCallback
import androidx.car.app.messaging.model.ConversationItem
import androidx.car.app.model.CarText
import androidx.core.app.Person
import androidx.car.app.validation.HostValidator
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.service.CarBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * JARVIS sur Android Auto (messagerie conversationnelle + commandes vocales) :
 *  - accueil : Parler à JARVIS, planning d'aujourd'hui / de demain, dernière réponse ;
 *  - conversation vocale au MICRO DE LA VOITURE (Android Auto récent) : la phrase est transcrite (Whisper sur Groq),
 *    JARVIS répond à voix haute, puis réécoute ; la conversation se referme après quelques secondes de silence ;
 *  - agenda : rendez-vous du jour, un appui lance la navigation vers l'adresse.
 * La messagerie basée sur des modèles exige Car API 7 et une distribution Google Play en test interne/fermé.
 * Le réglage « Sources inconnues » ne déverrouille pas les applications Car App Library sur un véhicule réel.
 */
class JarvisCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session = JarvisCarSession()
}

class JarvisCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = JarvisCarScreen(carContext)
}

private val heure = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(heure)

private fun limiteListe(ctx: CarContext): Int =
    runCatching { ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST) }.getOrDefault(6).coerceAtLeast(3)

/** Micro de la voiture utilisable (Android Auto assez récent + permission micro accordée au téléphone). */
private fun voixVoiture(ctx: CarContext) = ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_5 &&
    ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ======================================================================================================= accueil

class JarvisCarScreen(ctx: CarContext) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp

    private fun resume(jour: LocalDate): String {
        if (!app.calendar.hasPermission()) return "Autorise l'agenda dans JARVIS sur le téléphone"
        val evts = runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList()).sortedBy { it.begin }
        if (evts.isEmpty()) return "Aucun rendez-vous"
        val maintenant = System.currentTimeMillis()
        val prochain = evts.firstOrNull { it.end > maintenant } ?: evts.first()
        return "${evts.size} rendez-vous · " + (if (prochain.allDay) "" else "${hm(prochain.begin)} ") + prochain.title.take(40)
    }

    override fun onGetTemplate(): Template {
        if (!app.chatLoaded) app.chatCharger()
        val voix = voixVoiture(carContext)
        val liste = ItemList.Builder()
        val moi = Person.Builder().setName("Moi").setKey("jarvis_user").build()
        val jarvis = Person.Builder().setName("JARVIS").setKey("jarvis").setImportant(true).build()
        val historique = (CarBridge.contexte().ifEmpty { app.chat.toList() }).takeLast(5)
        val messages = if (historique.isEmpty()) {
            listOf(CarMessage.Builder().setSender(jarvis).setBody(CarText.create("Je suis prêt. Tu peux me parler ou me dicter une demande."))
                .setReceivedTimeEpochMillis(System.currentTimeMillis()).setRead(true).build())
        } else historique.map { m ->
            CarMessage.Builder().setSender(if (m.fromUser) moi else jarvis).setBody(CarText.create(m.text.take(500)))
                .setReceivedTimeEpochMillis(m.ts).setRead(true).build()
        }
        val callback = object : ConversationCallback {
            override fun onMarkAsRead() {}
            override fun onTextReply(text: String) {
                if (text.isNotBlank()) CarBridge.repondre(carContext, text) { invalidate() }
            }
        }
        liste.addItem(ConversationItem.Builder("jarvis-assistant", CarText.create("JARVIS"), moi, messages, callback).build())
        liste.addItem(Row.Builder().setTitle("🎤 Parler à JARVIS")                .addText(if (voix) "Micro de la voiture : demande, réponse lue, on enchaîne" else "Dictée (voiture à l'arrêt) — ou « Répondre » à la conversation JARVIS")
                .setOnClickListener {
                    if (voix) ConversationVoiture.ouvrir(carContext, screenManager)
                    else screenManager.push(JarvisSearchScreen(carContext) { q -> screenManager.push(ReponseTexteScreen(carContext, q)) })
                }.build())
            .addItem(Row.Builder().setTitle("📅 Aujourd'hui").addText(resume(LocalDate.now()))
                .setOnClickListener { screenManager.push(AgendaVoitureScreen(carContext, LocalDate.now(), "Aujourd'hui")) }.build())
            .addItem(Row.Builder().setTitle("📅 Demain").addText(resume(LocalDate.now().plusDays(1)))
                .setOnClickListener { screenManager.push(AgendaVoitureScreen(carContext, LocalDate.now().plusDays(1), "Demain")) }.build())
        CarBridge.contexte().lastOrNull { !it.fromUser }?.let { m ->
            liste.addItem(Row.Builder().setTitle("💬 Dernière réponse").addText(m.text.replace(Regex("\\s+"), " ").take(120)).build())
        }
        return ListTemplate.Builder().setTitle("JARVIS").setHeaderAction(Action.APP_ICON).setSingleList(liste.build()).build()
    }
}

// ======================================================================================================= agenda

class AgendaVoitureScreen(ctx: CarContext, private val jour: LocalDate, private val titre: String) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp

    private fun naviguer(lieu: String) {
        runCatching { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE).setData(Uri.parse("geo:0,0?q=" + Uri.encode(lieu)))) }
    }

    override fun onGetTemplate(): Template {
        val b = ItemList.Builder()
        if (!app.calendar.hasPermission()) b.setNoItemsMessage("Autorise l'accès à l'agenda dans JARVIS sur le téléphone.")
        else {
            val evts = runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList()).sortedBy { it.begin }
            if (evts.isEmpty()) b.setNoItemsMessage("Aucun rendez-vous.")
            evts.take(limiteListe(carContext)).forEach { e ->
                val quand = if (e.allDay) "Journée" else "${hm(e.begin)}–${hm(e.end)}"
                val r = Row.Builder().setTitle("$quand · ${e.title.ifBlank { "(sans titre)" }}")
                if (e.location.isNotBlank()) {
                    r.addText("📍 ${e.location.take(80)} — toucher pour y aller")
                    r.setOnClickListener { naviguer(e.location) }
                }
                b.addItem(r.build())
            }
        }
        return ListTemplate.Builder().setTitle(titre).setHeaderAction(Action.BACK).setSingleList(b.build()).build()
    }
}

// ======================================================================================================= conversation vocale

/**
 * Conversation au micro de la voiture. Chaque étape (écoute, réflexion, réponse, fin) est affichée par un nouvel écran
 * qui REMPLACE le précédent : Android Auto limite le nombre d'écrans d'une tâche, la pile reste ainsi à deux niveaux
 * (accueil + conversation) quel que soit le nombre d'échanges.
 */
class ConversationVoiture private constructor(private val ctx: CarContext, private val ecrans: androidx.car.app.ScreenManager) {

    sealed class Etat {
        object Ecoute : Etat()
        class Reflexion(val question: String) : Etat()
        class Reponse(val question: String, val texte: String) : Etat()
        class Fin(val texte: String) : Etat()
    }

    companion object {
        private var courante: ConversationVoiture? = null

        fun ouvrir(ctx: CarContext, ecrans: androidx.car.app.ScreenManager) {
            courante?.fermer()
            courante = ConversationVoiture(ctx, ecrans).also { it.ecouter(premiere = true) }
        }
    }

    private val app = ctx.applicationContext as JarvisApp
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var micro: MicroVoiture? = null
    private var tache: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsPret = false
    private var fermee = false
    private var incompris = 0
    private val arret = Regex("^(merci|c'est tout|c est tout|stop|arrete|termine|fin|au revoir|laisse tomber|ca suffit)\\b.*")

    init {
        tts = TextToSpeech(ctx) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.FRANCE
                runCatching { tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) }
                ttsPret = true
            }
        }
    }

    private fun silenceMs() = app.store.silenceSec.value.coerceIn(4, 30) * 1000L

    private fun montrer(e: Etat) {
        if (fermee) return
        val haut = ecrans.top
        if (haut is EcranConversation) { haut.remplace = true; ecrans.pop() }
        ecrans.push(EcranConversation(ctx, this, e))
    }

    /** Écoute une phrase au micro de la voiture, la transcrit et la traite. */
    fun ecouter(premiere: Boolean = false) {
        if (fermee) return
        tts?.stop()
        micro?.arreter(); tache?.cancel()
        montrer(Etat.Ecoute)
        tache = scope.launch {
            val m = MicroVoiture(ctx).also { micro = it }
            val res = withContext(Dispatchers.IO) { m.ecouter(if (premiere) maxOf(silenceMs(), 8000L) else silenceMs()) }
            if (fermee) return@launch
            when (res) {
                is Ecoute.Annulee -> return@launch
                is Ecoute.Silence -> { terminer("Conversation terminée."); return@launch }
                is Ecoute.Erreur -> { terminer("Micro de la voiture indisponible : ${res.message}"); return@launch }
                is Ecoute.Phrase -> traiter(res.pcm)
            }
        }
    }

    private suspend fun traiter(pcm: ByteArray) {
        if (!app.llm.groq.disponible()) { terminer("Ajoute une clé Groq dans JARVIS (Réglages) : elle sert aussi à la dictée en voiture."); return }
        montrer(Etat.Reflexion(""))
        val texte = try {
            Pcm.nettoyerTranscription(withContext(Dispatchers.IO) { app.llm.groq.transcrire(Pcm.wav(pcm)) })
        } catch (e: Throwable) { terminer("Transcription impossible : ${e.message?.take(120) ?: e.javaClass.simpleName}"); return }
        if (fermee) return
        if (texte.isBlank()) { if (++incompris < 3) ecouter() else terminer("Je n'ai pas compris."); return }
        incompris = 0
        val norm = Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        if (arret.matches(norm)) { parler("D'accord.") { terminer("Conversation terminée.") }; return }
        montrer(Etat.Reflexion(texte))
        CarBridge.ajouter(ChatMsg(true, texte))
        val rep = withContext(Dispatchers.IO) {
            try {
                // En voiture : les actions sensibles (envoi de SMS, appel…) qui exigent une confirmation forcée sont refusées.
                Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { }
            } catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
        }
        if (fermee) return
        val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
        CarBridge.ajouter(ChatMsg(false, propre))
        montrer(Etat.Reponse(texte, propre))
        // Réponse lue, puis on réécoute : on enchaîne sans retoucher l'écran ; silence → fin.
        parler(propre.take(1500)) { ecouter() }
    }

    private fun parler(texte: String, puis: () -> Unit) {
        val t = tts
        if (!ttsPret || t == null) { main.post(puis); return }
        val id = "jarvis-" + System.nanoTime()
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == id) main.post { if (!fermee) puis() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { if (utteranceId == id) main.post { if (!fermee) puis() } }
        })
        t.speak(texte, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** Coupe JARVIS et réécoute tout de suite (bouton « Répondre »). */
    fun repondre() { tts?.stop(); ecouter() }

    private fun terminer(texte: String) {
        micro?.arreter()
        montrer(Etat.Fin(texte))
    }

    fun fermer() {
        if (fermee) return
        fermee = true
        micro?.arreter(); tache?.cancel()
        scope.cancel()
        runCatching { tts?.stop(); tts?.shutdown() }
        if (courante === this) courante = null
    }

    /** Appelé quand un écran de conversation disparaît : si on est revenu à l'accueil, la conversation se ferme. */
    fun ecranFerme() {
        main.post { if (ecrans.top !is EcranConversation) fermer() }
    }
}

class EcranConversation(ctx: CarContext, private val conv: ConversationVoiture, private val etat: ConversationVoiture.Etat) : Screen(ctx) {
    /** Vrai quand l'écran est remplacé par l'étape suivante (et non fermé par l'utilisateur). */
    var remplace = false

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { if (!remplace) conv.ecranFerme() }
        })
    }

    override fun onGetTemplate(): Template {
        val terminer = Action.Builder().setTitle("Terminer").setOnClickListener { conv.fermer(); screenManager.pop() }.build()
        val parler = Action.Builder().setTitle("🎤 Répondre").setOnClickListener { conv.repondre() }.build()
        return when (etat) {
            is ConversationVoiture.Etat.Ecoute -> MessageTemplate.Builder("🎤 Je t'écoute…").setTitle("JARVIS")
                .setHeaderAction(Action.BACK).addAction(terminer).build()
            is ConversationVoiture.Etat.Reflexion -> MessageTemplate.Builder(if (etat.question.isBlank()) "…" else "« ${etat.question.take(160)} »")
                .setTitle("JARVIS").setHeaderAction(Action.BACK).setLoading(true).build()
            is ConversationVoiture.Etat.Reponse -> MessageTemplate.Builder(etat.texte.take(450)).setTitle("« ${etat.question.take(60)} »")
                .setHeaderAction(Action.BACK).addAction(parler).addAction(terminer).build()
            is ConversationVoiture.Etat.Fin -> MessageTemplate.Builder(etat.texte).setTitle("JARVIS")
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle("🎤 Parler").setOnClickListener { conv.repondre() }.build()).build()
        }
    }
}

// ======================================================================================================= repli sans micro voiture

/** Écran de saisie : Android Auto fournit le clavier (voiture à l'arrêt). */
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

/** Réponse à une demande tapée (repli) : lue à voix haute et affichée. */
class ReponseTexteScreen(ctx: CarContext, private val question: String) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var texte: String? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx) { st -> if (st == TextToSpeech.SUCCESS) tts?.language = Locale.FRANCE }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel(); runCatching { tts?.stop(); tts?.shutdown() } }
        })
        scope.launch {
            CarBridge.ajouter(ChatMsg(true, question))
            val rep = withContext(Dispatchers.IO) {
                try { Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { } }
                catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            CarBridge.ajouter(ChatMsg(false, propre))
            texte = propre
            invalidate()
            runCatching { tts?.speak(propre.take(1500), TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
        }
    }

    override fun onGetTemplate(): Template {
        val t = texte
        return if (t == null) MessageTemplate.Builder("« ${question.take(160)} »").setTitle("JARVIS").setHeaderAction(Action.BACK).setLoading(true).build()
        else MessageTemplate.Builder(t.take(450)).setTitle("JARVIS").setHeaderAction(Action.BACK).build()
    }
}
