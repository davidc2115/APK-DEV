package fr.dd.jarvis.device

/** Nettoyage du HTML présent dans les notes d'agenda (Google Agenda en insère : <br>, <b>, <a href>…). */
object HtmlText {
    private val link = Regex("(?is)<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>")
    private val br = Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|<li>")
    private val tag = Regex("<[^>]+>")
    val url = Regex("https?://[^\\s<>\")]+")

    fun lines(html: String): List<String> {
        if (html.isBlank()) return emptyList()
        var s = link.replace(html) { m ->
            val u = m.groupValues[1]
            val t = m.groupValues[2].replace(tag, "").trim()
            if (t.isBlank() || t == u) " $u " else "$t $u"
        }
        s = br.replace(s, "\n").replace(tag, "")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        return s.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    fun text(html: String): String = lines(html).joinToString(" · ")
}
