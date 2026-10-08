package dev.okami.luma.domain.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurringExpenseTest {
    /**
     * Comprueba que el día 31 se adapta a meses cortos y años bisiestos.
     *
     * Verificación: enero, febrero y abril producen días válidos sin saltarse meses.
     */
    @Test
    fun `clamps monthly occurrences to the last valid day`() {
        val rule = rule(startDate = LocalDate.of(2024, 1, 31), day = 31)

        assertEquals(
            listOf(
                LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 31),
                LocalDate.of(2024, 4, 30),
            ),
            calculateDueOccurrenceDates(rule, LocalDate.of(2024, 4, 30)),
        )
    }

    /**
     * Comprueba que nunca se generan fechas posteriores al límite ni para reglas inactivas.
     *
     * Verificación: el límite intermedio y la desactivación devuelven solo fechas vencidas.
     */
    @Test
    fun `generates only due dates for active rules`() {
        val active = rule(startDate = LocalDate.of(2026, 1, 15), day = 15)

        assertEquals(
            listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 2, 15)),
            calculateDueOccurrenceDates(active, LocalDate.of(2026, 3, 10)),
        )
        assertTrue(
            calculateDueOccurrenceDates(active.copy(active = false), LocalDate.of(2026, 12, 31))
                .isEmpty()
        )
    }

    /**
     * Construye una regla EUR mínima para los escenarios puros de calendario.
     *
     * Entradas: fecha inicial y día mensual.
     * Salida: regla activa sin datos personales.
     */
    private fun rule(startDate: LocalDate, day: Int) = RecurringExpense(
        id = "rule-1",
        money = Money(1_000, "EUR"),
        dayOfMonth = day,
        startDate = startDate,
        description = null,
        categoryId = null,
        active = true,
    )
}
