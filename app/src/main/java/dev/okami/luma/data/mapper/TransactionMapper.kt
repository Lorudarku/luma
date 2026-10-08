package dev.okami.luma.data.mapper

import dev.okami.luma.data.local.TransactionEntity
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate

class DataIntegrityException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * Convierte el modelo de dominio en la forma plana que Room puede persistir.
 *
 * Entrada: una [Transaction] validada.
 * Salida: [TransactionEntity] con enum, dinero y fecha serializados de forma explícita.
 * Efectos: ninguno; no realiza la escritura en la base de datos.
 */
fun Transaction.toEntity(): TransactionEntity = TransactionEntity(
    id = id,
    type = type.name,
    amountMinor = money.amountMinor,
    currencyCode = money.currencyCode,
    occurredAtEpochMillis = occurredAt.toEpochMilli(),
    description = description,
    categoryId = categoryId,
    recurringExpenseId = recurringExpenseId,
    recurrenceDateEpochDay = recurrenceDate?.toEpochDay(),
)

/**
 * Reconstruye el dominio desde Room y transforma corrupción de datos en un error explícito.
 *
 * Entrada: entidad leída de SQLite.
 * Salida: [Transaction] válida.
 * Errores: lanza [DataIntegrityException] si tipo, dinero, fecha o texto violan invariantes.
 * Pasos: deserializa enum y fecha, crea [Money], crea la transacción y encapsula errores de datos.
 */
fun TransactionEntity.toDomain(): Transaction = try {
    // 1. Convertir cada columna mediante los constructores que protegen las invariantes.
    Transaction(
        id = id,
        type = TransactionType.valueOf(type),
        money = Money(amountMinor = amountMinor, currencyCode = currencyCode),
        occurredAt = Instant.ofEpochMilli(occurredAtEpochMillis),
        description = description,
        categoryId = categoryId,
        recurringExpenseId = recurringExpenseId,
        recurrenceDate = recurrenceDateEpochDay?.let(LocalDate::ofEpochDay),
    )
} catch (error: IllegalArgumentException) {
    // 2. Ocultar la excepción concreta evita filtrar detalles de almacenamiento a la UI.
    throw DataIntegrityException("Stored transaction '$id' is invalid.", error)
} catch (error: DateTimeException) {
    // 3. Mantener el identificador en el mensaje permite localizar la fila defectuosa.
    throw DataIntegrityException("Stored transaction '$id' has an invalid date.", error)
}
