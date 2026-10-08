package dev.okami.luma.security

enum class RecoveryPromotion { COMPLETE, CLEANUP_PENDING, NOT_ACTIVATED, POINTER_UNAVAILABLE }

/**
 * Publica un slot y separa el commit de la retirada del anterior, sin depender de Android.
 * Entradas: slot nuevo y operaciones de disco/Keystore. Salida: estado reconciliado con el puntero.
 * Un fallo después de publicar conserva ambos slots; solo una activación durable permite retirar el anterior.
 */
fun promoteRecovery(replacement: String, activate: () -> Unit, activeSlot: () -> String,
    retirePrevious: () -> Unit): RecoveryPromotion {
    // 1. No interpretar una excepción como prueba de que el puntero no cambió.
    try { activate() } catch (_: Exception) {
        return when (runCatching(activeSlot).getOrDefault("unavailable")) {
            replacement -> RecoveryPromotion.CLEANUP_PENDING
            "unavailable" -> RecoveryPromotion.POINTER_UNAVAILABLE
            else -> RecoveryPromotion.NOT_ACTIVATED
        }
    }
    // 2. Confirmar la referencia antes de borrar cualquier protección anterior.
    if (runCatching(activeSlot).getOrDefault("unavailable") != replacement) {
        return RecoveryPromotion.POINTER_UNAVAILABLE
    }
    return try { retirePrevious(); RecoveryPromotion.COMPLETE }
    catch (_: Exception) { RecoveryPromotion.CLEANUP_PENDING }
}

/** Devuelve si un staging puede borrarse: entrada slots; salida falsa si es activo o el puntero es incierto. */
fun canDiscardRecovery(staging: String, active: String): Boolean = active != "unavailable" && staging != active
