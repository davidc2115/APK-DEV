package com.jarvis.assistant

import android.app.Activity
import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView

enum class NavDestination { CHAT, GITHUB, SETTINGS }

/**
 * Configure la barre de navigation inférieure (Chat / GitHub / Réglages)
 * incluse via <include layout="@layout/bottom_nav_bar" /> dans chaque écran
 * principal. Gère le style actif/inactif et la transition en fondu entre
 * écrans pour une sensation fluide.
 */
object BottomNav {

    fun setup(activity: Activity, current: NavDestination) {
        val navChat = activity.findViewById<LinearLayout>(R.id.navChat)
        val navGithub = activity.findViewById<LinearLayout>(R.id.navGithub)
        val navSettings = activity.findViewById<LinearLayout>(R.id.navSettings)

        val chatIcon = activity.findViewById<TextView>(R.id.navChatIcon)
        val githubIcon = activity.findViewById<TextView>(R.id.navGithubIcon)
        val settingsIcon = activity.findViewById<TextView>(R.id.navSettingsIcon)
        val chatLabel = activity.findViewById<TextView>(R.id.navChatLabel)
        val githubLabel = activity.findViewById<TextView>(R.id.navGithubLabel)
        val settingsLabel = activity.findViewById<TextView>(R.id.navSettingsLabel)

        applyState(navChat, chatIcon, chatLabel, current == NavDestination.CHAT)
        applyState(navGithub, githubIcon, githubLabel, current == NavDestination.GITHUB)
        applyState(navSettings, settingsIcon, settingsLabel, current == NavDestination.SETTINGS)

        navChat.setOnClickListener {
            if (current != NavDestination.CHAT) navigateTo(activity, MainActivity::class.java)
        }
        navGithub.setOnClickListener {
            if (current != NavDestination.GITHUB) navigateTo(activity, GitHubActivity::class.java)
        }
        navSettings.setOnClickListener {
            if (current != NavDestination.SETTINGS) navigateTo(activity, SettingsActivity::class.java)
        }
    }

    private fun applyState(container: LinearLayout, icon: TextView, label: TextView, active: Boolean) {
        container.setBackgroundResource(if (active) R.drawable.bg_nav_active else android.R.color.transparent)
        icon.alpha = if (active) 1f else 0.5f
        label.setTextColor(
            container.context.getColor(if (active) R.color.cyan_accent else R.color.text_secondary)
        )
    }

    private fun navigateTo(activity: Activity, target: Class<*>) {
        val intent = Intent(activity, target).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        activity.startActivity(intent)
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
