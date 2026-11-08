package fr.dd.jarvis.coffre

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class CoffreException(val code: Int, message: String, val version: Int = 0) : Exception(message)

/**
 * Accès HTTPS au coffre du serveur. Le certificat est auto-signé : JARVIS n'accepte QUE celui dont l'empreinte SHA-256
 * a été mémorisée à l'installation (épinglage) — un faux serveur est refusé, même avec un certificat « valide ».
 * Deux adresses : locale (Wi-Fi de la maison) et distante (redirection de port sur la box) ; la bonne est essayée d'abord.
 * En dernier recours, un tunnel SSH vers le serveur ([tunnel], téléphone qui a l'accès SSH) : le coffre reste joignable
 * même si son port n'est pas ouvert sur la box ni dans le pare-feu.
 */
class CoffreClient(
    private val adresses: List<String>,
    private val empreinte: String,
    private val tunnel: (() -> String)? = null,
    private val jeton: () -> String = { "" },
) {
    companion object {
        /** Dernière adresse qui a répondu, par coffre (empreinte) : évite d'attendre les adresses injoignables à chaque fois. */
        private val bonnes = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    private val confiance = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw java.security.cert.CertificateException("non utilisé")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val c = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("aucun certificat")
            val e = MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString("") { "%02x".format(it) }
            if (!e.equals(empreinte.trim(), ignoreCase = true)) throw java.security.cert.CertificateException("empreinte du coffre inattendue ($e) : connexion refusée")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply { init(null, arrayOf(confiance), null) }.socketFactory, confiance)
        .hostnameVerifier { _, _ -> true }   // l'identité est garantie par l'empreinte épinglée, pas par le nom
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun <T> essayer(f: (String) -> T): T {
        val cle = empreinte.trim().lowercase()
        val ordre = (listOfNotNull(bonnes[cle]) + adresses).distinct().filter { it.isNotBlank() }
        require(ordre.isNotEmpty() || tunnel != null) { "Adresse du coffre non configurée." }
        var derniere: Exception? = null
        for (a in ordre) {
            try { return f(a.trimEnd('/')).also { bonnes[cle] = a } }
            catch (e: java.io.IOException) { derniere = e }   // injoignable : adresse suivante
        }
        if (tunnel != null) {
            val parSsh = try { tunnel.invoke() } catch (e: Exception) { derniere = derniere ?: e; null }
            if (parSsh != null) {
                try { return f(parSsh.trimEnd('/')).also { bonnes[cle] = parSsh } }
                catch (e: java.io.IOException) { derniere = e }
            }
        }
        throw CoffreException(0, "Coffre injoignable (${(ordre + listOfNotNull(tunnel?.let { "tunnel SSH" })).joinToString(" / ")}) : ${derniere?.message ?: "pas de réponse"}")
    }

    private fun appel(methode: String, chemin: String, corps: ByteArray? = null, type: String = "application/json"): Pair<Int, ByteArray> = essayer { base ->
        val rb = corps?.toRequestBody(type.toMediaType()) ?: if (methode == "POST" || methode == "PUT") ByteArray(0).toRequestBody(null) else null
        val req = Request.Builder().url(base + chemin).method(methode, rb).apply { jeton().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }.build()
        http.newCall(req).execute().use { r -> r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }

    private fun json(methode: String, chemin: String, corps: JSONObject? = null): JSONObject {
        val (code, b) = appel(methode, chemin, corps?.toString()?.toByteArray())
        val j = runCatching { JSONObject(String(b)) }.getOrDefault(JSONObject())
        if (code !in 200..299) throw CoffreException(code, j.optString("erreur").ifBlank { "erreur HTTP $code" }, j.optInt("version"))
        return j
    }

    private fun enc(chemin: String) = chemin.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    fun sante(): JSONObject = json("GET", "/sante")
    fun init(installation: String, preuve: String, nom: String) = json("POST", "/init", JSONObject().put("installation", installation).put("preuve_recuperation", preuve).put("nom", nom))
    fun inviter(role: String, nom: String) = json("POST", "/invitations", JSONObject().put("role", role).put("nom", nom))
    fun appairer(code: String, nom: String) = json("POST", "/appairer", JSONObject().put("code", code).put("nom", nom))
    fun recuperer(preuve: String, nom: String) = json("POST", "/recuperer", JSONObject().put("preuve_recuperation", preuve).put("nom", nom))
    fun appareils() = json("GET", "/appareils")
    fun revoquer(id: String) = json("DELETE", "/appareils/$id")
    fun etat() = json("GET", "/etat")
    fun changements(depuis: Long) = json("GET", "/objets?depuis=$depuis")

    fun lire(chemin: String): ByteArray {
        val (code, b) = appel("GET", "/objets/" + enc(chemin))
        if (code != 200) throw CoffreException(code, runCatching { JSONObject(String(b)).optString("erreur") }.getOrNull().orEmpty().ifBlank { "lecture impossible ($code)" })
        return b
    }

    /** Envoie une nouvelle version ; [base] = version sur laquelle on s'appuie. Conflit → CoffreException(409, version actuelle). */
    fun ecrire(chemin: String, categorie: String, base: Int, octets: ByteArray): Int {
        val (code, b) = appel("PUT", "/objets/" + enc(chemin) + "?categorie=$categorie&base=$base", octets, "application/octet-stream")
        val j = runCatching { JSONObject(String(b)) }.getOrDefault(JSONObject())
        if (code != 200) throw CoffreException(code, j.optString("erreur").ifBlank { "envoi impossible ($code)" }, j.optInt("version"))
        return j.getInt("version")
    }

    fun supprimer(chemin: String, base: Int): Int = json("DELETE", "/objets/" + enc(chemin) + "?base=$base").optInt("version")
}
