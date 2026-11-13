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

    private fun normaliserMac(raw: String): String = raw.uppercase().replace('-', ':').trim()
        .takeIf { it.matches(Regex("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}")) }.orEmpty()

    @Synchronized fun appareils(app: JarvisApp, scan: List<Reseau.Appareil>, reseau: String): Int {
        val o = lire(app)
        val m = o.optJSONObject("appareils") ?: JSONObject()
        val now = System.currentTimeMillis()
        var nouveaux = 0
        for (d in scan) {
            val cle = d.ip
            val mac = normaliserMac(d.info.removePrefix("MAC "))
            // Une adresse MAC identifie généralement la carte réseau même si le DHCP change son IP.
            // On transfère alors le nom personnalisé et l'historique vers la nouvelle adresse.
            val ancienneCle = if (mac.isNotBlank()) m.keys().asSequence().firstOrNull { k ->
                k != cle && normaliserMac(m.optJSONObject(k)?.optString("mac").orEmpty()) == mac
            } else null
            val ancien = ancienneCle?.let { m.optJSONObject(it) }
            val e = m.optJSONObject(cle) ?: JSONObject().put("premier", ancien?.optLong("premier")?.takeIf { it > 0 } ?: now)
                .also { if (ancien == null) nouveaux++ }
            if (ancien != null) {
                if (e.optString("alias").isBlank() && ancien.optString("alias").isNotBlank()) e.put("alias", ancien.optString("alias"))
                val historique = e.optJSONArray("ips_precedentes") ?: JSONArray()
                historique.put(ancienneCle)
                val anciens = ancien.optJSONArray("ips_precedentes")
                if (anciens != null) for (j in 0 until anciens.length()) historique.put(anciens.optString(j))
                val uniques = (0 until historique.length()).map { historique.optString(it) }.filter { it.isNotBlank() && it != cle }.distinct().takeLast(20)
                e.put("ips_precedentes", JSONArray(uniques))
                m.remove(ancienneCle!!)
            }
            // Une source sans ports ni type précis (liste de la Freebox) n'efface pas ce qu'une analyse a trouvé.
            e.put("ip", d.ip).put("vu", now).put("reseau", reseau)
            if (d.ports.isNotEmpty() || !e.has("ports")) e.put("ports", JSONArray(d.ports))
            if (!d.type.startsWith("❔") || e.optString("type").isBlank()) e.put("type", d.type)
            if (mac.isNotBlank()) e.put("mac", mac)
            if (d.nom.isNotBlank()) e.put("hote", d.nom)
            if (d.titre.isNotBlank()) e.put("titre", d.titre)
            if (d.info.isNotBlank() && !d.info.startsWith("MAC ")) e.put("info", d.info)
            m.put(cle, e)
        }
        o.put("appareils", m); ecrire(app, o)
        return nouveaux
    }

    @Synchronized fun nommer(app: JarvisApp, ip: String, nom: String): Boolean {
        val adresse = ip.trim()
        val alias = nom.trim().take(60)
        if (!Reseau.prive(adresse) || alias.isBlank()) return false
        val o = lire(app)
        val m = o.optJSONObject("appareils") ?: JSONObject()
        val now = System.currentTimeMillis()
        val e = m.optJSONObject(adresse) ?: JSONObject().put("ip", adresse).put("premier", now).put("type", "❔ à identifier")
        e.put("alias", alias).put("ip", adresse)
        m.put(adresse, e); o.put("appareils", m); ecrire(app, o)
        return true
    }

    /** Tous les appareils enregistrés (copie), les plus récemment vus d'abord. */
    fun tous(app: JarvisApp): List<JSONObject> {
        val m = lire(app).optJSONObject("appareils") ?: return emptyList()
        return m.keys().asSequence().mapNotNull { m.optJSONObject(it) }.sortedByDescending { it.optLong("vu") }.toList()
    }

    fun nomDe(e: JSONObject): String = e.optString("alias").ifBlank { e.optString("hote").ifBlank { e.optString("titre").ifBlank { e.optString("info") } } }.ifBlank { e.optString("ip") }

    /** Appareil désigné par son IP, son nom donné, son nom réseau ou son type. */
    fun trouver(app: JarvisApp, nomOuIp: String): JSONObject? {
        val v = nomOuIp.trim(); if (v.isBlank()) return null
        val l = tous(app)
        l.firstOrNull { it.optString("ip") == v || it.optString("mac").equals(v, true) }?.let { return it }
        val n = fr.dd.jarvis.societe.core.normaliser(v)
        fun champs(e: JSONObject) = listOf("alias", "hote", "titre", "info", "type").map { fr.dd.jarvis.societe.core.normaliser(e.optString(it)) }
        l.firstOrNull { e -> champs(e).first() == n }?.let { return it }
        return l.filter { e -> champs(e).any { it.isNotBlank() && (it.contains(n) || n.contains(it)) } }.let { if (it.size == 1) it[0] else it.firstOrNull { e -> champs(e)[0].contains(n) } }
    }

    /** Mémorise un accès à distance configuré pour un appareil (port, port externe, adresse publique). */
    @Synchronized fun noterAcces(app: JarvisApp, ip: String, acces: JSONObject?, portLan: Int) {
        val o = lire(app); val m = o.optJSONObject("appareils") ?: return
        val e = m.optJSONObject(ip) ?: return
        val l = e.optJSONArray("distant") ?: JSONArray()
        val garde = JSONArray(); for (i in 0 until l.length()) l.optJSONObject(i)?.takeIf { it.optInt("port") != portLan }?.let { garde.put(it) }
        if (acces != null) garde.put(acces)
        e.put("distant", garde); m.put(ip, e); o.put("appareils", m); ecrire(app, o)
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
        sb.append("| IP actuelle | Nom mémorisé | Type | MAC | IP précédentes | Vu le |\n|---|---|---|---|---|---|\n")
        m.keys().asSequence().map { m.getJSONObject(it) }.sortedBy { x -> x.optString("ip").split(".").map { it.toIntOrNull() ?: 0 }.fold(0L) { a, b -> a * 256 + b } }.forEach { e ->
            val nom = e.optString("alias").ifBlank { e.optString("hote").ifBlank { e.optString("info").ifBlank { e.optString("titre") } } }
            val anciennes = e.optJSONArray("ips_precedentes")?.let { a -> (0 until a.length()).joinToString(", ") { a.optString(it) } }.orEmpty()
            sb.append("| ${e.optString("ip")} | ${nom.take(30)} | ${e.optString("type")} | ${e.optString("mac")} | ${anciennes} | ${fmt.format(java.util.Date(e.optLong("vu")))} |\n")
        }
        return sb.toString()
    }
}
