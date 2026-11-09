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
 * JARVIS sur Android Auto :
 *  - accueil : Parler à JARVIS, raccourcis (demandes toutes faites), planning d'aujourd'hui / de demain, dernière réponse ;
 *  - conversation vocale au MICRO DE LA VOITURE (Android Auto récent) : la phrase est transcrite (Whisper sur Groq),
 *    JARVIS répond avec SA voix (personnage choisi, voix naturelle), puis réécoute ; fin après quelques secondes de silence ;
 *  - agenda : un rendez-vous → y aller (navigation), appeler le client, en parler à JARVIS ;
 *  - conversation « messagerie » (notifications) : Android Auto lit les messages de JARVIS et permet d'y répondre.
 * Modèles utilisés : liste et message uniquement (acceptés par toutes les catégories d'applis voiture).
 * Visibilité : Android Auto exige une appli « installée depuis le Play Store » → Réglages › Téléphone › Android Auto.
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
    init {
        // Tant que JARVIS est ouvert dans Android Auto (même caché derrière le GPS), son contexte voiture sert à lancer
        // le guidage DANS Android Auto (Waze / Maps de la voiture), y compris depuis une demande vocale faite ailleurs.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { NavigationVoiture.contexte = carContext }
            override fun onDestroy(owner: LifecycleOwner) { if (NavigationVoiture.contexte === carContext) NavigationVoiture.contexte = null }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = JarvisCarScreen(carContext)
}

/**
 * Guidage depuis la voiture : Android Auto lance lui-même son appli de navigation (Waze, Maps…) vers la destination.
 * Fiable téléphone verrouillé, contrairement à l'ouverture de Waze sur le téléphone depuis l'arrière-plan.
 */
object NavigationVoiture {
    @Volatile var contexte: CarContext? = null

    /** Vrai si Android Auto a accepté la demande ; faux s'il faut se rabattre sur le téléphone. */
    suspend fun lancer(destination: String, point: Pair<Double, Double>? = null): Boolean {
        val c = contexte ?: return false
        if (destination.isBlank() && point == null) return false
        // Formats documentés par Android Auto : « geo:lat,lon » (point précis : fiche client, GPS) ou « geo:0,0?q=adresse ».
        val geo = if (point != null) "geo:${point.first},${point.second}" else "geo:0,0?q=" + Uri.encode(destination)
        return withContext(Dispatchers.Main) {
            runCatching { c.startCarApp(Intent(CarContext.ACTION_NAVIGATE).setData(Uri.parse(geo))); true }
                .recoverCatching {
                    // Certaines applis de navigation refusent le format avec coordonnées : nouvel essai avec l'adresse seule.
                    if (point != null) { c.startCarApp(Intent(CarContext.ACTION_NAVIGATE).setData(Uri.parse("geo:0,0?q=" + Uri.encode(destination)))); true } else false
                }.getOrDefault(false)
        }
    }
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

    init {
        VoixVoiture.preparer(ctx)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { VoixVoiture.taire() }
        })
    }

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
        liste.addItem(Row.Builder().setTitle("🎤 Parler à JARVIS")
                .addText(if (voix) "Je t'écoute au micro de la voiture, je réponds, on enchaîne" else "Dictée (voiture à l'arrêt) — ou « Répondre » au message de JARVIS")
                .setOnClickListener {
                    if (voix) ConversationVoiture.ouvrir(carContext, screenManager)
                    else screenManager.push(JarvisSearchScreen(carContext) { q -> screenManager.push(ReponseTexteScreen(carContext, q)) })
                }.build())
            .addItem(Row.Builder().setTitle("⚡ Raccourcis").addText(RaccourcisVoiture.liste(app).take(2).joinToString(" · ").take(90))
                .setOnClickListener { screenManager.push(RaccourcisVoitureScreen(carContext)) }.build())
            .addItem(Row.Builder().setTitle("📅 Aujourd'hui").addText(resume(LocalDate.now()))
                .setOnClickListener { screenManager.push(AgendaVoitureScreen(carContext, LocalDate.now(), "Aujourd'hui")) }.build())
            .addItem(Row.Builder().setTitle("📅 Demain").addText(resume(LocalDate.now().plusDays(1)))
                .setOnClickListener { screenManager.push(AgendaVoitureScreen(carContext, LocalDate.now().plusDays(1), "Demain")) }.build())
        val derniere = (CarBridge.contexte().ifEmpty { app.chat.toList() }).lastOrNull { !it.fromUser }
        derniere?.let { m ->
            val propre = m.text.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim()
            liste.addItem(Row.Builder().setTitle("🔊 Dernière réponse").addText(propre.take(120))
                .setOnClickListener { VoixVoiture.parler(carContext, propre.take(1500)) }.build())
        }
        return ListTemplate.Builder().setTitle("JARVIS · ${fr.dd.jarvis.voice.VoixNaturelle.personnage(app).icone}")
            .setHeaderAction(Action.APP_ICON).setSingleList(liste.build()).build()
    }
}

/** Raccourcis : une demande toute faite, un appui → JARVIS la traite et répond à voix haute. */
class RaccourcisVoitureScreen(ctx: CarContext) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp
    override fun onGetTemplate(): Template {
        val b = ItemList.Builder()
        RaccourcisVoiture.liste(app).take(limiteListe(carContext)).forEach { q ->
            b.addItem(Row.Builder().setTitle(q.take(70)).setOnClickListener { screenManager.push(ReponseTexteScreen(carContext, q)) }.build())
        }
        return ListTemplate.Builder().setTitle("⚡ Raccourcis").setHeaderAction(Action.BACK).setSingleList(b.build()).build()
    }
}

// ======================================================================================================= agenda

class AgendaVoitureScreen(ctx: CarContext, private val jour: LocalDate, private val titre: String) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp

    override fun onGetTemplate(): Template {
        val b = ItemList.Builder()
        if (!app.calendar.hasPermission()) b.setNoItemsMessage("Autorise l'accès à l'agenda dans JARVIS sur le téléphone.")
        else {
            val evts = runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList()).sortedBy { it.begin }
            if (evts.isEmpty()) b.setNoItemsMessage("Aucun rendez-vous.")
            evts.take(limiteListe(carContext)).forEach { e ->
                val quand = if (e.allDay) "Journée" else "${hm(e.begin)}–${hm(e.end)}"
                val r = Row.Builder().setTitle("$quand · ${e.title.ifBlank { "(sans titre)" }}")
                if (e.location.isNotBlank()) r.addText("📍 ${e.location.take(80)}")
                r.setOnClickListener { screenManager.push(EvenementVoitureScreen(carContext, e)) }
                b.addItem(r.build())
            }
        }
        return ListTemplate.Builder().setTitle(titre).setHeaderAction(Action.BACK).setSingleList(b.build()).build()
    }
}

/** Un rendez-vous : y aller, appeler le client (fiche Société ou numéro dans l'événement), en parler à JARVIS. */
class EvenementVoitureScreen(ctx: CarContext, private val e: fr.dd.jarvis.device.EventInfo) : Screen(ctx) {
    private val app = ctx.applicationContext as JarvisApp

    private fun telephone(): Pair<String, String>? {
        Regex("(?:\\+33\\s?|0)[1-9](?:[\\s.-]?\\d{2}){4}").find(e.description + " " + e.location)?.let { return "le contact" to it.value.replace(Regex("[\\s.-]"), "") }
        val data = runCatching { app.societe.data }.getOrNull() ?: return null
        val c = (data.chercherClients(e.title).firstOrNull() ?: e.location.takeIf { it.isNotBlank() }?.let { data.chercherClients(it).firstOrNull() })
        return c?.takeIf { it.tel.isNotBlank() }?.let { it.nom to it.tel.replace(Regex("[\\s.-]"), "") }
    }

    override fun onGetTemplate(): Template {
        val b = ItemList.Builder()
        val quand = if (e.allDay) "Journée" else "${hm(e.begin)}–${hm(e.end)}"
        if (e.location.isNotBlank()) b.addItem(Row.Builder().setTitle("🧭 Y aller").addText(e.location.take(90))
            .setOnClickListener { runCatching { carContext.startCarApp(Intent(CarContext.ACTION_NAVIGATE).setData(Uri.parse("geo:0,0?q=" + Uri.encode(e.location)))) } }.build())
        telephone()?.let { (nom, tel) ->
            b.addItem(Row.Builder().setTitle("📞 Appeler $nom").addText(tel)
                .setOnClickListener { runCatching { carContext.startCarApp(Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:$tel"))) } }.build())
        }
        b.addItem(Row.Builder().setTitle("🎤 Infos utiles par JARVIS").addText("Client, dernière intervention, trajet, météo")
            .setOnClickListener {
                screenManager.push(ReponseTexteScreen(carContext, "En 3 phrases courtes, donne-moi les infos utiles pour mon rendez-vous « ${e.title} » à $quand" +
                    (if (e.location.isNotBlank()) " (${e.location})" else "") + " : client, dernière intervention, temps de trajet."))
            }.build())
        return ListTemplate.Builder().setTitle("$quand · ${e.title.ifBlank { "(sans titre)" }}".take(60)).setHeaderAction(Action.BACK).setSingleList(b.build()).build()
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
    private var fermee = false
    private var incompris = 0
    private val arret = Regex("^(merci|c'est tout|c est tout|stop|arrete|termine|fin|au revoir|laisse tomber|ca suffit)\\b.*")

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
        VoixVoiture.taire()
        micro?.arreter(); tache?.cancel()
        montrer(Etat.Ecoute)
        tache = scope.launch {
            val m = MicroVoiture(ctx).also { micro = it }
            val res = withContext(Dispatchers.IO) { m.ecouter(if (premiere) maxOf(silenceMs(), 8000L) else silenceMs()) }
            if (fermee) return@launch
            when (res) {
                is Ecoute.Annulee -> return@launch
                is Ecoute.Silence -> { terminer("Conversation terminée."); return@launch }
                is Ecoute.Erreur -> { terminer("Micro indisponible (voiture et téléphone) : ${res.message}"); return@launch }
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
        app.chatCharger()
        if (CarBridge.contexte().isEmpty()) app.chat.toList().takeLast(10).forEach { CarBridge.ajouter(it) }
        val norm = Normalizer.normalize(texte.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        if (arret.matches(norm)) { parler("D'accord.") { terminer("Conversation terminée.") }; return }
        montrer(Etat.Reflexion(texte))
        val userMessage = ChatMsg(true, texte)
        CarBridge.ajouter(userMessage)
        app.chatAjouter(userMessage)
        val rep = withContext(Dispatchers.IO) {
            try {
                // En voiture : les actions sensibles (envoi de SMS, appel…) qui exigent une confirmation forcée sont refusées.
                Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { }
            } catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
        }
        if (fermee) return
        val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
        val assistantMessage = ChatMsg(false, propre)
        CarBridge.ajouter(assistantMessage)
        app.chatAjouter(assistantMessage)
        montrer(Etat.Reponse(texte, propre))
        // Réponse lue, puis on réécoute : on enchaîne sans retoucher l'écran ; silence → fin.
        parler(propre.take(1500)) { ecouter() }
    }

    /** JARVIS parle avec SA voix (personnage choisi, voix naturelle Gemini ou voix Android du même genre). */
    private fun parler(texte: String, puis: () -> Unit) {
        VoixVoiture.parler(ctx, texte) { if (!fermee) puis() }
    }

    /** Coupe JARVIS et réécoute tout de suite (bouton « Répondre »). */
    fun repondre() { VoixVoiture.taire(); ecouter() }

    private fun terminer(texte: String) {
        micro?.arreter()
        montrer(Etat.Fin(texte))
    }

    fun fermer() {
        if (fermee) return
        fermee = true
        micro?.arreter(); tache?.cancel()
        scope.cancel()
        VoixVoiture.taire()
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

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel(); VoixVoiture.taire() }
        })
        scope.launch {
            app.chatCharger()
            if (CarBridge.contexte().isEmpty()) app.chat.toList().takeLast(10).forEach { CarBridge.ajouter(it) }
            val userMessage = ChatMsg(true, question)
            CarBridge.ajouter(userMessage)
            app.chatAjouter(userMessage)
            val rep = withContext(Dispatchers.IO) {
                try { Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { } }
                catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val propre = rep.replace(Regex("[#*_`>|]+"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "C'est fait." }
            val assistantMessage = ChatMsg(false, propre)
            CarBridge.ajouter(assistantMessage)
            app.chatAjouter(assistantMessage)
            texte = propre
            invalidate()
            VoixVoiture.parler(carContext, propre.take(1500))
        }
    }

    override fun onGetTemplate(): Template {
        val t = texte
        return if (t == null) MessageTemplate.Builder("« ${question.take(160)} »").setTitle("JARVIS").setHeaderAction(Action.BACK).setLoading(true).build()
        else MessageTemplate.Builder(t.take(450)).setTitle("JARVIS").setHeaderAction(Action.BACK)
            .addAction(Action.Builder().setTitle("🔊 Relire").setOnClickListener { VoixVoiture.parler(carContext, t.take(1500)) }.build())
            .addAction(Action.Builder().setTitle("🎤 Parler").setOnClickListener {
                VoixVoiture.taire()
                if (voixVoiture(carContext)) ConversationVoiture.ouvrir(carContext, screenManager)
                else screenManager.push(JarvisSearchScreen(carContext) { q -> screenManager.push(ReponseTexteScreen(carContext, q)) })
            }.build()).build()
    }
}
