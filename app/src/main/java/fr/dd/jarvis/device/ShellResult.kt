package fr.dd.jarvis.device

/** Normalise les résultats shell pour qu'un code de sortie non nul ne puisse jamais être annoncé comme un succès. */
internal object ShellResult {
    private val marker = Regex("(?m)^__JARVIS_EXIT_CODE__=(-?\\d+)\\s*$")
    private val erreurExplicite = Regex("(?im)^(?:error(?::|\\\\b)|exception\\\\b|unknown command\\\\b|permission denied\\\\b|securityexception\\\\b|failure \\\\[|can't find service\\\\b|/system/bin/sh:.*(?:not found|permission denied))")

    fun withExitCode(command: String): String =
        "$command\n__jarvis_status=\$?\nprintf '\\n__JARVIS_EXIT_CODE__=%s\\n' \"\$__jarvis_status\""

    fun fromAdbOutput(output: String): String? {
        val match = marker.findAll(output).lastOrNull() ?: return null
        val code = match.groupValues[1].toIntOrNull() ?: return null
        val details = output.removeRange(match.range).trim()
        return format(code, details)
    }

    fun format(exitCode: Int, output: String): String {
        val details = output.trim()
        return when {
            exitCode != 0 -> "Erreur shell (code $exitCode) : ${details.ifEmpty { "aucune sortie" }}"
            erreurExplicite.containsMatchIn(details) -> "Erreur shell (code 0, message d'erreur) : $details"
            else -> details.ifEmpty { "(commande exécutée)" }
        }
    }

    fun isFailure(result: String): Boolean =
        result.startsWith("Erreur") || result.startsWith("ADB non connecté") ||
            result.startsWith("Aucun accès") || result.startsWith("Shizuku n'est pas") ||
            result.startsWith("⏱️") || result.startsWith("Commande Shizuku interrompue") ||
            result.startsWith("Commande ADB interrompue")
}
