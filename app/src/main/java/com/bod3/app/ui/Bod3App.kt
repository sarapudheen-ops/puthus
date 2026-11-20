package com.bod3.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bod3.app.ui.screens.ActuatorRunScreen
import com.bod3.app.ui.screens.AppSettingsScreen
import com.bod3.app.ui.screens.DataPacksActions
import com.bod3.app.ui.screens.DataPacksScreen
import com.bod3.app.ui.screens.OnboardingScreen
import com.bod3.app.ui.screens.ActuatorsScreen
import com.bod3.app.ui.screens.CodingEditorScreen
import com.bod3.app.ui.screens.CodingRunScreen
import com.bod3.app.ui.screens.CodingScreen
import com.bod3.app.ui.screens.ConnectionSettingsScreen
import com.bod3.app.ui.screens.CustomPidsScreen
import com.bod3.app.ui.screens.EcuDetailScreen
import com.bod3.app.ui.screens.GarageScreen
import com.bod3.app.ui.screens.ProfileEcusScreen
import com.bod3.app.ui.screens.LiveActions
import com.bod3.app.ui.screens.LiveSelectScreen
import com.bod3.app.ui.screens.PidEditorScreen
import com.bod3.app.ui.screens.CatalogActions
import com.bod3.app.ui.screens.CatalogEditorScreen
import com.bod3.app.ui.screens.CatalogDefinitionsScreen
import com.bod3.app.ui.screens.VehiclesScreen
import com.bod3.app.ui.screens.ToyotaSourceRoute
import com.bod3.app.ui.screens.ToyotaVehiclePacksScreen
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
    val actuatorState by viewModel.actuators.state.collectAsStateWithLifecycle()
    val codingState by viewModel.coding.state.collectAsStateWithLifecycle()
    val appSettings by viewModel.appSettings.collectAsStateWithLifecycle()
    val packsState by viewModel.packs.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(appSettings.keepScreenOn) {
        view.keepScreenOn = appSettings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    if (!appSettings.onboardingDone) {
        OnboardingScreen(onFinish = viewModel::finishOnboarding)
        return
    }

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
            onOpenVehicles = viewModel::openVehicles,
            onOpenGarage = viewModel::openGarage,
            onOpenAppSettings = viewModel::openAppSettings,
            onOpenDataPacks = viewModel::openDataPacks,
            onShowIntro = viewModel::showOnboarding,
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
                    onOpenVehicles = viewModel::openVehicles,
                    onOpenCustomPids = viewModel::openCustomPids,
                    onOpenSettings = viewModel::openSettings,
                    onDisconnect = viewModel::disconnect,
                    logText = viewModel::logText,
                    onClearLog = viewModel::clearLog,
                    attemptsText = viewModel::attemptsText,
                    writeReport = viewModel::writeReport,
                    onOpenGarage = viewModel::openGarage,
                    onOpenActuators = viewModel::openActuators,
                    onOpenCoding = viewModel::openCoding,
                    onOpenDataPacks = viewModel::openDataPacks,
                    onOpenAppSettings = viewModel::openAppSettings,
                ),
            )
        }

        Screen.DTCS -> {
            BackHandler(onBack = viewModel::backToDashboard)
            DtcScreen(
                state = state,
                onBack = viewModel::backToDashboard,
                onRefresh = viewModel::readDtcs,
                onClear = viewModel::clearObdDtcs,
                onDismissClear = viewModel::dismissClearResult,
                onSaveDescription = viewModel::setDtcDescription,
                units = appSettings.units,
            )
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
                catalogTitle = state.catalog.selected?.title,
                catalogConnection = state.catalog.selected?.data?.connection?.values.orEmpty(),
                catalogCoverage = state.catalog.selected?.coverage,
                catalogDownloaded = state.catalog.downloadedSelection,
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
                actions = LiveActions(
                    onBack = viewModel::closeLiveData,
                    onPause = viewModel::pauseLiveData,
                    onResume = viewModel::resumeLiveData,
                    onSelect = viewModel::openLiveSelection,
                    onDisplay = viewModel::setLiveDisplay,
                    onStartRecording = viewModel::startRecording,
                    onStopRecording = { viewModel.stopRecording() },
                    writeCsv = viewModel::writeRecordingCsv,
                ),
                units = appSettings.units,
            )
        }

        Screen.LIVE_SELECT -> {
            BackHandler(onBack = viewModel::closeLiveSelection)
            LiveSelectScreen(
                available = state.liveConfig.available,
                selected = state.live.map { it.def.key },
                onApply = viewModel::setLiveSelection,
                onCancel = viewModel::closeLiveSelection,
            )
        }

        Screen.VEHICLES -> {
            BackHandler(onBack = viewModel::closeVehicles)
            VehiclesScreen(state, CatalogActions(
                onBack = viewModel::closeVehicles, onBrowse = viewModel::browseCatalog, onSelect = viewModel::selectVehicle,
                onFavorite = viewModel::favoriteCatalog, onNew = viewModel::newCatalogNode, onEdit = viewModel::editCatalogNode,
                onDuplicate = viewModel::duplicateCatalogNode, onDelete = viewModel::deleteCatalogNode,
                onData = viewModel::editCatalogData, onPids = viewModel::openCatalogPids,
                onOpenEcu = viewModel::openEcu, onImport = viewModel::openDataPacks, writeExport = viewModel::writeCatalogExport,
                onLinkProfile = viewModel::setProfileVehicle, onDismissMessage = viewModel::dismissCatalogMessage,
                onToyotaPacks = viewModel::openToyotaPacks, onSystems = viewModel::openSelectedSystems,
            ))
        }

        Screen.CATALOG_EDIT -> {
            BackHandler(onBack = viewModel::cancelCatalogEdit)
            val node = state.catalog.editingNode
            if (node == null) LaunchedEffect(Unit) { viewModel.cancelCatalogEdit() }
            else CatalogEditorScreen(node = node, isNew = state.catalog.editingIsNew,
                parentTitle = state.catalog.tree.path(node.parentId).joinToString(" / ") { it.label },
                platforms = state.catalog.pack.platforms, onSave = viewModel::saveCatalogNode, onCancel = viewModel::cancelCatalogEdit)
        }

        Screen.CATALOG_DATA -> {
            BackHandler(onBack = viewModel::cancelCatalogEdit)
            val target = state.catalog.editingData
            if (target == null) LaunchedEffect(Unit) { viewModel.cancelCatalogEdit() }
            else CatalogDefinitionsScreen(target, state.catalog.pack, state.settings.activeProfile,
                onSave = viewModel::saveCatalogData, onCancel = viewModel::cancelCatalogEdit)
        }

        Screen.ECU_DETAIL -> {
            BackHandler(onBack = viewModel::closeEcu)
            val detail = state.ecuDetail
            if (detail == null) {
                LaunchedEffect(Unit) { viewModel.closeEcu() }
            } else {
                EcuDetailScreen(
                    detail = detail,
                    descriptions = state.data.descriptions,
                    connected = state.isConnected,
                    expertModeEnabled = state.settings.expertMode,
                    onRequestSourceSession = viewModel::requestSourceDiagnosticSession,
                    onConfirmSourceSession = viewModel::confirmSourceDiagnosticSession,
                    onDismissSourceSession = viewModel::dismissSourceDiagnosticSession,
                    onBack = viewModel::closeEcu,
                    onRefresh = viewModel::readEcu,
                    onReadRecords = viewModel::readEcuRecords,
                    onClear = viewModel::clearEcuDtcs,
                    onDismissClear = viewModel::dismissClearResult,
                    onSaveDescription = viewModel::setDtcDescription,
                )
            }
        }

        Screen.CUSTOM_PIDS -> {
            BackHandler(onBack = viewModel::closeCustomPids)
            CustomPidsScreen(state = state, onBack = viewModel::closeCustomPids, onEdit = viewModel::editPid)
        }

        Screen.PID_EDIT -> {
            BackHandler(onBack = viewModel::cancelPidEdit)
            val pid = state.data.editingPid
            if (pid == null) {
                LaunchedEffect(Unit) { viewModel.cancelPidEdit() }
            } else {
                val originalId = state.data.editingPidId
                PidEditorScreen(
                    pid = pid,
                    isUserPid = originalId != null && originalId in state.data.userPidIds,
                    isNew = originalId == null,
                    connected = state.isConnected,
                    error = state.data.pidError,
                    testResult = state.data.pidTest,
                    onSave = viewModel::savePid,
                    onTest = viewModel::testPid,
                    onDelete = { originalId?.let(viewModel::deletePid) },
                    onCancel = viewModel::cancelPidEdit,
                    onDuplicate = originalId?.let { id -> { viewModel.duplicatePid(id) } },
                )
            }
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
                onListedChange = viewModel::setScanListed,
                onQuickChange = viewModel::setQuickScan,
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
                onOpenEcus = viewModel::openProfileEcus,
                writeExport = state.savedProfile?.vin?.let { vin -> suspend { viewModel.writeProfileExport(vin) } },
            )
        }

        Screen.GARAGE -> {
            BackHandler(onBack = viewModel::closeGarage)
            GarageScreen(
                state = state,
                onBack = viewModel::closeGarage,
                onOpen = viewModel::openSavedProfile,
                onDelete = viewModel::deleteSavedProfile,
                onImportText = viewModel::importProfile,
                writeExport = { vin, includeLog -> viewModel.writeProfileExport(vin, includeLog) },
                onConfirmOverwrite = viewModel::confirmImportOverwrite,
                onCancelOverwrite = viewModel::cancelImportOverwrite,
                onDismissMessage = viewModel::dismissGarageMessage,
            )
        }

        Screen.ACTUATORS -> {
            BackHandler(onBack = viewModel::closeActuators)
            ActuatorsScreen(
                state = actuatorState,
                expertMode = state.settings.expertMode,
                connected = state.isConnected,
                onBack = viewModel::closeActuators,
                onOpen = viewModel::openActuator,
                onImportText = viewModel::previewRecipeImport,
                writeExport = { viewModel.writeActuatorExport(null) },
                writeAuditLog = viewModel::writeAuditLog,
                onDismissMessage = viewModel.actuators::dismissMessage,
                onOpenSettings = viewModel::openSettings,
            )
        }

        Screen.ACTUATOR_RUN -> {
            BackHandler(onBack = viewModel::closeActuator)
            ActuatorRunScreen(
                state = actuatorState,
                expertMode = state.settings.expertMode,
                connected = state.isConnected,
                onBack = viewModel::closeActuator,
                onCheck = viewModel.actuators::checkPreconditions,
                onStart = viewModel.actuators::requestStart,
                onConfirm = viewModel.actuators::confirmAndStart,
                onCancelConfirm = viewModel.actuators::cancelConfirmation,
                onOverride = viewModel.actuators::setOverrideApplicability,
                onStop = viewModel.actuators::stop,
                onDelete = viewModel.actuators::deleteUserRecipe,
                writeExport = { viewModel.writeActuatorExport(actuatorState.selectedId) },
                onDismissMessage = viewModel.actuators::dismissMessage,
            )
        }

        Screen.CODING -> {
            BackHandler(onBack = viewModel::closeCoding)
            CodingScreen(
                state = codingState,
                expertMode = state.settings.expertMode,
                connected = state.isConnected,
                onBack = viewModel::closeCoding,
                onOpen = viewModel::openCodingRecipe,
                onNewRecipe = { viewModel.openCodingEditor(null) },
                onImportText = viewModel::previewRecipeImport,
                writeExport = { viewModel.writeCodingExport(null) },
                writeAuditLog = viewModel::writeAuditLog,
                onRequestRollback = viewModel.coding::requestRollback,
                onConfirmRollback = viewModel.coding::confirmRollback,
                onCancelRollback = viewModel.coding::cancelRollback,
                onDismissMessage = viewModel.coding::dismissMessage,
                onOpenSettings = viewModel::openSettings,
            )
        }

        Screen.CODING_RUN -> {
            BackHandler(onBack = viewModel::closeCodingRecipe)
            CodingRunScreen(
                state = codingState,
                expertMode = state.settings.expertMode,
                connected = state.isConnected,
                onBack = viewModel::closeCodingRecipe,
                onRead = viewModel.coding::read,
                onOverride = viewModel.coding::setOverrideVersion,
                onValue = viewModel.coding::setNewValue,
                onWrite = viewModel.coding::requestWrite,
                onConfirm = viewModel.coding::confirmWrite,
                onCancelConfirm = viewModel.coding::cancelConfirmation,
                onRollbackLast = viewModel.coding::rollbackLast,
                onRequestRollback = viewModel.coding::requestRollback,
                onConfirmRollback = viewModel.coding::confirmRollback,
                onCancelRollback = viewModel.coding::cancelRollback,
                onEdit = { id -> viewModel.openCodingEditor(id) },
                onDelete = viewModel::deleteCodingRecipe,
                writeExport = { viewModel.writeCodingExport(codingState.selectedId) },
                onDismissMessage = viewModel.coding::dismissMessage,
            )
        }

        Screen.CODING_EDITOR -> {
            BackHandler(onBack = viewModel::closeCodingEditor)
            val editor = codingState.editor
            if (editor == null) {
                LaunchedEffect(Unit) { viewModel.closeCodingEditor() }
            } else {
                CodingEditorScreen(
                    editor = editor,
                    onChange = viewModel.coding::updateEditor,
                    onSave = viewModel::saveCodingEditor,
                    onTemplate = viewModel::codingEditorTemplate,
                    onBack = viewModel::closeCodingEditor,
                )
            }
        }

        Screen.PROFILE_ECUS -> {
            BackHandler(onBack = viewModel::closeProfileEcus)
            ProfileEcusScreen(
                state = state,
                onBack = viewModel::closeProfileEcus,
                onSave = viewModel::saveManualEcu,
                onDelete = viewModel::deleteManualEcu,
                onCopyFromPack = viewModel::copyPackEcusToProfile,
                onSelectVehicle = viewModel::setProfileVehicle,
                onOpenCatalog = viewModel::openCatalogForProfile,
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
                    descriptions = state.data.descriptions,
                    onSaveDescription = viewModel::setDtcDescription,
                    onRename = if (state.savedProfile != null) ({ name: String -> viewModel.renameModule(module.address.requestHeader, name) }) else null,
                )
            }
        }

        Screen.APP_SETTINGS -> {
            BackHandler(onBack = viewModel::closeAppSettings)
            AppSettingsScreen(
                settings = appSettings,
                onBack = viewModel::closeAppSettings,
                onChange = viewModel::updateAppSettings,
                onOpenDataPacks = viewModel::openDataPacks,
                onOpenConnectionSettings = viewModel::openSettings,
                onShowIntro = viewModel::showOnboarding,
            )
        }

        Screen.TOYOTA_SOURCE -> ToyotaSourceRoute(state.toyotaSourceEntry, state.toyotaSourceQuery, viewModel::closeToyotaSource)

        Screen.TOYOTA_PACKS -> {
            BackHandler(onBack = viewModel::closeToyotaPacks)
            val vehiclePacks by viewModel.vehiclePacks.state.collectAsStateWithLifecycle()
            val bringUp by viewModel.bringUp.state.collectAsStateWithLifecycle()
            ToyotaVehiclePacksScreen(vehiclePacks, viewModel.vehiclePacks, state.offline, state.isConnected,
                onBack = viewModel::closeToyotaPacks, onLive = viewModel::openSelectedSystemLive,
                onTests = viewModel::openActuators, onCoding = viewModel::openCoding,
                onDiagnostics = viewModel::openSelectedSystemDiagnostics, bringUp = bringUp, bringUpController = viewModel.bringUp,
                onStartBringUp = viewModel::startBringUp, writeBringUpReport = viewModel::writeBringUpReport,
                writeVerifiedSetups = viewModel::writeVerifiedSetups, onImportSetups = viewModel::openDataPacks)
        }

        Screen.DATA_PACKS -> {
            BackHandler(onBack = viewModel::closeDataPacks)
            DataPacksScreen(
                state = packsState,
                actions = DataPacksActions(
                    onBack = viewModel::closeDataPacks,
                    onImportText = viewModel::previewImport,
                    onConfirmImport = viewModel::confirmImport,
                    onCancelImport = viewModel::cancelImport,
                    onDismissMessage = viewModel::dismissPacksMessage,
                    onToyotaPacks = viewModel::openToyotaPacks,
                    writeData = viewModel::writeDataExport,
                    writeMyActuators = viewModel::writeMyActuatorsExport,
                    writeMyCoding = viewModel::writeMyCodingExport,
                    writeBundled = viewModel::writeBundledExport,
                    writeSample = viewModel::writeSampleExport,
                    writeVerifiedSetups = viewModel::writeVerifiedSetups,
                ),
            )
        }
    }
}
