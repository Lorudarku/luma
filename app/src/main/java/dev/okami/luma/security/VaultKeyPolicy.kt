package dev.okami.luma.security

/**
 * Decide si hace falta crear una KEK sin exigir que esté respaldada por hardware.
 * Entradas: existencia de clave y de datos ya protegidos (sobre o base cifrada).
 * Salida: true solo en una instalación nueva; una clave existente nunca se reemplaza.
 * Efectos: rechaza la pérdida de una clave para impedir reinicializar datos irrecuperables.
 */
internal fun needsNewVaultKey(hasKey: Boolean, hasProtectedData: Boolean): Boolean {
    // 1. Conservar tanto claves de hardware como las del Keystore por software.
    if (hasKey) return false
    // 2. La compatibilidad no autoriza sustituir claves perdidas de una base existente.
    check(!hasProtectedData) { "Existing vault key unavailable" }
    return true
}
