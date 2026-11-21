package fr.dd.jarvis.ai

import org.json.JSONObject

/**
 * Texte d'un message d'IA. Sur Android, `optString("content")` d'un contenu JSON `null` renvoie le MOT « null » : il
 * passait tous les contrôles « réponse vide » et s'affichait tel quel (« JARVIS : null »), puis restait dans
 * l'historique où les modèles l'imitaient. Vide si absent, null, ou réduit au mot « null ».
 */
fun JSONObject?.texteIA(cle: String = "content"): String {
    if (this == null || !has(cle) || isNull(cle)) return ""
    val t = optString(cle, "")
    return if (estNul(t)) "" else t
}

/** « null », « undefined », « None » seuls : jamais une vraie réponse. */
fun estNul(t: String?): Boolean = t == null || t.trim().trim('"', '`', '.', ' ').lowercase() in setOf("", "null", "undefined", "none", "nil")
