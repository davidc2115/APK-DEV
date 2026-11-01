plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.dd.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.dd.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // Keystore fixe, commité dans le dépôt : permet d'installer les mises à jour par-dessus l'APK précédent.
    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
            pickFirsts += setOf("META-INF/mailcap", "META-INF/mimetypes.default")
        }
    }
}

dependencies {
    // BOM relevé en même temps que Kotlin 2.2.21 (Phase 2 de l'Orbe Obsidienne) : avec le nouveau
    // plugin Compose Compiler (Kotlin 2.x), un runtime Compose trop ancien (2024.06.00) peut planter
    // au tout premier rendu (incompatibilité de version du Composer), avant même le premier écran.
    //
    // enforcedPlatform (et non platform) : la dépendance SceneView (Orbe 3D) réclame en transitif
    // androidx.compose.ui:ui / foundation en version 1.10.0, plus récente que celles de ce BOM, alors
    // que androidx.compose.material3 reste lui sur la version du BOM — un simple platform() aurait
    // laissé Gradle prendre la version la plus haute UNIQUEMENT pour ui/foundation, créant un mélange
    // de versions entre clickable (foundation, nouveau) et le ripple interne de material3 (ancien) :
    // exactement le crash « clickable only supports IndicationNodeFactory » observé au premier
    // lancement. enforcedPlatform force TOUT le module Compose à la même version cohérente du BOM,
    // quoi que SceneView réclame par ailleurs (son intégration Compose n'est de toute façon pas
    // utilisée ici : l'Orbe passe par la vue classique SceneView/AndroidView, pas par Compose).
    val composeBom = enforcedPlatform("androidx.compose:compose-bom:2025.01.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // ADB intégré (Débogage sans fil) : droits « adb » sans installer Shizuku
    implementation("com.github.MuntashirAkon:libadb-android:3.0.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("com.alphacephei:vosk-android:0.3.47")
    implementation("ai.picovoice:porcupine-android:4.0.2")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Contrôle du téléphone (shell via Shizuku)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Mails (IMAP)
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") { exclude(group = "org.bouncycastle") }
    implementation("commons-net:commons-net:3.11.1")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Montage vidéo (assemble plusieurs clips générés par tiktok_video en une seule vidéo)
    // Version 1.4.x : compatible compileSdk 34 (les versions plus récentes de Media3 exigent compileSdk 35/36).
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
}
