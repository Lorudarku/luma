package dev.okami.luma.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.okami.luma.R
import dev.okami.luma.ui.categories.CategoriesRoute
import dev.okami.luma.ui.categories.CategoriesViewModel
import dev.okami.luma.ui.recurring.RecurringExpensesRoute
import dev.okami.luma.ui.recurring.RecurringExpensesViewModel
import dev.okami.luma.ui.statistics.StatisticsRoute
import dev.okami.luma.ui.statistics.StatisticsViewModel
import dev.okami.luma.ui.transactions.TransactionsRoute
import dev.okami.luma.ui.transactions.TransactionsViewModel

private enum class AppDestination(
    @param:StringRes val labelRes: Int,
    @param:StringRes val contentDescriptionRes: Int,
    @param:DrawableRes val iconRes: Int,
) {
    TRANSACTIONS(R.string.nav_transactions, R.string.nav_transactions_icon, R.drawable.ic_nav_transactions),
    RECURRING(R.string.nav_recurring, R.string.nav_recurring_icon, R.drawable.ic_nav_recurring),
    STATISTICS(R.string.nav_statistics, R.string.nav_statistics_icon, R.drawable.ic_nav_statistics),
    CATEGORIES(R.string.nav_categories, R.string.nav_categories_icon, R.drawable.ic_nav_categories),
}

/**
 * Organiza las cuatro superficies principales sin incorporar un framework de navegación.
 *
 * Entradas: ViewModels retenidos por la Activity.
 * Salida: Scaffold raíz con destino restaurable y barra inferior accesible.
 */
@Composable
fun LumaApp(
    transactionsViewModel: TransactionsViewModel,
    recurringExpensesViewModel: RecurringExpensesViewModel,
    explorer: dev.okami.luma.ui.explorer.ExplorerViewModel,
    categoriesViewModel: CategoriesViewModel,
    securityContent: (@Composable (() -> Unit) -> Unit)? = null,
    showSecurityInitially: Boolean = false,
) {
    var security by androidx.compose.runtime.remember { mutableStateOf(showSecurityInitially) }
    androidx.activity.compose.BackHandler(security) { security = false }
    if (security && securityContent != null) {
        securityContent { security = false }
        return
    }
    var destinationName by rememberSaveable {
        mutableStateOf(AppDestination.TRANSACTIONS.name)
    }
    val destination = AppDestination.valueOf(destinationName)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == destination,
                        onClick = { destinationName = item.name },
                        icon = {
                            Icon(
                                painter = painterResource(item.iconRes),
                                contentDescription = stringResource(item.contentDescriptionRes),
                            )
                        },
                        label = { Text(stringResource(item.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        // Una única rama visible conserva ownership claro y evita navegación en composables hoja.
        val modifier = Modifier.padding(padding)
        Column(modifier.fillMaxSize().statusBarsPadding()) {
            if (securityContent != null) androidx.compose.material3.TextButton(onClick = { security = true }) {
                Text(stringResource(R.string.explore_security))
            }
            if (destination != AppDestination.RECURRING) dev.okami.luma.ui.explorer.FilterBar(explorer)
            when (destination) {
                AppDestination.TRANSACTIONS -> TransactionsRoute(transactionsViewModel, Modifier.weight(1f), explorer)
                AppDestination.RECURRING -> RecurringExpensesRoute(recurringExpensesViewModel, Modifier.weight(1f))
                AppDestination.STATISTICS -> dev.okami.luma.ui.explorer.ExplorerStatistics(explorer,
                    onDrillDown = { destinationName = AppDestination.TRANSACTIONS.name }, Modifier.weight(1f))
                AppDestination.CATEGORIES -> CategoriesRoute(categoriesViewModel, Modifier.weight(1f), explorer, transactionsViewModel)
            }
        }
    }
}
