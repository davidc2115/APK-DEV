package fr.dd.jarvis.device

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactInfo(
    val id: Long,
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    val company: String,
    val note: String
) {
    fun toPrompt(): String = buildString {
        append("Nom : $name\n")
        if (company.isNotBlank()) append("Société : $company\n")
        if (phones.isNotEmpty()) append("Téléphones : ${phones.joinToString(", ")}\n")
        if (emails.isNotEmpty()) append("Emails : ${emails.joinToString(", ")}\n")
        if (note.isNotBlank()) append("Notes : $note\n")
    }
}

class ContactsRepo(private val ctx: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Recherche par nom, numéro, email ou société. [query] vide : premiers contacts par ordre alphabétique. */
    fun search(query: String, limit: Int = 10000): List<ContactInfo> {
        if (!hasPermission()) return emptyList()
        val ids = LinkedHashSet<Long>()
        val q = query.trim()
        val selection: String?
        val args: Array<String>?
        if (q.isEmpty()) {
            selection = null
            args = null
        } else {
            selection = "(${ContactsContract.Data.MIMETYPE} IN (?,?,?,?)) AND (${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ? OR ${ContactsContract.Data.DATA1} LIKE ?)"
            args = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                "%$q%", "%$q%"
            )
        }
        val idProj = arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, idProj, selection, args,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext() && ids.size < limit) ids.add(c.getLong(0))
        }
        if (ids.isEmpty()) return emptyList()

        val map = LinkedHashMap<Long, MutableList<Array<String?>>>()
        val inList = ids.joinToString(",")
        val proj = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1
        )
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, proj,
            "${ContactsContract.Data.CONTACT_ID} IN ($inList)", null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " COLLATE NOCASE ASC"
        )?.use { c ->
            while (c.moveToNext()) {
                map.getOrPut(c.getLong(0)) { mutableListOf() }
                    .add(arrayOf(c.getString(1), c.getString(2), c.getString(3)))
            }
        }
        return ids.mapNotNull { id ->
            val rows = map[id] ?: return@mapNotNull null
            val phones = rows.filter { it[1] == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE }.mapNotNull { it[2] }.distinct()
            val emails = rows.filter { it[1] == ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE }.mapNotNull { it[2] }.distinct()
            val company = rows.firstOrNull { it[1] == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE }?.get(2).orEmpty()
            val note = rows.firstOrNull { it[1] == ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE }?.get(2).orEmpty()
            ContactInfo(id, rows.first()[0] ?: "(sans nom)", phones, emails, company, note)
        }
    }

    fun create(name: String, phone: String, email: String, company: String, note: String): Boolean {
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        fun data(mime: String, vararg kv: Pair<String, Any?>) {
            val b = ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, mime)
            kv.forEach { (k, v) -> b.withValue(k, v) }
            ops.add(b.build())
        }
        data(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME to name
        )
        if (phone.isNotBlank()) data(
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.NUMBER to phone,
            ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        )
        if (email.isNotBlank()) data(
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.ADDRESS to email,
            ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_WORK
        )
        if (company.isNotBlank()) data(
            ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Organization.COMPANY to company
        )
        if (note.isNotBlank()) data(
            ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Note.NOTE to note
        )
        ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
        return true
    }

    /** Met à jour les champs non nuls (le premier numéro / email existant est remplacé, sinon ajouté). */
    fun update(contactId: Long, name: String?, phone: String?, email: String?, company: String?, note: String?): Boolean {
        val rawId = rawContactId(contactId) ?: return false
        name?.let { upsert(rawId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, it, null) }
        phone?.let { upsert(rawId, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Phone.NUMBER, it, ContactsContract.CommonDataKinds.Phone.TYPE to ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) }
        email?.let { upsert(rawId, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Email.ADDRESS, it, ContactsContract.CommonDataKinds.Email.TYPE to ContactsContract.CommonDataKinds.Email.TYPE_WORK) }
        company?.let { upsert(rawId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Organization.COMPANY, it, null) }
        note?.let { upsert(rawId, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE, ContactsContract.CommonDataKinds.Note.NOTE, it, null) }
        return true
    }

    private fun rawContactId(contactId: Long): Long? {
        ctx.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(contactId.toString()), null
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }

    private fun upsert(rawId: Long, mime: String, col: String, value: String, extra: Pair<String, Any>?) {
        val where = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(rawId.toString(), mime)
        val v = ContentValues().apply { put(col, value) }
        val updated = ctx.contentResolver.update(ContactsContract.Data.CONTENT_URI, v, where, args)
        if (updated == 0 && value.isNotBlank()) {
            val ins = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, mime)
                put(col, value)
                extra?.let { (k, x) -> if (x is Int) put(k, x) else put(k, x.toString()) }
            }
            ctx.contentResolver.insert(ContactsContract.Data.CONTENT_URI, ins)
        }
    }

    // ---------------------------------------------------------------- nettoyage en masse des numéros

    private fun cleanNum(n: String): String = n.replace(Regex("[^0-9+]"), "").let { if (it.startsWith("00")) "+" + it.drop(2) else it }

    /** Numéro français ramené à 10 chiffres « 0612345678 », ou null si ce n'est pas un numéro français. */
    private fun nationalFr(n: String): String? {
        val c = cleanNum(n)
        val d = when {
            c.startsWith("+330") -> "0" + c.drop(4)
            c.startsWith("+33") -> "0" + c.drop(3)
            else -> c
        }
        return d.takeIf { it.length == 10 && it.startsWith("0") && it.all(Char::isDigit) }
    }

    private fun formater(n: String, format: String): String {
        val nat = nationalFr(n) ?: return n.trim()
        return if (format.startsWith("inter")) "+33 " + nat[1] + " " + nat.drop(2).chunked(2).joinToString(" ")
        else nat.chunked(2).joinToString(" ")
    }

    private fun cle(n: String): String = nationalFr(n) ?: cleanNum(n)

    /**
     * Met tous les numéros au même format (« 06 12 34 56 78 » ou international) et supprime les doublons d'un même contact.
     * [apercu] : ne modifie rien, annonce seulement ce qui serait fait.
     */
    fun normaliserNumeros(format: String, doublons: Boolean, apercu: Boolean): String {
        if (!hasPermission()) return "Permission contacts manquante (lecture + écriture)."
        data class Ligne(val id: Long, val contact: Long, val numero: String, val type: Int)
        val lignes = mutableListOf<Ligne>()
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE),
            "${ContactsContract.Data.MIMETYPE}=?", arrayOf(ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE), null
        )?.use { c ->
            while (c.moveToNext()) lignes.add(Ligne(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getInt(3)))
        }
        if (lignes.isEmpty()) return "Aucun numéro trouvé dans les contacts."
        val ops = ArrayList<ContentProviderOperation>()
        var reformates = 0; var supprimes = 0; var contacts = HashSet<Long>()
        val exemples = mutableListOf<String>()
        for ((cid, l) in lignes.groupBy { it.contact }) {
            val vus = HashSet<String>()
            // Mobiles d'abord : en cas de doublon, on garde la ligne la plus utile.
            for (r in l.sortedBy { if (it.type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) 0 else 1 }) {
                val k = cle(r.numero)
                if (k.isBlank()) continue
                if (doublons && !vus.add(k)) {
                    supprimes++; contacts.add(cid)
                    if (exemples.size < 8) exemples.add("🗑️ ${r.numero} (doublon)")
                    ops.add(ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(r.id.toString())).build())
                    continue
                }
                val nouveau = formater(r.numero, format)
                if (nouveau != r.numero) {
                    reformates++; contacts.add(cid)
                    if (exemples.size < 8) exemples.add("✏️ ${r.numero} → $nouveau")
                    ops.add(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(r.id.toString()))
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, nouveau).build())
                }
            }
        }
        val bilan = "$reformates numéro(s) reformaté(s), $supprimes doublon(s) à supprimer, sur ${contacts.size} contact(s) (${lignes.size} numéros analysés)."
        val ex = if (exemples.isEmpty()) "" else "\n" + exemples.joinToString("\n")
        if (apercu) return "👀 Aperçu : $bilan$ex"
        if (ops.isEmpty()) return "✅ Rien à modifier : tous les numéros sont déjà propres."
        var faits = 0
        for (lot in ops.chunked(150)) {
            try { ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(lot)); faits += lot.size } catch (e: Exception) { return "⚠️ Interrompu après $faits modification(s) : ${e.message}" }
        }
        return "✅ Terminé : $bilan$ex"
    }
}
