package fr.dd.jarvis.images

import org.json.JSONObject

/**
 * Préparation d'une demande d'image : style et format repérés dans la demande, choix automatique du modèle
 * Pollinations, enrichissement local (sans IA) et lecture de la réponse d'une IA de réécriture.
 */
object PromptImage {

    enum class Style(val libelle: String) {
        PHOTO("photo réaliste"), ANIME("anime / manga"), ART("illustration / peinture"), RENDU3D("rendu 3D"),
        TEXTE("texte dans l'image"), AUTO("général")
    }

    enum class Format(val largeur: Int, val hauteur: Int, val libelle: String) {
        CARRE(1024, 1024, "carré"), PORTRAIT(832, 1216, "portrait"), PAYSAGE(1216, 832, "paysage")
    }

    data class Prompt(val positif: String, val negatif: String, val style: Style, val format: Format?, val source: String)

    sealed class Reponse {
        data class Reecrit(val prompt: Prompt) : Reponse()
        data class Refuse(val raison: String) : Reponse()
        object Illisible : Reponse()
    }

    data class Choix(val modele: String, val raison: String)

    private fun n(t: String) = GardeImages.norm(t)
    private fun a(n: String, vararg mots: String) = mots.any { Regex("(^|[^a-z0-9])" + Regex.escape(it) + "([^a-z0-9]|$)").containsMatchIn(n) }

    fun style(texte: String, explicite: Boolean = false): Style {
        val t = n(texte)
        return when {
            !explicite && a(t, "affiche", "logo", "poster", "flyer", "typographie", "texte", "ecrit", "ecrite", "slogan", "titre",
                "banniere", "carte de visite", "avec le mot", "qui dit", "lettrage", "lettering", "typography", "text") -> Style.TEXTE
            a(t, "anime", "manga", "hentai", "ecchi", "cartoon", "dessin anime", "style japonais", "waifu", "chibi", "cel shading",
                "bande dessinee", "bd", "comics", "comic", "ghibli") -> Style.ANIME
            a(t, "3d", "rendu 3d", "blender", "octane", "unreal", "pixar", "figurine", "sculpture", "cgi") -> Style.RENDU3D
            a(t, "photo", "photos", "photographie", "photographique", "dslr", "reflex", "selfie", "35mm", "50mm", "85mm", "raw photo",
                "photoshoot", "shooting", "photorealiste", "photorealistic", "photoreal") -> Style.PHOTO
            a(t, "peinture", "huile", "aquarelle", "illustration", "dessin", "croquis", "esquisse", "tableau", "pastel", "fusain",
                "gravure", "renaissance", "art nouveau", "fantasy", "concept art", "digital art", "painting", "watercolor", "artistique") -> Style.ART
            a(t, "realiste", "realisme", "hyperrealiste", "hyper realiste", "realistic", "hyperrealistic", "cinematique", "cinematic") -> Style.PHOTO
            else -> Style.AUTO
        }
    }

    fun format(texte: String): Format? {
        val t = n(texte)
        return when {
            a(t, "carre", "carree", "square", "1:1", "avatar", "photo de profil", "pp") -> Format.CARRE
            a(t, "vertical", "verticale", "fond d ecran telephone", "fond d ecran de telephone", "story", "stories", "9:16", "2:3",
                "3:4", "en pied", "plein pied", "full body", "portrait") -> Format.PORTRAIT
            a(t, "horizontal", "horizontale", "panorama", "panoramique", "banniere", "16:9", "3:2", "4:3", "fond d ecran pc",
                "fond d ecran ordinateur", "wallpaper", "cinemascope", "paysage", "landscape", "widescreen") -> Format.PAYSAGE
            else -> null
        }
    }

    fun rapide(texte: String): Boolean = a(n(texte), "rapide", "rapidement", "brouillon", "vite", "fast", "quick", "draft", "test rapide")

    /** Modèles dont le fournisseur filtre la nudité : jamais choisis automatiquement pour une demande adulte. */
    fun modere(modele: String): Boolean = modele.lowercase().let { m ->
        listOf("gpt", "nanobanana", "seedream", "grok", "kontext", "imagen", "dall", "veo", "seedance").any { m.startsWith(it) }
    }

    fun candidats(style: Style, explicite: Boolean, rapide: Boolean): List<String> = when {
        rapide -> listOf("klein", "turbo", "flux", "zimage")
        explicite -> when (style) {
            Style.PHOTO, Style.AUTO -> listOf("zimage", "flux", "klein", "turbo")
            else -> listOf("flux", "zimage", "klein", "turbo")
        }
        else -> when (style) {
            Style.TEXTE -> listOf("qwen-image", "gptimage", "flux")
            Style.PHOTO -> listOf("zimage", "flux-2-dev", "flux")
            Style.ART -> listOf("flux", "qwen-image", "zimage")
            else -> listOf("flux", "zimage")
        }
    }

    /**
     * Modèle choisi d'après la demande. [disponibles] : modèles gratuits annoncés par Pollinations (vide = liste
     * inconnue, on garde la préférence). Une demande adulte n'obtient jamais un modèle d'un fournisseur qui la filtre.
     */
    fun choisirModele(style: Style, explicite: Boolean, rapide: Boolean, disponibles: List<String>): Choix {
        val pref = candidats(style, explicite, rapide)
        val dispo = disponibles.map { it.lowercase() }
        val m = if (dispo.isEmpty()) pref.first() else pref.firstOrNull { it in dispo }
            ?: dispo.firstOrNull { !explicite || !modere(it) } ?: pref.first()
        val pourquoi = buildList {
            if (rapide) add("rapide")
            add(style.libelle)
            if (explicite) add("contenu adulte → modèle non filtré")
        }.joinToString(", ")
        return Choix(m, pourquoi)
    }

    const val NEGATIF_BASE = "blurry, low quality, worst quality, deformed, bad anatomy, bad hands, extra fingers, extra limbs, " +
        "missing fingers, disfigured, mutated, watermark, signature, jpeg artifacts"
    const val NEGATIF_ADULTE = "child, children, kid, teen, teenager, underage, minor, young-looking, childlike, loli, " +
        "petite child body, school uniform"

    private fun qualite(style: Style): String = when (style) {
        Style.PHOTO -> "photorealistic, professional photography, 85mm lens, natural skin texture, soft cinematic lighting, sharp focus, high detail"
        Style.ANIME -> "anime style, clean lineart, vibrant colors, detailed shading, high quality illustration"
        Style.ART -> "digital painting, rich colors, detailed brushwork, artistic lighting, masterpiece"
        Style.RENDU3D -> "3d render, octane render, global illumination, detailed textures, studio lighting"
        Style.TEXTE -> "clean graphic design, legible typography, balanced composition, high resolution"
        Style.AUTO -> "highly detailed, cinematic lighting, sharp focus, high quality"
    }

    /** Enrichissement sans IA : la demande telle quelle + mots de qualité du style + négatif adapté. */
    fun enrichirLocal(demande: String, explicite: Boolean): Prompt {
        val st = style(demande, explicite)
        val base = demande.replace(Regex("\\s+"), " ").trim().trimEnd('.', ',')
        return Prompt("$base, ${qualite(st)}", NEGATIF_BASE, st, format(demande), "locale")
    }

    /** Ajouts obligatoires, quelle que soit la réécriture : marqueurs « adulte » et négatif anti-mineurs. */
    fun finaliser(p: Prompt, explicite: Boolean): Prompt {
        var pos = p.positif.replace(Regex("\\s+"), " ").trim()
        var neg = p.negatif.replace(Regex("\\s+"), " ").trim().ifBlank { NEGATIF_BASE }
        if (explicite) {
            if (!Regex("\\b(adult|adults|mature)\\b", RegexOption.IGNORE_CASE).containsMatchIn(pos)) pos = "mature adults, $pos"
            if (!neg.contains("underage")) neg = "$neg, $NEGATIF_ADULTE"
        } else if (!neg.contains("nsfw")) neg = "$neg, nsfw, nudity"
        if (p.style != Style.TEXTE && !Regex("\\btext\\b").containsMatchIn(neg)) neg = "$neg, text"
        return p.copy(positif = pos.take(1400), negatif = neg.take(600))
    }

    fun systeme(explicite: Boolean): String = buildString {
        append("Tu es un expert en prompts pour modèles de diffusion d'images (Flux, Z-Image, SDXL). ")
        append("Transforme la demande de l'utilisateur en UN prompt en anglais, précis et visuel : sujet, apparence, tenue, pose, ")
        append("décor, éclairage, cadrage, objectif ou style, ambiance, qualité. Reste fidèle à l'intention de l'utilisateur, ")
        append("n'ajoute rien de hors sujet, pas de phrase d'explication, pas de nom d'artiste vivant.\n")
        if (explicite) {
            append("Contenu pour adultes demandé par l'utilisateur (majeur). Règles absolues : tous les personnages sont des adultes ")
            append("fictifs de 25 ans ou plus, d'apparence clairement adulte ; jamais de mineur ni d'apparence juvénile ; jamais de ")
            append("personne réelle ou identifiable (remplace tout nom par un personnage fictif sans nom) ; uniquement des scènes ")
            append("entre adultes consentants ; pas d'animaux. Si la demande enfreint ces règles, réponds exactement {\"refus\":\"raison\"}.\n")
        }
        append("Réponds UNIQUEMENT avec ce JSON : {\"prompt\":\"...\",\"negatif\":\"éléments à éviter, en anglais\",")
        append("\"style\":\"photo|anime|art|3d|texte|auto\",\"format\":\"portrait|paysage|carre|auto\"}")
    }

    private val REFUS = Regex(
        "(i can ?t|i cannot|i m sorry|i am sorry|sorry,? but|i won ?t|i m not able|i am not able|i am unable|i m unable|" +
            "as an ai|cannot help|can ?t help|cannot assist|can ?t assist|cannot create|can ?t create|cannot generate|" +
            "je ne peux pas|je suis desole|desole,? mais|en tant qu ia|je ne suis pas en mesure|je refuse|not appropriate|" +
            "against (my|the) (policy|policies|guidelines)|content policy)"
    )

    fun estRefus(texte: String): Boolean = REFUS.containsMatchIn(n(texte).replace(Regex("[^a-z0-9 ,]"), " ").replace(Regex("\\s+"), " "))

    private fun styleDe(v: String): Style? = when (n(v)) {
        "photo" -> Style.PHOTO; "anime" -> Style.ANIME; "art" -> Style.ART; "3d" -> Style.RENDU3D; "texte", "text" -> Style.TEXTE
        "auto" -> Style.AUTO; else -> null
    }

    private fun formatDe(v: String): Format? = when (n(v)) {
        "portrait" -> Format.PORTRAIT; "paysage", "landscape" -> Format.PAYSAGE; "carre", "square" -> Format.CARRE; else -> null
    }

    /** Lit la réponse de l'IA de réécriture (JSON attendu, texte brut accepté) ; refus et réponses vides détectés. */
    fun lireReponse(brut: String, demande: String, explicite: Boolean, source: String): Reponse {
        val t = brut.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (t.isBlank()) return Reponse.Illisible
        val debut = t.indexOf('{'); val fin = t.lastIndexOf('}')
        if (debut >= 0 && fin > debut) {
            val j = runCatching { JSONObject(t.substring(debut, fin + 1)) }.getOrNull()
            if (j != null) {
                val refus = j.optString("refus", "").trim()
                if (refus.isNotEmpty()) return Reponse.Refuse(refus)
                val p = j.optString("prompt", "").trim()
                if (p.length < 8 || estRefus(p)) return if (estRefus(p)) Reponse.Refuse(p.take(160)) else Reponse.Illisible
                val st = styleDe(j.optString("style", "")).takeIf { it != null && it != Style.AUTO } ?: style(demande, explicite)
                return Reponse.Reecrit(Prompt(p, j.optString("negatif", j.optString("negative", "")).trim(), st,
                    formatDe(j.optString("format", "")) ?: format(demande), source))
            }
        }
        if (estRefus(t)) return Reponse.Refuse(t.take(160))
        if (t.length < 8) return Reponse.Illisible
        return Reponse.Reecrit(Prompt(t.lines().joinToString(" ").take(1400), "", style(demande, explicite), format(demande), source))
    }
}
