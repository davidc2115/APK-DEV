package com.jarvis.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.jarvis.ai.ui.chat.ChatScreen
import com.jarvis.ai.ui.settings.SettingsScreen

sealed class JarvisDestination(val route: String) {
    data object Chat : JarvisDestination("chat")
    data object Settings : JarvisDestination("settings")
}

@Composable
fun JarvisNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = JarvisDestination.Chat.route) {
        composable(JarvisDestination.Chat.route) {
            ChatScreen(onOpenSettings = { navController.navigate(JarvisDestination.Settings.route) })
        }
        composable(JarvisDestination.Settings.route) {
            SettingsScreen()
        }
    }
}
