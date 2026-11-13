package fr.dd.jarvis.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Stockage des réglages. Les clés API sont chiffrées (EncryptedSharedPreferences).
 * Tous les champs sont observables par Compose.
 */
class Store(context: Context) {

    private val prefs: SharedPreferences = try {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "jarvis_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        // Keystore indisponible sur certains appareils : repli non chiffré plutôt que de planter.
        context.getSharedPreferences("jarvis_plain", Context.MODE_PRIVATE)
    }

    inner class SPref(private val key: String, def: String) {
        var value by mutableStateOf(prefs.getString(key, def) ?: def)
            private set

        fun set(v: String) {
            value = v
            prefs.edit().putString(key, v).apply()
        }
    }

    // Clés API sensibles : stockage séparé, chiffré uniquement. Aucun repli en clair.
    private val secretPrefs: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "jarvis_api_secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrNull()

    inner class SecretPref(private val key: String) {
        var value by mutableStateOf(secretPrefs?.getString(key, "") ?: "")
            private set
        val available: Boolean get() = secretPrefs != null
        fun set(v: String) {
            val secure = secretPrefs ?: throw IllegalStateException("Le stockage chiffré Android est indisponible : la clé API n'a pas été enregistrée.")
            secure.edit().putString(key, v).apply()
            value = v
        }
        fun clear() = set("")
    }

    /** Migration des anciennes listes Gemini/Groq vers le stockage chiffré dédié. */
    init {
        val secure = secretPrefs
        if (secure != null) {
            listOf("geminiKeys", "groqKeys", "openaiKeys").forEach { key ->
                val legacy = prefs.getString(key, null)
                if (!legacy.isNullOrBlank()) {
                    val current = secure.getString(key, null)
                    val saved = if (current.isNullOrBlank()) secure.edit().putString(key, legacy).commit() else true
                    if (saved) prefs.edit().remove(key).commit()
                }
            }
            // Suppression des anciens identifiants Google Flow : fournisseur retiré de JARVIS.
            secure.edit().remove("googleFlowApiToken").commit()
            prefs.edit().remove("googleFlowAccountEmail").remove("googleFlowModel").apply()
            // Migration automatique des clés OpenAI déjà collées dans le champ Gemini.
            // Écriture atomique : on ne retire les clés de Gemini que si leur copie chiffrée réussit.
            val geminiRaw = secure.getString("geminiKeys", "").orEmpty()
            val geminiList = geminiRaw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val openAiFound = geminiList.filter(fr.dd.jarvis.ai.KeyRing::estCleOpenAi)
            if (openAiFound.isNotEmpty()) {
                val openAiCurrent = secure.getString("openaiKeys", "").orEmpty()
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }
                val editor = secure.edit()
                    .putString("openaiKeys", (openAiCurrent + openAiFound).distinct().joinToString(System.lineSeparator()))
                    .putString("geminiKeys", geminiList.filterNot(fr.dd.jarvis.ai.KeyRing::estCleOpenAi).joinToString(System.lineSeparator()))
                check(editor.commit()) { "Migration sécurisée des clés OpenAI impossible." }
            }
        }
    }

    val apiKeysSecureAvailable: Boolean get() = secretPrefs != null

    private fun readApiKeyList(key: String): List<String> {
        val secure = secretPrefs ?: return emptyList()
        val raw = secure.getString(key, "") ?: ""
        return raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun writeApiKeyList(key: String, values: List<String>) {
        val secure = secretPrefs ?: throw IllegalStateException(
            "Stockage chiffré Android indisponible : les clés Gemini/Groq ne peuvent pas être modifiées ni synchronisées."
        )
        check(secure.edit().putString(key, values.joinToString(System.lineSeparator())).commit()) {
            "Échec de l'enregistrement chiffré des clés API."
        }
    }

    inner class BPref(private val key: String, def: Boolean) {
        var value by mutableStateOf(prefs.getBoolean(key, def))
            private set

        fun set(v: Boolean) {
            value = v
            prefs.edit().putBoolean(key, v).apply()
        }
    }

    inner class IPref(private val key: String, def: Int) {
        var value by mutableStateOf(prefs.getInt(key, def))
            private set

        fun set(v: Int) {
            value = v
            prefs.edit().putInt(key, v).apply()
        }
    }

    inner class FPref(private val key: String, def: Float) {
        var value by mutableStateOf(prefs.getFloat(key, def))
            private set

        fun set(v: Float) {
            value = v
            prefs.edit().putFloat(key, v).apply()
        }
    }

    // ---- Profil / apparence
    val userName = SPref("userName", "")
    val accent = SPref("accent", "cyan")
    val tileAlpha = FPref("tileAlpha", 0.16f)

    // ---- Cerveau (Groq) et agents (Gemini)
    val groqModel = SPref("groqModel", "llama-3.3-70b-versatile")
    val geminiModel = SPref("geminiModel", "gemini-3.8-flash")
    val openaiModel = SPref("openaiModel", "gpt-4.1-mini")
    val groqKeys: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readApiKeyList("groqKeys")) }
    val geminiKeys: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readApiKeyList("geminiKeys")) }
    val openaiKeys: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readApiKeyList("openaiKeys")) }

    // ---- Serveur personnel : SSH + Home Assistant
    val haUrl = SPref("haUrl", "")
    val haToken = SPref("haToken", "")
    val haDir = SPref("haDir", "")
    val sshHost = SPref("sshHost", "")
    val sshPort = SPref("sshPort", "22")
    val sshUser = SPref("sshUser", "")
    val sshPass = SPref("sshPass", "")
    val sshKey = SPref("sshKey", "")
    val sshFp = SPref("sshFp", "")
    /** Jeton Plex Media Server : toujours conservé dans le stockage chiffré du Keystore. */
    val plexToken = SecretPref("plexToken")
    val ghToken = SPref("ghToken", "")
    val scanAuto = BPref("scanAuto", true)
    val sshHostDistant = SPref("sshHostDistant", "")
    val sshPortDistant = SPref("sshPortDistant", "")
    /** Dernier relevé SSH des services du serveur (Docker, systemd, pm2…) et sa date (ms) : fiche « infrastructure » d'Hermès. */
    val serveurServices = SPref("serveurServices", "")
    val serveurServicesLe = SPref("serveurServicesLe", "0")
    // ---- Box internet (disque dur USB)
    val boxHost = SPref("boxHost", "")
    val boxProto = SPref("boxProto", "smb")
    val boxPort = SPref("boxPort", "21")
    val boxUser = SPref("boxUser", "")
    val boxPass = SPref("boxPass", "")
    // ---- Freebox OS : identité API et jeton d’application conservé uniquement chiffré
    val freeboxApiDomain = SPref("freeboxApiDomain", "")
    val freeboxHttpsPort = SPref("freeboxHttpsPort", "443")
    val freeboxUid = SPref("freeboxUid", "")
    val freeboxTrackId = SPref("freeboxTrackId", "")
    val freeboxAppToken = SecretPref("freeboxAppToken")
    // ---- Imprimante réseau découverte (IPP), utilisable localement ou via le serveur Debian
    val printerHost = SPref("printerHost", "")
    val printerName = SPref("printerName", "")
    val printerPath = SPref("printerPath", "/ipp/print")

    // ---- Génération d'images (Cloudflare Workers AI) : lignes « idCompte:jeton », rotation automatique
    val cfModel = SPref("cfModel", "@cf/black-forest-labs/flux-1-schnell")
    /** Modèle de conversation Cloudflare, utilisé uniquement en dernier recours quand toutes les clés Groq sont épuisées (mêmes comptes que les images). */
    val cfChatModel = SPref("cfChatModel", "@cf/meta/llama-3.3-70b-instruct-fp8-fast")
    /** Ollama du serveur de la maison : IA locale de secours et sous-agent d'Hermès (adresse vide = serveur SSH:11434). */
    val ollamaActif = BPref("ollamaActif", true)
    val ollamaUrl = SPref("ollamaUrl", "")
    val ollamaModele = SPref("ollamaModele", "")
    val cfAccounts: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("cfAccounts")) }
    /** Ajoute un compte (ID + clé API). Retourne un message d'erreur, ou null si c'est bon. */
    fun addCfAccount(idRaw: String, keyRaw: String): String? {
        var id = idRaw.trim(); var key = keyRaw.trim()
        if (id.contains(':') && key.isEmpty()) { key = id.substringAfter(':').trim(); id = id.substringBefore(':').trim() }
        else if (key.contains(':') && id.isEmpty()) { id = key.substringBefore(':').trim(); key = key.substringAfter(':').trim() }
        id = id.replace(Regex("^.*/accounts/"), "").substringBefore('/').trim()
        if (id.isEmpty()) return "ID de compte manquant."
        if (key.isEmpty()) return "Clé API manquante."
        if (!Regex("[0-9a-fA-F]{32}").matches(id)) return "L'ID de compte doit faire 32 caractères hexadécimaux (visible dans l'URL dash.cloudflare.com/<ID>/…)."
        if (key.length < 20 || key.any { it.isWhitespace() }) return "Clé API invalide (jeton Workers AI, sans espace)."
        val entry = "$id:$key"
        if (entry in cfAccounts) return "Ce compte est déjà enregistré."
        cfAccounts.add(entry); writeList("cfAccounts", cfAccounts)
        prefs.edit().commit()
        return null
    }
    fun removeCfAccount(i: Int) { if (i in cfAccounts.indices) cfAccounts.removeAt(i); writeList("cfAccounts", cfAccounts) }


    // Fournisseurs externes configurables depuis la conversation Hermès/Jarvis.
    // Les secrets restent exclusivement dans EncryptedSharedPreferences.
    private val externalProviderIds = setOf("zai", "openrouter", "nvidia", "mistral", "huggingface")
    fun setExternalProvider(providerRaw: String, apiKeyRaw: String, modelRaw: String = "", endpointRaw: String = "") {
        val provider = providerRaw.trim().lowercase()
        require(provider in externalProviderIds) { "Fournisseur non pris en charge : $provider" }
        val apiKey = apiKeyRaw.trim()
        require(apiKey.length >= 8 && !apiKey.any { it.isWhitespace() }) { "Clé API invalide." }
        val secure = secretPrefs ?: throw IllegalStateException("Stockage chiffré Android indisponible : clé non enregistrée.")
        check(secure.edit().putString("external_${provider}_key", apiKey).commit()) { "Échec de l'enregistrement chiffré." }
        prefs.edit().putString("external_${provider}_model", modelRaw.trim())
            .putString("external_${provider}_endpoint", endpointRaw.trim()).apply()
    }
    fun externalProviderKey(provider: String): String =
        if (provider in externalProviderIds) secretPrefs?.getString("external_${provider}_key", "").orEmpty() else ""
    fun externalProviderModel(provider: String): String =
        if (provider in externalProviderIds) prefs.getString("external_${provider}_model", "").orEmpty() else ""
    fun externalProviderEndpoint(provider: String): String =
        if (provider in externalProviderIds) prefs.getString("external_${provider}_endpoint", "").orEmpty() else ""
    fun externalProvidersConfigured(): List<String> =
        externalProviderIds.filter { externalProviderKey(it).isNotBlank() }

    fun addGroqKeys(raw: String) = addApiKeys(groqKeys, "groqKeys", raw)
    /** Les clés OpenAI reconnues dans le champ Gemini sont automatiquement rangées ici. */
    fun addGeminiKeys(raw: String) {
        val parts = raw.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.length >= 8 }.distinct()
        val openAi = parts.filter(fr.dd.jarvis.ai.KeyRing::estCleOpenAi)
        val google = parts.filterNot(fr.dd.jarvis.ai.KeyRing::estCleOpenAi)
        if (google.isNotEmpty()) addApiKeys(geminiKeys, "geminiKeys", google.joinToString("\n"))
        if (openAi.isNotEmpty()) addApiKeys(openaiKeys, "openaiKeys", openAi.joinToString("\n"))
    }
    fun addOpenAiKeys(raw: String) = addApiKeys(openaiKeys, "openaiKeys", raw)
    fun removeOpenAiKey(i: Int) {
        if (i !in openaiKeys.indices) return
        val updated = openaiKeys.toList().toMutableList().also { it.removeAt(i) }
        writeApiKeyList("openaiKeys", updated)
        openaiKeys.removeAt(i)
    }
    fun removeGroqKey(i: Int) {
        if (i !in groqKeys.indices) return
        val updated = groqKeys.toList().toMutableList().also { it.removeAt(i) }
        writeApiKeyList("groqKeys", updated)
        groqKeys.removeAt(i)
    }

    fun removeGeminiKey(i: Int) {
        if (i !in geminiKeys.indices) return
        val updated = geminiKeys.toList().toMutableList().also { it.removeAt(i) }
        writeApiKeyList("geminiKeys", updated)
        geminiKeys.removeAt(i)
    }

    private fun addApiKeys(list: SnapshotStateList<String>, key: String, raw: String) {
        val secure = secretPrefs ?: throw IllegalStateException(
            "Stockage chiffré Android indisponible : active les services de sécurité du téléphone avant d'ajouter des clés."
        )
        val parts = raw.split(Regex("[\\s,;]+")).map { it.trim() }
            .filter { it.length >= 8 && it !in list }.distinct()
        if (parts.isEmpty()) return
        val updated = (list.toList() + parts).distinct()
        check(secure.edit().putString(key, updated.joinToString(System.lineSeparator())).commit()) {
            "Échec de l'enregistrement chiffré des clés API."
        }
        list.addAll(parts)
    }

    private fun readList(k: String): List<String> =
        (prefs.getString(k, "") ?: "").split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun writeList(k: String, l: List<String>) {
        prefs.edit().putString(k, l.joinToString("\n")).apply()
    }

    // ---- Applications par défaut par type de demande (navigation, musique…), lignes « type=application »
    val appPrefs = androidx.compose.runtime.mutableStateMapOf<String, String>().also { m ->
        readList("appPrefs").forEach { l -> l.substringBefore('=', "").takeIf { it.isNotBlank() }?.let { k -> m[k] = l.substringAfter('=') } }
    }
    fun setAppPref(type: String, app: String?) {
        if (app.isNullOrBlank()) appPrefs.remove(type) else appPrefs[type] = app
        writeList("appPrefs", appPrefs.map { "${it.key}=${it.value}" })
    }

    // Clé d'accès Picovoice (gratuite, console.picovoice.ai) : détecteur « Jarvis » de type OK Google
    val picoKey = SPref("picoKey", "")

    // ---- Voix
    val speakReplies = BPref("speakReplies", true)
    /** Voix naturelle (synthèse Gemini) au lieu de la voix Android ; repli automatique sur la voix Android. */
    val voixNaturelle = BPref("voixNaturelle", true)
    val voixNom = SPref("voixNom", "Charon")
    /** Personnage de voix (homme, femme, jeune, âgé, IA…) ; « perso » = voix Gemini [voixNom] + description [voixStyle]. */
    val voixPersonnage = SPref("voixPersonnage", "")
    /** Mode présence automatique : JARVIS plein écran quand le téléphone est posé en charge (et qu'on ne le touche plus). */
    val presenceAuto = BPref("presenceAuto", true)
    /** Autorise l'écran de présence déjà ouvert à rester visible au-dessus du verrouillage Android. */
    val presenceVerrouillage = BPref("presenceVerrouillage", true)
    /** Mode Présence épinglé comme écran de veille/verrouillage visuel ; restauré au retour de l’activité. */
    val presenceModeActif = BPref("presenceModeActif", false)
    // ---- Alerte de départ, météo des rendez-vous, routines, journal, montre, empreinte vocale
    val departAlertes = BPref("departAlertes", true)
    val departNotifie = SPref("departNotifie", "")
    val meteoAlertes = BPref("meteoAlertes", true)
    val meteoNotifie = SPref("meteoNotifie", "")
    /** Clé TomTom (gratuite, facultative) : trafic en temps réel pour l'heure de départ. */
    val tomtomKey = SPref("tomtomKey", "")
    val routinesDernier = SPref("routinesDernier", "{}")
    val veilleLieu = SPref("veilleLieu", "")
    val journalAuto = BPref("journalAuto", true)
    val journalHeure = SPref("journalHeure", "21")
    val journalFait = SPref("journalFait", "")
    val poignet = BPref("poignet", false)
    val empreinteVocale = BPref("empreinteVocale", false)
    val empreinteSeuil = SPref("empreinteSeuil", "")
    // ---- Grand ménage des mails
    val menageCompte = SPref("menageCompte", "")
    val menageDemarre = SPref("menageDemarre", "")
    val menageBilan = SPref("menageBilan", "{}")
    val menageErreurs = SPref("menageErreurs", "0")
    // ---- Android Auto
    /** Raccourcis de la voiture (une demande par ligne) ; vide = raccourcis par défaut. */
    val raccourcisVoiture = SPref("raccourcisVoiture", "")
    /** Message d'accueil de JARVIS à la connexion à la voiture (et annonce vocale si [voitureAccueilVocal]). */
    val voitureAccueil = BPref("voitureAccueil", true)
    val voitureAccueilVocal = BPref("voitureAccueilVocal", false)
    /** L'utilisateur a rendu JARVIS visible dans Android Auto (réinstallation « Play Store ») : on le lui repropose après chaque mise à jour. */
    val autoVisibilite = BPref("autoVisibilite", false)
    val autoReinstallTs = SPref("autoReinstallTs", "0")
    /** Écrans voiture (Car App Library) : seulement pour une installation faite par le Play Store ; sinon JARVIS est une appli média. */
    val autoEcrans = BPref("autoEcrans", false)
    /** Jeton de la dernière notification « Rétablir » traitée (évite de relancer l'installateur en boucle). */
    val autoPlayJeton = SPref("autoPlayJeton", "")
    /** Dernier verdict d'Android Auto sur JARVIS relevé dans le journal système (à la connexion à la voiture). */
    val autoJournal = SPref("autoJournal", "")
    val voixStyle = SPref("voixStyle", "")
    /** Voix Android choisie (nom de la voix du moteur Google) ; vide = la meilleure voix française disponible. */
    val voixAndroid = SPref("voixAndroid", "")
    val rapportsPhotosV2 = BPref("rapportsPhotosV2", false)
    /** Rapports d'intervention régénérés une fois avec la nouvelle présentation (emojis, tableau des séries). */
    val rapportsV3 = BPref("rapportsV3", false)
    /** Modes automatiques (voiture, chantier, nuit, maison) et mode forcé (redevient automatique au bout de 4 h). */
    val modesAuto = BPref("modesAuto", true)
    val modeForce = SPref("modeForce", "")
    val modeForceJusqua = SPref("modeForceJusqua", "0")
    val nuitDebut = SPref("nuitDebut", "22")
    val nuitFin = SPref("nuitFin", "7")
    /** Dernière installation où une arrivée a été signalée (« idClient|idSite|date »). */
    val dernierChantier = SPref("dernierChantier", "")
    /** Empreinte (ou code) demandée avant les actions sensibles. */
    val biometrie = BPref("biometrie", true)
    /** Alertes de santé déjà signalées (clé → horodatage), pour ne pas répéter. */
    val santeAlertes = SPref("santeAlertes", "")
    val conversationMode = BPref("conversationMode", false)

    // ---- Sécurité
    val confirmSensitive = BPref("confirmSensitive", true)
    val analyseGemini = BPref("analyseGemini", true)

    // ---- Batterie & vitesse
    /** Économie d'énergie : « auto » (économiseur Android ou batterie < 20 %), « performance », « eco ». */
    val modeEnergie = SPref("modeEnergie", "balanced")
    /** Écoute « Jarvis » économe : le détecteur hors-ligne ne travaille que quand il y a du son (pas pendant le silence). */
    val ecouteEconome = BPref("ecouteEconome", true)
    /** Mode « Hermès pour tout » : les commandes simples (appel, SMS, musique, domotique, agenda…) restent à Jarvis, plus rapide. */
    val hermesRapide = BPref("hermesRapide", true)
    /** IA du moteur Hermès (via le relais Jarvis) : « gemini » = Gemini d'abord, requête complète (précis, sans attente de
     *  quota) puis Groq compacté en secours ; « groq » = Groq d'abord (requêtes compactées), comme avant. */
    val relaisHermes = SPref("relaisHermes", "gemini")
    /** Derniers passages des travaux lourds du démarrage (index du graphe, contrôle des documents), en ms. */
    val grapheIndexeLe = SPref("grapheIndexeLe", "")
    val docsVerifiesLe = SPref("docsVerifiesLe", "")
    /** Dernier onglet ouvert dans les Réglages (« onglet/sous-onglet »). */
    val reglagesVue = SPref("reglagesVue", "")

    // ---- Arrière-plan : écoute « Jarvis » et agents autonomes
    val wakeOn = BPref("wakeOn", false)
    val convSeconds = IPref("convSeconds", 25)
    /** Fin de la conversation vocale après ce nombre de secondes sans parole (après la réponse de JARVIS). */
    val silenceSec = IPref("silenceSec", 6)
    /** Couper la parole à JARVIS en reprenant la parole pendant qu'il répond. */
    val coupeParole = BPref("coupeParole", true)
    /** Voix en mode « appel » sur le haut-parleur pendant la réponse (annulation d'écho : coupure de parole fiable). */
    val duplex = BPref("duplex", true)
    val muteBeeps = BPref("muteBeeps", true)
    val agentsOn = BPref("agentsOn", false)
    val agentMails = BPref("agentMails", true)
    val agentFactures = BPref("agentFactures", true)
    val agentPlanning = BPref("agentPlanning", true)
    val agentNotifs = BPref("agentNotifs", true)
    val agentRanger = BPref("agentRanger", true)
    val notifLive = BPref("notifLive", true)
    val agentMinutes = IPref("agentMinutes", 15)
    val agentLastFact = SPref("agentLastFact", "")
    val agentLastNotif = SPref("agentLastNotif", "0")
    val agentLastRunAt = SPref("agentLastRunAt", "")
    val agentLastRunStatus = SPref("agentLastRunStatus", "Aucun cycle enregistré")
    /** Historique local des 20 derniers cycles, limité aux résumés sans contenu de mails ou notifications. */
    val agentRunHistory = SPref("agentRunHistory", "")

    // ---- Discord (bot)
    val discordToken = SPref("discordToken", "")
    val discordGuildId = SPref("discordGuildId", "")

    // ---- TikTok (OAuth, compte personnel)
    val tiktokClientKey = SPref("tiktokClientKey", "")
    val tiktokClientSecret = SPref("tiktokClientSecret", "")
    val tiktokAccessToken = SPref("tiktokAccessToken", "")
    val tiktokRefreshToken = SPref("tiktokRefreshToken", "")
    val tiktokOpenId = SPref("tiktokOpenId", "")

    // ---- Génération vidéo IA (tiktok_video) : Hugging Face (ZeroGPU, Spaces officiels), compte gratuit sans
    // carte bancaire, token créé sur huggingface.co/settings/tokens (lecture suffit). Pollinations.ai exige
    // maintenant un abonnement payant ("Pollen") même pour la vidéo — plus utilisable gratuitement.
    val huggingFaceToken = SPref("huggingFaceToken", "")
    val falApiKey = SecretPref("falApiKey")
    val dreaminaApiKey = SecretPref("dreaminaApiKey")
    val dreaminaModel = SPref("dreaminaModel", "seedance-1-5-pro-251215")
    // ---- Worker Cloudflare personnel pour animer une image avec Wan 2.2 (sans token Hugging Face côté Android)
    val imageVideoWorkerUrl = SPref("imageVideoWorkerUrl", "")
    val imageVideoWorkerToken = SPref("imageVideoWorkerToken", "")
    val tiktokTokenExpiry = SPref("tiktokTokenExpiry", "0")   // epoch secondes
    val tiktokOAuthState = SPref("tiktokOAuthState", "")      // anti-CSRF, vérifié au retour

    // ---- Annonces vocales (SMS, mails, notifications) : mode par catégorie (off | annonce | lecture) et sonnerie (URI, « default » ou vide = aucune)
    val annOn = BPref("annOn", false)
    val annPrive = BPref("annPrive", false)

    // ---- Coffre-fort de la société (serveur) : adresse, empreinte du certificat, accès de cet appareil, clés
    val coffreUrlLocal = SPref("coffreUrlLocal", "")
    val coffreUrlDistant = SPref("coffreUrlDistant", "")
    val coffreEmpreinte = SPref("coffreEmpreinte", "")
    val coffreJeton = SecretPref("coffreJeton")
    val coffreAppareil = SPref("coffreAppareil", "")
    val coffreRole = SPref("coffreRole", "")
    val coffreCategories = SPref("coffreCategories", "")
    val coffreCles = SecretPref("coffreCles")
    val coffreMaitre = SecretPref("coffreMaitre")
    val coffreSeq = SPref("coffreSeq", "0")
    val coffreDerniere = SPref("coffreDerniere", "0")
    val coffreBilan = SPref("coffreBilan", "")
    val coffreAuto = BPref("coffreAuto", true)

    // ---- Anti-vol par SMS (« JARVIS <code> VOL ») : le code est haché (PBKDF2) dans le stockage chiffré
    val antivolActif = BPref("antivolActif", false)
    val antivolCode = SecretPref("antivolCode")
    val antivolSecours = SPref("antivolSecours", "")
    val antivolAlerte = BPref("antivolAlerte", false)
    val antivolMessage = SPref("antivolMessage", "")
    val antivolSuiviJusqua = SPref("antivolSuiviJusqua", "0")
    val antivolSuiviNumero = SPref("antivolSuiviNumero", "")
    val antivolSim = SPref("antivolSim", "")
    val antivolSimVerrou = BPref("antivolSimVerrou", true)
    val antivolEchecs = SPref("antivolEchecs", "{}")
    val antivolJournal = SPref("antivolJournal", "[]")
    /** Heure (ms) du dernier SMS transmis par Android à JARVIS (diagnostic de l'anti-vol). */
    val antivolDernierSms = SPref("antivolDernierSms", "")
    /** Heure (ms) du dernier message « JARVIS… » vu dans les notifications d'une messagerie (Chat/RCS). */
    val antivolDerniereNotif = SPref("antivolDerniereNotif", "")
    val antivolVolume = SPref("antivolVolume", "")
    /** Pendant l'alerte : réglages d'avant à remettre (filtre « Ne pas déranger »|mode sonnerie ; notifications sur l'écran verrouillé). */
    val antivolSilence = SPref("antivolSilence", "")
    val antivolNotifsEcran = SPref("antivolNotifsEcran", "")
    val annApps = SPref("annApps", "")
    private val annModes = androidx.compose.runtime.mutableStateMapOf<String, String>().also { m ->
        listOf("sms", "mail", "autres").forEach { m[it] = prefs.getString("annMode_$it", if (it == "autres") "annonce" else "lecture") ?: "lecture" }
    }
    private val annSons = androidx.compose.runtime.mutableStateMapOf<String, String>().also { m ->
        listOf("sms", "mail", "autres").forEach { m[it] = prefs.getString("annSon_$it", "default") ?: "default" }
    }
    fun annMode(c: String) = annModes[c] ?: "lecture"
    fun setAnnMode(c: String, v: String) { annModes[c] = v; prefs.edit().putString("annMode_$c", v).apply() }
    fun annSon(c: String) = annSons[c] ?: "default"
    fun setAnnSon(c: String, v: String) { annSons[c] = v; prefs.edit().putString("annSon_$c", v).apply() }
    val agentArchiver = BPref("agentArchiver", false)

    // ---- Mails (IMAP)
    val mailHost = SPref("mailHost", "imap.gmail.com")
    val mailPort = SPref("mailPort", "993")
    val mailUser = SPref("mailUser", "")
    val mailPass = SPref("mailPass", "")
    val gmailAccounts: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("gmailAccounts")) }

    fun addGmailAccount(email: String) {
        if (email.isNotBlank() && gmailAccounts.none { it.equals(email, true) }) gmailAccounts.add(email)
        writeList("gmailAccounts", gmailAccounts)
    }

    fun removeGmailAccount(email: String) {
        gmailAccounts.removeAll { it == email }
        writeList("gmailAccounts", gmailAccounts)
    }

    /** Comptes mis de côté : conservés, mais ignorés par JARVIS sauf demande explicite (« IMAP » désigne le compte IMAP). */
    val disabledMail: SnapshotStateList<String> = mutableStateListOf<String>().also { it.addAll(readList("disabledMail")) }
    fun mailActive(account: String) = disabledMail.none { it.equals(account, true) }
    fun setMailActive(account: String, active: Boolean) {
        disabledMail.removeAll { it.equals(account, true) }
        if (!active) disabledMail.add(account)
        writeList("disabledMail", disabledMail)
    }

    /** Préfixe des libellés Gmail créés par JARVIS (vide = « Urgent », « Clients »… sans « JARVIS/ » devant). */
    val mailLabelPrefix = SPref("mailLabelPrefix", "")
    /** Règle persistante du tri automatique : lus (défaut), non_lus ou tous. Le grand ménage l’ignore volontairement. */
    val mailTriStatut = SPref("mailTriStatut", "lus")

    fun mailConfigured() = gmailAccounts.isNotEmpty() || mailHost.value.isNotBlank() && mailUser.value.isNotBlank() && mailPass.value.isNotBlank()

    // ---- Obsidian (dossier choisi via le sélecteur Android)
    val obsidianUri = SPref("obsidianUri", "")

    // ---- Hermès (agent Nous Research dans Termux) : cerveau principal quand il est en ligne
    /** auto = Hermès cerveau unique pour toutes les demandes ; il choisit ses sous-agents (Gemini, Groq, Cloudflare…) ou répond lui-même. demande = Hermès uniquement si nommé ; off = cerveau Jarvis de secours. */
    val hermesMode = SPref("hermesMode", "tout")
    /** Retour à « Hermès pour tout » (demandé après l'essai « Jarvis d'abord ») : appliqué une fois. */
    val hermesModeMigre4 = BPref("hermesModeHermesDirect", false)
    /** Migration 3 : « Jarvis d'abord, Hermès si besoin » devient le défaut (demande de l'utilisateur). */
    val hermesModeMigre3 = BPref("hermesModeJarvisDabord", false)
    /** Migration unique : l'ancien mode « auto » devient « Hermès pour tout » (nouveau défaut, avec réflexes). */
    val hermesModeMigre = BPref("hermesModeMigreTout", false)
    /** Seconde migration : « Si je dis Hermès » devient aussi « Hermès pour tout » (Hermès consulté sans le nommer). */
    val hermesModeMigre2 = BPref("hermesModeMigreTout2", false)
    /** Version du hook de mémoire partagée installée dans Hermès (0 = jamais). */
    val hookMemoireVersion = SPref("hookMemoireVersion", "0")
    /** Connexion facultative au serveur officiel Suno MCP (OAuth). */
    val sunoMcpActive = BPref("sunoMcpActive", false)
    val modeAdulte = BPref("modeAdulte", false)
    /** Modèle Gemini utilisé par Hermès (vide = celui des agents Gemini). */
    val hermesModele = SPref("hermesModele", "")
    /** Jetons locaux (127.0.0.1 uniquement) : API d'Hermès et pont MCP « jarvis ». */
    val hermesCle = SPref("hermesCle", "")
    val mcpJeton = SPref("mcpJeton", "")
    val hermesInstalle = BPref("hermesInstalle", false)
    /** Version de configuration Hermès déjà poussée dans Termux ; une version plus récente déclenche une resynchronisation unique. */
    val hermesConfigVersion = IPref("hermesConfigVersion", 0)

    // ---- Briefing du matin
    val briefingOn = BPref("briefingOn", false)
    val briefingHour = IPref("briefingHour", 7)
    val briefingMinute = IPref("briefingMinute", 30)
    val lastBriefing = SPref("lastBriefing", "")
    /** Dernière synthèse calculée par les agents proactifs, affichée dans le planning. */
    val proactiveSummary = SPref("proactiveSummary", "")

    // ---- Agendas : alias, couleurs, visibilité (réglages locaux, n'altèrent pas les agendas du téléphone)
    val calAlias = mutableStateMapOf<Long, String>().also { it.putAll(readMapStr("calAlias")) }
    val calColor = mutableStateMapOf<Long, Int>().also { it.putAll(readMapInt("calColor")) }
    val eventColor = mutableStateMapOf<Long, Int>().also { it.putAll(readMapInt("eventColor")) }
    val hiddenCals = mutableStateListOf<Long>().also { it.addAll(readList("hiddenCals").mapNotNull { s -> s.toLongOrNull() }) }

    fun setCalAlias(id: Long, name: String) {
        if (name.isBlank()) calAlias.remove(id) else calAlias[id] = name.trim()
        writeMapStr("calAlias", calAlias)
    }

    fun setCalColor(id: Long, color: Int?) {
        if (color == null) calColor.remove(id) else calColor[id] = color
        writeMapInt("calColor", calColor)
    }

    fun setEventColor(eventId: Long, color: Int?) {
        if (color == null) eventColor.remove(eventId) else eventColor[eventId] = color
        writeMapInt("eventColor", eventColor)
    }

    fun toggleCalendar(id: Long) {
        if (id in hiddenCals) hiddenCals.remove(id) else hiddenCals.add(id)
        writeList("hiddenCals", hiddenCals.map { it.toString() })
    }

    private fun readMapStr(k: String): Map<Long, String> = try {
        val o = JSONObject(prefs.getString(k, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to o.getString(key) } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun readMapInt(k: String): Map<Long, Int> = try {
        val o = JSONObject(prefs.getString(k, "{}") ?: "{}")
        o.keys().asSequence().mapNotNull { key -> key.toLongOrNull()?.let { it to o.getInt(key) } }.toMap()
    } catch (e: Exception) {
        emptyMap()
    }

    private fun writeMapStr(k: String, m: Map<Long, String>) {
        val o = JSONObject()
        m.forEach { (key, v) -> o.put(key.toString(), v) }
        prefs.edit().putString(k, o.toString()).apply()
    }

    private fun writeMapInt(k: String, m: Map<Long, Int>) {
        val o = JSONObject()
        m.forEach { (key, v) -> o.put(key.toString(), v) }
        prefs.edit().putString(k, o.toString()).apply()
    }
}
