package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Personnages RÉCURRENTS des vidéos : enregistrés une fois (nom, description, image de référence), ils gardent la même
 * apparence dans tous les montages — chaque plan qui les nomme reçoit leur image comme modèle (FLUX.2).
 *
 * Trois origines possibles :
 *  - ORIGINAL : personnage inventé, dessiné par JARVIS d'après ta description (fiche de référence) ;
 *  - IMAGE : ton personnage, depuis une image que tu fournis (bouton +, galerie, coffre) ;
 *  - DOMAINE PUBLIC : personnage historique ou œuvre ancienne (Vercingétorix, Jeanne d'Arc, Marianne…) cherché sur
 *    Wikimedia Commons, UNIQUEMENT parmi les images du domaine public ou CC0 (réutilisables sans autorisation).
 *
 * Les personnages protégés par le droit d'auteur (héros de BD, de dessins animés, de films, de jeux : Astérix, Tintin,
 * Mickey, Pikachu…) ne sont jamais recherchés ni reproduits : TikTok retire ces vidéos et peut sanctionner le compte.
 */
object Personnages {
    data class Personnage(
        val nom: String, val description: String, val origine: String, val credit: String = "",
        val image: String, val reference: String, val creeLe: Long = System.currentTimeMillis()
    ) {
        fun json() = JSONObject().put("nom", nom).put("description", description).put("origine", origine).put("credit", credit)
            .put("image", image).put("reference", reference).put("creeLe", creeLe)
    }

    data class Candidat(val titre: String, val url: String, val page: String, val licence: String, val auteur: String, val largeur: Int, val hauteur: Int)

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    /** Wikimedia demande un User-Agent qui identifie l'appli (aucune donnée personnelle). */
    private const val AGENT = "JARVIS-Android/1.0 (assistant personnel ; recherche d'images du domaine public)"

    // ------------------------------------------------------------------ personnages protégés

    /**
     * Personnages et univers protégés par le droit d'auteur les plus courants (liste indicative : l'IA a la même consigne
     * pour tous les autres). Les noms communs (Thor, Hercule : mythologie, domaine public) n'y sont pas.
     */
    private val PROTEGES = Regex("(?i)\\b(" + listOf(
        "ast[ée]rix", "ob[ée]lix", "id[ée]fix", "panoramix", "abraracourcix", "assurancetourix", "c[ée]tautomatix", "agecanonix", "ordralfab[ée]tix",
        "dogmatix", "getafix", "vitalstatistix", "tintin", "capitaine haddock", "professeur tournesol", "schtroumpfs?", "smurfs?", "gargamel",
        "lucky luke", "rantanplan", "spirou", "marsupilami", "gaston lagaffe", "titeuf", "boule et bill", "iznogoud", "largo winch",
        "blake et mortimer", "kid paddle", "corto maltese", "thorgal", "mickey", "minnie", "donald duck", "picsou", "reine des neiges",
        "lilo et stitch", "buzz l'[ée]clair", "spider.?man", "iron.?man", "captain america", "hulk", "batman", "superman", "wonder woman", "deadpool",
        "avengers", "pok[ée]mon", "pikachu", "super mario", "sonic the hedgehog", "pac.?man", "son goku", "goku",
        "dragon ball", "naruto", "luffy", "one piece", "sailor moon", "totoro", "doraemon", "hello kitty", "bob l'[ée]ponge", "spongebob",
        "simpsons?", "homer simpson", "shrek", "minions", "garfield", "snoopy", "bugs bunny", "scooby.?doo", "peppa pig", "pat'? ?patrouille",
        "paw patrol", "harry potter", "dark vador", "darth vader", "yoda", "star wars", "james bond", "gollum", "gandalf", "frodon?",
        "barbie", "wall.?e", "kung fu panda", "tom et jerry"
    ).joinToString("|") + ")\\b")

    /** Univers d'Astérix : remplacé par des guerriers gaulois originaux ; les autres par des personnages originaux. */
    private val GAULOIS = Regex("(?i)ast[ée]rix|ob[ée]lix|id[ée]fix|panoramix|abraracourcix|assurancetourix|c[ée]tautomatix|agecanonix|ordralfab[ée]tix|dogmatix|getafix|vitalstatistix")

    /** Personnages protégés remplacés dans un prompt d'image (« Astérix et Obélix » → des guerriers gaulois originaux). */
    fun remplacerProteges(texte: String): String =
        Regex(PROTEGES.pattern + "(\\s*(,|et|and|&)\\s*" + PROTEGES.pattern.removePrefix("(?i)") + ")*", RegexOption.IGNORE_CASE).replace(texte) { m ->
            if (GAULOIS.containsMatchIn(m.value)) "original ancient Gaulish warriors of our own design" else "original characters of our own design"
        }

    /** Noms de personnages protégés cités dans [texte] (sans doublon, dans l'ordre). */
    fun proteges(texte: String): List<String> = PROTEGES.findAll(texte).map { m ->
        m.value.trim().split(' ').joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
    }.distinctBy { it.lowercase() }.toList()

    /** Explication courte quand un personnage protégé est demandé, et ce que JARVIS fait à la place. */
    fun refusProteges(noms: List<String>): String {
        if (noms.isEmpty()) return ""
        val l = if (noms.size == 1) noms[0] else noms.dropLast(1).joinToString(", ") + " et " + noms.last()
        val gaulois = noms.any { GAULOIS.containsMatchIn(it) }
        val plusieurs = noms.size > 1
        return "ℹ️ $l ${if (plusieurs) "sont des personnages protégés" else "est un personnage protégé"} par le droit d'auteur : je ne peux ni " +
            "${if (plusieurs) "les" else "le"} reproduire ni chercher ${if (plusieurs) "leurs" else "ses"} images (TikTok retire ces vidéos et peut sanctionner le compte). " +
            (if (gaulois) "À la place : des guerriers gaulois ORIGINAUX. " else "À la place : des personnages originaux. ") +
            "Pour retrouver les mêmes personnages d'une vidéo à l'autre, crée les tiens (« crée le personnage Brennos, un vieux chef gaulois… ») " +
            "ou utilise un personnage historique du domaine public (« cherche le personnage Vercingétorix »)."
    }

    // ------------------------------------------------------------------ enregistrement

    private fun dossier(app: JarvisApp) = File(app.filesDir, "personnages").apply { mkdirs() }
    private fun fichierListe(app: JarvisApp) = File(app.filesDir, "personnages.json")

    internal fun slug(nom: String) = java.text.Normalizer.normalize(nom.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "personnage" }

    @Synchronized
    fun tous(app: JarvisApp): List<Personnage> = runCatching {
        val a = JSONArray(fichierListe(app).takeIf { it.isFile }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Personnage(o.optString("nom"), o.optString("description"), o.optString("origine"), o.optString("credit"),
                o.optString("image"), o.optString("reference"), o.optLong("creeLe"))
        }.filter { it.nom.isNotBlank() && File(it.reference).isFile }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun ecrire(app: JarvisApp, liste: List<Personnage>) {
        fichierListe(app).writeText(JSONArray().apply { liste.forEach { put(it.json()) } }.toString())
    }

    fun trouver(app: JarvisApp, nom: String): Personnage? = tous(app).firstOrNull { slug(it.nom) == slug(nom) }

    /** Personnages enregistrés que [texte] nomme (mot entier, sans tenir compte des accents ni des majuscules). */
    fun cites(app: JarvisApp, texte: String): List<Personnage> = cites(tous(app), texte)

    internal fun cites(liste: List<Personnage>, texte: String): List<Personnage> {
        val t = " " + slug(texte).replace('-', ' ') + " "
        return liste.filter { p -> slug(p.nom).replace('-', ' ').let { n -> n.isNotBlank() && t.contains(" $n ") } }
    }

    /**
     * Description du personnage glissée dans le prompt d'un plan, à la place de son seul nom (le modèle d'image ne
     * connaît pas « Brennos ») : « Brennos (an old Gaulish chief…) ».
     */
    internal fun decrire(visuel: String, personnages: List<Personnage>): String = personnages.fold(visuel) { v, p ->
        val r = Regex("(?i)\\b${Regex.escape(p.nom)}\\b")
        if (r.containsMatchIn(v)) r.replaceFirst(v, "${p.nom} (${p.description.take(220)})") else "$v, with ${p.nom} (${p.description.take(220)})"
    }

    private fun garder(app: JarvisApp, nom: String, description: String, origine: String, credit: String, image: File): Personnage {
        val d = File(dossier(app), slug(nom)).apply { mkdirs() }
        val ext = image.extension.lowercase().ifBlank { "jpg" }
        val original = File(d, "image.$ext").also { if (it.absolutePath != image.absolutePath) image.copyTo(it, overwrite = true) }
        val ref = ImageGen.reference(original, File(d, "reference.jpg")) ?: throw IllegalStateException("image illisible")
        // Copie dans les images de JARVIS : affichable dans le chat (jarvis-img:) et ouverte dans la visionneuse.
        runCatching { original.copyTo(File(File(app.filesDir, "images").apply { mkdirs() }, apercu(nom, ext)), overwrite = true) }
        val p = Personnage(nom.trim(), description.trim(), origine, credit, original.absolutePath, ref.absolutePath)
        ecrire(app, tous(app).filterNot { slug(it.nom) == slug(nom) } + p)
        // Fiche dans le coffre (image intégrée : elle est reliée dans le graphe).
        runCatching {
            val nomImage = "${nom.trim()}.${ext}"
            app.obsidian.saveBytes("JARVIS/Personnages", nomImage, original.readBytes(), if (ext == "png") "image/png" else "image/jpeg")
            app.obsidian.saveIn("JARVIS/Personnages", nom.trim(), "---\njarvis: personnage\norigine: $origine\n---\n# ${nom.trim()}\n\n![[JARVIS/Personnages/$nomImage]]\n\n" +
                "$description\n" + (if (credit.isNotBlank()) "\n_Source : ${credit}_\n" else "") +
                "\nRéutilisé automatiquement dans les vidéos quand son nom est cité.\n")
        }
        return p
    }

    /** Nom de l'aperçu dans les images de JARVIS (« perso_brennos.png »). */
    fun apercu(nom: String, ext: String) = "perso_${slug(nom)}.$ext"
    fun apercu(p: Personnage) = apercu(p.nom, File(p.image).extension.lowercase().ifBlank { "jpg" })

    fun supprimer(app: JarvisApp, nom: String): Boolean {
        val p = trouver(app, nom) ?: return false
        ecrire(app, tous(app).filterNot { slug(it.nom) == slug(nom) })
        File(dossier(app), slug(p.nom)).deleteRecursively()
        File(File(app.filesDir, "images"), apercu(p)).delete()
        return true
    }

    // ------------------------------------------------------------------ création

    /** Personnage ORIGINAL dessiné d'après [description] : fiche de référence en pied, fond neutre. */
    suspend fun creerOriginal(app: JarvisApp, nom: String, description: String): Personnage {
        if (description.isBlank()) throw IllegalStateException("décris le personnage (âge, allure, vêtements, couleurs, caractère)")
        val prompt = "Character reference sheet of ONE original character named ${nom.trim()}: ${description.trim()}. Full body, front view, standing, " +
            "plain light neutral background, consistent design, detailed costume, original design that does not resemble any existing cartoon, comic, " +
            "film or video game character, no text, no letters, no logo, no watermark"
        val (f, _) = ImageGen.generate(app, prompt, "flux-2-klein-4b", 768, 1024, huggingFace = true)
        return garder(app, nom, description, "original", "", f)
    }

    /** Ton personnage, depuis une image (pièce jointe, image JARVIS, chemin du téléphone ou du coffre). */
    suspend fun depuisImage(app: JarvisApp, nom: String, description: String, fichier: String): Personnage = withContext(Dispatchers.IO) {
        val f = trouverImage(app, fichier) ?: throw IllegalStateException("image « $fichier » introuvable (joins-la avec le bouton + ou donne son chemin)")
        garder(app, nom, description.ifBlank { nom }, "image", "", f)
    }

    private fun trouverImage(app: JarvisApp, s: String): File? {
        val n = s.trim().trim('"', '\'', '«', '»', ' ')
        if (n.isBlank()) return null
        val images = setOf("png", "jpg", "jpeg", "webp", "bmp", "heic", "heif")
        DocumentStore.find(app, n)?.takeIf { it.extension.lowercase() in images }?.let { return it }
        ImageGen.fichier(app, n)?.let { return it }
        runCatching { File(Stockage.racine(), n.trim('/')).takeIf { it.isFile && it.canRead() && it.extension.lowercase() in images } }.getOrNull()?.let { return it }
        return runCatching { app.obsidian.resoudre(n)?.let { app.obsidian.versFichierLocal(it, File(app.cacheDir, "personnages_import")) } }.getOrNull()
            ?.takeIf { it.extension.lowercase() in images }
    }

    // ------------------------------------------------------------------ domaine public (Wikimedia Commons)

    /** Licence réutilisable sans autorisation ni attribution obligatoire : domaine public ou CC0. */
    internal fun licenceLibre(licence: String, courte: String): Boolean {
        val l = licence.trim().lowercase(); val c = courte.trim().lowercase()
        return l == "pd" || l.startsWith("pd-") || l == "cc0" || l.startsWith("cc0") ||
            Regex("public domain|domaine public|^cc0|^pd\\b|pdm").containsMatchIn(c)
    }

    internal fun sansHtml(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'").replace(Regex("\\s+"), " ").trim()

    /** Lecture de la réponse de l'API Commons (formatversion=2) : seulement les images libres, dans l'ordre de pertinence. */
    internal fun candidats(reponse: JSONObject): List<Candidat> {
        val pages = reponse.optJSONObject("query")?.optJSONArray("pages") ?: return emptyList()
        return (0 until pages.length()).mapNotNull { pages.optJSONObject(it) }.sortedBy { it.optInt("index", 999) }.mapNotNull { p ->
            val ii = p.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val m = ii.optJSONObject("extmetadata") ?: JSONObject()
            val licence = m.optJSONObject("License")?.optString("value").orEmpty()
            val courte = m.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            if (!licenceLibre(licence, courte)) return@mapNotNull null
            if (ii.optString("mime") !in setOf("image/jpeg", "image/png", "image/webp")) return@mapNotNull null
            if (ii.optInt("width") < 300 || ii.optInt("height") < 300) return@mapNotNull null
            Candidat(p.optString("title").removePrefix("File:").substringBeforeLast('.'), ii.optString("thumburl").ifBlank { ii.optString("url") },
                ii.optString("descriptionurl"), courte.ifBlank { licence }, sansHtml(m.optJSONObject("Artist")?.optString("value").orEmpty()).take(80),
                ii.optInt("width"), ii.optInt("height"))
        }
    }

    /** Images du domaine public de [nom] sur Wikimedia Commons (tableaux, gravures, statues anciennes…). */
    suspend fun chercherDomainePublic(nom: String, max: Int = 6): List<Candidat> = withContext(Dispatchers.IO) {
        if (proteges(nom).isNotEmpty()) throw IllegalStateException(refusProteges(proteges(nom)))
        val url = "https://commons.wikimedia.org/w/api.php?action=query&format=json&formatversion=2&generator=search" +
            "&gsrsearch=" + java.net.URLEncoder.encode("filetype:bitmap $nom", "UTF-8") + "&gsrnamespace=6&gsrlimit=30&prop=imageinfo" +
            "&iiprop=url%7Cmime%7Csize%7Cextmetadata&iiurlwidth=1024&iiextmetadatafilter=License%7CLicenseShortName%7CArtist"
        val texte = http.newCall(Request.Builder().url(url).header("User-Agent", AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Wikimedia Commons a répondu ${r.code}")
            r.body?.string().orEmpty()
        }
        candidats(JSONObject(texte)).take(max)
    }

    /** Personnage du domaine public : la [choix]-ième image trouvée (1 = la plus pertinente). */
    suspend fun depuisDomainePublic(app: JarvisApp, nom: String, description: String, choix: Int = 1): Pair<Personnage, Candidat> = withContext(Dispatchers.IO) {
        val c = chercherDomainePublic(nom, 8).getOrNull((choix - 1).coerceAtLeast(0))
            ?: throw IllegalStateException("aucune image du domaine public trouvée pour « $nom » sur Wikimedia Commons")
        val tmp = File(app.cacheDir, "personnage_${slug(nom)}.jpg")
        http.newCall(Request.Builder().url(c.url).header("User-Agent", AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("téléchargement refusé (${r.code})")
            tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
        }
        val credit = "« ${c.titre} »" + (if (c.auteur.isNotBlank()) " — ${c.auteur}" else "") + " · ${c.licence} · Wikimedia Commons (${c.page})"
        val p = garder(app, nom, description.ifBlank { nom }, "domaine public", credit, tmp)
        tmp.delete()
        p to c
    }

    // ------------------------------------------------------------------ montage

    /** Consigne du scénario : personnages enregistrés que la demande nomme (ils doivent apparaître, sous ce nom exact). */
    fun consigneScenario(liste: List<Personnage>): String = if (liste.isEmpty()) "" else
        "Personnages récurrents à faire apparaître (écris leur NOM EXACT dans « visuel » de chaque plan où ils sont visibles ; leur apparence est fixée par une image de référence) :\n" +
            liste.joinToString("\n") { "- ${it.nom} : ${it.description.take(200)}" } + "\n"

    fun ligne(p: Personnage) = "- **${p.nom}** (${p.origine}) — ${p.description.take(120)}" + (if (p.credit.isNotBlank()) "\n  _${p.credit.take(160)}_" else "")
}
