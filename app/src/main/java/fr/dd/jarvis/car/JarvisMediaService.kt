package fr.dd.jarvis.car

import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.R
import fr.dd.jarvis.ai.Brain
import fr.dd.jarvis.ai.ChatMsg
import fr.dd.jarvis.ai.ToolBox
import fr.dd.jarvis.device.EventInfo
import fr.dd.jarvis.device.PhoneControl
import fr.dd.jarvis.service.CarBridge
import fr.dd.jarvis.service.JarvisService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * JARVIS dans Android Auto en tant qu'appli « média » (depuis Android Auto 17, seul type accepté hors Play Store).
 * Trois onglets :
 *  - 🎙️ JARVIS : une grande tuile micro → bip, tu parles, JARVIS répond à voix haute (écoute « Jarvis » du téléphone) ;
 *  - 📅 Aujourd'hui : les rendez-vous du jour (et de demain) ; un appui lance Waze vers l'adresse du rendez-vous ;
 *  - ⚡ Raccourcis : demandes toutes faites, relire la dernière réponse, reprendre la musique.
 * L'écran de lecture sert de page de conversation : ta phrase, la réponse, et trois boutons (🎙️ parler, 🧭 prochain
 * rendez-vous dans Waze, 🔁 relire). Le gros bouton ▶ ouvre aussi le micro.
 */
class JarvisMediaService : MediaBrowserService() {
    companion object {
        private const val RACINE = "racine"
        private const val VIDE = "vide"
        private const val SUGGESTIONS = "suggestions"
        private const val APPLI_GOOGLE = "com.google.android.googlequicksearchbox"
        private const val ONGLET_JARVIS = "onglet:jarvis"
        private const val ONGLET_AGENDA = "onglet:agenda"
        private const val ONGLET_RACCOURCIS = "onglet:raccourcis"
        private const val DEMAIN = "jour:demain"
        private const val PARLER = "parler"
        private const val RELIRE = "relire"
        private const val MUSIQUE = "musique"
        private const val LIRE_JOUR = "lirejour:"      // + date
        private const val RACCOURCI = "raccourci:"
        private const val RECHERCHE = "demande:"
        private const val EVT = "evt:"                 // + clé : un rendez-vous (une adresse ou aucune)
        private const val EVT_LIEUX = "lieux:"         // + clé : rendez-vous à plusieurs adresses (dossier)
        private const val LIEU = "lieu:"               // + clé + "#" + n° : une des adresses
        private const val ACT_PARLER = "fr.dd.jarvis.auto.PARLER"
        private const val ACT_WAZE = "fr.dd.jarvis.auto.WAZE"
        private const val ACT_RELIRE = "fr.dd.jarvis.auto.RELIRE"
        private const val STYLE_JOUABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_DOSSIER = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val LISTE = 1
        private const val GRILLE = 2

        /** Seules la voiture, l'Assistant et le système voient l'agenda (le service est visible des autres applis). */
        private val CONFIANCE = setOf(
            "com.google.android.projection.gearhead", "com.google.android.carassistant", "com.android.car.media",
            APPLI_GOOGLE, "com.android.systemui", "com.google.android.gms", "com.google.android.apps.bard",
            "com.google.android.apps.googleassistant",
        )
        private val heure = DateTimeFormatter.ofPattern("HH:mm", Locale.FRANCE)
        private fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(heure)
    }

    private lateinit var session: MediaSession
    private val portee = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as JarvisApp
    private val creeLe = SystemClock.elapsedRealtime()
    @Volatile private var derniere = ""
    /** Conversation lancée depuis la voiture : l'écran de lecture suit l'écoute de JARVIS. */
    private var enCours = false
    private var piloteJusqua = 0L
    private var veille: Job? = null
    /** Fin de la dernière écoute / dernier appui micro : un ▶ envoyé juste après par la voiture n'est pas un vrai appui. */
    private var derniereFin = 0L
    private var dernierParler = 0L
    private val abonne: (ActiviteVoiture.Evt) -> Unit = { surActivite(it) }

    override fun onCreate() {
        super.onCreate()
        session = MediaSession(this, "JARVIS").apply {
            setCallback(Rappels())
            // « En pause » (et non arrêté) : la carte média d'Android Auto, à côté du GPS en grand, affiche alors JARVIS
            // avec ▶ (= parler) et ses boutons 🎙️ / 🧭 / 🔁, au lieu des seules suggestions « Pour vous ».
            setPlaybackState(etat(PlaybackState.STATE_PAUSED))
            setMetadata(meta("JARVIS · ▶ pour parler", "🎙️ parler · 🧭 prochain rendez-vous dans Waze · 🔁 relire"))
            isActive = true
        }
        portee.launch {
            val p = withContext(Dispatchers.IO) { runCatching { prochainResume() }.getOrNull() }
            if (p != null && !enCours) session.setMetadata(meta("JARVIS · ▶ pour parler", p))
        }
        sessionToken = session.sessionToken
        ActiviteVoiture.abonne = abonne
        VoixVoiture.preparer(this)
    }

    override fun onDestroy() {
        if (ActiviteVoiture.abonne === abonne) ActiviteVoiture.abonne = null
        portee.cancel()
        runCatching { session.release() }
        super.onDestroy()
    }

    /** Android Auto, l'Assistant / Gemini, le système et JARVIS lui-même ; les autres applis ne voient rien. */
    private fun fiable(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg == packageName || pkg in CONFIANCE) return true
        return runCatching {
            (packageManager.getApplicationInfo(pkg, 0).flags and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean("android.media.browse.SEARCH_SUPPORTED", true)       // loupe / micro de la voiture → onPlayFromSearch
            putInt(STYLE_DOSSIER, LISTE)
            putInt(STYLE_JOUABLE, LISTE)
        }
        if (!fiable(clientPackageName)) return BrowserRoot(VIDE, extras)
        // Reprise de « la dernière écoute » (système) : rien à reprendre, JARVIS ne joue pas de musique lui-même.
        if (rootHints?.getBoolean(BrowserRoot.EXTRA_RECENT) == true) return BrowserRoot(VIDE, extras)
        // Carte « Pour vous » d'Android Auto : remplie par l'appli Google avec des éléments JOUABLES (les onglets n'en ont pas).
        if (clientPackageName == APPLI_GOOGLE || rootHints?.getBoolean(BrowserRoot.EXTRA_SUGGESTED) == true) return BrowserRoot(SUGGESTIONS, extras)
        return BrowserRoot(RACINE, extras)
    }

    private fun icone(nom: String): Uri = Uri.parse("android.resource://$packageName/drawable/$nom")

    private fun element(id: String, titre: String, sousTitre: String = "", icone: String = "auto_micro",
                        dossier: Boolean = false, style: Int? = null): MediaBrowser.MediaItem {
        val d = MediaDescription.Builder().setMediaId(id).setTitle(titre).setIconUri(icone(icone))
        if (sousTitre.isNotBlank()) d.setSubtitle(sousTitre)
        if (style != null) d.setExtras(Bundle().apply { putInt(STYLE_JOUABLE, style) })
        return MediaBrowser.MediaItem(d.build(), if (dossier) MediaBrowser.MediaItem.FLAG_BROWSABLE else MediaBrowser.MediaItem.FLAG_PLAYABLE)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val client = if (Build.VERSION.SDK_INT >= 28) runCatching { currentBrowserInfo.packageName }.getOrNull() else null
        if (parentId == VIDE || (client != null && !fiable(client))) {
            result.sendResult(mutableListOf()); return
        }
        result.detach()
        portee.launch {
            val l = withContext(Dispatchers.IO) { runCatching { enfants(parentId) }.getOrElse { mutableListOf() } }
            result.sendResult(l)
        }
    }

    private fun enfants(parentId: String): MutableList<MediaBrowser.MediaItem> = when {
        // Onglets (Android Auto affiche en onglets les dossiers de la racine).
        parentId == RACINE -> mutableListOf(
            element(ONGLET_JARVIS, "JARVIS", icone = "auto_onglet_micro", dossier = true, style = GRILLE),
            element(ONGLET_AGENDA, "Aujourd'hui", icone = "auto_onglet_agenda", dossier = true),
            element(ONGLET_RACCOURCIS, "Raccourcis", icone = "auto_onglet_eclair", dossier = true),
        )
        parentId == SUGGESTIONS -> suggestions()
        parentId == ONGLET_JARVIS -> mutableListOf(element(PARLER, "Parler à JARVIS", "Touche, attends le bip et parle", "auto_micro"))
        parentId == ONGLET_AGENDA -> agenda(LocalDate.now(), "aujourd'hui").apply {
            val demain = evenements(LocalDate.now().plusDays(1))
            add(element(DEMAIN, "📅 Demain", if (demain.isEmpty()) "Aucun rendez-vous" else "${demain.size} rendez-vous", "auto_agenda", dossier = true))
        }
        parentId == DEMAIN -> agenda(LocalDate.now().plusDays(1), "demain")
        parentId.startsWith(EVT_LIEUX) -> {
            val c = parentId.removePrefix(EVT_LIEUX)
            val e = trouver(c)
            if (e == null) mutableListOf()
            else destinations(e).mapIndexed { i, d -> element("$LIEU$c#$i", "🧭 ${d.libelle.take(70)}", "Waze", "auto_route") }.toMutableList()
        }
        parentId == ONGLET_RACCOURCIS -> mutableListOf<MediaBrowser.MediaItem>().apply {
            RaccourcisVoiture.liste(app).forEachIndexed { i, r -> add(element(RACCOURCI + i, r, icone = "auto_eclair")) }
            add(element(RELIRE, "🔁 Relire la dernière réponse", derniereReponse().take(80), "auto_relire"))
            add(element(MUSIQUE, "🎵 Reprendre la musique", "YT Music Morphe", "auto_musique"))
        }
        else -> mutableListOf()
    }

    /** Ligne de la carte média : prochain rendez-vous du jour (le bouton 🧭 y emmène). */
    private fun prochainResume(): String {
        val maintenant = System.currentTimeMillis()
        val e = evenements(LocalDate.now()).firstOrNull { !it.allDay && it.end > maintenant }
            ?: return "🎙️ parler · 📅 plus de rendez-vous aujourd'hui · 🔁 relire"
        val t = AgendaVoiture.texteBrut(e.title).ifBlank { "Rendez-vous" }.take(40)
        return "Prochain : ${hm(e.begin)} · $t" + (if (destinations(e).isNotEmpty()) " — 🧭 Waze" else "")
    }

    /** « Pour vous » : parler, Waze vers le prochain rendez-vous, lire la journée, premiers raccourcis. */
    private fun suggestions(): MutableList<MediaBrowser.MediaItem> {
        val l = mutableListOf(element(PARLER, "🎙️ Parler à JARVIS", "Touche, attends le bip et parle", "auto_micro"))
        val maintenant = System.currentTimeMillis()
        val aujourdhui = evenements(LocalDate.now())
        aujourdhui.filter { !it.allDay && it.end > maintenant }
            .firstNotNullOfOrNull { e -> destinations(e).firstOrNull()?.let { e to it } }
            ?.let { (e, d) ->
                l += element(EVT + cle(LocalDate.now(), e), "🧭 ${hm(e.begin)} · ${AgendaVoiture.texteBrut(e.title).ifBlank { "Rendez-vous" }.take(50)}",
                    "Waze → ${d.libelle.take(60)}", "auto_route")
            }
        if (app.calendar.hasPermission()) l += element(LIRE_JOUR + LocalDate.now(), "🗣️ Lis-moi aujourd'hui",
            if (aujourdhui.isEmpty()) "Aucun rendez-vous" else "${aujourdhui.size} rendez-vous", "auto_parole")
        RaccourcisVoiture.liste(app).take(3).forEachIndexed { i, r -> l += element(RACCOURCI + i, "⚡ $r", icone = "auto_eclair") }
        return l
    }

    // ------------------------------------------------------------------------------------------------ agenda

    private fun evenements(jour: LocalDate): List<EventInfo> =
        if (!app.calendar.hasPermission()) emptyList()
        else runCatching { app.calendar.events(jour, 1) }.getOrDefault(emptyList()).sortedBy { it.begin }

    private fun cle(jour: LocalDate, e: EventInfo) = "$jour|${e.id}|${e.begin}"

    private fun trouver(cle: String): EventInfo? {
        val p = cle.split('|')
        val jour = runCatching { LocalDate.parse(p[0]) }.getOrNull() ?: return null
        val id = p.getOrNull(1)?.toLongOrNull(); val debut = p.getOrNull(2)?.toLongOrNull()
        val l = evenements(jour)
        return l.firstOrNull { it.id == id && it.begin == debut } ?: l.firstOrNull { it.id == id }
    }

    /** Adresses du rendez-vous : lieu, description, sinon fiche du client (sites de la Société). */
    private fun destinations(e: EventInfo): List<AgendaVoiture.Destination> {
        val client = runCatching {
            val data = app.societe.data
            data.chercherClients(AgendaVoiture.texteBrut(e.title)).firstOrNull()
                ?: e.location.takeIf { it.isNotBlank() }?.let { data.chercherClients(it).firstOrNull() }
        }.getOrNull()
        val duClient = client?.let { c ->
            c.sites.filter { it.adresse.isNotBlank() }.map { AgendaVoiture.Destination("🏭 ${it.nom.ifBlank { c.nom }} — ${it.adresse}", it.adresse) }
                .ifEmpty { listOfNotNull(c.adresse.takeIf { it.isNotBlank() }?.let { AgendaVoiture.Destination("${c.nom} — $it", it) }) }
        }.orEmpty()
        return AgendaVoiture.destinations(e, duClient)
    }

    private fun agenda(jour: LocalDate, mot: String): MutableList<MediaBrowser.MediaItem> {
        val l = mutableListOf<MediaBrowser.MediaItem>()
        if (!app.calendar.hasPermission()) {
            l += element(LIRE_JOUR + jour, "Agenda non autorisé", "Autorise l'agenda dans JARVIS sur le téléphone", "auto_agenda")
            return l
        }
        val evts = evenements(jour)
        l += element(LIRE_JOUR + jour, "🗣️ Lis-moi $mot", if (evts.isEmpty()) "Aucun rendez-vous" else "${evts.size} rendez-vous", "auto_parole")
        val maintenant = System.currentTimeMillis()
        val prochain = if (jour == LocalDate.now()) evts.firstOrNull { it.end > maintenant } else null
        evts.forEach { e ->
            val lieux = destinations(e)
            val quand = if (e.allDay) "Journée" else "${hm(e.begin)}–${hm(e.end)}"
            val fini = jour == LocalDate.now() && e.end < maintenant
            val titre = (if (fini) "✓ " else "") + "$quand · " + AgendaVoiture.texteBrut(e.title).ifBlank { "(sans titre)" }.take(60)
            val avant = if (e === prochain) "Prochain · " else ""
            val c = cle(jour, e)
            l += when (lieux.size) {
                0 -> element(EVT + c, titre, avant + "Pas d'adresse · touche pour les détails", "auto_agenda")
                1 -> element(EVT + c, titre, "$avant🧭 ${lieux[0].libelle.take(70)}", "auto_route")
                else -> element(EVT_LIEUX + c, titre, "$avant🧭 ${lieux.size} adresses", "auto_route", dossier = true)
            }
        }
        return l
    }

    /** Lance Waze (sinon Google Maps) vers [d] ; Android Auto affiche le guidage. */
    private fun naviguer(d: AgendaVoiture.Destination, titre: String) {
        afficher("🧭 $titre", "Waze → ${d.adresse}", PlaybackState.STATE_BUFFERING, "auto_route")
        portee.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val pc = PhoneControl(app)
                    pc.navigate(d.adresse, "d", if (pc.navApps().containsKey("Waze")) "Waze" else "")
                }.getOrElse { "Navigation impossible : ${it.message}" }
            }
            val ok = r.startsWith("🧭")
            afficher("🧭 $titre", if (ok) r.removePrefix("🧭 ") else r.take(160), PlaybackState.STATE_PAUSED, "auto_route")
            if (!ok) VoixVoiture.parler(this@JarvisMediaService, "Je n'ai pas pu lancer la navigation.")
        }
    }

    private fun jouerEvenement(c: String, numero: Int?) {
        portee.launch {
            val e = withContext(Dispatchers.IO) { trouver(c) }
            if (e == null) { lire("📅 Agenda", "Ce rendez-vous n'existe plus."); return@launch }
            val lieux = withContext(Dispatchers.IO) { destinations(e) }
            val titre = AgendaVoiture.texteBrut(e.title).ifBlank { "Rendez-vous" }
            val d = lieux.getOrNull(numero ?: 0)
            if (d != null) naviguer(d, titre) else lire("📅 $titre", AgendaVoiture.detailsParles(e), "auto_agenda")
        }
    }

    /** Bouton 🧭 de l'écran de lecture : Waze vers le prochain rendez-vous du jour qui a une adresse. */
    private fun prochainRendezVous() {
        portee.launch {
            val maintenant = System.currentTimeMillis()
            val cible = withContext(Dispatchers.IO) {
                evenements(LocalDate.now()).filter { !it.allDay && it.end > maintenant }
                    .firstNotNullOfOrNull { e -> destinations(e).firstOrNull()?.let { e to it } }
            }
            if (cible == null) lire("🧭 Prochain rendez-vous", "Pas de prochain rendez-vous avec une adresse aujourd'hui.", "auto_route")
            else naviguer(cible.second, AgendaVoiture.texteBrut(cible.first.title).ifBlank { "Rendez-vous" })
        }
    }

    private fun lireJour(date: String) {
        portee.launch {
            val jour = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
            val mot = if (jour == LocalDate.now()) "Aujourd'hui" else if (jour == LocalDate.now().plusDays(1)) "Demain" else "Ce jour-là"
            val texte = withContext(Dispatchers.IO) {
                if (!app.calendar.hasPermission()) "Autorise l'accès à l'agenda dans JARVIS sur le téléphone."
                else AgendaVoiture.resumeParle(evenements(jour), mot) { destinations(it) }
            }
            lire("📅 $mot", texte, "auto_agenda")
        }
    }

    // ------------------------------------------------------------------------------------------------ lecture / écran

    private fun etat(e: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH)
        .addCustomAction(PlaybackState.CustomAction.Builder(ACT_PARLER, "Parler à JARVIS", R.drawable.ic_auto_micro).build())
        .addCustomAction(PlaybackState.CustomAction.Builder(ACT_WAZE, "Prochain rendez-vous dans Waze", R.drawable.ic_auto_route).build())
        .addCustomAction(PlaybackState.CustomAction.Builder(ACT_RELIRE, "Relire la dernière réponse", R.drawable.ic_auto_relire).build())
        .setState(e, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()

    private fun meta(titre: String, texte: String, icone: String = "auto_micro") = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, titre)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, texte.take(300))
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, titre)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, texte.take(300))
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, icone(icone).toString())
        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, icone(icone).toString())
        .build()

    private fun afficher(titre: String, texte: String, e: Int, icone: String = "auto_micro") {
        session.setMetadata(meta(titre, texte, icone))
        session.setPlaybackState(etat(e))
    }

    private fun propre(t: String) = t.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1").replace(Regex("[#*_`>|]+"), "")
        .replace(Regex("\\s+"), " ").trim()

    private fun derniereReponse(): String = derniere.ifBlank {
        (CarBridge.contexte().ifEmpty { runCatching { app.chat.toList() }.getOrDefault(emptyList()) }).lastOrNull { !it.fromUser }?.text?.let { propre(it) }.orEmpty()
    }

    /** Lit [texte] avec la voix de JARVIS et l'affiche. */
    private fun lire(titre: String, texte: String, icone: String = "auto_micro") {
        afficher(titre, texte, PlaybackState.STATE_PLAYING, icone)
        VoixVoiture.parler(this, texte.take(1500)) { session.setPlaybackState(etat(PlaybackState.STATE_PAUSED)) }
    }

    /** 🎙️ : JARVIS ouvre le micro (bip, puis ta demande) ; l'écran de lecture suit la conversation. */
    private fun parler() {
        dernierParler = SystemClock.elapsedRealtime()
        VoixVoiture.taire()
        piloteJusqua = SystemClock.elapsedRealtime() + 8000
        afficher("🎙️ Je t'écoute…", "Parle après le bip", PlaybackState.STATE_BUFFERING)
        if (!JarvisService.ecouterDepuisVoiture(this)) {
            echec("Micro indisponible : autorise le micro et active l'écoute « Jarvis » dans JARVIS. Tu peux aussi toucher la loupe pour dicter.")
            return
        }
        // Rien après 8 s (micro bloqué par Android, écoute coupée) : on le dit au lieu de rester sur « Je t'écoute ».
        veille?.cancel()
        veille = portee.launch {
            delay(8000)
            if (!enCours) echec("Le micro ne s'est pas ouvert. Active l'écoute « Jarvis » dans JARVIS, ou touche la loupe pour dicter.")
        }
    }

    private fun echec(texte: String) {
        enCours = false; piloteJusqua = 0L; veille?.cancel(); derniereFin = SystemClock.elapsedRealtime()
        lire("🎙️ JARVIS", texte)
    }

    /** Suit l'écoute de JARVIS (service « Jarvis ») pendant une conversation lancée depuis la voiture. */
    private fun surActivite(e: ActiviteVoiture.Evt) {
        val pilote = enCours || SystemClock.elapsedRealtime() < piloteJusqua
        when (e.etape) {
            ActiviteVoiture.Etape.ECOUTE -> if (pilote) {
                enCours = true; veille?.cancel()
                afficher("🎙️ Je t'écoute…", "Parle maintenant", PlaybackState.STATE_PLAYING)
            }
            ActiviteVoiture.Etape.PARTIEL -> if (enCours) afficher("🎙️ « ${e.texte.take(90)} »", "…", PlaybackState.STATE_PLAYING)
            ActiviteVoiture.Etape.REFLEXION -> if (enCours) afficher("« ${e.question.take(90)} »", "JARVIS réfléchit…", PlaybackState.STATE_BUFFERING)
            ActiviteVoiture.Etape.REPONSE -> {
                derniere = propre(e.texte)
                if (enCours) afficher("« ${e.question.take(70)} »", derniere, PlaybackState.STATE_PLAYING)
            }
            ActiviteVoiture.Etape.FIN -> if (enCours) {
                enCours = false; derniereFin = SystemClock.elapsedRealtime()
                session.setPlaybackState(etat(PlaybackState.STATE_PAUSED))
            }
            ActiviteVoiture.Etape.ECHEC -> if (pilote) echec(e.texte)
        }
    }

    /** Raccourci ou phrase dictée à l'Assistant (« Ok Google, … sur JARVIS ») : JARVIS répond à voix haute. */
    private fun demander(demande: String) {
        if (demande.isBlank()) return
        afficher("« $demande »", "JARVIS réfléchit…", PlaybackState.STATE_BUFFERING)
        portee.launch {
            val question = ChatMsg(true, demande)
            app.chatCharger()
            if (CarBridge.contexte().isEmpty()) app.chat.toList().takeLast(10).forEach { CarBridge.ajouter(it) }
            CarBridge.ajouter(question); app.chatAjouter(question)
            val rep = withContext(Dispatchers.IO) {
                try { Brain(app.llm, ToolBox(app) { _, force -> !force }, app) { app.store.userName.value }.reply(CarBridge.contexte()) { } }
                catch (e: Throwable) { "Désolé, je n'ai pas pu faire ça : ${e.message ?: e.javaClass.simpleName}" }
            }
            val p = propre(rep).ifBlank { "C'est fait." }
            val reponse = ChatMsg(false, p)
            CarBridge.ajouter(reponse); app.chatAjouter(reponse)
            derniere = p
            lire("« $demande »", p)
            notifyChildrenChanged(ONGLET_RACCOURCIS)
        }
    }

    private fun relire() {
        val d = derniereReponse()
        if (d.isNotBlank()) lire("🔁 Dernière réponse", d, "auto_relire") else lire("JARVIS", "Pas encore de réponse à relire.", "auto_relire")
    }

    private inner class Rappels : MediaSession.Callback() {
        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            val q = query.orEmpty().trim()
            if (q.isBlank()) parler() else demander(q)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val id = mediaId.orEmpty()
            when {
                id == PARLER -> parler()
                id == RELIRE -> relire()
                id == MUSIQUE -> {
                    val c = fr.dd.jarvis.device.MusiqueBibliotheque.controleur(this@JarvisMediaService, fr.dd.jarvis.device.Musique.MORPHE_PACKAGE)
                    if (c != null) c.transportControls.play() else lire("🎵 Musique", "Aucune musique de YT Music Morphe à reprendre : demande-moi un titre.", "auto_musique")
                }
                id.startsWith(LIRE_JOUR) -> lireJour(id.removePrefix(LIRE_JOUR))
                id.startsWith(EVT) -> jouerEvenement(id.removePrefix(EVT), null)
                id.startsWith(EVT_LIEUX) -> jouerEvenement(id.removePrefix(EVT_LIEUX), 0)
                id.startsWith(LIEU) -> {
                    val r = id.removePrefix(LIEU)
                    jouerEvenement(r.substringBeforeLast('#'), r.substringAfterLast('#').toIntOrNull() ?: 0)
                }
                id.startsWith(RACCOURCI) -> RaccourcisVoiture.liste(app).getOrNull(id.removePrefix(RACCOURCI).toIntOrNull() ?: -1)?.let { demander(it) }
                id.startsWith(RECHERCHE) -> demander(id.removePrefix(RECHERCHE))
            }
        }

        override fun onCustomAction(action: String, extras: Bundle?) {
            when (action) {
                ACT_PARLER -> parler()
                ACT_WAZE -> prochainRendezVous()
                ACT_RELIRE -> relire()
            }
        }

        /**
         * ▶ (écran de lecture ou volant) = parler à JARVIS. Ignoré juste après la connexion (Android Auto relance la
         * dernière source audio) et hors voiture (boutons d'un casque Bluetooth).
         */
        override fun onPlay() {
            val t = SystemClock.elapsedRealtime()
            if (t - creeLe < 15_000 || !app.enVoiture || t - derniereFin < 5_000 || t - dernierParler < 5_000) {
                session.setPlaybackState(etat(PlaybackState.STATE_PAUSED)); return
            }
            parler()
        }

        override fun onPause() {
            VoixVoiture.taire(); JarvisService.interrompreDepuisVoiture()
            enCours = false; piloteJusqua = 0L; veille?.cancel(); derniereFin = SystemClock.elapsedRealtime()
            session.setPlaybackState(etat(PlaybackState.STATE_PAUSED))
        }

        override fun onStop() {
            VoixVoiture.taire(); JarvisService.interrompreDepuisVoiture()
            enCours = false; piloteJusqua = 0L; veille?.cancel()
            session.setPlaybackState(etat(PlaybackState.STATE_STOPPED))
        }
    }
}
