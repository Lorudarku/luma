package dev.okami.luma.domain.validation

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionDateCodecTest {
    /**
     * Comprueba ida y vuelta exacta del formato visible español.
     *
     * Verificación: la fecha conserva día, mes y año.
     */
    @Test
    fun `formats and parses dd MM uuuu strictly`() {
        val date = LocalDate.of(2026, 8, 30)

        assertEquals("30/08/2026", TransactionDateCodec.format(date))
        assertEquals(date, TransactionDateCodec.parse("30/08/2026"))
    }

    /**
     * Comprueba que formatos ambiguos y días inexistentes se rechazan.
     *
     * Verificación: ISO, mes 13 y febrero inválido devuelven null.
     */
    @Test
    fun `rejects ambiguous and impossible dates`() {
        assertNull(TransactionDateCodec.parse("2026-08-30"))
        assertNull(TransactionDateCodec.parse("30/13/2026"))
        assertNull(TransactionDateCodec.parse("30/02/2026"))
    }
}
