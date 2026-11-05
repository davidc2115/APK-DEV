package fr.dd.jarvis.device

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import fr.dd.jarvis.data.Store
import java.io.File

/**
 * Écrit les fichiers dans le dossier Obsidian choisi (sélecteur Android, sous-dossiers créés au besoin),
 * sinon dans le dossier propre à l'application (Android/data/fr.dd.jarvis/files/<dossier>).
 */
class ObsidianWriter(private val ctx: Context, private val store: Store) {

    private fun safe(name: String, def: String) =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { def }

    private fun subDir(root: DocumentFile, path: String): DocumentFile {
        var cur = root
        for (part in path.split('/').filter { it.isNotBlank() }) {
            cur = cur.trouverLoose(part)?.takeIf { it.isDirectory } ?: cur.createDirectory(part) ?: return cur
        }
        return cur
    }

    /**
     * Équivalent de DocumentFile.findFile(), mais insensible aux accents/majuscules et aux formes de
     * normalisation Unicode (NFC/NFD) : certains fournisseurs de stockage Android renvoient les noms de
     * fichiers accentués dans une forme différente de celle demandée, ce qui fait échouer un findFile()
     * exact — et donc, silencieusement, une suppression ou une recherche de dossier/fichier existant.
     */
    private fun DocumentFile.trouverLoose(nom: String): DocumentFile? {
        findFile(nom)?.let { return it }
        val cible = normaliseNom(nom)
        return listFiles().firstOrNull { f -> f.name?.let { normaliseNom(it) == cible } == true }
    }

    /** [folder] peut contenir des sous-dossiers : « Société/Devis ». Retourne l'emplacement lisible. */
    fun saveBytes(folder: String, fileName: String, bytes: ByteArray, mime: String): String {
        val name = safe(fileName, "Fichier")
        val uriStr = store.obsidianUri.value
        if (uriStr.isNotBlank()) {
            try {
                val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                if (tree != null && tree.canWrite()) {
                    val dir = subDir(tree, folder)
                    val target = dir.trouverLoose(name) ?: dir.createFile(mime, name)
                    if (target != null) {
                        ctx.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) }
                        return "coffre Obsidian › ${folder.replace("/", " › ")} › $name"
                    }
                }
            } catch (_: Exception) {
                // repli sur le dossier de l'application
            }
        }
        val dir = File(ctx.getExternalFilesDir(null), folder).apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return "${dir.absolutePath}/$name (choisis ton dossier Obsidian dans les Réglages pour y écrire directement)"
    }

    fun saveIn(folder: String, baseName: String, markdown: String): String =
        saveBytes(folder, safe(baseName, "Fiche") + ".md", markdown.toByteArray(), "text/markdown")

    fun save(fileBaseName: String, markdown: String): String = saveIn("Contacts", fileBaseName, markdown)

    private fun normaliseNom(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "") // retire les accents
            .lowercase().trim().replace(Regex("\\s+"), " ")

    /**
     * Cherche une fiche déjà existante pour [nom] n'importe où sous [racine] (ex. « Contacts »),
     * insensible aux accents/majuscules/espaces, afin d'éviter de créer un doublon (« Jean Dupont »
     * vs « jean  dupont » vs « Jean DUPONT »…). Retourne le chemin relatif exact (avec .md) si trouvé.
     */
    fun ficheExistante(racine: String, nom: String): String? {
        val cible = normaliseNom(nom)
        if (cible.isBlank()) return null
        return listNotes(racine).firstOrNull { chemin ->
            normaliseNom(chemin.substringAfterLast('/').removeSuffix(".md")) == cible
        }
    }

    /**
     * Supprime un fichier (coffre Obsidian ou dossier de l'application). Ne lève pas d'exception si absent.
     * Retourne vrai si un fichier a réellement été supprimé (jamais vrai en cas d'échec silencieux :
     * c'était le bug — on affichait « supprimé » même quand rien n'avait disparu du coffre).
     */
    fun supprimerFichier(folder: String, fileName: String): Boolean {
        val name = safe(fileName, "Fichier")
        val uriStr = store.obsidianUri.value
        val localFile = File(File(localRoot(), folder), name)
        if (uriStr.isBlank()) {
            if (!localFile.exists()) return false
            return runCatching { localFile.delete() && !localFile.exists() }.getOrDefault(false)
        }
        return try {
            val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr)) ?: return false
            var cur: DocumentFile? = tree
            var dossierTrouve = true
            for (part in folder.split('/').filter { it.isNotBlank() }) {
                cur = cur?.trouverLoose(part)?.takeIf { it.isDirectory }
                if (cur == null) { dossierTrouve = false; break }
            }
            val cible = if (dossierTrouve) cur?.trouverLoose(name) else null
            var supprime = false
            if (cible != null) {
                if (!cible.canWrite()) return false
                cible.delete()
                if (cur?.listFiles()?.any { it.uri == cible.uri } == true) return false
                supprime = true
            }
            // Certaines écritures ont été basculées localement lorsque le fournisseur SAF était indisponible.
            if (localFile.exists()) {
                if (!runCatching { localFile.delete() && !localFile.exists() }.getOrDefault(false)) return false
                supprime = true
            }
            supprime
        } catch (_: Exception) {
            false
        }
    }
    /**
     * Supprime les fichiers .md et .pdf associés à un document (même nom de base que saveIn/saveBytes).
     * Retourne vrai si au moins un des deux a réellement été supprimé.
     */
    fun supprimer(folder: String, baseName: String): Boolean {
        val base = safe(baseName, "Fiche")
        val md = supprimerFichier(folder, "$base.md")
        val pdf = supprimerFichier(folder, "$base.pdf")
        return md || pdf
    }

    /**
     * Supprime les pièces jointes d'une intervention par son identifiant stable, et non par un chemin supposé.
     * Recherche dans le dossier Interventions même si le nom du client/site a été normalisé ou si le sous-dossier
     * a été déplacé. Les rapports sont ciblés par le couple date+ID ; les photos par le préfixe ID-.
     * Retourne les chemins réellement supprimés et ceux qui restent accessibles mais non supprimables.
     */
    fun supprimerPiecesIntervention(date: String, interventionId: String): Pair<List<String>, List<String>> {
        val base = safe("$date $interventionId", "Fiche")
        val tous = toutLister(20_000).filter { !it.second }.map { it.first }
        fun sousInterventions(path: String): Boolean =
            path.split('/').dropLast(1).any { normaliseNom(it) == normaliseNom("Interventions") }
        val cibles = tous.filter { path ->
            val nom = path.substringAfterLast('/')
            val rapport = nom.equals("$base.md", true) || nom.equals("$base.pdf", true)
            val photo = sousInterventions(path) && nom.startsWith("$interventionId-", true)
            rapport || photo
        }.distinct()
        val supprimes = mutableListOf<String>()
        val echecs = mutableListOf<String>()
        for (path in cibles) {
            val dossier = path.substringBeforeLast('/', "")
            val nom = path.substringAfterLast('/')
            val ok = supprimerFichier(dossier, nom)
            val encorePresent = toutLister(20_000).any { !it.second && it.first.equals(path, true) }
            if (ok && !encorePresent) supprimes += path else echecs += path
        }
        return supprimes to echecs
    }

    /** Supprime une note par son chemin relatif (ex. « Contacts/Dupont.md »). Vrai si elle a bien disparu. */
    fun supprimerNote(chemin: String): Boolean {
        val p = withMd(chemin)
        val folder = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        return supprimerFichier(folder, name)
    }

    /** Relit une note du coffre Obsidian (ou du dossier de l'application) ; null si introuvable. */
    fun read(folder: String, fileName: String): String? {
        val name = safe(fileName, "Fichier")
        val uriStr = store.obsidianUri.value
        try {
            if (uriStr.isNotBlank()) {
                val tree = DocumentFile.fromTreeUri(ctx, Uri.parse(uriStr))
                if (tree != null && tree.canRead()) {
                    var cur: DocumentFile? = tree
                    for (part in folder.split('/').filter { it.isNotBlank() }) cur = cur?.findFile(part)?.takeIf { it.isDirectory }
                    val f = cur?.findFile(name)
                    if (f != null) return ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    return null
                }
            }
            val f = File(File(ctx.getExternalFilesDir(null), folder), name)
            return if (f.exists()) f.readText() else null
        } catch (_: Exception) {
            return null
        }
    }

    // ------------------------------------------------------------------ lecture / recherche dans le coffre

    private fun vaultRoot(): DocumentFile? = store.obsidianUri.value.takeIf { it.isNotBlank() }?.let {
        runCatching { DocumentFile.fromTreeUri(ctx, Uri.parse(it)) }.getOrNull()
    }?.takeIf { it.canRead() }

    private fun localRoot(): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    private fun walk(dir: DocumentFile, prefix: String, out: MutableList<Pair<String, DocumentFile>>, limit: Int) {
        for (f in dir.listFiles()) {
            if (out.size >= limit) return
            val n = f.name ?: continue
            if (f.isDirectory) { if (!n.startsWith(".")) walk(f, "$prefix$n/", out, limit) }
            else if (n.endsWith(".md", true)) out += (prefix + n) to f
        }
    }

    /** Même parcours que [walk], mais relève AUSSI les pièces jointes (images, PDF, audio, vidéo…) en un seul
     *  passage : sur un coffre SAF, chaque listFiles() coûte cher, on évite donc de parcourir l'arbre deux fois. */
    private fun walkAvecPieces(
        dir: DocumentFile, prefix: String,
        notes: MutableList<Pair<String, DocumentFile>>, pieces: MutableList<String>,
        limiteNotes: Int, limitePieces: Int, dossiers: MutableList<String>? = null
    ) {
        for (f in dir.listFiles()) {
            if (notes.size >= limiteNotes && pieces.size >= limitePieces) return
            val n = f.name ?: continue
            if (f.isDirectory) {
                if (!n.startsWith(".")) {
                    dossiers?.add(prefix + n)
                    walkAvecPieces(f, "$prefix$n/", notes, pieces, limiteNotes, limitePieces, dossiers)
                }
            }
            else if (n.endsWith(".md", true)) { if (notes.size < limiteNotes) notes += (prefix + n) to f }
            else if (!n.startsWith(".") && pieces.size < limitePieces) pieces += prefix + n
        }
    }

    private fun allNotes(limit: Int = 2500): List<Pair<String, DocumentFile>> {
        val r = vaultRoot() ?: return emptyList()
        return mutableListOf<Pair<String, DocumentFile>>().also { walk(r, "", it, limit) }
    }

    /** Notes du coffre (chemins relatifs), éventuellement dans un dossier. */
    fun listNotes(folder: String = ""): List<String> {
        val f = folder.trim().trim('/')
        val vaultNotes = if (vaultRoot() != null) allNotes().map { it.first } else emptyList()
        val localDir = File(localRoot(), f)
        val localNotes = if (localDir.exists()) localDir.walkTopDown().filter { it.isFile && it.name.endsWith(".md", true) }
            .map { it.relativeTo(localRoot()).path }.toList() else emptyList()
        return (vaultNotes.filter { f.isEmpty() || it.startsWith("$f/") } + localNotes).distinct().sorted()
    }

    /** Recherche texte (nom + contenu). Retourne (chemin, extrait). */
    fun searchNotes(query: String, limit: Int = 12): List<Pair<String, String>> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        fun extrait(txt: String): String {
            val i = txt.lowercase().indexOf(q)
            return if (i < 0) "" else txt.substring(maxOf(0, i - 60), minOf(txt.length, i + 140)).replace(Regex("\\s+"), " ")
        }
        if (vaultRoot() != null) {
            val notes = allNotes()
            for ((path, f) in notes) if (path.lowercase().contains(q) && out.size < limit) out += path to ""
            for ((path, f) in notes.take(600)) {
                if (out.size >= limit) break
                if (out.any { it.first == path }) continue
                val t = runCatching { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().take(80_000).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() ?: continue
                if (t.lowercase().contains(q)) out += path to extrait(t)
            }
        } else {
            localRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".md", true) }.forEach { f ->
                if (out.size >= limit) return@forEach
                val t = runCatching { f.readText() }.getOrDefault("")
                if (f.name.lowercase().contains(q) || t.lowercase().contains(q)) out += f.relativeTo(localRoot()).path to extrait(t)
            }
        }
        return out
    }

    private fun withMd(path: String) = path.trim().trim('/').let { if (it.endsWith(".md", true)) it else "$it.md" }

    fun readNote(path: String): String? {
        val p = withMd(path)
        val v = vaultRoot()
        if (v != null) {
            var cur: DocumentFile? = v
            for (part in p.split('/').filter { it.isNotBlank() }) cur = cur?.findFile(part)
            val f = cur?.takeIf { it.isFile }
            if (f != null) {
                val content = runCatching { ctx.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
                if (content != null) return content
            }
        }
        // saveBytes peut avoir utilisé le stockage local si le coffre SAF était en lecture seule.
        val local = File(localRoot(), p)
        return if (local.isFile) runCatching { local.readText() }.getOrNull() else null
    }

    /** Écrit (ou ajoute à la fin de) une note. Retourne l'emplacement. */
    fun writeNote(path: String, content: String, append: Boolean): String {
        val p = withMd(path)
        val folder = p.substringBeforeLast('/', "")
        val name = p.substringAfterLast('/')
        val body = if (append) (readNote(p)?.trimEnd()?.plus("\n\n") ?: "") + content else content
        return saveBytes(folder, name, body.toByteArray(), "text/markdown")
    }

    // ------------------------------------------------------------------ fichiers et dossiers DU COFFRE (pas du téléphone)

    /** Élément du coffre (note, image, PDF, dossier…) par chemin relatif EXACT (déjà résolu), insensible aux accents/majuscules. */
    private fun trouver(chemin: String): DocumentFile? {
        var cur: DocumentFile? = vaultRoot() ?: return null
        for (part in chemin.trim().trim('/').split('/').filter { it.isNotBlank() }) cur = cur?.trouverLoose(part)
        return cur
    }

    /**
     * Racine « fichier » utilisable directement : le coffre lui-même (accès à tous les fichiers, contrôlé contre la
     * vue Android du coffre) ou, sans coffre choisi, le dossier local. Null : passer par le coffre SAF.
     */
    private fun racineFichier(): File? = fichierCoffre() ?: if (vaultRoot() == null) localRoot() else null

    private fun fichierSur(racine: File, chemin: String): File {
        val r = racine.canonicalFile
        val f = File(r, chemin.trim().trim('/')).canonicalFile
        if (f.path != r.path && !f.path.startsWith(r.path + File.separator)) throw IllegalArgumentException("Chemin hors du coffre.")
        return f
    }

    /** Clé de comparaison tolérante : sans accents, majuscules, espaces doubles, « _ » / « - », ni pluriel en s/x. */
    private fun cleNom(s: String): String = normaliseNom(s.replace('_', ' ').replace('-', ' ')).replace(Regex("\\s+"), " ").let {
        if (it.length > 3 && (it.endsWith("s") || it.endsWith("x"))) it.dropLast(1) else it
    }

    /** Noms des éléments directs d'un dossier du coffre (chemin réel), avec « est un dossier ». Null si introuvable. */
    private fun enfants(dossier: String): List<Pair<String, Boolean>>? {
        racineFichier()?.let { r ->
            val d = fichierSur(r, dossier)
            return d.takeIf { it.isDirectory }?.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.name to it.isDirectory }
        }
        val d = (if (dossier.isBlank()) vaultRoot() else trouver(dossier))?.takeIf { it.isDirectory } ?: return null
        return d.listFiles().mapNotNull { f -> f.name?.takeIf { !it.startsWith(".") }?.let { it to f.isDirectory } }
    }

    private fun correspondance(noms: List<Pair<String, Boolean>>, voulu: String, dossierSeul: Boolean = false): String? {
        val c = noms.filter { !dossierSeul || it.second }
        return c.firstOrNull { it.first == voulu }?.first
            ?: c.firstOrNull { normaliseNom(it.first) == normaliseNom(voulu) }?.first
            ?: c.firstOrNull { cleNom(it.first) == cleNom(voulu) }?.first
            ?: c.firstOrNull { cleNom(it.first.substringBeforeLast('.')) == cleNom(voulu) }?.first
    }

    /**
     * Retrouve un élément du coffre même si le chemin est écrit approximativement (« Jarvis/image » → « JARVIS/Images ») :
     * majuscules, accents, singulier/pluriel, extension omise. Chemin réel, ou null s'il n'existe pas.
     */
    fun resoudre(chemin: String): String? {
        val parts = relatif(chemin).split('/').filter { it.isNotBlank() }
        var courant = ""
        for ((i, part) in parts.withIndex()) {
            val noms = enfants(courant) ?: return null
            val m = correspondance(noms, part, dossierSeul = i < parts.size - 1) ?: return null
            courant = if (courant.isEmpty()) m else "$courant/$m"
        }
        return courant
    }

    /** Chemin du coffre sur le téléphone (ex. « Documents/Obsidian jarvis »), d'après le dossier choisi dans les Réglages. */
    fun cheminPhysiqueCoffre(): String? = idCoffre()?.substringAfter(':', "")?.trim('/')?.takeIf { it.isNotBlank() }

    /**
     * Ramène un chemin donné « comme sur le téléphone » à un chemin DU COFFRE : « Documents/Obsidian jarvis/JARVIS/Images »,
     * « /storage/emulated/0/Documents/Obsidian jarvis/… », « Obsidian jarvis/… », « coffre › JARVIS › Images » → « JARVIS/Images ».
     */
    fun relatif(chemin: String): String {
        var c = chemin.trim().trim('"', '\'', '«', '»').replace('\\', '/').replace("›", "/")
            .split('/').map { it.trim() }.filter { it.isNotBlank() }.joinToString("/")
        c = c.replace(Regex("^(?i)(storage/emulated/0|storage/self/primary|sdcard|stockage interne|stockage partag[ée]|internal storage|m[ée]moire interne)(/|$)"), "")
        cheminPhysiqueCoffre()?.let { phys ->
            val p = phys.split('/').filter { it.isNotBlank() }.map { cleNom(it) }
            val parts = c.split('/').filter { it.isNotBlank() }
            val k = (p.size downTo 1).firstOrNull { n -> parts.size >= n && parts.take(n).map { cleNom(it) } == p.takeLast(n) } ?: 0
            if (k > 0) c = parts.drop(k).joinToString("/")
        }
        c = c.replace(Regex("^(?i)(le |mon )?coffre( obsidian)?(/|$)"), "")
        return c.trim('/')
    }

    /** Vrai si [chemin] (chemin du téléphone) désigne le coffre ou un élément du coffre. */
    fun estDansCoffre(chemin: String): Boolean {
        val phys = cheminPhysiqueCoffre() ?: return false
        val p = phys.split('/').filter { it.isNotBlank() }.map { cleNom(it) }
        val parts = chemin.trim().replace('\\', '/').replace(Regex("^(?i)/?(storage/emulated/0|sdcard)/"), "").split('/').filter { it.isNotBlank() }.map { cleNom(it) }
        return parts.size >= p.size && parts.take(p.size) == p
    }

    /** Mots significatifs d'un nom (sans accents, pluriels, articles) pour les recherches « dont le nom contient… ». */
    private fun motsCles(s: String): List<String> =
        normaliseNom(s.replace(Regex("[_\\-.*?()\\[\\]]"), " ")).split(Regex("\\s+"))
            .filter { it.length >= 2 && it !in MOTS_VIDES }
            .map { if (it.length > 3 && (it.endsWith("s") || it.endsWith("x"))) it.dropLast(1) else it }

    /** Tous les éléments du coffre (chemin, est un dossier), hors dossiers cachés. Parcours rapide (fichiers directs ou requêtes SAF groupées). */
    fun toutLister(limite: Int = 20_000): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        racineFichier()?.let { r ->
            r.walkTopDown().onEnter { it == r || !it.name.startsWith(".") }.forEach { f ->
                if (out.size < limite && f != r && !f.name.startsWith(".")) out += f.relativeTo(r).path.replace(File.separatorChar, '/') to f.isDirectory
            }
            return out
        }
        val v = vaultRoot() ?: return out
        val id = idCoffre() ?: return out
        fun parcourir(docId: String, prefixe: String, profondeur: Int) {
            val enfantsUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(v.uri, docId)
            val sous = mutableListOf<Pair<String, String>>()
            runCatching {
                ctx.contentResolver.query(enfantsUri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME, android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                    while (c.moveToNext() && out.size < limite) {
                        val nom = c.getString(1) ?: continue
                        if (nom.startsWith(".")) continue
                        val dossier = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                        out += (prefixe + nom) to dossier
                        if (dossier) sous += c.getString(0) to "$prefixe$nom/"
                    }
                }
            }
            if (profondeur < 10) sous.forEach { (sid, p) -> parcourir(sid, p, profondeur + 1) }
        }
        parcourir(id, "", 0)
        return out
    }

    /**
     * Chemin de dossier à utiliser pour [chemin] : chaque partie existante est retrouvée malgré majuscules / accents /
     * pluriel, et un premier dossier absent de la racine mais présent une seule fois ailleurs est repris (« images » →
     * « JARVIS/Images ») au lieu d'en créer un nouveau à côté. Les parties restantes seront créées.
     */
    fun cheminDossier(chemin: String): String {
        val parts = relatif(chemin).split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return ""
        var base = ""
        var i = 0
        val racine = enfants("").orEmpty()
        val premier = correspondance(racine, parts[0], dossierSeul = true)
        if (premier != null) { base = premier; i = 1 }
        else {
            val voulu = cleNom(parts[0])
            val candidats = toutLister().filter { it.second && cleNom(it.first.substringAfterLast('/')) == voulu }.map { it.first }
            if (candidats.size == 1) { base = candidats[0]; i = 1 }
        }
        while (i < parts.size) {
            val m = correspondance(enfants(base).orEmpty(), parts[i], dossierSeul = true) ?: break
            base = if (base.isEmpty()) m else "$base/$m"; i++
        }
        val reste = parts.drop(i).joinToString("/") { safe(it, "Dossier") }
        return listOf(base, reste).filter { it.isNotBlank() }.joinToString("/")
    }

    /** Vrai si [chemin] existe dans le coffre (écriture approximative tolérée). */
    fun existe(chemin: String): Boolean = resoudre(chemin) != null

    /** Retrouve un fichier du coffre à partir de son seul nom (premier trouvé), pour « déplace img_….png ». */
    fun chercherParNom(nom: String): String? {
        val cible = cleNom(nom.trim())
        return toutLister().firstOrNull { !it.second && (cleNom(it.first.substringAfterLast('/')) == cible || cleNom(it.first.substringAfterLast('/').substringBeforeLast('.')) == cible) }?.first
    }

    /**
     * Fichiers (et dossiers si [dossiers]) du coffre dont le NOM correspond à [motif] : motif avec * ou ?, sinon tous
     * les mots du motif présents dans le nom (sans accents ni majuscules). Les images générées par JARVIS sont aussi
     * retrouvées par leur description d'origine.
     */
    fun chercherFichiers(motif: String, dossiers: Boolean = false, limite: Int = 200): List<String> {
        val m = motif.trim().trim('"', '\'')
        if (m.isBlank()) return emptyList()
        val tout = toutLister()
        val descriptions = descriptionsImages()
        val dansDossier = m.substringBeforeLast('/', "").let { if (it.isBlank()) null else resoudre(it) ?: it }
        val nomMotif = m.substringAfterLast('/')
        val glob = nomMotif.contains('*') || nomMotif.contains('?')
        val re = if (glob) Regex("^" + nomMotif.split('*').joinToString(".*") { p -> p.split('?').joinToString(".") { Regex.escape(normaliseNom(it)) } } + "$") else null
        val mots = motsCles(nomMotif)
        val candidats = tout.asSequence().filter { (p, estDossier) -> (dossiers || !estDossier) && (dansDossier == null || p.startsWith("$dansDossier/")) }
        fun parMots() = candidats.filter { (p, _) ->
            val nom = p.substringAfterLast('/')
            val texte = (motsCles(nom.substringBeforeLast('.')) + (descriptions[nom]?.let { motsCles(it) } ?: emptyList())).joinToString(" ")
            mots.isNotEmpty() && mots.all { texte.contains(it) }
        }.map { it.first }.take(limite).toList()
        if (re == null) return parMots()
        // Motif avec * ou ? : d'abord sur le nom exact, sinon sur les mots (nom ou description d'image).
        val parMotif = candidats.filter { (p, _) ->
            val nom = normaliseNom(p.substringAfterLast('/'))
            re.matches(nom) || re.matches(nom.substringBeforeLast('.'))
        }.map { it.first }.take(limite).toList()
        return parMotif.ifEmpty { parMots() }
    }

    /**
     * Range dans [destination] TOUS les fichiers du coffre dont le nom contient les mots de [motif] (ou correspond au
     * motif avec *), filtrés par [types] (images, pdf, notes, videos, audio, tous) et éventuellement limités au dossier
     * [source]. Une destination simple (« Coloriage pompier ») devient un sous-dossier du dossier où se trouvent les
     * fichiers, sauf si un dossier de ce nom existe déjà. Rien n'est déplacé hors du coffre.
     */
    fun trier(motif: String, destination: String, types: String = "tous", source: String = "", sousDossier: Boolean = true): String {
        val m = motif.trim().trim('"', '\'', '«', '»').trim()
        if (m.isBlank()) return "Précise les mots que doit contenir le nom des fichiers."
        val src = relatif(source).takeIf { it.isNotBlank() }?.let { resoudre(it) ?: return "Dossier introuvable dans le coffre : $source" }
        val exts = when {
            types.contains("image", true) || types.contains("photo", true) -> setOf("png", "jpg", "jpeg", "webp", "gif", "heic", "bmp", "svg")
            types.contains("pdf", true) -> setOf("pdf")
            types.contains("note", true) || types.contains("markdown", true) -> setOf("md")
            types.contains("vid", true) -> setOf("mp4", "mov", "webm", "mkv", "3gp")
            types.contains("audio", true) || types.contains("son", true) -> setOf("mp3", "m4a", "ogg", "wav", "flac", "aac")
            else -> null
        }
        val destRel = relatif(destination)
        var fichiers = chercherFichiers(if (src != null) "$src/$m" else m, dossiers = false, limite = 5000)
            .filter { f -> exts == null || f.substringAfterLast('.', "").lowercase() in exts }
        if (fichiers.isEmpty()) return "Aucun fichier du coffre dont le nom contient « $m »" + (if (src != null) " dans coffre › ${src.replace("/", " › ")}" else "") + "."
        // Dossier commun aux fichiers trouvés (pour créer le sous-dossier au bon endroit).
        val parents = fichiers.map { it.substringBeforeLast('/', "") }
        var commun = parents.first()
        parents.forEach { p -> while (commun.isNotEmpty() && p != commun && !p.startsWith("$commun/")) commun = commun.substringBeforeLast('/', "") }
        val dest = when {
            destRel.isBlank() -> return "Précise le dossier de destination."
            resoudre(destRel)?.let { r -> enfants(r) != null } == true -> resoudre(destRel)!!
            !destRel.contains('/') && commun.isNotBlank() && resoudre("$commun/$destRel") != null -> resoudre("$commun/$destRel")!!
            !destRel.contains('/') && sousDossier && commun.isNotBlank() -> {
                // Un dossier de ce nom existe déjà ailleurs (une seule fois) : on le reprend ; sinon sous-dossier à côté des fichiers.
                val voulu = cleNom(destRel)
                toutLister().filter { it.second && cleNom(it.first.substringAfterLast('/')) == voulu }.map { it.first }.singleOrNull()
                    ?: "$commun/${safe(destRel, "Dossier")}"
            }
            else -> cheminDossier(destRel)
        }
        val reel = creerDossierChemin(dest)
        fichiers = fichiers.filter { it.substringBeforeLast('/', "") != reel }
        if (fichiers.isEmpty()) return "✅ Tous les fichiers « $m » sont déjà dans coffre › ${reel.replace("/", " › ")}."
        val ok = mutableListOf<String>(); val ko = mutableListOf<String>()
        fichiers.forEach { f -> runCatching { deplacer(f, reel) }.onSuccess { ok += f.substringAfterLast('/') }.onFailure { ko += "${f.substringAfterLast('/')} : ${it.message}" } }
        return buildString {
            append("📦 ${ok.size} fichier(s) « $m » rangé(s) dans coffre › ${reel.replace("/", " › ")}")
            if (ok.isNotEmpty()) append("\n" + ok.take(40).joinToString("\n") { "- $it" } + (if (ok.size > 40) "\n… et ${ok.size - 40} autres" else ""))
            if (ko.isNotEmpty()) append("\n\n❌ Non déplacé(s) :\n" + ko.take(20).joinToString("\n") { "- $it" })
        }
    }

    /** Fichier local lisible (pour un montage vidéo…) d'un fichier du coffre : direct si possible, sinon copie dans [dossier]. */
    fun versFichierLocal(chemin: String, dossier: File): File? {
        val c = resoudre(chemin) ?: return null
        racineFichier()?.let { r -> return fichierSur(r, c).takeIf { it.isFile } }
        val f = trouver(c)?.takeIf { it.isFile } ?: return null
        val sortie = File(dossier.apply { mkdirs() }, c.substringAfterLast('/'))
        return runCatching {
            ctx.contentResolver.openInputStream(f.uri)?.use { i -> sortie.outputStream().use { o -> i.copyTo(o) } } ?: return null
            sortie
        }.getOrNull()
    }

    /** Description (prompt) de chaque image générée par JARVIS, par nom de fichier. */
    private fun descriptionsImages(): Map<String, String> = runCatching {
        val f = File(ctx.filesDir, "images_descriptions.json")
        if (!f.isFile) emptyMap() else org.json.JSONObject(f.readText()).let { j -> j.keys().asSequence().associateWith { j.optString(it) } }
    }.getOrDefault(emptyMap())

    /** Fichiers d'un dossier du coffre (noms seuls), du plus récent au plus ancien quand les noms sont datés. */
    fun fichiersDe(dossier: String): List<String> {
        val d = resoudre(dossier) ?: return emptyList()
        return enfants(d).orEmpty().filter { !it.second }.map { it.first }.sortedDescending()
    }

    /** Crée un dossier du coffre (sous-dossiers compris) s'il n'existe pas ; renvoie le chemin réel utilisé. */
    fun creerDossierChemin(chemin: String): String {
        val c = cheminDossier(chemin)
        if (c.isBlank()) throw IllegalArgumentException("Nom de dossier manquant.")
        racineFichier()?.let { r ->
            val d = fichierSur(r, c)
            if (!d.isDirectory && !d.mkdirs()) throw IllegalStateException("Création du dossier refusée.")
            return c
        }
        val tree = vaultRoot()?.takeIf { it.canWrite() } ?: throw IllegalStateException("Coffre Obsidian non choisi ou en lecture seule (Réglages).")
        subDir(tree, c)
        if (trouver(c)?.isDirectory != true) throw IllegalStateException("Création du dossier « $c » refusée par le stockage.")
        return c
    }

    /** Crée un dossier du coffre (sous-dossiers compris) s'il n'existe pas. */
    fun creerDossier(chemin: String): String = "coffre › " + creerDossierChemin(chemin).replace("/", " › ")

    /** Déplace une note, une image, un fichier ou un dossier du coffre dans [dossierCible] (créé au besoin). */
    fun deplacer(source: String, dossierCible: String): String {
        val src = resoudre(source) ?: throw IllegalArgumentException("Introuvable dans le coffre : ${source.trim().trim('/')}")
        val dest = creerDossierChemin(dossierCible)
        racineFichier()?.let { r ->
            val f = fichierSur(r, src)
            if (!f.exists()) throw IllegalArgumentException("Introuvable dans le coffre : $src")
            val d = fichierSur(r, dest).apply { mkdirs() }
            if (f.isDirectory && d.canonicalPath.startsWith(f.canonicalPath)) throw IllegalArgumentException("Impossible de déplacer un dossier dans lui-même.")
            val cible = File(d, f.name)
            if (cible.canonicalPath == f.canonicalPath) return "${f.name} est déjà dans coffre › ${dest.replace("/", " › ")}"
            if (cible.exists()) throw IllegalStateException("« ${f.name} » existe déjà dans $dest")
            if (!f.renameTo(cible)) { f.copyRecursively(cible); f.deleteRecursively() }
            return "${f.name} → coffre › ${dest.replace("/", " › ")}"
        }
        val tree = vaultRoot()?.takeIf { it.canWrite() } ?: throw IllegalStateException("Coffre Obsidian non choisi ou en lecture seule (Réglages).")
        val f = trouver(src) ?: throw IllegalArgumentException("Introuvable dans le coffre : $src")
        val parent = if (src.contains('/')) trouver(src.substringBeforeLast('/')) ?: tree else tree
        val nom = f.name ?: src.substringAfterLast('/')
        if (src.substringBeforeLast('/', "") == dest) return "$nom est déjà dans coffre › ${dest.replace("/", " › ")}"
        val d = trouver(dest)?.takeIf { it.isDirectory } ?: throw IllegalStateException("Impossible de créer le dossier « $dest » dans le coffre.")
        if (d.trouverLoose(nom) != null) throw IllegalStateException("« $nom » existe déjà dans $dest")
        // Déplacement natif (même stockage) ; sinon copie puis suppression de l'original.
        val deplace = runCatching { android.provider.DocumentsContract.moveDocument(ctx.contentResolver, f.uri, parent.uri, d.uri) }.getOrNull()
        if (deplace == null) {
            if (f.isDirectory) throw IllegalStateException("Ce stockage ne permet pas de déplacer un dossier entier.")
            val copie = d.createFile(f.type ?: "application/octet-stream", nom) ?: throw IllegalStateException("Copie impossible dans « $dest ».")
            ctx.contentResolver.openInputStream(f.uri)?.use { i -> ctx.contentResolver.openOutputStream(copie.uri, "wt")?.use { o -> i.copyTo(o) } }
                ?: throw IllegalStateException("Lecture de « $nom » impossible.")
            f.delete()
        }
        return "$nom → coffre › ${dest.replace("/", " › ").ifBlank { "racine" }}"
    }

    /** Renomme un élément du coffre (le dossier ne change pas). */
    fun renommer(chemin: String, nouveauNom: String): String {
        val c = resoudre(chemin) ?: throw IllegalArgumentException("Introuvable dans le coffre : ${chemin.trim().trim('/')}")
        val n = safe(nouveauNom.trim(), "")
        if (n.isBlank()) throw IllegalArgumentException("Nouveau nom manquant.")
        racineFichier()?.let { r ->
            val f = fichierSur(r, c)
            val cible = File(f.parentFile, n)
            if (cible.exists()) throw IllegalStateException("« $n » existe déjà.")
            if (!f.renameTo(cible)) throw IllegalStateException("Renommage refusé.")
            return "${f.name} → $n"
        }
        val f = trouver(c) ?: throw IllegalArgumentException("Introuvable dans le coffre : $c")
        val ancien = f.name
        if (!f.renameTo(n)) throw IllegalStateException("Renommage refusé par le stockage.")
        return "$ancien → $n"
    }

    /** Contenu d'un dossier du coffre : sous-dossiers puis fichiers de tous types (notes, images avec leur description…). */
    fun listerDossier(chemin: String): String {
        val c = resoudre(chemin) ?: return "Dossier introuvable dans le coffre : ${chemin.trim().trim('/').ifBlank { "racine" }}" +
            proches(chemin).let { if (it.isEmpty()) "" else "\nDossiers proches : " + it.joinToString(", ") }
        val entrees = enfants(c) ?: return "« $c » n'est pas un dossier."
        if (entrees.isEmpty()) return "📂 coffre › ${c.replace("/", " › ").ifBlank { "racine" }} : (vide)"
        val descriptions = descriptionsImages()
        val tri = entrees.sortedWith(compareBy({ !it.second }, { it.first.lowercase() }))
        return "📂 coffre › ${c.replace("/", " › ").ifBlank { "racine" }}\n" + tri.take(300).joinToString("\n") { (n, dir) ->
            if (dir) "📁 $n" else "📄 $n" + (descriptions[n]?.let { " — « ${it.take(80)} »" } ?: "")
        } + (if (tri.size > 300) "\n… (${tri.size} éléments)" else "")
    }

    /** Dossiers dont le nom ressemble à la fin de [chemin] (aide quand un dossier demandé est introuvable). */
    private fun proches(chemin: String): List<String> {
        val voulu = cleNom(chemin.trim().trim('/').substringAfterLast('/'))
        if (voulu.length < 2) return emptyList()
        return toutLister().filter { it.second && cleNom(it.first.substringAfterLast('/')).let { n -> n.contains(voulu) || voulu.contains(n) } }.map { it.first }.take(8)
    }

    // ------------------------------------------------------------------ graphe (vue « Obsidian graph »)

    data class GraphNode(val id: String, val label: String, val folder: String, val ghost: Boolean = false)
    data class GraphEdge(val from: String, val to: String)
    /** Lien Markdown orienté : from référence to. Une cible absente reste une référence, pas un faux nœud. */
    data class GraphReference(val from: String, val to: String, val resolved: Boolean, val rawTarget: String)
    /** Fichier non-Markdown réellement présent dans le coffre (image, PDF, audio, vidéo, autre). */
    data class GraphAttachment(val path: String, val name: String, val kind: String)
    data class GraphData(
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>,
        val references: List<GraphReference> = emptyList(),
        /** Étiquettes réellement écrites dans chaque note (#tag dans le texte ou « tags: » du frontmatter). */
        val tags: Map<String, List<String>> = emptyMap(),
        /** Pièces jointes réellement présentes dans le coffre (aucune n'est inventée). */
        val attachments: List<GraphAttachment> = emptyList(),
        /** Tous les dossiers du coffre (chemins relatifs), y compris les dossiers encore vides. */
        val dossiers: List<String> = emptyList()
    )

    private val lienRegex = Regex("\\[\\[([^\\]|#]+)")
    // #tag Obsidian : précédé d'un début de ligne ou d'un blanc, commence par une lettre (exclut « # Titre » et les
    // couleurs « #FF00AA » qui commencent souvent par un chiffre), lettres/chiffres/_/-// ensuite.
    private val tagRegex = Regex("(?:^|(?<=\\s))#(\\p{L}[\\p{L}\\p{N}_/-]*)")
    private val frontmatterRegex = Regex("\\A---\\s*\\n([\\s\\S]*?)\\n---")

    private fun typePiece(nom: String): String = when (nom.substringAfterLast('.', "").lowercase()) {
        "png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "heic" -> "IMAGE"
        "pdf" -> "PDF"
        "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> "AUDIO"
        "mp4", "mov", "webm", "mkv", "avi", "3gp" -> "VIDEO"
        else -> "FICHIER"
    }

    /** Étiquettes d'une note : #tags du texte (hors blocs de code) + liste « tags: » du frontmatter YAML. */
    private fun extraireTags(texte: String): List<String> {
        val out = LinkedHashSet<String>()
        frontmatterRegex.find(texte)?.groupValues?.get(1)?.let { fm ->
            val lignes = fm.lines()
            val i = lignes.indexOfFirst { it.trimStart().startsWith("tags:", true) }
            if (i >= 0) {
                val enLigne = lignes[i].substringAfter(':').trim().trim('[', ']')
                enLigne.split(',').map { it.trim().trim('"', '\'', '#') }.filter { it.isNotBlank() }.forEach { out += it }
                for (l in lignes.drop(i + 1)) {
                    val t = l.trim()
                    if (!t.startsWith("-")) break
                    t.removePrefix("-").trim().trim('"', '\'', '#').takeIf { it.isNotBlank() }?.let { out += it }
                }
            }
        }
        val sansCode = texte.replace(Regex("```[\\s\\S]*?```"), " ")
        tagRegex.findAll(sansCode).forEach { out += it.groupValues[1].trimEnd('/', '-') }
        return out.filter { it.isNotBlank() }.take(30)
    }

    private fun idOf(path: String) = path.removeSuffix(".md").removeSuffix(".MD")
    private fun labelOf(path: String) = idOf(path).substringAfterLast('/')
    private fun folderOf(path: String) = idOf(path).substringBeforeLast('/', "")

    /** Identifiant SAF du dossier du coffre (gère aussi un lien « document dans une arborescence »). */
    private fun idCoffre(): String? = vaultRoot()?.uri?.let { u ->
        runCatching { android.provider.DocumentsContract.getDocumentId(u) }.getOrNull()
            ?: runCatching { android.provider.DocumentsContract.getTreeDocumentId(u) }.getOrNull()
    }

    @Volatile private var cacheDirect: Triple<String, Long, File?>? = null

    /**
     * Dossier du coffre sur le stockage du téléphone, lisible directement (bien plus rapide que l'accès SAF) quand le
     * coffre est sur le stockage principal et que l'accès à tous les fichiers est accordé ; null sinon. Garde-fous :
     * jamais la racine du téléphone, et le contenu lu doit être celui que montre Android pour le coffre choisi —
     * sinon on reste sur l'accès SAF (c'était la cause des dossiers créés à la racine du téléphone).
     */
    fun fichierCoffre(): File? {
        val cle = store.obsidianUri.value
        val maintenant = System.currentTimeMillis()
        cacheDirect?.let { (c, t, f) -> if (c == cle && maintenant - t < 60_000) return f?.takeIf { it.isDirectory } }
        val f = runCatching { calculerFichierCoffre() }.getOrNull()
        cacheDirect = Triple(cle, maintenant, f)
        return f
    }

    private fun calculerFichierCoffre(): File? {
        val v = vaultRoot() ?: return null
        if (v.uri.authority != "com.android.externalstorage.documents") return null
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return null
        val id = idCoffre() ?: return null
        val rel = when {
            id.startsWith("primary:") -> id.removePrefix("primary:")
            id.startsWith("home:") -> "Documents/" + id.removePrefix("home:")
            else -> return null
        }.trim('/')
        if (rel.isBlank()) return null
        val f = File(android.os.Environment.getExternalStorageDirectory(), rel)
        if (!f.isDirectory || !f.canRead()) return null
        val direct = f.list()?.filterNot { it.startsWith(".") }?.map { normaliseNom(it) }?.toSet() ?: return null
        val saf = v.listFiles().mapNotNull { it.name }.filterNot { it.startsWith(".") }.map { normaliseNom(it) }.toSet()
        return f.takeIf { direct == saf }
    }

    /**
     * Empreinte rapide de l'arborescence du coffre (chemins, dates, tailles) : elle change dès qu'un dossier ou un
     * fichier est ajouté, supprimé, renommé ou modifié. Sert à rafraîchir l'Orbe et le Graphe tout seuls.
     */
    fun signature(): Long {
        var h = 1125899906842597L
        fun mix(chemin: String, date: Long, taille: Long) { h = 31 * (31 * (31 * h + chemin.hashCode()) + date) + taille }
        val direct = fichierCoffre()
        val v = vaultRoot()
        when {
            direct != null -> direct.walkTopDown().onEnter { it == direct || !it.name.startsWith(".") }
                .forEach { f -> if (f != direct && !f.name.startsWith(".")) mix(f.relativeTo(direct).path, f.lastModified(), if (f.isFile) f.length() else -1) }
            v != null -> {
                val racine = idCoffre() ?: return 0L
                signatureSaf(v.uri, racine, "", 0) { c, d, t -> mix(c, d, t) }
            }
            else -> localRoot().walkTopDown().forEach { f -> mix(f.path, f.lastModified(), if (f.isFile) f.length() else -1) }
        }
        return h
    }

    /** Parcours SAF par requêtes groupées (une par dossier) : beaucoup plus léger que DocumentFile.listFiles(). */
    private fun signatureSaf(tree: Uri, docId: String, prefixe: String, profondeur: Int, mix: (String, Long, Long) -> Unit) {
        val enfants = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val colonnes = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID, android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE, android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            android.provider.DocumentsContract.Document.COLUMN_SIZE
        )
        val sous = mutableListOf<Pair<String, String>>()
        runCatching {
            ctx.contentResolver.query(enfants, colonnes, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val nom = c.getString(1) ?: continue
                    if (nom.startsWith(".")) continue
                    val dossier = c.getString(2) == android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    mix(prefixe + nom, if (c.isNull(3)) 0L else c.getLong(3), if (dossier || c.isNull(4)) -1L else c.getLong(4))
                    if (dossier) sous += c.getString(0) to "$prefixe$nom/"
                }
            }
        }
        if (profondeur < 12) sous.forEach { (id, p) -> signatureSaf(tree, id, p, profondeur + 1, mix) }
    }

    /** Ouvre un fichier du coffre (PDF, image, audio…) dans l'application adaptée du téléphone. */
    fun ouvrirFichier(chemin: String): Boolean {
        val propre = resoudre(chemin) ?: chemin.trim().trim('/')
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(propre.substringAfterLast('.', "").lowercase()) ?: "*/*"
        var uri: Uri? = null
        val v = vaultRoot()
        if (v != null) {
            var cur: DocumentFile? = v
            for (part in propre.split('/').filter { it.isNotBlank() }) cur = cur?.findFile(part)
            uri = cur?.takeIf { it.isFile }?.uri
        }
        if (uri == null) return false
        return runCatching {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    /** Construit le graphe (nœuds = notes du coffre, arêtes = liens « [[...]] », dossiers, pièces jointes) pour l'Orbe
     *  et le Graphe. Toutes les notes et tous les dossiers sont relevés (jusqu'à [limiteNotes]) ; le contenu n'est lu
     *  (liens, tags) que pour [limiteLecture] notes, plafonné à [limiteOctets] par note, pour rester fluide. */
    fun graph(limiteNotes: Int = 3000, limiteOctets: Int = 60_000, limitePieces: Int = 3000, limiteLecture: Int = 700): GraphData {
        val v = vaultRoot()
        val direct = fichierCoffre()
        val cheminsPieces = mutableListOf<String>()
        val dossiers = mutableListOf<String>()
        val fichiers: List<Pair<String, () -> String?>> =
            if (direct != null || v == null) {
                val racine = direct ?: localRoot()
                val tous = racine.walkTopDown().onEnter { it == racine || !it.name.startsWith(".") }.filter { it != racine && !it.name.startsWith(".") }.toList()
                tous.filter { it.isDirectory }.forEach { dossiers += it.relativeTo(racine).path }
                tous.filter { it.isFile && !it.name.endsWith(".md", true) }.take(limitePieces).forEach { cheminsPieces += it.relativeTo(racine).path }
                tous.filter { it.isFile && it.name.endsWith(".md", true) }.take(limiteNotes).map { f ->
                    f.relativeTo(racine).path to { runCatching { f.readText().take(limiteOctets) }.getOrNull() }
                }
            } else {
                val notesSaf = mutableListOf<Pair<String, DocumentFile>>()
                runCatching { walkAvecPieces(v, "", notesSaf, cheminsPieces, limiteNotes, limitePieces, dossiers) }
                notesSaf.map { (chemin, df) ->
                    chemin to { runCatching { ctx.contentResolver.openInputStream(df.uri)?.use { it.readBytes().take(limiteOctets).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() }
                }
            }
        val pieces = cheminsPieces.map { p -> GraphAttachment(p, p.substringAfterLast('/'), typePiece(p)) }
        val tagsParNote = HashMap<String, List<String>>()

        val nodesById = LinkedHashMap<String, GraphNode>()
        for ((chemin, _) in fichiers) {
            val id = idOf(chemin)
            nodesById[id] = GraphNode(id, labelOf(chemin), folderOf(chemin))
        }

        // Pour retrouver une cible « [[Nom]] » même sans chemin complet : on indexe aussi par simple nom de fichier.
        val parIdMinuscule = nodesById.keys.associateBy { it.lowercase() }
        val parNom = HashMap<String, MutableList<String>>()
        for (id in nodesById.keys) parNom.getOrPut(id.substringAfterLast('/').lowercase()) { mutableListOf() }.add(id)

        val edges = mutableListOf<GraphEdge>()
        val references = mutableListOf<GraphReference>()
        val vusReferences = HashSet<Pair<String, String>>()
        val vusEdges = HashSet<Pair<String, String>>()
        for ((chemin, lire) in fichiers.take(limiteLecture)) {
            val fromId = idOf(chemin)
            val texte = lire() ?: continue
            runCatching { extraireTags(texte) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { tagsParNote[fromId] = it }
            for (m in lienRegex.findAll(texte)) {
                val cible = m.groupValues[1].trim().trim('/')
                if (cible.isBlank()) continue
                val cibleNorm = cible.removeSuffix(".md").removeSuffix(".MD")
                val toId = parIdMinuscule[cibleNorm.lowercase()]
                    ?: parNom[cibleNorm.substringAfterLast('/').lowercase()]?.firstOrNull()
                    ?: cibleNorm
                if (fromId == toId) continue

                // Une relation est toujours orientée depuis la note qui contient le wikilink.
                if (vusReferences.add(fromId to toId)) {
                    references += GraphReference(
                        from = fromId,
                        to = toId,
                        resolved = toId in nodesById,
                        rawTarget = cible
                    )
                }

                // Conservation temporaire du format d'arêtes utilisé par l'ancienne vue 2D.
                // La nouvelle indexation Room consomme exclusivement references, sans perdre le sens du lien.
                val cle = if (fromId < toId) fromId to toId else toId to fromId
                if (vusEdges.add(cle)) edges += GraphEdge(fromId, toId)
            }
        }
        return GraphData(nodesById.values.toList(), edges, references, tagsParNote, pieces, dossiers.map { it.replace(File.separatorChar, '/') })
    }

    private companion object {
        val MOTS_VIDES = setOf("le", "la", "les", "un", "une", "des", "du", "de", "d", "l", "et", "ou", "a", "au", "aux", "en", "dans", "leur", "nom",
            "fichier", "fichiers", "image", "images", "the", "of", "and")
    }
}
