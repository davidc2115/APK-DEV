package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Connexion DIRECTE à la télé (sans passer par Home Assistant) pour la piloter ET changer ses réglages.
 *
 *  - Android TV / Google TV (Sony Bravia, Philips, TCL, Xiaomi, Hisense, Chromecast avec Google TV, Freebox Pop/Player
 *    Android…) : débogage réseau (ADB) piloté depuis le serveur Debian de la maison — marche aussi quand tu es dehors.
 *    Touches de la télécommande, applications, pages de réglages, paramètres système, saisie de texte, capture d'écran.
 *    Une seule fois sur la télé : Paramètres › Système › À propos › 7 appuis sur « Version » (mode développeur), puis
 *    Options pour les développeurs › Débogage réseau / USB ; à la première connexion, accepter « Toujours autoriser ».
 *  - Sony Bravia : en plus, l'API IP de Sony (clé pré-partagée) donne les réglages d'image et de son (luminosité, mode
 *    d'image, contraste…), l'alimentation, le volume et l'entrée.
 *  - Autres marques (Samsung, LG…) : pilotage par Home Assistant (media_player) en attendant mieux.
 */
object Tele {
    private fun q(s: String) = Serveur.q(s)
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    fun ip(app: JarvisApp) = app.store.teleIp.value.trim()
    fun type(app: JarvisApp) = app.store.teleType.value.trim().ifBlank { "androidtv" }
    fun configuree(app: JarvisApp) = ip(app).isNotBlank()

    // ------------------------------------------------------------------ détection

    data class Candidat(val ip: String, val nom: String, val type: String, val raison: String)

    internal fun estEnceinteGoogle(texte: String, type: String = ""): Boolean =
        Regex("google.?home|google.?nest|nest.?audio|nest.?mini|nest.?hub|home.?mini|speaker|enceinte|haut.?parleur").containsMatchIn(texte.lowercase()) ||
            type.contains("🔊")

    /** Télés trouvées dans l'inventaire du Wi-Fi (ports et noms caractéristiques). */
    fun detecter(app: JarvisApp): List<Candidat> = Registre.tous(app).mapNotNull { e ->
        val ports = e.optJSONArray("ports")?.let { a -> (0 until a.length()).map { a.optInt(it) } }.orEmpty()
        val texte = listOf("alias", "hote", "titre", "info", "type").joinToString(" ") { e.optString(it) }.lowercase()
        val ip = e.optString("ip")
        val enceinteGoogle = estEnceinteGoogle(texte, e.optString("type"))
        when {
            // Un Nest/Google Home expose lui aussi le port Cast 8009 (parfois 8008) : ne jamais le proposer comme télé.
            enceinteGoogle -> null
            texte.contains("bravia") || texte.contains("sony") && (texte.contains("tv") || texte.contains("tele")) -> Candidat(ip, Registre.nomDe(e), "bravia", "Sony Bravia")
            5555 in ports -> Candidat(ip, Registre.nomDe(e), "androidtv", "débogage réseau ouvert (port 5555)")
            6466 in ports || 6467 in ports || (8009 in ports && 8008 in ports) || Regex("android ?tv|google ?tv|chromecast|shield|mibox|freebox (pop|player)").containsMatchIn(texte) ->
                Candidat(ip, Registre.nomDe(e), "androidtv", "Android TV / Google TV")
            8001 in ports || 8002 in ports || texte.contains("samsung") && texte.contains("tv") -> Candidat(ip, Registre.nomDe(e), "samsung", "Samsung (Tizen)")
            3000 in ports || 3001 in ports || texte.contains("webos") || texte.contains("lg") && texte.contains("tv") -> Candidat(ip, Registre.nomDe(e), "lg", "LG (webOS)")
            e.optString("type").contains("📺") || Regex("\\b(tv|tele|television)\\b").containsMatchIn(texte) -> Candidat(ip, Registre.nomDe(e), "androidtv", "télé")
            else -> null
        }
    }

    // ------------------------------------------------------------------ ADB par le serveur

    /** Commande adb exécutée sur le serveur (adb installé au besoin). */
    private fun adb(app: JarvisApp, args: String, delai: Int = 40): Pair<Int, String> {
        if (!Serveur.sshConfigure(app)) throw IllegalStateException("Le pilotage direct de la télé passe par le serveur de la maison : configure l'accès SSH (Réglages › Maison › Serveur).")
        val cible = q(ip(app).let { if (it.contains(':')) it else "$it:5555" })
        val prep = "command -v adb >/dev/null 2>&1 || { sudo apt-get update -qq >/dev/null 2>&1; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq adb >/dev/null 2>&1; }; " +
            "adb connect $cible >/dev/null 2>&1; "
        return Serveur.exec(app, prep + "adb -s $cible $args 2>&1", delai)
    }

    private fun shell(app: JarvisApp, cmd: String, delai: Int = 40): String {
        val r = adb(app, "shell " + q(cmd), delai)
        val t = r.second.trim()
        when {
            t.contains("unauthorized", true) -> throw IllegalStateException("La télé n'a pas encore autorisé le serveur : accepte « Autoriser le débogage ? » sur l'écran de la télé (coche « Toujours autoriser »), puis réessaie.")
            t.contains("failed to connect", true) || t.contains("cannot connect", true) || t.contains("device offline", true) || t.contains("not found", true) && t.contains("device", true) ->
                throw IllegalStateException("Connexion à la télé (${ip(app)}) impossible : vérifie qu'elle est allumée et que le débogage réseau est activé (Options pour les développeurs).")
            r.first == 127 -> throw IllegalStateException("adb n'a pas pu être installé sur le serveur (sudo requis).")
        }
        return t
    }

    /** Connexion (et demande d'autorisation sur l'écran de la télé) ; renvoie l'état. */
    fun connecter(app: JarvisApp): String {
        val r = adb(app, "get-state", 30)
        return when {
            r.second.contains("device") -> "✅ Télé connectée (${ip(app)}) : " + etat(app)
            r.second.contains("unauthorized", true) -> "📺 Regarde la télé : accepte « Autoriser le débogage ? » (coche « Toujours autoriser cet ordinateur »), puis redis « connecte la télé »."
            else -> "❌ Télé injoignable (${ip(app)}) : allume-la, vérifie le débogage réseau (Options pour les développeurs) et son adresse IP.\n${r.second.take(200)}"
        }
    }

    fun etat(app: JarvisApp): String {
        val modele = shell(app, "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.version.release").lines().filter { it.isNotBlank() }
        val app1 = shell(app, "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -1").substringAfterLast('/').substringBefore('}').substringBefore(' ').ifBlank { "?" }
        val veille = shell(app, "dumpsys power | grep -E 'mWakefulness=' | head -1").substringAfter('=').trim()
        return "📺 ${modele.getOrNull(0).orEmpty()} ${modele.getOrNull(1).orEmpty()} (Android ${modele.getOrNull(2).orEmpty()}) · " +
            (if (veille.equals("Awake", true)) "allumée" else "en veille") + " · au premier plan : $app1"
    }

    /** Touches de la télécommande (noms français acceptés). */
    val TOUCHES = linkedMapOf(
        "allumer_eteindre" to 26, "veille" to 223, "reveil" to 224, "accueil" to 3, "retour" to 4, "haut" to 19, "bas" to 20, "gauche" to 21, "droite" to 22,
        "ok" to 23, "volume_plus" to 24, "volume_moins" to 25, "muet" to 164, "menu" to 82, "reglages" to 176, "source" to 178, "lecture_pause" to 85,
        "stop" to 86, "suivant" to 87, "precedent" to 88, "avance" to 90, "recul" to 89, "chaine_plus" to 166, "chaine_moins" to 167, "guide" to 172, "infos" to 165,
        "recherche" to 84, "assistant" to 231,
    )
    private val SYNONYMES = mapOf("power" to "allumer_eteindre", "eteindre" to "veille", "allumer" to "reveil", "home" to "accueil", "back" to "retour",
        "entree" to "ok", "valider" to "ok", "volume+" to "volume_plus", "volume-" to "volume_moins", "plus fort" to "volume_plus", "moins fort" to "volume_moins",
        "mute" to "muet", "parametres" to "reglages", "settings" to "reglages", "input" to "source", "entrees" to "source", "pause" to "lecture_pause", "play" to "lecture_pause")

    fun touche(app: JarvisApp, nom: String, fois: Int = 1): String {
        val n = fr.dd.jarvis.societe.core.normaliser(nom).replace(' ', '_')
        val cle = TOUCHES.keys.firstOrNull { it == n } ?: SYNONYMES[n.replace('_', ' ')] ?: SYNONYMES[n]
            ?: return "Touche inconnue « $nom ». Disponibles : " + TOUCHES.keys.joinToString(", ")
        val code = TOUCHES[cle]!!
        shell(app, (1..fois.coerceIn(1, 30)).joinToString("; ") { "input keyevent $code" })
        return "📺 ${cle.replace('_', ' ')}" + (if (fois > 1) " ×$fois" else "") + " ✓"
    }

    /** Pages de réglages de la télé (Android TV). */
    private val PAGES = linkedMapOf(
        "general" to "android.settings.SETTINGS", "affichage" to "android.settings.DISPLAY_SETTINGS", "image" to "android.settings.DISPLAY_SETTINGS",
        "son" to "android.settings.SOUND_SETTINGS", "reseau" to "android.settings.WIFI_SETTINGS", "wifi" to "android.settings.WIFI_SETTINGS",
        "bluetooth" to "android.settings.BLUETOOTH_SETTINGS", "applis" to "android.settings.APPLICATION_SETTINGS", "date" to "android.settings.DATE_SETTINGS",
        "langue" to "android.settings.LOCALE_SETTINGS", "stockage" to "android.settings.INTERNAL_STORAGE_SETTINGS", "accessibilite" to "android.settings.ACCESSIBILITY_SETTINGS",
        "developpeur" to "android.settings.APPLICATION_DEVELOPMENT_SETTINGS", "compte" to "android.settings.SYNC_SETTINGS", "a_propos" to "android.settings.DEVICE_INFO_SETTINGS",
    )

    fun reglages(app: JarvisApp, page: String): String {
        val n = fr.dd.jarvis.societe.core.normaliser(page).replace(' ', '_').ifBlank { "general" }
        val action = PAGES[n] ?: PAGES.entries.firstOrNull { n.contains(it.key) || it.key.contains(n) }?.value ?: "android.settings.SETTINGS"
        shell(app, "am start -a $action")
        return "📺 Réglages « ${PAGES.entries.firstOrNull { it.value == action }?.key ?: "général"} » ouverts sur la télé. Dis « haut / bas / ok / retour » pour naviguer."
    }

    /** Applications installées (nom de paquet), filtrées. */
    fun applis(app: JarvisApp, filtre: String = ""): List<String> =
        shell(app, "pm list packages -3; pm list packages | grep -E 'youtube|netflix|primevideo|disney|plex|canal|molotov|spotify|freebox|tv\\.|launcher'")
            .lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() && (filtre.isBlank() || it.contains(filtre, true)) }.distinct().sorted()

    /** Tape un numéro de chaîne sur la télécommande (touches 0 à 9). */
    fun chiffres(app: JarvisApp, numero: Int): String {
        shell(app, numero.toString().map { "input keyevent ${7 + (it - '0')}" }.joinToString("; "))
        return "📺 chaîne $numero ✓"
    }

    internal val APPLIS_CONNUES = mapOf("youtube" to "com.google.android.youtube.tv", "netflix" to "com.netflix.ninja", "prime video" to "com.amazon.amazonvideo.livingroom",
        "disney" to "com.disney.disneyplus", "plex" to "com.plexapp.android", "canal" to "com.canal.android.canal", "molotov" to "tv.molotov.app", "spotify" to "com.spotify.tv.android",
        "mycanal" to "com.canal.android.canal", "france tv" to "fr.francetv.pluzz", "tf1" to "fr.tf1.mytf1", "arte" to "tv.arte.plus7", "twitch" to "tv.twitch.android.app")

    fun ouvrirAppli(app: JarvisApp, nom: String): String {
        val n = fr.dd.jarvis.societe.core.normaliser(nom)
        val pkg = APPLIS_CONNUES.entries.firstOrNull { n.contains(it.key) }?.value?.takeIf { p -> applis(app).contains(p) }
            ?: applis(app).firstOrNull { it.contains(n.replace(" ", ""), true) || n.split(' ').any { m -> m.length > 3 && it.contains(m, true) } }
            ?: return "Application « $nom » introuvable sur la télé. Applications : " + applis(app).take(25).joinToString(", ")
        shell(app, "monkey -p $pkg -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1 || monkey -p $pkg 1")
        return "📺 $pkg lancé sur la télé."
    }

    fun texte(app: JarvisApp, t: String): String {
        // « input text » n'accepte que l'ASCII simple : accents retirés, espaces codés %s, caractères spéciaux du shell écartés.
        val propre = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .filter { it.isLetterOrDigit() && it.code < 128 || it in " .,@-_:/+=" }.replace(" ", "%s").take(300)
        if (propre.isBlank()) return "Rien à saisir (seuls les lettres, chiffres et . , @ - _ : / + = passent)."
        shell(app, "input text $propre")
        return "⌨️ Texte saisi sur la télé."
    }

    /** Paramètre système Android de la télé (system / secure / global). */
    fun parametre(app: JarvisApp, espace: String, cle: String, valeur: String?): String {
        val e = espace.lowercase().takeIf { it in setOf("system", "secure", "global") } ?: "system"
        require(Regex("^[A-Za-z0-9_.]+$").matches(cle)) { "nom de paramètre invalide." }
        if (valeur == null) return "📺 $e/$cle = " + shell(app, "settings get $e $cle")
        require(Regex("^[A-Za-z0-9_.:,-]{0,100}$").matches(valeur)) { "valeur invalide." }
        shell(app, "settings put $e $cle $valeur")
        return "✅ $e/$cle = " + shell(app, "settings get $e $cle")
    }

    /** Capture de l'écran de la télé, affichée dans la conversation. */
    fun capture(app: JarvisApp): String {
        val cible = ip(app).let { if (it.contains(':')) it else "$it:5555" }
        val dist = "/tmp/jarvis_tv_${System.currentTimeMillis()}.png"
        adb(app, "exec-out screencap -p > ${q(dist)}", 40)
        val octets = try { Serveur.lireOctets(app, dist, 20_000_000L) } finally { runCatching { Serveur.exec(app, "rm -f ${q(dist)}", 10) } }
        if (octets.size < 100) throw IllegalStateException("capture vide (télé en veille, ou contenu protégé : les applis de streaming bloquent la capture).")
        val dir = File(app.filesDir, "images").apply { mkdirs() }
        val f = File(dir, "tele_${System.currentTimeMillis()}.png"); f.writeBytes(octets)
        return "![écran de la télé](jarvis-img:${f.name})\n📺 Écran de la télé ($cible)."
    }

    fun commande(app: JarvisApp, cmd: String): String = "```\n" + shell(app, cmd, 60).take(4000) + "\n```"

    // ------------------------------------------------------------------ Sony Bravia (API IP, clé pré-partagée)

    private fun bravia(app: JarvisApp, service: String, methode: String, params: JSONArray = JSONArray(), version: String = "1.0"): JSONObject {
        val cle = app.store.teleCle.value.trim()
        require(cle.isNotBlank()) { "Clé pré-partagée Bravia manquante : sur la télé, Paramètres › Réseau › Contrôle IP › Authentification « Normal et clé pré-partagée », puis saisis la même clé dans Réglages › Maison › Télé." }
        val corps = JSONObject().put("method", methode).put("params", params).put("id", 1).put("version", version).toString()
        val req = Request.Builder().url("http://${ip(app).substringBefore(':')}/sony/$service").header("X-Auth-PSK", cle)
            .post(corps.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (r.code == 403) throw IllegalStateException("La télé refuse la clé pré-partagée (vérifie-la dans les réglages de la télé et de JARVIS).")
            val o = runCatching { JSONObject(t) }.getOrElse { throw IllegalStateException("Réponse illisible de la télé (HTTP ${r.code}).") }
            o.optJSONArray("error")?.let { throw IllegalStateException("Télé : erreur ${it.optInt(0)} ${it.optString(1)}") }
            return o
        }
    }

    /** Réglages d'image / de son de la Bravia : lecture (valeur null) ou modification. */
    fun braviaReglage(app: JarvisApp, quoi: String, valeur: String?): String {
        val (service, lire, ecrire) = if (Regex("son|volume|sound|audio", RegexOption.IGNORE_CASE).containsMatchIn(quoi))
            Triple("audio", "getSoundSettings", "setSoundSettings") else Triple("video", "getPictureQualitySettings", "setPictureQualitySettings")
        if (valeur == null) {
            val r = bravia(app, service, lire, JSONArray().put(JSONObject().put("target", "")))
            val l = r.optJSONArray("result")?.optJSONArray(0) ?: JSONArray()
            return "📺 Réglages ${if (service == "audio") "du son" else "de l'image"} :\n" + (0 until l.length()).mapNotNull { l.optJSONObject(it) }.joinToString("\n") { o ->
                "• ${o.optString("target")} = ${o.optString("currentValue")}" + (o.optJSONArray("candidate")?.let { c ->
                    val v = (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                    if (v.any { it.has("value") }) " (choix : ${v.joinToString(", ") { it.optString("value") }.take(120)})" else if (v.any { it.has("min") }) " (de ${v[0].optString("min")} à ${v[0].optString("max")})" else ""
                } ?: "")
            }
        }
        bravia(app, service, ecrire, JSONArray().put(JSONObject().put("settings", JSONArray().put(JSONObject().put("target", quoi).put("value", valeur)))))
        return "✅ $quoi = $valeur"
    }

    fun braviaAlimentation(app: JarvisApp, allumer: Boolean): String {
        bravia(app, "system", "setPowerStatus", JSONArray().put(JSONObject().put("status", allumer)))
        return if (allumer) "📺 Télé allumée." else "📺 Télé en veille."
    }

    fun braviaEntree(app: JarvisApp, entree: String): String {
        val n = entree.lowercase().replace(" ", "")
        val uri = when { n.startsWith("hdmi") -> "extInput:hdmi?port=${n.filter { it.isDigit() }.ifBlank { "1" }}"; n.contains("tv") || n.contains("tnt") -> "tv:dvbt"; else -> entree }
        bravia(app, "avContent", "setPlayContent", JSONArray().put(JSONObject().put("uri", uri)))
        return "📺 Entrée : $entree"
    }
}
