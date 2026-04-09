package com.p2r3.convert.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.p2r3.convert.model.ConversionStatus
import com.p2r3.convert.ui.theme.ConvertTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertRoot(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val keepScreenOn = uiState.settings.keepScreenOn && uiState.history.any {
        it.status == ConversionStatus.QUEUED || it.status == ConversionStatus.RUNNING
    }

    LaunchedEffect(uiState.session.noticeMessage) {
        val message = uiState.session.noticeMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.dismissNotice()
    }

    DisposableEffect(activity, keepScreenOn) {
        if (keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (!keepScreenOn) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    ConvertTheme(settings = uiState.settings) {
        key(uiState.settings.startDestination) {
            val navController = rememberNavController()
            val startDestination = AppDestination.fromSettings(uiState.settings.startDestination)
            val reduceMotion = uiState.settings.reduceMotion
            val destinations = listOf(
                AppDestination.Home,
                AppDestination.Convert,
                AppDestination.Common,
                AppDestination.History,
                AppDestination.Settings
            )
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = navBackStackEntry?.destination?.route ?: startDestination.route
            val currentDestination = destinations.firstOrNull { it.route == currentRoute } ?: startDestination

            LaunchedEffect(uiState.pendingNavigationTarget) {
                val target = uiState.pendingNavigationTarget ?: return@LaunchedEffect
                if (navController.currentDestination?.route != target.route) {
                    navController.navigate(target.route) {
                        launchSingleTop = true
                        restoreState = true
                    }
                }
                viewModel.consumeNavigationRequest()
            }

            NavigationSuiteScaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.surface,
                navigationSuiteItems = {
                    destinations.forEach { destination ->
                        item(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label
                                )
                            },
                            label = { Text(destination.label) }
                        )
                    }
                }
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(title = { Text(currentDestination.title) })
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = startDestination.route,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        enterTransition = {
                            fadeIn(animationSpec = tween(if (reduceMotion) 90 else 160))
                        },
                        exitTransition = {
                            fadeOut(animationSpec = tween(if (reduceMotion) 70 else 120))
                        },
                        popEnterTransition = {
                            fadeIn(animationSpec = tween(if (reduceMotion) 90 else 160))
                        },
                        popExitTransition = {
                            fadeOut(animationSpec = tween(if (reduceMotion) 70 else 120))
                        }
                    ) {
                        composable(AppDestination.Home.route) {
                            HomeScreen(
                                uiState = uiState,
                                reduceMotion = reduceMotion,
                                onOpenConvert = { navController.navigate(AppDestination.Convert.route) },
                                onPresetClick = {
                                    viewModel.applyPreset(it)
                                    navController.navigate(AppDestination.Convert.route)
                                }
                            )
                        }
                        composable(AppDestination.Convert.route) {
                            ConvertScreen(
                                uiState = uiState,
                                reduceMotion = reduceMotion,
                                onPickFiles = viewModel::onFilesPicked,
                                onSelectSource = viewModel::selectSource,
                                onSelectTarget = viewModel::selectTarget,
                                onStartConversion = viewModel::startConversion,
                                onClearSelection = viewModel::clearSelection
                            )
                        }
                        composable(AppDestination.Common.route) {
                            CommonScreen(
                                presets = uiState.presets,
                                reduceMotion = reduceMotion,
                                onPresetClick = {
                                    viewModel.applyPreset(it)
                                    navController.navigate(AppDestination.Convert.route)
                                }
                            )
                        }
                        composable(AppDestination.History.route) {
                            HistoryScreen(
                                entries = uiState.history,
                                reduceMotion = reduceMotion,
                                onRerun = viewModel::rerunHistoryEntry
                            )
                        }
                        composable(AppDestination.Settings.route) {
                            SettingsScreen(
                                settings = uiState.settings,
                                engineBody = uiState.engineBody,
                                engineDiagnostics = uiState.engineDiagnostics,
                                onThemeMode = viewModel::setThemeMode,
                                onDynamicColor = viewModel::setDynamicColorEnabled,
                                onStartDestination = viewModel::setStartDestination,
                                onAutoPreview = viewModel::setAutoPreview,
                                onPreviewLimit = viewModel::setPreviewLimit,
                                onOutputNamingPolicy = viewModel::setOutputNamingPolicy,
                                onAutoOpenResult = viewModel::setAutoOpenResult,
                                onKeepHistory = viewModel::setKeepHistory,
                                onKeepScreenOn = viewModel::setKeepScreenOn,
                                onPerformancePreset = viewModel::setPerformancePreset,
                                onMaxParallelJobs = viewModel::setMaxParallelJobs,
                                onBatteryFriendlyMode = viewModel::setBatteryFriendlyMode,
                                onConfirmBeforeBatch = viewModel::setConfirmBeforeBatch,
                                onReduceMotion = viewModel::setReduceMotion,
                                onHaptics = viewModel::setHapticsEnabled,
                                onClearHistory = viewModel::clearHistory,
                                onSetOutputDirectory = viewModel::setOutputDirectory,
                                onExportSettings = viewModel::exportSettingsToUri,
                                onImportSettings = viewModel::importSettingsFromUri,
                                onClearTemporaryFiles = viewModel::clearTemporaryFiles
                            )
                        }
                    }
                }

                uiState.incomingImportPrompt?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = viewModel::dismissIncomingImport,
                        title = { Text(prompt.title) },
                        text = { Text(prompt.body) },
                        confirmButton = {
                            TextButton(onClick = viewModel::confirmIncomingImport) {
                                Text(prompt.confirmLabel)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = viewModel::dismissIncomingImport) {
                                Text(prompt.dismissLabel)
                            }
                        }
                    )
                }
            }
        }
    }
}

private val AppDestination.title: String
    get() = when (this) {
        AppDestination.Home -> "Home"
        AppDestination.Convert -> "Converti"
        AppDestination.Common -> "Comuni"
        AppDestination.History -> "Cronologia"
        AppDestination.Settings -> "Impostazioni"
    }

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
