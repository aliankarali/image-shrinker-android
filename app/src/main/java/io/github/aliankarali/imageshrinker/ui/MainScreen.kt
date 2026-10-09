package io.github.aliankarali.imageshrinker.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aliankarali.imageshrinker.R
import io.github.aliankarali.imageshrinker.core.CropTarget
import io.github.aliankarali.imageshrinker.data.AppSettings
import io.github.aliankarali.imageshrinker.engine.InputSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(vm: MainViewModel, onOpenSettings: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val activity = LocalActivity.current

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.handle(listOf(InputSource.Documents(uris)))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.handle(listOf(InputSource.Tree(uri)))
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(vm) {
        vm.requestNotificationPermission.collect {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // The whole window accepts drops, not just the card (tablets, ChromeOS,
    // desktop mode and split screen).
    var dragging by remember { mutableStateOf(false) }
    val dropTarget = remember(vm, activity) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragging = true }
            override fun onExited(event: DragAndDropEvent) { dragging = false }
            override fun onEnded(event: DragAndDropEvent) { dragging = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragging = false
                val dragEvent = event.toAndroidDragEvent()
                activity?.requestDragAndDropPermissions(dragEvent)
                val clip = dragEvent.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                vm.handle(listOf(InputSource.Shared(uris)))
                return true
            }
        }
    }

    Scaffold(
        modifier = Modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event -> event.mimeTypes().any { it.startsWith("image/") || it == "application/octet-stream" } },
            target = dropTarget,
        ),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
        bottomBar = { SessionBar(vm, settings) },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            item {
                AddImagesCard(
                    dragging = dragging,
                    onFiles = { pickFiles.launch(arrayOf("image/*", "application/octet-stream")) },
                    onFolder = { pickFolder.launch(null) },
                )
            }
            vm.progress?.let { progress -> item { ProgressRow(progress) } }
            if (vm.rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.empty_results),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            } else {
                stickyHeader { RecentHeader(vm.fileCount, vm.bytesSaved, onClear = vm::clearHistory) }
                items(vm.rows, key = { it.id }) { row ->
                    ResultRowItem(row)
                    HorizontalDivider()
                }
            }
        }
    }

    if (vm.isSessionPanelOpen) SessionSheet(vm, settings)
    vm.overwritePrompt?.let { OverwriteDialog(it, onAnswer = vm::answerOverwrite) }
    if (vm.problems.isNotEmpty()) ProblemsDialog(vm.problems, onDismiss = vm::dismissProblems)
}

@Composable
private fun AddImagesCard(dragging: Boolean, onFiles: () -> Unit, onFolder: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        border = BorderStroke(if (dragging) 2.dp else 1.dp, if (dragging) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).background(colors.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, tint = colors.onPrimary)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (dragging) R.string.drop_active else R.string.drop_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.drop_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onFiles) {
                    Icon(painterResource(R.drawable.ic_image), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_choose_files))
                }
                OutlinedButton(onClick = onFolder) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_choose_folder))
                }
            }
        }
    }
}

@Composable
private fun ProgressRow(progress: Progress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            if (progress.total == 0) stringResource(R.string.shrinking_preparing)
            else stringResource(R.string.shrinking_progress, progress.done, progress.total),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(8.dp))
        if (progress.total == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { (progress.done - 1).coerceAtLeast(0) / progress.total.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun RecentHeader(fileCount: Int, bytesSaved: Long, onClear: () -> Unit) {
    val res = LocalResources.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent_header).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(
                        R.string.recent_summary,
                        pluralStringResource(R.plurals.file_count, fileCount, fileCount),
                        Formatting.bytes(res, bytesSaved),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear)) }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun ResultRowItem(row: ResultRow) {
    val context = LocalContext.current
    val res = LocalResources.current
    val percent = when {
        row.savedPercent > 0 -> stringResource(R.string.result_saved_percent, row.savedPercent)
        row.savedPercent < 0 -> stringResource(R.string.result_grew_percent, -row.savedPercent)
        else -> "0%"
    }
    ListItem(
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.action_open)) { open(context, row) },
        headlineContent = { Text(row.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis) },
        supportingContent = {
            Column {
                LinearProgressIndicator(
                    progress = { (row.savedPercent.coerceIn(0, 100)) / 100f },
                    modifier = Modifier.padding(vertical = 6.dp).width(160.dp),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                Text(
                    stringResource(
                        R.string.result_sizes,
                        Formatting.bytes(res, row.originalBytes),
                        Formatting.bytes(res, row.shrunkBytes),
                    ),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (row.keptOriginal) {
                    Text(stringResource(R.string.result_kept_original), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    percent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                IconButton(onClick = { share(context, row) }) {
                    Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share))
                }
            }
        },
    )
}

private fun open(context: Context, row: ResultRow) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(row.uri, row.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
    }
}

private fun share(context: Context, row: ResultRow) {
    val send = Intent(Intent.ACTION_SEND)
        .setType(row.mimeType)
        .putExtra(Intent.EXTRA_STREAM, row.uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

/** The line pinned under the results: what happens to the next files added. */
@Composable
private fun SessionBar(vm: MainViewModel, settings: AppSettings) {
    val modified = vm.isSessionModified(settings.quality)
    val summary = sessionSummary(LocalResources.current, vm, settings)
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.session_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (modified) {
                        val description = stringResource(R.string.session_modified)
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier.size(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .semantics { contentDescription = description },
                        )
                    }
                }
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (modified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (modified) {
                TextButton(onClick = vm::resetSession) { Text(stringResource(R.string.action_reset)) }
            }
            FilledTonalButton(onClick = { vm.setSessionPanel(true) }) {
                Text(stringResource(R.string.action_adjust))
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * "WebP · High · Max 2000px · Crop 16:9". Format, quality and size always state
 * themselves, off values included; a crop is named only when there is one.
 */
fun sessionSummary(res: Resources, vm: MainViewModel, settings: AppSettings): String {
    val crop = vm.cropTarget
    return listOfNotNull(
        vm.sessionFormat?.label ?: res.getString(R.string.session_app_default),
        Formatting.quality(res, vm.sessionQuality ?: settings.quality),
        vm.maxDimension?.let { res.getString(R.string.session_max, it.toString()) }
            ?: res.getString(R.string.session_no_limit),
        crop?.let {
            val id = if (it.mode == CropTarget.Mode.PIXELS) R.string.session_crop_px else R.string.session_crop_ratio
            res.getString(id, it.width.toString(), it.height.toString())
        },
    ).joinToString(" · ")
}

@Composable
private fun ProblemsDialog(problems: List<Problem>, onDismiss: () -> Unit) {
    val res = LocalResources.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.error_title)) },
        text = {
            LazyColumn {
                items(problems.distinct()) { problem ->
                    Text(problemMessage(res, problem), modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
    )
}

private fun problemMessage(res: Resources, problem: Problem): String = when (problem) {
    Problem.CropIncomplete -> res.getString(R.string.error_crop_incomplete)
    Problem.NoImages -> res.getString(R.string.error_no_images)
    is Problem.Unexpected -> res.getString(R.string.error_generic, problem.fileName, problem.message)
    is Problem.Shrink -> {
        val e = problem.error
        when (e.kind) {
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.UNSUPPORTED -> res.getString(R.string.error_unsupported, e.fileName)
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.READ -> res.getString(R.string.error_read, e.fileName)
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.DECODE -> res.getString(R.string.error_decode, e.fileName)
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.ENCODE ->
                res.getString(R.string.error_encode, e.fileName, (e.detail ?: "").uppercase())
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.WRITE -> res.getString(R.string.error_write, e.fileName)
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.SVG -> res.getString(R.string.error_svg, e.fileName, e.detail ?: "")
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.FOLDER_UNAVAILABLE -> res.getString(R.string.error_folder_unavailable)
            io.github.aliankarali.imageshrinker.engine.ShrinkException.Kind.OTHER -> res.getString(R.string.error_generic, e.fileName, e.detail ?: "")
        }
    }
}
