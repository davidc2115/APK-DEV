package fr.dd.jarvis.ai

import fr.dd.jarvis.JarvisApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Mémoire des appareils du réseau et des adresses IP publiques vues (pour retrouver et joindre ses appareils, même à distance). */
object Registre {
    private fun f(app: JarvisApp) = File(app.filesDir, "reseau_registre.json")

    private fun lire(app: JarvisApp): JSONObject = runCatching { JSONObject(f(app).readText()) }.getOrDefault(JSONObject())

    private fun ecrire(app: JarvisApp, o: JSONObject) { runCatching { f(app).writeText(o.toString()) } }

    private val fmt get() = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH)

    /** Enregistre une IP publique ; retourne un message si elle a changé depuis la dernière fois (IP dynamique). */
    @Synchronized fun ipPublique(app: JarvisApp, ip: String, reseau: String): String {
        if (ip.isBlank()) return ""
        val o = lire(app)
        val h = o.optJSONArray("ip_publiques") ?: JSONArray()
        val dernier = if (h.length() > 0) h.getJSONObject(h.length() - 1) else null
        val now = System.currentTimeMillis()
        var msg = ""
        if (dernier == null || dernier.optString("ip") != ip) {
            h.put(JSONObject().put("ip", ip).put("reseau", reseau).put("ts", now))
            msg = if (dernier != null) "⚠️ L'IP publique a changé : ${dernier.optString("ip")} → $ip (adresse dynamique : pour une connexion distante fiable, utilise un nom DDNS)." else "IP publique enregistrée : $ip."
        } else dernier.put("vu", now)
        while (h.length() > 15) h.remove(0)
        o.put("ip_publiques", h); ecrire(app, o)
        return msg
    }

    fun derniereIpPublique(app: JarvisApp): String = lire(app).optJSONArray("ip_publiques")?.let { if (it.length() > 0) it.getJSONObject(it.length() - 1).optString("ip") else "" }.orEmpty()

    fun nombreAppareils(app: JarvisApp): Int = lire(app).optJSONObject("appareils")?.length() ?: 0

    @Synchronized fun appareils(app: JarvisApp, scan: List<Reseau.Appareil>, reseau: String): Int {
        val o = lire(app)
        val m = o.optJSONObject("appareils") ?: JSONObject()
        val now = System.currentTimeMillis()
        var nouveaux = 0
        for (d in scan) {
            val cle = d.ip
            val e = m.optJSONObject(cle) ?: JSONObject().put("premier", now).also { nouveaux++ }
            e.put("ip", d.ip).put("type", d.type).put("ports", JSONArray(d.ports)).put("vu", now).put("reseau", reseau)
            if (d.nom.isNotBlank()) e.put("hote", d.nom)
            if (d.titre.isNotBlank()) e.put("titre", d.titre)
            if (d.info.isNotBlank()) { if (d.info.startsWith("MAC ")) e.put("mac", d.info.removePrefix("MAC ")) else e.put("info", d.info) }
            m.put(cle, e)
        }
        o.put("appareils", m); ecrire(app, o)
        return nouveaux
    }

    @Synchronized fun nommer(app: JarvisApp, ip: String, nom: String): Boolean {
        val o = lire(app); val m = o.optJSONObject("appareils") ?: return false
        val e = m.optJSONObject(ip) ?: return false
        e.put("alias", nom); ecrire(app, o); return true
    }

    fun alias(app: JarvisApp): Map<String, String> {
        val m = lire(app).optJSONObject("appareils") ?: return emptyMap()
        return m.keys().asSequence().mapNotNull { k -> m.getJSONObject(k).optString("alias").takeIf { it.isNotBlank() }?.let { k to it } }.toMap()
    }

    fun liste(app: JarvisApp): String {
        val o = lire(app)
        val m = o.optJSONObject("appareils")
        val sb = StringBuilder("# 🗂️ Appareils enregistrés\n\n")
        val h = o.optJSONArray("ip_publiques")
        if (h != null && h.length() > 0) {
            sb.append("**🌍 IP publiques vues** :\n")
            for (i in h.length() - 1 downTo maxOf(0, h.length() - 5)) h.getJSONObject(i).let { sb.append("- ${it.optString("ip")} (${fmt.format(java.util.Date(it.optLong("ts")))})\n") }
            sb.append("\n")
        }
        if (m == null || m.length() == 0) return sb.append("Aucun appareil enregistré : lance une analyse du réseau.").toString()
        sb.append("| IP | Nom | Type | Ports | Vu le |\n|---|---|---|---|---|\n")
        m.keys().asSequence().map { m.getJSONObject(it) }.sortedBy { x -> x.optString("ip").split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 256 + b } }.forEach { e ->
            val nom = e.optString("alias").ifBlank { e.optString("info").ifBlank { e.optString("hote").ifBlank { e.optString("titre").ifBlank { e.optString("mac") } } } }
            sb.append("| ${e.optString("ip")} | ${nom.take(30)} | ${e.optString("type")} | ${e.optJSONArray("ports")?.let { a -> (0 until a.length()).joinToString(",") { a.getInt(it).toString() } }.orEmpty()} | ${fmt.format(java.util.Date(e.optLong("vu")))} |\n")
        }
        return sb.toString()
    }
}
