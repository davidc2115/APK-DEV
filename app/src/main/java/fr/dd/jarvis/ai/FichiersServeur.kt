package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp

/**
 * Fichiers du serveur Debian — ou d'un conteneur Docker du serveur — pour l'outil ssh_fichier : lecture par lignes
 * numérotées, recherche, modifications ciblées (remplacer un passage, insérer, ajouter, remplacer ou supprimer des
 * lignes), réécriture complète, restauration de la dernière sauvegarde.
 *
 * Une modification ciblée relit le fichier, l'applique ici, puis le réécrit (sauvegarde, droits conservés, sudo si
 * nécessaire, contenu vérifié) : l'IA n'a jamais à recopier un long fichier pour en changer trois lignes — c'est ce qui
 * faisait échouer les réécritures (fichier tronqué par les limites du modèle, ou réécrit de mémoire).
 */
object FichiersServeur {

    // ------------------------------------------------------------------ Modifications (pures, testables)

    data class Edition(val texte: String, val resume: String)

    private class Lignes(src: String) {
        val crlf = src.contains("\r\n")
        private val s = src.replace("\r\n", "\n")
        val finNL = s.isEmpty() || s.endsWith("\n") // un nouveau fichier se termine par un saut de ligne
        val l: MutableList<String> = if (s.isEmpty()) mutableListOf() else s.removeSuffix("\n").split("\n").toMutableList()
        fun texte(): String {
            val t = if (l.isEmpty()) "" else l.joinToString("\n") + (if (finNL) "\n" else "")
            return if (crlf) t.replace("\n", "\r\n") else t
        }
    }

    private fun lignesDe(contenu: String): List<String> {
        val c = contenu.replace("\r\n", "\n")
        return if (c.isEmpty()) emptyList() else c.removeSuffix("\n").split("\n")
    }

    /** « 12| texte » recopié depuis une lecture numérotée : on retire la numérotation. */
    private val NUMERO = Regex("^\\s*\\d+\\s?[|│:]\\s?")
    private fun sansNumeros(s: String): String? {
        val l = s.replace("\r\n", "\n").split("\n")
        if (l.none { it.isNotBlank() } || !l.filter { it.isNotBlank() }.all { NUMERO.containsMatchIn(it) }) return null
        return l.joinToString("\n") { it.replaceFirst(NUMERO, "") }
    }

    private fun occurrences(src: String, motif: String): List<Int> {
        val out = mutableListOf<Int>(); var i = src.indexOf(motif)
        while (i >= 0) { out += i; i = src.indexOf(motif, i + motif.length.coerceAtLeast(1)) }
        return out
    }

    private fun ligneDe(src: String, index: Int) = src.substring(0, index).count { it == '\n' } + 1

    /** Remplace un passage exact (ou, à défaut, le même passage à l'indentation / aux espaces de fin près). */
    fun remplacer(src: String, ancien0: String, nouveau0: String, tout: Boolean = false): Edition {
        require(ancien0.isNotEmpty()) { "« ancien » est vide : donne le passage exact à remplacer (lis d'abord le fichier avec action=lire ou chercher)." }
        val crlf = src.contains("\r\n")
        val s = src.replace("\r\n", "\n")
        val nouveau = nouveau0.replace("\r\n", "\n")
        fun fin(t: String) = if (crlf) t.replace("\n", "\r\n") else t
        for (ancien in listOfNotNull(ancien0.replace("\r\n", "\n"), sansNumeros(ancien0))) {
            val occ = occurrences(s, ancien)
            if (occ.size == 1 || (occ.size > 1 && tout)) {
                val nv = if (tout) s.replace(ancien, nouveau) else s.replaceFirst(ancien, nouveau)
                val lignes = occ.map { ligneDe(s, it) }
                return Edition(fin(nv), "${occ.size} passage${if (occ.size > 1) "s" else ""} remplacé${if (occ.size > 1) "s" else ""} (ligne${if (lignes.size > 1) "s" else ""} ${lignes.joinToString(", ")})")
            }
            if (occ.size > 1) throw IllegalArgumentException(
                "Le passage « ancien » apparaît ${occ.size} fois (lignes ${occ.map { ligneDe(s, it) }.joinToString(", ")}) : donne un passage plus long pour n'en viser qu'un, ou tout=oui pour tous les remplacer.")
        }
        // Tolérance : mêmes lignes à l'indentation et aux espaces de fin près (l'IA recopie souvent mal les espaces).
        fun simple(l: String) = l.trim().replace(Regex("\\s+"), " ")
        val cherche = lignesDe(sansNumeros(ancien0) ?: ancien0).map { simple(it) }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
        if (cherche.isNotEmpty()) {
            val f = Lignes(src)
            val fenetres = (0..f.l.size - cherche.size).filter { d -> cherche.indices.all { k -> simple(f.l[d + k]) == cherche[k] } }
            if (fenetres.size == 1 || (fenetres.size > 1 && tout)) {
                // Remplacement de la fin vers le début pour garder les indices valables.
                fenetres.sortedDescending().forEach { d ->
                    repeat(cherche.size) { f.l.removeAt(d) }
                    f.l.addAll(d, lignesDe(nouveau))
                }
                return Edition(f.texte(), "${fenetres.size} passage${if (fenetres.size > 1) "s" else ""} remplacé${if (fenetres.size > 1) "s" else ""} (ligne${if (fenetres.size > 1) "s" else ""} ${fenetres.map { it + 1 }.joinToString(", ")}, espaces ignorés)")
            }
            if (fenetres.size > 1) throw IllegalArgumentException(
                "Le passage « ancien » apparaît ${fenetres.size} fois (lignes ${fenetres.map { it + 1 }.joinToString(", ")}) : donne un passage plus long, ou tout=oui.")
        }
        val indice = cherche.firstOrNull { it.length >= 4 }?.let { premier ->
            Lignes(src).l.withIndex().filter { it.value.contains(premier.take(40), ignoreCase = true) }.take(5).joinToString("\n") { "${it.index + 1}| ${it.value.take(160)}" }
        }.orEmpty()
        throw IllegalArgumentException("Passage « ancien » introuvable dans le fichier (il doit être recopié exactement, sans numéros de ligne). " +
            if (indice.isNotBlank()) "Lignes proches :\n$indice" else "Relis le fichier (action=lire ou chercher) puis réessaie, ou utilise remplacer_lignes avec les numéros de ligne.")
    }

    /** Insère du contenu après la ligne contenant [apres], avant celle contenant [avant], ou à la ligne n° [ligne]. */
    fun inserer(src: String, contenu: String, apres: String? = null, avant: String? = null, ligne: Int? = null): Edition {
        require(contenu.isNotEmpty()) { "« contenu » est vide : rien à insérer." }
        val f = Lignes(src)
        val position = when {
            ligne != null -> { require(ligne in 1..f.l.size + 1) { "Ligne $ligne hors du fichier (1 à ${f.l.size + 1})." }; ligne - 1 }
            !apres.isNullOrBlank() || !avant.isNullOrBlank() -> {
                val repere = (apres ?: avant)!!.trim()
                val trouvees = f.l.withIndex().filter { it.value.contains(repere) }.map { it.index }
                    .ifEmpty { f.l.withIndex().filter { it.value.trim().contains(repere, ignoreCase = true) }.map { it.index } }
                if (trouvees.isEmpty()) throw IllegalArgumentException("Aucune ligne ne contient « ${repere.take(80)} ».")
                if (trouvees.size > 1) throw IllegalArgumentException("« ${repere.take(80)} » se trouve sur ${trouvees.size} lignes (${trouvees.take(12).joinToString(", ") { (it + 1).toString() }}) : précise le repère ou donne « ligne ».")
                if (!apres.isNullOrBlank()) trouvees[0] + 1 else trouvees[0]
            }
            else -> f.l.size
        }
        val ajout = lignesDe(contenu)
        f.l.addAll(position, ajout)
        return Edition(f.texte(), "${ajout.size} ligne${if (ajout.size > 1) "s" else ""} insérée${if (ajout.size > 1) "s" else ""} à partir de la ligne ${position + 1}")
    }

    fun ajouter(src: String, contenu: String): Edition = inserer(src, contenu, ligne = Lignes(src).l.size + 1).let {
        Edition(it.texte, it.resume.replace("insérée", "ajoutée") + " (fin du fichier)")
    }

    fun remplacerLignes(src: String, debut: Int, fin: Int, contenu: String): Edition {
        val f = Lignes(src)
        require(debut in 1..f.l.size && fin in debut..f.l.size) { "Lignes $debut à $fin hors du fichier (1 à ${f.l.size})." }
        repeat(fin - debut + 1) { f.l.removeAt(debut - 1) }
        val ajout = lignesDe(contenu)
        f.l.addAll(debut - 1, ajout)
        return Edition(f.texte(), "lignes $debut à $fin remplacées par ${ajout.size} ligne${if (ajout.size > 1) "s" else ""}")
    }

    fun supprimerLignes(src: String, debut: Int, fin: Int): Edition = remplacerLignes(src, debut, fin, "").let {
        Edition(it.texte, "lignes $debut à $fin supprimées")
    }

    /** Lecture numérotée (« 12| texte »), éventuellement d'une plage de lignes. */
    fun numeroter(src: String, debut: Int? = null, fin: Int? = null, maxCaracteres: Int = 12_000): String {
        val l = lignesDe(src)
        if (l.isEmpty()) return "(fichier vide)"
        val d = (debut ?: 1).coerceIn(1, l.size)
        val f = (fin ?: l.size).coerceIn(d, l.size)
        val sb = StringBuilder()
        var derniere = d - 1
        for (i in d..f) {
            val ligne = "${i.toString().padStart(4)}| ${l[i - 1]}\n"
            if (sb.length + ligne.length > maxCaracteres) break
            sb.append(ligne); derniere = i
        }
        if (derniere < f) sb.append("[… suite non affichée : lis la suite avec debut=${derniere + 1}]\n")
        return "(${l.size} lignes" + (if (d > 1 || f < l.size) ", lignes $d à ${minOf(f, derniere)} affichées" else "") + ")\n" + sb.toString().trimEnd()
    }

    fun chercher(src: String, motif: String, max: Int = 60): String {
        require(motif.isNotBlank()) { "« motif » est vide." }
        val l = lignesDe(src)
        val trouvees = l.withIndex().filter { it.value.contains(motif, ignoreCase = true) }
        if (trouvees.isEmpty()) return "Aucune ligne ne contient « $motif » (${l.size} lignes)."
        return "${trouvees.size} ligne${if (trouvees.size > 1) "s" else ""} contenant « $motif » :\n" +
            trouvees.take(max).joinToString("\n") { "${(it.index + 1).toString().padStart(4)}| ${it.value.take(300)}" } +
            (if (trouvees.size > max) "\n[… ${trouvees.size - max} autres]" else "")
    }

    /** Différences compactes avant / après (lignes retirées « - », ajoutées « + », avec leur numéro). */
    fun difference(avant: String, apres: String, max: Int = 40): String {
        val a = lignesDe(avant); val b = lignesDe(apres)
        var debut = 0
        while (debut < a.size && debut < b.size && a[debut] == b[debut]) debut++
        var fa = a.size; var fb = b.size
        while (fa > debut && fb > debut && a[fa - 1] == b[fb - 1]) { fa--; fb-- }
        if (debut == fa && debut == fb) return "(aucun changement)"
        val out = mutableListOf<String>()
        if (debut > 0) out += "  ${debut.toString().padStart(4)}| ${a[debut - 1].take(200)}"
        for (i in debut until fa) out += "- ${(i + 1).toString().padStart(4)}| ${a[i].take(200)}"
        for (i in debut until fb) out += "+ ${(i + 1).toString().padStart(4)}| ${b[i].take(200)}"
        if (fb < b.size) out += "  ${(fb + 1).toString().padStart(4)}| ${b[fb].take(200)}"
        return if (out.size <= max) out.joinToString("\n") else (out.take(max) + "[… ${out.size - max} lignes de différence en plus]").joinToString("\n")
    }

    // ------------------------------------------------------------------ Serveur / conteneur

    private fun q(s: String) = Serveur.q(s)

    /** Commande Docker : directe, puis avec sudo si le compte n'a pas accès au démon. */
    private fun docker(app: JarvisApp, cmd: String, delai: Int = 90): Pair<Int, String> {
        val r = Serveur.exec(app, cmd, delai)
        if (r.first != 0 && Regex("permission denied|docker\\.sock|connect to the docker daemon", RegexOption.IGNORE_CASE).containsMatchIn(r.second))
            return Serveur.exec(app, "sudo $cmd", delai)
        return r
    }

    private fun conteneurConnu(app: JarvisApp, c: String) {
        val r = docker(app, "docker inspect -f '{{.State.Running}}' ${q(c)}", 30)
        if (r.first != 0) {
            val noms = docker(app, "docker ps --format '{{.Names}}'", 30).second.lines().map { it.trim() }.filter { it.isNotBlank() }
            throw IllegalStateException("Conteneur « $c » introuvable." + if (noms.isNotEmpty()) " Conteneurs en marche : ${noms.joinToString(", ")}." else "")
        }
        if (!r.second.contains("true")) throw IllegalStateException("Le conteneur « $c » est arrêté : démarre-le (serveur_service) ou modifie le fichier côté serveur (dossier monté).")
    }

    fun lire(app: JarvisApp, chemin: String, conteneur: String = ""): String {
        if (conteneur.isBlank()) return Serveur.lire(app, chemin)
        conteneurConnu(app, conteneur)
        val r = docker(app, "docker exec ${q(conteneur)} cat -- ${q(chemin)}")
        if (r.first != 0) throw IllegalStateException("Lecture de $chemin dans le conteneur $conteneur impossible : ${r.second.trim().take(300)}")
        return r.second
    }

    fun lister(app: JarvisApp, dossier: String, conteneur: String = ""): String {
        if (conteneur.isBlank()) return Serveur.lister(app, dossier)
        conteneurConnu(app, conteneur)
        return docker(app, "docker exec ${q(conteneur)} ls -lA ${q(dossier.ifBlank { "/" })}").second.lines().take(150).joinToString("\n")
    }

    /** Écrit le fichier ; renvoie le chemin de la sauvegarde de l'ancienne version (null s'il n'existait pas). */
    fun ecrire(app: JarvisApp, chemin: String, contenu: String, conteneur: String = ""): String? {
        if (conteneur.isBlank()) return Serveur.ecrire(app, chemin, contenu)
        conteneurConnu(app, conteneur)
        val tmp = "/tmp/jarvis_up_${System.currentTimeMillis()}"
        val octets = contenu.toByteArray(Charsets.UTF_8)
        Serveur.envoyerOctets(app, tmp, octets)
        val bak = "$chemin.jarvis-bak-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        // Fichier existant : sauvegardé puis réécrit EN PLACE (propriétaire et droits conservés) ; dossier créé au besoin.
        val script = "if [ -e \"\$1\" ]; then cp -p \"\$1\" \"\$2\" && echo JARVIS_SAUVEGARDE; fi; mkdir -p \"\$(dirname \"\$1\")\" && cat > \"\$1\""
        val r = try { docker(app, "docker exec -i ${q(conteneur)} sh -c ${q(script)} sh ${q(chemin)} ${q(bak)} < ${q(tmp)}") }
            finally { runCatching { Serveur.exec(app, "rm -f ${q(tmp)}") } }
        if (r.first != 0) throw IllegalStateException("Écriture de $chemin dans le conteneur $conteneur refusée : ${r.second.replace("JARVIS_SAUVEGARDE", "").trim().take(300)}")
        val attendu = java.security.MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val v = docker(app, "docker exec ${q(conteneur)} sha256sum -- ${q(chemin)}", 30)
        if (v.first == 0 && !v.second.contains(attendu)) throw IllegalStateException("Vérification échouée : le contenu relu dans le conteneur ne correspond pas à ce qui a été écrit.")
        return if (r.second.contains("JARVIS_SAUVEGARDE")) bak else null
    }

    /** Dernière sauvegarde « .jarvis-bak-… » d'un fichier, ou null. */
    fun derniereSauvegarde(app: JarvisApp, chemin: String, conteneur: String = ""): String? {
        val cmd = "ls -1t -- ${q(if (conteneur.isBlank()) Serveur.absolu(app, chemin) else chemin)}.jarvis-bak-* 2>/dev/null | head -1"
        val r = if (conteneur.isBlank()) Serveur.exec(app, cmd) else docker(app, "docker exec ${q(conteneur)} sh -c ${q(cmd)}")
        return r.second.lines().map { it.trim() }.firstOrNull { it.contains(".jarvis-bak-") }
    }
}
