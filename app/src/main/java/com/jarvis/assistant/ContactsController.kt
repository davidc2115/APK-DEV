package com.jarvis.assistant

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

object ContactsController {

    /**
     * Recherche le premier numéro de téléphone pour le nom donné.
     * Utilise le CONTENT_FILTER_URI natif d'Android pour une recherche
     * insensible à la casse et tolérante aux fautes / prénoms / noms.
     */
    fun findPhoneNumber(context: Context, name: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val cleanQuery = name.trim()
        if (cleanQuery.isBlank()) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        // 1. Recherche officielle native Android (CONTENT_FILTER_URI)
        val filterUri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(cleanQuery)
        )

        try {
            context.contentResolver.query(filterUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)
                    if (!number.isNull@findPhoneNumber.isBlank()) {
                        return number
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback : recherche large sur CONTENT_URI
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$cleanQuery%"),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Recherche les contacts correspondant à une requête et les retourne sous forme de texte.
     */
    fun searchContacts(context: Context, query: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée. Cliquez sur le bouton 'Demander Contacts' dans le dashboard."
        }

        val cleanQuery = query.trim()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val filterUri = Uri.withAppendedPath(
            ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
            Uri.encode(cleanQuery)
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(filterUri, projection, null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC")

            cursor?.use { c ->
                if (c.count == 0) return "👤 Aucun contact trouvé pour « $query »."

                val sb = StringBuilder("👤 **Résultats de la recherche pour « $query »** :\n\n")
                var count = 0
                val seenNumbers = mutableSetOf<String>()

                while (c.moveToNext() && count < 10) {
                    val displayName = c.getString(0) ?: "Inconnu"
                    val rawPhone = c.getString(1) ?: "Pas de numéro"
                    val cleanPhone = rawPhone.replace(" ", "")

                    if (!seenNumbers.contains(cleanPhone)) {
                        seenNumbers.add(cleanPhone)
                        sb.append("${count + 1}. **$displayName** : $rawPhone\n")
                        count++
                    }
                }
                sb.toString()
            } ?: "❌ Impossible d'effectuer la recherche dans les contacts."
        } catch (e: Exception) {
            "❌ Erreur lors de la recherche des contacts : ${e.message}"
        }
    }

    fun addContact(context: Context, name: String, phone: String, email: String = ""): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification des contacts non accordée."
        }

        return try {
            val ops = ArrayList<ContentProviderOperation>()

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build()
            )

            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )

            if (email.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                        .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
                        .build()
                )
            }

            context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "✅ Contact **$name** ($phone) ajouté avec succès !"
        } catch (e: Exception) {
            "❌ Échec de l'ajout du contact : ${e.message}"
        }
    }

    fun getContactList(context: Context, count: Int = 20): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'accès aux contacts non accordée."
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        return try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "👤 Aucun contact enregistré dans le téléphone."

                val sb = StringBuilder("👤 **Liste des contacts (${minOf(count, c.count)})** :\n\n")
                var idx = 0
                val seenNumbers = mutableSetOf<String>()

                while (c.moveToNext() && idx < count) {
                    val displayName = c.getString(0) ?: "Inconnu"
                    val phone = c.getString(1) ?: ""
                    val cleanPhone = phone.replace(" ", "")

                    if (!seenNumbers.contains(cleanPhone)) {
                        seenNumbers.add(cleanPhone)
                        sb.append("${idx + 1}. **$displayName** — $phone\n")
                        idx++
                    }
                }
                sb.toString()
            } ?: "❌ Échec de la lecture de la liste des contacts."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture des contacts : ${e.message}"
        }
    }
}
