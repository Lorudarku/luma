package dev.okami.luma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    /**
     * Sustituye el Main dispatcher por el dispatcher controlado del test.
     *
     * Entrada: descripción JUnit del caso que comienza.
     * Efectos: permite ejecutar `viewModelScope` de forma determinista.
     */
    override fun starting(description: Description) {
        // 1. Instala el dispatcher de prueba para que las corrutinas sean deterministas.
        Dispatchers.setMain(dispatcher)
    }

    /**
     * Restaura el Main dispatcher global al terminar el caso.
     *
     * Entrada: descripción JUnit del caso finalizado.
     * Efectos: evita que el dispatcher de prueba contamine otros tests.
     */
    override fun finished(description: Description) {
        // 1. Restaura el dispatcher global para aislar los siguientes casos.
        Dispatchers.resetMain()
    }
}
