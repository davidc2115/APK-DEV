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
}
