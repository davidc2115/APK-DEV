plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jarvis.ai"
        minSdk = 26 // Wake word / STT locaux + APIs modernes ; 26 = bonne couverture d'appareils réels
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-scaffold"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Requis pour @HiltWorker / @AssistedInject dans ObsidianSyncWorker (CoroutineWorker) :
    // sans ce compilateur androidx.hilt dédié, KSP ne peut pas générer les classes Hilt_*
    // pour les Workers et échoue avec "error.NonExistentClass".
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.documentfile) // DocumentFile (SAF) pour ObsidianVaultManager

    // TODO Phase 2+ : whisper.cpp (JNI/.aar maison), llama.cpp/MLC (module natif), openWakeWord (tflite runtime),
    // Apache POI / PdfBox-Android (documents), JGit (module codage).
    // Non ajoutés au scaffold pour garder un premier build léger et rapide à synchroniser.
}
