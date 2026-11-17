package com.bod3.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bod3.app.ui.screens.ConnectionSettingsScreen
import com.bod3.app.ui.screens.DashboardActions
import com.bod3.app.ui.screens.DashboardScreen
import com.bod3.app.ui.screens.DevicePickerScreen
import com.bod3.app.ui.screens.DtcScreen
import com.bod3.app.ui.screens.LiveDataScreen
import com.bod3.app.ui.screens.ModuleDetailScreen
import com.bod3.app.ui.screens.ModuleListScreen
import com.bod3.app.ui.screens.ProfileEditorScreen
import com.bod3.app.ui.screens.ScanScreen
import com.bod3.app.ui.screens.TerminalScreen

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
            onOpenSettings = viewModel::openSettings,
        )

        Screen.DASHBOARD -> {
            BackHandler(onBack = viewModel::disconnect)
            DashboardScreen(
                state = state,
                log = log,
                actions = DashboardActions(
                    onRefreshVoltage = viewModel::refreshBatteryVoltage,
                    onDetectVehicle = viewModel::detectVehicle,
                    onOpenLiveData = viewModel::openLiveData,
                    onOpenDtcs = viewModel::openDtcs,
                    onOpenScan = viewModel::openScan,
                    onOpenModules = viewModel::openModules,
                    onOpenTerminal = viewModel::openTerminal,
                    onOpenSettings = viewModel::openSettings,
                    onDisconnect = viewModel::disconnect,
                    logText = viewModel::logText,
                    onClearLog = viewModel::clearLog,
                    attemptsText = viewModel::attemptsText,
                    writeReport = viewModel::writeReport,
                ),
            )
        }

        Screen.DTCS -> {
            BackHandler(onBack = viewModel::backToDashboard)
            DtcScreen(state = state, onBack = viewModel::backToDashboard, onRefresh = viewModel::readDtcs)
        }

        Screen.SETTINGS -> {
            BackHandler(onBack = viewModel::closeSettings)
            ConnectionSettingsScreen(
                settings = state.settings,
                connected = state.connection !is ConnectionState.Idle && state.connection !is ConnectionState.Failed,
                onBack = viewModel::closeSettings,
                onSelect = viewModel::selectProfile,
                onEdit = viewModel::editProfile,
                onNew = viewModel::newProfile,
                onDelete = viewModel::deleteProfile,
                onForgetProtocol = viewModel::forgetProtocol,
                onExpertMode = viewModel::setExpertMode,
            )
        }

        Screen.PROFILE_EDIT -> {
            BackHandler(onBack = viewModel::cancelProfileEdit)
            val profile = state.editingProfile
            if (profile == null) {
                LaunchedEffect(Unit) { viewModel.cancelProfileEdit() }
            } else {
                ProfileEditorScreen(profile = profile, onSave = viewModel::saveProfile, onCancel = viewModel::cancelProfileEdit)
            }
        }

        Screen.TERMINAL -> {
            BackHandler(onBack = viewModel::closeTerminal)
            TerminalScreen(
                state = state,
                onBack = viewModel::closeTerminal,
                onSend = viewModel::sendTerminal,
                terminalText = viewModel::terminalText,
                onClear = viewModel::clearTerminal,
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
                onClearLog = viewModel::clearRawLog,
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
                onClearLog = viewModel::clearRawLog,
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
                    onClearLog = viewModel::clearRawLog,
                )
            }
        }
    }
}
