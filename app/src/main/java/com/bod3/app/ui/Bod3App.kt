package com.bod3.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bod3.app.ui.screens.DashboardScreen
import com.bod3.app.ui.screens.DevicePickerScreen
import com.bod3.app.ui.screens.LiveDataScreen

@Composable
fun Bod3App(viewModel: ObdViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val log by viewModel.log.collectAsStateWithLifecycle()

    // Permission or Bluetooth state may change while we are in the background (system settings).
    LifecycleResumeEffect(Unit) {
        viewModel.refreshBluetooth()
        onPauseOrDispose { }
    }

    when (state.screen) {
        Screen.DEVICES -> DevicePickerScreen(
            state = state,
            onPermissionResult = viewModel::onPermissionResult,
            onRefresh = viewModel::refreshBluetooth,
            onConnect = viewModel::connect,
            onDismissError = viewModel::dismissError,
        )

        Screen.DASHBOARD -> {
            BackHandler(onBack = viewModel::disconnect)
            DashboardScreen(
                state = state,
                log = log,
                onRefreshVoltage = viewModel::refreshBatteryVoltage,
                onDetectVehicle = viewModel::detectVehicle,
                onOpenLiveData = viewModel::openLiveData,
                onDisconnect = viewModel::disconnect,
            )
        }

        Screen.LIVE -> {
            BackHandler(onBack = viewModel::closeLiveData)
            LiveDataScreen(
                state = state,
                onBack = viewModel::closeLiveData,
                onPause = viewModel::pauseLiveData,
                onResume = viewModel::resumeLiveData,
            )
        }
    }
}
