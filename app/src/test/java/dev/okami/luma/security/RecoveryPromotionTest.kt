package dev.okami.luma.security

import org.junit.Assert.*
import org.junit.Test

class RecoveryPromotionTest {
    /** Fallo previo al commit: conserva el activo anterior y no lo retira. */
    @Test fun failureBeforePublishingPreservesOldVault() {
        var retired = false
        val result = promoteRecovery("new", { error("injected") }, { "old" }, { retired = true })
        assertEquals(RecoveryPromotion.NOT_ACTIVATED, result)
        assertFalse(retired)
        assertTrue(canDiscardRecovery("new", "old"))
    }

    /** Fallo de fsync tras publicar: el nuevo slot nunca vuelve a ser staging descartable. */
    @Test fun failureAfterPublishingPreservesBothVaults() {
        var active = "old"
        var retired = false
        val result = promoteRecovery("new", { active = "new"; error("injected fsync") }, { active }, { retired = true })
        assertEquals(RecoveryPromotion.CLEANUP_PENDING, result)
        assertFalse(retired)
        assertFalse(canDiscardRecovery("new", active))
    }

    /** Fallo al retirar Keystore: el commit permanece y cancelar no puede borrar el slot activo. */
    @Test fun retirementFailureDoesNotUndoCommit() {
        var active = "old"
        val result = promoteRecovery("new", { active = "new" }, { active }, { error("injected Keystore") })
        assertEquals(RecoveryPromotion.CLEANUP_PENDING, result)
        assertFalse(canDiscardRecovery("new", active))
    }

    /** Puntero ilegible tras fallo: ambos slots se conservan y el estado falla cerrado. */
    @Test fun unavailablePointerNeverAuthorizesDestruction() {
        val result = promoteRecovery("new", { error("injected") }, { "unavailable" }, { fail("must not retire") })
        assertEquals(RecoveryPromotion.POINTER_UNAVAILABLE, result)
        assertFalse(canDiscardRecovery("new", "unavailable"))
    }

    /** Una publicación durable retira el anterior exactamente una vez y completa la recuperación. */
    @Test fun successfulPromotionRetiresOnlyAfterPublication() {
        var active = "old"
        var retireCount = 0
        val result = promoteRecovery("new", { active = "new" }, { active }, {
            assertEquals("new", active)
            retireCount++
        })
        assertEquals(RecoveryPromotion.COMPLETE, result)
        assertEquals(1, retireCount)
    }
}
