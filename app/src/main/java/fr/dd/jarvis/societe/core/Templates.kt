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

# {{type_label}} n° {{numero}}

**{{societe_nom}}** {{societe_forme}}
{{societe_adresse}}
{{#societe_tel}}📞 {{societe_tel}}  {{/societe_tel}}{{#societe_email}}✉️ {{societe_email}}  {{/societe_email}}
{{#societe_siret}}SIRET : {{societe_siret}}  {{/societe_siret}}{{#societe_tva}}TVA : {{societe_tva}}{{/societe_tva}}

---

**Client** : {{client_nom}}
{{client_adresse}}
{{#site_adresse}}**Installation** : {{site_nom}} — {{site_adresse}} ({{site_kwc}} kWc){{/site_adresse}}

**Date** : {{date}} · **{{echeance_label}}** : {{echeance}}
{{#devis_ref}}**Réf. devis** : {{devis_ref}}{{/devis_ref}}

| Désignation | Qté | PU HT | Remise | Total HT |
|---|---:|---:|---:|---:|
{{#lignes}}| {{designation}} | {{qte}} {{unite}} | {{pu_ht}} | {{remise}} | {{total_ht}} |
{{/lignes}}

{{#remise_info}}🎁 Remise commerciale : {{remise_info}}{{/remise_info}}

| Récapitulatif | Montant |
|---|---:|
| **Total HT** | **{{total_ht}}** |
{{#tva_lignes}}| TVA {{taux}} % (base {{base}}) | {{montant}} |
{{/tva_lignes}}| **Total TTC** | **{{total_ttc}}** |

{{#tva_mention}}{{tva_mention}}{{/tva_mention}}

{{#notes}}**Notes** : {{notes}}{{/notes}}

**Conditions** : {{conditions}}

{{#societe_iban}}**IBAN** : {{societe_iban}} {{#societe_bic}}· BIC : {{societe_bic}}{{/societe_bic}}{{/societe_iban}}

{{#societe_mentions}}{{societe_mentions}}{{/societe_mentions}}

{{#signature}}Bon pour accord — date et signature du client :{{/signature}}
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

# 💶 Bulletin de paie — {{mois_label}}

**Employeur** : {{societe_nom}} — {{societe_adresse}} {{#societe_siret}}· SIRET {{societe_siret}}{{/societe_siret}}
**Salarié** : {{employe_nom}} — {{poste}} ({{statut_label}})
**Base horaire** : {{heures_hebdo}} h/semaine · {{heures_contrat}} h/mois (contrat) · taux horaire {{taux_horaire}}
{{#net_vise}}**Net visé (contrat)** : {{net_vise}} (brut recalculé automatiquement pour l'atteindre){{/net_vise}}
**Heures retenues ce mois** : {{heures_effectuees}} h
{{#heures_manquantes}}⚠️ Dont {{heures_manquantes}} h en-deçà de la base contractuelle (absence non rémunérée non calculée automatiquement).
{{/heures_manquantes}}

| Rubrique | Détail | Montant |
|---|---|---:|
| Heures normales | {{heures_normales}} h × {{taux_horaire}} | {{brut_heures_normales}} |
{{#heures_sup25}}| Heures sup. à 25 % | {{heures_sup25}} h majorées 25 % | — |
{{/heures_sup25}}{{#heures_sup50}}| Heures sup. à 50 % | {{heures_sup50}} h majorées 50 % | — |
{{/heures_sup50}}{{#brut_heures_sup}}| **Total heures supplémentaires** | | **{{brut_heures_sup}}** |
{{/brut_heures_sup}}{{#primes}}| Primes | | {{primes}} |
{{/primes}}| **Salaire brut** | | **{{brut}}** |
{{#reduction_heures_sup}}
ℹ️ Réduction salariale heures sup. (loi TEPA) déjà incluse dans les cotisations ci-dessous : **- {{reduction_heures_sup}}**
{{/reduction_heures_sup}}

## Cotisations et contributions sociales
| Rubrique | Base | Part salariale | Part patronale |
|---|---:|---:|---:|
{{#cotisations}}| {{libelle}} | {{base}} | {{taux_sal}} → {{montant_sal}} | {{taux_pat}} → {{montant_pat}} |
{{/cotisations}}
| **Total cotisations salariales** | | **- {{cotisations_salariales}}** | |

| Rubrique | Montant |
|---|---:|
| **Net avant impôt** | **{{net_avant_impot}}** |
| Prélèvement à la source | {{taux_pas}} % → - {{pas}} |
| **Net à payer** | **{{net_a_payer}}** |

## Coût employeur
| Rubrique | Montant |
|---|---:|
| Total charges patronales | {{charges_patronales}} |
| **Coût total employeur** | **{{cout_employeur}}** |

⚠️ {{avertissement}}
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
