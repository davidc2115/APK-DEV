package fr.dd.jarvis.ai

import android.os.Build
import android.os.Environment
import fr.dd.jarvis.JarvisApp
import java.io.File

/** Stockage interne/externe du téléphone (en dehors du dossier privé de JARVIS) : lister, lire, renommer, déplacer, supprimer, créer un dossier.
 *  Nécessite la permission « Tous les fichiers » (MANAGE_EXTERNAL_STORAGE) sur Android 11+. */
object Stockage {
    private const val MAX_LECTURE = 300_000L

    fun racine(): File = Environment.getExternalStorageDirectory()

    fun autorise(): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager() else true

    private fun fmt(n: Long) = when { n >= 1 shl 30 -> "%.1f Go".format(n / 1073741824.0); n >= 1 shl 20 -> "%.1f Mo".format(n / 1048576.0); n >= 1024 -> "${n / 1024} Ko"; else -> "$n o" }
    private val dateFmt get() = java.text.SimpleDateFormat("dd/MM/yy HH:mm", java.util.Locale.FRENCH)

    /** Résout un chemin relatif à la racine du stockage, en bloquant toute sortie (« .. ») hors de cette racine. */
    private fun fichier(chemin: String): File {
        val root = racine().canonicalFile
        val f = File(root, chemin.trim().trim('/')).canonicalFile
        if (!f.path.startsWith(root.path)) throw IllegalArgumentException("Chemin hors du stockage autorisé.")
        return f
    }

    private fun lister(f: File): String {
        if (!f.exists()) return "Introuvable : ${f.path}"
        if (!f.isDirectory) return "Ce n'est pas un dossier : ${f.path}"
        val l = (f.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (l.isEmpty()) return "(dossier vide)"
        return l.take(200).joinToString("\n") { x ->
            if (x.isDirectory) "📁 ${x.name}" else "📄 ${x.name} · ${fmt(x.length())} · ${dateFmt.format(java.util.Date(x.lastModified()))}"
        } + if (l.size > 200) "\n… (${l.size} éléments)" else ""
    }

    /** Actions : lister | lire | renommer | deplacer | supprimer | dossier. */
    fun action(action: String, chemin: String, destination: String): String {
        if (!autorise()) throw IllegalStateException("Accès « Tous les fichiers » non autorisé (Réglages › Téléphone et permissions).")
        val f = fichier(chemin)
        return when (action.lowercase()) {
            "lister" -> "📂 /${f.toRelativeString(racine())}\n" + lister(if (chemin.isBlank()) racine() else f)
            "lire" -> {
                if (!f.exists() || f.isDirectory) throw IllegalArgumentException("Fichier introuvable : ${chemin}")
                if (f.length() > MAX_LECTURE) throw IllegalStateException("Fichier trop gros pour être lu (${fmt(f.length())}).")
                val t = f.readText(Charsets.UTF_8)
                if (t.count { it == '�' } > 5) "Fichier binaire (${fmt(f.length())})." else "```\n" + t.take(8000) + "\n```"
            }
            "renommer", "deplacer" -> {
                if (!f.exists()) throw IllegalArgumentException("Introuvable : $chemin")
                val dest = destination.trim()
                if (dest.isBlank()) throw IllegalArgumentException("Précise le nouveau chemin/nom (paramètre « destination »).")
                val d = fichier(dest)
                d.parentFile?.mkdirs()
                if (d.exists()) throw IllegalStateException("« $dest » existe déjà.")
                if (!f.renameTo(d)) throw IllegalStateException("Renommage/déplacement refusé par le système.")
                "✅ /${chemin.trim('/')} → /${dest.trim('/')}"
            }
            "supprimer" -> {
                if (!f.exists()) throw IllegalArgumentException("Introuvable : $chemin")
                val ok = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (!ok) throw IllegalStateException("Suppression refusée par le système.")
                "🗑️ Supprimé : /${chemin.trim('/')}"
            }
            "dossier" -> {
                if (!f.mkdirs() && !f.isDirectory) throw IllegalStateException("Création du dossier refusée.")
                "📁 Dossier créé : /${chemin.trim('/')}"
            }
            else -> "Action inconnue (lister, lire, renommer, deplacer, supprimer, dossier)."
        }
    }
}
