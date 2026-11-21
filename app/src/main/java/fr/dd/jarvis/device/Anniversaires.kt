package fr.dd.jarvis.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fr.dd.jarvis.JarvisApp
import fr.dd.jarvis.ai.CommandesDirectes
import fr.dd.jarvis.ai.DatesFr
import fr.dd.jarvis.societe.core.normaliser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Anniversaires : ceux des contacts Android (champ « anniversaire ») et ceux dits à JARVIS (« l'anniversaire de Marie
 * c'est le 14 mars »). Annoncés au point du matin (le jour même) et au point du soir (la veille), avec une notification
 * « Lui écrire » ; « quels sont les prochains anniversaires ? », « c'est quand l'anniversaire de Paul ? » — sans IA.
 */
object Anniversaires {
    data class Anniv(val nom: String, val mois: Int, val jour: Int, val annee: Int? = null, val source: String = "contacts")

    private val MOIS = listOf("janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre")
    private val MOIS_AFF = listOf("janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre")

    // ------------------------------------------------------------------ dates

    /** Date d'anniversaire telle que la donnent les contacts : « 1985-03-14 », « --03-14 », « 14/03/1985 », « 19850314 », « 14 mars ». */
    internal fun parser(s: String): Triple<Int?, Int, Int>? {
        val t = s.trim()
        Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").find(t)?.let { m -> return valide(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        Regex("^--(\\d{1,2})-?(\\d{1,2})").find(t)?.let { m -> return valide(null, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        Regex("^(\\d{1,2})[/.-](\\d{1,2})(?:[/.-](\\d{2,4}))?$").find(t)?.let { m ->
            val a = m.groupValues[3].takeIf { it.length == 4 }?.toInt()
            return valide(a, m.groupValues[2].toInt(), m.groupValues[1].toInt())
        }
        Regex("^(\\d{4})(\\d{2})(\\d{2})$").find(t)?.let { m -> return valide(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        Regex("(\\d{1,2}|1er) (${MOIS.joinToString("|")})(?: (\\d{4}))?").find(normaliser(t))?.let { m ->
            return valide(m.groupValues[3].toIntOrNull(), MOIS.indexOf(m.groupValues[2]) + 1, m.groupValues[1].removeSuffix("er").toInt())
        }
        return null
    }

    private fun valide(a: Int?, m: Int, j: Int): Triple<Int?, Int, Int>? =
        if (m in 1..12 && j in 1..31 && (a == null || a in 1900..2100)) Triple(a?.takeIf { it > 1900 }, m, j) else null

    /** Prochain jour d'anniversaire (aujourd'hui compris) ; 29 février → 28 février les autres années. */
    fun prochaine(a: Anniv, aujourdhui: LocalDate): LocalDate {
        fun en(annee: Int) = runCatching { LocalDate.of(annee, a.mois, a.jour) }.getOrElse { LocalDate.of(annee, a.mois, 28) }
        val d = en(aujourdhui.year)
        return if (d.isBefore(aujourdhui)) en(aujourdhui.year + 1) else d
    }

    fun age(a: Anniv, le: LocalDate): Int? = a.annee?.let { le.year - it }?.takeIf { it in 1..120 }

    fun dans(liste: List<Anniv>, aujourdhui: LocalDate, jours: Int): List<Pair<Anniv, LocalDate>> =
        liste.map { it to prochaine(it, aujourdhui) }.filter { !it.second.isAfter(aujourdhui.plusDays(jours.toLong())) }.sortedBy { it.second }

    fun ligne(a: Anniv, d: LocalDate, aujourdhui: LocalDate): String {
        val quand = when (d) { aujourdhui -> "aujourd'hui"; aujourdhui.plusDays(1) -> "demain"; else -> DatesFr.libelle(d, aujourdhui) }
        return "🎂 **${a.nom}** — $quand" + (age(a, d)?.let { " ($it ans)" }.orEmpty())
    }

    // ------------------------------------------------------------------ sources

    private fun fichier(ctx: Context) = File(ctx.filesDir, "anniversaires.json")

    fun ajoutes(ctx: Context): List<Anniv> = runCatching {
        val a = JSONArray(fichier(ctx).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            Anniv(o.optString("nom"), o.optInt("mois"), o.optInt("jour"), o.optInt("annee").takeIf { it > 0 }, "jarvis")
        }
    }.getOrDefault(emptyList())

    private fun sauver(ctx: Context, l: List<Anniv>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("nom", it.nom).put("mois", it.mois).put("jour", it.jour).put("annee", it.annee ?: 0)) }
        fichier(ctx).writeText(a.toString())
    }

    /** Anniversaires du carnet d'adresses Android. */
    fun contacts(ctx: Context): List<Anniv> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val out = mutableListOf<Anniv>()
        runCatching {
            ctx.contentResolver.query(ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Event.START_DATE),
                "${ContactsContract.Data.MIMETYPE} = ? AND ${ContactsContract.CommonDataKinds.Event.TYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Event.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val nom = c.getString(0).orEmpty().trim()
                    val d = parser(c.getString(1).orEmpty()) ?: continue
                    if (nom.isNotBlank()) out += Anniv(nom, d.second, d.third, d.first)
                }
            }
        }
        return out
    }

    fun tous(ctx: Context): List<Anniv> = (ajoutes(ctx) + contacts(ctx)).distinctBy { normaliser(it.nom) + "|" + it.mois + "|" + it.jour }

    // ------------------------------------------------------------------ point du matin / du soir, notifications

    /** Ligne du point du matin (anniversaires du jour + des 7 prochains jours) ou du soir (ceux de demain). */
    fun pourBriefing(ctx: Context, soir: Boolean, aujourdhui: LocalDate = LocalDate.now()): String? = texteBriefing(tous(ctx), soir, aujourdhui)

    internal fun texteBriefing(l: List<Anniv>, soir: Boolean, aujourdhui: LocalDate): String? {
        if (soir) {
            val demain = l.filter { prochaine(it, aujourdhui) == aujourdhui.plusDays(1) }
            if (demain.isEmpty()) return null
            return "🎂 Demain : anniversaire de " + demain.joinToString(", ") { a -> a.nom + (age(a, aujourdhui.plusDays(1))?.let { " ($it ans)" }.orEmpty()) } + " — pense à un message ou un cadeau."
        }
        val jour = l.filter { prochaine(it, aujourdhui) == aujourdhui }
        val semaine = dans(l, aujourdhui, 7).filter { it.second != aujourdhui }
        if (jour.isEmpty() && semaine.isEmpty()) return null
        return buildString {
            if (jour.isNotEmpty()) append("🎂 Aujourd'hui : anniversaire de " + jour.joinToString(", ") { a -> a.nom + (age(a, aujourdhui)?.let { " ($it ans)" }.orEmpty()) } + ".")
            if (semaine.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("Cette semaine : " + semaine.take(4).joinToString(", ") { (a, d) -> "${a.nom} (${if (d == aujourdhui.plusDays(1)) "demain" else DatesFr.libelle(d, aujourdhui)})" } + ".")
            }
        }
    }

    private const val CANAL = "anniversaires_jarvis"

    /** Appelé par la veille : le jour même (dès 8 h) et la veille (dès 18 h), une seule notification par anniversaire. */
    fun verifier(app: JarvisApp, maintenant: java.time.LocalDateTime = java.time.LocalDateTime.now()) {
        if (!app.store.anniversairesOn.value) return
        val auj = maintenant.toLocalDate()
        val l = tous(app)
        if (l.isEmpty()) return
        val prefs = app.getSharedPreferences("jarvis_anniversaires", Context.MODE_PRIVATE)
        fun une(a: Anniv, cle: String, titre: String, texte: String) {
            if (prefs.getBoolean(cle, false)) return
            prefs.edit().putBoolean(cle, true).apply()
            notifier(app, a, titre, texte, cle.hashCode())
        }
        if (maintenant.toLocalTime() >= LocalTime.of(8, 0)) l.filter { prochaine(it, auj) == auj }.forEach { a ->
            une(a, "j|${a.nom}|$auj", "🎂 Anniversaire de ${a.nom} aujourd'hui", "${a.nom}${age(a, auj)?.let { " a $it ans" } ?: " fête son anniversaire"} aujourd'hui.")
        }
        if (maintenant.toLocalTime() >= LocalTime.of(18, 0)) l.filter { prochaine(it, auj) == auj.plusDays(1) }.forEach { a ->
            une(a, "v|${a.nom}|$auj", "🎂 Demain : anniversaire de ${a.nom}", "${a.nom}${age(a, auj.plusDays(1))?.let { " aura $it ans" } ?: " fête son anniversaire"} demain.")
        }
    }

    private fun notifier(app: JarvisApp, a: Anniv, titre: String, texte: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, "Anniversaires", NotificationManager.IMPORTANCE_DEFAULT))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val code = 8100 + (id and 0x3FF)
        val b = NotificationCompat.Builder(app, CANAL).setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(titre).setContentText(texte).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(app, code, Intent(app, fr.dd.jarvis.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags))
            .addAction(0, "✉️ Lui écrire", PendingIntent.getActivity(app, code + 1, Intent(app, fr.dd.jarvis.MainActivity::class.java)
                .putExtra("demande", "Prépare un SMS pour souhaiter un joyeux anniversaire à ${a.nom}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
        runCatching { NotificationManagerCompat.from(app).notify(code, b.build()) }
    }

    // ------------------------------------------------------------------ compréhension (sans IA)

    sealed interface Intention {
        data class Lister(val jours: Int) : Intention
        data class DeQui(val nom: String) : Intention
        data class Ajouter(val nom: String, val mois: Int, val jour: Int, val annee: Int?) : Intention
        data class Supprimer(val nom: String) : Intention
    }

    private const val DATE = "(\\d{1,2}|1er|premier) (janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre)(?: (\\d{4}))?|(\\d{1,2})[ /.-](\\d{1,2})(?:[ /.-](\\d{4}))?"
    private const val POLI = "^(?:(?:jarvis|ok|alors|dis moi|dis|est ce que tu peux|tu peux|peux tu|s il te plait|stp)\\s+)*"
    private val R_AJOUT = listOf(
        Regex("$POLI(?:(?:note|notes|retiens|enregistre|ajoute|memorise|souviens toi)\\s+(?:que\\s+|bien\\s+que\\s+)?)?l anniversaire (?:de|d) (.+?) (?:c est|est|tombe)(?: le)? (?:$DATE)\\s*$"),
        Regex("$POLI(?:(?:note|notes|retiens|enregistre|ajoute|memorise)\\s+(?:que\\s+)?)?(.+?) (?:est ne|est nee|est nes|fete son anniversaire)(?: le)? (?:$DATE)\\s*$"),
        Regex("$POLI(?:note|notes|ajoute|enregistre|memorise)\\s+l anniversaire (?:de|d) (.+?) (?:le|au) (?:$DATE)\\s*$"))
    private val R_DE_QUI = Regex("$POLI(?:c est quand|quand est|quand c est|quelle est la date de|a quelle date est|quand tombe|quand a lieu) l anniversaire (?:de|d) (.+?)\\s*$")
    private val R_SUPPR = Regex("$POLI(?:supprime|oublie|retire|efface|enleve) l anniversaire (?:de|d) (.+?)\\s*$")
    private val R_LISTE = Regex("\\b(?:(?:prochains?|les|mes) anniversaires|anniversaires? (?:de la semaine|du mois|a venir|cette semaine|ce mois ci|de ce mois|prochains?|du jour|d aujourd hui|de demain)|" +
        "qui (?:a|fete|fete son|a son) (?:son )?anniversaire|c est l anniversaire de qui|il y a (?:un|des) anniversaires?|y a t il (?:un|des) anniversaires?|quels? anniversaires?)\\b")

    fun intention(texte: String): Intention? {
        val o = texte.trim().trimEnd('?', '!', '.', ' ')
        if (o.isBlank() || o.length > 200) return null
        val p = CommandesDirectes.plat(o)
        if (!p.contains("anniversaire") && !Regex("\\best nee?s?\\b").containsMatchIn(p)) return null
        fun brut(m: MatchResult, i: Int) = m.groups[i]?.range?.let { o.substring(it.first, it.last + 1).trim().trim(',', ' ') }.orEmpty()
        for (r in R_AJOUT) r.matchEntire(p)?.let { m ->
            val nom = nettoyerNom(brut(m, 1))
            val (mois, jour, annee) = if (m.groupValues[3].isNotBlank()) Triple(MOIS.indexOf(m.groupValues[3]) + 1, m.groupValues[2].let { if (it.startsWith("1er") || it == "premier") 1 else it.toInt() }, m.groupValues[4].toIntOrNull())
                else Triple(m.groupValues[6].toIntOrNull() ?: 0, m.groupValues[5].toIntOrNull() ?: 0, m.groupValues[7].toIntOrNull())
            if (nom.isBlank() || nom.split(' ').size > 5 || valide(annee, mois, jour) == null) return null
            return Intention.Ajouter(nom, mois, jour, annee?.takeIf { it > 1900 })
        }
        R_SUPPR.matchEntire(p)?.let { m -> return Intention.Supprimer(nettoyerNom(brut(m, 1))) }
        R_DE_QUI.matchEntire(p)?.let { m -> return Intention.DeQui(nettoyerNom(brut(m, 1))) }
        if (R_LISTE.containsMatchIn(p)) return Intention.Lister(when {
            Regex("\\b(aujourd hui|du jour)\\b").containsMatchIn(p) -> 0
            Regex("\\bdemain\\b").containsMatchIn(p) -> 1
            Regex("\\bsemaine\\b").containsMatchIn(p) -> 7
            Regex("\\bmois\\b").containsMatchIn(p) -> 31
            else -> 60
        })
        return null
    }

    private fun nettoyerNom(s: String) = s.trim().removePrefix("mon ").removePrefix("ma ").trim()
        .replace(Regex("^(?i)(?:monsieur|madame|mme|m\\.)\\s+"), "").trim()

    fun executer(app: JarvisApp, i: Intention, aujourdhui: LocalDate = LocalDate.now()): String = when (i) {
        is Intention.Ajouter -> {
            val l = ajoutes(app).filterNot { normaliser(it.nom) == normaliser(i.nom) } + Anniv(i.nom, i.mois, i.jour, i.annee, "jarvis")
            sauver(app, l)
            val a = l.last()
            val d = prochaine(a, aujourdhui)
            "🎂 Noté : anniversaire de ${i.nom} le ${i.jour}${if (i.jour == 1) "er" else ""} ${MOIS_AFF[i.mois - 1]}${i.annee?.let { " $it" }.orEmpty()} — " +
                "prochain ${if (d == aujourdhui) "aujourd'hui" else DatesFr.libelle(d, aujourdhui)}${age(a, d)?.let { " ($it ans)" }.orEmpty()}. Je te le rappellerai la veille et le jour même."
        }
        is Intention.Supprimer -> {
            val avant = ajoutes(app)
            val reste = avant.filterNot { normaliser(it.nom) == normaliser(i.nom) || normaliser(it.nom).contains(normaliser(i.nom)) }
            if (reste.size == avant.size) "Je n'avais pas noté l'anniversaire de ${i.nom}" + (if (contacts(app).any { normaliser(it.nom).contains(normaliser(i.nom)) }) " (il vient de ses contacts : modifie-le dans la fiche du contact)." else ".")
            else { sauver(app, reste); "🗑️ Anniversaire de ${i.nom} oublié." }
        }
        is Intention.DeQui -> {
            val n = normaliser(i.nom)
            val l = tous(app).filter { normaliser(it.nom) == n }.ifEmpty { tous(app).filter { normaliser(it.nom).split(' ').any { m -> m == n } || normaliser(it.nom).contains(n) } }
            if (l.isEmpty()) "Je ne connais pas l'anniversaire de ${i.nom}. Dis-moi « l'anniversaire de ${i.nom} c'est le 14 mars » et je le retiens."
            else l.joinToString("\n") { a -> val d = prochaine(a, aujourdhui)
                "🎂 ${a.nom} : le ${a.jour}${if (a.jour == 1) "er" else ""} ${MOIS_AFF[a.mois - 1]}${a.annee?.let { " $it" }.orEmpty()} — " +
                    (if (d == aujourdhui) "c'est aujourd'hui" else "prochain : ${DatesFr.libelle(d, aujourdhui)}, dans ${java.time.temporal.ChronoUnit.DAYS.between(aujourdhui, d)} jour(s)") +
                    (age(a, d)?.let { " ($it ans)" }.orEmpty()) + "." }
        }
        is Intention.Lister -> {
            val l = tous(app)
            if (l.isEmpty()) "Aucun anniversaire connu : ajoute-les dans les fiches de tes contacts, ou dis-moi « l'anniversaire de Marie c'est le 14 mars »."
            else {
                val p = dans(l, aujourdhui, i.jours)
                val periode = when (i.jours) { 0 -> "aujourd'hui"; 1 -> "d'ici demain"; 7 -> "cette semaine"; 31 -> "ce mois-ci"; else -> "dans les ${i.jours} prochains jours" }
                if (p.isEmpty()) "Aucun anniversaire $periode." + dans(l, aujourdhui, 366).firstOrNull()?.let { (a, d) -> " Le prochain : ${a.nom}, ${DatesFr.libelle(d, aujourdhui)}." }.orEmpty()
                else "# 🎂 Anniversaires $periode\n" + p.joinToString("\n") { (a, d) -> "- " + ligne(a, d, aujourdhui) }
            }
        }
    }
}
