package fr.dd.jarvis.device

/**
 * Résolution des chemins dont le premier dossier est omis.
 * On ne retient un chemin que si le suffixe complet correspond à un seul emplacement.
 */
internal object ObsidianPathResolution {
    /** Reconstitue les enfants directs depuis l'index SAF complet quand DocumentFile.listFiles() renvoie une liste vide. */
    fun directChildren(folder: String, entries: List<Pair<String, Boolean>>): List<Pair<String, Boolean>> {
        val base = folder.trim('/')
        val prefix = if (base.isBlank()) "" else "$base/"
        return entries.mapNotNull { (path, isDirectory) ->
            if (!path.startsWith(prefix)) return@mapNotNull null
            val remainder = path.substring(prefix.length)
            if (remainder.isBlank() || '/' in remainder) null else remainder to isDirectory
        }.distinctBy { it.first }
    }

    fun <T> uniqueNestedPath(
        firstSegment: String,
        folderPaths: List<String>,
        normalize: (String) -> String,
        resolveRemainder: (String) -> T?
    ): T? {
        val wanted = normalize(firstSegment)
        if (wanted.isBlank()) return null
        val candidates = folderPaths.asSequence()
            .filter { path -> path.isNotBlank() && normalize(path.substringAfterLast('/')) == wanted }
            .distinct()
            .mapNotNull(resolveRemainder)
            .distinct()
            .toList()
        return candidates.singleOrNull()
    }
    /** Résout un nom exact ou approché uniquement si une seule entrée correspond. */
    fun uniqueNameMatch(
        candidates: List<String>,
        wanted: String,
        normalize: (String) -> String,
        loose: (String) -> String
    ): String? {
        fun unique(matches: List<String>, mode: String): String? {
            if (matches.size > 1) {
                throw IllegalArgumentException("Plusieurs éléments correspondent à « $wanted » ($mode). Précise le chemin complet.")
            }
            return matches.singleOrNull()
        }
        unique(candidates.filter { it == wanted }, "nom exact")?.let { return it }

        val normalizedMatches = candidates.filter { normalize(it) == normalize(wanted) }
        val looseMatches = candidates.filter { loose(it) == loose(wanted) }
        if (normalizedMatches.isNotEmpty()) {
            // Une correspondance accent/casse n'est sûre que si aucun autre nom
            // proche ne pourrait être confondu avec elle (ex. "Images" / "image").
            if (looseMatches.size > 1) {
                throw IllegalArgumentException(
                    "Plusieurs éléments correspondent à « $wanted » (nom approché). Précise le chemin complet."
                )
            }
            return normalizedMatches.singleOrNull()
        }
        return unique(looseMatches, "nom approché")
    }

}
