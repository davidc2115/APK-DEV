package com.jarvis.ai

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Point d'entrée Hilt. Toute la DI de l'app (réseau, base de données, réglages chiffrés,
 * fournisseurs IA) est déclarée dans le package `di/`.
 */
@HiltAndroidApp
class JarvisApplication : Application()
