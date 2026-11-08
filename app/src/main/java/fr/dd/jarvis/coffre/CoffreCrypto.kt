package fr.dd.jarvis.coffre

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement du coffre-fort : tout est chiffré SUR LE TÉLÉPHONE avant l'envoi (AES-256-GCM), le serveur ne voit que
 * des données illisibles. Une clé maîtresse (32 octets, créée sur le premier téléphone) donne une clé par catégorie
 * (base, clients, documents, paie, interventions, fichiers) : un salarié ne reçoit que les clés de ses catégories.
 * Le « code de récupération » est la clé maîtresse écrite en 52 caractères (à garder en lieu sûr).
 */
object CoffreCrypto {
    private val alea = SecureRandom()
    private const val MAGIE = "JC1"

    fun nouvelleCleMaitre(): ByteArray = ByteArray(32).also { alea.nextBytes(it) }

    private fun hmac(cle: ByteArray, texte: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(cle, "HmacSHA256")) }.doFinal(texte.toByteArray())

    /** Clé d'une catégorie, dérivée de la clé maîtresse. */
    fun cleCategorie(maitre: ByteArray, categorie: String): ByteArray = hmac(maitre, "jarvis-coffre/cle/$categorie")

    /** Preuve de récupération envoyée au serveur (il n'en garde que l'empreinte) : permet de réenregistrer un téléphone. */
    fun preuveRecuperation(maitre: ByteArray): String = hmac(maitre, "jarvis-coffre/recuperation").joinToString("") { "%02x".format(it) }

    /** Chiffre [clair] pour l'objet [chemin] (le chemin est lié au chiffré : impossible de déplacer un fichier chiffré). */
    fun chiffrer(cle: ByteArray, chemin: String, clair: ByteArray): ByteArray {
        val iv = ByteArray(12).also { alea.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(chemin.toByteArray())
        return MAGIE.toByteArray() + iv + c.doFinal(clair)
    }

    fun dechiffrer(cle: ByteArray, chemin: String, chiffre: ByteArray): ByteArray {
        require(chiffre.size > 3 + 12 + 16 && String(chiffre, 0, 3) == MAGIE) { "objet du coffre illisible (format inconnu)" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(cle, "AES"), GCMParameterSpec(128, chiffre.copyOfRange(3, 15)))
        c.updateAAD(chemin.toByteArray())
        return c.doFinal(chiffre, 15, chiffre.size - 15)
    }

    // ------------------------------------------------------------------ code de récupération (base 32, groupes de 4)

    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // sans I, O, 0, 1 (confusions)

    fun codeRecuperation(maitre: ByteArray): String {
        val sb = StringBuilder()
        var tampon = 0; var bits = 0
        for (b in maitre) {
            tampon = (tampon shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { sb.append(ALPHABET[(tampon shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) sb.append(ALPHABET[(tampon shl (5 - bits)) and 31])
        return sb.chunked(4).joinToString("-")
    }

    fun depuisCode(code: String): ByteArray? {
        val propre = code.uppercase().filter { it.isLetterOrDigit() }
        if (propre.length != 52 || propre.any { it !in ALPHABET }) return null
        val out = java.io.ByteArrayOutputStream()
        var tampon = 0; var bits = 0
        for (c in propre) {
            tampon = (tampon shl 5) or ALPHABET.indexOf(c); bits += 5
            if (bits >= 8) { out.write((tampon shr (bits - 8)) and 0xFF); bits -= 8 }
        }
        return out.toByteArray().takeIf { it.size == 32 }
    }

    fun b64(o: ByteArray): String = java.util.Base64.getEncoder().encodeToString(o)
    fun deB64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    fun sha256(o: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(o).joinToString("") { "%02x".format(it) }
}
