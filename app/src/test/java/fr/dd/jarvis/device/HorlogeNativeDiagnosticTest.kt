package fr.dd.jarvis.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HorlogeNativeDiagnosticTest {
    @Test fun reconnaitLesPaquetsHorlogeConnusSansAssimilerLesAutresApplications() {
        listOf("com.android.deskclock", "com.miui.clock", "com.xiaomi.clock", "com.google.android.deskclock")
            .forEach { assertTrue("$it doit être reconnu", Horloge.estPaquetHorloge(it)) }
        listOf(null, "", "fr.dd.jarvis", "com.android.settings", "com.google.android.calendar")
            .forEach { assertFalse("$it ne doit pas être reconnu", Horloge.estPaquetHorloge(it)) }
    }
}
