package com.jarvis.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.jarvis.ai.ui.navigation.JarvisNavGraph
import com.jarvis.ai.ui.theme.JarvisTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Activité unique : toute la navigation (chat, réglages, historique) se fait
 * en interne via Compose Navigation, comme une vraie app mobile "Jarvis".
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    JarvisNavGraph(navController = navController)
                }
            }
        }
    }
}
