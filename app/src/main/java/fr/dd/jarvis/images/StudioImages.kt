package fr.dd.jarvis.images

import android.content.ContentValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.device.ContactsRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Studio d'images (onglet « Images », séparé du reste de JARVIS : ni le chat ni les outils n'y ont accès).
 *
 * Étapes : garde-fous locaux → réécriture du prompt → choix du modèle → génération Pollinations → galerie privée.
 * Réécriture : Gemini pour les demandes non explicites ; pour une demande adulte, un modèle de texte Pollinations
 * (Gemini l'interdit dans ses règles d'utilisation et risquerait de bloquer la clé), puis l'enrichissement local
 * si le modèle refuse ou échoue. Les images restent dans le stockage privé de l'application tant que tu ne les
 * enregistres pas toi-même dans la galerie.
 */
object StudioImages {

    data class Options(
        val adulte: Boolean,
        val modele: String = "auto",
        val format: PromptImage.Format? = null,
        val reecriture: String = "auto",
        val nombre: Int = 1,
        /** Prompt final saisi à la main (pas de réécriture). */
        val promptManuel: String = "",
        val negatifManuel: String = "",
        val seed: Long? = null
    )

    data class Resultat(
        val images: List<Image>, val prompt: PromptImage.Prompt, val modele: String, val raison: String,
        val explicite: Boolean, val etapes: List<String>, val erreurs: List<String>
    )

    data class Image(
        val fichier: File, val demande: String, val prompt: String, val negatif: String, val modele: String,
        val seed: Long, val largeur: Int, val hauteur: Int, val adulte: Boolean, val ts: Long
    )

    class Refus(message: String) : Exception(message)

    fun dossier(app: JarvisApp): File = File(app.filesDir, "studio_images").apply { mkdirs() }

    // ── Contacts (personnes réelles protégées) ─────────────────────────────────────────────────────────────────────
    @Volatile private var contacts: Pair<Long, List<String>> = 0L to emptyList()

    private fun nomsContacts(app: JarvisApp): List<String> {
        val (ts, noms) = contacts
        if (System.currentTimeMillis() - ts < 10 * 60_000L && noms.isNotEmpty()) return noms
        val l = runCatching { ContactsRepo(app).search("", 5000).map { it.name }.filter(String::isNotBlank) }.getOrDefault(emptyList())
        contacts = System.currentTimeMillis() to l
        return l
    }

    // ── Modèles disponibles ───────────────────────────────────────────────────────────────────────────────────────
    fun modelesEnCache(app: JarvisApp): List<Pollinations.ModeleImage> =
        Pollinations.parserModeles(app.store.imagesModelesCache.value).ifEmpty { Pollinations.MODELES_DEFAUT }

    suspend fun rafraichirModeles(app: JarvisApp): List<Pollinations.ModeleImage> {
        val l = Pollinations.modelesImage(app.store.pollinationsCle.value.trim())
        if (l.isNotEmpty()) app.store.imagesModelesCache.set(org.json.JSONArray().apply {
            l.forEach { m -> put(JSONObject().put("name", m.nom).put("description", m.description).put("paid_only", m.payant)
                .put("aliases", org.json.JSONArray(m.aliases))) }
        }.toString())
        return l.ifEmpty { Pollinations.MODELES_DEFAUT }
    }

    // ── Accès : clé, ou mode gratuit sans clé ─────────────────────────────────────────────────────────────────────
    /**
     * Pollen épuisé jusqu'à cette heure : après un 402 avec la clé, on passe en mode gratuit (sans clé) pendant 1 h
     * au lieu de refaire une demande payante vouée à l'échec à chaque image.
     */
    var epuiseJusqua by androidx.compose.runtime.mutableLongStateOf(0L)

    fun pollenEpuise(): Boolean = System.currentTimeMillis() < epuiseJusqua

    /** Clé utilisée maintenant : vide en mode « gratuit », sans clé enregistrée ou Pollen épuisé. */
    fun cleEffective(app: JarvisApp): String =
        if (app.store.imagesAcces.value == "gratuit" || pollenEpuise()) "" else app.store.pollinationsCle.value.trim()

    const val BASCULE = "💸 Solde Pollen épuisé : bascule sur le mode gratuit sans clé (un seul modèle, ~768 × 768, une image toutes les 45 s). " +
        "Pour retrouver les autres modèles : Quêtes ou achat de Pollen sur enter.pollinations.ai, et un budget sur la clé."

    private fun gratuits(app: JarvisApp, avecCle: Boolean): List<String> =
        if (!avecCle) listOf("flux", "turbo")
        else modelesEnCache(app).filter { !it.payant }.flatMap { listOf(it.nom) + it.aliases }

    // ── Réécriture ────────────────────────────────────────────────────────────────────────────────────────────────
    private suspend fun parGemini(app: JarvisApp, demande: String): PromptImage.Reponse {
        if (app.llm.etatGemini().first == 0) return PromptImage.Reponse.Illisible
        val brut = app.llm.gemini.generate(PromptImage.systeme(false), demande)
        return PromptImage.lireReponse(brut, demande, false, "Gemini")
    }

    private suspend fun parPollinations(app: JarvisApp, demande: String, explicite: Boolean): PromptImage.Reponse {
        val modele = app.store.imagesModeleTexte.value.ifBlank { "mistral" }
        val cle = cleEffective(app)
        val brut = try {
            Pollinations.texte(cle, modele, PromptImage.systeme(explicite), demande)
        } catch (e: fr.dd.jarvis.ai.ApiException) {
            if (e.code != 402 || cle.isBlank()) throw e
            epuiseJusqua = System.currentTimeMillis() + 3_600_000L
            return PromptImage.lireReponse(Pollinations.texte("", modele, PromptImage.systeme(explicite), demande), demande, explicite, "Pollinations gratuit")
        }
        return PromptImage.lireReponse(brut, demande, explicite, if (cle.isBlank()) "Pollinations gratuit" else "Pollinations · $modele")
    }

    private suspend fun reecrire(app: JarvisApp, demande: String, explicite: Boolean, mode: String, etapes: MutableList<String>): PromptImage.Prompt {
        val ordre: List<String> = when (mode) {
            "aucune" -> return PromptImage.Prompt(demande.trim(), "", PromptImage.style(demande, explicite), PromptImage.format(demande), "aucune")
            "locale" -> listOf("locale")
            "gemini" -> if (explicite) listOf("pollinations", "locale") else listOf("gemini", "pollinations", "locale")
            "pollinations" -> listOf("pollinations", "locale")
            else -> if (explicite) listOf("pollinations", "locale") else listOf("gemini", "pollinations", "locale")
        }
        if (explicite && mode == "gemini") etapes += "ℹ️ Gemini n'est pas utilisé pour le contenu adulte (interdit par les règles de Google, la clé risquerait d'être bloquée) : réécriture par Pollinations."
        for (voie in ordre) {
            if (voie == "locale") {
                etapes += "✍️ Prompt enrichi localement (sans IA)"
                return PromptImage.enrichirLocal(demande, explicite)
            }
            val r = try {
                if (voie == "gemini") parGemini(app, demande) else parPollinations(app, demande, explicite)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                etapes += "⚠️ Réécriture ${if (voie == "gemini") "Gemini" else "Pollinations"} impossible : ${e.message.orEmpty().take(120)}"
                continue
            }
            when (r) {
                is PromptImage.Reponse.Reecrit -> {
                    // Le prompt réécrit repasse les garde-fous : une IA qui ajoute un élément interdit est écartée.
                    val refus = GardeImages.verifier(r.prompt.positif, explicite, nomsContacts(app))
                    if (refus == null) { etapes += "✍️ Prompt réécrit par ${r.prompt.source}"; return r.prompt }
                    etapes += "⚠️ Réécriture ${r.prompt.source} écartée (${refus.motif.name.lowercase()})"
                }
                is PromptImage.Reponse.Refuse -> etapes += "⚠️ ${if (voie == "gemini") "Gemini" else "Pollinations"} a refusé de réécrire" + if (voie == "gemini") "" else " — essaie un autre modèle de texte"
                PromptImage.Reponse.Illisible -> if (voie != "gemini" || app.llm.etatGemini().first > 0) etapes += "⚠️ Réponse de réécriture illisible (${voie})"
            }
        }
        return PromptImage.enrichirLocal(demande, explicite)
    }

    // ── Génération ────────────────────────────────────────────────────────────────────────────────────────────────
    suspend fun generer(app: JarvisApp, demande: String, o: Options, progres: (String) -> Unit = {}): Resultat = withContext(Dispatchers.IO) {
        val etapes = mutableListOf<String>()
        val texteControle = demande + " " + o.promptManuel
        val explicite = o.adulte || GardeImages.estExplicite(texteControle)
        if (explicite && !app.store.imagesMajeur.value)
            throw Refus("🔞 Contenu adulte : confirme d'abord que tu es majeur (en haut de l'onglet Images).")
        progres("Vérification de la demande…")
        GardeImages.verifier(texteControle, explicite, nomsContacts(app))?.let { throw Refus(it.message) }
        if (explicite) etapes += "🔞 Demande adulte : personnages adultes fictifs uniquement"

        val prompt0 = if (o.promptManuel.isNotBlank()) {
            etapes += "✍️ Prompt final saisi à la main"
            PromptImage.Prompt(o.promptManuel.trim(), o.negatifManuel.trim(), PromptImage.style(demande.ifBlank { o.promptManuel }, explicite),
                PromptImage.format(demande), "manuel")
        } else {
            progres("Réécriture du prompt…")
            reecrire(app, demande, explicite, o.reecriture, etapes)
        }
        val prompt = PromptImage.finaliser(prompt0, explicite)

        var cle = cleEffective(app)
        if (cle.isBlank() && app.store.pollinationsCle.value.isNotBlank() && pollenEpuise()) etapes += BASCULE
        val choixAuto = PromptImage.choisirModele(prompt.style, explicite, PromptImage.rapide(demande), gratuits(app, true))
        val manuel = o.modele.trim().takeIf { it.isNotBlank() && it != "auto" && it != "gratuit" && !it.startsWith("modèle") }
        val choix = if (manuel != null) {
            if (explicite && PromptImage.modere(manuel)) etapes += "⚠️ $manuel filtre la nudité : l'image risque d'être refusée ou censurée."
            PromptImage.Choix(manuel, "choisi à la main")
        } else choixAuto
        val format = o.format ?: prompt.format ?: PromptImage.format(demande)
            ?: if (prompt.style == PromptImage.Style.PHOTO || explicite) PromptImage.Format.PORTRAIT else PromptImage.Format.CARRE
        fun libelleModele() = if (cle.isBlank()) "modèle gratuit (sans clé)" else choix.modele
        etapes += "🎨 " + (if (cle.isBlank()) "Mode gratuit sans clé · un seul modèle" else "Modèle ${choix.modele} (${choix.raison})") + " · ${format.libelle}"

        val n = if (cle.isBlank()) o.nombre.coerceIn(1, 2) else o.nombre.coerceIn(1, 4)
        val base = o.seed ?: Random.nextLong(1, 2_000_000_000L)
        val images = mutableListOf<Image>(); val erreurs = mutableListOf<String>()
        var i = 0
        while (i < n) {
            progres(if (n > 1) "Génération ${i + 1}/$n · ${libelleModele()}…" else "Génération · ${libelleModele()}…")
            val seed = base + i
            val modele = if (cle.isBlank()) "flux" else choix.modele
            val url = Pollinations.urlImage(prompt.positif, prompt.negatif, modele, format.largeur, format.hauteur, seed, cle.isNotBlank(), filtre = !explicite)
            try {
                val octets = Pollinations.image(cle, url) { s -> progres("Mode gratuit : attente $s s (une image toutes les 45 s)…") }
                val (w, h) = Pollinations.taille(format.largeur, format.hauteur, cle.isNotBlank())
                images += enregistrer(app, octets, Image(File(""), demande.ifBlank { o.promptManuel }, prompt.positif, prompt.negatif,
                    if (cle.isBlank()) "gratuit" else modele, seed, w, h, explicite, System.currentTimeMillis()))
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val code = (e as? fr.dd.jarvis.ai.ApiException)?.code
                if (code == 402 && cle.isNotBlank()) {
                    // Plus de Pollen sur le compte : même image refaite en mode gratuit, et les suivantes aussi.
                    epuiseJusqua = System.currentTimeMillis() + 3_600_000L
                    cle = ""
                    etapes += BASCULE
                    continue
                }
                erreurs += e.message.orEmpty().ifBlank { e.javaClass.simpleName }
                if (images.isEmpty() && i == 0 && n > 1 && code in setOf(401, 402)) break
            }
            i++
            if (cle.isBlank() && i >= 2) break
        }
        Resultat(images, prompt, libelleModele(), choix.raison, explicite, etapes, erreurs)
    }

    // ── Galerie privée ────────────────────────────────────────────────────────────────────────────────────────────
    private fun enregistrer(app: JarvisApp, octets: ByteArray, im: Image): Image {
        val nom = "img_${im.ts}_${im.seed}"
        val f = File(dossier(app), "$nom.${Pollinations.extension(octets)}")
        f.writeBytes(octets)
        File(dossier(app), "$nom.json").writeText(JSONObject().put("demande", im.demande).put("prompt", im.prompt)
            .put("negatif", im.negatif).put("modele", im.modele).put("seed", im.seed).put("largeur", im.largeur)
            .put("hauteur", im.hauteur).put("adulte", im.adulte).put("ts", im.ts).put("fichier", f.name).toString())
        return im.copy(fichier = f)
    }

    fun galerie(app: JarvisApp): List<Image> = dossier(app).listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { m ->
        runCatching {
            val j = JSONObject(m.readText())
            val f = File(dossier(app), j.getString("fichier"))
            if (!f.exists()) null else Image(f, j.optString("demande"), j.optString("prompt"), j.optString("negatif"), j.optString("modele"),
                j.optLong("seed"), j.optInt("largeur"), j.optInt("hauteur"), j.optBoolean("adulte"), j.optLong("ts"))
        }.getOrNull()
    }.sortedByDescending { it.ts }

    fun supprimer(im: Image) {
        im.fichier.delete()
        File(im.fichier.parentFile, im.fichier.nameWithoutExtension + ".json").delete()
    }

    fun toutSupprimer(app: JarvisApp) { dossier(app).listFiles()?.forEach { it.delete() } }

    /** Copie dans la galerie du téléphone (Images/JARVIS Studio) : seulement à ta demande. */
    fun versGalerie(app: JarvisApp, im: Image): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val type = when (im.fichier.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, im.fichier.name)
            put(MediaStore.Images.Media.MIME_TYPE, type)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/JARVIS Studio")
        }
        val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return false
        return runCatching { app.contentResolver.openOutputStream(uri)?.use { it.write(im.fichier.readBytes()) } != null }.getOrDefault(false)
    }

    fun partager(app: JarvisApp, im: Image) {
        val d = File(app.cacheDir, "share").apply { mkdirs() }
        val copie = File(d, im.fichier.name).apply { writeBytes(im.fichier.readBytes()) }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".files", copie)
        val i = Intent(Intent.ACTION_SEND).setType(if (copie.extension == "png") "image/png" else "image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        app.startActivity(Intent.createChooser(i, "Partager l'image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
