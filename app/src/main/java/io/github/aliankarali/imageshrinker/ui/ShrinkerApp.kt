package io.github.aliankarali.imageshrinker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

private object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
}

@Composable
fun ShrinkerApp(viewModel: MainViewModel) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(viewModel, onOpenSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(viewModel, onBack = { nav.popBackStack() })
        }
    }
}
