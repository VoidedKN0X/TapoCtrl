package com.vknxworks.tapoctrl.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.vknxworks.tapoctrl.ui.devices.AccountScreen
import com.vknxworks.tapoctrl.ui.devices.AddDeviceScreen
import com.vknxworks.tapoctrl.ui.devices.DeviceInfoScreen
import com.vknxworks.tapoctrl.ui.devices.DeviceScreen
import com.vknxworks.tapoctrl.ui.devices.EnergyScreen
import com.vknxworks.tapoctrl.ui.devices.FirmwareScreen
import com.vknxworks.tapoctrl.ui.devices.HomeScreen
import com.vknxworks.tapoctrl.ui.settings.SettingsScreen
import com.vknxworks.tapoctrl.ui.settings.YouScreen

/** No tabs: the device list is home; everything else opens on top of it. */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val navigator = remember(navController) { Navigator(navController) }
    CompositionLocalProvider(LocalNavigator provides navigator) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NavHost(navController, startDestination = HomeRoute) {
                composable<HomeRoute> { HomeScreen() }
                composable<AddRoute> { AddDeviceScreen() }
                composable<AccountRoute> { AccountScreen() }
                composable<DeviceRoute> { DeviceScreen(it.toRoute<DeviceRoute>().id) }
                composable<EnergyRoute> { EnergyScreen(it.toRoute<EnergyRoute>().id) }
                composable<DeviceInfoRoute> { DeviceInfoScreen(it.toRoute<DeviceInfoRoute>().id) }
                composable<FirmwareRoute> { FirmwareScreen(it.toRoute<FirmwareRoute>().id) }
                composable<YouRoute> { YouScreen() }
                composable<SettingsRoute> { SettingsScreen() }
            }
        }
    }
}
