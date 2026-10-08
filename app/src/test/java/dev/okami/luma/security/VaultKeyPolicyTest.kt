package dev.okami.luma.security

import org.junit.Assert.*
import org.junit.Test

class VaultKeyPolicyTest {
    /** Entrada: instalación nueva. Salida: permite crear una clave en el Keystore disponible. */
    @Test fun newInstallationCreatesKey() {
        assertTrue(needsNewVaultKey(hasKey = false, hasProtectedData = false))
    }

    /** Entrada: clave existente, hardware o software. Salida: nunca solicita reemplazarla. */
    @Test fun existingKeysArePreservedBeforeAndAfterFirstSave() {
        assertFalse(needsNewVaultKey(hasKey = true, hasProtectedData = false))
        assertFalse(needsNewVaultKey(hasKey = true, hasProtectedData = true))
    }

    /** Entrada: sobre/base sin su clave. Efecto: falla sin autorizar una reinicialización destructiva. */
    @Test fun missingKeyWithProtectedDataIsNotRecreated() {
        assertThrows(IllegalStateException::class.java) {
            needsNewVaultKey(hasKey = false, hasProtectedData = true)
        }
    }
}
