package fr.dd.jarvis.device

/** Normalise les résultats shell pour qu'un code de sortie non nul ne puisse jamais être annoncé comme un succès. */
internal object ShellResult {
    private val marker = Regex("(?m)^__JARVIS_EXIT_CODE__=(-?\\d+)\\s*$")

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
            exitCode == 0 -> details.ifEmpty { "(commande exécutée)" }
            details.isEmpty() -> "Erreur shell (code $exitCode) : aucune sortie."
            else -> "Erreur shell (code $exitCode) : $details"
        }
    }
}
