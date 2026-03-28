package com.p2r3.convert.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.p2r3.convert.model.ConversionPreview
import com.p2r3.convert.model.FormatDescriptor
import com.p2r3.convert.model.PreviewKind
import com.p2r3.convert.model.RoutePreview
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    uiState: MainUiState,
    reduceMotion: Boolean,
    onPickFiles: (List<Uri>) -> Unit,
    onSelectSource: (FormatDescriptor) -> Unit,
    onSelectTarget: (FormatDescriptor) -> Unit,
    onStartConversion: () -> Unit,
    onClearSelection: () -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onPickFiles(uris)
    }
    var showSourceSheet by rememberSaveable { mutableStateOf(false) }
    var showTargetSheet by rememberSaveable { mutableStateOf(false) }

    if (showSourceSheet) {
        FormatSheet(
            title = "Scegli il formato sorgente",
            options = uiState.session.catalog.filter { it.supportsInput },
            reduceMotion = reduceMotion,
            onDismiss = { showSourceSheet = false },
            onSelect = {
                onSelectSource(it)
                showSourceSheet = false
            }
        )
    }

    if (showTargetSheet) {
        FormatSheet(
            title = "Scegli il formato di output",
            options = uiState.session.availableTargets,
            reduceMotion = reduceMotion,
            onDismiss = { showTargetSheet = false },
            onSelect = {
                onSelectTarget(it)
                showTargetSheet = false
            }
        )
    }

    val selectedInputs = uiState.session.selectedInputs
    val detectedLabels = selectedInputs.mapNotNull { it.detectedFormatLabel }.distinct().sorted()
    val detectedCount = selectedInputs.count { it.detectedFormatId != null }
    val totalBytes = selectedInputs.sumOf { it.sizeBytes }
    val sourceSubtitle = when {
        selectedInputs.isEmpty() -> "Seleziona uno o piu file: il catalogo prova a riconoscere il formato in automatico."
        detectedLabels.size > 1 -> "Nel batch sono presenti piu formati. Conferma manualmente il sorgente."
        uiState.session.selectedSource != null -> "${uiState.session.selectedSource.mimeType} - puoi correggerlo in qualsiasi momento."
        else -> "Se il provider Android non fornisce abbastanza dati, puoi correggere il rilevamento a mano."
    }
    val targetSubtitle = when {
        uiState.session.selectedSource == null -> "Prima conferma il sorgente, poi il pannello mostera solo i target davvero raggiungibili."
        uiState.session.availableTargets.isEmpty() -> "Sto verificando le route disponibili sul device."
        uiState.session.selectedTarget != null -> "${uiState.session.availableTargets.size} output compatibili disponibili per questo sorgente."
        else -> "Seleziona uno dei ${uiState.session.availableTargets.size} target supportati per questo sorgente."
    }

    ScreenContainer(
        title = "Converti",
        subtitle = "Picker Android nativo, auto-detect sul catalogo completo, route preview e scheduler on-device.",
        reduceMotion = reduceMotion
    ) {
        item {
            AnimatedScreenBlock(index = 1, reduceMotion = reduceMotion) {
                InputWorkspaceCard(
                    inputs = selectedInputs,
                    detectedLabels = detectedLabels,
                    detectedCount = detectedCount,
                    totalBytes = totalBytes,
                    onPickFiles = { picker.launch(arrayOf("*/*")) },
                    onClearSelection = onClearSelection
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 2, reduceMotion = reduceMotion) {
                PairingCard(
                    label = "Formato sorgente",
                    title = uiState.session.selectedSource?.displayName ?: "Da rilevare o scegliere",
                    subtitle = sourceSubtitle,
                    onClick = { showSourceSheet = true }
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 3, reduceMotion = reduceMotion) {
                PairingCard(
                    label = "Formato di output",
                    title = uiState.session.selectedTarget?.displayName ?: "Scegli il target",
                    subtitle = targetSubtitle,
                    onClick = { showTargetSheet = true },
                    enabled = uiState.session.selectedSource != null
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 4, reduceMotion = reduceMotion) {
                AnimatedContent(
                    targetState = uiState.session.routePreview,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(if (reduceMotion) 80 else 120)) togetherWith
                            fadeOut(animationSpec = tween(if (reduceMotion) 60 else 90))
                    },
                    label = "route-preview"
                ) { route ->
                    if (route != null) {
                        RouteCard(route)
                    } else {
                        EmptyStateCard(
                            title = "Anteprima route",
                            body = "Dopo aver confermato sorgente e target, qui vedrai runtime, tempi attesi, impatto CPU e passaggi previsti."
                        )
                    }
                }
            }
        }
        item {
            AnimatedScreenBlock(index = 5, reduceMotion = reduceMotion) {
                AnimatedContent(
                    targetState = uiState.session.preview,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(if (reduceMotion) 80 else 120)) togetherWith
                            fadeOut(animationSpec = tween(if (reduceMotion) 60 else 90))
                    },
                    label = "preview-panel"
                ) { preview ->
                    PreviewCard(preview = preview)
                }
            }
        }
        item {
            AnimatedScreenBlock(index = 6, reduceMotion = reduceMotion) {
                Button(
                    onClick = onStartConversion,
                    enabled = selectedInputs.isNotEmpty() && uiState.session.selectedTarget != null && !uiState.session.isSubmitting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (uiState.session.isSubmitting) "Accodo il job..." else "Avvia conversione")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputWorkspaceCard(
    inputs: List<PickedInput>,
    detectedLabels: List<String>,
    detectedCount: Int,
    totalBytes: Long,
    onPickFiles: () -> Unit,
    onClearSelection: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        PremiumHeroSurface(reduceMotion = false) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("File di input", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (inputs.isEmpty()) {
                                "Scegli file dal picker Android per iniziare."
                            } else {
                                "Controlla il rilevamento automatico prima di pianificare la route."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricPill(label = "File", value = inputs.size.toString(), emphasized = true)
                    MetricPill(label = "Rilevati", value = if (inputs.isEmpty()) "0/0" else "$detectedCount/${inputs.size}")
                    MetricPill(label = "Peso", value = humanFileSize(totalBytes))
                    detectedLabels.take(3).forEach { label ->
                        MetricPill(label = "Auto", value = label)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onPickFiles) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Scegli file")
                    }
                    if (inputs.isNotEmpty()) {
                        OutlinedButton(onClick = onClearSelection) {
                            Icon(Icons.Outlined.Tune, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pulisci")
                        }
                    }
                }

                if (inputs.isEmpty()) {
                    Text(
                        "Il rilevamento usa nome file, MIME Android, alias comuni e catalogo unificato del motore.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    inputs.take(5).forEachIndexed { index, input ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f))
                        }
                        SelectedInputRow(input = input)
                    }
                    if (inputs.size > 5) {
                        Text(
                            "Altri ${inputs.size - 5} file selezionati.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedInputRow(input: PickedInput) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            input.displayName,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricPill(label = "Formato", value = input.detectedFormatLabel ?: "Da confermare")
            MetricPill(label = "MIME", value = input.mimeType ?: "Sconosciuto")
            MetricPill(label = "Peso", value = humanFileSize(input.sizeBytes))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(route: RoutePreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Anteprima route", style = MaterialTheme.typography.titleLarge)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricPill(label = "Motore", value = route.runtimeKind.name, emphasized = true)
                MetricPill(label = "Tempo", value = route.etaLabel)
                MetricPill(label = "CPU", value = route.cpuImpactLabel)
                MetricPill(label = "Affidabilita", value = route.confidenceLabel)
            }
            route.steps.forEach { step ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "${formatIdLabel(step.fromFormatId)} -> ${formatIdLabel(step.toFormatId)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            MetricPill(label = "Handler", value = step.handlerName)
                            MetricPill(label = "Classe", value = step.performanceClass)
                            MetricPill(label = "Batch", value = step.batchStrategy)
                            MetricPill(label = "Runtime", value = step.runtimeKind.name)
                        }
                        Text(
                            step.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            route.reasons.forEach { reason ->
                Text("- $reason", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: ConversionPreview?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Anteprima", style = MaterialTheme.typography.titleLarge)
            if (preview == null) {
                Text(
                    "Qui comparira l'anteprima non appena la route sara valida e abbastanza leggera da essere mostrata.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MetricPill(
                    label = "Modalita",
                    value = preview.kind.name.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase),
                    emphasized = preview.supported
                )
                Text(preview.headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    preview.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (preview.kind) {
                    PreviewKind.IMAGE_PROXY -> {
                        val imageUri = preview.proxyUri
                        if (imageUri != null) {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(MaterialTheme.shapes.large),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    PreviewKind.TEXT -> {
                        StatusCard(
                            title = "Anteprima testuale",
                            body = preview.textPreview ?: "Questa route resta leggera, quindi il contenuto finale puo essere mostrato inline."
                        )
                    }

                    PreviewKind.DOCUMENT -> {
                        StatusCard(
                            title = "Anteprima documento",
                            body = "Il PDF viene assemblato localmente quando parte il job."
                        )
                    }

                    PreviewKind.NONE -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FormatSheet(
    title: String,
    options: List<FormatDescriptor>,
    reduceMotion: Boolean,
    onDismiss: () -> Unit,
    onSelect: (FormatDescriptor) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val categories = options
        .flatMap { descriptor -> descriptor.categories.ifEmpty { listOf("Altro") } }
        .distinct()
        .sorted()

    val filteredOptions = options.filter { format ->
        val matchesQuery = query.isBlank() || listOf(
            format.displayName,
            format.shortLabel,
            format.extension,
            format.mimeType,
            format.id,
            format.categories.joinToString(" ")
        ).any { token -> token.contains(query, ignoreCase = true) }
        val matchesCategory = selectedCategory == null || format.categories.ifEmpty { listOf("Altro") }.contains(selectedCategory)
        matchesQuery && matchesCategory
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineMedium)
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Cerca formato") },
                            singleLine = true
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedCategory == null,
                                onClick = { selectedCategory = null },
                                label = { Text("Tutti") }
                            )
                            categories.forEach { category ->
                                FilterChip(
                                    selected = selectedCategory == category,
                                    onClick = { selectedCategory = if (selectedCategory == category) null else category },
                                    label = { Text(category) }
                                )
                            }
                        }
                    }
                }
            }

            if (filteredOptions.isEmpty()) {
                item {
                    AnimatedScreenBlock(index = 1, reduceMotion = reduceMotion) {
                        EmptyStateCard(
                            title = "Nessun formato trovato",
                            body = "Prova un altro termine di ricerca o rimuovi il filtro categoria."
                        )
                    }
                }
            } else {
                items(filteredOptions) { format ->
                    AnimatedScreenBlock(index = filteredOptions.indexOf(format) + 1, reduceMotion = reduceMotion) {
                        Card(
                            onClick = { onSelect(format) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(format.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    format.mimeType,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    MetricPill(label = "Sigla", value = format.shortLabel, emphasized = true)
                                    MetricPill(label = "Est.", value = format.extension.ifBlank { "-" })
                                    MetricPill(
                                        label = "Runtime",
                                        value = format.availableRuntimeKinds.joinToString { it.name }
                                    )
                                    format.categories.ifEmpty { listOf("Altro") }.forEach { category ->
                                        MetricPill(label = "Categoria", value = category)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

private fun formatIdLabel(formatId: String): String {
    val explicit = formatId.substringAfterLast('(', "").substringBefore(')').trim()
    if (explicit.isNotBlank()) return explicit.uppercase()
    return formatId.substringBefore('(').substringAfterLast('/').ifBlank { formatId }.uppercase()
}

private fun humanFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex += 1
    }
    val rounded = if (value >= 10 || unitIndex == 0) {
        value.roundToInt().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$rounded ${units[unitIndex]}"
}
