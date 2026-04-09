package com.p2r3.convert.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
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
import com.p2r3.convert.model.RuntimeAvailability
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
            title = "Conferma il formato sorgente",
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
        selectedInputs.isEmpty() -> "Prima scegli uno o piu file."
        detectedLabels.size > 1 -> "Sono stati rilevati piu formati. Conviene confermare il sorgente a mano."
        uiState.session.selectedSource != null -> "Controlla il formato suggerito e continua."
        else -> "Se il rilevamento non e corretto puoi cambiarlo manualmente."
    }

    val targetSubtitle = when {
        uiState.session.selectedSource == null -> "Il target si sblocca dopo la conferma del sorgente."
        uiState.session.availableTargets.isEmpty() -> "Sto caricando solo gli output davvero disponibili sul device."
        uiState.session.selectedTarget != null -> "${uiState.session.availableTargets.size} output compatibili disponibili."
        else -> "Seleziona uno dei ${uiState.session.availableTargets.size} output compatibili."
    }

    ScreenContainer {
        item {
            AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                SectionTitle(
                    title = "Flusso guidato",
                    body = "Segui i passaggi in ordine: file, sorgente, output e avvio del job."
                )
            }
        }
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
                SelectorCard(
                    stepLabel = "2. Formato sorgente",
                    title = uiState.session.selectedSource?.displayName ?: "Formato sorgente",
                    subtitle = sourceSubtitle,
                    runtimeLabel = uiState.session.selectedSource?.runtimeAvailability?.toReadableLabel(),
                    onClick = { showSourceSheet = true },
                    enabled = selectedInputs.isNotEmpty()
                )
            }
        }
        item {
            AnimatedScreenBlock(index = 3, reduceMotion = reduceMotion) {
                SelectorCard(
                    stepLabel = "3. Formato di output",
                    title = uiState.session.selectedTarget?.displayName ?: "Formato di output",
                    subtitle = targetSubtitle,
                    runtimeLabel = uiState.session.selectedTarget?.runtimeAvailability?.toReadableLabel(),
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
                        fadeIn(animationSpec = tween(if (reduceMotion) 90 else 140)) togetherWith
                            fadeOut(animationSpec = tween(if (reduceMotion) 70 else 100))
                    },
                    label = "route-preview"
                ) { route ->
                    if (route != null) {
                        RouteCard(route)
                    } else {
                        StatusCard(
                            title = "4. Route",
                            body = "Quando sorgente e target sono pronti, qui vedrai runtime, passaggi e livello di affidabilita."
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
                        fadeIn(animationSpec = tween(if (reduceMotion) 90 else 140)) togetherWith
                            fadeOut(animationSpec = tween(if (reduceMotion) 70 else 100))
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
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("1. Scegli i file", style = MaterialTheme.typography.titleLarge)
            Text(
                if (inputs.isEmpty()) {
                    "Apri il picker Android nativo e seleziona quello che vuoi convertire."
                } else {
                    "Controlla i file selezionati prima di confermare il formato sorgente."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryChip(label = "${inputs.size} file", highlighted = inputs.isNotEmpty())
                SummaryChip(label = humanFileSize(totalBytes))
                if (inputs.isNotEmpty()) {
                    SummaryChip(label = "Rilevati $detectedCount/${inputs.size}")
                }
                detectedLabels.take(2).forEach { label ->
                    SummaryChip(label = label)
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(onClick = onPickFiles) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scegli file")
                }
                if (inputs.isNotEmpty()) {
                    OutlinedButton(onClick = onClearSelection) {
                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Pulisci")
                    }
                }
            }

            if (inputs.isEmpty()) {
                Text(
                    "Il rilevamento usa nome file e MIME Android, ma puoi sempre correggere il formato manualmente.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                inputs.take(4).forEachIndexed { index, input ->
                    if (index > 0) {
                        HorizontalDivider()
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                input.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = {
                            Text(
                                "${input.detectedFormatLabel ?: "Da confermare"} - ${input.mimeType ?: "MIME sconosciuto"} - ${humanFileSize(input.sizeBytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
                if (inputs.size > 4) {
                    Text(
                        "Altri ${inputs.size - 4} file selezionati.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectorCard(
    stepLabel: String,
    title: String,
    subtitle: String,
    runtimeLabel: String?,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            runtimeLabel?.let {
                SummaryChip(label = it, icon = Icons.Outlined.AutoAwesome, highlighted = enabled)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteCard(route: RoutePreview) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("4. Route", style = MaterialTheme.typography.titleLarge)
            Text(
                route.runtimeAvailability.toReadableLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SummaryChip(label = route.runtimeKind.name, icon = Icons.Outlined.Route, highlighted = true)
                SummaryChip(label = route.etaLabel)
                SummaryChip(label = route.confidenceLabel)
            }

            route.steps.forEachIndexed { index, step ->
                if (index > 0) {
                    HorizontalDivider()
                }
                ListItem(
                    headlineContent = {
                        Text("${formatIdLabel(step.fromFormatId)} -> ${formatIdLabel(step.toFormatId)}")
                    },
                    overlineContent = { Text(step.runtimeKind.name) },
                    supportingContent = {
                        Text(
                            "${step.handlerName} - ${step.performanceClass} - ${step.batchStrategy}\n${step.note}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            route.reasons.forEach { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PreviewCard(preview: ConversionPreview?) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("5. Anteprima", style = MaterialTheme.typography.titleLarge)
            if (preview == null) {
                Text(
                    "Se la route lo consente, qui vedrai un'anteprima leggera prima del job.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                SummaryChip(
                    label = preview.kind.name.replace('_', ' ').lowercase(Locale.ROOT).replaceFirstChar(Char::titlecase),
                    highlighted = preview.supported
                )
                Text(preview.headline, style = MaterialTheme.typography.titleMedium)
                Text(
                    preview.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (preview.kind) {
                    PreviewKind.IMAGE_PROXY -> {
                        preview.proxyUri?.let { imageUri ->
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
                            body = preview.textPreview ?: "Il contenuto finale puo essere mostrato direttamente qui."
                        )
                    }

                    PreviewKind.DOCUMENT -> {
                        StatusCard(
                            title = "Anteprima documento",
                            body = "Il documento finale viene generato on-device quando parte il job."
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
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AnimatedScreenBlock(index = 0, reduceMotion = reduceMotion) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        SearchBar(
                            inputField = {
                                SearchBarDefaults.InputField(
                                    query = query,
                                    onQueryChange = { query = it },
                                    onSearch = {},
                                    expanded = false,
                                    onExpandedChange = {},
                                    placeholder = { Text("Cerca formato") }
                                )
                            },
                            expanded = false,
                            onExpandedChange = {},
                            modifier = Modifier.fillMaxWidth()
                        ) { }
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
                                    onClick = {
                                        selectedCategory = if (selectedCategory == category) null else category
                                    },
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
                            body = "Prova un altro termine oppure rimuovi il filtro attivo."
                        )
                    }
                }
            } else {
                itemsIndexed(filteredOptions, key = { _, format -> format.id }) { index, format ->
                    AnimatedScreenBlock(index = index + 1, reduceMotion = reduceMotion) {
                        ElevatedCard(
                            onClick = { onSelect(format) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
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
                                    SummaryChip(label = format.shortLabel, highlighted = true)
                                    SummaryChip(label = format.extension.ifBlank { "-" })
                                    SummaryChip(label = format.runtimeAvailability.toReadableLabel())
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatIdLabel(formatId: String): String {
    val explicit = formatId.substringAfterLast('(', "").substringBefore(')').trim()
    if (explicit.isNotBlank()) return explicit.uppercase()
    return formatId.substringBefore('(').substringAfterLast('/').ifBlank { formatId }.uppercase()
}

private fun RuntimeAvailability.toReadableLabel(): String = when (this) {
    RuntimeAvailability.NATIVE -> "Runtime nativo"
    RuntimeAvailability.VALIDATED_BRIDGE -> "Bridge validato"
    RuntimeAvailability.UNAVAILABLE -> "Non disponibile"
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
