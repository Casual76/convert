package com.p2r3.convert.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import com.p2r3.convert.model.StartDestination

enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Home", Icons.Outlined.Home),
    Convert("convert", "Converti", Icons.Outlined.SwapHoriz),
    Common("common", "Comuni", Icons.Outlined.AutoAwesome),
    History("history", "Cronologia", Icons.Outlined.History),
    Settings("settings", "Impostazioni", Icons.Outlined.Settings);

    companion object {
        fun fromSettings(destination: StartDestination): AppDestination = when (destination) {
            StartDestination.HOME -> Home
            StartDestination.CONVERT -> Convert
            StartDestination.COMMON -> Common
            StartDestination.HISTORY -> History
            StartDestination.SETTINGS -> Settings
        }
    }
}
