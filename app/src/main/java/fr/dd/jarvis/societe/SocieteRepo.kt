package fr.dd.jarvis.societe

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fr.dd.jarvis.societe.core.Societe
import fr.dd.jarvis.societe.core.SocieteData
import fr.dd.jarvis.societe.core.jsonSociete
import java.io.File

/** Données Société persistées dans le stockage privé et observables par Compose. */
class SocieteRepo(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val dir = File(ctx.filesDir, "societe").apply { mkdirs() }
    private val file = File(dir, "data.json")
    private val backup = File(dir, "data.json.bak")

    /**
     * Copie de l'identité de la société (nom, adresse, mail, SIRET…) hors du dossier Société : si la base revient un
     * jour sans identité (fichier perdu, synchronisation ou restauration d'une vieille copie), elle est reprise d'ici.
     */
    private val secours = ctx.applicationContext.getSharedPreferences("societe_identite", Context.MODE_PRIVATE)
    private val journal = File(ctx.filesDir, "journal-identite-societe.txt")

    var data by mutableStateOf(load())
        private set

    private fun noter(t: String) = runCatching {
        journal.appendText("${java.time.LocalDateTime.now().withNano(0)} $t\n")
        if (journal.length() > 64_000) journal.writeText(journal.readLines().takeLast(200).joinToString("\n") + "\n")
    }

    private fun aUneIdentite(s: Societe) = s.nom.isNotBlank() || s.siret.isNotBlank() || s.email.isNotBlank() || s.adresse.isNotBlank()

    private fun garderIdentite(s: Societe) {
        if (aUneIdentite(s)) runCatching { secours.edit().putString("societe", jsonSociete.encodeToString(Societe.serializer(), s)).apply() }
    }

    /** Base chargée sans identité alors qu'une identité avait été enregistrée : reprise de la dernière connue. */
    private fun avecIdentite(d: SocieteData): SocieteData {
        if (aUneIdentite(d.societe)) return d
        val copie = secours.getString("societe", null)?.let { runCatching { jsonSociete.decodeFromString(Societe.serializer(), it) }.getOrNull() } ?: return d
        if (!aUneIdentite(copie)) return d
        noter("base sans identité au chargement : identité « ${copie.nom} » reprise de la copie de secours")
        return d.copy(societe = copie)
    }

    /** Champs d'identité : jamais vidés par une écriture automatique (seulement par toi, dans le formulaire ou le chat). */
    private fun proteger(avant: Societe, apres: Societe): Societe {
        fun g(a: String, b: String) = if (a.isNotBlank() && b.isBlank()) a else b
        val p = apres.copy(nom = g(avant.nom, apres.nom), forme = g(avant.forme, apres.forme), siret = g(avant.siret, apres.siret),
            tvaNum = g(avant.tvaNum, apres.tvaNum), adresse = g(avant.adresse, apres.adresse), tel = g(avant.tel, apres.tel),
            email = g(avant.email, apres.email), iban = g(avant.iban, apres.iban), bic = g(avant.bic, apres.bic), rcs = g(avant.rcs, apres.rcs),
            codeApe = g(avant.codeApe, apres.codeApe), mentions = g(avant.mentions, apres.mentions))
        if (p != apres) noter("écriture automatique qui vidait l'identité, bloquée (" +
            Throwable().stackTrace.drop(2).take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" } + ")")
        return p
    }

    private fun decode(source: File): SocieteData =
        jsonSociete.decodeFromString(SocieteData.serializer(), source.readText())

    /**
     * Ne jamais transformer silencieusement une base Société illisible en base vide :
     * cela faisait disparaître tous les clients de l'onglet alors que leurs fiches Markdown
     * existaient encore dans Obsidian. On conserve le fichier défectueux et restaure le dernier
     * instantané JSON valide avant de laisser l'application continuer.
     */
    private fun load(): SocieteData {
        if (file.exists()) {
            runCatching { return avecIdentite(decode(file)) }
            // Le fichier principal est invalide : le mettre de côté sans toucher à la sauvegarde.
            val damaged = File(dir, "data.corrompu.${System.currentTimeMillis()}.json")
            runCatching { file.copyTo(damaged, overwrite = false) }
        }

        if (backup.exists()) {
            val restored = runCatching { decode(backup) }.getOrNull()
            if (restored != null) {
                // Restaurer immédiatement le fichier principal : les prochaines écritures repartiront
                // d'une base saine et ne recopieront pas le JSON corrompu dans le backup.
                runCatching {
                    val tmp = File(dir, "data.json.restore.tmp")
                    backup.copyTo(tmp, overwrite = true)
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                    if (decode(file) != restored) throw java.io.IOException("Vérification de restauration impossible.")
                }
                return avecIdentite(restored)
            }
        }

        // Aucun fichier exploitable : conserver les fichiers pour diagnostic et démarrer vide (identité reprise si connue).
        return avecIdentite(SocieteData())
    }

    /**
     * Relit le fichier persistant avant les consultations importantes.
     * Utile lorsque l'écriture a été déclenchée par un agent en arrière-plan :
     * l'écran ne doit jamais rester sur un instantané chargé avant cette écriture.
     */
    @Synchronized
    fun refresh(): SocieteData {
        val relu = load()
        data = relu
        return relu
    }

    /** [effacerIdentite] : écriture voulue par l'utilisateur (formulaire, demande à JARVIS) — un champ peut être vidé. */
    @Synchronized
    fun update(effacerIdentite: Boolean = false, synchroniserCoffres: Boolean = true, f: (SocieteData) -> SocieteData) {
        // Plusieurs écritures peuvent venir de l'écran Société, d'Hermès ou des agents en parallèle.
        // Repartir du dernier fichier validé sous le même verrou évite qu'une mise à jour basée sur
        // un ancien instantané efface les créations ou modifications d'un autre thread.
        val base = load()
        data = base
        val brute = f(base)
        val nouvelle = if (effacerIdentite) brute else brute.copy(societe = proteger(base.societe, brute.societe))
        val tmp = File(dir, "data.json.tmp")
        val contenu = jsonSociete.encodeToString(SocieteData.serializer(), nouvelle)
        tmp.writeText(contenu)
        if (!tmp.exists() || tmp.length() == 0L) throw java.io.IOException("Écriture de la base Société impossible.")
        // La sauvegarde ne remplace jamais un instantané valide par un fichier principal illisible.
        if (file.exists()) {
            runCatching { decode(file) }.onSuccess {
                file.copyTo(backup, overwrite = true)
            }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        val relu = decode(file)
        if (jsonSociete.encodeToString(SocieteData.serializer(), relu) != contenu)
            throw java.io.IOException("La base Société n'a pas été enregistrée intégralement.")
        data = relu
        garderIdentite(relu.societe)
        // Les brouillons sont conservés localement sans déclencher une synchronisation réseau à chaque frappe.
        if (synchroniserCoffres) {
            runCatching { fr.dd.jarvis.coffre.Coffre.signalerChangement(appCtx) }
            runCatching { MiroirFreebox.signalerChangement(appCtx) }
        }
    }
}
