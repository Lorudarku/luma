package dev.okami.luma.data.mapper

import dev.okami.luma.data.local.TransactionEntity
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransactionMapperTest {
    @Test
    /**
     * Comprueba que el mapeo de dominio a entidad y de vuelta conserva cada campo.
     *
     * Preparación: construye una transacción ficticia con importe, moneda, fecha y descripción.
     * Verificación: compara el objeto original con el resultado de ambos mapeos.
     */
    fun `round trips every transaction field`() {
        val transaction = Transaction(
            id = "transaction-1",
            type = TransactionType.EXPENSE,
            money = Money(12_050, "EUR"),
            occurredAt = Instant.parse("2026-08-30T10:15:30Z"),
            description = "Compra ficticia",
        )

        assertEquals(transaction, transaction.toEntity().toDomain())
    }

    @Test
    /**
     * Comprueba que un valor almacenado inválido se convierte en un error explícito.
     *
     * Preparación: crea una entidad con un tipo de transacción desconocido.
     * Verificación: espera una excepción de integridad al reconstruir el dominio.
     */
    fun `reports invalid stored values as data integrity error`() {
        val entity = TransactionEntity(
            id = "transaction-1",
            type = "UNKNOWN",
            amountMinor = 10,
            currencyCode = "EUR",
            occurredAtEpochMillis = 0,
            description = null,
        )

        assertThrows(DataIntegrityException::class.java) {
            entity.toDomain()
        }
    }
}
