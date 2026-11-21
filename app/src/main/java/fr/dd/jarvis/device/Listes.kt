package fr.dd.jarvis.device

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Listes à cocher (courses, bricolage, cadeaux…) : « ajoute du lait et des œufs à la liste de courses », « qu'est-ce
 * qu'il y a sur ma liste ? », « j'ai pris le lait », « enlève le pain », « vide la liste », « envoie la liste de courses
 * à Angeline ». Instantané (sans IA), gardé dans le téléphone et recopié dans Obsidian (JARVIS/Listes/<Nom>.md, cases
 * à cocher).
 */
object Listes {
    data class Element(val texte: String, val coche: Boolean = false, val ts: Long = System.currentTimeMillis())

    sealed class Intention {
        data class Ajouter(val liste: String, val elements: List<String>) : Intention()
        data class Retirer(val liste: String?, val elements: List<String>) : Intention()
        data class Cocher(val liste: String?, val elements: List<String>) : Intention()
        data class Lire(val liste: String?) : Intention()
        data class Vider(val liste: String?, val cochesSeulement: Boolean = false) : Intention()
        object Toutes : Intention()
        data class Envoyer(val liste: String?, val destinataire: String) : Intention()
    }

    const val DOSSIER = "JARVIS/Listes"
    private const val DEFAUT = "Courses"

    // ------------------------------------------------------------------ compréhension

    private const val POLI = "(?:(?:jarvis|ok|alors|bon|du coup|tu peux|peux tu|est ce que tu peux|pourrais tu|stp|s il te plait) )*"
    /** « liste de courses », « liste des courses », « liste bricolage », « liste pour Noël », « courses ». */
    private const val NOM = "(?:(?:ma |la |mes |les |notre |nos |une |un )?(?:liste(?: (?:de |des |du |d |pour (?:les |la |le |l )?)?([a-z][a-z\\s]{1,30}?))?|courses))"
    /** Ce qui n'est pas une liste à cocher : tâches, playlist, contacts, appareils… */
    private val PAS_LISTE = Regex("\\b(taches?|taches? a faire|choses? a faire|lecture|appareils?|peripheriques?|contacts?|entites?|noire|blanche|diffusion|attente|mails?|courriels?|" +
        "sms|messages?|rendez vous|rdv|rappels?|reveils?|alarmes?|evenements?|agendas?|calendriers?|outils?|capacites?|fonctions?|fonctionnalites?|applis?|applications?|" +
        "fichiers?|documents?|notes?|consignes?|clients?|factures?|devis|chansons?|musiques?|playlists?|films?|series?|videos?|photos?|chaines?|abonnements?|favoris|" +
        "ports?|redirections?|utilisateurs?|serveurs?|conteneurs?|cles?|modeles?|comptes?|derniers?|dernieres?|courses? a pied)\\b")

    private fun esp(m: String) = m.replace(" ?", "\\s*").replace(" ", "\\s+")
    private fun m(corps: String) = Regex(esp("^\\s*$POLI$corps(?: (?:stp|s il te plait|merci|jarvis))?\\s*$"))

    private val AJOUTER = listOf(
        m("(?:ajoute|rajoute|mets|met|mettre|note|inscris|ecris|place) (.+?) (?:a|sur|dans|aux|au|en) $NOM"),
        m("(?:il (?:me |nous )?faut (?:acheter|racheter|prendre)|faut (?:acheter|racheter)|(?:j|on) a besoin d acheter|pense a (?:acheter|racheter|prendre)|a acheter) (.+?)(?: (?:a|sur|dans) $NOM)?"),
    )
    private val RETIRER = m("(?:enleve|enlever|retire|retirer|supprime|supprimer|efface|effacer|raye|rayer|barre|barrer|vire|virer|ote) (.+?) (?:de|du|des|sur|dans) $NOM")
    private val COCHER = m("(?:coche|cocher|j ai (?:pris|achete|trouve|eu|recupere)|on a (?:pris|achete)|c est (?:pris|achete) pour|(?:j ai )?deja (?:pris|achete)) (.+?)(?: (?:de|sur|dans|pour) $NOM)?")
    private val LIRE = listOf(
        m("(?:(?:lis|lire|montre|affiche|donne|dis|rappelle)(?: moi)? |qu est ce qu il y a (?:sur|dans) |qu y a t il (?:sur|dans) |quoi (?:sur|dans) |c est quoi |quelle est |voir )?$NOM"),
        m("(?:qu est ce qu il (?:me |nous )?(?:faut acheter|manque|reste a acheter)|qu est ce que (?:je dois|j ai a|on doit) acheter|il (?:me |nous )?reste quoi a acheter|que dois je acheter)"),
    )
    private val VIDER = m("(?:vide|vider|efface|effacer|supprime|supprimer|remets a zero|reinitialise|nettoie|nettoyer) (?:toute )?$NOM")
    private val VIDER_COCHES = m("(?:enleve|retire|supprime|efface|vire|nettoie) (?:les (?:articles |elements |trucs )?coches|ce qui est coche|ce que j ai pris)(?: (?:de|sur|dans) $NOM)?")
    private val TOUTES = m("(?:quelles sont |montre moi |liste |affiche |donne moi )?(?:mes|les|tes) listes")
    private val ENVOYER = m("(?:envoie|envoyer|partage|partager|transmets|transmettre)(?: moi)? $NOM (?:a|au|par sms a|par message a) (.+)")

    /** Nom affichable de la liste (« courses » → « Courses »), null si ce n'est pas une liste à cocher. */
    internal fun nomListe(groupe: String?): String? {
        val g = groupe?.trim()?.replace(Regex("\\s+"), " ").orEmpty().replace(Regex("^(?:les|la|le|l|mes|tes|nos|vos|ses|mon|ma|ton|ta) "), "").trim()
        if (g.isBlank() || Regex("^(courses?|course)$").matches(g)) return DEFAUT
        if (PAS_LISTE.containsMatchIn(g)) return null
        return g.replaceFirstChar { it.uppercase() }
    }

    /** Articles d'un passage du texte d'origine : « du lait, des œufs et 2 baguettes » → [Lait, Œufs, 2 baguettes]. */
    internal fun articles(s: String): List<String> = s.split(Regex("\\s*(?:,|;|\\bet\\b|\\bpuis\\b|\\bainsi que\\b)\\s*", RegexOption.IGNORE_CASE))
        .map { a ->
            a.trim().replace(Regex("^(?:(?:du|de la|des|un|une|le|la|les|de|quelques|encore|aussi|un peu de|un paquet de)\\s+|(?:de l|l|d)['’]\\s*)+", RegexOption.IGNORE_CASE), "")
                .trim().trim('.', '!', '?', '"', '«', '»', ' ').replaceFirstChar { it.uppercase() }
        }.filter { it.isNotBlank() && it.length <= 60 }.distinct()

    /** Ce que la phrase demande aux listes, ou null. */
    fun intention(texte: String): Intention? {
        val o = texte.trim()
        if (o.isBlank() || o.length > 300) return null
        // plat garde la longueur : les passages se relisent dans le texte d'origine (accents, majuscules).
        val p = CommandesDirectes.plat(o)
        fun brut(r: MatchResult, i: Int): String? = r.groups[i]?.range?.let { o.substring(it.first, it.last + 1) }
        fun nom(r: MatchResult, i: Int): String? = r.groups[i]?.value?.let { nomListe(it) } ?: DEFAUT.takeIf { r.groups[i] == null }
        TOUTES.matchEntire(p)?.let { return Intention.Toutes }
        VIDER_COCHES.matchEntire(p)?.let { r -> return Intention.Vider(nom(r, 1) ?: return null, cochesSeulement = true) }
        VIDER.matchEntire(p)?.let { r -> return Intention.Vider(nom(r, 1) ?: return null) }
        ENVOYER.matchEntire(p)?.let { r -> return Intention.Envoyer(nom(r, 1) ?: return null, brut(r, 2)?.trim() ?: return null) }
        RETIRER.matchEntire(p)?.let { r ->
            val n = nom(r, 2) ?: return null
            return Intention.Retirer(n, articles(brut(r, 1) ?: return null)).takeIf { it.elements.isNotEmpty() }
        }
        for (a in AJOUTER) a.matchEntire(p)?.let { r ->
            val n = nom(r, 2) ?: return null
            val el = articles(brut(r, 1) ?: return null)
            if (el.isEmpty() || el.any { Regex("^(ca|cela|ceci|le tout)$").matches(normaliser(it)) }) return null
            return Intention.Ajouter(n, el)
        }
        COCHER.matchEntire(p)?.let { r ->
            val n = r.groups[2]?.value?.let { nomListe(it) ?: return null }
            return Intention.Cocher(n, articles(brut(r, 1) ?: return null)).takeIf { it.elements.isNotEmpty() }
        }
        LIRE[0].matchEntire(p)?.let { r -> return Intention.Lire(nom(r, 1) ?: return null) }
        LIRE[1].matchEntire(p)?.let { return Intention.Lire(DEFAUT) }
        return null
    }

    // ------------------------------------------------------------------ stockage

    private fun fichier(app: JarvisApp) = File(app.filesDir, "listes.json")

    @Synchronized
    internal fun charger(app: JarvisApp): LinkedHashMap<String, MutableList<Element>> {
        val out = LinkedHashMap<String, MutableList<Element>>()
        val o = runCatching { JSONObject(fichier(app).readText()) }.getOrNull() ?: return out
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Element(it.optString("t"), it.optBoolean("c"), it.optLong("ts")) }.toMutableList()
        }
        return out
    }

    @Synchronized
    private fun sauver(app: JarvisApp, l: Map<String, List<Element>>, modifiee: String?) {
        val o = JSONObject()
        l.forEach { (k, v) -> o.put(k, JSONArray().apply { v.forEach { e -> put(JSONObject().put("t", e.texte).put("c", e.coche).put("ts", e.ts)) } }) }
        runCatching { fr.dd.jarvis.data.Ecritures.ecrire(fichier(app)) { o.toString() } }
        // Copie Obsidian (cases à cocher), sans faire attendre la réponse.
        if (modifiee != null) {
            val md = markdown(modifiee, l[modifiee].orEmpty())
            Thread { runCatching { app.obsidian.saveIn(DOSSIER, modifiee, md) } }.start()
        }
    }

    internal fun markdown(nom: String, l: List<Element>): String = "---\ntype: liste-jarvis\ntags: [jarvis, liste]\n---\n\n# ${icone(nom)} $nom\n\n" +
        (if (l.isEmpty()) "_Liste vide._\n" else l.joinToString("\n") { "- [${if (it.coche) "x" else " "}] ${it.texte}" } + "\n")

    private fun icone(nom: String) = if (normaliser(nom).startsWith("course")) "🛒" else "📝"

    private fun cle(s: String) = normaliser(s).split(' ').joinToString(" ") { w -> if (w.length > 3 && (w.endsWith("s") || w.endsWith("x"))) w.dropLast(1) else w }

    /** Liste existante du même nom (sans tenir compte des accents / pluriels), sinon le nom tel quel. */
    private fun resoudre(l: Map<String, *>, nom: String) = l.keys.firstOrNull { cle(it) == cle(nom) } ?: nom

    private fun correspond(e: Element, cherche: String): Boolean {
        val a = cle(e.texte); val b = cle(cherche)
        return a == b || (b.length >= 3 && (a.contains(b) || b.contains(a)))
    }

    private fun texteListe(nom: String, l: List<Element>): String {
        if (l.isEmpty()) return "${icone(nom)} Ta liste $nom est vide."
        val reste = l.count { !it.coche }
        return "${icone(nom)} Liste $nom ($reste à prendre${if (reste != l.size) ", ${l.size - reste} pris" else ""}) :\n" +
            l.sortedBy { it.coche }.joinToString("\n") { (if (it.coche) "☑ " else "☐ ") + it.texte }
    }

    // ------------------------------------------------------------------ actions

    /** Exécute l'intention ; null = rien de pertinent (ex. « j'ai pris mes médicaments » sans article dans une liste). */
    suspend fun executer(app: JarvisApp, i: Intention, envoyerSms: (suspend (String, String) -> String)? = null): String? {
        val toutes = charger(app)
        return when (i) {
            is Intention.Ajouter -> {
                val nom = resoudre(toutes, i.liste)
                val l = toutes.getOrPut(nom) { mutableListOf() }
                val ajoutes = mutableListOf<String>(); val deja = mutableListOf<String>()
                for (a in i.elements) {
                    val idx = l.indexOfFirst { correspond(it, a) }
                    if (idx >= 0 && !l[idx].coche) deja += l[idx].texte
                    else { if (idx >= 0) l.removeAt(idx); l += Element(a); ajoutes += a }
                }
                sauver(app, toutes, nom)
                if (ajoutes.isNotEmpty()) fr.dd.jarvis.ai.Annulation.noterListe(nom, ajoutes)
                "${icone(nom)} " + (if (ajoutes.isNotEmpty()) "Ajouté à ta liste $nom : ${ajoutes.joinToString(", ") { it.lowercase() }}." else "") +
                    (if (deja.isNotEmpty()) " Déjà sur la liste : ${deja.joinToString(", ") { it.lowercase() }}." else "") +
                    " (${l.count { !it.coche }} à prendre)"
            }
            is Intention.Retirer, is Intention.Cocher -> {
                val (liste, elements) = if (i is Intention.Retirer) i.liste to i.elements else (i as Intention.Cocher).liste to i.elements
                val noms = liste?.let { listOf(resoudre(toutes, it)) } ?: toutes.keys.toList()
                val faits = mutableListOf<String>(); var nomTouche: String? = null
                for (a in elements) for (n in noms) {
                    val l = toutes[n] ?: continue
                    val idx = l.indexOfFirst { correspond(it, a) && (i is Intention.Retirer || !it.coche) }
                    if (idx < 0) continue
                    faits += l[idx].texte; nomTouche = n
                    if (i is Intention.Retirer) l.removeAt(idx) else l[idx] = l[idx].copy(coche = true)
                    break
                }
                if (faits.isEmpty()) return if (i is Intention.Cocher && liste == null) null
                    else "Je ne trouve pas ${elements.joinToString(", ")} sur ${liste?.let { "ta liste $it" } ?: "tes listes"}."
                sauver(app, toutes, nomTouche)
                val l = toutes[nomTouche].orEmpty()
                val reste = l.filter { !it.coche }
                (if (i is Intention.Retirer) "🗑️ Retiré de ta liste $nomTouche : " else "✅ Coché sur ta liste $nomTouche : ") + faits.joinToString(", ") { it.lowercase() } + ". " +
                    (if (reste.isEmpty()) "Plus rien à prendre 🎉" else "Reste : ${reste.joinToString(", ") { it.texte.lowercase() }}.")
            }
            is Intention.Lire -> {
                val nom = resoudre(toutes, i.liste ?: DEFAUT)
                texteListe(nom, toutes[nom].orEmpty())
            }
            is Intention.Vider -> {
                val nom = resoudre(toutes, i.liste ?: DEFAUT)
                val l = toutes[nom] ?: return "${icone(nom)} Ta liste $nom est déjà vide."
                val avant = l.size
                if (i.cochesSeulement) l.removeAll { it.coche } else l.clear()
                sauver(app, toutes, nom)
                if (i.cochesSeulement) "🧹 ${avant - l.size} article(s) pris retiré(s) de ta liste $nom. Reste ${l.size}."
                else "🗑️ Liste $nom vidée ($avant article${if (avant > 1) "s" else ""})."
            }
            Intention.Toutes -> if (toutes.isEmpty()) "📝 Aucune liste pour l'instant. Dis par exemple « ajoute du lait à la liste de courses »."
                else "📝 Tes listes :\n" + toutes.entries.joinToString("\n") { (k, v) -> "${icone(k)} $k — ${v.count { !it.coche }} à prendre" + if (v.any { it.coche }) ", ${v.count { it.coche }} pris" else "" }
            is Intention.Envoyer -> {
                val nom = resoudre(toutes, i.liste ?: DEFAUT)
                val reste = toutes[nom].orEmpty().filter { !it.coche }
                if (reste.isEmpty()) return "${icone(nom)} Ta liste $nom est vide : rien à envoyer."
                val message = "${icone(nom)} Liste $nom :\n" + reste.joinToString("\n") { "- ${it.texte}" }
                envoyerSms?.invoke(i.destinataire, message) ?: "Envoi impossible pour l'instant."
            }
        }
    }

    /** Retire des articles ajoutés (annulation). */
    fun retirerAjout(app: JarvisApp, liste: String, elements: List<String>): Int {
        val toutes = charger(app)
        val l = toutes[liste] ?: return 0
        val n = l.size
        l.removeAll { e -> elements.any { it == e.texte } && !e.coche }
        sauver(app, toutes, liste)
        return n - l.size
    }
}
