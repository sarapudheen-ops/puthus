package com.bod3.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bod3.app.ui.screens.DashboardScreen
import com.bod3.app.ui.screens.DevicePickerScreen
import com.bod3.app.ui.screens.LiveDataScreen
import com.bod3.app.ui.screens.ModuleDetailScreen
import com.bod3.app.ui.screens.ModuleListScreen
import com.bod3.app.ui.screens.ScanScreen

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
                onOpenScan = viewModel::openScan,
                onOpenModules = viewModel::openModules,
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

        Screen.SCAN -> {
            // Leaving the screen does not cancel a running scan (only the Cancel button does); the
            // dashboard's "Scan running…" button comes back here.
            BackHandler(onBack = viewModel::backToDashboard)
            ScanScreen(
                state = state,
                onBack = viewModel::backToDashboard,
                onStart = viewModel::startScan,
                onCancel = viewModel::cancelScan,
                onOtherBusChange = viewModel::setScanOtherBus,
                onOpenModules = viewModel::openModules,
                rawLogText = { viewModel.rawLogText() },
            )
        }

        Screen.MODULES -> {
            BackHandler(onBack = viewModel::backToDashboard)
            ModuleListScreen(
                state = state,
                onBack = viewModel::backToDashboard,
                onOpenModule = viewModel::openModule,
                onRescan = viewModel::openScan,
                rawLogText = { viewModel.rawLogText() },
            )
        }

        Screen.MODULE_DETAIL -> {
            BackHandler(onBack = viewModel::closeModuleDetail)
            val module = state.selectedModule
            if (module == null) {
                LaunchedEffect(Unit) { viewModel.closeModuleDetail() }
            } else {
                ModuleDetailScreen(
                    module = module,
                    rawLog = state.rawLog,
                    onBack = viewModel::closeModuleDetail,
                    moduleLogText = { viewModel.rawLogText(module) },
                    fullLogText = { viewModel.rawLogText() },
                )
            }
        }
    }
}
