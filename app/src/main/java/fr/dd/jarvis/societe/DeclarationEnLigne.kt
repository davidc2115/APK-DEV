package fr.dd.jarvis.societe

import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Déclarations faites EN LIGNE par JARVIS, sur les sites officiels (URSSAF, impôts, net-entreprises…), dans un navigateur
 * intégré : JARVIS se connecte avec les identifiants enregistrés, va à la bonne page, remplit les montants qu'il a
 * calculés (onglet Déclarations) et, avant le bouton final (envoyer, signer, payer), montre le récapitulatif et attend
 * l'accord — sauf si l'option « valider sans me demander » est activée.
 *
 * Sécurité : les identifiants sont chiffrés sur le téléphone (Android Keystore) ; ils ne sont JAMAIS envoyés à une IA —
 * seulement tapés, par le téléphone lui-même, dans la page du site officiel, et uniquement si l'adresse de la page est
 * bien celle de ce site (https). L'IA ne voit que le texte de la page et la liste des boutons / champs (sans leur contenu,
 * sauf les montants).
 */
object DeclarationEnLigne {
    data class Site(val cle: String, val nom: String, val hotes: List<String>, val accueil: String, val identifiant: String)

    /** Sites reconnus (le premier qui correspond à l'adresse de la page). */
    val SITES = listOf(
        Site("urssaf_ae", "URSSAF — auto-entrepreneur", listOf("autoentrepreneur.urssaf.fr"), "https://www.autoentrepreneur.urssaf.fr", "Adresse mail ou n° SIRET"),
        Site("urssaf", "URSSAF — indépendants et employeurs", listOf("urssaf.fr"), "https://www.urssaf.fr", "Adresse mail ou identifiant"),
        Site("impots_pro", "Impôts — espace professionnel", listOf("cfspro.impots.gouv.fr", "cfspro-idp.impots.gouv.fr"), "https://cfspro.impots.gouv.fr", "Adresse électronique"),
        Site("impots_part", "Impôts — espace particulier", listOf("cfspart.impots.gouv.fr", "cfspart-idp.impots.gouv.fr"), "https://cfspart.impots.gouv.fr", "Numéro fiscal (13 chiffres)"),
        Site("net_entreprises", "net-entreprises (DSN)", listOf("net-entreprises.fr"), "https://www.net-entreprises.fr", "SIRET + nom + prénom (voir le site)"),
        Site("guichet", "Guichet unique des entreprises", listOf("formalites.entreprises.gouv.fr", "procedures.inpi.fr"), "https://formalites.entreprises.gouv.fr", "Adresse mail"),
    )

    /** Domaines officiels où JARVIS a le droit de naviguer. */
    private val OFFICIELS = listOf("urssaf.fr", "gouv.fr", "net-entreprises.fr", "inpi.fr", "service-public.fr", "ameli.fr", "msa.fr")

    private fun hote(url: String) = runCatching { java.net.URI(url).host.orEmpty().lowercase() }.getOrDefault("")
    private fun correspond(h: String, domaine: String) = h == domaine || h.endsWith(".$domaine")

    fun officiel(url: String) = url.startsWith("https://") && hote(url).let { h -> OFFICIELS.any { correspond(h, it) } }

    /** Site (et donc identifiants) de la page : seulement en https et sur l'adresse exacte du site. */
    fun site(url: String): Site? {
        if (!url.startsWith("https://")) return null
        val h = hote(url)
        return SITES.firstOrNull { s -> s.hotes.any { correspond(h, it) } }
    }

    // ------------------------------------------------------------------ identifiants (chiffrés)

    data class Identifiants(val login: String, val mdp: String)

    private fun tous(app: JarvisApp): JSONObject = runCatching { JSONObject(app.store.sitesIdentifiants.value.ifBlank { "{}" }) }.getOrDefault(JSONObject())

    fun identifiants(app: JarvisApp, cle: String): Identifiants? = tous(app).optJSONObject(cle)?.let { o ->
        Identifiants(o.optString("l"), o.optString("m")).takeIf { it.login.isNotBlank() || it.mdp.isNotBlank() }
    }

    fun enregistrer(app: JarvisApp, cle: String, login: String, mdp: String) {
        val o = tous(app)
        if (login.isBlank() && mdp.isBlank()) o.remove(cle) else o.put(cle, JSONObject().put("l", login.trim()).put("m", mdp))
        app.store.sitesIdentifiants.set(o.toString())
    }

    // ------------------------------------------------------------------ échéances faisables en ligne

    /** L'échéance se fait-elle sur un site officiel que JARVIS sait ouvrir ? */
    fun automatisable(e: Declarations.Echeance) = officiel(e.lien) && !e.id.startsWith("efacture") && !e.id.startsWith("seuils") && !e.id.startsWith("ag-")

    private val R_DEMANDE = Regex("^(?:(?:jarvis|ok|alors|est ce que tu peux|tu peux|peux tu|stp)\\s+)*(?:fais|faire|remplis|remplir|envoie|envoyer|depose|deposer|effectue|effectuer|occupe toi de|lance|declare|declarer|fais moi)\\s+(?:moi\\s+)?" +
        "(?:ma |mes |la |les |le |mon )?(?:declarations?|chiffre d affaires|tva|cfe|dsn|acompte|impots?|urssaf)\\b")

    /** « Fais ma déclaration URSSAF », « déclare mon chiffre d'affaires », « remplis ma déclaration de TVA » → échéance visée. */
    fun intention(texte: String, data: fr.dd.jarvis.societe.core.SocieteData, aujourdhui: LocalDate = LocalDate.now()): Declarations.Echeance? {
        val n = normaliser(texte)
        if (!R_DEMANDE.containsMatchIn(n) || Regex("\\b(quoi|quand|combien|comment|qu est ce|quel|quelle)\\b").containsMatchIn(n)) return null
        val faites = data.declarationsFaites.keys
        val possibles = Declarations.echeances(data, aujourdhui).filter { automatisable(it) && it.id !in faites }
        val mot: (Declarations.Echeance) -> Boolean = when {
            Regex("\\btva\\b").containsMatchIn(n) -> { e -> e.id.startsWith("tva") }
            Regex("\\bcfe\\b").containsMatchIn(n) -> { e -> e.id.startsWith("cfe") }
            Regex("\\bdsn\\b|\\bpaie\\b|salaires?").containsMatchIn(n) -> { e -> e.id.startsWith("dsn") }
            Regex("\\bacompte\\b|\\bis\\b").containsMatchIn(n) -> { e -> e.id.startsWith("is-") }
            Regex("\\bimpots? sur le revenu|\\brevenus\\b|2042").containsMatchIn(n) -> { e -> e.id.startsWith("ir-") || e.id.startsWith("tns-") }
            Regex("\\burssaf\\b|chiffre d affaires|cotisations").containsMatchIn(n) -> { e -> e.id.startsWith("urssaf") }
            else -> { _ -> true }
        }
        // La plus ancienne pas encore faite dont la période est terminée, sinon la prochaine.
        return possibles.filter(mot).let { l -> l.firstOrNull { !it.date.isAfter(aujourdhui.plusDays(31)) } ?: l.firstOrNull() }
    }

    // ------------------------------------------------------------------ scripts de la page

    /** Liste numérotée des éléments utilisables + texte visible (sans le contenu des champs, sauf les montants). */
    const val JS_LIRE = """(function(){
function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
function lab(e){var t='';
if(e.id){try{var l=document.querySelector('label[for="'+CSS.escape(e.id)+'"]');if(l)t=l.innerText;}catch(x){}}
if(!t&&e.closest&&e.closest('label'))t=e.closest('label').innerText;
if(!t)t=e.getAttribute('aria-label')||'';
if(!t&&e.getAttribute('aria-labelledby')){var x=document.getElementById(e.getAttribute('aria-labelledby'));if(x)t=x.innerText;}
if(!t)t=e.getAttribute('placeholder')||e.getAttribute('title')||'';
if(!t&&(e.tagName=='BUTTON'||e.tagName=='A'||e.getAttribute('role')))t=e.innerText||'';
if(!t&&e.tagName=='INPUT'&&(e.type=='submit'||e.type=='button'))t=e.value;
if(!t){var p=e.parentElement,k=0;while(p&&!t&&k<3){t=p.innerText||'';k++;p=p.parentElement;}}
if(!t)t=e.name||e.id||'';
return t.replace(/\s+/g,' ').trim().slice(0,90);}
var out=[],n=0;var els=document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem]');
for(var i=0;i<els.length&&n<140;i++){var e=els[i];if(e.type=='hidden'||e.disabled||!vis(e))continue;n++;e.setAttribute('data-jarvis',n);
var tag=e.tagName.toLowerCase(),ty=(e.type||'').toLowerCase();
var k=tag=='a'?'lien':tag=='select'?'liste':tag=='textarea'?'zone':(tag=='input'&&(ty=='checkbox'||ty=='radio'))?ty:(tag=='input'&&['submit','button','image','reset'].indexOf(ty)<0)?'champ':'bouton';
var o={i:n,k:k,l:lab(e)};
if(k=='champ'||k=='zone'){o.t=ty;if(ty=='password'){o.mdp=1;}else{var v=e.value||'';o.r=v.length>0;if(/^[\d\s.,€-]*$/.test(v))o.v=v;}}
if(k=='checkbox'||k=='radio')o.c=e.checked;
if(k=='liste'){o.v=e.selectedIndex>=0?e.options[e.selectedIndex].text:'';o.o=[].slice.call(e.options,0,15).map(function(x){return x.text;});}
out.push(o);}
var txt=(document.body?document.body.innerText:'').replace(/[ \t]+/g,' ').replace(/\n\s*\n+/g,'\n').slice(0,3500);
return JSON.stringify({u:location.href,ti:document.title,rs:document.readyState,x:txt,e:out});})();"""

    private fun q(s: String) = JSONObject.quote(s)

    private const val JS_OUTILS = "function el(n){return document.querySelector('[data-jarvis=\"'+n+'\"]');}" +
        "function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}" +
        "function poser(e,v){e.focus();var p=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(e,v);" +
        "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));e.dispatchEvent(new Event('blur',{bubbles:true}));}"

    fun jsCliquer(n: Int) = "(function(){$JS_OUTILS var e=el($n);if(!e)return 'absent';e.scrollIntoView({block:'center'});e.click();return 'ok';})();"
    fun jsSaisir(n: Int, texte: String) = "(function(){$JS_OUTILS var e=el($n);if(!e)return 'absent';if(e.type=='password')return 'refuse';e.scrollIntoView({block:'center'});poser(e,${q(texte)});return 'ok';})();"
    fun jsChoisir(n: Int, texte: String) = "(function(){$JS_OUTILS var e=el($n);if(!e||!e.options)return 'absent';var t=${q(texte)}.toLowerCase();" +
        "for(var i=0;i<e.options.length;i++){if(e.options[i].text.toLowerCase().indexOf(t)>=0){e.value=e.options[i].value;e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';}}return 'option absente';})();"
    fun jsCocher(n: Int, oui: Boolean) = "(function(){$JS_OUTILS var e=el($n);if(!e)return 'absent';if(e.checked!==$oui)e.click();return 'ok';})();"
    const val JS_DEFILER = "(function(){window.scrollBy(0,Math.round(window.innerHeight*0.8));return 'ok';})();"

    /**
     * Connexion : identifiant puis mot de passe (page à une ou deux étapes). Tapés par le téléphone, jamais par l'IA.
     * Renvoie « ok », « identifiant » (page d'identifiant seule, validée) ou la raison de l'échec.
     */
    fun jsConnexion(login: String, mdp: String) = "(function(){$JS_OUTILS" +
        "var pw=[].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];" +
        "var txt=[].slice.call(document.querySelectorAll('input')).filter(function(x){return vis(x)&&['text','email','tel','number',''].indexOf((x.type||'').toLowerCase())>=0;});" +
        "var u=null;if(pw){for(var i=0;i<txt.length;i++){if(txt[i].compareDocumentPosition(pw)&Node.DOCUMENT_POSITION_FOLLOWING)u=txt[i];}}else{u=txt.filter(function(x){var l=((x.name||'')+' '+(x.id||'')+' '+(x.getAttribute('aria-label')||'')+' '+(x.placeholder||'')+' '+(x.autocomplete||'')).toLowerCase();" +
        "return /ident|fiscal|spi|mail|courriel|login|user|siret|siren|numero|numéro/.test(l);})[0]||(txt.length==1?txt[0]:null);}" +
        "var L=${q(login)},M=${q(mdp)};if(!pw&&!u)return 'aucun champ de connexion';" +
        "if(u&&L&&!(pw&&u.value))poser(u,L);if(pw){if(!M)return 'mot de passe non enregistré';poser(pw,M);}" +
        "var c=pw||u;var f=c.form;var b=f?[].slice.call(f.querySelectorAll('button,input[type=submit]')).filter(vis).filter(function(x){return (x.type||'').toLowerCase()=='submit'||/connex|connect|valider|continuer|suivant|login/i.test(x.innerText||x.value||'');})[0]:null;" +
        "if(!b)b=[].slice.call(document.querySelectorAll('button,input[type=submit],a,[role=button]')).filter(vis).filter(function(x){return /^(se connecter|connexion|me connecter|valider|continuer|suivant|je me connecte)/i.test((x.innerText||x.value||'').trim());})[0];" +
        "setTimeout(function(){if(b)b.click();else if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}},350);" +
        "return pw?'ok':'identifiant';})();"

    // ------------------------------------------------------------------ IA : prochaine action

    data class Action(val action: String, val id: Int = 0, val texte: String = "", val recap: String = "", val pensee: String = "")

    fun consigne(e: Declarations.Echeance, s: fr.dd.jarvis.societe.core.Societe): String = """
Tu fais une démarche administrative EN LIGNE à la place de l'utilisateur, dans un navigateur, sur un site officiel français.
ENTREPRISE : ${s.nom.ifBlank { "(nom non renseigné)" }} — ${s.forme.ifBlank { "micro-entreprise" }} — SIRET ${s.siret.ifBlank { "non renseigné" }}
OBJECTIF : ${e.titre} (${e.organisme}) — échéance le ${Declarations.jour(e.date)}.
${if (e.montant.isNotBlank()) "MONTANTS CALCULÉS PAR JARVIS : ${e.montant}\n" else ""}MARCHE À SUIVRE :
${e.etapes.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")}
${if (e.note.isNotBlank()) "NOTE : ${e.note}\n" else ""}
À chaque tour tu reçois l'adresse, le titre et le texte visible de la page, puis la liste numérotée des éléments utilisables :
[n] type « libellé » (état). Les champs ne montrent pas leur contenu (seulement « rempli » ou le montant).
Réponds UNIQUEMENT par un objet JSON :
{"pensee":"ce que tu vois et ce que tu fais","action":"cliquer|saisir|choisir|cocher|decocher|connexion|aller|defiler|attendre|code|valider_final|termine|echec","id":n,"texte":"...","recap":"..."}
Règles :
- connexion : dès qu'une page de connexion est affichée (identifiant, numéro fiscal, adresse mail ou mot de passe demandés). Le téléphone remplit lui-même l'identifiant et le mot de passe : tu ne les vois jamais, tu ne les écris jamais.
- code : le site demande un code reçu par SMS ou mail, un captcha ou une validation sur une autre application → l'utilisateur le fait lui-même ; texte = ce qu'il doit faire.
- saisir : id du champ + texte. Montants en chiffres, sans espace ni symbole € (ex. 1234 ; décimales avec une virgule seulement si le champ en accepte). Utilise EXACTEMENT les montants de la marche à suivre, n'en invente jamais.
- choisir : id d'une liste déroulante + texte de l'option. cocher / decocher : id d'une case.
- aller : texte = adresse https d'un site officiel (urssaf.fr, impots.gouv.fr, net-entreprises.fr…) si la page n'est pas la bonne.
- valider_final : pour le bouton qui ENVOIE, SIGNE, CONFIRME la déclaration ou PAIE (« Valider », « Confirmer », « Envoyer », « Payer », « Signer ») : id du bouton + recap = résumé exact de ce qui sera envoyé (période, montants saisis, somme à payer, date de prélèvement). L'utilisateur donne son accord avant le clic.
- Ne modifie JAMAIS les coordonnées bancaires, l'adresse, l'identité, les options du compte ou de paiement ; ne coche aucune option non demandée.
- Si une valeur demandée par le site n'est pas dans la marche à suivre, ou si tu as un doute : echec, avec la question précise à poser dans texte.
- termine : quand l'accusé de réception ou la confirmation d'enregistrement est affiché ; texte = ce qui est confirmé, avec la référence ou le numéro d'enregistrement.
- Une seule action par réponse. Ne refais pas une action qui n'a rien changé : essaie autre chose ou echec.
""".trim()

    /** Page lue par [JS_LIRE] → texte pour l'IA. */
    fun page(json: String): String = runCatching {
        val o = JSONObject(json)
        val els = o.optJSONArray("e") ?: JSONArray()
        "ADRESSE : ${o.optString("u")}\nTITRE : ${o.optString("ti")}\nTEXTE VISIBLE :\n${o.optString("x").take(3500)}\n\nÉLÉMENTS :\n" +
            (0 until els.length()).mapNotNull { els.optJSONObject(it) }.joinToString("\n") { e ->
                val etat = buildList {
                    if (e.optInt("mdp") == 1) add("mot de passe")
                    if (e.has("t") && e.optString("t").isNotBlank() && e.optInt("mdp") != 1) add(e.optString("t"))
                    if (e.has("v") && e.optString("v").isNotBlank()) add("valeur : ${e.optString("v")}") else if (e.optBoolean("r")) add("rempli")
                    if (e.has("c")) add(if (e.optBoolean("c")) "coché" else "non coché")
                    e.optJSONArray("o")?.let { a -> add("options : " + (0 until a.length()).joinToString(" | ") { a.optString(it) }) }
                }
                "[${e.optInt("i")}] ${e.optString("k")} « ${e.optString("l")} »" + if (etat.isNotEmpty()) " (${etat.joinToString(", ")})" else ""
            }
    }.getOrDefault("Page illisible.")

    /** La page contient-elle un champ mot de passe (pour savoir si une connexion est demandée) ? */
    fun demandeMotDePasse(json: String) = runCatching {
        val a = JSONObject(json).optJSONArray("e") ?: JSONArray(); (0 until a.length()).any { a.optJSONObject(it)?.optInt("mdp") == 1 }
    }.getOrDefault(false)

    fun adresse(json: String) = runCatching { JSONObject(json).optString("u") }.getOrDefault("")

    /** Réponse de l'IA → action (JSON tolérant : texte autour, blocs ```). */
    fun action(reponse: String): Action? = runCatching {
        val t = reponse.substring(reponse.indexOf('{'), reponse.lastIndexOf('}') + 1)
        val o = JSONObject(t)
        Action(o.optString("action").trim().lowercase(), o.optInt("id", 0), o.optString("texte"), o.optString("recap"), o.optString("pensee"))
    }.getOrNull()

    /** Ouvre le navigateur de déclaration de JARVIS sur l'échéance [e] ; texte de réponse. */
    fun ouvrir(app: JarvisApp, e: Declarations.Echeance): String {
        runCatching {
            app.startActivity(android.content.Intent(app, fr.dd.jarvis.MainActivity::class.java).putExtra("terrain", "declarer:${e.id}")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        val aucun = SITES.none { identifiants(app, it.cle) != null }
        return "🤖 J'ouvre le site pour « ${e.titre} » (${e.organisme})" + (if (e.montant.isNotBlank()) " — ${e.montant}" else "") +
            ". Je me connecte et je remplis ; je te demande ton accord avant d'envoyer." +
            (if (aucun) " Aucun identifiant enregistré : tu te connecteras toi-même, ou enregistre-les dans Société › Déclarations." else "")
    }

    /** Échéance marquée faite + trace dans le coffre Obsidian. */
    fun terminer(app: JarvisApp, e: Declarations.Echeance, resultat: String, journal: List<String>) {
        app.societe.repo.update { d -> d.copy(declarationsFaites = d.declarationsFaites + (e.id to LocalDate.now().toString())) }
        runCatching {
            app.obsidian.saveIn("Société/Déclarations", "${LocalDate.now()} ${e.titre}".replace(Regex("[\\\\/:*?\"<>|]"), "-"),
                "# ${e.titre}\n\n**Organisme :** ${e.organisme}\n**Faite le :** ${Declarations.jour(LocalDate.now())} par JARVIS\n\n## Résultat\n$resultat\n\n## Étapes\n" +
                    journal.joinToString("\n") { "- $it" })
        }
    }
}
