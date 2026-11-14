package fr.dd.jarvis.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AutorisationsCommandeTest {
    @Test fun memeCommandeMemeEmpreinte() {
        val a = Autorisations.canonique(JSONObject().put("action", "supprimer_appareil").put("filtre", "sans_nom"))
        val b = Autorisations.canonique(JSONObject().put("filtre", "sans_nom ").put("action", "supprimer_appareil"))
        assertEquals(a, b)
        assertEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("freebox", b))
        val c = Autorisations.canonique(JSONObject().put("action", "supprimer_appareil").put("filtre", "hors_ligne"))
        assertNotEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("freebox", c))
        assertNotEquals(Autorisations.empreinte("freebox", a), Autorisations.empreinte("ssh_commande", a))
    }
}
