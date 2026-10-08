package dev.okami.luma.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PrivateCalendarTest {
    @get:Rule val compose = createComposeRule()

    /** Abrir el calendario real no registra fechas ni savers internos de Material en el SavedState del padre. */
    @Test fun financialDateNeverEntersParentSavedState() {
        val registry = SaveableStateRegistry(null) { true }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                MaterialTheme {
                    PastDatePickerField("01/08/2026", LocalDate.of(2026, 9, 5), "Fecha ficticia", null, {})
                }
            }
        }
        compose.onNodeWithContentDescription("Abrir calendario").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(registry.performSave().isEmpty()) }
    }
}
