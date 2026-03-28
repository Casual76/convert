package com.p2r3.convert.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.p2r3.convert.data.engine.ConversionEngine
import com.p2r3.convert.data.history.HistoryRepository
import com.p2r3.convert.data.jobs.ConversionJobScheduler
import com.p2r3.convert.data.jobs.deserializeConversionRequest
import com.p2r3.convert.data.preset.PresetRepository
import com.p2r3.convert.data.settings.SettingsRepository
import com.p2r3.convert.model.AppSettings
import com.p2r3.convert.model.CommonConversionPreset
import com.p2r3.convert.model.ConversionHistoryEntry
import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.ConversionRequest
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import com.p2r3.convert.model.RoutePreview
import com.p2r3.convert.model.StartDestination
import com.p2r3.convert.model.ThemeMode
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

data class PickedInput(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val detectedFormatId: String? = null,
    val detectedFormatLabel: String? = null
)

data class SessionState(
    val catalog: List<FormatDescriptor> = emptyList(),
    val selectedInputs: List<PickedInput> = emptyList(),
    val selectedSource: FormatDescriptor? = null,
    val availableTargets: List<FormatDescriptor> = emptyList(),
    val selectedTarget: FormatDescriptor? = null,
    val selectedPreset: CommonConversionPreset? = null,
    val routePreview: RoutePreview? = null,
    val preview: ConversionPreview? = null,
    val isPlanning: Boolean = false,
    val isSubmitting: Boolean = false,
    val noticeMessage: String? = null,
    val pendingBatchConfirmationKey: String? = null
)

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val history: List<ConversionHistoryEntry> = emptyList(),
    val presets: List<CommonConversionPreset> = emptyList(),
    val featuredPresets: List<CommonConversionPreset> = emptyList(),
    val session: SessionState = SessionState(),
    val engineDiagnostics: EngineDiagnostics? = null,
    val pendingNavigationTarget: AppDestination? = null,
    val incomingImportPrompt: IncomingImportPrompt? = null
) {
    val engineHeadline: String
        get() = if (engineDiagnostics == null) {
            "Motore universale in inizializzazione"
        } else {
            "Motore universale pronto"
        }

    val engineBody: String
        get() {
            val diagnostics = engineDiagnostics
                ?: return "Sto caricando il catalogo effettivo e il bridge legacy headless."
            val cacheLabel = diagnostics.cacheSource?.let { "Cache $it" } ?: "Cache non ancora disponibile"
            val disabledLabel = if (diagnostics.disabledHandlers.isEmpty()) {
                "nessun handler disabilitato"
            } else {
                "${diagnostics.disabledHandlers.size} handler disabilitati"
            }
            return "${diagnostics.catalogFormatCount} formati disponibili sul device, ${diagnostics.handlerCount} handler attivi e fallback bridge invisibile quando serve. $cacheLabel, $disabledLabel."
        }
}

private enum class ImportOrigin {
    PICKER,
    SHARE_SHEET,
    OPEN_WITH
}

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val historyRepository: HistoryRepository,
    private val presetRepository: PresetRepository,
    private val conversionEngine: ConversionEngine,
    private val conversionJobScheduler: ConversionJobScheduler
) : ViewModel() {

    private val sessionState = MutableStateFlow(SessionState())
    private val diagnosticsState = MutableStateFlow<EngineDiagnostics?>(null)
    private val navigationTargetState = MutableStateFlow<AppDestination?>(null)
    private val incomingImportPromptState = MutableStateFlow<IncomingImportPrompt?>(null)
    private var pendingIncomingPayload: IncomingSharePayload? = null

    private val baseUiState = combine(
        settingsRepository.settings,
        historyRepository.history,
        presetRepository.presets,
        presetRepository.featuredPresets,
        sessionState
    ) { settings, history, presets, featured, session ->
        MainUiState(
            settings = settings,
            history = history,
            presets = presets,
            featuredPresets = featured,
            session = session
        )
    }.combine(navigationTargetState) { state, navigationTarget ->
        state.copy(pendingNavigationTarget = navigationTarget)
    }.combine(incomingImportPromptState) { state, incomingImportPrompt ->
        state.copy(incomingImportPrompt = incomingImportPrompt)
    }

    val uiState: StateFlow<MainUiState> = combine(baseUiState, diagnosticsState) { base, diagnostics ->
        base.copy(engineDiagnostics = diagnostics)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    init {
        viewModelScope.launch {
            refreshCatalogAndDiagnostics()
        }
    }

    fun dismissNotice() {
        sessionState.update { it.copy(noticeMessage = null) }
    }

    fun consumeNavigationRequest() {
        navigationTargetState.value = null
    }

    fun handleIncomingPayload(payload: IncomingSharePayload) {
        viewModelScope.launch {
            navigationTargetState.value = AppDestination.Convert
            if (hasActiveSession(sessionState.value)) {
                pendingIncomingPayload = payload
                incomingImportPromptState.value = buildIncomingImportPrompt(payload)
                return@launch
            }

            pendingIncomingPayload = null
            incomingImportPromptState.value = null
            ingestInputUris(payload.uris, payload.source.toImportOrigin())
        }
    }

    fun confirmIncomingImport() {
        viewModelScope.launch {
            val payload = pendingIncomingPayload ?: return@launch
            pendingIncomingPayload = null
            incomingImportPromptState.value = null
            ingestInputUris(payload.uris, payload.source.toImportOrigin())
        }
    }

    fun dismissIncomingImport() {
        pendingIncomingPayload = null
        incomingImportPromptState.value = null
        sessionState.update {
            it.copy(noticeMessage = "Importazione esterna annullata. Mantengo la sessione corrente.")
        }
    }

    fun applyPreset(preset: CommonConversionPreset) {
        viewModelScope.launch {
            val catalog = sessionState.value.catalog
            val source = catalog.firstOrNull { it.id == preset.sourceFormatId }
            val target = catalog.firstOrNull { it.id == preset.targetFormatId }
            val targets = source?.let { sessionTargetsFor(it.id) }.orEmpty()
            sessionState.update {
                it.copy(
                    selectedPreset = preset,
                    selectedSource = source ?: it.selectedSource,
                    selectedTarget = target ?: it.selectedTarget,
                    availableTargets = targets,
                    noticeMessage = if (source != null && target != null) {
                        "${preset.title} pronto nel flusso nativo."
                    } else {
                        "Il preset e stato selezionato, ma il catalogo sta ancora completando il mapping dei formati."
                    }
                )
            }
            refreshPlanning()
        }
    }

    fun onFilesPicked(uris: List<Uri>) {
        viewModelScope.launch {
            ingestInputUris(uris, ImportOrigin.PICKER)
        }
    }

    fun selectSource(format: FormatDescriptor) {
        viewModelScope.launch {
            val targets = sessionTargetsFor(format.id)
            sessionState.update {
                it.copy(
                    selectedSource = format,
                    availableTargets = targets,
                    selectedTarget = it.selectedTarget?.takeIf { current -> targets.any { target -> target.id == current.id } },
                    pendingBatchConfirmationKey = null
                )
            }
            refreshPlanning()
        }
    }

    fun selectTarget(format: FormatDescriptor) {
        viewModelScope.launch {
            sessionState.update {
                it.copy(
                    selectedTarget = format,
                    pendingBatchConfirmationKey = null
                )
            }
            refreshPlanning()
        }
    }

    fun clearSelection() {
        pendingIncomingPayload = null
        incomingImportPromptState.value = null
        sessionState.value = sessionState.value.copy(
            selectedInputs = emptyList(),
            selectedSource = null,
            availableTargets = emptyList(),
            selectedTarget = null,
            selectedPreset = null,
            routePreview = null,
            preview = null,
            pendingBatchConfirmationKey = null,
            isPlanning = false,
            isSubmitting = false
        )
    }

    fun startConversion() {
        viewModelScope.launch {
            val settings = uiState.value.settings
            val session = sessionState.value
            val target = session.selectedTarget ?: run {
                sessionState.update { it.copy(noticeMessage = "Scegli prima un formato di output.") }
                return@launch
            }
            if (session.selectedInputs.isEmpty()) {
                sessionState.update { it.copy(noticeMessage = "Seleziona almeno un file di input.") }
                return@launch
            }

            val source = session.selectedSource ?: run {
                sessionState.update { it.copy(noticeMessage = "Conferma o correggi il formato sorgente prima di avviare il job.") }
                return@launch
            }

            val batchConfirmationKey = buildBatchConfirmationKey(session.selectedInputs, source.id, target.id)
            if (settings.confirmBeforeBatch && session.selectedInputs.size > 1 && session.pendingBatchConfirmationKey != batchConfirmationKey) {
                sessionState.update {
                    it.copy(
                        pendingBatchConfirmationKey = batchConfirmationKey,
                        noticeMessage = "Batch di ${session.selectedInputs.size} file pronto. Premi ancora Avvia conversione per confermare."
                    )
                }
                return@launch
            }

            sessionState.update { it.copy(isSubmitting = true, pendingBatchConfirmationKey = null) }

            val request = ConversionRequest(
                inputUris = session.selectedInputs.map { it.uri },
                sourceFormatId = source.id,
                targetFormatId = target.id,
                presetId = session.selectedPreset?.id,
                outputDirectoryUri = settings.outputDirectoryUri,
                outputNamingPolicy = settings.outputNamingPolicy,
                performancePreset = settings.performancePreset,
                routeToken = session.routePreview?.routeToken,
                allowBridgeFallback = true,
                maxParallelJobs = settings.maxParallelJobs,
                batteryFriendlyMode = settings.batteryFriendlyMode,
                autoOpenResult = settings.autoOpenResult,
                previewLimitBytes = settings.previewSizeLimitMb.toLong() * 1024L * 1024L
            )

            conversionJobScheduler.schedule(
                request = request,
                title = "${source.shortLabel} to ${target.shortLabel}",
                subtitle = if (session.selectedInputs.size == 1) {
                    session.selectedInputs.first().displayName
                } else {
                    "${session.selectedInputs.size} file"
                },
                presetTitle = session.selectedPreset?.title
            )

            sessionState.update {
                it.copy(
                    isSubmitting = false,
                    noticeMessage = "Conversione accodata nello scheduler nativo."
                )
            }
        }
    }

    fun rerunHistoryEntry(entry: ConversionHistoryEntry) {
        viewModelScope.launch {
            val snapshot = entry.requestSnapshot ?: run {
                sessionState.update { it.copy(noticeMessage = "Questo job non contiene un request snapshot riutilizzabile.") }
                return@launch
            }
            val request = runCatching { deserializeConversionRequest(snapshot) }.getOrElse {
                sessionState.update { state -> state.copy(noticeMessage = "Impossibile ricostruire il job dalla cronologia.") }
                return@launch
            }
            val targetDescriptor = sessionState.value.catalog.firstOrNull { it.id == request.targetFormatId }
            val sourceDescriptor = request.sourceFormatId?.let { sourceId ->
                sessionState.value.catalog.firstOrNull { it.id == sourceId }
            }
            conversionJobScheduler.schedule(
                request = request,
                title = "${sourceDescriptor?.shortLabel ?: "Input"} to ${targetDescriptor?.shortLabel ?: request.targetFormatId}",
                subtitle = if (request.inputUris.size == 1) "Riesecuzione da cronologia" else "Riesecuzione batch da cronologia",
                presetTitle = entry.presetTitle
            )
            sessionState.update { it.copy(noticeMessage = "Job riaccodato dalla cronologia.") }
        }
    }

    fun exportSettingsToUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(serializeSettings(uiState.value.settings))
                } ?: error("No output stream for $uri")
            }.onSuccess {
                sessionState.update { it.copy(noticeMessage = "Impostazioni esportate con successo.") }
            }.onFailure { error ->
                sessionState.update { it.copy(noticeMessage = "Export impostazioni fallito: ${error.message}") }
            }
        }
    }

    fun importSettingsFromUri(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val raw = appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("No input stream for $uri")
                applyImportedSettings(parseSettings(raw))
            }.onSuccess {
                sessionState.update { it.copy(noticeMessage = "Impostazioni importate con successo.") }
            }.onFailure { error ->
                sessionState.update { it.copy(noticeMessage = "Import impostazioni fallito: ${error.message}") }
            }
        }
    }

    fun clearTemporaryFiles() {
        viewModelScope.launch {
            runCatching {
                clearDirectory(appContext.cacheDir)
                clearDirectory(appContext.externalCacheDir)
                clearDirectory(File(appContext.filesDir, "bridge-temp"))
                clearDirectory(File(appContext.filesDir, "temp"))
            }.onSuccess {
                sessionState.update { it.copy(noticeMessage = "Cache e file temporanei puliti.") }
            }.onFailure { error ->
                sessionState.update { it.copy(noticeMessage = "Pulizia cache fallita: ${error.message}") }
            }
        }
    }

    fun setThemeMode(value: ThemeMode) = launchSettingUpdate { settingsRepository.setThemeMode(value) }
    fun setDynamicColorEnabled(enabled: Boolean) = launchSettingUpdate { settingsRepository.setDynamicColorEnabled(enabled) }
    fun setStartDestination(value: StartDestination) = launchSettingUpdate { settingsRepository.setStartDestination(value) }
    fun setAutoPreview(enabled: Boolean) = launchSettingUpdate { settingsRepository.setAutoPreview(enabled) }
    fun setPreviewLimit(value: Int) = launchSettingUpdate { settingsRepository.setPreviewSizeLimitMb(value) }
    fun setOutputDirectory(uri: String?) = launchSettingUpdate { settingsRepository.setOutputDirectoryUri(uri) }
    fun setOutputNamingPolicy(value: OutputNamingPolicy) = launchSettingUpdate { settingsRepository.setOutputNamingPolicy(value) }
    fun setAutoOpenResult(enabled: Boolean) = launchSettingUpdate { settingsRepository.setAutoOpenResult(enabled) }
    fun setKeepHistory(enabled: Boolean) = launchSettingUpdate { settingsRepository.setKeepHistory(enabled) }
    fun setKeepScreenOn(enabled: Boolean) = launchSettingUpdate { settingsRepository.setKeepScreenOn(enabled) }
    fun setPerformancePreset(value: PerformancePreset) = launchSettingUpdate { settingsRepository.setPerformancePreset(value) }
    fun setMaxParallelJobs(value: Int) = launchSettingUpdate { settingsRepository.setMaxParallelJobs(value) }
    fun setBatteryFriendlyMode(enabled: Boolean) = launchSettingUpdate { settingsRepository.setBatteryFriendlyMode(enabled) }
    fun setConfirmBeforeBatch(enabled: Boolean) = launchSettingUpdate { settingsRepository.setConfirmBeforeBatch(enabled) }
    fun setReduceMotion(enabled: Boolean) = launchSettingUpdate { settingsRepository.setReduceMotion(enabled) }
    fun setHapticsEnabled(enabled: Boolean) = launchSettingUpdate { settingsRepository.setHapticsEnabled(enabled) }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clearAll()
            sessionState.update { it.copy(noticeMessage = "Cronologia conversioni svuotata.") }
        }
    }

    private suspend fun refreshCatalogAndDiagnostics() {
        runCatching {
            val catalog = conversionEngine.loadCatalog()
            val diagnostics = conversionEngine.diagnostics()
            val currentSession = sessionState.value
            val remappedSource = currentSession.selectedSource?.let { current ->
                catalog.firstOrNull { it.id == current.id } ?: current
            }
            val remappedTargets = remappedSource?.let { sessionTargetsFor(it.id) }.orEmpty()
            val remappedTarget = currentSession.selectedTarget?.let { current ->
                catalog.firstOrNull { it.id == current.id } ?: current
            }?.takeIf { current -> remappedTargets.any { it.id == current.id } }

            sessionState.update {
                it.copy(
                    catalog = catalog,
                    selectedSource = remappedSource,
                    availableTargets = remappedTargets,
                    selectedTarget = remappedTarget
                )
            }
            diagnosticsState.value = diagnostics
            if (remappedSource != null && remappedTarget != null) {
                refreshPlanning()
            }
        }.onFailure { error ->
            sessionState.update { it.copy(noticeMessage = "Avvio motore fallito: ${error.message}") }
        }
    }

    private suspend fun ingestInputUris(uris: List<Uri>, origin: ImportOrigin) {
        if (uris.isEmpty()) return

        val orderedUris = uris
            .distinctBy(Uri::toString)
            .sortedBy(::resolveDisplayName)

        val detectedFormatsById = linkedMapOf<String, FormatDescriptor>()
        val picked = orderedUris.map { uri ->
            persistReadPermission(uri)
            val displayName = resolveDisplayName(uri)
            val mimeType = appContext.contentResolver.getType(uri)
            val detected = conversionEngine.detectFormat(
                uri = uri.toString(),
                fileName = displayName,
                mimeType = mimeType
            )
            detected?.let { detectedFormatsById.putIfAbsent(it.id, it) }
            PickedInput(
                uri = uri.toString(),
                displayName = displayName,
                mimeType = mimeType,
                sizeBytes = resolveFileSize(uri),
                detectedFormatId = detected?.id,
                detectedFormatLabel = detected?.shortLabel
            )
        }

        val currentSelection = sessionState.value.selectedSource
        val detectedFormats = detectedFormatsById.values.toList()
        val source = when {
            detectedFormats.size == 1 -> detectedFormats.first()
            currentSelection != null && picked.all {
                it.detectedFormatId == null || it.detectedFormatId == currentSelection.id
            } -> currentSelection
            else -> null
        }
        val targets = source?.let { sessionTargetsFor(it.id) }.orEmpty()
        val detectionNotice = buildDetectionNotice(
            inputs = picked,
            detectedFormats = detectedFormats,
            chosenSource = source,
            previousSelection = currentSelection
        )

        sessionState.update {
            it.copy(
                selectedInputs = picked,
                selectedSource = source,
                availableTargets = targets,
                selectedTarget = null,
                selectedPreset = null,
                routePreview = null,
                preview = null,
                isPlanning = false,
                isSubmitting = false,
                pendingBatchConfirmationKey = null,
                noticeMessage = buildImportNotice(origin, picked.size, detectionNotice)
            )
        }
    }

    private fun launchSettingUpdate(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun refreshPlanning() {
        val session = sessionState.value
        val source = session.selectedSource ?: run {
            sessionState.update { it.copy(routePreview = null, preview = null, isPlanning = false) }
            return
        }
        val target = session.selectedTarget ?: run {
            sessionState.update { it.copy(routePreview = null, preview = null, isPlanning = false) }
            return
        }

        sessionState.update { it.copy(isPlanning = true) }

        val totalBytes = session.selectedInputs.sumOf { it.sizeBytes }
        runCatching {
            val route = conversionEngine.planRoute(
                sourceFormatId = source.id,
                targetFormatId = target.id,
                fileCount = session.selectedInputs.size.coerceAtLeast(1),
                totalBytes = totalBytes
            )
            val preview = if (uiState.value.settings.autoPreview && session.selectedInputs.isNotEmpty() && route != null) {
                conversionEngine.generatePreview(
                    inputUri = session.selectedInputs.first().uri,
                    sourceFormatId = source.id,
                    targetFormatId = target.id,
                    routeToken = route.routeToken,
                    previewLimitBytes = uiState.value.settings.previewSizeLimitMb.toLong() * 1024L * 1024L
                )
            } else {
                null
            }
            sessionState.update {
                it.copy(
                    routePreview = route,
                    preview = preview,
                    isPlanning = false
                )
            }
        }.onFailure { error ->
            sessionState.update {
                it.copy(
                    routePreview = null,
                    preview = null,
                    isPlanning = false,
                    noticeMessage = "Pianificazione route fallita: ${error.message}"
                )
            }
        }
    }

    private suspend fun sessionTargetsFor(sourceFormatId: String): List<FormatDescriptor> =
        runCatching { conversionEngine.listTargets(sourceFormatId) }.getOrDefault(emptyList())

    private fun hasActiveSession(session: SessionState): Boolean =
        session.selectedInputs.isNotEmpty()
            || session.selectedSource != null
            || session.selectedTarget != null
            || session.routePreview != null
            || session.isSubmitting

    private fun buildIncomingImportPrompt(payload: IncomingSharePayload): IncomingImportPrompt {
        val itemLabel = if (payload.uris.size == 1) "questo file" else "questi ${payload.uris.size} file"
        val sourceLabel = when (payload.source) {
            IncomingShareSource.SHARE_SHEET -> "ricevuto da un'altra app"
            IncomingShareSource.OPEN_WITH -> "aperto dall'esterno"
        }
        return IncomingImportPrompt(
            title = "Sostituire la selezione corrente?",
            body = "Hai $sourceLabel $itemLabel. Se continui, la sessione di conversione attuale verra sostituita e i nuovi file verranno caricati nella schermata Converti."
        )
    }

    private fun buildImportNotice(origin: ImportOrigin, fileCount: Int, detectionNotice: String): String {
        val prefix = when (origin) {
            ImportOrigin.PICKER -> ""
            ImportOrigin.SHARE_SHEET -> if (fileCount == 1) {
                "File ricevuto da un'altra app. "
            } else {
                "$fileCount file ricevuti da un'altra app. "
            }

            ImportOrigin.OPEN_WITH -> if (fileCount == 1) {
                "File aperto dall'esterno. "
            } else {
                "$fileCount file aperti dall'esterno. "
            }
        }
        return prefix + detectionNotice
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildBatchConfirmationKey(
        inputs: List<PickedInput>,
        sourceFormatId: String,
        targetFormatId: String
    ): String = buildString {
        append(sourceFormatId)
        append('|')
        append(targetFormatId)
        append('|')
        inputs.forEach {
            append(it.uri)
            append(';')
        }
    }

    private suspend fun applyImportedSettings(settings: AppSettings) {
        settingsRepository.setThemeMode(settings.themeMode)
        settingsRepository.setDynamicColorEnabled(settings.useDynamicColor)
        settingsRepository.setStartDestination(settings.startDestination)
        settingsRepository.setAutoPreview(settings.autoPreview)
        settingsRepository.setPreviewSizeLimitMb(settings.previewSizeLimitMb)
        settingsRepository.setOutputDirectoryUri(settings.outputDirectoryUri)
        settingsRepository.setOutputNamingPolicy(settings.outputNamingPolicy)
        settingsRepository.setAutoOpenResult(settings.autoOpenResult)
        settingsRepository.setKeepHistory(settings.keepHistory)
        settingsRepository.setKeepScreenOn(settings.keepScreenOn)
        settingsRepository.setPerformancePreset(settings.performancePreset)
        settingsRepository.setMaxParallelJobs(settings.maxParallelJobs)
        settingsRepository.setBatteryFriendlyMode(settings.batteryFriendlyMode)
        settingsRepository.setConfirmBeforeBatch(settings.confirmBeforeBatch)
        settingsRepository.setReduceMotion(settings.reduceMotion)
        settingsRepository.setHapticsEnabled(settings.hapticsEnabled)
    }

    private fun serializeSettings(settings: AppSettings): String = JSONObject()
        .put("themeMode", settings.themeMode.name)
        .put("useDynamicColor", settings.useDynamicColor)
        .put("startDestination", settings.startDestination.name)
        .put("autoPreview", settings.autoPreview)
        .put("previewSizeLimitMb", settings.previewSizeLimitMb)
        .put("outputDirectoryUri", settings.outputDirectoryUri)
        .put("outputNamingPolicy", settings.outputNamingPolicy.name)
        .put("autoOpenResult", settings.autoOpenResult)
        .put("keepHistory", settings.keepHistory)
        .put("keepScreenOn", settings.keepScreenOn)
        .put("performancePreset", settings.performancePreset.name)
        .put("maxParallelJobs", settings.maxParallelJobs)
        .put("batteryFriendlyMode", settings.batteryFriendlyMode)
        .put("confirmBeforeBatch", settings.confirmBeforeBatch)
        .put("reduceMotion", settings.reduceMotion)
        .put("hapticsEnabled", settings.hapticsEnabled)
        .toString(2)

    private fun parseSettings(raw: String): AppSettings {
        val json = JSONObject(raw)
        val defaults = AppSettings()
        return AppSettings(
            themeMode = json.optString("themeMode").takeIf { it.isNotBlank() }?.let { enumValueOf<ThemeMode>(it) } ?: defaults.themeMode,
            useDynamicColor = json.optBoolean("useDynamicColor", defaults.useDynamicColor),
            startDestination = json.optString("startDestination").takeIf { it.isNotBlank() }?.let { enumValueOf<StartDestination>(it) } ?: defaults.startDestination,
            autoPreview = json.optBoolean("autoPreview", defaults.autoPreview),
            previewSizeLimitMb = json.optInt("previewSizeLimitMb", defaults.previewSizeLimitMb),
            outputDirectoryUri = json.optString("outputDirectoryUri").takeIf { it.isNotBlank() },
            outputNamingPolicy = json.optString("outputNamingPolicy").takeIf { it.isNotBlank() }?.let { enumValueOf<OutputNamingPolicy>(it) } ?: defaults.outputNamingPolicy,
            autoOpenResult = json.optBoolean("autoOpenResult", defaults.autoOpenResult),
            keepHistory = json.optBoolean("keepHistory", defaults.keepHistory),
            keepScreenOn = json.optBoolean("keepScreenOn", defaults.keepScreenOn),
            performancePreset = json.optString("performancePreset").takeIf { it.isNotBlank() }?.let { enumValueOf<PerformancePreset>(it) } ?: defaults.performancePreset,
            maxParallelJobs = json.optInt("maxParallelJobs", defaults.maxParallelJobs),
            batteryFriendlyMode = json.optBoolean("batteryFriendlyMode", defaults.batteryFriendlyMode),
            confirmBeforeBatch = json.optBoolean("confirmBeforeBatch", defaults.confirmBeforeBatch),
            reduceMotion = json.optBoolean("reduceMotion", defaults.reduceMotion),
            hapticsEnabled = json.optBoolean("hapticsEnabled", defaults.hapticsEnabled)
        )
    }

    private fun buildDetectionNotice(
        inputs: List<PickedInput>,
        detectedFormats: List<FormatDescriptor>,
        chosenSource: FormatDescriptor?,
        previousSelection: FormatDescriptor?
    ): String {
        if (inputs.isEmpty()) return "Nessun file selezionato."

        val detectedCount = inputs.count { it.detectedFormatId != null }
        return when {
            detectedFormats.size == 1 && chosenSource != null -> {
                val scope = if (inputs.size == 1) "il file selezionato" else "tutti i ${inputs.size} file"
                "${chosenSource.shortLabel} rilevato automaticamente per $scope."
            }

            detectedFormats.size > 1 -> {
                val labels = detectedFormats.map { it.shortLabel }.sorted().joinToString(", ")
                "Sono stati rilevati piu formati di input ($labels). Seleziona manualmente il formato sorgente o separa il batch."
            }

            detectedCount in 1 until inputs.size && previousSelection != null && chosenSource == previousSelection -> {
                "Rilevamento parziale completato. Mantengo ${previousSelection.shortLabel} come formato sorgente corrente."
            }

            detectedCount > 0 -> {
                "Rilevamento parziale completato. Controlla il formato sorgente prima di avviare il job."
            }

            chosenSource != null -> {
                "Rilevamento automatico incompleto: mantengo il formato sorgente selezionato."
            }

            else -> {
                "Rilevamento automatico non riuscito: scegli manualmente il formato sorgente."
            }
        }
    }

    private fun clearDirectory(directory: File?) {
        if (directory == null || !directory.exists()) return
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                child.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.lastPathSegment ?: "input-file"
        }
        val cursor: Cursor? = appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && it.moveToFirst()) {
                return it.getString(index)
            }
        }
        return uri.lastPathSegment ?: "input-file"
    }

    private fun resolveFileSize(uri: Uri): Long {
        appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index != -1 && cursor.moveToFirst()) {
                return cursor.getLong(index)
            }
        }
        return 0L
    }

    private fun IncomingShareSource.toImportOrigin(): ImportOrigin = when (this) {
        IncomingShareSource.SHARE_SHEET -> ImportOrigin.SHARE_SHEET
        IncomingShareSource.OPEN_WITH -> ImportOrigin.OPEN_WITH
    }
}
