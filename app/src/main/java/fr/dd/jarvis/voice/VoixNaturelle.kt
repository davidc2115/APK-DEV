package fr.dd.jarvis.voice

import fr.dd.jarvis.JarvisApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Voix naturelle de Jarvis : synthèse vocale de Gemini (avec les clés Gemini des réglages), phrase par phrase.
 * Si la synthèse échoue (quota, réseau, modèle indisponible), la voix Android prend le relais pendant un moment.
 *
 * API « Interactions » (modèles Gemini TTS actuels) ; repli sur l'ancienne API generateContent pour les modèles 2.5.
 * Audio reçu : WAV ou PCM brut 16 bits mono (24 kHz).
 */
object VoixNaturelle {
    /** Les 30 voix de Gemini : nom → (genre, caractère). */
    val CATALOGUE: Map<String, Pair<String, String>> = linkedMapOf(
        "Charon" to ("homme" to "informative"), "Orus" to ("homme" to "ferme"), "Iapetus" to ("homme" to "claire"),
        "Algenib" to ("homme" to "rocailleuse"), "Sadaltager" to ("homme" to "érudite"), "Puck" to ("homme" to "enjouée"),
        "Fenrir" to ("homme" to "vive"), "Enceladus" to ("homme" to "soufflée"), "Umbriel" to ("homme" to "décontractée"),
        "Algieba" to ("homme" to "veloutée"), "Rasalgethi" to ("homme" to "informative"), "Alnilam" to ("homme" to "ferme"),
        "Schedar" to ("homme" to "égale"), "Achird" to ("homme" to "amicale"), "Zubenelgenubi" to ("homme" to "familière"),
        "Sadachbia" to ("homme" to "animée"),
        "Kore" to ("femme" to "ferme"), "Zephyr" to ("femme" to "lumineuse"), "Leda" to ("femme" to "jeune"),
        "Aoede" to ("femme" to "légère"), "Callirrhoe" to ("femme" to "décontractée"), "Autonoe" to ("femme" to "lumineuse"),
        "Despina" to ("femme" to "veloutée"), "Erinome" to ("femme" to "claire"), "Laomedeia" to ("femme" to "enjouée"),
        "Achernar" to ("femme" to "douce"), "Gacrux" to ("femme" to "mûre"), "Pulcherrima" to ("femme" to "affirmée"),
        "Vindemiatrix" to ("femme" to "tendre"), "Sulafat" to ("femme" to "chaleureuse"),
    )

    /**
     * Un personnage de voix : une voix Gemini + une description (âge, ton, débit) qui oriente l'interprétation ;
     * [hauteur] et [debit] règlent la voix Android de repli (même genre) pour rester proche du personnage.
     */
    data class Personnage(val id: String, val icone: String, val nom: String, val groupe: String, val voix: String, val style: String,
                          val hauteur: Float = 1f, val debit: Float = 1f, val mots: Set<String> = emptySet()) {
        val genre get() = CATALOGUE[voix]?.first ?: "homme"
        val libelle get() = "$icone $nom"
    }

    const val SUR_MESURE = "perso"
    private const val FR = " Parle en français, avec un débit naturel et fluide."

    val PERSONNAGES = listOf(
        Personnage("jarvis", "🤵", "Jarvis, posé", "Hommes", "Charon", "Voix d'un assistant personnel futuriste, calme, posée et assurée, comme un majordome attentionné.", 1f, 1f,
            setOf("jarvis", "majordome", "defaut", "normal", "normale", "habituelle", "pose", "homme", "adulte")),
        Personnage("jeune_homme", "🧑", "Jeune homme dynamique", "Hommes", "Puck", "Voix d'un jeune homme d'environ 25 ans, dynamique, souriante et spontanée, débit vif.", 1.08f, 1.08f,
            setOf("jeune", "homme", "garcon", "dynamique", "enjoue", "ado", "jeunot")),
        Personnage("homme_assure", "👨‍💼", "Homme assuré, la quarantaine", "Hommes", "Orus", "Voix d'un homme d'une quarantaine d'années, assurée, nette et professionnelle.", 0.95f, 1f,
            setOf("homme", "assure", "ferme", "professionnel", "quarantaine", "adulte", "pro")),
        Personnage("homme_grave", "🧔", "Homme mûr, voix grave", "Hommes", "Algenib", "Voix d'un homme d'une cinquantaine d'années, grave, profonde et chaleureuse, débit posé.", 0.85f, 0.95f,
            setOf("homme", "grave", "profonde", "mur", "cinquantaine", "basse")),
        Personnage("homme_age", "👴", "Homme âgé, sage", "Hommes", "Sadaltager", "Voix d'un homme âgé d'environ 75 ans, sage et bienveillante, légèrement voilée, débit lent et posé.", 0.8f, 0.85f,
            setOf("homme", "age", "vieux", "vieil", "sage", "grandpere", "papy", "papi", "senior", "ancien", "aine")),
        Personnage("femme_assuree", "👩‍💼", "Femme assurée (façon FRIDAY)", "Femmes", "Kore", "Voix d'une femme d'environ 35 ans, claire, assurée et efficace, comme une assistante futuriste.", 1.05f, 1f,
            setOf("femme", "feminine", "assuree", "ferme", "friday", "adulte", "pro", "professionnelle")),
        Personnage("jeune_femme", "👱‍♀️", "Jeune femme pétillante", "Femmes", "Leda", "Voix d'une jeune femme d'environ 22 ans, pétillante, souriante et spontanée.", 1.15f, 1.08f,
            setOf("femme", "jeune", "fille", "petillante", "enjouee", "dynamique", "ado")),
        Personnage("femme_douce", "🌸", "Femme douce, apaisante", "Femmes", "Achernar", "Voix d'une femme douce, calme et apaisante, débit lent et rassurant.", 1f, 0.92f,
            setOf("femme", "douce", "doux", "calme", "apaisante", "zen", "rassurante", "relax")),
        Personnage("femme_mure", "👩", "Femme mûre, chaleureuse", "Femmes", "Gacrux", "Voix d'une femme d'une cinquantaine d'années, chaleureuse, rassurante et posée.", 0.92f, 0.95f,
            setOf("femme", "mure", "chaleureuse", "cinquantaine", "maman", "mere")),
        Personnage("femme_agee", "👵", "Femme âgée, tendre", "Femmes", "Sulafat", "Voix d'une dame âgée d'environ 75 ans, tendre et bienveillante, légèrement chevrotante, débit lent.", 0.88f, 0.85f,
            setOf("femme", "agee", "vieille", "grandmere", "mamie", "mami", "senior", "tendre", "dame", "ancienne")),
        Personnage("ia", "🤖", "IA futuriste", "Spéciales", "Schedar", "Voix d'une intelligence artificielle futuriste : neutre, précise, régulière, très légèrement synthétique.", 0.9f, 1f,
            setOf("ia", "robot", "robotique", "synthetique", "machine", "futuriste", "neutre", "ordinateur")),
        Personnage("narrateur", "🎬", "Narrateur de cinéma", "Spéciales", "Alnilam", "Voix de narrateur de bande-annonce de cinéma, profonde, intense et captivante.", 0.82f, 0.92f,
            setOf("narrateur", "cinema", "film", "bandeannonce", "epique", "conteur")),
        Personnage("radio", "📻", "Animatrice radio", "Spéciales", "Laomedeia", "Voix d'animatrice de radio du matin, enjouée, énergique et très articulée.", 1.08f, 1.1f,
            setOf("radio", "animatrice", "presentatrice", "matin", "energique", "speakerine")),
        Personnage("murmure", "🤫", "Murmure", "Spéciales", "Enceladus", "Voix basse et feutrée, presque chuchotée, calme et intime.", 0.95f, 0.9f,
            setOf("chuchote", "chuchotee", "murmure", "chuchotement", "basse", "feutree", "discrete")),
    )

    /** Personnage choisi (un ancien choix de voix Gemini devient un personnage « sur mesure »). */
    fun personnage(app: JarvisApp): Personnage {
        val id = app.store.voixPersonnage.value
        PERSONNAGES.firstOrNull { it.id == id }?.let { return it }
        val voix = app.store.voixNom.value.ifBlank { "Charon" }.takeIf { it in CATALOGUE } ?: "Charon"
        if (id.isBlank() && voix == "Charon") return PERSONNAGES[0]
        return surMesure(voix, app.store.voixStyle.value)
    }

    fun surMesure(voix: String, style: String): Personnage {
        val g = CATALOGUE[voix]?.first ?: "homme"
        return Personnage(SUR_MESURE, "🎛️", "Sur mesure · $voix", "Sur mesure", voix,
            style.trim().ifBlank { "Voix ${CATALOGUE[voix]?.second ?: "naturelle"} d'${if (g == "femme") "une femme" else "un homme"}, naturelle et posée." })
    }

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Personnage décrit en mots (« une voix de femme âgée », « le robot », « Kore »…) ; null si rien ne correspond. */
    fun trouver(texte: String): Personnage? {
        val n = norm(texte)
        CATALOGUE.keys.firstOrNull { Regex("\\b${it.lowercase()}\\b").containsMatchIn(n) }?.let { v ->
            return PERSONNAGES.firstOrNull { it.voix == v } ?: surMesure(v, "")
        }
        val mots = n.replace(Regex("grand[- ]?(pere|mere)")) { it.value.replace(Regex("[- ]"), "") }
            .replace(Regex("bande[- ]annonce"), "bandeannonce").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            .map { it.removeSuffix("s") }.toSet()
        val femme = mots.any { it in setOf("femme", "feminine", "fille", "dame", "mamie", "mami", "grandmere", "vieille") }
        val homme = mots.any { it in setOf("homme", "masculine", "masculin", "garcon", "monsieur", "papy", "papi", "grandpere", "vieux", "mec") }
        return PERSONNAGES.map { p ->
            var score = p.mots.count { it in mots } * 2
            if (femme && p.genre == "femme" || homme && p.genre == "homme") score += 1
            if (femme && !homme && p.genre == "homme" || homme && !femme && p.genre == "femme") score -= 5
            p to score
        }.filter { it.second > 1 }.maxByOrNull { it.second }?.first
    }

    /** Description de la voix pour cette phrase : personnage + situation (nuit : plus bas ; voiture : bien articulé). */
    fun style(app: JarvisApp, p: Personnage = personnage(app)): String {
        val situation = when {
            app.enVoiture -> " Articule bien, débit un peu plus posé, on t'écoute en conduisant."
            runCatching { fr.dd.jarvis.device.Contexte.nuitMaintenant(app) }.getOrDefault(false) -> " C'est la nuit : parle plus doucement et plus bas, avec calme."
            else -> ""
        }
        return p.style + situation + FR
    }

    /** Voix proposées dans les réglages (nom Gemini → description). */
    val VOIX: Map<String, String> get() = CATALOGUE.mapValues { (_, v) -> "${v.second} (${v.first})" }

    private val MODELES = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()

    @Volatile private var indisponibleJusqua = 0L
    @Volatile var raison = ""
        private set
    @Volatile private var modeleQuiMarche: String? = null
    @Volatile private var cle = 0

    fun configuree(app: JarvisApp) = app.store.voixNaturelle.value && app.store.geminiKeys.any { it.isNotBlank() }
    fun active(app: JarvisApp) = configuree(app) && System.currentTimeMillis() >= indisponibleJusqua

    /** État lisible pour les réglages. */
    fun etat(app: JarvisApp): String = when {
        !app.store.voixNaturelle.value -> "désactivée (voix Android)"
        app.store.geminiKeys.none { it.isNotBlank() } -> "aucune clé Gemini : voix Android"
        System.currentTimeMillis() < indisponibleJusqua -> "en pause ($raison) : voix Android pour l'instant"
        else -> "active" + (modeleQuiMarche?.let { " · $it" } ?: "")
    }

    class Audio(val pcm: ByteArray, val frequence: Int, val canaux: Int)

    private class Echec(val code: Int, message: String) : Exception(message)

    /** Synthèse d'une phrase (bloquant, hors fil principal). Null = utiliser la voix Android. */
    fun synthese(app: JarvisApp, texte: String): Audio? {
        if (!active(app) || texte.isBlank()) return null
        val cles = app.store.geminiKeys.filter { it.isNotBlank() }
        val p = personnage(app)
        val voix = p.voix
        val style = style(app, p)
        val modeles = (listOfNotNull(modeleQuiMarche) + MODELES).distinct()
        var quota = false; var dernier = ""
        for (m in modeles) {
            for (i in cles.indices) {
                val k = cles[(cle + i) % cles.size]
                try {
                    val a = essayer(k, m, texte, voix, style)
                    modeleQuiMarche = m; cle = (cle + i) % cles.size
                    return a
                } catch (e: Echec) {
                    dernier = e.message.orEmpty()
                    when (e.code) {
                        429 -> { quota = true; continue }          // quota de cette clé : clé suivante
                        401, 403 -> continue                        // clé refusée pour ce modèle
                        else -> break                               // modèle inconnu / refusé : modèle suivant
                    }
                } catch (e: Exception) {
                    // Réseau : inutile d'insister, voix Android pendant 1 min.
                    pause(60_000, "réseau : ${e.message ?: e.javaClass.simpleName}"); return null
                }
            }
        }
        if (quota) pause(15 * 60_000, "quota Gemini atteint") else pause(30 * 60_000, dernier.take(80).ifBlank { "synthèse refusée" })
        return null
    }

    /** Un modèle par ses deux API : Interactions (modèles récents), puis generateContent (anciens modèles, ou si refusé). */
    private fun essayer(k: String, m: String, texte: String, voix: String, style: String): Audio {
        if (m.startsWith("gemini-2.5")) return ancienne(k, m, texte, voix, style)
        return try { interactions(k, m, texte, voix, style) } catch (e: Echec) {
            if (e.code in setOf(400, 404, 501)) ancienne(k, m, texte, voix, style) else throw e
        }
    }

    /** Essai de chaque modèle avec la première clé : résultat lisible (pour comprendre pourquoi la voix ne passe pas). */
    fun diagnostic(app: JarvisApp): String {
        val k = app.store.geminiKeys.firstOrNull { it.isNotBlank() } ?: return "Aucune clé Gemini dans les réglages."
        val p = personnage(app)
        return "Voix : ${p.libelle} (${p.voix})\n" + MODELES.joinToString("\n") { m ->
            val debut = System.currentTimeMillis()
            try {
                val a = essayer(k, m, "Bonjour, je suis JARVIS.", p.voix, style(app, p))
                modeleQuiMarche = modeleQuiMarche ?: m
                "✅ $m : ${a.pcm.size / (2 * a.frequence.coerceAtLeast(1))} s d'audio en ${System.currentTimeMillis() - debut} ms"
            } catch (e: Echec) {
                "❌ $m : " + when (e.code) { 429 -> "quota atteint (429)"; 404 -> "modèle introuvable (404)"; 403 -> "refusé pour cette clé (403)"; else -> "HTTP ${e.code}" } + " — ${e.message.orEmpty().take(120)}"
            } catch (e: Exception) { "❌ $m : ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    private fun pause(ms: Long, pourquoi: String) { indisponibleJusqua = System.currentTimeMillis() + ms; raison = pourquoi }

    fun reactiver() { indisponibleJusqua = 0; raison = "" }

    private fun post(url: String, cle: String, corps: JSONObject): JSONObject {
        val req = Request.Builder().url(url).header("x-goog-api-key", cle).post(corps.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw Echec(r.code, runCatching { JSONObject(t).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().ifBlank { "HTTP ${r.code}" })
            return JSONObject(t)
        }
    }

    private fun interactions(cle: String, modele: String, texte: String, voix: String, style: String): Audio {
        val corps = JSONObject().put("model", modele)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", texte)
                    .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)))))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voix))))
        return audioDe(post("https://generativelanguage.googleapis.com/v1beta/interactions", cle, corps))
            ?: throw Echec(502, "réponse sans audio")
    }

    private fun ancienne(cle: String, modele: String, texte: String, voix: String, style: String): Audio {
        // Anciens modèles : la description de la voix se donne en tête du texte (« Dis ce qui suit avec … : »).
        val consigne = "Dis ce qui suit avec cette voix — ${style.trim()} :\n$texte"
        val corps = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", consigne)))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voix)))))
        return audioDe(post("https://generativelanguage.googleapis.com/v1beta/models/$modele:generateContent", cle, corps))
            ?: throw Echec(502, "réponse sans audio")
    }

    /** Cherche l'audio (base64) dans la réponse, quelle que soit sa forme (Interactions ou generateContent). */
    internal fun audioDe(rep: JSONObject): Audio? {
        var donnees: String? = null; var type = ""
        fun fouiller(x: Any?) {
            if (donnees != null) return
            when (x) {
                is JSONObject -> {
                    val inline = x.optJSONObject("inlineData") ?: x.optJSONObject("inline_data")
                    if (inline != null && inline.optString("data").isNotBlank()) { donnees = inline.optString("data"); type = inline.optString("mimeType", inline.optString("mime_type")); return }
                    if (x.optString("type") == "audio" && x.optString("data").isNotBlank()) { donnees = x.optString("data"); type = x.optString("mime_type", x.optString("mimeType")); return }
                    x.keys().forEach { k -> fouiller(x.opt(k)) }
                }
                is JSONArray -> for (i in 0 until x.length()) fouiller(x.opt(i))
            }
        }
        // Dernière sortie du modèle d'abord (les étapes précédentes ne portent pas d'audio).
        fouiller(rep)
        val b = donnees?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: return null
        return decoder(b, type)
    }

    /** WAV (en-tête RIFF) ou PCM brut 16 bits (fréquence lue dans le type MIME, 24 kHz par défaut). */
    internal fun decoder(b: ByteArray, type: String = ""): Audio? {
        if (b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WAVE") {
            var i = 12; var freq = 24000; var canaux = 1
            while (i + 8 <= b.size) {
                val id = String(b, i, 4, Charsets.US_ASCII)
                val taille = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8) or ((b[i + 6].toInt() and 0xFF) shl 16) or ((b[i + 7].toInt() and 0xFF) shl 24)
                if (id == "fmt " && i + 16 <= b.size) {
                    canaux = (b[i + 10].toInt() and 0xFF) or ((b[i + 11].toInt() and 0xFF) shl 8)
                    freq = (b[i + 12].toInt() and 0xFF) or ((b[i + 13].toInt() and 0xFF) shl 8) or ((b[i + 14].toInt() and 0xFF) shl 16) or ((b[i + 15].toInt() and 0xFF) shl 24)
                }
                if (id == "data") {
                    val fin = if (taille <= 0 || i + 8 + taille > b.size) b.size else i + 8 + taille
                    return Audio(b.copyOfRange(i + 8, fin), freq.takeIf { it in 8000..48000 } ?: 24000, canaux.coerceIn(1, 2))
                }
                i += 8 + taille + (taille and 1)
                if (taille < 0) break
            }
            return null
        }
        val freq = Regex("rate=(\\d+)").find(type)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
        return if (b.size >= 2) Audio(b, freq, 1) else null
    }

    /**
     * Découpe un texte qui arrive au fil de l'eau en morceaux à dire : la PREMIÈRE phrase seule (pour parler vite), puis
     * des blocs de phrases d'environ [taille] caractères (moins d'appels de synthèse) ; sans [attendreBloc] (la voix est
     * libre), les phrases complètes partent tout de suite. Renvoie (morceaux prêts, reste).
     */
    internal fun decouper(texte: String, dejaUnMorceau: Boolean, toutDire: Boolean, attendreBloc: Boolean = true, taille: Int = 280): Pair<List<String>, String> {
        val out = mutableListOf<String>()
        var reste = texte
        var premier = !dejaUnMorceau
        val fin = Regex("(?<=[.!?…:;])\\s+|\\n+")
        while (true) {
            val limites = fin.findAll(reste).map { it.range.last + 1 }.toList()
            if (limites.isEmpty()) break
            val coupe = if (premier) limites.firstOrNull { reste.substring(0, it).trim().length >= 12 } ?: break
                else limites.lastOrNull { it <= taille } ?: limites.first()
            // Hors première phrase, on attend d'avoir un bloc assez long (sauf si tout doit être dit maintenant).
            if (!premier && !toutDire && attendreBloc && coupe < taille / 2 && limites.last() == coupe) break
            val morceau = reste.substring(0, coupe).trim()
            if (morceau.isNotBlank()) out += morceau
            reste = reste.substring(coupe)
            premier = false
        }
        if (toutDire && reste.isNotBlank()) { out += reste.trim(); reste = "" }
        return out to reste
    }
}
