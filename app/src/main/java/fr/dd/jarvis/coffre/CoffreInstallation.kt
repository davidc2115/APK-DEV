package fr.dd.jarvis.coffre

import android.os.Build
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.Serveur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Installation du coffre-fort sur le serveur SSH (à côté de Home Assistant et du bot Discord) : envoi des fichiers,
 * construction et démarrage du conteneur Docker « coffre-jarvis », lecture de l'empreinte du certificat et du jeton
 * d'installation, enregistrement de ce téléphone comme administrateur, premier envoi des données.
 * Invitations (QR code), récupération sur un nouveau téléphone, ouverture du port sur la Freebox.
 */
object CoffreInstallation {
    const val PORT = 8443
    private const val DOSSIER = "coffre-jarvis"

    fun nomAppareil() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private fun exec(app: JarvisApp, cmd: String, timeoutS: Int = 60) = Serveur.exec(app, cmd, timeoutS)

    /** Commande Docker (sans sudo si l'utilisateur est dans le groupe docker, sinon avec). */
    private fun docker(app: JarvisApp, args: String, timeoutS: Int = 60): Pair<Int, String> {
        val r = exec(app, "docker $args 2>&1", timeoutS)
        return if (r.first != 0 && Regex("permission denied|docker.sock", RegexOption.IGNORE_CASE).containsMatchIn(r.second)) exec(app, "sudo docker $args 2>&1", timeoutS) else r
    }

    suspend fun installer(app: JarvisApp, progres: (String) -> Unit): String = withContext(Dispatchers.IO) {
        if (!Serveur.sshConfigure(app)) return@withContext "❌ Configure d'abord l'accès SSH à ton serveur (Réglages › Maison › Serveur)."
        progres("Envoi des fichiers sur le serveur…")
        exec(app, "mkdir -p ~/$DOSSIER/data ~/$DOSSIER/sauvegardes")
        for (f in listOf("serveur.py", "Dockerfile", "docker-compose.yml")) {
            Serveur.envoyerOctets(app, "$DOSSIER/$f", app.assets.open("coffre/$f").use { it.readBytes() })
        }
        progres("Construction et démarrage du coffre (2 à 5 minutes la première fois)…")
        var r = docker(app, "compose -f ~/$DOSSIER/docker-compose.yml --project-directory ~/$DOSSIER up -d --build", 900)
        if (r.first != 0 && r.second.contains("is not a docker command", true)) {
            r = exec(app, "cd ~/$DOSSIER && (docker-compose up -d --build 2>&1 || sudo docker-compose up -d --build 2>&1)", 900)
        }
        if (r.first != 0) return@withContext "❌ Démarrage du coffre impossible :\n" + r.second.takeLast(600)
        progres("Recherche des adresses du serveur…")
        val local = adresseLocale(app)
        val distant = adresseDistante(app)
        val tunnel = Coffre.tunnel(app)
        progres("Attente du coffre…")
        var empreinte = ""; var installation = ""
        for (essai in 0 until 30) {
            if (empreinte.isBlank()) empreinte = docker(app, "exec coffre-jarvis cat /data/tls/empreinte.txt").second.trim().takeIf { Regex("^[0-9a-f]{64}$").matches(it) }.orEmpty()
            installation = docker(app, "exec coffre-jarvis cat /data/installation.txt").second.trim().takeIf { Regex("^[A-Za-z0-9_-]{20,}$").matches(it) }.orEmpty()
            if (empreinte.isNotBlank() && installation.isNotBlank()) break
            if (empreinte.isNotBlank()) {
                // Pas de jeton d'installation : coffre déjà initialisé (réinstallation) → récupération avec le code.
                val ok = runCatching { CoffreClient(listOf(local, distant), empreinte, tunnel).sante().optBoolean("initialise") }.getOrDefault(false)
                if (ok) return@withContext "ℹ️ Le coffre tourne déjà sur ton serveur et a déjà un propriétaire. Utilise « Récupérer avec mon code » (code de récupération noté à la première installation) ou une invitation depuis ton autre téléphone."
            }
            delay(3000)
        }
        if (empreinte.isBlank() || installation.isBlank()) return@withContext "❌ Le coffre ne répond pas. Journal : " + docker(app, "logs --tail 20 coffre-jarvis").second.takeLast(500)
        progres("Enregistrement de ce téléphone (administrateur)…")
        val maitre = app.store.coffreMaitre.value.takeIf { it.isNotBlank() }?.let { CoffreCrypto.deB64(it) } ?: CoffreCrypto.nouvelleCleMaitre()
        val client = CoffreClient(listOf(local, distant), empreinte, tunnel)
        val rep = try { client.init(installation, CoffreCrypto.preuveRecuperation(maitre), nomAppareil()) } catch (e: CoffreException) {
            return@withContext "❌ Le coffre tourne sur le serveur mais ce téléphone n'arrive pas à le joindre (${e.message}).\n" +
                "Vérifie que Docker publie bien le port $PORT : " + docker(app, "port coffre-jarvis").second.trim().ifBlank { "aucun port publié" }
        }
        Coffre.enregistrer(app, local, distant, empreinte, rep, maitre, null)
        progres("Ouverture du port $PORT sur la box (accès des autres téléphones hors de la maison)…")
        val port = if (app.store.freeboxAppToken.value.isNotBlank() && local.isNotBlank()) runCatching { ouvrirPort(app) }.getOrElse { "❌ ${it.message}" } else null
        progres("Premier envoi des données de la société…")
        val bilan = Coffre.synchroniser(app)
        "✅ Coffre-fort installé sur ton serveur et ce téléphone enregistré comme administrateur.\n$bilan\n\n" +
            "🔐 Ce téléphone joint le coffre " + (if (tunnel != null) "directement à la maison, et sinon par le tunnel SSH (aucun port à ouvrir pour toi).\n" else "par ${listOf(local, distant).filter { it.isNotBlank() }.joinToString(" ou ")}.\n") +
            (when {
                port == null -> "🌍 Pour les téléphones de tes salariés hors de la maison, ouvre le port $PORT de la box vers ${local.removePrefix("https://").substringBefore(':').ifBlank { "le serveur" }} (bouton « Ouvrir le port »).\n"
                port.startsWith("✅") -> "🌍 Port $PORT ouvert sur la Freebox : $port\n"
                else -> "🌍 Port $PORT pas ouvert sur la Freebox ($port) : nécessaire seulement pour les téléphones sans accès SSH, hors de la maison.\n"
            }) +
            "\n⚠️ Note maintenant ton CODE DE RÉCUPÉRATION (bouton « Code de récupération ») : sans lui, impossible de retrouver les données chiffrées si tu perds ce téléphone."
    }

    /** IP du serveur sur le réseau de la maison : celle des réglages si elle est privée, sinon lue sur le serveur par SSH. */
    fun ipLocale(app: JarvisApp): String? {
        val h = app.store.sshHost.value.trim()
        if (Serveur.ipPrivee(h)) return h
        app.store.coffreUrlLocal.value.let { runCatching { java.net.URI(it).host }.getOrNull() }?.takeIf { Serveur.ipPrivee(it) }?.let { return it }
        return Serveur.ipLocaleServeur(app)
    }

    /** Adresse à la maison (IP privée du serveur). */
    fun adresseLocale(app: JarvisApp) = ipLocale(app)?.let { "https://$it:$PORT" }.orEmpty()

    /** Adresse hors de la maison : adresse distante des réglages, sinon l'adresse SSH si elle est publique (IP ou nom de domaine). */
    fun adresseDistante(app: JarvisApp): String {
        val d = app.store.sshHostDistant.value.trim().ifBlank { app.store.sshHost.value.trim().takeUnless { Serveur.ipPrivee(it) }.orEmpty() }
        return d.takeIf { it.isNotBlank() }?.let { "https://$it:$PORT" }.orEmpty()
    }

    /** Ouvre le port du coffre sur la Freebox (redirection vers l'IP LOCALE du serveur), pour y accéder hors de la maison. */
    suspend fun ouvrirPort(app: JarvisApp): String {
        val ip = withContext(Dispatchers.IO) { ipLocale(app) } ?: return "❌ Adresse locale du serveur inconnue (SSH injoignable ?)."
        val existantes = runCatching { fr.dd.jarvis.ai.FreeboxOs.gerer(app, "redirections", JSONObject()) { _, _ -> true } }.getOrDefault("")
        if (Regex("🟢[^\\n]*\\b$PORT\\b[^\\n]{0,40}${Regex.escape(ip)}:").containsMatchIn(existantes)) return "✅ Le port $PORT est déjà redirigé vers $ip."
        return fr.dd.jarvis.ai.FreeboxOs.gerer(app, "ajouter_redirection",
            JSONObject().put("ip", ip).put("port", PORT.toString()).put("port_externe", PORT.toString())
                .put("protocole", "tcp").put("commentaire", "Coffre-fort JARVIS")) { _, _ -> true }
    }

    // ------------------------------------------------------------------ invitations (QR code)

    private const val PREFIXE = "JARVISCOFFRE:"

    /** Crée une invitation à usage unique (1 h) ; renvoie le texte à mettre dans le QR code. */
    suspend fun inviter(app: JarvisApp, role: String, nom: String): String = withContext(Dispatchers.IO) {
        val r = Coffre.client(app).inviter(role, nom)
        val maitre = CoffreCrypto.deB64(app.store.coffreMaitre.value)
        val o = JSONObject().put("t", "coffre").put("v", 1).put("l", app.store.coffreUrlLocal.value).put("d", app.store.coffreUrlDistant.value)
            .put("e", app.store.coffreEmpreinte.value).put("c", r.getString("code")).put("r", role)
        if (role == "admin") o.put("m", CoffreCrypto.b64(maitre))
        else {
            val cats = r.optJSONArray("categories")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            o.put("k", JSONObject().apply { cats.forEach { put(it, CoffreCrypto.b64(CoffreCrypto.cleCategorie(maitre, it))) } })
        }
        PREFIXE + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray())
    }

    /** Ce téléphone rejoint le coffre avec une invitation (QR code scanné ou texte collé). */
    suspend fun rejoindre(app: JarvisApp, invitation: String): String = withContext(Dispatchers.IO) {
        val brut = invitation.trim().substringAfter(PREFIXE, "")
        if (brut.isBlank()) return@withContext "❌ Ce n'est pas une invitation du coffre JARVIS."
        val o = runCatching { JSONObject(String(java.util.Base64.getUrlDecoder().decode(brut))) }.getOrNull() ?: return@withContext "❌ Invitation illisible."
        val client = CoffreClient(listOf(o.optString("l"), o.optString("d")).filter { it.isNotBlank() }, o.getString("e"), Coffre.tunnel(app))
        val rep = try { client.appairer(o.getString("c"), nomAppareil()) } catch (e: CoffreException) {
            return@withContext "❌ " + (if (e.code == 401) "Invitation expirée ou déjà utilisée : crée-en une nouvelle sur le téléphone administrateur." else e.message)
        }
        val maitre = o.optString("m").takeIf { it.isNotBlank() }?.let { CoffreCrypto.deB64(it) }
        Coffre.enregistrer(app, o.optString("l"), o.optString("d"), o.getString("e"), rep, maitre, o.optJSONObject("k"))
        "✅ Ce téléphone a rejoint le coffre (${Coffre.ROLES[rep.optString("role")] ?: rep.optString("role")}).\n" + Coffre.synchroniser(app)
    }

    // ------------------------------------------------------------------ récupération (téléphone perdu, volé, neuf)

    private const val KIT = "JARVISKIT:"

    /** Kit de récupération : adresses + empreinte + clé maîtresse, en une ligne (à garder dans un coffre de mots de passe). */
    fun kit(app: JarvisApp): String = KIT + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("l", app.store.coffreUrlLocal.value).put("d", app.store.coffreUrlDistant.value)
            .put("e", app.store.coffreEmpreinte.value).put("m", app.store.coffreMaitre.value).toString().toByteArray())

    /** Récupère l'accès avec le kit, ou avec le code de récupération + l'adresse du coffre. */
    suspend fun recuperer(app: JarvisApp, kitOuCode: String, adresse: String): String = withContext(Dispatchers.IO) {
        val t = kitOuCode.trim()
        val (local, distant, empreinte, maitre) = if (t.startsWith(KIT)) {
            val o = runCatching { JSONObject(String(java.util.Base64.getUrlDecoder().decode(t.removePrefix(KIT)))) }.getOrNull() ?: return@withContext "❌ Kit illisible."
            listOf(o.optString("l"), o.optString("d"), o.optString("e"), o.optString("m"))
        } else {
            val m = CoffreCrypto.depuisCode(t) ?: return@withContext "❌ Code de récupération invalide (52 caractères, groupes de 4)."
            val saisie = adresse.trim().ifBlank { adresseDistante(app).ifBlank { adresseLocale(app) } }
            if (saisie.isBlank()) return@withContext "❌ Indique l'adresse du coffre (IP ou nom de ta box, ex. 82.65.x.x)."
            val url = saisie.let { if (it.startsWith("https://")) it else "https://$it" }.let { if (Regex(":\\d+$").containsMatchIn(it)) it else "$it:$PORT" }
            // Empreinte lue directement, sinon par le tunnel SSH (le port n'a pas besoin d'être ouvert sur la box).
            val e = empreinteDe(url) ?: Coffre.tunnel(app)?.let { t -> runCatching { empreinteDe(t()) }.getOrNull() }
                ?: return@withContext "❌ Coffre injoignable à $url" + (if (Coffre.tunnel(app) == null) " (et pas d'accès SSH configuré pour passer par le serveur)." else " ni par le tunnel SSH.")
            listOf(url, "", e, CoffreCrypto.b64(m))
        }
        val m = CoffreCrypto.deB64(maitre)
        val rep = try { CoffreClient(listOf(local, distant).filter { it.isNotBlank() }, empreinte, Coffre.tunnel(app)).recuperer(CoffreCrypto.preuveRecuperation(m), nomAppareil()) }
            catch (e: CoffreException) { return@withContext "❌ " + (if (e.code == 401) "Code de récupération incorrect pour ce coffre." else e.message) }
        Coffre.enregistrer(app, local, distant, empreinte, rep, m, null)
        "✅ Accès récupéré : ce téléphone est administrateur du coffre. Récupération des données…\n" + Coffre.synchroniser(app)
    }

    /** Empreinte du certificat présenté par une adresse (première connexion sans kit). */
    private fun empreinteDe(url: String): String? = runCatching {
        val u = java.net.URI(url)
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        var vue: String? = null
        ctx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out java.security.cert.X509Certificate>?, a: String?) {
                vue = c?.firstOrNull()?.let { CoffreCrypto.sha256(it.encoded) }
            }
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
        }), null)
        (ctx.socketFactory.createSocket() as javax.net.ssl.SSLSocket).use { s ->
            s.connect(java.net.InetSocketAddress(u.host, if (u.port > 0) u.port else PORT), 8000)
            s.startHandshake()
        }
        vue
    }.getOrNull()
}
