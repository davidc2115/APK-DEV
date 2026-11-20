package fr.dd.jarvis.device

/**
 * Résolution des chemins dont le premier dossier est omis.
 * On ne retient un chemin que si le suffixe complet correspond à un seul emplacement.
 */
internal object ObsidianPathResolution {
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
        unique(candidates.filter { normalize(it) == normalize(wanted) }, "accents ou casse")?.let { return it }
        return unique(candidates.filter { loose(it) == loose(wanted) }, "nom approché")
    }

}
