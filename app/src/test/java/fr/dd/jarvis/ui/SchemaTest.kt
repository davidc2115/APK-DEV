package fr.dd.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaTest {
    @Test fun milieux() {
        assertEquals(2, SchemaDessinLogique.milieu(1, 3)); assertEquals(5, SchemaDessinLogique.milieu(1, 9))
        assertEquals(5, SchemaDessinLogique.milieu(2, 8)); assertEquals(null, SchemaDessinLogique.milieu(1, 6))
        assertEquals(null, SchemaDessinLogique.milieu(1, 2))
        assertEquals(listOf(1, 2, 3, 6, 5, 4), SchemaDessinLogique.completer(listOf(1, 3, 6, 4)))
        assertEquals(listOf(5, 1, 2, 3), SchemaDessinLogique.completer(listOf(5, 1, 3, 2)))
    }
}
