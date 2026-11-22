package fr.dd.jarvis.voice

import android.content.ContentValues
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.FileProvider
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.voice.VoixNaturelle.Personnage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Messages vocaux enregistrés avec des voix de personnages (Père Noël, lutin, fée…), à écouter, partager
 * (WhatsApp, SMS…) ou enregistrer dans la musique du téléphone.
 *
 * Voix : synthèse Gemini (voix + consigne d'interprétation du personnage), puis effet sonore du personnage (plus aigu
 * pour le lutin, plus grave pour le Père Noël, écho magique pour la fée…). Sans Gemini : voix Android, hauteur et débit
 * réglés pour le personnage. Plusieurs voix : une réplique par ligne « Père Noël : … » / « Lutin : … ».
 */
object MessagesVocaux {

    /** Effet appliqué après la synthèse : [vitesse] > 1 = plus aigu et plus vif, < 1 = plus grave et plus lent. */
    data class Effet(val vitesse: Double = 1.0, val reverb: Double = 0.0, val robot: Boolean = false)

    private fun p(id: String, icone: String, nom: String, groupe: String, voix: String, style: String, hauteur: Float, debit: Float, vararg mots: String) =
        Personnage(id, icone, nom, groupe, voix, style, hauteur, debit, mots.toSet())

    const val NOEL = "🎄 Noël"
    const val CONTES = "✨ Contes et aventures"
    const val QUOTIDIEN = "🗣️ Voix de tous les jours"

    /** Personnages de fantaisie (les voix « normales » viennent de [VoixNaturelle.PERSONNAGES]). */
    val FANTAISIE: List<Personnage> = listOf(
        p("pere_noel", "🎅", "Père Noël", NOEL, "Algenib",
            "Tu es le Père Noël : un vieil homme au grand cœur, voix très grave, ronde, chaude et un peu rocailleuse, joviale et pleine " +
                "de bonhomie, avec de grands rires « Ho ho ho ! » sonores. Débit lent et posé, plein de tendresse et de magie, comme " +
                "s'il parlait à un enfant qu'il aime beaucoup.", 0.72f, 0.85f, "pere noel", "perenoel", "papa noel", "santa", "santa claus", "saint nicolas"),
        p("mere_noel", "🤶", "Mère Noël", NOEL, "Sulafat",
            "Tu es la Mère Noël : une grand-mère douce, chaleureuse et rieuse, voix tendre et enveloppante, un brin malicieuse, débit " +
                "calme, comme au coin du feu avec un chocolat chaud.", 0.95f, 0.9f, "mere noel", "maman noel", "madame noel"),
        p("lutin", "🧝", "Lutin", NOEL, "Puck",
            "Tu es un petit lutin du Père Noël : voix espiègle et pétillante, très enjouée, avec de petits rires malicieux, plein " +
                "d'énergie et d'excitation, mais avec un débit modéré et chaque mot bien articulé.", 1.7f, 1.1f, "lutin", "lutins", "elfe", "elfes", "farfadet", "petit lutin"),
        p("lutine", "🧝‍♀️", "Lutine", NOEL, "Leda",
            "Tu es une petite lutine du Père Noël : voix espiègle, pétillante et rieuse, toute excitée, avec un débit modéré et chaque " +
                "mot bien articulé.", 1.75f, 1.1f, "lutine", "lutines", "petite lutine"),
        p("renne", "🦌", "Renne du traîneau", NOEL, "Fenrir",
            "Tu es un renne du traîneau du Père Noël : voix jeune, enjouée et un peu nasale, fier de voler dans le ciel, rieur et taquin.",
            1.2f, 1.05f, "renne", "rennes", "renne du pere noel", "traineau"),
        p("bonhomme_neige", "⛄", "Bonhomme de neige", NOEL, "Umbriel",
            "Tu es un bonhomme de neige gentil et un peu naïf : voix ronde, douce et toute contente, qui s'émerveille de tout, avec de " +
                "petits rires.", 1.1f, 0.95f, "bonhomme de neige", "bonhomme"),
        p("fee", "🧚", "Fée", CONTES, "Aoede",
            "Tu es une fée des contes : voix aérienne, cristalline, douce et émerveillée, presque chantante, légèrement chuchotée, " +
                "pleine de magie.", 1.3f, 0.95f, "fee", "fees", "fee clochette", "bonne fee", "marraine"),
        p("sorciere", "🧙‍♀️", "Sorcière", CONTES, "Gacrux",
            "Tu es une vieille sorcière de conte, gentille mais farceuse : voix éraillée et chevrotante, malicieuse, avec des " +
                "ricanements « hi hi hi ! », débit théâtral.", 1.1f, 0.95f, "sorciere", "sorcieres"),
        p("magicien", "🧙", "Magicien", CONTES, "Sadaltager",
            "Tu es un vieux magicien sage : voix grave, mystérieuse et bienveillante, débit lent et solennel, comme s'il révélait un " +
                "grand secret.", 0.85f, 0.85f, "magicien", "enchanteur", "merlin"),
        p("geant", "👹", "Géant", CONTES, "Alnilam",
            "Tu es un géant gentil : voix énorme, très grave et tonitruante, débit lent, qui rit fort mais avec douceur.", 0.6f, 0.8f,
            "geant", "ogre", "monstre", "troll"),
        p("dragon", "🐉", "Dragon", CONTES, "Algieba",
            "Tu es un dragon gentil : voix très grave et grondante, qui souffle un peu, impressionnante mais amicale.", 0.62f, 0.85f,
            "dragon", "dragons"),
        p("pirate", "🏴‍☠️", "Pirate", CONTES, "Orus",
            "Tu es un vieux capitaine pirate bourru et rigolo : voix rauque et puissante, qui roule les r, avec des « Arrr ! » et des " +
                "« moussaillon ! ».", 0.85f, 0.95f, "pirate", "pirates", "capitaine", "corsaire"),
        p("robot", "🤖", "Robot", CONTES, "Schedar",
            "Tu es un robot : voix monotone, saccadée et très régulière, chaque mot détaché, sans émotion.", 0.9f, 0.9f,
            "robot", "androide", "cyborg"),
        p("fantome", "👻", "Fantôme", CONTES, "Enceladus",
            "Tu es un gentil fantôme : voix soufflée, lente et mystérieuse, avec des « Hou hou ! » qui résonnent.", 0.85f, 0.8f,
            "fantome", "fantomes", "spectre", "revenant"),
        p("super_heros", "🦸", "Super-héros", CONTES, "Iapetus",
            "Tu es un super-héros : voix héroïque, puissante, pleine d'assurance et d'enthousiasme, comme dans un dessin animé.", 0.95f, 1.0f,
            "super heros", "superheros", "heros", "super heroine"),
        p("princesse", "👸", "Princesse", CONTES, "Zephyr",
            "Tu es une princesse de conte : voix douce, lumineuse et gracieuse, souriante et bienveillante.", 1.15f, 0.95f,
            "princesse", "reine", "fee princesse"),
    )

    val EFFETS: Map<String, Effet> = mapOf(
        "pere_noel" to Effet(vitesse = 0.93, reverb = 0.12), "mere_noel" to Effet(reverb = 0.08),
        "lutin" to Effet(vitesse = 1.3, reverb = 0.05), "lutine" to Effet(vitesse = 1.27, reverb = 0.05),
        "renne" to Effet(vitesse = 1.08), "bonhomme_neige" to Effet(vitesse = 1.04, reverb = 0.12),
        "fee" to Effet(vitesse = 1.1, reverb = 0.4), "sorciere" to Effet(vitesse = 1.03, reverb = 0.2),
        "magicien" to Effet(vitesse = 0.96, reverb = 0.3), "geant" to Effet(vitesse = 0.8, reverb = 0.3),
        "dragon" to Effet(vitesse = 0.82, reverb = 0.35), "pirate" to Effet(vitesse = 0.97),
        "robot" to Effet(robot = true), "fantome" to Effet(vitesse = 0.93, reverb = 0.6),
        "super_heros" to Effet(reverb = 0.15), "princesse" to Effet(reverb = 0.1),
    )

    val EXEMPLES: Map<String, String> = mapOf(
        "pere_noel" to "Ho ho ho ! Bonjour, c'est le Père Noël ! J'ai une grande nouvelle pour toi.",
        "mere_noel" to "Coucou mon trésor, c'est la Mère Noël ! Les biscuits sortent tout juste du four.",
        "lutin" to "Hi hi ! Salut, c'est moi, le lutin ! On prépare les cadeaux à toute vitesse !",
        "lutine" to "Coucou, c'est la lutine ! J'emballe les cadeaux avec des rubans tout brillants !",
        "renne" to "Salut ! C'est moi, le renne du traîneau. Cette nuit, on vole jusqu'à chez toi !",
        "bonhomme_neige" to "Oh, bonjour ! Je suis un bonhomme de neige, et j'adore les câlins tout doux.",
        "fee" to "Bonjour petite étoile, je suis la fée. D'un coup de baguette, la magie commence.",
        "sorciere" to "Hi hi hi ! Je suis la sorcière, et ma potion est presque prête !",
        "magicien" to "Approche-toi. Je suis le magicien, et j'ai un secret à te confier.",
        "geant" to "Ho ho ! Je suis le géant de la montagne. N'aie pas peur, je suis très gentil !",
        "dragon" to "Grrr… Bonjour ! Je suis le dragon. Je garde le trésor, mais je partage volontiers.",
        "pirate" to "Arrr ! Ohé moussaillon ! C'est le capitaine. Le trésor nous attend !",
        "robot" to "Bonjour. Je suis un robot. Analyse terminée. Tu es formidable.",
        "fantome" to "Hou hou ! Je suis un gentil fantôme. Je fais juste un petit coucou.",
        "super_heros" to "Pas de panique ! Le super-héros est là pour sauver la journée !",
        "princesse" to "Bonjour, je suis la princesse du château. Bienvenue dans mon royaume.",
    )

    val TOUS: List<Personnage> get() = FANTAISIE + VoixNaturelle.PERSONNAGES.map { it.copy(groupe = QUOTIDIEN) }

    fun parId(id: String): Personnage? = TOUS.firstOrNull { it.id == id }

    fun effet(p: Personnage): Effet = EFFETS[p.id] ?: Effet()

    fun exemple(p: Personnage): String = EXEMPLES[p.id] ?: "Bonjour ! Voici à quoi ressemble ma voix pour ton message."

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    private val ARTICLES = Regex("^(le|la|les|l|un|une|du|de la|des|voix de|voix du|voix d|la voix de|la voix du|avec la voix du|avec la voix de|avec la voix d)\\s+")

    /** Personnage désigné par son nom (« Père Noël », « le lutin », « une fée », « Jarvis », « femme âgée »…). */
    fun role(nom: String): Personnage? {
        var n = norm(nom)
        repeat(3) { n = n.replace(ARTICLES, "") }
        if (n.isBlank()) return null
        FANTAISIE.firstOrNull { norm(it.nom) == n || n in it.mots || norm(it.id.replace('_', ' ')) == n }?.let { return it }
        FANTAISIE.firstOrNull { p -> p.mots.any { m -> m.length >= 4 && Regex("(^| )" + Regex.escape(m) + "( |$)").containsMatchIn(n) } }?.let { return it }
        // Voix de tous les jours par leur nom (« Jarvis », « narrateur », « murmure », « IA »), puis par description.
        VoixNaturelle.PERSONNAGES.firstOrNull { p ->
            val nn = norm(p.nom)
            nn == n || p.id == n.replace(' ', '_') || (n.length >= 4 && nn.substringBefore(' ') == n)
        }?.let { return it.copy(groupe = QUOTIDIEN) }
        return VoixNaturelle.trouver(nom)?.let { t -> VoixNaturelle.PERSONNAGES.firstOrNull { it.id == t.id }?.copy(groupe = QUOTIDIEN) ?: t }
    }

    data class Replique(val role: Personnage, val texte: String)

    private val ENTETE = Regex("^\\s*[-*•]?\\s*\\**\\s*([^:：\\n]{2,40}?)\\s*\\**\\s*[:：]\\s*(.*)$")

    /** Texte prêt à dire : sans émojis, mise en forme ni indications de jeu (« (en riant) », « [rire] », « *chuchote* »). */
    fun nettoyer(t: String): String = t
        .replace(Regex("\\[[^\\]]{0,60}\\]"), " ")
        .replace("**", "")
        .replace(Regex("\\*[^*]{0,60}\\*"), " ")
        .replace("*", " ")
        .replace(Regex("\\([^)]{0,50}\\)"), " ")
        .replace(Regex("[\\p{So}\\p{Cn}\\x{FE0F}\\x{200D}]"), "")
        .replace(Regex("[#_`>~]+"), " ")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s+([,.])"), "$1").trim()

    /**
     * Découpe le texte en répliques : une ligne « Nom : texte » change de voix si le nom est un personnage connu ;
     * les autres lignes continuent la voix en cours ([defaut] au début).
     */
    fun scenario(texte: String, defaut: Personnage): List<Replique> {
        val out = mutableListOf<Replique>()
        var courant = defaut
        val tampon = StringBuilder()
        fun pousser() {
            val t = nettoyer(tampon.toString())
            if (t.isNotBlank()) {
                if (out.isNotEmpty() && out.last().role.id == courant.id) out[out.lastIndex] = out.last().copy(texte = out.last().texte + " " + t)
                else out += Replique(courant, t)
            }
            tampon.clear()
        }
        for (ligne in texte.lines()) {
            val m = ENTETE.find(ligne)
            val r = m?.groupValues?.get(1)?.let { role(it) }
            if (m != null && r != null) {
                pousser(); courant = r
                tampon.append(m.groupValues[2]).append(' ')
            } else tampon.append(ligne).append(' ')
        }
        pousser()
        return out
    }

    // ── Messages enregistrés ────────────────────────────────────────────────────────────────────────────────────────

    data class Message(val id: String, val fichier: File, val titre: String, val texte: String, val roles: List<String>,
                       val dureeMs: Long, val ts: Long, val source: String)

    fun dossier(app: JarvisApp) = File(app.filesDir, "messages_vocaux").apply { mkdirs() }

    fun titre(roles: List<Personnage>): String {
        val noms = roles.distinctBy { it.id }.map { it.nom }
        return when (noms.size) {
            0 -> "Message vocal"
            1 -> "Message " + when {
                noms[0].matches(Regex("^[AEIOUYÉÈaeiouyéè].*")) -> "de l'" + noms[0]
                roles[0].id in setOf("pere_noel", "lutin", "renne", "bonhomme_neige", "magicien", "geant", "dragon", "pirate", "robot", "fantome", "super_heros") -> "du " + noms[0]
                roles[0].groupe != QUOTIDIEN -> "de la " + noms[0]
                else -> "· " + noms[0]
            }
            else -> "Dialogue · " + noms.joinToString(" & ")
        }
    }

    private fun style(r: Personnage) = r.style + " Parle en français, avec une diction claire. Lis les noms propres comme des mots, " +
        "ne prononce jamais la ponctuation ni les symboles, et ne lis aucune indication de jeu."

    /** Voix Android de repli, créée une fois par message. */
    private class Repli(private val app: JarvisApp) {
        private var tts: TextToSpeech? = null

        suspend fun dire(texte: String, r: Personnage): ShortArray {
            val t = tts ?: ouvrir().also { tts = it }
            val fr = runCatching { t.voices }.getOrNull().orEmpty()
                .filter { it.locale.language == "fr" && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            (fr.filter { VoixAndroid.genre(it.name) == r.genre }.maxByOrNull { it.quality } ?: fr.maxByOrNull { it.quality })?.let { runCatching { t.voice = it } }
            t.setPitch(r.hauteur); t.setSpeechRate(r.debit)
            val f = File(app.cacheDir, "tts_${System.nanoTime()}.wav")
            val fini = CompletableDeferred<Boolean>()
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { fini.complete(true) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { fini.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { fini.complete(false) }
            })
            try {
                if (t.synthesizeToFile(texte, Bundle(), f, "mv") != TextToSpeech.SUCCESS) throw IllegalStateException("la voix Android a refusé le texte")
                if (withTimeoutOrNull(90_000) { fini.await() } != true) throw IllegalStateException("la voix Android n'a pas répondu")
                val a = VoixNaturelle.decoder(f.readBytes()) ?: throw IllegalStateException("audio Android illisible")
                return AudioFx.reechantillonner(AudioFx.mono(AudioFx.versShorts(a.pcm), a.canaux), a.frequence, AudioFx.FREQ)
            } finally { f.delete() }
        }

        private suspend fun ouvrir(): TextToSpeech {
            val pret = CompletableDeferred<Boolean>()
            val l = TextToSpeech.OnInitListener { pret.complete(it == TextToSpeech.SUCCESS) }
            val t = withContext(Dispatchers.Main) {
                if (VoixAndroid.googleInstalle(app)) TextToSpeech(app, l, VoixAndroid.MOTEUR_GOOGLE) else TextToSpeech(app, l)
            }
            if (withTimeoutOrNull(8000) { pret.await() } != true) { runCatching { t.shutdown() }; throw IllegalStateException("voix Android indisponible") }
            runCatching { t.language = Locale.FRANCE }
            return t
        }

        fun fermer() { runCatching { tts?.shutdown() }; tts = null }
    }

    /** Chaîne d'effets d'un personnage. Avec la voix Android, la hauteur est déjà réglée : seulement écho / robot. */
    fun appliquer(s: ShortArray, e: Effet, viaAndroid: Boolean): ShortArray {
        var x = s
        if (!viaAndroid) x = AudioFx.vitesse(x, e.vitesse)
        if (e.robot) x = AudioFx.robot(x)
        if (e.reverb > 0) x = AudioFx.reverb(x, e.reverb)
        return x
    }

    /**
     * Crée et enregistre le message. [progres] : étape en cours. Gemini d'abord ; à la première erreur, la voix Android
     * prend le relais pour la suite (la raison est gardée dans [Message.source]).
     */
    suspend fun creer(app: JarvisApp, texte: String, defaut: Personnage, clochettes: Boolean, effets: Boolean = true,
                      progres: (String) -> Unit = {}): Message = withContext(Dispatchers.IO) {
        val reps = scenario(texte, defaut)
        if (reps.isEmpty()) throw IllegalArgumentException("Le message est vide.")
        val parts = mutableListOf<ShortArray>()
        if (clochettes) { parts += AudioFx.clochettes(1500); parts += AudioFx.silence(120) } else parts += AudioFx.silence(150)
        val repli = Repli(app)
        var geminiKo: String? = if (app.store.geminiKeys.none { it.isNotBlank() }) "aucune clé Gemini" else null
        var viaGemini = 0; var viaAndroid = 0
        try {
            reps.forEachIndexed { i, r ->
                progres(if (reps.size > 1) "Réplique ${i + 1}/${reps.size} · ${r.role.libelle}…" else "Voix ${r.role.libelle}…")
                val morceaux = VoixNaturelle.decouper(r.texte, dejaUnMorceau = true, toutDire = true, attendreBloc = false, taille = 450).first
                var android = false
                val sons = morceaux.map { m ->
                    if (geminiKo == null) {
                        try {
                            val a = VoixNaturelle.enregistrement(app, m, r.role.voix, style(r.role))
                            viaGemini++
                            return@map AudioFx.reechantillonner(AudioFx.mono(AudioFx.versShorts(a.pcm), a.canaux), a.frequence, AudioFx.FREQ)
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            geminiKo = e.message ?: e.javaClass.simpleName
                        }
                    }
                    android = true; viaAndroid++
                    repli.dire(m, r.role)
                }
                var voix = AudioFx.concatener(sons.flatMapIndexed { k, s -> if (k == 0) listOf(s) else listOf(AudioFx.silence(140), s) })
                if (effets) voix = appliquer(voix, effet(r.role), android)
                parts += AudioFx.fondu(voix)
                parts += AudioFx.silence(if (i < reps.lastIndex) 450 else 300)
            }
        } finally { repli.fermer() }
        if (clochettes) parts += AudioFx.clochettes(1200, graine = 11)
        val audio = AudioFx.normaliser(AudioFx.concatener(parts))

        val ts = System.currentTimeMillis()
        val id = "mv_$ts"
        val m4a = File(dossier(app), "$id.m4a")
        val fichier = if (runCatching { versM4a(audio, AudioFx.FREQ, m4a) }.getOrDefault(false) && m4a.length() > 0) m4a
            else File(dossier(app), "$id.wav").apply { m4a.delete(); writeBytes(AudioFx.wav(audio)) }
        val source = when {
            viaAndroid == 0 -> "voix Gemini"
            viaGemini == 0 -> "voix Android" + (geminiKo?.let { " (Gemini : $it)" } ?: "")
            else -> "Gemini puis voix Android (Gemini : $geminiKo)"
        }
        val msg = Message(id, fichier, titre(reps.map { it.role }), texte.trim(), reps.map { it.role.id }.distinct(), AudioFx.dureeMs(audio), ts, source)
        File(dossier(app), "$id.json").writeText(JSONObject().put("titre", msg.titre).put("texte", msg.texte)
            .put("roles", JSONArray(msg.roles)).put("duree", msg.dureeMs).put("ts", ts).put("source", source).put("fichier", fichier.name).toString())
        msg
    }

    /** Court essai de la voix d'un personnage (non enregistré dans la liste). */
    suspend fun essai(app: JarvisApp, r: Personnage, effets: Boolean = true): File = withContext(Dispatchers.IO) {
        val texte = exemple(r)
        var android = false
        val s = try {
            if (app.store.geminiKeys.none { it.isNotBlank() }) throw IllegalStateException("aucune clé Gemini")
            val a = VoixNaturelle.enregistrement(app, texte, r.voix, style(r))
            AudioFx.reechantillonner(AudioFx.mono(AudioFx.versShorts(a.pcm), a.canaux), a.frequence, AudioFx.FREQ)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            android = true
            val repli = Repli(app)
            try { repli.dire(texte, r) } finally { repli.fermer() }
        }
        val audio = AudioFx.normaliser(AudioFx.fondu(if (effets) appliquer(s, effet(r), android) else s))
        File(app.cacheDir, "essai_${r.id}.wav").apply { writeBytes(AudioFx.wav(audio)) }
    }

    fun liste(app: JarvisApp): List<Message> = dossier(app).listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { j ->
        runCatching {
            val o = JSONObject(j.readText())
            val f = File(dossier(app), o.getString("fichier"))
            if (!f.exists()) return@runCatching null
            val roles = o.optJSONArray("roles")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            Message(j.nameWithoutExtension, f, o.optString("titre"), o.optString("texte"), roles, o.optLong("duree"), o.optLong("ts"), o.optString("source"))
        }.getOrNull()
    }.sortedByDescending { it.ts }

    fun supprimer(app: JarvisApp, m: Message) {
        m.fichier.delete(); File(dossier(app), "${m.id}.json").delete()
    }

    private fun nomFichier(m: Message) = m.titre.replace(Regex("[^\\p{L}0-9 &'-]"), "").replace(Regex("\\s+"), " ").trim().ifBlank { "Message vocal" } +
        "." + m.fichier.extension

    private fun mime(f: File) = if (f.extension == "m4a") "audio/mp4" else "audio/x-wav"

    /** Partage (WhatsApp, SMS, mail…) : copie au nom du message, envoyée par la feuille de partage d'Android. */
    fun partager(app: JarvisApp, m: Message) {
        val d = File(app.cacheDir, "share").apply { mkdirs() }
        val copie = File(d, nomFichier(m)).apply { writeBytes(m.fichier.readBytes()) }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", copie)
        val i = Intent(Intent.ACTION_SEND).setType(mime(copie)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(i, "Envoyer le message vocal").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Copie dans Musique › JARVIS Messages (visible par les applis de musique et de fichiers). */
    fun versMusique(app: JarvisApp, m: Message): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val v = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, nomFichier(m))
            put(MediaStore.Audio.Media.MIME_TYPE, mime(m.fichier))
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/JARVIS Messages")
            put(MediaStore.Audio.Media.TITLE, m.titre)
        }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        return runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(m.fichier.readBytes()) } != null }.getOrDefault(false)
    }

    /** Ouvre l'écran Messages vocaux (sur ce message s'il est donné). */
    fun ouvrir(app: JarvisApp, id: String = "") {
        runCatching {
            app.startActivity(Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "messages_vocaux" + if (id.isNotBlank()) ":$id" else "")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    // ── Rédaction par l'IA ─────────────────────────────────────────────────────────────────────────────────────────

    fun consigneRedaction(roles: List<Personnage>): String = buildString {
        append("Tu écris le texte d'un message vocal qui sera lu par une synthèse vocale. Uniquement les mots à prononcer, en français : ")
        append("pas de titre, pas d'émoji, pas de guillemets, pas de didascalie ni d'indication de jeu entre parenthèses ou crochets. ")
        append("Ton chaleureux et vivant ; si le message s'adresse à un enfant, phrases simples, pleines de magie et de bienveillance. ")
        if (roles.size <= 1) {
            val r = roles.firstOrNull()
            if (r != null) append("Tu écris dans la peau de ce personnage : ${r.nom}. ${r.style} ")
            append("Longueur : 60 à 140 mots, sauf si on te demande autre chose.")
        } else {
            append("Écris un court dialogue vivant entre ces personnages : ${roles.joinToString(", ") { it.nom }}. ")
            append("Une réplique par ligne, au format exact « Nom : texte », avec exactement ces noms : ${roles.joinToString(" / ") { it.nom }}. ")
            append("4 à 10 répliques, 150 mots au plus en tout.")
        }
    }

    /** Nettoie la réponse de l'IA (guillemets, gras, titre). */
    fun nettoyerRedaction(t: String): String = t.trim().removePrefix("```").removeSuffix("```").lines()
        .map { it.replace("**", "").replace(Regex("[«»“”\"]"), "").replace(Regex("[ \\t]+"), " ").trim() }
        .filterNot { it.startsWith("#") }
        .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()

    suspend fun rediger(app: JarvisApp, idee: String, roles: List<Personnage>): String {
        val sys = consigneRedaction(roles)
        val brut = runCatching { app.llm.gemini.generate(sys, idee) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { app.llm.groq.plain(sys, idee) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Aucune IA disponible pour écrire le message (Gemini et Groq indisponibles).")
        return nettoyerRedaction(brut)
    }

    // ── Encodage AAC (.m4a : lu directement par WhatsApp, Messages…) ───────────────────────────────────────────────

    fun versM4a(s: ShortArray, freq: Int, sortie: File): Boolean {
        val pcm = AudioFx.versOctets(s)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, freq, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(sortie.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var piste = -1; var demarre = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var pos = 0; var finEntree = false; var finSortie = false
            val limite = System.currentTimeMillis() + 120_000
            while (!finSortie && System.currentTimeMillis() < limite) {
                if (!finEntree) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        val n = minOf(buf.remaining(), pcm.size - pos).let { it - it % 2 }
                        val pts = (pos / 2).toLong() * 1_000_000L / freq
                        if (n <= 0) { codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); finEntree = true }
                        else { buf.put(pcm, pos, n); codec.queueInputBuffer(i, 0, n, pts, 0); pos += n }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    piste = muxer.addTrack(codec.outputFormat); muxer.start(); demarre = true
                } else if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && demarre) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(piste, out, info)
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) finSortie = true
                }
            }
            return finSortie && demarre
        } finally {
            runCatching { codec.stop() }; runCatching { codec.release() }
            if (demarre) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
