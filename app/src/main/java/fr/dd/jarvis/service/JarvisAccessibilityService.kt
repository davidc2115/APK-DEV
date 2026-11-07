package fr.dd.jarvis.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Service d'accessibilité : lit l'écran et effectue des gestes à la demande de JARVIS.
 * Il doit être activé manuellement dans Réglages Android › Accessibilité.
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
        private val uiEvents = ArrayDeque<String>()
        @Volatile private var uiEventSequence: Long = 0L
        private val uiEventsLock = Any()
        internal fun recentUiEvents(): Pair<Long, List<String>> = synchronized(uiEventsLock) {
            uiEventSequence to uiEvents.toList()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString()?.take(120).orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "fenêtre/application changée"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "contenu actualisé"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "défilement"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "élément cliqué"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "champ modifié"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "fenêtres modifiées"
            else -> "événement ${event.eventType}"
        }
        val cls = event.className?.toString()?.substringAfterLast('.')?.take(80).orEmpty()
        val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT).format(java.util.Date())
        val line = "$stamp · $pkg · $kind" + if (cls.isNotBlank()) " · $cls" else ""
        synchronized(uiEventsLock) {
            uiEventSequence++
            if (uiEvents.lastOrNull() != line) uiEvents.addLast(line)
            while (uiEvents.size > 30) uiEvents.removeFirst()
        }
    }

    override fun onInterrupt() {}

    fun global(action: Int): Boolean = performGlobalAction(action)

    /**
     * Expose une représentation structurée de l'écran réellement affiché :
     * application, composants, libellés, états et coordonnées. Ce n'est pas une capture vidéo
     * ni un flux de pixels ; les champs marqués mot de passe sont volontairement masqués.
     */
    fun readScreen(): String = inspectScreen()

    fun inspectScreen(): String {
        val root = rootInActiveWindow ?: return "Écran inaccessible : aucune fenêtre active exposée par Android."
        val sb = StringBuilder()
        val pkg = root.packageName?.toString().orEmpty()
        sb.append("APPLICATION AU PREMIER PLAN : ").append(pkg.ifBlank { "inconnue" }).append('\n')
        sb.append("ARBRE D'INTERFACE ACCESSIBILITÉ (coordonnées en pixels écran) :\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || sb.length > 9500) return
            val rect = android.graphics.Rect()
            n.getBoundsInScreen(rect)
            val raw = when {
                n.isPassword -> "[contenu masqué : champ protégé]"
                else -> (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty()
            }
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "View"
            if (raw.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable) {
                sb.append("  ".repeat(depth.coerceAtMost(12)))
                    .append("• ").append(cls)
                if (raw.isNotBlank()) sb.append(" : ").append(raw.replace('\n', ' ').take(180))
                sb.append(" [").append(rect.left).append(',').append(rect.top).append("–")
                    .append(rect.right).append(',').append(rect.bottom).append(']')
                if (n.isClickable) sb.append(" cliquable")
                if (n.isEditable) sb.append(" saisie")
                if (n.isScrollable) sb.append(" défilable")
                if (!n.isEnabled) sb.append(" désactivé")
                if (n.isSelected) sb.append(" sélectionné")
                sb.append('\n')
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        val (sequence, events) = recentUiEvents()
        sb.append("\n\nACTIVITÉ RÉCENTE DU TÉLÉPHONE (événements d'accessibilité, sans contenu privé) :\n")
        sb.append("Séquence : ").append(sequence).append(" · ").append(events.size).append(" événement(s) conservé(s)\n")
        events.takeLast(20).forEach { sb.append("• ").append(it).append('\n') }
        sb.append("NOTE : journal événementiel mis à jour par Android en arrière-plan ; relancer inspect_screen/watch_screen pour lire les derniers changements.\n")
        return sb.toString().trim().take(12_000)
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || sb.length > 5000) return
        val t = if (n.isPassword) "[contenu masqué]" else (n.text?.toString() ?: n.contentDescription?.toString())
        if (!t.isNullOrBlank()) {
            sb.append(t.take(140))
            if (n.isClickable) sb.append("  [cliquable]")
            sb.append('\n')
        }
        for (i in 0 until n.childCount) collect(n.getChild(i), sb)
    }

    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            var cur: AccessibilityNodeInfo? = node
            while (cur != null) {
                if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                cur = cur.parent
            }
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(g, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGesture(g, null, null)
    }


    /** Sélectionne le résultat le plus proche de la requête dans une liste de résultats accessible. */
    fun clickBestMatch(query: String): String {
        val root = rootInActiveWindow ?: return "Écran de résultats inaccessible."
        val terms = query.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct()
        if (terms.isEmpty()) return "Recherche trop courte pour sélectionner un résultat."
        data class Candidate(val node: AccessibilityNodeInfo, val score: Int, val label: String)
        val found = mutableListOf<Candidate>()
        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val label = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty()
            if (label.isNotBlank() && node.className?.toString()?.endsWith("EditText") != true && !node.isEditable) {
                val normalized = label.lowercase(java.util.Locale.ROOT)
                val score = terms.count { normalized.contains(it) }
                if (score > 0) {
                    var clickable: AccessibilityNodeInfo? = node
                    while (clickable != null && !clickable.isClickable) clickable = clickable.parent
                    if (clickable != null) found += Candidate(clickable, score, label)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(root)
        val best = found.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.label.length }) ?: return "Aucun résultat correspondant détecté. Aucun morceau n'a été lancé."
        return if (best.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Résultat sélectionné : " + best.label
            else "Résultat trouvé mais Android a refusé le clic."
    }

    /** Défile vers le bas (le contenu monte) ou vers le haut. */
    fun scroll(down: Boolean): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return if (down) swipe(w / 2, h * 0.72f, w / 2, h * 0.28f) else swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
    }
}
