package fr.dd.jarvis.securite

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.dd.jarvis.JarvisApp
import java.lang.ref.WeakReference

/**
 * Écran « téléphone perdu ou volé », affiché par-dessus l'écran verrouillé : message, numéro à appeler, et champ du code
 * pour arrêter l'alerte (le propriétaire qui retrouve son téléphone). Le bouton Retour ne le ferme pas.
 */
class AlerteVolActivity : ComponentActivity() {
    companion object {
        @Volatile private var courante: WeakReference<Activity>? = null
        fun fermer() { courante?.get()?.let { a -> a.runOnUiThread { a.finish() } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        courante = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val app = application as JarvisApp
        setContent {
            var code by remember { mutableStateOf("") }
            var erreur by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF7A0010)).padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("📵", fontSize = 64.sp)
                Text("TÉLÉPHONE PERDU OU VOLÉ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(app.store.antivolMessage.value.ifBlank { "Ce téléphone a été perdu ou volé." }, color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                app.store.antivolSecours.value.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp)); Text("📞 $it", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text("Sa position est transmise à son propriétaire.", color = Color(0xFFFFC9CF), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(36.dp))
                OutlinedTextField(code, { code = it; erreur = "" }, singleLine = true, label = { Text("Code du propriétaire", color = Color(0xFFFFC9CF)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth())
                if (erreur.isNotBlank()) Text(erreur, color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (AntiVol.verifier(code, app.store.antivolCode.value)) { AntiVol.arreter(app); finish() }
                    else { erreur = "Code incorrect."; code = ""; AntiVol.journal(app, "❌ Mauvais code saisi sur l'écran d'alerte") }
                }) { Text("Arrêter l'alerte") }
            }
        }
    }

    @Deprecated("Retour désactivé pendant l'alerte")
    override fun onBackPressed() { /* l'alerte ne se ferme qu'avec le code */ }

    override fun onDestroy() {
        if (courante?.get() === this) courante = null
        super.onDestroy()
    }
}
