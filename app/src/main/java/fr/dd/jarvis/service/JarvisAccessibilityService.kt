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
        @Volatile
        var instance: JarvisAccessibilityService? = null
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun global(action: Int): Boolean = performGlobalAction(action)

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collect(root, sb)
        return sb.toString().trim().take(5000)
    }

    private fun collect(n: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (n == null || sb.length > 5000) return
        val t = n.text?.toString() ?: n.contentDescription?.toString()
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

    /** Défile vers le bas (le contenu monte) ou vers le haut. */
    fun scroll(down: Boolean): Boolean {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        return if (down) swipe(w / 2, h * 0.72f, w / 2, h * 0.28f) else swipe(w / 2, h * 0.28f, w / 2, h * 0.72f)
    }
}
