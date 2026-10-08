package dev.okami.luma.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.okami.luma.data.RecurringExpenseRepositoryImpl
import dev.okami.luma.data.mapper.toDomain
import dev.okami.luma.domain.model.Money
import dev.okami.luma.domain.model.Transaction
import dev.okami.luma.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TransactionDaoTest {
    private lateinit var database: LumaDatabase
    private lateinit var dao: TransactionDao

    @Before
    /**
     * Crea una base Room en memoria aislada para probar el DAO sin tocar datos de la aplicación.
     *
     * Entradas: metadatos de JUnit gestionados por el runner.
     * Efectos: inicializa [database] y [dao] antes de cada prueba.
     * Pasos: obtiene el contexto instrumentado, construye la base temporal y extrae el DAO.
     */
    fun createDatabase() {
        // 1. La base en memoria evita persistencia entre pruebas y mantiene el test determinista.
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LumaDatabase::class.java,
        ).build()
        dao = database.transactionDao()
    }

    @After
    /**
     * Libera la base temporal después de cada prueba instrumentada.
     *
     * Efectos: cierra conexiones y evita fugas de recursos entre casos.
     */
    fun closeDatabase() {
        database.close()
    }

    @Test
    /**
     * Comprueba inserción, orden temporal, actualización, eliminación y observación del DAO.
     *
     * Efectos: escribe filas únicamente en la base en memoria del test.
     * Pasos: inserta dos fechas, verifica el orden, actualiza una fila y elimina la otra.
     */
    fun insertUpdateDeleteAndObserveInDateOrder() = runBlocking {
        // 1. Preparar filas con distintas fechas y una cantidad en el límite de Long.
        val older = entity(id = "older", amountMinor = Long.MAX_VALUE, occurredAt = 1)
        val newer = entity(id = "newer", amountMinor = 200, occurredAt = 2)

        // 2. Insertar y observar demuestra que Flow emite en el orden SQL solicitado.
        dao.insert(older)
        dao.insert(newer)
        assertEquals(listOf(newer, older), dao.observeAll().first())

        // 3. Actualizar y volver a observar comprueba que todos los campos se reemplazan.
        val updated = newer.copy(amountMinor = 350, description = "Actualizada")
        assertEquals(1, dao.update(updated))
        assertEquals(listOf(updated, older), dao.observeAll().first())

        // 4. Eliminar deja solo la fila actualizada y devuelve su recuento afectado.
        assertEquals(1, dao.deleteById("older"))
        assertEquals(listOf(updated), dao.observeAll().first())
    }

    @Test
    /**
     * Verifica asociación atómica, meses cortos, idempotencia y conservación tras desactivar.
     *
     * Pasos: guarda un gasto histórico, activa hasta marzo, repite, desactiva y reactiva en abril.
     */
    fun monthlyRecurrenceLinksOriginalAndPreservesHistory() = runBlocking {
        val repository = RecurringExpenseRepositoryImpl(
            database,
            database.recurringExpenseDao(),
            dao,
        )
        val original = Transaction(
            id = "historical-expense",
            type = TransactionType.EXPENSE,
            money = Money(2_500, "EUR"),
            occurredAt = Instant.parse("2026-01-31T00:00:00Z"),
            description = "Gasto ficticio",
        )
        dao.insert(original.toEntityForTest())

        // 1. Enero ocupa la fila original; febrero y marzo se generan con sus últimos días válidos.
        repository.setMonthlyRecurrence(
            original,
            enabled = true,
            throughDate = LocalDate.of(2026, 3, 31),
            zoneId = ZoneOffset.UTC,
        )
        val firstPass = dao.observeAll().first()
        assertEquals(3, firstPass.size)
        assertEquals(
            LocalDate.of(2026, 1, 31).toEpochDay(),
            firstPass.single { it.id == original.id }.recurrenceDateEpochDay,
        )

        // 2. Repetir no duplica; desactivar conserva tres filas y reactivar añade solo abril.
        repository.setMonthlyRecurrence(original, true, LocalDate.of(2026, 3, 31), ZoneOffset.UTC)
        assertEquals(3, dao.observeAll().first().size)
        val linkedOriginal = original.copy(
            recurringExpenseId = original.id,
            recurrenceDate = LocalDate.of(2026, 1, 31),
        )
        repository.setMonthlyRecurrence(linkedOriginal, false, LocalDate.of(2026, 3, 31), ZoneOffset.UTC)
        assertEquals(3, dao.observeAll().first().size)
        repository.setMonthlyRecurrence(linkedOriginal, true, LocalDate.of(2026, 4, 30), ZoneOffset.UTC)
        assertEquals(4, dao.observeAll().first().size)
    }

    /** Eliminar una cuota omite su mes; editar el calendario y reabrir generación no duplica el histórico. */
    @Test fun deletedMonthsAndCalendarEditsPreserveHistory() = runBlocking {
        val rules = RecurringExpenseRepositoryImpl(database, database.recurringExpenseDao(), dao)
        val transactions = dev.okami.luma.data.TransactionRepositoryImpl(dao)
        val rule = dev.okami.luma.domain.model.RecurringExpense("rule", Money(1000, "EUR"), 31,
            LocalDate.of(2026, 1, 31), "Ficticio", null, true)
        rules.saveAndGenerate(rule, LocalDate.of(2026, 3, 31), ZoneOffset.UTC)
        val original = dao.observeAll().first()
        val february = original.single { it.recurrenceDateEpochDay == LocalDate.of(2026, 2, 28).toEpochDay() }
        transactions.delete(february.id)
        rules.generateDue(LocalDate.of(2026, 3, 31), ZoneOffset.UTC)
        assertEquals(2, dao.observeAll().first().size)
        rules.saveAndGenerate(rule.copy(dayOfMonth = 15, startDate = LocalDate.of(2026, 1, 15), money = Money(2000, "EUR")),
            LocalDate.of(2026, 4, 30), ZoneOffset.UTC)
        val edited = dao.observeAll().first()
        assertEquals(3, edited.size)
        assertEquals(2, edited.count { it.amountMinor == 1000L })
        assertEquals(2000L, edited.single { it.recurrenceDateEpochDay == LocalDate.of(2026, 4, 15).toEpochDay() }.amountMinor)
    }

    /** Convertir una cuota a ingreso conserva el mes tratado al eliminar sus metadatos recurrentes. */
    @Test fun convertingOccurrenceToIncomeNeverRegeneratesExpense() = runBlocking {
        val rules = RecurringExpenseRepositoryImpl(database, database.recurringExpenseDao(), dao)
        rules.saveAndGenerate(dev.okami.luma.domain.model.RecurringExpense("rule", Money(1000, "EUR"), 5,
            LocalDate.of(2026, 9, 5), null, null, true), LocalDate.of(2026, 9, 5), ZoneOffset.UTC)
        val expense = dao.observeAll().first().single()
        val income = expense.toDomain().copy(type = TransactionType.INCOME,
            recurringExpenseId = null, recurrenceDate = null)
        dev.okami.luma.data.TransactionRepositoryImpl(dao).update(income)
        rules.generateDue(LocalDate.of(2026, 9, 5), ZoneOffset.UTC)
        assertEquals(listOf("INCOME"), dao.observeAll().first().map { it.type })
    }

    /** Un conflicto al generar revierte también la regla y el registro mensual; no queda un alta parcial. */
    @Test fun generationFailureRollsBackRuleAndHandledMonth() = runBlocking {
        val date = LocalDate.of(2026, 9, 5)
        dao.insert(entity("recurring:rule:${date.toEpochDay()}", 1, 1))
        val rules = RecurringExpenseRepositoryImpl(database, database.recurringExpenseDao(), dao)
        var failed = false
        try { rules.saveAndGenerate(dev.okami.luma.domain.model.RecurringExpense("rule", Money(1000, "EUR"), 5,
            date, null, null, true), date, ZoneOffset.UTC) } catch (_: Exception) { failed = true }
        org.junit.Assert.assertTrue(failed)
        org.junit.Assert.assertNull(database.recurringExpenseDao().findById("rule"))
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM recurring_occurrences").use {
            it.moveToFirst(); assertEquals(0L, it.getLong(0))
        }
        assertEquals(1, dao.observeAll().first().size)
    }

    /**
     * Construye una entidad mínima para aislar la prueba de las decisiones de la UI.
     *
     * Entradas: ID, cantidad y fecha de prueba.
     * Salida: entidad de gasto válida para Room.
     */
    private fun entity(id: String, amountMinor: Long, occurredAt: Long) = TransactionEntity(
        id = id,
        type = "EXPENSE",
        amountMinor = amountMinor,
        currencyCode = "EUR",
        occurredAtEpochMillis = occurredAt,
        description = null,
    )

    /** Convierte el modelo ficticio al esquema Room sin depender de mapeadores internos. */
    private fun Transaction.toEntityForTest() = TransactionEntity(
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
}
