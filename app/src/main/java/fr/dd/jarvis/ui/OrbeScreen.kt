package fr.dd.jarvis.ui

// Écran « Orbe Obsidienne » — PHASE 3 (incrément minimal) du projet décrit dans le prompt utilisateur.
// Objectif de cet incrément : la plus petite chose testable possible, conformément à la consigne
// « Ne tente pas de tout développer en une seule opération. Procède par étapes. »
// => Une unique sphère 3D (vrai moteur Filament via SceneView), matière sombre à reflets violets,
//    qui tourne doucement sur elle-même et réagit au doigt (rotation de la caméra, gérée nativement
//    par SceneView). Aucune donnée réelle connectée encore (notes, fichiers, agents) : ça viendra aux
//    phases 4 à 6. Le graphe 2D « Graphe Obsidian » existant (GraphScreen.kt) reste intact et accessible
//    séparément (MODE GRAPHE du prompt).
//
// Note technique : SceneView 2.3.4 (seule version compatible avec compileSdk 35 / AGP 8.7.3 sur ce
// projet — les versions 4.x exigent compileSdk 37) n'expose pas d'API Compose idiomatique dédiée.
// On intègre donc la vue classique `io.github.sceneview.SceneView` via AndroidView, comme recommandé
// par SceneView pour les projets Compose encore sur cette ligne de version.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.LightManager
import dev.romainguy.kotlin.math.Float3
import fr.dd.jarvis.JarvisApp
import io.github.sceneview.SceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.node.LightNode
import io.github.sceneview.node.SphereNode

/**
 * MODE ORBE — vue immersive de la sphère Obsidienne (incrément minimal, phase 3).
 */
@Composable
fun OrbeScreen(app: JarvisApp) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            // Toute la création de la scène 3D est protégée : un appareil sans OpenGL ES 3.0 (ou tout
            // autre souci Filament) doit afficher un message, jamais faire planter l'app entière.
            factory = { ctx ->
                try {
                    creerVueOrbe(ctx)
                } catch (e: Throwable) {
                    android.widget.TextView(ctx).apply {
                        text = "Orbe 3D indisponible sur cet appareil :\n${e.message ?: e.javaClass.simpleName}"
                        setTextColor(android.graphics.Color.LTGRAY)
                        setPadding(32, 32, 32, 32)
                    }
                }
            }
        )

        Column(Modifier.padding(16.dp)) {
            Text(
                "ORBE — incrément phase 3 (sphère seule, pas encore connectée aux données)",
                color = HudDim,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun creerVueOrbe(ctx: android.content.Context): android.view.View {
    val sceneView = SceneView(ctx)
    val materialLoader = MaterialLoader(sceneView.engine, ctx)

    // SceneView crée par défaut un éclairage ambiant (IBL) neutre assez lumineux : combiné à une
    // réflectance élevée, ça « lave » le noir obsidienne en gris clair avec de gros reflets blancs
    // soufflés (c'est ce que l'utilisateur a vu). Sans lumière d'ambiance par défaut, seules nos deux
    // lumières directionnelles (violette + cyan, ci-dessous) éclairent la sphère : elle reste noire
    // dans l'ombre et ne montre que des reflets colorés nets, façon verre volcanique sombre.
    sceneView.indirectLight = null

    // Matériau « obsidienne violette » : noir très sombre, réflectance modérée et rugosité un peu
    // plus élevée qu'avant pour que les reflets des lumières colorées restent des reflets nets,
    // pas des aplats blancs soufflés.
    val materiau = materialLoader.createColorInstance(
        Color(0xFF130018).toArgb(),
        0.35f,  // metallic
        0.35f,  // roughness (était 0.25 : un peu plus mat pour éviter les reflets soufflés)
        0.6f    // reflectance (était 0.9 : trop proche du miroir pur sans contrôle d'IBL)
    )

    val sphere = SphereNode(
        engine = sceneView.engine,
        radius = 0.6f,
        center = Float3(0f, 0f, 0f),
        stacks = 32,
        slices = 32,
        materialInstance = materiau,
        builderApply = {}
    )
    sceneView.addChildNode(sphere)
    sceneView.cameraNode.position = Float3(0f, 0f, 2.2f)

    // Lumière principale violette pour faire ressortir les reflets, plus une lumière
    // d'appoint froide en contre-jour.
    val applyLumierePrincipale: (LightManager.Builder) -> Unit = { builder ->
        builder.color(0.70f, 0.49f, 1.0f)
            .intensity(120_000f)
            .direction(-0.5f, -0.8f, -0.3f)
    }
    val lumierePrincipale = LightNode(
        engine = sceneView.engine,
        type = LightManager.Type.DIRECTIONAL,
        apply = applyLumierePrincipale
    )
    sceneView.addChildNode(lumierePrincipale)

    val applyLumiereAppoint: (LightManager.Builder) -> Unit = { builder ->
        builder.color(0.36f, 0.88f, 1.0f)
            .intensity(35_000f)
            .direction(0.6f, 0.5f, 0.6f)
    }
    val lumiereAppoint = LightNode(
        engine = sceneView.engine,
        type = LightManager.Type.DIRECTIONAL,
        apply = applyLumiereAppoint
    )
    sceneView.addChildNode(lumiereAppoint)

    // Rotation automatique douce sur l'axe Y. La rotation tactile (orbite caméra) est
    // gérée nativement par le gestionnaire de gestes par défaut de SceneView.
    var angleAuto = 0f
    sceneView.onFrame = { _ ->
        angleAuto += 0.15f
        sphere.rotation = Float3(sphere.rotation.x, angleAuto, sphere.rotation.z)
    }

    return sceneView
}
