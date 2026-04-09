package com.p2r3.convert.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.p2r3.convert.model.AppSettings
import com.p2r3.convert.model.EngineDiagnostics
import com.p2r3.convert.model.OutputNamingPolicy
import com.p2r3.convert.model.PerformancePreset
import com.p2r3.convert.model.StartDestination
import com.p2r3.convert.model.ThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    engineBody: String,
    engineDiagnostics: EngineDiagnostics?,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onStartDestination: (StartDestination) -> Unit,
    onAutoPreview: (Boolean) -> Unit,
    onPreviewLimit: (Int) -> Unit,
    onOutputNamingPolicy: (OutputNamingPolicy) -> Unit,
    onAutoOpenResult: (Boolean) -> Unit,
    onKeepHistory: (Boolean) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onPerformancePreset: (PerformancePreset) -> Unit,
    onMaxParallelJobs: (Int) -> Unit,
    onBatteryFriendlyMode: (Boolean) -> Unit,
    onConfirmBeforeBatch: (Boolean) -> Unit,
    onReduceMotion: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onSetOutputDirectory: (String?) -> Unit,
    onExportSettings: (Uri) -> Unit,
    onImportSettings: (Uri) -> Unit,
    onClearTemporaryFiles: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        onSetOutputDirectory(uri.toString())
    }
    val exportSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        onExportSettings(uri)
    }
    val importSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        onImportSettings(uri)
    }

    fun matches(text: String): Boolean = query.isBlank() || text.contains(query, ignoreCase = true)

    ScreenContainer {
        item {
            AnimatedScreenBlock(index = 0, reduceMotion = settings.reduceMotion) {
                SearchBar(
                    inputField = {
                        SearchBarDefaults.InputField(
                            query = query,
                            onQueryChange = { next -> query = next },
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = {},
                            placeholder = { Text("Cerca impostazioni") }
                        )
                    },
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier.fillMaxWidth()
                ) { }
            }
        }
        if (matches("aspetto appearance tema dynamic colore home")) {
            item {
                AnimatedScreenBlock(index = 1, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Aspetto", "Tema, dynamic color e schermata iniziale.")
                }
            }
            item {
                AnimatedScreenBlock(index = 2, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Aspetto") {
                        ListItem(
                            headlineContent = { Text("Tema") },
                            supportingContent = { Text("Scegli il comportamento generale dell'interfaccia.") }
                        )
                        EnumChoiceRow(
                            values = ThemeMode.entries,
                            selected = settings.themeMode,
                            label = { it.name.lowercase().replaceFirstChar(Char::titlecase) },
                            onSelect = onThemeMode
                        )
                        SwitchRow(
                            "Dynamic color",
                            "Usa i colori derivati dallo sfondo di Android quando disponibili.",
                            settings.useDynamicColor,
                            onDynamicColor
                        )
                        ListItem(
                            headlineContent = { Text("Schermata iniziale") },
                            supportingContent = { Text("La destinazione che apri piu spesso puo diventare quella di default.") }
                        )
                        EnumChoiceRow(
                            values = StartDestination.entries,
                            selected = settings.startDestination,
                            label = {
                                when (it) {
                                    StartDestination.HOME -> "Home"
                                    StartDestination.CONVERT -> "Converti"
                                    StartDestination.COMMON -> "Comuni"
                                    StartDestination.HISTORY -> "Cronologia"
                                    StartDestination.SETTINGS -> "Impostazioni"
                                }
                            },
                            onSelect = onStartDestination
                        )
                    }
                }
            }
        }
        if (matches("conversione conversion preview naming output")) {
            item {
                AnimatedScreenBlock(index = 3, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Conversione", "Anteprima, naming dei file e comportamento del risultato.")
                }
            }
            item {
                AnimatedScreenBlock(index = 4, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Flusso di conversione") {
                        SwitchRow(
                            "Anteprima automatica",
                            "Prepara i metadati di preview mentre scegli la route.",
                            settings.autoPreview,
                            onAutoPreview
                        )
                        ListItem(
                            headlineContent = { Text("Limite preview") },
                            supportingContent = { Text("${settings.previewSizeLimitMb} MB") }
                        )
                        Slider(
                            value = settings.previewSizeLimitMb.toFloat(),
                            onValueChange = { onPreviewLimit(it.toInt()) },
                            valueRange = 1f..50f
                        )
                        ListItem(
                            headlineContent = { Text("Naming output") },
                            supportingContent = { Text("Scegli come costruire il nome del file esportato.") }
                        )
                        EnumChoiceRow(
                            values = OutputNamingPolicy.entries,
                            selected = settings.outputNamingPolicy,
                            label = {
                                when (it) {
                                    OutputNamingPolicy.KEEP_SOURCE_STEM -> "Keep name"
                                    OutputNamingPolicy.APPEND_TARGET -> "Append target"
                                    OutputNamingPolicy.TIMESTAMP_SUFFIX -> "Timestamp"
                                }
                            },
                            onSelect = onOutputNamingPolicy
                        )
                        SwitchRow(
                            "Apri risultato automaticamente",
                            "Apre il file convertito appena possibile dopo un job riuscito.",
                            settings.autoOpenResult,
                            onAutoOpenResult
                        )
                    }
                }
            }
        }
        if (matches("file files cartella output")) {
            item {
                AnimatedScreenBlock(index = 5, reduceMotion = settings.reduceMotion) {
                    SectionTitle("File", "Cartella di output predefinita e comportamento del salvataggio.")
                }
            }
            item {
                AnimatedScreenBlock(index = 6, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Destinazione output") {
                        ListItem(
                            headlineContent = { Text("Percorso attuale") },
                            supportingContent = {
                                Text(settings.outputDirectoryUri ?: "Cartella Downloads gestita dall'app")
                            }
                        )
                        TextButton(onClick = { folderPicker.launch(null) }) {
                            Text("Scegli cartella")
                        }
                        TextButton(onClick = { onSetOutputDirectory(null) }) {
                            Text("Usa cartella app")
                        }
                    }
                }
            }
        }
        if (matches("performance batteria parallel job")) {
            item {
                AnimatedScreenBlock(index = 7, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Performance", "Scheduler, budget CPU e profili adatti all'uso mobile.")
                }
            }
            item {
                AnimatedScreenBlock(index = 8, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Profilo di performance") {
                        EnumChoiceRow(
                            values = PerformancePreset.entries,
                            selected = settings.performancePreset,
                            label = {
                                when (it) {
                                    PerformancePreset.BATTERY -> "Battery"
                                    PerformancePreset.BALANCED -> "Balanced"
                                    PerformancePreset.PERFORMANCE -> "Performance"
                                }
                            },
                            onSelect = onPerformancePreset
                        )
                        ListItem(
                            headlineContent = { Text("Job paralleli massimi") },
                            supportingContent = { Text(settings.maxParallelJobs.toString()) }
                        )
                        Slider(
                            value = settings.maxParallelJobs.toFloat(),
                            onValueChange = { onMaxParallelJobs(it.toInt().coerceAtLeast(1)) },
                            valueRange = 1f..6f
                        )
                        SwitchRow(
                            "Modalita battery friendly",
                            "Privilegia temperature piu basse e job piu stabili sul telefono.",
                            settings.batteryFriendlyMode,
                            onBatteryFriendlyMode
                        )
                        SwitchRow(
                            "Tieni lo schermo acceso durante i job",
                            "Evita sospensioni accidentali durante conversioni lunghe.",
                            settings.keepScreenOn,
                            onKeepScreenOn
                        )
                        SwitchRow(
                            "Conferma prima dei batch",
                            "Aggiunge un controllo in piu prima dei job multi-file.",
                            settings.confirmBeforeBatch,
                            onConfirmBeforeBatch
                        )
                    }
                }
            }
        }
        if (matches("privacy cronologia history")) {
            item {
                AnimatedScreenBlock(index = 9, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Privacy", "Cronologia, persistenza e pulizia rapida.")
                }
            }
            item {
                AnimatedScreenBlock(index = 10, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Cronologia e privacy") {
                        SwitchRow(
                            "Mantieni la cronologia conversioni",
                            "Conserva i job recenti nella schermata Cronologia.",
                            settings.keepHistory,
                            onKeepHistory
                        )
                        TextButton(onClick = onClearHistory) {
                            Text("Svuota cronologia adesso")
                        }
                    }
                }
            }
        }
        if (matches("accessibilita accessibility motion haptics")) {
            item {
                AnimatedScreenBlock(index = 11, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Accessibilita", "Controlli per motion ridotto e feedback aptico.")
                }
            }
            item {
                AnimatedScreenBlock(index = 12, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Accessibilita") {
                        SwitchRow(
                            "Riduci motion",
                            "Disattiva le animazioni meno importanti in tutta l'app.",
                            settings.reduceMotion,
                            onReduceMotion
                        )
                        SwitchRow(
                            "Feedback aptico",
                            "Usa una vibrazione leggera nelle azioni piu importanti.",
                            settings.hapticsEnabled,
                            onHaptics
                        )
                    }
                }
            }
        }
        if (matches("avanzate advanced diagnostics export import motore")) {
            item {
                AnimatedScreenBlock(index = 13, reduceMotion = settings.reduceMotion) {
                    SectionTitle("Avanzate", "Diagnostica motore, import/export e manutenzione.")
                }
            }
            item {
                AnimatedScreenBlock(index = 14, reduceMotion = settings.reduceMotion) {
                    ChoiceCard("Diagnostica e manutenzione") {
                        Text(
                            engineBody,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        engineDiagnostics?.let { diagnostics ->
                            MetadataRow("Bridge validation", diagnostics.bridgeValidationState.name)
                            MetadataRow("Bridge formati", diagnostics.validatedBridgeFormatCount.toString())
                            MetadataRow("Bridge handler", diagnostics.validatedBridgeHandlerCount.toString())
                            MetadataRow("Cache source", diagnostics.cacheSource ?: "n/d")
                            diagnostics.diagnosticsMessage?.let { message ->
                                Text(
                                    message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                        TextButton(onClick = { exportSettingsLauncher.launch("convert-to-it-settings.json") }) {
                            Text("Esporta impostazioni")
                        }
                        TextButton(onClick = { importSettingsLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Importa impostazioni")
                        }
                        TextButton(onClick = onClearTemporaryFiles) {
                            Text("Pulisci cache e temp")
                        }
                    }
                }
            }
        }
    }
}
