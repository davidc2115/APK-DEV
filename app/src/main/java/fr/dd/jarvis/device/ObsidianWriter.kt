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

    /** Élément du coffre (note, image, PDF, dossier…) par chemin relatif, insensible aux accents/majuscules. */
    private fun trouver(chemin: String): DocumentFile? {
        var cur: DocumentFile? = vaultRoot() ?: return null
        for (part in chemin.trim().trim('/').split('/').filter { it.isNotBlank() }) cur = cur?.trouverLoose(part)
        return cur
    }

    /** Racine « fichier » utilisable directement : le coffre lui-même (accès à tous les fichiers) ou, sans coffre choisi, le dossier local. */
    private fun racineFichier(): File? = fichierCoffre() ?: if (vaultRoot() == null) localRoot() else null

    private fun fichierSur(racine: File, chemin: String): File {
        val r = racine.canonicalFile
        val f = File(r, chemin.trim().trim('/')).canonicalFile
        if (!f.path.startsWith(r.path)) throw IllegalArgumentException("Chemin hors du coffre.")
        return f
    }

    /** Vrai si [chemin] existe dans le coffre. */
    fun existe(chemin: String): Boolean = racineFichier()?.let { fichierSur(it, chemin).exists() } ?: (trouver(chemin) != null)

    /** Retrouve un fichier du coffre à partir de son seul nom (premier trouvé), pour « déplace img_….png ». */
    fun chercherParNom(nom: String): String? {
        val cible = normaliseNom(nom.trim())
        racineFichier()?.let { r ->
            return r.walkTopDown().onEnter { it == r || !it.name.startsWith(".") }.firstOrNull { it.isFile && normaliseNom(it.name) == cible }?.relativeTo(r)?.path
        }
        val v = vaultRoot() ?: return null
        val notes = mutableListOf<Pair<String, DocumentFile>>(); val pieces = mutableListOf<String>()
        runCatching { walkAvecPieces(v, "", notes, pieces, 5000, 5000) }
        return (notes.map { it.first } + pieces).firstOrNull { normaliseNom(it.substringAfterLast('/')) == cible }
    }

    /** Fichiers d'un dossier du coffre (noms seuls), du plus récent au plus ancien quand les noms sont datés. */
    fun fichiersDe(dossier: String): List<String> {
        racineFichier()?.let { r -> return fichierSur(r, dossier).listFiles()?.filter { it.isFile }?.map { it.name }?.sortedDescending().orEmpty() }
        return trouver(dossier)?.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.mapNotNull { it.name }?.sortedDescending().orEmpty()
    }

    /** Crée un dossier du coffre (sous-dossiers compris) s'il n'existe pas. */
    fun creerDossier(chemin: String): String {
        val c = chemin.trim().trim('/')
        if (c.isBlank()) throw IllegalArgumentException("Nom de dossier manquant.")
        racineFichier()?.let { r ->
            val d = fichierSur(r, c)
            if (!d.isDirectory && !d.mkdirs()) throw IllegalStateException("Création du dossier refusée.")
            return "coffre › ${c.replace("/", " › ")}"
        }
        val tree = vaultRoot()?.takeIf { it.canWrite() } ?: throw IllegalStateException("Coffre Obsidian non choisi ou en lecture seule (Réglages).")
        subDir(tree, c)
        if (trouver(c)?.isDirectory != true) throw IllegalStateException("Création du dossier « $c » refusée par le stockage.")
        return "coffre › ${c.replace("/", " › ")}"
    }

    /** Déplace une note, une image, un fichier ou un dossier du coffre dans [dossierCible] (créé au besoin). */
    fun deplacer(source: String, dossierCible: String): String {
        val src = source.trim().trim('/')
        val dest = dossierCible.trim().trim('/')
        racineFichier()?.let { r ->
            val f = fichierSur(r, src)
            if (!f.exists()) throw IllegalArgumentException("Introuvable dans le coffre : $src")
            val d = fichierSur(r, dest).apply { mkdirs() }
            val cible = File(d, f.name)
            if (cible.exists()) throw IllegalStateException("« ${f.name} » existe déjà dans $dest")
            if (!f.renameTo(cible)) { f.copyRecursively(cible); f.deleteRecursively() }
            return "${f.name} → coffre › ${dest.replace("/", " › ")}"
        }
        val tree = vaultRoot()?.takeIf { it.canWrite() } ?: throw IllegalStateException("Coffre Obsidian non choisi ou en lecture seule (Réglages).")
        val f = trouver(src) ?: throw IllegalArgumentException("Introuvable dans le coffre : $src")
        val parent = if (src.contains('/')) trouver(src.substringBeforeLast('/')) ?: tree else tree
        val nom = f.name ?: src.substringAfterLast('/')
        val d = subDir(tree, dest)
        if (dest.isNotBlank() && trouver(dest)?.isDirectory != true) throw IllegalStateException("Impossible de créer le dossier « $dest » dans le coffre.")
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
        val c = chemin.trim().trim('/')
        val n = safe(nouveauNom.trim(), "")
        if (n.isBlank()) throw IllegalArgumentException("Nouveau nom manquant.")
        racineFichier()?.let { r ->
            val f = fichierSur(r, c)
            if (!f.exists()) throw IllegalArgumentException("Introuvable dans le coffre : $c")
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

    /** Contenu d'un dossier du coffre : sous-dossiers puis fichiers de tous types (notes, images, PDF…). */
    fun listerDossier(chemin: String): String {
        val c = chemin.trim().trim('/')
        val entrees: List<Pair<String, Boolean>> = racineFichier()?.let { r ->
            val d = fichierSur(r, c)
            if (!d.isDirectory) return "Dossier introuvable dans le coffre : ${c.ifBlank { "racine" }}"
            d.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.name to it.isDirectory }.orEmpty()
        } ?: run {
            val d = (if (c.isBlank()) vaultRoot() else trouver(c))?.takeIf { it.isDirectory } ?: return "Dossier introuvable dans le coffre : ${c.ifBlank { "racine" }}"
            d.listFiles().mapNotNull { f -> f.name?.takeIf { !it.startsWith(".") }?.let { it to f.isDirectory } }
        }
        if (entrees.isEmpty()) return "📂 coffre › ${c.ifBlank { "racine" }} : (vide)"
        val tri = entrees.sortedWith(compareBy({ !it.second }, { it.first.lowercase() }))
        return "📂 coffre › ${c.replace("/", " › ").ifBlank { "racine" }}\n" + tri.take(300).joinToString("\n") { (n, dir) -> if (dir) "📁 $n" else "📄 $n" } +
            (if (tri.size > 300) "\n… (${tri.size} éléments)" else "")
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

    /**
     * Dossier du coffre sur le stockage du téléphone, lisible directement (bien plus rapide que l'accès SAF) quand le
     * coffre est sur le stockage principal et que l'accès à tous les fichiers est accordé ; null sinon.
     */
    fun fichierCoffre(): File? {
        val uri = store.obsidianUri.value.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        if (uri.authority != "com.android.externalstorage.documents") return null
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) return null
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        if (!id.startsWith("primary:")) return null
        val f = File(android.os.Environment.getExternalStorageDirectory(), id.removePrefix("primary:"))
        return f.takeIf { it.isDirectory && it.canRead() && it.listFiles() != null }
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
                val tree = Uri.parse(store.obsidianUri.value)
                val racine = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return 0L
                signatureSaf(tree, racine, "", 0) { c, d, t -> mix(c, d, t) }
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
        val propre = chemin.trim().trim('/')
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
}
