package fr.dd.jarvis.device

import android.content.Context
import java.io.File

/**
 * Mémoire des consignes de l'utilisateur (présentation des fiches, contenu du point du matin…).
 * Source : la note Obsidian « JARVIS/Mémoire JARVIS.md » (modifiable à la main), copie locale en secours.
 * Une consigne par sujet : contacts, briefing, planning, mails, societe, general…
 */
class Memo(private val ctx: Context, private val obsidian: ObsidianWriter) {
    private val local get() = File(ctx.filesDir, "memo.md")
    private val items = linkedMapOf<String, String>()
    @Volatile private var loaded = false

    companion object {
        const val FOLDER = "JARVIS"
        const val FILE = "Mémoire JARVIS.md"
    }

    private fun parse(md: String) {
        items.clear()
        var cur: String? = null
        val buf = StringBuilder()
        fun flush() { cur?.let { k -> buf.toString().trim().takeIf { it.isNotBlank() }?.let { items[k] = it } }; buf.clear() }
        for (line in md.lines()) {
            if (line.startsWith("## ")) { flush(); cur = line.removePrefix("## ").trim().lowercase() }
            else if (cur != null) buf.appendLine(line)
        }
        flush()
    }

    private fun render(): String = buildString {
        append("---\ntype: memoire-jarvis\ntags: [jarvis]\n---\n\n# 🧠 Mémoire JARVIS\n\n")
        append("_Consignes réutilisées par JARVIS. Une rubrique par sujet : tu peux les modifier ici._\n\n")
        items.forEach { (k, v) -> append("## $k\n$v\n\n") }
    }

    @Synchronized
    fun load() {
        val md = obsidian.read(FOLDER, FILE) ?: local.takeIf { it.exists() }?.readText()
        if (md != null) parse(md)
        loaded = true
    }

    @Synchronized
    private fun ensure() { if (!loaded) load() }

    @Synchronized
    private fun persist(): String {
        val md = render()
        runCatching { local.writeText(md) }
        return obsidian.saveIn(FOLDER, FILE.removeSuffix(".md"), md)
    }

    @Synchronized
    fun set(sujet: String, texte: String): String {
        ensure(); items[sujet.trim().lowercase().ifBlank { "general" }] = texte.trim(); return persist()
    }

    @Synchronized
    fun remove(sujet: String): Boolean {
        ensure(); val r = items.remove(sujet.trim().lowercase()) != null; if (r) persist(); return r
    }

    @Synchronized
    fun clear() { ensure(); items.clear(); persist() }

    @Synchronized
    fun all(): Map<String, String> { ensure(); return LinkedHashMap(items) }

    /** Consignes des sujets demandés (+ « general »), prêtes à ajouter à un prompt. Vide s'il n'y en a pas. */
    @Synchronized
    fun pour(vararg sujets: String): String {
        ensure()
        val keys = sujets.map { it.lowercase() } + "general"
        val sel = items.filterKeys { k -> keys.any { k == it } }
        if (sel.isEmpty()) return ""
        return "\n\nConsignes enregistrées par l'utilisateur (à respecter, elles priment sur la mise en forme par défaut) :\n" +
            sel.entries.joinToString("\n") { "- [${it.key}] ${it.value}" }
    }

    @Synchronized
    fun toutes(): String {
        ensure()
        if (items.isEmpty()) return ""
        return "\n\nMémoire de l'utilisateur (consignes durables, à appliquer) :\n" +
            items.entries.joinToString("\n") { "- [${it.key}] ${it.value.take(600)}" }.take(3500)
    }

    private val exclRegex = Regex(
        "(?:\\bsans\\b|\\bsauf\\b|\\bpas de\\b|\\bne pas (?:afficher|inclure|montrer|prendre|mettre|compter)|\\bne plus (?:afficher|inclure|montrer|prendre)|\\bexclure|\\bexclus|\\bignorer?|\\bmasquer|\\bcacher|\\benlever|\\bretirer)" +
            "\\s+(?:le |la |l'|l’|les |un |du |des )?(?:planning |agenda |calendrier |rdv |rendez-vous )?(?:de |d'|d’|du |des )?([\\p{L}0-9'’. -]{2,40}?)(?=[,.;:!?\\n()]| et | ou | mais | quand | sauf |$)",
        RegexOption.IGNORE_CASE
    )

    /** Mots (noms d'agendas) que les consignes demandent d'exclure : « sans le planning de Thomas » → [thomas]. */
    @Synchronized
    fun agendaExclus(): List<String> {
        ensure()
        val out = linkedSetOf<String>()
        items.forEach { (k, v) ->
            if (k in setOf("contacts", "mails", "societe")) return@forEach
            v.lines().forEach { line ->
                exclRegex.findAll(line).forEach { m ->
                    m.groupValues[1].lowercase().split(' ', '-', '\'', '’').map { it.trim('.') }
                        .filter { it.length >= 3 && it !in setOf("les", "des", "mon", "ton", "son", "tout", "plus", "ses", "mes") }
                        .forEach { out.add(it) }
                }
            }
        }
        return out.toList()
    }
}
