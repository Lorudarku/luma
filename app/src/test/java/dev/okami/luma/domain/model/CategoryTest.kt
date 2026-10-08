package dev.okami.luma.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CategoryTest {
    @Test
    /**
     * Comprueba que la clave de unicidad ignora espacios y diferencias entre mayúsculas.
     */
    fun `normalizes category names for duplicate detection`() {
        assertEquals("alimentación", normalizeCategoryName("  ALIMENTACIÓN "))
    }

    @Test
    /**
     * Comprueba límites de nombre y emoji antes de que los datos alcancen Room.
     */
    fun `rejects invalid category fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            Category("category", " ", "🍎", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Category("category", "Válida", "123456789", false)
        }
    }
}
