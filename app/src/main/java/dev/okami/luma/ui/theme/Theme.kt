package dev.okami.luma.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = GoldPrimaryDark,
    secondary = GoldSecondaryDark,
    tertiary = GoldTertiaryDark,
    error = ExpenseRedDark,
    background = WarmBackgroundDark,
    surface = WarmSurfaceDark,
    surfaceVariant = WarmSurfaceVariantDark,
)

private val LightColorScheme = lightColorScheme(
    primary = GoldPrimaryLight,
    secondary = GoldSecondaryLight,
    tertiary = GoldTertiaryLight,
    error = ExpenseRedLight,
    background = WarmBackgroundLight,
    surface = WarmSurfaceLight,
    surfaceVariant = WarmSurfaceVariantLight,
)

@Composable
/**
 * Aplica el sistema visual dorado estable de Luma en tema claro u oscuro.
 *
 * Entradas: preferencia de tema oscuro y contenido hijo.
 * Salida: contenido envuelto en [MaterialTheme].
 * Pasos: elige la paleta dorada oscura/clara y publica tipografía, colores y contenido.
 */
fun LumaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // 1. Una paleta propia evita que los colores dinámicos sustituyan el dorado por azul.
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    // 2. MaterialTheme centraliza los tokens que consumen todos los composables.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = LumaShapes,
        content = content
    )
}
