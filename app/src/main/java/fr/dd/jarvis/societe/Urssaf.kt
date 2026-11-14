package fr.dd.jarvis.societe

import fr.dd.jarvis.societe.core.Baremes
import fr.dd.jarvis.societe.core.BaremesCalcul
import fr.dd.jarvis.societe.core.CoutMensuel
import fr.dd.jarvis.societe.core.Employe
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.normaliser
import fr.dd.jarvis.societe.core.round2
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * API publique et gratuite de l'URSSAF (https://mon-entreprise.urssaf.fr/api/v1/evaluate, sans clé) : le même moteur de
 * calcul et les mêmes règles que les simulateurs officiels, mis à jour par l'URSSAF à chaque changement de loi (taux,
 * plafonds, SMIC, réduction générale…). Jarvis s'en sert pour ses barèmes (relevés chaque semaine) et pour les bulletins.
 * Limite de l'API : 5 requêtes par seconde — Jarvis en fait au plus quelques-unes par mise à jour.
 */
object Urssaf {
    private const val URL = "https://mon-entreprise.urssaf.fr/api/v1/evaluate"
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** Évalue des expressions (avec leur unité) dans une situation ; une valeur nulle si l'expression n'a pas de valeur. */
    fun evaluer(situation: Map<String, String>, expressions: List<Pair<String, String?>>): List<Double?> {
        val corps = JSONObject()
            .put("situation", JSONObject().also { o -> situation.forEach { (k, v) -> o.put(k, v) } })
            .put("expressions", JSONArray().also { a ->
                expressions.forEach { (e, u) -> a.put(if (u == null) e else JSONObject().put("valeur", e).put("unité", u)) }
            })
        val req = Request.Builder().url(URL).post(corps.toString().toRequestBody(JSON)).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("API URSSAF : HTTP ${r.code} ${txt.take(160)}")
            val ev = JSONObject(txt).optJSONArray("evaluate") ?: throw IllegalStateException("API URSSAF : réponse inattendue")
            return (0 until ev.length()).map { i ->
                ev.optJSONObject(i)?.opt("nodeValue")?.let { v -> (v as? Number)?.toDouble() }
            }
        }
    }

    /** Relève les barèmes officiels en vigueur (3 requêtes). */
    fun baremes(): Baremes {
        val base = evaluer(emptyMap(), listOf(
            "plafond sécurité sociale" to "€/mois", "SMIC . horaire" to "€/heure",
            "entreprise . imposition . IS . montant . taux réduit" to "%", "entreprise . imposition . IS . montant . taux normal" to "%"
        ))
        val pmss = base[0] ?: throw IllegalStateException("API URSSAF : plafond de la Sécurité sociale absent")
        val tr = base[2] ?: throw IllegalStateException("API URSSAF : taux réduit d'IS absent")
        val tn = base[3] ?: throw IllegalStateException("API URSSAF : taux normal d'IS absent")
        val r = 100_000.0
        val is100k = evaluer(mapOf("entreprise . imposition" to "'IS'", "entreprise . imposition . IS . résultat imposable" to "$r €/an"),
            listOf("entreprise . imposition . IS . montant" to "€/an"))[0]
        val plafond = is100k?.let { BaremesCalcul.plafondIs(r, it, tr, tn) } ?: 0.0
        val div = evaluer(mapOf("entreprise . catégorie juridique" to "'SAS'", "entreprise . imposition" to "'IS'", "bénéficiaire" to "oui",
            "bénéficiaire . dividendes . bruts" to "10000 €/an", "impôt . méthode de calcul" to "'PFU'"),
            listOf("bénéficiaire . dividendes . nets d'impôt" to "€/an", "bénéficiaire . dividendes . cotisations et contributions" to "€/an"))
        val nets = div[0] ?: throw IllegalStateException("API URSSAF : dividendes nets absents")
        return Baremes(
            dateMaj = LocalDate.now().toString(), source = BaremesCalcul.SOURCE, pmss = round2(pmss), smicHoraire = round2(base[1] ?: 0.0),
            isTauxReduit = round2(tr), isPlafondReduit = plafond, isTauxNormal = round2(tn),
            pfuPct = round2((10_000.0 - nets) / 100.0), prelevementsSociauxPct = round2((div[1] ?: 0.0) / 100.0)
        )
    }

    /** Expressions d'un bulletin (€/mois) et leur clé dans [BaremesCalcul.MontantsPaie]. */
    private val EXPRESSIONS_PAIE = listOf(
        "maladie.employeur" to "salarié . cotisations . maladie . employeur", "maladie.salarie" to "salarié . cotisations . maladie . salarié",
        "sante.employeur" to "salarié . cotisations . prévoyances . santé . employeur", "sante.salarie" to "salarié . cotisations . prévoyances . santé . salarié",
        "atmp" to "salarié . cotisations . ATMP",
        "vieillesse.plafonnee.employeur" to "salarié . cotisations . vieillesse . plafonnée . employeur", "vieillesse.plafonnee.salarie" to "salarié . cotisations . vieillesse . plafonnée . salarié",
        "vieillesse.deplafonnee.employeur" to "salarié . cotisations . vieillesse . déplafonnée . employeur", "vieillesse.deplafonnee.salarie" to "salarié . cotisations . vieillesse . déplafonnée . salarié",
        "retraite.employeur" to "salarié . cotisations . retraite complémentaire-CEG-CET . employeur", "retraite.salarie" to "salarié . cotisations . retraite complémentaire-CEG-CET . salarié",
        "apec.employeur" to "salarié . cotisations . APEC . employeur", "apec.salarie" to "salarié . cotisations . APEC . salarié",
        "prevoyance.employeur" to "salarié . cotisations . prévoyances . incapacité invalidité décès . employeur",
        "famille" to "salarié . cotisations . allocations familiales", "chomage" to "salarié . cotisations . assurance chômage",
        "autres" to "salarié . cotisations . autres employeur",
        "csg.deductible" to "salarié . cotisations . CSG-CRDS . CSG . déductible",
        "csg.non_deductible" to "salarié . cotisations . CSG-CRDS . sur revenus imposables non déductible",
        "csg.non_imposable" to "salarié . cotisations . CSG-CRDS . sur revenus non imposables",
        "exo.employeur" to "salarié . cotisations . exonérations . employeur", "exo.salarie" to "salarié . cotisations . exonérations . salarié",
        "total.employeur" to "salarié . cotisations . employeur", "total.salarie" to "salarié . cotisations . salarié",
        "cout" to "salarié . coût total employeur", "net.avant_impot" to "salarié . rémunération . net . à payer avant impôt",
        "net.imposable" to "salarié . rémunération . net . imposable"
    )

    /** Situation URSSAF d'un employé (statut cadre, dirigeant assimilé salarié, effectif, taux AT/MP). */
    fun situation(d: SocieteData, e: Employe, brut: Double): Map<String, String> {
        val m = linkedMapOf("salarié . contrat . salaire brut" to "${round2(brut)} €/mois")
        if (d.paie.cadre) m["salarié . contrat . statut cadre"] = "oui"
        if (d.paie.tauxAtmp > 0) m["établissement . taux ATMP"] = "${d.paie.tauxAtmp} %"
        val n = d.employes.count { it.statut != "dirigeant" }
        m["entreprise . salariés . effectif . seuil"] = when { n < 5 -> "'moins de 5'"; n < 11 -> "'moins de 11'"; n < 20 -> "'moins de 20'"; n < 50 -> "'moins de 50'"; else -> "'moins de 150'" }
        // Président de SAS/SASU = assimilé salarié (ni chômage, ni AGS, ni réduction générale).
        if (e.statut == "dirigeant" && Regex("\\bsas").containsMatchIn(normaliser(d.societe.forme))) m["entreprise . catégorie juridique"] = "'SAS'"
        return m
    }

    /** Dirigeant pour qui le calcul « salarié » ne s'applique pas (gérant majoritaire de SARL/EURL : travailleur non salarié). */
    fun horsRegimeGeneral(d: SocieteData, e: Employe) = e.statut == "dirigeant" && Regex("sarl|eurl").containsMatchIn(normaliser(d.societe.forme))

    fun montantsPaie(d: SocieteData, e: Employe, brut: Double): BaremesCalcul.MontantsPaie {
        val sit = situation(d, e, brut)
        val v = evaluer(sit, EXPRESSIONS_PAIE.map { it.second to "€/mois" })
        // Montant net social (mention obligatoire du bulletin) : règle encore « expérimentale » chez l'URSSAF, demandée à
        // part pour qu'un renommage éventuel ne fasse pas échouer tout le bulletin (repli : net avant impôt).
        val social = runCatching { evaluer(sit, listOf("salarié . rémunération . montant net social" to "€/mois"))[0] }.getOrNull() ?: 0.0
        return BaremesCalcul.MontantsPaie(EXPRESSIONS_PAIE.mapIndexed { i, (k, _) -> k to (v.getOrNull(i) ?: 0.0) }.toMap() + ("net.social" to social))
    }

    /** Coût employeur et net officiels pour le salaire contractuel de chaque employé (mis en cache dans les barèmes). */
    fun couts(d: SocieteData): Map<String, CoutMensuel> = d.employes.filter { it.brutMensuel > 0 && !horsRegimeGeneral(d, it) }.mapNotNull { e ->
        val v = runCatching {
            evaluer(situation(d, e, e.brutMensuel), listOf("salarié . coût total employeur" to "€/mois", "salarié . rémunération . net . à payer avant impôt" to "€/mois"))
        }.getOrNull() ?: return@mapNotNull null
        val cout = v[0] ?: return@mapNotNull null
        e.id to CoutMensuel(round2(e.brutMensuel), round2(cout), round2(v[1] ?: 0.0), LocalDate.now().toString())
    }.toMap()
}
