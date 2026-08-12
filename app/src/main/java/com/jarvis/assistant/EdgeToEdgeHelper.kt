package com.jarvis.assistant

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Depuis Android 15 (targetSdk 35), le mode "bord à bord" est imposé par
 * défaut : sans gestion explicite, le contenu passe SOUS la barre de statut
 * en haut et sous la barre/geste de navigation du téléphone en bas.
 *
 * Ces fonctions ajoutent le padding nécessaire aux bonnes vues pour que rien
 * ne soit caché, sans pour autant réserver cet espace inutilement sur les
 * anciens Android.
 */
object EdgeToEdgeHelper {

    /** Ajoute le padding de la barre de statut en haut de la vue donnée (ex: l'en-tête). */
    fun applyTopInset(view: View) {
        val initialPaddingTop = view.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, initialPaddingTop + systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    /** Ajoute le padding de la barre/geste de navigation en bas de la vue donnée (ex: la barre de nav du bas). */
    fun applyBottomInset(view: View) {
        val initialPaddingBottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialPaddingBottom + systemBars.bottom)
            insets
        }
    }
}
