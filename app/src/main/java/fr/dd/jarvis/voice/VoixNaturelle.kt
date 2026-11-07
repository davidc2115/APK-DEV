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
    /** Voix proposées dans les réglages (nom Gemini → description). */
    val VOIX = linkedMapOf(
        "Charon" to "posée, informative (homme)", "Orus" to "ferme, assurée (homme)", "Iapetus" to "claire (homme)",
        "Algenib" to "grave, rocailleuse (homme)", "Sadaltager" to "érudite (homme)", "Kore" to "ferme (femme)",
        "Aoede" to "légère (femme)", "Sulafat" to "chaleureuse (femme)", "Despina" to "douce (femme)", "Puck" to "enjouée",
    )
    private val MODELES = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
    private const val STYLE = "Voix d'un assistant personnel futuriste, calme, posée et assurée, débit naturel et fluide, en français."
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
        val voix = app.store.voixNom.value.ifBlank { "Charon" }
        val modeles = (listOfNotNull(modeleQuiMarche) + MODELES).distinct()
        var quota = false; var dernier = ""
        for (m in modeles) {
            for (i in cles.indices) {
                val k = cles[(cle + i) % cles.size]
                try {
                    val a = essayer(k, m, texte, voix)
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
    private fun essayer(k: String, m: String, texte: String, voix: String): Audio {
        if (m.startsWith("gemini-2.5")) return ancienne(k, m, texte, voix)
        return try { interactions(k, m, texte, voix) } catch (e: Echec) {
            if (e.code in setOf(400, 404, 501)) ancienne(k, m, texte, voix) else throw e
        }
    }

    /** Essai de chaque modèle avec la première clé : résultat lisible (pour comprendre pourquoi la voix ne passe pas). */
    fun diagnostic(app: JarvisApp): String {
        val k = app.store.geminiKeys.firstOrNull { it.isNotBlank() } ?: return "Aucune clé Gemini dans les réglages."
        val voix = app.store.voixNom.value.ifBlank { "Charon" }
        return MODELES.joinToString("\n") { m ->
            val debut = System.currentTimeMillis()
            try {
                val a = essayer(k, m, "Bonjour, je suis JARVIS.", voix)
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

    private fun interactions(cle: String, modele: String, texte: String, voix: String): Audio {
        val corps = JSONObject().put("model", modele)
            .put("input", JSONArray().put(JSONObject().put("type", "user_input").put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", texte)
                    .put("annotations", JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", STYLE)))))))
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voix))))
        return audioDe(post("https://generativelanguage.googleapis.com/v1beta/interactions", cle, corps))
            ?: throw Echec(502, "réponse sans audio")
    }

    private fun ancienne(cle: String, modele: String, texte: String, voix: String): Audio {
        val corps = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", texte)))))
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
