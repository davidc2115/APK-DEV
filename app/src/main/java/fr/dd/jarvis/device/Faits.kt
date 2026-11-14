package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.launch

/**
 * Mémoire FIABLE des faits durables : lieux (domicile, travail…), surnoms (« les potes » = serveur Discord « XYZ »,
 * « maman » = contact « Marie Dupont »…) et faits simples (« ma voiture » = Peugeot 308…).
 *
 * Avant, une note prise (« prends note de mon adresse ») restait du texte libre : elle n'était retrouvée que si les mots
 * de la demande la recoupaient (« rentrer à la maison » ne recoupe pas « adresse »), elle pouvait être coupée quand le
 * relais IA raccourcissait les consignes, et aucun outil ne savait qu'un surnom désignait un serveur Discord. Ici :
 *  - chaque note / « retiens que… » est analysée et ses faits rangés (règles immédiates + IA en arrière-plan) ;
 *  - les faits sont TOUJOURS donnés à Jarvis et Hermès (bloc essentiel, jamais raccourci) ;
 *  - les outils remplacent eux-mêmes un surnom par sa cible (serveur, salon, contact, destination…).
 */
object Faits {
    data class Surnom(val surnom: String, val cible: String, val domaine: String)

    private fun f(app: JarvisApp) = File(app.filesDir, "faits.json")
    @Synchronized private fun lire(app: JarvisApp): JSONObject = runCatching { JSONObject(f(app).readText()) }.getOrDefault(JSONObject())
    @Synchronized private fun ecrire(app: JarvisApp, o: JSONObject) {
        runCatching { f(app).writeText(o.toString(2)) }
        // Copie lisible dans Obsidian (JARVIS/Mémoire — faits.md).
        runCatching { app.obsidian.saveIn("JARVIS", "Mémoire — faits", markdown(o)) }
    }

    private fun cle(s: String) = normaliser(s).replace(Regex("^(le|la|les|l|mon|ma|mes|notre|nos|un|une|du|des|au|aux|a la|a l)\\s+"), "").trim()

    fun lieux(app: JarvisApp): Map<String, String> = lire(app).optJSONObject("lieux")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()
    fun surnoms(app: JarvisApp): List<Surnom> = lire(app).optJSONArray("surnoms")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Surnom(it.optString("surnom"), it.optString("cible"), it.optString("domaine", "autre")) }
    }.orEmpty()
    fun faits(app: JarvisApp): Map<String, String> = lire(app).optJSONObject("faits")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }.orEmpty()

    @Synchronized fun definirLieu(app: JarvisApp, nom: String, adresse: String): String {
        val n = nomLieu(nom); val v = adresse.trim().trimEnd('.', ' ')
        if (n.isBlank() || v.isBlank()) return ""
        val o = lire(app); val l = o.optJSONObject("lieux") ?: JSONObject()
        l.put(n, v); o.put("lieux", l); ecrire(app, o)
        return "📍 $n : $v"
    }

    @Synchronized fun definirSurnom(app: JarvisApp, surnom: String, cible: String, domaine: String = "autre"): String {
        val s = surnom.trim().trim('«', '»', '"', '\'', ' '); val c = cible.trim().trim('«', '»', '"', '\'', ' ')
        if (s.isBlank() || c.isBlank() || cle(s) == cle(c)) return ""
        val o = lire(app); val a = o.optJSONArray("surnoms") ?: JSONArray()
        val garde = JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { cle(it.optString("surnom")) != cle(s) }?.let { garde.put(it) }
        garde.put(JSONObject().put("surnom", s).put("cible", c).put("domaine", domaine.ifBlank { "autre" }))
        o.put("surnoms", garde); ecrire(app, o)
        return "🏷️ « $s » = ${libelleDomaine(domaine)}« $c »"
    }

    @Synchronized fun definirFait(app: JarvisApp, cleFait: String, valeur: String): String {
        if (cleFait.isBlank() || valeur.isBlank()) return ""
        val o = lire(app); val l = o.optJSONObject("faits") ?: JSONObject()
        l.put(cleFait.trim().lowercase().take(60), valeur.trim().take(300)); o.put("faits", l); ecrire(app, o)
        return "📌 ${cleFait.trim()} : ${valeur.trim()}"
    }

    /** Oublie un lieu, un surnom ou un fait (par son nom). */
    @Synchronized fun oublier(app: JarvisApp, quoi: String): Boolean {
        val k = cle(quoi); val o = lire(app); var fait = false
        o.optJSONObject("lieux")?.let { l -> l.keys().asSequence().toList().filter { cle(it) == k || cle(it) == nomLieu(quoi) }.forEach { l.remove(it); fait = true } }
        o.optJSONObject("faits")?.let { l -> l.keys().asSequence().toList().filter { cle(it) == k }.forEach { l.remove(it); fait = true } }
        o.optJSONArray("surnoms")?.let { a ->
            val g = JSONArray(); for (i in 0 until a.length()) a.optJSONObject(i)?.let { if (cle(it.optString("surnom")) == k) fait = true else g.put(it) }; o.put("surnoms", g)
        }
        if (fait) ecrire(app, o)
        return fait
    }

    private fun libelleDomaine(d: String) = when (d) {
        "discord_serveur" -> "serveur Discord "; "discord_salon" -> "salon Discord "; "contact" -> "contact "; "appareil" -> "appareil "; "lieu" -> "lieu "; else -> ""
    }

    /** Nom de lieu canonique : « chez moi », « la maison », « mon adresse » → domicile ; « le bureau », « le travail » → travail. */
    fun nomLieu(s: String): String {
        val n = cle(s)
        return when {
            Regex("^(maison|domicile|chez moi|chez nous|adresse|adresse perso\\w*|adresse du domicile|adresse de la maison|logement|appartement)$").matches(n) -> "domicile"
            Regex("^(travail|boulot|bureau|societe|entreprise|depot|atelier|siege)$").matches(n) -> "travail"
            else -> n
        }
    }

    // ------------------------------------------------------------------ restitution

    private fun markdown(o: JSONObject): String = buildString {
        append("# Mémoire — faits durables\n\n_Tenue par JARVIS (lieux, surnoms, faits). Pour corriger : « Jarvis, retiens que… » ou « oublie … »._\n\n")
        o.optJSONObject("lieux")?.let { l -> if (l.length() > 0) { append("## Lieux\n"); l.keys().forEach { append("- **$it** : ${l.optString(it)}\n") }; append('\n') } }
        o.optJSONArray("surnoms")?.let { a -> if (a.length() > 0) { append("## Surnoms\n"); for (i in 0 until a.length()) a.optJSONObject(i)?.let { append("- « ${it.optString("surnom")} » = ${libelleDomaine(it.optString("domaine"))}« ${it.optString("cible")} »\n") }; append('\n') } }
        o.optJSONObject("faits")?.let { l -> if (l.length() > 0) { append("## Faits\n"); l.keys().forEach { append("- **$it** : ${l.optString(it)}\n") } } }
    }

    /** Bloc donné à Jarvis et à Hermès à CHAQUE demande (vide s'il n'y a rien). */
    fun bloc(app: JarvisApp): String {
        val l = lieux(app); val s = surnoms(app); val fa = faits(app)
        if (l.isEmpty() && s.isEmpty() && fa.isEmpty()) return ""
        return buildString {
            append("MÉMOIRE FIABLE de l'utilisateur (à utiliser sans la redemander ; les outils remplacent déjà eux-mêmes un surnom par sa cible) :")
            if (l.isNotEmpty()) append(" Lieux — " + l.entries.joinToString(" ; ") { "${it.key} : ${it.value}" } + ".")
            if (s.isNotEmpty()) append(" Surnoms — " + s.joinToString(" ; ") { "« ${it.surnom} » = ${libelleDomaine(it.domaine)}« ${it.cible} »" } + ".")
            if (fa.isNotEmpty()) append(" Faits — " + fa.entries.joinToString(" ; ") { "${it.key} : ${it.value}" } + ".")
        }.take(1600)
    }

    fun texte(app: JarvisApp): String = markdown(lire(app)).let { if (lieux(app).isEmpty() && surnoms(app).isEmpty() && faits(app).isEmpty()) "Aucun fait mémorisé pour l'instant." else it }

    // ------------------------------------------------------------------ application aux outils

    private val CLES_LIEU = setOf("destination", "adresse", "lieu", "location", "depart", "origine", "arrivee", "ville", "site_adresse")
    private val CLES_SURNOM = setOf("serveur", "guild", "salon", "channel", "canal", "contact", "destinataire", "nom", "appareil", "membre", "role", "to", "personne", "titre_contact")

    /** Remplace dans les arguments d'un outil un surnom par sa cible, « la maison / chez moi » par l'adresse du domicile. */
    fun appliquer(app: JarvisApp, outil: String, a: JSONObject): JSONObject {
        // Outils qui manipulent la mémoire ou les noms eux-mêmes : jamais de remplacement.
        if (outil.startsWith("memoire") || outil.startsWith("note") || outil.contains("nommer") || outil.startsWith("carnet") || outil == "modes_jarvis") return a
        val l by lazy { lieux(app) }; val s by lazy { surnoms(app) }
        var copie: JSONObject? = null
        for (k in a.keys().asSequence().toList()) {
            val v = a.opt(k) as? String ?: continue
            if (v.isBlank() || v.length > 80) continue
            val nouveau = when (k.lowercase()) {
                in CLES_LIEU -> l[nomLieu(v)] ?: s.firstOrNull { cle(it.surnom) == cle(v) && it.domaine in setOf("lieu", "autre") }?.cible
                in CLES_SURNOM -> if (k == "nom" && !(outil.startsWith("discord") || Regex("contact|appel|sms|message|whatsapp").containsMatchIn(outil))) null else {
                    val dom = when { k.contains("serveur") || k == "guild" -> setOf("discord_serveur"); k in setOf("salon", "channel", "canal") -> setOf("discord_salon")
                        k in setOf("contact", "destinataire", "to", "personne") -> setOf("contact"); k == "appareil" -> setOf("appareil"); else -> emptySet() }
                    val discord = outil.startsWith("discord")
                    s.filter { cle(it.surnom) == cle(v) }.sortedByDescending { it.domaine in dom }
                        .firstOrNull { it.domaine in dom || it.domaine == "autre" || (!discord && !it.domaine.startsWith("discord")) }?.cible
                }
                else -> null
            }
            if (nouveau != null && nouveau != v) { if (copie == null) copie = JSONObject(a.toString()); copie.put(k, nouveau) }
        }
        return copie ?: a
    }

    // ------------------------------------------------------------------ extraction

    private val R_ADRESSE = Regex("\\b(\\d{1,4}\\s*(bis|ter)?,?\\s+(rue|avenue|av|boulevard|bd|chemin|route|impasse|all[ée]e|place|quai|cours|lotissement|lieu[- ]dit|hameau|r[ée]sidence)\\b|\\b\\d{5}\\b|\\b(rue|avenue|chemin|route|impasse|all[ée]e|lieu[- ]dit)\\b)", RegexOption.IGNORE_CASE)
    private val R_DOMICILE = listOf(
        Regex("(?:mon|notre|ma) (?:adresse(?: (?:du domicile|de la maison|perso(?:nnelle)?|de chez moi|postale))?|domicile|maison)\\s*(?:est|c'est|c’est|:|=|se trouve(?: au| à| a)?)\\s*(?:au |à |a )?(.{6,160})", RegexOption.IGNORE_CASE),
        Regex("\\bj'?\\s*habite (?:au |à |a |dans |en )?(.{6,160})", RegexOption.IGNORE_CASE),
    )
    private val R_TRAVAIL = Regex("adresse (?:du travail|de mon travail|du bureau|de la soci[ée]t[ée]|de l'entreprise|du d[ée]p[ôo]t|de l'atelier)\\s*(?:est|c'est|:|=)\\s*(.{6,160})", RegexOption.IGNORE_CASE)
    private val R_SURNOMS = listOf(
        // « les potes » c'est le serveur Discord « Les Copains du 21 »
        Regex("[«\"“]\\s*(.{2,40}?)\\s*[»\"”],?\\s*(?:c'est|c’est|=|d[ée]signe|correspond [àa]) (?:le |la |l'|les )?(serveur|salon|canal|groupe|contact)?(?: discord)?\\s*[«\"“]?\\s*(.{2,60}?)\\s*[»\"”]?\\s*\\.?$", RegexOption.IGNORE_CASE),
        // le surnom du serveur XYZ est « les potes »
        Regex("(?:le )?surnom (?:du |de la |de l'|des |de )?(serveur|salon|contact)?\\s*(?:discord )?[«\"“]?(.{2,60}?)[»\"”]?\\s*(?:est|c'est|:|=)\\s*[«\"“]?(.{2,40}?)[»\"”]?\\s*\\.?$", RegexOption.IGNORE_CASE),
        // quand je dis « les potes », je parle du serveur XYZ
        Regex("quand je dis [«\"“]?(.{2,40}?)[»\"”]?,?\\s*(?:je parle (?:du|de la|de l'|des|de)|c'est|il s'agit (?:du|de la|de l'|des|de)|[çc]a veut dire)\\s*(serveur|salon|contact)?(?: discord)?\\s*[«\"“]?(.{2,60}?)[»\"”]?\\s*\\.?$", RegexOption.IGNORE_CASE),
    )

    private fun domaineDe(mot: String?, texte: String): String {
        val m = normaliser(mot.orEmpty()); val n = normaliser(texte); val discord = n.contains("discord")
        return when {
            m == "serveur" -> if (Regex("debian|ssh|linux|home assistant|plex").containsMatchIn(n) && !discord) "autre" else "discord_serveur"
            m in setOf("salon", "canal") -> "discord_salon"
            m == "groupe" -> if (discord) "discord_serveur" else "autre"
            m == "contact" -> "contact"
            discord -> "discord_serveur"
            else -> "autre"
        }
    }

    /** Faits reconnus immédiatement par des règles (adresse, surnoms) ; renvoie ce qui a été retenu. */
    fun extraireRapide(app: JarvisApp, texte: String): List<String> {
        val out = mutableListOf<String>()
        val t = texte.trim()
        for (r in R_DOMICILE) r.find(t)?.groupValues?.get(1)?.let { adr -> nettoyerAdresse(adr)?.let { out += definirLieu(app, "domicile", it) } }
        R_TRAVAIL.find(t)?.groupValues?.get(1)?.let { adr -> nettoyerAdresse(adr)?.let { out += definirLieu(app, "travail", it) } }
        R_SURNOMS[0].find(t)?.let { m -> out += definirSurnom(app, m.groupValues[1], m.groupValues[3], domaineDe(m.groupValues[2], t)) }
        R_SURNOMS[1].find(t)?.let { m -> out += definirSurnom(app, m.groupValues[3], m.groupValues[2], domaineDe(m.groupValues[1], t)) }
        R_SURNOMS[2].find(t)?.let { m -> out += definirSurnom(app, m.groupValues[1], m.groupValues[3], domaineDe(m.groupValues[2], t)) }
        return out.filter { it.isNotBlank() }.distinct()
    }

    private fun nettoyerAdresse(brut: String): String? {
        val a = brut.substringBefore("\n").replace(Regex("\\s+(et|mais|donc|alors)\\s+(prends|note|retiens|souviens).*$", RegexOption.IGNORE_CASE), "")
            .trim().trimEnd('.', ',', ';', ' ')
        return a.takeIf { it.length >= 6 && R_ADRESSE.containsMatchIn(it) }
    }

    /**
     * Extraction par l'IA (en arrière-plan) des faits durables d'une note : lieux, surnoms, faits. Rien n'est inventé :
     * seulement ce que la note dit explicitement.
     */
    suspend fun extraireIa(app: JarvisApp, texte: String): List<String> {
        if (texte.isBlank()) return emptyList()
        val sys = "Tu extrais d'une note de l'utilisateur UNIQUEMENT les faits durables qu'elle énonce explicitement, en JSON strict : " +
            "{\"lieux\":[{\"nom\":\"domicile|travail|autre nom\",\"adresse\":\"...\"}],\"surnoms\":[{\"surnom\":\"...\",\"cible\":\"nom exact de ce qu'il désigne\",\"domaine\":\"discord_serveur|discord_salon|contact|appareil|lieu|autre\"}],\"faits\":[{\"cle\":\"...\",\"valeur\":\"...\"}]}. " +
            "Un surnom = un autre nom donné à un serveur Discord, un salon, une personne, un appareil ou un lieu. N'invente rien ; listes vides si rien. Réponds seulement le JSON."
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys)).put(JSONObject().put("role", "user").put("content", texte.take(2000)))
        val rep = runCatching { app.llm.groqChat(msgs, null, forceText = true).optString("content") }.getOrDefault("")
        val json = runCatching { JSONObject(rep.substring(rep.indexOf('{'), rep.lastIndexOf('}') + 1)) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        json.optJSONArray("lieux")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += definirLieu(app, it.optString("nom"), it.optString("adresse")) } }
        json.optJSONArray("surnoms")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += definirSurnom(app, it.optString("surnom"), it.optString("cible"), it.optString("domaine")) } }
        json.optJSONArray("faits")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += definirFait(app, it.optString("cle"), it.optString("valeur")) } }
        return out.filter { it.isNotBlank() }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Après une note : règles tout de suite, IA en arrière-plan (sans faire attendre la réponse). */
    fun apprendre(app: JarvisApp, texte: String): List<String> {
        val vite = runCatching { extraireRapide(app, texte) }.getOrDefault(emptyList())
        scope.launch { runCatching { extraireIa(app, texte) } }
        return vite
    }

    /** Une fois : les notes déjà prises (carnet, mémoire) sont relues pour en tirer les faits (adresse, surnoms…). */
    fun rattrapage(app: JarvisApp) {
        if (lire(app).optBoolean("rattrapage_fait")) return
        scope.launch {
            val textes = runCatching { Carnet.toutes(app).map { it.texte } }.getOrDefault(emptyList()) +
                runCatching { app.memo.all().values.toList() }.getOrDefault(emptyList())
            textes.forEach { runCatching { extraireRapide(app, it) } }
            textes.joinToString("\n---\n").chunked(1800).take(6).forEach { runCatching { extraireIa(app, it) } }
            synchronized(this@Faits) { val o = lire(app); o.put("rattrapage_fait", true); runCatching { f(app).writeText(o.toString(2)) } }
        }
    }
}
