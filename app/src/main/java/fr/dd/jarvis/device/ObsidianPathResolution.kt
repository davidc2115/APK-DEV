package fr.dd.jarvis.device

/**
 * Sélectionne un dossier imbriqué quand l'utilisateur omet un préfixe connu du coffre.
 * Le résultat n'est accepté que s'il est unique : aucune sélection arbitraire entre homonymes.
 */
internal object ObsidianPathResolution {
    fun uniqueNestedFolder(
        firstSegment: String,
        folderPaths: List<String>,
        normalize: (String) -> String
    ): String? {
        val wanted = normalize(firstSegment)
        if (wanted.isBlank()) return null
        return folderPaths.asSequence()
            .filter { path -> path.isNotBlank() && normalize(path.substringAfterLast('/')) == wanted }
            .distinct()
            .singleOrNull()
    }
}
