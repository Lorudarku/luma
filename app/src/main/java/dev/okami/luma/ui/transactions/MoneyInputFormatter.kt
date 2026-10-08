package dev.okami.luma.ui.transactions

import dev.okami.luma.domain.model.currencyScale

/**
 * Formatea unidades monetarias mínimas para volver a mostrarlas en un campo editable.
 *
 * Entrada: importe no negativo en unidades mínimas y código ISO de moneda.
 * Salida: texto con separador decimal de formulario (coma).
 * Errores: rechaza importes negativos y escalas de moneda no soportadas.
 * Pasos: resuelve la escala, separa parte mayor y fraccionaria, completa ceros y concatena.
 */
fun formatMinorForInput(amountMinor: Long, currencyCode: String): String {
    // 1. Resolver la escala evita convertir el importe a Float o Double.
    require(amountMinor >= 0) { "Editable amount must not be negative." }
    val fractionDigits = currencyScale(currencyCode)
    var scale = 1L
    repeat(fractionDigits) { scale = Math.multiplyExact(scale, 10L) }
    // 2. Las monedas sin fracción se muestran como un entero.
    if (fractionDigits == 0) return amountMinor.toString()

    // 3. Separar y rellenar la fracción conserva todos los dígitos significativos.
    val major = amountMinor / scale
    val fraction = (amountMinor % scale).toString().padStart(fractionDigits, '0')
    return "$major,$fraction"
}
