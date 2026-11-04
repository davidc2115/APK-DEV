package fr.dd.jarvis.societe.core

import kotlinx.serialization.Serializable

/** Puissance « infinie » pour la dernière tranche d'un tarif. */
const val KWC_MAX = 1_000_000.0

@Serializable
data class Societe(
    val nom: String = "",
    val forme: String = "",              // EURL, SASU, SARL, micro-entreprise…
    val siret: String = "",
    val tvaNum: String = "",
    val adresse: String = "",            // siège : point de départ des déplacements
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsPrecision: String = "",       // Geo.Precision (MANUELLE si coordonnées saisies)
    val tel: String = "",
    val email: String = "",
    val iban: String = "",
    val bic: String = "",
    val tvaDefaut: Double = 20.0,
    val franchiseTva: Boolean = false,   // micro-entreprise : « TVA non applicable, art. 293 B du CGI »
    val tarifsSaisisTtc: Boolean = false, // true : les prix saisis pour les tarifs sont TTC (convertis en HT à l'enregistrement) ; false : HT directement
    val delaiPaiementJours: Int = 30,
    val validiteDevisJours: Int = 30,
    val mentions: String = "",           // mentions légales complémentaires (assurance décennale, etc.)
    val reservePct: Double = 0.0,        // % du HT de chaque facture encaissée mis de côté (réserve bénéfices)
    val partDirigeantPct: Double = 50.0,
    val kmlCouleurParticulier: String = "#20A45A",
    val kmlCouleurProfessionnel: String = "#2878D0",
    val kmlCouleurSiege: String = "#FF9800"
)

enum class Mode { FORFAIT, PAR_KWC, HEURE, UNITE }

@Serializable
data class Tranche(val jusquaKwc: Double, val prix: Double)

@Serializable
data class Tarif(
    val designation: String,
    val mode: Mode = Mode.FORFAIT,
    val tranches: List<Tranche> = emptyList(),
    val tva: Double? = null
)

@Serializable
data class Seuil(val seuil: Int, val pct: Double)

@Serializable
data class Regles(
    val remiseFidelite: List<Seuil> = emptyList(),   // années d'ancienneté → %
    val remiseVolume: List<Seuil> = emptyList(),     // nombre d'installations → %
    val remiseCumul: Boolean = true,                 // true : fidélité + volume s'additionnent ; false : la plus forte
    val remiseMax: Double = 0.0,                     // plafond total en % (0 = pas de plafond)
    val remiseSurDeplacement: Boolean = false,
    val kmPrix: Double = 0.0,                        // € / km
    val kmGratuits: Double = 0.0,                    // km offerts (sur le trajet aller)
    val deplacementMin: Double = 0.0,                // forfait minimum dès qu'il y a un déplacement
    val allerRetour: Boolean = true
)

@Serializable
data class Site(
    val id: String,
    val nom: String = "",
    val adresse: String = "",
    val kwc: Double = 0.0,
    val miseEnService: String = "",
    val onduleur: String = "",
    val nbPanneaux: Int = 0,
    val notes: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val distanceKm: Double? = null,
    val dureeMin: Double? = null,
    val distanceCle: String = "",                    // « adresse siège|adresse site » pour savoir si le cache est valide
    val gpsPrecision: String = "",                   // Geo.Precision : MANUELLE, NUMERO, RUE, LIEU_DIT, COMMUNE ; vide = ancien calcul non vérifié
    val gpsTrouve: String = "",                      // adresse officielle reconnue (Base Adresse Nationale / OpenStreetMap)
    val interventions: List<Intervention> = emptyList()
)

/** Historique daté des opérations réellement réalisées sur une installation. */
@Serializable
data class Intervention(
    val id: String,
    val date: String,
    val categorie: String = "maintenance",
    val description: String,
    val diagnostic: String = "",
    val actions: String = "",
    val pieces: String = "",
    val mesures: String = "",
    val statut: String = "terminee",
    val prochaineMaintenance: String = "",
    val notes: String = ""
)

@Serializable
data class Client(
    val id: String,
    val nom: String,
    val type: String = "particulier",                // particulier | professionnel
    val contact: String = "",
    val tel: String = "",
    val email: String = "",
    val adresse: String = "",
    val sites: List<Site> = emptyList(),
    val depuis: String = "",                         // yyyy-MM-dd : début de la relation (ancienneté)
    val notes: String = ""
)

@Serializable
data class Ligne(
    val designation: String,
    val qte: Double = 1.0,
    val unite: String = "",
    val puHt: Double = 0.0,
    val remisePct: Double = 0.0,
    val tva: Double = 20.0,
    val genre: String = "prestation"                 // prestation | deplacement
)

@Serializable
data class Doc(
    val numero: String,
    val type: String,                                // devis | facture
    val clientId: String,
    val siteId: String = "",
    val date: String,                                // yyyy-MM-dd
    val echeance: String = "",                       // validité (devis) ou échéance (facture)
    val lignes: List<Ligne> = emptyList(),
    val notes: String = "",
    val statut: String = "brouillon",                // devis : brouillon|envoye|accepte|refuse|facture — facture : a_payer|payee|annulee
    val devisRef: String = "",
    val payeLe: String = "",
    val modePaiement: String = "",
    val remiseInfo: String = "",
    val distanceKm: Double? = null
)

@Serializable
data class Employe(
    val id: String,
    val nom: String,
    val poste: String = "",
    val statut: String = "salarie",                  // dirigeant | salarie
    val brutMensuel: Double = 0.0,                   // toujours le brut HT ; si saisi en net, recalculé automatiquement (voir netMensuelVise)
    val netMensuelVise: Double = 0.0,                 // 0 = non utilisé ; sinon brutMensuel est recalculé pour atteindre ce net avant impôt
    val heuresHebdo: Double = 35.0,                  // base contractuelle hebdomadaire (35, 39, 40…) → heures mensuelles = heuresHebdo × 52 / 12
    val tauxPas: Double = 0.0,                       // prélèvement à la source (%)
    val dateEntree: String = ""
)

@Serializable
data class ConfigPaie(
    val cadre: Boolean = false,                      // régime cadre (AGIRC-ARRCO + APEC/CET) ou non-cadre
    val plafondSecu: Double = 3925.0,                // plafond mensuel de la Sécurité sociale (PMSS) — à vérifier chaque année
    val tauxAtmp: Double = 1.2,                      // taux accident du travail / maladies professionnelles (patronal, dépend de l'activité)
    val tauxFormationPct: Double = 0.55,             // contribution formation professionnelle (patronal)
    val tauxTaxeApprentissagePct: Double = 0.68,     // taxe d'apprentissage (patronal)
    // Champs conservés pour compatibilité avec d'anciennes données ; plus utilisés par le calcul (voir Paie.kt).
    val tauxSalarial: Double = 22.0,
    val tauxPatronal: Double = 42.0,
    val tauxSalarialDirigeant: Double = 22.0,
    val tauxPatronalDirigeant: Double = 55.0
)

@Serializable
data class Provision(
    val id: String,
    val date: String,
    val factureNumero: String,
    val baseHt: Double,
    val pct: Double,
    val montant: Double,
    val categorie: String = "reserve_benefices"
)

@Serializable
data class BulletinRef(
    val id: String,
    val employeId: String,
    val mois: String,                                // yyyy-MM
    val brut: Double,
    val netAPayer: Double,
    val coutEmployeur: Double
)
