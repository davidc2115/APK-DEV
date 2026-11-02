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

    // ------------------------------------------------------------------ graphe (vue « Obsidian graph »)

    data class GraphNode(val id: String, val label: String, val folder: String, val ghost: Boolean = false)
    data class GraphEdge(val from: String, val to: String)
    /** Lien Markdown orienté : from référence to. Une cible absente reste une référence, pas un faux nœud. */
    data class GraphReference(val from: String, val to: String, val resolved: Boolean, val rawTarget: String)
    data class GraphData(
        val nodes: List<GraphNode>,
        val edges: List<GraphEdge>,
        val references: List<GraphReference> = emptyList()
    )

    private val lienRegex = Regex("\\[\\[([^\\]|#]+)")

    private fun idOf(path: String) = path.removeSuffix(".md").removeSuffix(".MD")
    private fun labelOf(path: String) = idOf(path).substringAfterLast('/')
    private fun folderOf(path: String) = idOf(path).substringBeforeLast('/', "")

    /** Construit le graphe (nœuds = notes du coffre, arêtes = liens « [[...]] ») pour l'onglet Graphe.
     *  Plafonné à [limiteNotes] notes et [limiteOctets] par note pour rester fluide sur un gros coffre. */
    fun graph(limiteNotes: Int = 400, limiteOctets: Int = 60_000): GraphData {
        val v = vaultRoot()
        val fichiers: List<Pair<String, () -> String?>> =
            if (v != null) allNotes(limiteNotes).map { (chemin, df) ->
                chemin to { runCatching { ctx.contentResolver.openInputStream(df.uri)?.use { it.readBytes().take(limiteOctets).toByteArray().toString(Charsets.UTF_8) } }.getOrNull() }
            }
            else localRoot().walkTopDown().filter { it.isFile && it.name.endsWith(".md", true) }.take(limiteNotes).map { f ->
                f.relativeTo(localRoot()).path to { runCatching { f.readText().take(limiteOctets) }.getOrNull() }
            }.toList()

        val nodesById = LinkedHashMap<String, GraphNode>()
        for ((chemin, _) in fichiers) {
            val id = idOf(chemin)
            nodesById[id] = GraphNode(id, labelOf(chemin), folderOf(chemin))
        }

        // Pour retrouver une cible « [[Nom]] » même sans chemin complet : on indexe aussi par simple nom de fichier.
        val parNom = HashMap<String, MutableList<String>>()
        for (id in nodesById.keys) parNom.getOrPut(id.substringAfterLast('/').lowercase()) { mutableListOf() }.add(id)

        val edges = mutableListOf<GraphEdge>()
        val references = mutableListOf<GraphReference>()
        val vusReferences = HashSet<Pair<String, String>>()
        val vusEdges = HashSet<Pair<String, String>>()
        for ((chemin, lire) in fichiers) {
            val fromId = idOf(chemin)
            val texte = lire() ?: continue
            for (m in lienRegex.findAll(texte)) {
                val cible = m.groupValues[1].trim().trim('/')
                if (cible.isBlank()) continue
                val cibleNorm = cible.removeSuffix(".md").removeSuffix(".MD")
                val toId = nodesById.keys.firstOrNull { it.equals(cibleNorm, true) }
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
        return GraphData(nodesById.values.toList(), edges, references)
    }
}
