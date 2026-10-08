package dev.okami.luma.data

import dev.okami.luma.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Representación versionada de filtros que se almacena dentro de SQLCipher. */
object FilterCodec {
    /** Serializa únicamente campos permitidos de un criterio validado. */
    fun encode(f: TransactionFilter): String = JSONObject().apply {
        put("v", 1); put("start", f.start?.toString()); put("end", f.end?.toString())
        put("types", JSONArray(f.types.map { it.name })); put("categories", JSONArray(f.categoryIds.toList()))
        put("none", f.uncategorized); put("currencies", JSONArray(f.currencies.toList()))
        put("min", f.minimum); put("max", f.maximum); put("text", f.text)
        put("recurrence", f.recurrence.name); put("description", f.description.name); put("order", f.order.name)
        put("period", f.period?.name)
    }.toString()

    /** Reconstruye criterios con validación de versión y límites; rechaza entradas malformadas. */
    fun decode(value: String): TransactionFilter {
        require(value.length <= 16_384)
        val o = JSONObject(value)
        require(integer(o, "v") == 1L)
        return TransactionFilter(
            start = if (o.has("start")) LocalDate.parse(string(o, "start")) else null,
            end = if (o.has("end")) LocalDate.parse(string(o, "end")) else null,
            types = strings(o.getJSONArray("types")).map { TransactionType.valueOf(it) }.toSet(),
            categoryIds = strings(o.getJSONArray("categories")), uncategorized = boolean(o, "none"),
            currencies = strings(o.getJSONArray("currencies")),
            minimum = if (o.has("min")) integer(o, "min") else null,
            maximum = if (o.has("max")) integer(o, "max") else null,
            text = string(o, "text"), recurrence = RecurrenceFilter.valueOf(string(o, "recurrence")),
            description = DescriptionFilter.valueOf(string(o, "description")), order = TransactionOrder.valueOf(string(o, "order")),
            period = if (o.has("period")) StatisticsPeriod.valueOf(string(o, "period")) else null,
        )
    }

    /** Lee texto JSON original; entradas objeto/clave; rechaza conversiones de números, booleanos y null. */
    private fun string(objectValue: JSONObject, key: String): String = objectValue.get(key).also {
        require(it is String)
    } as String

    /** Lee un booleano JSON original; entradas objeto/clave; rechaza strings como "true" y enteros. */
    private fun boolean(objectValue: JSONObject, key: String): Boolean = objectValue.get(key).also {
        require(it is Boolean)
    } as Boolean

    /** Lee un entero JSON exacto; entradas: objeto y clave; rechaza strings, reales y coerciones con pérdida. */
    private fun integer(objectValue: JSONObject, key: String): Long {
        val value = objectValue.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    /** Convierte una lista acotada de elementos JSON a una selección sin duplicados. */
    private fun strings(array: JSONArray): Set<String> {
        require(array.length() <= 1000)
        return (0 until array.length()).map { index ->
            val value = array.get(index)
            require(value is String && value.length <= 200)
            value
        }.toSet()
    }
}
