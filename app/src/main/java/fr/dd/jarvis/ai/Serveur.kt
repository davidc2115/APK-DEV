package fr.dd.jarvis.ai

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Serveur personnel : SSH (commandes, fichiers) et Home Assistant (API REST + fichiers de configuration YAML). */
object Serveur {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------ SSH

    fun sshConfigure(app: JarvisApp) = app.store.sshHost.value.isNotBlank() && app.store.sshUser.value.isNotBlank() &&
        (app.store.sshPass.value.isNotBlank() || app.store.sshKey.value.isNotBlank())

    /** Autre machine à joindre en SSH avec des identifiants donnés par l'utilisateur (sinon le serveur des Réglages). */
    data class Cible(val hote: String, val port: Int, val user: String, val pass: String, val jump: Boolean = false)

    /** Traduit l'erreur technique JSch en explication compréhensible (mauvais mot de passe, hôte injoignable, port fermé…). */
    fun expliquer(e: Exception, hote: String, port: Int): String {
        val m = (e.message ?: "").lowercase()
        return when {
            e is java.net.UnknownHostException -> "Adresse « $hote » introuvable (faute de frappe, ou nom qui ne se résout pas depuis ce réseau)."
            m.contains("auth fail") || m.contains("auth cancel") -> "Authentification refusée : utilisateur, mot de passe ou clé incorrects (vérifie Réglages › Serveur)."
            e is java.net.ConnectException || m.contains("connection refused") -> "Connexion refusée par $hote:$port — rien n'écoute sur ce port, ou un pare-feu bloque SSH."
            e is java.net.NoRouteToHostException -> "$hote est injoignable depuis ce réseau (hors ligne, mauvaise IP, ou réseau différent)."
            e is java.net.SocketTimeoutException || m.contains("timeout") -> "Pas de réponse de $hote:$port dans le délai imparti (pare-feu, port fermé, ou mauvaise adresse distante/redirection de port)."
            m.contains("host key") -> "L'empreinte du serveur a changé (réinstallation ?) — efface « Empreinte mémorisée » dans Réglages › Serveur et réessaie."
            m.contains("key") && m.contains("invalid") -> "Clé privée invalide ou mal collée (format PEM/OpenSSH attendu)."
            else -> e.message ?: e.javaClass.simpleName
        }
    }

    private fun ouvrir(app: JarvisApp, cible: Cible? = null): Session {
        if (cible != null) {
            val s = JSch().getSession(cible.user, cible.hote, cible.port)
            s.setPassword(cible.pass); s.setConfig("StrictHostKeyChecking", "no"); s.connect(15000); return s
        }
        val st = app.store
        val jsch = JSch()
        if (st.sshKey.value.isNotBlank()) {
            jsch.addIdentity("jarvis", st.sshKey.value.trim().replace("\r", "").plus("\n").toByteArray(), null,
                st.sshPass.value.takeIf { it.isNotBlank() }?.toByteArray())
        }
        val s = jsch.getSession(st.sshUser.value.trim(), st.sshHost.value.trim(), st.sshPort.value.toIntOrNull() ?: 22)
        // Si une ancienne clé privée est encore renseignée, ne pas empêcher le repli sur le mot de passe.
        // JSch n'essaie l'authentification password que si le mot de passe est explicitement fourni à la session.
        if (st.sshPass.value.isNotBlank()) s.setPassword(st.sshPass.value)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", when {
            st.sshKey.value.isNotBlank() && st.sshPass.value.isNotBlank() -> "publickey,password,keyboard-interactive"
            st.sshKey.value.isNotBlank() -> "publickey"
            else -> "password,keyboard-interactive,publickey"
        })
        try { s.connect(if (st.sshHostDistant.value.isNotBlank()) 6000 else 15000) }
        catch (e: Exception) {
            // Hors du réseau local : repli sur l'adresse distante (IP publique ou nom DDNS, port redirigé sur la box).
            if (st.sshHostDistant.value.isBlank()) throw e
            runCatching { s.disconnect() }
            val s2 = jsch.getSession(st.sshUser.value.trim(), st.sshHostDistant.value.trim(), st.sshPortDistant.value.toIntOrNull() ?: (st.sshPort.value.toIntOrNull() ?: 22))
            if (st.sshPass.value.isNotBlank()) s2.setPassword(st.sshPass.value)
            s2.setConfig("StrictHostKeyChecking", "no")
            s2.setConfig("PreferredAuthentications", when {
                st.sshKey.value.isNotBlank() && st.sshPass.value.isNotBlank() -> "publickey,password,keyboard-interactive"
                st.sshKey.value.isNotBlank() -> "publickey"
                else -> "password,keyboard-interactive,publickey"
            })
            s2.connect(15000)
            return s2.also { sess ->
                val fp2 = sess.hostKey.getFingerPrint(jsch)
                if (st.sshFp.value.isNotBlank() && st.sshFp.value != fp2) { sess.disconnect(); throw IllegalStateException("L'empreinte du serveur distant ne correspond pas : connexion refusée.") }
            }
        }
        // Empreinte du serveur mémorisée à la première connexion ; refus si elle change (protège d'un faux serveur).
        val fp = s.hostKey.getFingerPrint(jsch)
        val connu = st.sshFp.value
        if (connu.isBlank()) st.sshFp.set(fp)
        else if (connu != fp) { s.disconnect(); throw IllegalStateException("L'empreinte du serveur SSH a changé ($fp au lieu de $connu). Si c'est normal (réinstallation), efface-la dans Réglages › Serveur.") }
        return s
    }

    private fun <T> session(app: JarvisApp, cible: Cible? = null, f: (Session) -> T): T {
        val hote = cible?.hote ?: app.store.sshHost.value; val port = cible?.port ?: (app.store.sshPort.value.toIntOrNull() ?: 22)
        try {
            if (cible != null && cible.jump) {
                // Rebond par le serveur : tunnel SSH vers une machine de SON réseau local (accessible de n'importe où si le serveur l'est).
                val main = ouvrir(app)
                try {
                    val lp = main.setPortForwardingL(0, cible.hote, cible.port)
                    val s2 = JSch().getSession(cible.user, "127.0.0.1", lp)
                    s2.setPassword(cible.pass); s2.setConfig("StrictHostKeyChecking", "no"); s2.connect(15000)
                    try { return f(s2) } finally { runCatching { s2.disconnect() } }
                } finally { runCatching { main.disconnect() } }
            }
            val s = ouvrir(app, cible)
            try { return f(s) } finally { runCatching { s.disconnect() } }
        } catch (e: Exception) { throw IllegalStateException(expliquer(e, hote, port), e) }
    }

    /** Exécute une commande ; « sudo » utilise le mot de passe enregistré. Retourne (code de sortie, sortie). */
    fun exec(app: JarvisApp, commande: String, timeoutS: Int = 60, cible: Cible? = null): Pair<Int, String> = session(app, cible) { s ->
        val sudo = commande.contains("sudo ")
        val cmd = if (sudo) commande.replace(Regex("\\bsudo\\s+(?!-S)"), "sudo -S -p '' ") else commande
        val ch = s.openChannel("exec") as ChannelExec
        ch.setCommand("export LANG=C.UTF-8; $cmd")
        val out = ByteArrayOutputStream()
        ch.setInputStream(if (sudo && (cible?.pass ?: app.store.sshPass.value).isNotBlank()) ByteArrayInputStream(((cible?.pass ?: app.store.sshPass.value) + "\n").toByteArray()) else null)
        ch.setErrStream(out)
        val inp = ch.inputStream
        ch.connect(10000)
        val buf = ByteArray(4096)
        val fin = System.currentTimeMillis() + timeoutS * 1000L
        while (true) {
            while (inp.available() > 0) { val n = inp.read(buf, 0, buf.size); if (n < 0) break; out.write(buf, 0, n) }
            if (ch.isClosed) { if (inp.available() > 0) continue; break }
            if (System.currentTimeMillis() > fin) { out.write("\n[… délai de ${timeoutS}s dépassé, commande interrompue]".toByteArray()); ch.disconnect(); return@session -1 to out.toString("UTF-8") }
            Thread.sleep(80)
        }
        val code = ch.exitStatus
        ch.disconnect()
        code to out.toString("UTF-8")
    }

    fun lire(app: JarvisApp, chemin: String, max: Int = 400_000): String = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try {
            val st = c.stat(chemin)
            if (st.size > max) throw IllegalStateException("Fichier trop gros (${st.size / 1024} Ko).")
            c.get(chemin).use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: com.jcraft.jsch.SftpException) {
            if (e.id == ChannelSftp.SSH_FX_PERMISSION_DENIED) null else throw e
        } finally { c.disconnect() }
    } ?: exec(app, "sudo cat " + q(chemin)).second

    /** Écrit un fichier (sauvegarde .jarvis-bak-… avant écrasement) ; passe par sudo si les droits manquent. */
    fun ecrire(app: JarvisApp, chemin: String, contenu: String, sauvegarde: Boolean = true): String? {
        val bak = if (sauvegarde) "$chemin.jarvis-bak-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) else null
        if (bak != null && exec(app, "test -f ${q(chemin)} && (cp -p ${q(chemin)} ${q(bak)} 2>/dev/null || sudo cp -p ${q(chemin)} ${q(bak)})").first != 0) {
            // fichier inexistant : pas de sauvegarde à faire
        }
        val ok = try {
            session(app) { s ->
                val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
                try { c.put(ByteArrayInputStream(contenu.toByteArray()), chemin, ChannelSftp.OVERWRITE); true }
                catch (e: com.jcraft.jsch.SftpException) { false } finally { c.disconnect() }
            }
        } catch (e: Exception) { throw e }
        if (!ok) {
            val tmp = "/tmp/jarvis_up_${System.currentTimeMillis()}"
            session(app) { s ->
                val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
                try { c.put(ByteArrayInputStream(contenu.toByteArray()), tmp, ChannelSftp.OVERWRITE) } finally { c.disconnect() }
            }
            val r = exec(app, "sudo cp ${q(tmp)} ${q(chemin)} && rm -f ${q(tmp)}")
            if (r.first != 0) throw IllegalStateException("Écriture refusée : ${r.second.take(200)}")
        }
        return bak
    }

    fun envoyerOctets(app: JarvisApp, chemin: String, octets: ByteArray) = session(app) { s ->
        val c = s.openChannel("sftp") as ChannelSftp; c.connect(10000)
        try { c.put(ByteArrayInputStream(octets), chemin, ChannelSftp.OVERWRITE) } finally { c.disconnect() }
    }

    fun lister(app: JarvisApp, dossier: String): String = exec(app, "ls -lAh --time-style=long-iso ${q(dossier)} | head -150").second

    /** Analyse le LAN du serveur Debian : sous-réseaux réellement configurés, table ARP/neigh et ports TCP.
     *  Les appareils sans adresse MAC connue restent visibles dans les résultats. */
    fun scanReseau(app: JarvisApp): List<Reseau.Appareil> {
        val commande = "nets=\$(ip -4 route show scope link | awk '\$1 ~ /^[0-9]+\\./ {print \$1}' | sort -u); " +
            "for net in \$nets; do python3 -c 'import ipaddress,sys; n=ipaddress.ip_network(sys.argv[1],strict=False); print(\"\\n\".join(str(x) for x in n.hosts()) if n.num_addresses <= 1024 else \"\")' \$net; done | " +
            "while read ip; do (ping -c1 -W1 \$ip >/dev/null 2>&1) & while [ \$(jobs -rp | wc -l) -ge 48 ]; do wait -n 2>/dev/null || break; done; done; wait; sleep 1; " +
            "ip neigh show | awk '\$1 ~ /^[0-9]+\\./ && \$0 !~ /FAILED/ {mac=\"\"; for(i=1;i<=NF;i++) if(\$i==\"lladdr\" && i<NF) mac=\$(i+1); print \"N \"\$1\" \"mac}'; " +
            "for ip in \$(ip neigh show | awk '\$1 ~ /^[0-9]+\\./ && \$0 !~ /FAILED/ {print \$1}' | sort -u); do " +
            "(for p in 22 80 443 445 631 9100 8123 8080 554 1883; do timeout 0.8 bash -c \"echo >/dev/tcp/\$ip/\$p\" 2>/dev/null && echo \"P \$ip \$p\"; done) & " +
            "while [ \$(jobs -rp | wc -l) -ge 48 ]; do wait -n 2>/dev/null || break; done; done; wait";
        val r = exec(app, commande, 180).second
        val mac = HashMap<String, String>(); val ports = HashMap<String, MutableList<Int>>()
        val vus = LinkedHashSet<String>()
        r.lines().forEach { l ->
            val t = l.trim().split(Regex("\\s+"))
            if (t.size >= 2 && t[0] == "N") { vus += t[1]; if (t.size >= 3 && t[2].matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) mac[t[1]] = t[2] }
            if (t.size >= 3 && t[0] == "P") { vus += t[1]; ports.getOrPut(t[1]) { mutableListOf() }.add(t[2].toIntOrNull() ?: 0) }
        }
        return vus.sortedBy { ip -> ip.split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 256 + b } }.map { ip ->
            val p = ports[ip].orEmpty().distinct().sorted()
            val m = mac[ip].orEmpty()
            val type = Reseau.typeDeApp(p, "").let { t -> if (t == "❔ appareil" && m.isNotBlank()) Reseau.fabricant(m) ?: t else t }
            Reseau.Appareil(ip, "", p, "", type, if (m.isNotBlank()) "MAC $m" else "MAC inconnue")
        }
    }

    // ------------------------------------------------------------------ Scan de ports (depuis le téléphone, sans SSH)

    /** Ports SSH non standards les plus courants (au cas où le port configuré est faux). */
    val PORTS_SSH_COURANTS = listOf(22, 222, 2222, 2022, 22022, 50022, 10022, 2200, 8022)
    /** Autres services serveur fréquents, utiles pour un diagnostic plus large. */
    val PORTS_SERVICES_COURANTS = listOf(80, 443, 8080, 8443, 8123, 3000, 9000, 9090, 32400, 445, 139, 21, 3306, 5432, 6379, 8096, 1883)

    /** Teste une liste de ports TCP sur [hote] DIRECTEMENT DEPUIS LE TÉLÉPHONE (ne passe pas par SSH, donc fonctionne même
     *  quand SSH ne se connecte pas) : utile pour retrouver le bon port quand celui des Réglages est faux. Ne marche que si
     *  le téléphone peut atteindre cette adresse (même réseau local que le serveur, ou port déjà redirigé vers l'extérieur). */
    fun scannerPorts(hote: String, ports: List<Int>): List<Pair<Int, Boolean>> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(24)
        return try {
            ports.map { p -> pool.submit(java.util.concurrent.Callable { p to fr.dd.jarvis.ai.Reseau.ouvertTcp(hote, p, 1200) }) }.map { it.get() }
        } finally { pool.shutdown() }
    }

    /** Si un port ouvert répond bien comme un serveur SSH, sa bannière commence par « SSH-… » ; sinon null. */
    fun banniereSsh(hote: String, port: Int, ms: Int = 1500): String? = try {
        java.net.Socket().use { sk ->
            sk.connect(java.net.InetSocketAddress(hote, port), ms)
            sk.soTimeout = ms
            sk.getInputStream().bufferedReader().readLine()?.takeIf { it.startsWith("SSH-") }
        }
    } catch (e: Exception) { null }

    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private val DANGER = Regex(
        "\\b(rm|rmdir|mkfs\\S*|dd|shred|reboot|shutdown|poweroff|halt|fdisk|parted|wipefs|userdel|passwd|chpasswd|iptables|ufw|kill|killall|pkill|truncate|mv|crontab)\\b" +
            "|docker\\s+(rm|rmi|system\\s+prune|volume\\s+rm|stop|kill|down|restart|compose\\s+down)|systemctl\\s+(stop|disable|mask|restart|reboot|poweroff)" +
            "|apt(-get)?\\s+(remove|purge|autoremove|upgrade|dist-upgrade)|>\\s*/(etc|boot|dev|var|usr)|chmod\\s+-R|chown\\s+-R|\\|\\s*(sudo\\s+)?(sh|bash)\\b|sed\\s+-i|tee\\s",
        RegexOption.IGNORE_CASE
    )
    fun dangereuse(cmd: String) = DANGER.containsMatchIn(cmd)

    // ------------------------------------------------------------------ Home Assistant (API REST)

    fun haConfigure(app: JarvisApp) = app.store.haUrl.value.isNotBlank() && app.store.haToken.value.isNotBlank()

    private fun base(app: JarvisApp) = app.store.haUrl.value.trim().trimEnd('/').let { if (it.startsWith("http")) it else "http://$it" }

    fun ha(app: JarvisApp, methode: String, chemin: String, corps: JSONObject? = null): String {
        if (!haConfigure(app)) throw IllegalStateException("Home Assistant non configuré (Réglages › Serveur : adresse et jeton d'accès longue durée).")
        val b = Request.Builder().url(base(app) + chemin).header("Authorization", "Bearer ${app.store.haToken.value.trim()}")
        if (methode == "POST") b.post((corps?.toString() ?: "{}").toRequestBody("application/json".toMediaType())) else b.get()
        try {
            http.newCall(b.build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (r.code == 401) throw IllegalStateException("Home Assistant 401 : le jeton est invalide ou expiré. Dans Home Assistant, connecte-toi avec le compte qui possède les droits d'administration, puis crée un nouveau jeton d'accès longue durée dans ton profil et remplace-le dans JARVIS › Réglages › Serveur.")
                if (r.code == 403) throw IllegalStateException("Home Assistant 403 — action interdite pour le compte associé au jeton. Ce n'est pas une permission Android/Shizuku : le jeton reprend les droits du compte Home Assistant qui l'a créé. Vérifie que ce compte peut piloter cet appareil et, si nécessaire, crée un nouveau jeton depuis un compte administrateur. Détail HA : ${t.take(500)}")
                if (r.code == 404) throw IllegalStateException("Home Assistant 404 : service ou entité introuvable. Vérifie le domaine, le nom du service et l'identifiant exact de l'appareil. Détail HA : ${t.take(400)}")
                if (!r.isSuccessful) throw IllegalStateException("Home Assistant ${r.code} : ${t.take(500)}")
                return t
            }
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw IllegalStateException("Home Assistant : adresse introuvable (« ${app.store.haUrl.value} »). Vérifie l'adresse dans Réglages › Serveur (faute de frappe, ou nom DNS qui ne résout plus).")
        } catch (e: java.net.ConnectException) {
            throw IllegalStateException("Home Assistant : connexion refusée sur « ${base(app)} ». Soit Home Assistant est arrêté (vérifie avec serveur_diagnostic), soit le port/l'adresse est faux dans Réglages › Serveur, soit le téléphone n'est pas sur le même réseau (et l'accès distant n'est pas configuré).")
        } catch (e: java.net.SocketTimeoutException) {
            throw IllegalStateException("Home Assistant : pas de réponse (délai dépassé) sur « ${base(app)} ». Le serveur est peut-être éteint/inaccessible depuis ce réseau, ou surchargé.")
        } catch (e: javax.net.ssl.SSLException) {
            throw IllegalStateException("Home Assistant : erreur TLS/SSL sur « ${base(app)} ». Si l'adresse commence par https:// mais que Home Assistant n'a pas de certificat valide configuré, utilise http:// à la place (Réglages › Serveur).")
        } catch (e: Exception) {
            throw IllegalStateException("Home Assistant : connexion impossible sur « ${base(app)} » (${e.javaClass.simpleName} : ${e.message}).")
        }
    }

    /** Même appareil sous plusieurs noms (« télé » ↔ tv ↔ media_player…), et le domaine Home Assistant qu'il évoque. */
    private val SYNONYMES_HA: List<Pair<Set<String>, String?>> = listOf(
        setOf("tele", "tv", "television", "televiseur", "telly", "bravia", "freebox player", "chromecast", "android tv", "media player") to "media_player",
        setOf("enceinte", "speaker", "sonos", "google home", "nest", "echo", "alexa", "barre de son", "soundbar") to "media_player",
        setOf("lumiere", "lumieres", "lampe", "lampes", "eclairage", "ampoule", "spot", "light", "plafonnier", "led") to "light",
        setOf("chauffage", "radiateur", "thermostat", "clim", "climatisation", "poele", "pac", "climate") to "climate",
        setOf("volet", "volets", "store", "stores", "rideau", "cover") to "cover",
        setOf("prise", "prises", "plug", "interrupteur", "switch") to "switch",
        setOf("temperature", "humidite", "capteur", "sensor") to "sensor",
        setOf("portail", "garage", "porte") to null,
        setOf("volume", "son") to "media_player",
    )

    /**
     * Filtre tolérant des entités Home Assistant : sans accents ni casse, « _ » = espace, par mots (avec synonymes),
     * les plus pertinentes d'abord. [entites] = (entity_id, friendly_name). Renvoie les index retenus.
     */
    internal fun filtrerEntites(entites: List<Pair<String, String>>, recherche: String, domaine: String): List<Int> {
        val d = domaine.trim().lowercase().removeSuffix(".")
        fun norm(x: String) = fr.dd.jarvis.societe.core.normaliser(x.replace('_', ' ').replace('.', ' '))
        val q = norm(recherche)
        val candidats = entites.indices.filter { d.isBlank() || entites[it].first.lowercase().startsWith("$d.") }
        if (q.isBlank()) return candidats.sortedBy { entites[it].first }
        val textes = candidats.associateWith { " " + norm(entites[it].first + " " + entites[it].second) + " " }
        // 1. La recherche telle quelle.
        candidats.filter { textes.getValue(it).contains(q) }.takeIf { it.isNotEmpty() }?.let { l -> return l.sortedBy { entites[it].first } }
        // 2. Mot à mot, avec synonymes ; score = nombre de mots de la recherche retrouvés.
        val vides = setOf("le", "la", "les", "de", "du", "des", "mon", "ma", "mes", "un", "une", "et", "a", "au", "aux", "en", "sur", "dans")
        val mots = q.split(' ').filter { it.length >= 2 && it !in vides }
        if (mots.isEmpty()) return emptyList()
        val groupes = mots.map { m -> setOf(m) + SYNONYMES_HA.filter { (g, _) -> m in g }.flatMap { it.first } }
        val domaines = mots.flatMap { m -> SYNONYMES_HA.filter { (g, _) -> m in g }.mapNotNull { it.second } }.toSet()
        return candidats.map { i ->
            val t = textes.getValue(i)
            val dom = entites[i].first.substringBefore('.')
            i to (groupes.count { g -> g.any { t.contains(" $it") } } * 2 + (if (dom in domaines) 1 else 0))
        }.filter { it.second >= 2 || (it.second == 1 && domaines.size == 1 && mots.size == 1) }
            .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { entites[it.first].first }).map { it.first }
    }

    fun haEntites(app: JarvisApp, recherche: String, domaine: String, decalage: Int = 0, limite: Int = 100): String {
        val arr = JSONArray(ha(app, "GET", "/api/states"))
        val toutes = (0 until arr.length()).map { arr.getJSONObject(it) }
        val paires = toutes.map { it.getString("entity_id") to it.optJSONObject("attributes")?.optString("friendly_name").orEmpty() }
        val l = filtrerEntites(paires, recherche, domaine).map { toutes[it] }
        if (l.isEmpty()) {
            // Jamais un simple « aucune entité » : on donne de quoi réessayer (domaines présents, appareils multimédia…).
            val parDomaine = paires.groupingBy { it.first.substringBefore('.') }.eachCount().entries.sortedByDescending { it.value }
            val medias = paires.filter { it.first.startsWith("media_player.") }.take(15)
            return "Aucune entité ne correspond à « ${recherche.ifBlank { domaine }} » (${arr.length()} entités au total). " +
                "Domaines présents : " + parDomaine.take(14).joinToString(", ") { "${it.key} (${it.value})" } + "." +
                (if (medias.isNotEmpty()) "\nAppareils multimédia (télé, enceintes) : " + medias.joinToString(" ; ") { "`${it.first}` ${it.second}" } else "") +
                "\nRéessaie avec un autre mot (nom de la pièce, marque, tv…) ou un domaine, et regarde les notes de l'utilisateur (memoire_lire / obsidian_chercher)."
        }
        val debut = decalage.coerceIn(0, l.size)
        val taille = limite.coerceIn(1, 250)
        val page = l.drop(debut).take(taille)
        val fin = debut + page.size
        val suite = if (fin < l.size) "\n\nIl reste ${l.size - fin} entité(s). Pour continuer, rappelle ha_entites avec decalage=$fin et la même recherche/domaine." else "\n\nToutes les entités correspondant au filtre ont été parcourues."
        return "# 🏠 Entités : ${l.size} résultat(s) · affichage ${if (page.isEmpty()) 0 else debut + 1}–$fin\n\n" + page.joinToString("\n") { o ->
            val a = o.optJSONObject("attributes"); val unit = a?.optString("unit_of_measurement").orEmpty()
            "- `${o.getString("entity_id")}` · ${a?.optString("friendly_name").orEmpty()} : **${o.getString("state")}$unit**"
        } + suite
    }

    fun haEtat(app: JarvisApp, id: String): String {
        val o = JSONObject(ha(app, "GET", "/api/states/$id"))
        return "`$id` = **${o.getString("state")}**\n" + (o.optJSONObject("attributes")?.toString(1)?.take(1800) ?: "")
    }

    fun haService(app: JarvisApp, domaine: String, service: String, entite: String, donnees: JSONObject?): String {
        val corps = donnees ?: JSONObject()
        if (entite.isNotBlank()) corps.put("entity_id", if (entite.contains(',')) JSONArray(entite.split(",").map { it.trim() }) else entite)
        ha(app, "POST", "/api/services/$domaine/$service", corps)
        return "✅ $domaine.$service" + (if (entite.isNotBlank()) " → $entite" else "") + " exécuté."
    }

    /** Valide la configuration (API, sinon commande Docker). Retourne null si valide, sinon le message d'erreur. */
    fun haValider(app: JarvisApp): String? {
        if (haConfigure(app)) runCatching {
            val o = JSONObject(ha(app, "POST", "/api/config/core/check_config"))
            return if (o.optString("result") == "valid") null else "Configuration invalide : " + o.optString("errors", o.toString()).take(900)
        }
        if (sshConfigure(app)) {
            val nom = haConteneur(app) ?: return "Validation impossible (API Home Assistant injoignable et conteneur Docker introuvable)."
            val r = exec(app, "docker exec $nom python -m homeassistant --script check_config -c /config 2>&1 | tail -40", 120)
            return if (r.second.contains("Failed config", true) || r.second.contains("error", true) && !r.second.contains("Successful config", true)) "Configuration invalide :\n" + r.second.take(900) else null
        }
        return "Validation impossible (ni API, ni SSH)."
    }

    fun haConteneur(app: JarvisApp): String? =
        exec(app, "docker ps -a --format '{{.Names}}\t{{.Image}}' | grep -i home-assistant | head -1 | cut -f1").second.trim().ifBlank { null }

    /** Dossier de configuration de Home Assistant sur le serveur (réglage, sinon détection Docker puis emplacements usuels). */
    fun haDossier(app: JarvisApp): String? {
        app.store.haDir.value.trim().takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        val nom = haConteneur(app)
        if (nom != null) {
            val src = exec(app, "docker inspect $nom --format '{{range .Mounts}}{{if eq .Destination \"/config\"}}{{.Source}}{{end}}{{end}}'").second.trim()
            if (src.startsWith("/")) { app.store.haDir.set(src); return src }
        }
        for (c in listOf("/config", "~/homeassistant", "/opt/homeassistant/config", "/opt/homeassistant", "/home/homeassistant/.homeassistant", "/usr/share/hassio/homeassistant", "~/.homeassistant")) {
            if (exec(app, "test -f $c/configuration.yaml").first == 0) {
                val abs = exec(app, "cd $c && pwd").second.trim().ifBlank { c }
                app.store.haDir.set(abs); return abs
            }
        }
        return null
    }
}
