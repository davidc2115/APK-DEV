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
    /** Tunnel SSH rouvert à neuf : celui gardé de la veille est souvent mort après une nuit de veille du téléphone. */
    private val tunnelNeuf: (() -> String)? = null,
) {
    companion object {
        /** Dernière adresse qui a répondu, par coffre (empreinte) : évite d'attendre les adresses injoignables à chaque fois. */
        private val bonnes = java.util.concurrent.ConcurrentHashMap<String, String>()

        /** Erreur réseau en clair. */
        internal fun libelle(e: Throwable): String {
            val m = generateSequence(e) { it.cause }.take(4).mapNotNull { it.message }.joinToString(" ").ifBlank { e.javaClass.simpleName }
            return when {
                m.contains("empreinte du coffre", true) -> m
                e is java.net.SocketTimeoutException || Regex("timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "pas de réponse"
                Regex("reset|broken pipe|connection abort|unexpected end of stream", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "connexion coupée"
                Regex("refused|ECONNREFUSED", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "refusée (port fermé)"
                Regex("unreachable|ENETUNREACH|EHOSTUNREACH|No route", RegexOption.IGNORE_CASE).containsMatchIn(m) -> "réseau inaccessible"
                e is java.net.UnknownHostException -> "adresse inconnue"
                e is java.net.ConnectException -> "refusée (port fermé)"
                else -> m.take(90)
            }
        }

        /** « https://192.168.1.133:8443 » → « 192.168.1.133 ». */
        internal fun court(adresse: String) = runCatching { java.net.URI(adresse).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: adresse

        /** Erreur passagère (réseau qui se rétablit au réveil, connexion périmée) : un nouvel essai a des chances de passer. */
        internal fun passagere(l: String) = l == "pas de réponse" || l == "connexion coupée" || l == "réseau inaccessible"
    }

    /** Erreurs du dernier essai, adresse par adresse (pour le diagnostic). */
    var erreurs: List<Pair<String, String>> = emptyList()
        private set

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
        val err = mutableListOf<Pair<String, String>>()
        // Deux tours : au réveil du téléphone le réseau se rétablit souvent pendant le premier (Wi-Fi qui se reconnecte).
        for (tourNo in 1..2) {
            err.clear()
            for (a in ordre) {
                try { return f(a.trimEnd('/')).also { bonnes[cle] = a; erreurs = emptyList() } }
                catch (e: java.io.IOException) { err += court(a) to libelle(e) }   // injoignable : adresse suivante
            }
            if (tunnel != null) {
                val essais = listOfNotNull(tunnel, if (tourNo == 2 || err.any { passagere(it.second) }) tunnelNeuf else null)
                for ((i, t) in essais.withIndex()) {
                    val parSsh = try { t.invoke() } catch (e: Exception) { err += "tunnel SSH" to (e.message ?: e.javaClass.simpleName).take(120); null } ?: break
                    try { return f(parSsh.trimEnd('/')).also { bonnes[cle] = parSsh; erreurs = emptyList() } }
                    catch (e: java.io.IOException) { err += (if (i == 0) "tunnel SSH" else "tunnel SSH rouvert") to libelle(e) }
                }
            }
            if (tourNo == 1 && err.any { passagere(it.second) }) {
                http.connectionPool.evictAll()
                Thread.sleep(4000)
            } else break
        }
        erreurs = err.toList()
        throw CoffreException(0, "Coffre injoignable — " + err.joinToString(" · ") { "${it.first} : ${it.second}" })
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
