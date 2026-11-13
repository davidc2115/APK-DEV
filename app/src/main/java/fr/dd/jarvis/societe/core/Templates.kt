package fr.dd.jarvis.societe.core

class Ctx(
    val vars: Map<String, String>,
    val lists: Map<String, List<Map<String, String>>> = emptyMap()
)

/**
 * Moteur de modèles Markdown minimal :
 *  {{variable}}                      → valeur
 *  {{#variable}}…{{/variable}}       → affiché seulement si la variable n'est pas vide
 *  {{#liste}}…{{/liste}}             → répété pour chaque élément de la liste (champs de l'élément disponibles)
 */
object Templates {

    private val section = Regex("""\{\{#(\w+)\}\}(.*?)\{\{/\1\}\}""", RegexOption.DOT_MATCHES_ALL)
    private val variable = Regex("""\{\{(\w+)\}\}""")
    private val anyTag = Regex("""\{\{[#/]?(\w+)\}\}""")

    fun render(template: String, vars: Map<String, String>, lists: Map<String, List<Map<String, String>>> = emptyMap()): String {
        val withSections = section.replace(template) { m ->
            val name = m.groupValues[1]
            val body = m.groupValues[2]
            val rows = lists[name]
            when {
                rows != null -> rows.joinToString("") { row -> render(body, vars + row, emptyMap()) }
                !vars[name].isNullOrBlank() -> render(body, vars, lists)
                else -> ""
            }
        }
        return variable.replace(withSections) { vars[it.groupValues[1]] ?: "" }
    }

    fun render(template: String, ctx: Ctx): String = render(template, ctx.vars, ctx.lists)

    /** Noms de balises utilisés dans un modèle. */
    fun balises(template: String): Set<String> = anyTag.findAll(template).map { it.groupValues[1] }.toSet()

    /** Noms autorisés pour un contexte donné (variables + listes + champs des listes). */
    fun autorises(ctx: Ctx): Set<String> =
        ctx.vars.keys + ctx.lists.keys + ctx.lists.values.flatMap { rows -> rows.flatMap { it.keys } }

    fun inconnues(template: String, ctx: Ctx): Set<String> = balises(template) - autorises(ctx)

    /** Les sections { {#x} } doivent être fermées dans le même ordre. */
    fun equilibre(template: String): Boolean {
        val pile = ArrayDeque<String>()
        Regex("""\{\{([#/])(\w+)\}\}""").findAll(template).forEach { m ->
            if (m.groupValues[1] == "#") pile.addLast(m.groupValues[2])
            else if (pile.lastOrNull() == m.groupValues[2]) pile.removeLast() else return false
        }
        return pile.isEmpty()
    }

    // ------------------------------------------------------------------ modèles par défaut

    val DEVIS_FACTURE = """
---
type: {{type_doc}}
numero: {{numero}}
client: "[[{{client_nom}}]]"
date: {{date}}
statut: {{statut_label}}
total_ht: {{total_ht_num}}
total_ttc: {{total_ttc_num}}
tags: [societe, {{type_doc}}]
---

# {{type_label_maj}} N° {{numero}}

| Émetteur | Client |
|---|---|
{{#entete}}| {{gauche}} | {{droite}} |
{{/entete}}

**Date d'émission : {{date}}**{{#facture}} · Date de la prestation : {{date_prestation}}{{/facture}} · {{echeance_label}} : {{echeance}}
{{#devis_ref}}Référence du devis : {{devis_ref}}
{{/devis_ref}}{{#site_adresse}}Lieu d'intervention : {{site_nom}} — {{site_adresse}} ({{site_kwc}} kWc)
{{/site_adresse}}
| Désignation | Qté | PU HT | Remise | TVA | Total HT |
|---|---:|---:|---:|---:|---:|
{{#lignes}}| {{designation}} | {{qte}} {{unite}} | {{pu_ht}} | {{remise}} | {{tva}} | {{total_ht}} |
{{/lignes}}

{{#remise_info}}Remise commerciale : {{remise_info}}
{{/remise_info}}
| Récapitulatif | Montant |
|---|---:|
| Total HT | {{total_ht}} |
{{#tva_lignes}}| TVA {{taux}} % sur {{base}} | {{montant}} |
{{/tva_lignes}}| **Total TTC** | **{{total_ttc}}** |
{{#facture}}| **Net à payer** | **{{net_a_payer}}** |
{{/facture}}

{{#tva_mention}}{{tva_mention}}
{{/tva_mention}}Nature de l'opération : {{nature}}.

{{#notes}}Notes : {{notes}}
{{/notes}}
Conditions de règlement : {{conditions}}

{{#societe_iban}}Règlement par virement — IBAN : {{societe_iban}}{{#societe_bic}} · BIC : {{societe_bic}}{{/societe_bic}}
{{/societe_iban}}{{#paye_le}}Facture acquittée le {{paye_le}}{{#mode_paiement}} ({{mode_paiement}}){{/mode_paiement}}.
{{/paye_le}}
{{#societe_mentions}}{{societe_mentions}}
{{/societe_mentions}}
{{#signature}}**Bon pour accord**
Date, signature et cachet du client, précédés de la mention « Devis reçu avant l'exécution des travaux, bon pour accord » :
{{/signature}}
""".trimStart()

    val CLIENT = """
---
type: client
nom: {{client_nom}}
categorie: {{type}}
tel: {{tel}}
email: {{email}}
depuis: {{depuis}}
tags: [societe, client]
---

# 👤 {{client_nom}}

**Type** : {{type}} · **Client depuis** : {{depuis}} ({{anciennete}} an(s))
{{#contact}}**Contact** : {{contact}}{{/contact}}

## 📍 Coordonnées
- Adresse : {{adresse}}
- 📞 {{tel}}
- ✉️ {{email}}

## ☀️ Installations ({{nb_sites}})
{{#sites}}### {{nom}} — {{kwc}} kWc
- 📍 {{adresse}}
- Mise en service : {{mise_en_service}} · Onduleur : {{onduleur}} · Panneaux : {{nb_panneaux}}
- Distance depuis le siège : {{distance_km}} km
{{#gps}}- 🛰️ GPS : `{{gps}}` ({{precision_gps}}) · [Google Maps]({{lien_maps}})
{{/gps}}{{#notes}}- 📝 {{notes}}
{{/notes}}
{{/sites}}
## 🧾 Historique
| Numéro | Type | Date | Total TTC | Statut |
|---|---|---|---:|---|
{{#documents}}| {{numero}} | {{type}} | {{date}} | {{total_ttc}} | {{statut}} |
{{/documents}}

**CA facturé (HT)** : {{ca_total}} · **Remise applicable** : {{remise_applicable}}

## 📝 Notes
{{notes}}
""".trimStart()

    val PAIE = """
---
type: bulletin-paie
employe: "[[{{employe_nom}}]]"
mois: {{mois}}
net_a_payer: {{net_a_payer_num}}
tags: [societe, paie]
---

# BULLETIN DE PAIE

**Période du {{periode_debut}} au {{periode_fin}}** · Paiement le {{date_paiement}} par virement

| Employeur | Salarié |
|---|---|
{{#entete}}| {{gauche}} | {{droite}} |
{{/entete}}

## Rémunération
| Élément | Nombre | Taux | Montant |
|---|---:|---:|---:|
| Salaire de base | {{heures_normales}} h | {{taux_horaire}} | {{brut_heures_normales}} |
{{#heures_sup25}}| Heures supplémentaires majorées à 25 % | {{heures_sup25}} h | {{taux_sup25}} | {{montant_sup25}} |
{{/heures_sup25}}{{#heures_sup50}}| Heures supplémentaires majorées à 50 % | {{heures_sup50}} h | {{taux_sup50}} | {{montant_sup50}} |
{{/heures_sup50}}{{#primes}}| Primes | | | {{primes}} |
{{/primes}}| **SALAIRE BRUT** | | | **{{brut}}** |
{{#heures_manquantes}}
Heures en deçà de la base contractuelle : {{heures_manquantes}} h (absence non rémunérée, non déduite automatiquement).
{{/heures_manquantes}}

## Cotisations et contributions sociales
| Rubrique | Base | Taux salarié | Part salarié | Taux employeur | Part employeur |
|---|---:|---:|---:|---:|---:|
{{#rubriques}}| {{libelle}} | {{base}} | {{taux_sal}} | {{montant_sal}} | {{taux_pat}} | {{montant_pat}} |
{{/rubriques}}| **TOTAL DES COTISATIONS ET CONTRIBUTIONS** | | | **{{cotisations_salariales}}** | | **{{charges_patronales}}** |
{{#reduction_heures_sup}}
Réduction de cotisations salariales sur heures supplémentaires (loi TEPA) incluse : - {{reduction_heures_sup}}
{{/reduction_heures_sup}}

| Net | Montant |
|---|---:|
| **NET À PAYER AVANT IMPÔT SUR LE REVENU** | **{{net_avant_impot}}** |
| Impôt sur le revenu prélevé à la source (base {{net_imposable}}, taux {{taux_pas}} %) | - {{pas}} |
| **NET PAYÉ** | **{{net_a_payer}}** |
| Montant net social | {{net_social}} |

## Cumuls {{annee}}
| Salaire brut | Net imposable | Heures | Coût employeur |
|---:|---:|---:|---:|
| {{cumul_brut}} | {{cumul_imposable}} | {{cumul_heures}} | {{cumul_cout}} |

Coût total pour l'employeur ce mois-ci : {{cout_employeur}}{{#allegements}} (dont allègements de cotisations : - {{allegements}}){{/allegements}}.

Dans votre intérêt et pour vous aider à faire valoir vos droits, conservez ce bulletin de paie sans limitation de durée. Rubrique dédiée au bulletin de paie sur le portail www.service-public.fr.

{{avertissement}}
""".trimStart()

    val PLANNING_FORMAT_DEFAUT = """
Mise en forme Markdown obligatoire :
- Un titre par jour : `# 📅 lundi 3 novembre 2026`.
- Un bloc par événement, séparé du suivant par une ligne `---`.
- Les horaires dans le titre du bloc, avec un emoji : `## 🕗 08:00 – 12:00 · Titre de l'événement` (événement sur la journée : `## 🕗 Journée entière · Titre`).
- Les détails sous le titre, un par ligne, avec un emoji :
  `📍 [adresse](https://www.google.com/maps/search/?api=1&query=ADRESSE_ENCODEE)` (adresse cliquable vers Google Maps, espaces remplacés par +),
  `📞 [06 12 34 56 78](tel:+33612345678)` (numéro cliquable, trouvé dans le lieu ou les notes),
  `⚠️ ...` pour les consignes, accès, matériel ou points d'attention.
- Quand un événement couvre plusieurs sites, un sous-bloc par site (`### 🏭 Nom du site` pour un site industriel, `### ☀️ ...` pour un site solaire/photovoltaïque, `### 🔌 ...` pour un site électrique), avec ses propres détails.
- N'invente aucune information absente des données. Si un détail manque, ne l'écris pas.
""".trim()

    /**
     * Rapport d'intervention : sobre, avec des emojis repères. Les sections n'apparaissent que si elles ont un contenu ;
     * les mesures par série deviennent un tableau (2 séries par ligne).
     */
    val RAPPORT = """
# 📋 {{titre}}

**{{client}}** — {{installation}}

| | |
|---|---|
| 📅 **Date** | {{date}} |
| 🔧 **Intervention** | {{categorie}} |
| 📌 **Statut** | {{statut}} |
{{#adresse}}| 📍 **Adresse** | {{adresse}} |
{{/adresse}}{{#onduleur}}| ⚡ **Onduleur** | {{onduleur}} |
{{/onduleur}}{{#kwc}}| ☀️ **Puissance** | {{kwc}} kWc |
{{/kwc}}
{{#description}}
## 📝 Description
{{description}}
{{/description}}
{{#diagnostic}}
## 🔍 Diagnostic
{{diagnostic}}
{{/diagnostic}}
{{#a_actions}}
## 🛠️ Actions réalisées
{{#actions}}- ✅ {{action}}
{{/actions}}{{/a_actions}}
{{#a_pieces}}
## 🔩 Pièces remplacées
{{#pieces}}- 🔹 {{piece}}
{{/pieces}}{{/a_pieces}}
{{#a_series}}
## 📊 Mesures par série
| Série | Valeur | Série | Valeur |
|---|---:|---|---:|
{{#series_paires}}| {{serie_a}} | {{valeur_a}} | {{serie_b}} | {{valeur_b}} |
{{/series_paires}}{{/a_series}}
{{#a_mesures}}
## 📏 Autres mesures
{{#mesures}}- {{mesure}}
{{/mesures}}{{/a_mesures}}
{{#a_photos_avant}}
## 📷 Photos avant intervention
{{#photos_avant}}{{photo}}
{{/photos_avant}}{{/a_photos_avant}}
{{#a_photos_apres}}
## 📷 Photos après intervention
{{#photos_apres}}{{photo}}
{{/photos_apres}}{{/a_photos_apres}}
{{#prochaine_maintenance}}
## 📆 Prochaine maintenance
{{prochaine_maintenance}}
{{/prochaine_maintenance}}
{{#notes}}
## 🗒️ Notes
{{notes}}
{{/notes}}
{{#signature}}
## ✍️ Signature du client
{{signature}}
{{/signature}}

---
**{{societe_nom}}**{{#societe_tel}} · 📞 {{societe_tel}}{{/societe_tel}}{{#societe_email}} · ✉️ {{societe_email}}{{/societe_email}}
""".trimStart()

    fun defaut(type: String): String = when (type) {
        "rapport" -> RAPPORT
        "devis", "facture" -> DEVIS_FACTURE
        "client" -> CLIENT
        "paie" -> PAIE
        "planning" -> PLANNING_FORMAT_DEFAUT
        else -> ""
    }
}
