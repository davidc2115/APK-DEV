package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sites web créés par JARVIS et hébergés sur le serveur Debian : l'IA écrit un site complet (HTML / CSS / JS statiques),
 * Jarvis le dépose dans ~/jarvis-sites/<nom>, le sert avec un petit conteneur nginx (relancé au démarrage du serveur),
 * le met en ligne sur Internet par une redirection de port de la Freebox, puis le modifie à la demande — chaque
 * modification garde une version précédente restaurable.
 */
class SitesWebTools(private val app: JarvisApp, private val confirm: suspend (String, Boolean) -> Boolean) {
    companion object {
        val NAMES = setOf("site_web")
        private val MOTS = Regex("site (web|internet)|\\bsite\\b|page web|landing|vitrine|nginx|h[ée]berg|mettre en ligne|mise en ligne", RegexOption.IGNORE_CASE)
        fun concerned(t: String) = MOTS.containsMatchIn(t)
        private val portee = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val CONSIGNE_CREATION = """Tu es un développeur web expert. Crée un site web COMPLET, moderne, élégant et responsive, prêt à être servi tel quel par nginx :
uniquement des fichiers statiques (HTML, CSS, JavaScript) ; aucune compilation ni dépendance à installer (bibliothèques seulement par CDN si vraiment utile).
- index.html à la racine ; plusieurs pages si le sujet s'y prête, avec une navigation commune ; feuille de style partagée (css/style.css) et script (js/main.js) si utile.
- Contenu réel, précis et soigné en français (jamais de lorem ipsum), titres et méta-descriptions, accessibilité (contrastes, textes alternatifs), favicon en SVG intégré.
- Pas d'images binaires : illustrations en SVG intégré, dégradés CSS ou emojis. Formulaires : mailto: ou simple affichage (pas de serveur).
Réponds UNIQUEMENT par les fichiers, chacun COMPLET, dans ce format exact (rien d'autre) :
=== FICHIER: chemin/relatif.ext ===
contenu
=== FIN ==="""

        private const val CONSIGNE_MODIF = """Tu es un développeur web expert. Voici les fichiers actuels d'un site web statique (servi par nginx) et une demande de modification.
Applique la demande avec soin, en gardant le style, la cohérence et tout ce qui n'est pas concerné. Contenu en français.
Réponds UNIQUEMENT par les fichiers CRÉÉS ou MODIFIÉS, chacun COMPLET, dans ce format exact :
=== FICHIER: chemin/relatif.ext ===
contenu
=== FIN ===
Pour supprimer un fichier : une ligne === SUPPRIMER: chemin/relatif.ext ===
Si rien n'est à changer, réponds : === AUCUN ==="""

        /** Fichiers d'une réponse de l'IA (format === FICHIER … === FIN ===) + chemins à supprimer. Testable. */
        internal fun lireFichiers(texte: String): Pair<Map<String, String>, List<String>> {
            val f = linkedMapOf<String, String>()
            Regex("=== FICHIER: *(.+?) *===\\r?\\n([\\s\\S]*?)\\r?\\n=== FIN ===").findAll(texte).forEach { m ->
                val chemin = propre(m.groupValues[1]) ?: return@forEach
                var c = m.groupValues[2]
                Regex("^```[a-zA-Z]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\s*$").find(c.trim())?.let { c = it.groupValues[1] }
                f[chemin] = c
            }
            val suppr = Regex("=== SUPPRIMER: *(.+?) *===").findAll(texte).mapNotNull { propre(it.groupValues[1]) }.toList()
            return f to suppr
        }

        /** Chemin relatif sûr (pas de « .. », pas de chemin absolu). */
        internal fun propre(c: String): String? = c.trim().trim('`', '"', '\'').removePrefix("./").trimStart('/')
            .takeIf { it.isNotBlank() && !it.split('/').any { p -> p == ".." } && Regex("^[\\w./ -]+$").matches(it) }

        internal fun slug(nom: String) = fr.dd.jarvis.societe.core.normaliser(nom).replace(' ', '-').take(30).trim('-').ifBlank { "site" }

        private fun fichierMeta(app: JarvisApp) = File(app.filesDir, "sites_web.json")

        @Synchronized internal fun lireSites(app: JarvisApp): List<Site> = runCatching {
            val a = JSONArray(fichierMeta(app).readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Site(it.optString("slug"), it.optString("nom"), it.optString("dossier"), it.optInt("port"), it.optInt("portExterne"),
                it.optString("description"), it.optLong("creeLe"), it.optString("statut", "pret"), it.optString("erreur")) }
        }.getOrDefault(emptyList())

        @Synchronized internal fun ecrireSites(app: JarvisApp, l: List<Site>) {
            fichierMeta(app).writeText(JSONArray().also { a -> l.forEach { x -> a.put(JSONObject().put("slug", x.slug).put("nom", x.nom).put("dossier", x.dossier).put("port", x.port)
                .put("portExterne", x.portExterne).put("description", x.description).put("creeLe", x.creeLe).put("statut", x.statut).put("erreur", x.erreur)) } }.toString(1))
        }

        /** Demande sur les sites reconnue sans IA (évite qu'une IA fabrique le site ailleurs que sur le serveur). */
        fun intention(app: JarvisApp, texte: String): JSONObject? = intention(texte, lireSites(app).map { it.nom })

        internal fun intention(texte: String, noms: List<String>): JSONObject? {
            if (texte.length > 1500) return null
            val t = fr.dd.jarvis.societe.core.normaliser(texte).removePrefix("jarvis ").trim()
            if (!Regex("\\b(sites?|page web|landing)\\b").containsMatchIn(t)) return null
            val cite = noms.filter { n -> slug(n).replace('-', ' ').let { it.length >= 3 && t.contains(it) } }.maxByOrNull { it.length }
            fun avecNom(o: JSONObject) = o.also { if (cite != null) it.put("nom", cite) }
            return when {
                Regex("^(montre|liste|affiche|quels sont|donne moi)\\b.{0,30}\\bsites\\b").containsMatchIn(t) -> JSONObject().put("action", "lister")
                Regex("\\b(ou en est|est il pret|est pret|retrouve|lien|adresse|url)\\b").containsMatchIn(t) && !Regex("^(cree|fais|fabrique)").containsMatchIn(t) -> avecNom(JSONObject().put("action", "voir"))
                Regex("\\ben ligne\\b|\\bpublie|accessible (depuis|sur) (internet|l exterieur)").containsMatchIn(t) && !Regex("^(cree|creer|fais|fabrique|construis|genere|realise|developpe)").containsMatchIn(t) ->
                    avecNom(JSONObject().put("action", if (Regex("\\b(retire|enleve|plus en ligne|hors ligne)\\b").containsMatchIn(t)) "retirer_en_ligne" else "mettre_en_ligne"))
                Regex("^(supprime|efface|detruis)\\b").containsMatchIn(t) -> avecNom(JSONObject().put("action", "supprimer"))
                Regex("^(cree|creer|fais|fabrique|construis|genere|realise|monte|developpe|code|je veux|je voudrais|j aimerais)\\b.{0,40}\\b(site|page web|landing)").containsMatchIn(t) && cite == null -> {
                    if (t.split(' ').size < 6) return null   // trop vague : l'IA pose ses questions
                    val sujet = Regex("(?:site|page)(?: web| internet)?(?: vitrine)? (?:pour|de|du|des|sur) (.{3,45}?)(?:[,.;!]| avec | qui | en | et |$)").find(t)?.groupValues?.get(1)
                    JSONObject().put("action", "creer").put("description", texte.trim()).put("nom", "Site " + (sujet ?: t.split(' ').drop(3).take(4).joinToString(" ")))
                }
                (cite != null || noms.size == 1) && Regex("^(modifie|change|ajoute|enleve|retire|remplace|corrige|mets a jour|ameliore|refais|traduis|dans le site)\\b").containsMatchIn(t) ->
                    JSONObject().put("action", "modifier").put("nom", cite ?: noms.single()).put("consigne", texte.trim())
                else -> null
            }
        }
    }

    internal data class Site(val slug: String, val nom: String, val dossier: String, val port: Int, val portExterne: Int = 0, val description: String = "",
                             val creeLe: Long = 0, val statut: String = "pret", val erreur: String = "")

    private fun sites(): List<Site> = lireSites(app)
    private fun sauver(s: Site) = ecrireSites(app, lireSites(app).filter { it.slug != s.slug } + s)
    private fun retirer(slug: String) = ecrireSites(app, lireSites(app).filter { it.slug != slug })

    private fun trouver(nom: String): Site? {
        val l = sites(); val n = slug(nom)
        return l.firstOrNull { it.slug == n } ?: l.firstOrNull { it.slug.contains(n) || n.contains(it.slug) }
            // « mon site », « le site », nom vide : le seul site, sinon le plus récent.
            ?: (if (nom.isBlank() || Regex("^(mon |le |ce )?site( web| internet)?$").matches(fr.dd.jarvis.societe.core.normaliser(nom))) l.maxByOrNull { it.creeLe } else null)
    }

    private fun root() = app.store.sshUser.value.trim() == "root" || app.store.sshPass.value.isNotBlank()
    private fun su(cmd: String) = if (root()) "sudo $cmd" else cmd

    private fun prop(d: String) = JSONObject().put("type", "string").put("description", d)

    val schema = listOf(JSONObject().put("type", "function").put("function", JSONObject().put("name", "site_web").put("description",
        "SITES WEB créés et hébergés par JARVIS sur le serveur Debian : creer (l'IA écrit un site complet à partir de la description, Jarvis le dépose sur le serveur et le sert avec nginx) | modifier (n'importe quel changement demandé en français) | lister | voir | mettre_en_ligne (accessible depuis Internet : redirection de port de la Freebox) | retirer_en_ligne | arreter | demarrer | versions | restaurer_version | supprimer. Les créations et modifications prennent 1 à 3 minutes : Jarvis prévient quand c'est prêt.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("action", prop("creer | modifier | lister | voir | mettre_en_ligne | retirer_en_ligne | arreter | demarrer | versions | restaurer_version | supprimer"))
            .put("nom", prop("Nom du site (ex. « site entreprise PV »)."))
            .put("description", prop("creer : ce que doit être le site (sujet, pages, sections, style, couleurs, coordonnées…) — la demande complète de l'utilisateur."))
            .put("consigne", prop("modifier : ce qu'il faut changer, en français."))
            .put("port_externe", prop("mettre_en_ligne : port public souhaité (défaut : le même que le port du site)."))
            .put("version", prop("restaurer_version : nom ou date de la version (voir versions).")))
            .put("required", JSONArray().put("action")))))

    suspend fun execute(name: String, a: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (!Serveur.sshConfigure(app)) return@withContext ToolResult("Serveur SSH non configuré (Réglages › Maison › Serveur) : impossible d'héberger un site.")
            val action = fr.dd.jarvis.societe.core.normaliser(a.optString("action")).replace(' ', '_')
            when (action) {
                "creer" -> creer(a)
                "modifier" -> modifier(a)
                "lister" -> ToolResult(sites().joinToString("\n", "# 🌐 Sites web\n") { s -> "- **${s.nom}** · ${statut(s)} · ${adresseLocale(s)}" + (if (s.portExterne > 0) " · en ligne : ${adressePublique(s)}" else "") + " · ${s.dossier}" }
                    .takeIf { sites().isNotEmpty() } ?: "Aucun site créé. Dis par exemple « crée un site vitrine pour mon entreprise de maintenance photovoltaïque ».")
                "voir" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site « ${a.optString("nom")} » introuvable.")
                    val r = Serveur.exec(app, "cd ${Serveur.q(s.dossier)} && find . -type f | sort | head -80; echo; du -sh .; " + su("docker ps -a --filter name=^/jarvis-site-${s.slug}\$ --format '{{.Status}}'"), 30)
                    ToolResult("# 🌐 ${s.nom}\n- État : ${statut(s)}\n- Adresse à la maison : ${adresseLocale(s)}\n" + (if (s.portExterne > 0) "- En ligne : ${adressePublique(s)}\n" else "- Pas encore en ligne sur Internet (dis « mets le site en ligne »)\n") +
                        "- Dossier : ${s.dossier}\n```\n${r.second.trim().take(4000)}\n```")
                }
                "mettre_en_ligne" -> enLigne(a)
                "retirer_en_ligne" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site introuvable.")
                    if (s.portExterne <= 0) return@withContext ToolResult("« ${s.nom} » n'est pas en ligne.")
                    val r = FreeboxOs.gerer(app, "supprimer_redirection", JSONObject().put("port", s.portExterne.toString()), confirm)
                    sauver(s.copy(portExterne = 0))
                    ToolResult("🔒 « ${s.nom} » n'est plus accessible depuis Internet (il reste visible à la maison).\n$r")
                }
                "arreter", "demarrer" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site introuvable.")
                    val r = Serveur.exec(app, su("docker ${if (action == "arreter") "stop" else "start"} jarvis-site-${s.slug}"), 60)
                    ToolResult((if (r.first == 0) "✅ " else "❌ ") + "« ${s.nom} » ${if (action == "arreter") "arrêté" else "démarré"}." + (if (r.first != 0) "\n${r.second.take(300)}" else ""))
                }
                "versions" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site introuvable.")
                    ToolResult("```\n" + Serveur.exec(app, "ls -1t ${Serveur.q(File(s.dossier).parent + "/.versions")}/${s.slug}-*.tar.gz 2>/dev/null | head -20 | xargs -r -n1 basename || echo 'Aucune version.'", 20).second.ifBlank { "Aucune version." } + "\n```")
                }
                "restaurer_version" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site introuvable.")
                    val v = a.optString("version").trim()
                    val versions = Serveur.exec(app, "ls -1t ${Serveur.q(File(s.dossier).parent + "/.versions")}/${s.slug}-*.tar.gz 2>/dev/null", 20).second.lines().filter { it.isNotBlank() }
                    val choisie = (if (v.isBlank() || fr.dd.jarvis.societe.core.normaliser(v).startsWith("prec") || fr.dd.jarvis.societe.core.normaliser(v).startsWith("dern")) versions.firstOrNull()
                        else versions.firstOrNull { it.contains(v.replace(Regex("[^0-9A-Za-z-]"), "")) }) ?: return@withContext ToolResult("Version introuvable. Versions :\n" + versions.joinToString("\n") { File(it).name })
                    if (!confirm("Remettre « ${s.nom} » dans sa version ${File(choisie).name} ? (la version actuelle est gardée)", false)) return@withContext ToolResult("Annulé.")
                    sauvegarderVersion(s)
                    val r = Serveur.exec(app, "cd ${Serveur.q(s.dossier)} && find . -mindepth 1 -delete && tar -xzf ${Serveur.q(choisie)} -C ${Serveur.q(s.dossier)} && echo ok", 120)
                    ToolResult(if (r.second.contains("ok")) "✅ « ${s.nom} » restauré (${File(choisie).name}) : ${adresseLocale(s)}" else "❌ ${r.second.take(300)}")
                }
                "supprimer" -> {
                    val s = trouver(a.optString("nom")) ?: return@withContext ToolResult("Site introuvable.")
                    if (!confirm("Supprimer le site « ${s.nom} » (conteneur arrêté, fichiers déplacés dans les versions, plus en ligne) ?", true)) return@withContext ToolResult("Annulé.")
                    sauvegarderVersion(s)
                    Serveur.exec(app, su("docker rm -f jarvis-site-${s.slug}") + "; rm -rf ${Serveur.q(s.dossier)}", 60)
                    if (s.portExterne > 0) runCatching { FreeboxOs.gerer(app, "supprimer_redirection", JSONObject().put("port", s.portExterne.toString()), confirm) }
                    retirer(s.slug)
                    ToolResult("🗑️ Site « ${s.nom} » supprimé (dernière version gardée dans ${File(s.dossier).parent}/.versions).")
                }
                else -> ToolResult("Action inconnue.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { ToolResult("❌ Site web : ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun ipLocale(): String = app.store.serveurIpLocale.value.takeIf { Serveur.ipPrivee(it) }
        ?: Serveur.ipLocaleServeur(app)?.also { app.store.serveurIpLocale.set(it) } ?: app.store.sshHost.value.trim()
    private fun adresseLocale(s: Site) = "http://${ipLocale()}:${s.port}"
    private fun adressePublique(s: Site): String {
        val ip = runCatching { Serveur.exec(app, "curl -s -m 5 https://api.ipify.org || curl -s -m 5 https://ifconfig.me", 15).second.trim().lines().last() }.getOrDefault("")
            .takeIf { Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(it) } ?: app.store.sshHostDistant.value.ifBlank { "<IP publique>" }
        return "http://$ip:${s.portExterne}"
    }

    private fun sauvegarderVersion(s: Site) {
        val versions = File(s.dossier).parent + "/.versions"
        Serveur.exec(app, "mkdir -p ${Serveur.q(versions)} && tar -czf ${Serveur.q(versions)}/${s.slug}-\$(date +%Y%m%d-%H%M%S).tar.gz -C ${Serveur.q(s.dossier)} . 2>/dev/null; " +
            "ls -1t ${Serveur.q(versions)}/${s.slug}-*.tar.gz 2>/dev/null | tail -n +16 | xargs -r rm -f", 120)
    }

    private fun deposer(s: Site, fichiers: Map<String, String>, suppr: List<String>) {
        val dossiers = fichiers.keys.mapNotNull { File(it).parent }.distinct()
        Serveur.exec(app, "mkdir -p ${Serveur.q(s.dossier)}" + dossiers.joinToString("") { " ${Serveur.q(s.dossier + "/" + it)}" }, 20)
        fichiers.forEach { (chemin, contenu) -> Serveur.envoyerOctets(app, "${s.dossier}/$chemin", contenu.toByteArray()) }
        if (suppr.isNotEmpty()) Serveur.exec(app, "cd ${Serveur.q(s.dossier)} && rm -f " + suppr.joinToString(" ") { Serveur.q(it) }, 20)
        Serveur.exec(app, "chmod -R a+rX ${Serveur.q(s.dossier)}", 20)
    }

    /** Conteneur nginx du site (créé s'il n'existe pas) ; renvoie le compte rendu. */
    private fun servir(s: Site): String {
        val r = Serveur.exec(app, su("bash -c " + Serveur.q(
            "docker inspect jarvis-site-${s.slug} >/dev/null 2>&1 && { docker start jarvis-site-${s.slug} >/dev/null; echo existe; exit 0; }; " +
            "command -v docker >/dev/null || { echo 'DOCKER ABSENT'; exit 1; }; " +
            "docker run -d --name jarvis-site-${s.slug} --restart unless-stopped -p ${s.port}:80 -v ${s.dossier}:/usr/share/nginx/html:ro nginx:alpine >/dev/null && echo cree")), 300)
        return r.second
    }

    private fun portLibre(): Int {
        val pris = Serveur.exec(app, "ss -ltnH 2>/dev/null | awk '{print \$4}' | sed 's/.*://'", 20).second.lines().mapNotNull { it.trim().toIntOrNull() }.toSet() + sites().map { it.port }
        return (8081..8199).first { it !in pris }
    }

    private fun prevenir(titre: String, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { app.chatAjouter(ChatMsg(false, "🌐 **$titre**\n\n$message")) }
        runCatching { fr.dd.jarvis.work.SuiviTaches.termine(app, 7700 + (System.currentTimeMillis() % 200).toInt(), titre.take(60), message.take(300)) }
    }

    private fun statut(s: Site) = when {
        s.statut == "creation" && System.currentTimeMillis() - s.creeLe > 15 * 60_000L -> "❌ création interrompue (redemande-la)"
        s.statut == "creation" -> "⏳ en cours de création"
        s.statut == "echec" -> "❌ échec : ${s.erreur.take(160)}"
        else -> "✅ prêt"
    }

    /** Pages du site : d'un seul coup, sinon fichier par fichier (réponse trop longue ou coupée). */
    private suspend fun generer(nom: String, description: String): Map<String, String> {
        val (fichiers, _) = lireFichiers(app.llm.redigerLong(CONSIGNE_CREATION, "Nom du site : $nom\nDemande : $description"))
        if (fichiers.keys.any { it.endsWith(".html") } && fichiers.values.all { it.isNotBlank() }) return fichiers
        val plan = app.llm.agent("Tu prépares un site web statique (HTML, CSS, JavaScript, servi par nginx). Donne uniquement la liste de ses fichiers, un chemin relatif par ligne, " +
            "index.html en premier, de 3 à 7 fichiers (pages .html, css/style.css, js/main.js si utile). Rien d'autre.", "Site : $nom\nDemande : $description")
        val chemins = plan.lines().mapNotNull { propre(it.trim().trimStart('-', '*', '•', ' ').substringBefore(' ')) }.filter { it.contains('.') }.distinct().take(8)
            .let { l -> if ("index.html" in l) l else listOf("index.html") + l }
        val out = linkedMapOf<String, String>()
        for (c in chemins) {
            val r = app.llm.redigerLong("Tu écris UN fichier d'un site web statique moderne et responsive, contenu réel et soigné en français (jamais de lorem ipsum), " +
                "cohérent avec les autres fichiers du site (mêmes noms de pages, de classes CSS et de liens). Réponds UNIQUEMENT par le contenu complet du fichier, sans explication.",
                "Site : $nom\nDemande : $description\nFichiers du site : ${chemins.joinToString()}\nÉcris maintenant le fichier : $c")
            out[c] = Regex("^```[a-zA-Z]*\\r?\\n([\\s\\S]*?)\\r?\\n```\\s*$").find(r.trim())?.groupValues?.get(1) ?: r.trim()
        }
        return out
    }

    private fun creer(a: JSONObject): ToolResult {
        val description = a.optString("description").trim().ifBlank { a.optString("consigne").trim() }
        val nom = a.optString("nom").trim().ifBlank { description.take(40).ifBlank { "Mon site" } }
        if (description.length < 10) return ToolResult("Décris le site : sujet, pages et sections, style, couleurs, coordonnées à afficher…")
        if (trouver(nom)?.slug == slug(nom)) return ToolResult("Un site « $nom » existe déjà : dis « modifie le site $nom … » pour le changer, ou donne un autre nom.")
        val home = Serveur.exec(app, "echo \$HOME", 15).second.trim().lines().last()
        val site = Site(slug(nom), nom, "$home/jarvis-sites/${slug(nom)}", portLibre(), description = description.take(500), creeLe = System.currentTimeMillis(), statut = "creation")
        sauver(site)   // visible tout de suite (« où en est mon site ? »)
        val publier = Regex("en ligne|publi|internet|accessible", RegexOption.IGNORE_CASE).containsMatchIn(description)
        portee.launch {
            val compte = try {
                val fichiers = generer(nom, description)
                if (fichiers.isEmpty() || fichiers.keys.none { it.endsWith(".html") }) throw IllegalStateException("l'IA n'a pas renvoyé de fichiers de site exploitables, réessaie en reformulant")
                val complet = if ("index.html" !in fichiers) fichiers + ("index.html" to fichiers.entries.first { it.key.endsWith(".html") }.value) else fichiers
                deposer(site, complet, emptyList())
                val r = servir(site)
                if (r.contains("DOCKER ABSENT")) throw IllegalStateException("Docker n'est pas installé sur le serveur (nécessaire pour servir le site)")
                // Vérification réelle : la page répond-elle sur le serveur ?
                val code = Serveur.exec(app, "sleep 2; curl -s -o /dev/null -w '%{http_code}' -m 8 http://localhost:${site.port}/", 30).second.trim().takeLast(3)
                sauver(site.copy(statut = "pret"))
                var texte = "Site « $nom » créé (${complet.size} fichiers : ${complet.keys.take(8).joinToString()})" +
                    (if (code == "200") " et il répond sur le serveur ✅" else " — le serveur répond « $code » : vérifie avec « montre le site $nom »") +
                    ".\n\n👉 À la maison : ${adresseLocale(site)}"
                if (publier) texte += "\n\n" + runCatching { enLigne(JSONObject().put("nom", nom)).text }.getOrElse { "⚠️ Mise en ligne impossible : ${it.message}" }
                else texte += "\n\nPour le rendre accessible depuis Internet : « mets le site $nom en ligne »."
                texte + "\nPour le changer : « modifie le site $nom : … »."
            } catch (e: Exception) {
                sauver(site.copy(statut = "echec", erreur = e.message ?: e.javaClass.simpleName))
                "❌ Création du site « $nom » impossible : ${e.message}"
            }
            prevenir(if (compte.startsWith("❌")) "Site web : échec" else "Site web prêt", compte)
        }
        return ToolResult("⏳ Je crée le site « $nom » (1 à 3 minutes) : l'IA écrit les pages, puis je les dépose sur ton serveur et je le démarre sur le port ${site.port}. Je te préviens ici dès qu'il est prêt.")
    }

    private fun modifier(a: JSONObject): ToolResult {
        val s = trouver(a.optString("nom")) ?: return ToolResult("Site « ${a.optString("nom")} » introuvable : " + sites().joinToString { it.nom }.ifBlank { "aucun site créé" })
        val consigne = a.optString("consigne").trim().ifBlank { a.optString("description").trim() }
        if (consigne.isBlank()) return ToolResult("Que faut-il changer sur « ${s.nom} » ?")
        portee.launch {
            val compte = runCatching {
                val actuel = Serveur.exec(app, "cd ${Serveur.q(s.dossier)} && find . -type f \\( -name '*.html' -o -name '*.css' -o -name '*.js' -o -name '*.json' -o -name '*.svg' -o -name '*.txt' -o -name '*.md' -o -name '*.xml' \\) -size -300k | sort | " +
                    "while read f; do echo \"=== FICHIER: \${f#./} ===\"; cat \"\$f\"; echo; echo '=== FIN ==='; done", 60).second
                val rep = app.llm.redigerLong(CONSIGNE_MODIF, "Site « ${s.nom} ». Demande : $consigne\n\nFichiers actuels :\n" + actuel.take(160_000))
                if (rep.contains("=== AUCUN ===")) return@runCatching "Rien à changer sur « ${s.nom} » pour cette demande."
                val (fichiers, suppr) = lireFichiers(rep)
                if (fichiers.isEmpty() && suppr.isEmpty()) throw IllegalStateException("l'IA n'a pas renvoyé de modification exploitable, reformule la demande")
                sauvegarderVersion(s)
                deposer(s, fichiers, suppr)
                servir(s)
                "Site « ${s.nom} » modifié : " + (fichiers.keys.toList() + suppr.map { "🗑️ $it" }).joinToString() + ".\n\n👉 ${adresseLocale(s)}" +
                    (if (s.portExterne > 0) " · en ligne : ${adressePublique(s)}" else "") + "\n(La version précédente est gardée : « reviens à la version précédente du site ${s.nom} ».)"
            }.getOrElse { "❌ Modification du site « ${s.nom} » impossible : ${it.message}" }
            prevenir(if (compte.startsWith("❌")) "Site web : échec" else "Site web modifié", compte)
        }
        return ToolResult("⏳ Je modifie le site « ${s.nom} » (1 à 3 minutes) ; je garde la version actuelle et je te préviens ici dès que c'est fait.")
    }

    private suspend fun enLigne(a: JSONObject): ToolResult {
        val s = trouver(a.optString("nom")) ?: return ToolResult("Site « ${a.optString("nom")} » introuvable.")
        val externe = a.optString("port_externe").filter { it.isDigit() }.toIntOrNull() ?: s.port
        if (!confirm("Rendre le site « ${s.nom} » accessible depuis Internet (port $externe de la box → serveur ${ipLocale()}:${s.port}) ?", true)) return ToolResult("Annulé.")
        if (app.store.freeboxAppToken.value.isBlank())
            return ToolResult("La Freebox n'est pas associée à Jarvis : ouvre toi-même le port $externe (TCP) de ta box vers ${ipLocale()}:${s.port}, ou associe la Freebox dans Réglages › Maison › Box. Le site sera alors sur ${adressePublique(s.copy(portExterne = externe))}.")
        val r = FreeboxOs.gerer(app, "ajouter_redirection", JSONObject().put("ip", ipLocale()).put("port", s.port.toString()).put("port_externe", externe.toString())
            .put("protocole", "tcp").put("commentaire", "Site JARVIS ${s.nom}".take(60)), confirm)
        if (r.contains("✅")) sauver(s.copy(portExterne = externe))
        return ToolResult(r + if (r.contains("✅")) "\n\n🌍 « ${s.nom} » est en ligne : ${adressePublique(s.copy(portExterne = externe))}\n(Pour une adresse avec un nom de domaine et le cadenas HTTPS, il faudra un nom de domaine : demande-le-moi.)" else "")
    }
}
