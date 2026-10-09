package app.shrinkerpro.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shrinkerpro.App
import app.shrinkerpro.BuildConfig
import app.shrinkerpro.R
import app.shrinkerpro.core.ConversionFormat
import app.shrinkerpro.core.ConversionTarget
import app.shrinkerpro.core.MetadataPolicy
import app.shrinkerpro.core.OutputNaming
import app.shrinkerpro.core.OutputWarning
import app.shrinkerpro.core.QualityLevel
import app.shrinkerpro.core.SaveLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val res = LocalResources.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            vm.updateSettings { setCustomFolder(uri) }
        }
    }
    val customFolderName by produceState<String?>(null, settings.customFolder) {
        value = settings.customFolder?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull() }
        }
    }

    var notificationsAllowed by remember { mutableStateOf(App.from(context).notifier.canPost()) }
    LifecycleResumeEffect(Unit) {
        notificationsAllowed = App.from(context).notifier.canPost()
        onPauseOrDispose { }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = App.from(context).notifier.canPost()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding) {
            // ---- Output ----
            item { SectionHeader(R.string.section_output) }
            item {
                Column(Modifier.selectableGroup()) {
                    Subheader(stringResource(R.string.settings_where))
                    RadioItem(stringResource(R.string.where_pictures), null, settings.location == SaveLocation.PICTURES) {
                        vm.updateSettings { setLocation(SaveLocation.PICTURES) }
                    }
                    RadioItem(stringResource(R.string.where_same), null, settings.location == SaveLocation.SAME_FOLDER) {
                        vm.updateSettings { setLocation(SaveLocation.SAME_FOLDER) }
                    }
                    val custom = settings.customFolder
                    RadioItem(
                        title = if (custom == null) stringResource(R.string.where_custom)
                        else stringResource(R.string.where_custom_chosen, customFolderName ?: custom.lastPathSegment.orEmpty()),
                        supporting = if (custom != null) stringResource(R.string.where_custom_change) else null,
                        selected = settings.location == SaveLocation.CUSTOM,
                    ) {
                        // Choosing (or re-choosing) the folder is the point of this
                        // option, so selecting it always opens the picker.
                        chooseFolder.launch(custom)
                    }
                }
            }
            if (settings.location == SaveLocation.SAME_FOLDER) item { Footer(stringResource(R.string.where_same_footer)) }
            item {
                SwitchItem(stringResource(R.string.settings_subfolder), settings.useSubfolder) {
                    vm.updateSettings { setUseSubfolder(it) }
                }
            }
            item {
                val naming = OutputNaming.style(settings.location, settings.useSubfolder)
                val beside = naming == OutputNaming.BESIDE_ORIGINALS
                Column(Modifier.selectableGroup()) {
                    Subheader(stringResource(if (beside) R.string.naming_files else R.string.naming_filenames))
                    RadioItem(
                        stringResource(if (beside) R.string.naming_keep_originals else R.string.naming_add_min),
                        if (beside) null else stringResource(R.string.naming_example_min),
                        settings.keepOriginal,
                    ) { vm.updateSettings { setKeepOriginal(true) } }
                    RadioItem(
                        stringResource(if (beside) R.string.naming_replace_originals else R.string.naming_leave_as_is),
                        if (beside) null else stringResource(R.string.naming_example_plain),
                        !settings.keepOriginal,
                    ) { vm.updateSettings { setKeepOriginal(false) } }
                }
            }
            if (OutputWarning.replacesOriginals(settings.keepOriginal, settings.location, settings.useSubfolder)) {
                item { Footer(stringResource(R.string.replace_warning), error = true) }
            }
            item {
                SwitchItem(stringResource(R.string.settings_warn_overwrite), settings.warnBeforeOverwrite) {
                    vm.updateSettings { setWarnBeforeOverwrite(it) }
                }
            }

            // ---- Conversion ----
            item { SectionHeader(R.string.section_conversion) }
            item {
                RuleItem(stringResource(R.string.format_png), "PNG", settings.rules.png) { vm.updateSettings { setPngRule(it) } }
                RuleItem(stringResource(R.string.format_jpeg), "JPEG", settings.rules.jpeg) { vm.updateSettings { setJpegRule(it) } }
                ChoiceItem(
                    title = stringResource(R.string.format_heic),
                    options = ConversionFormat.entries,
                    selected = settings.rules.heic,
                    labelOf = { it.label },
                ) { vm.updateSettings { setHeicRule(it) } }
                RuleItem(stringResource(R.string.format_webp), "WebP", settings.rules.webp) { vm.updateSettings { setWebpRule(it) } }
                RuleItem(stringResource(R.string.format_avif), "AVIF", settings.rules.avif) { vm.updateSettings { setAvifRule(it) } }
            }
            item { Footer(stringResource(R.string.conversion_footer)) }

            // ---- Quality ----
            item { SectionHeader(R.string.section_quality) }
            item {
                ChoiceItem(
                    title = stringResource(R.string.settings_encode_at),
                    options = QualityLevel.entries,
                    selected = settings.quality,
                    labelOf = { Formatting.quality(res, it) },
                ) { vm.updateSettings { setQuality(it) } }
            }
            item { Footer(stringResource(R.string.quality_footer)) }

            // ---- Metadata ----
            item { SectionHeader(R.string.section_metadata) }
            item {
                ChoiceItem(
                    title = stringResource(R.string.settings_keep_metadata),
                    options = MetadataPolicy.entries,
                    selected = settings.metadata,
                    labelOf = { Formatting.metadata(res, it) },
                ) { vm.updateSettings { setMetadata(it) } }
            }
            item { Footer(stringResource(R.string.metadata_footer)) }

            // ---- General ----
            item { SectionHeader(R.string.section_general) }
            item {
                SwitchItem(stringResource(R.string.settings_notifications), settings.notification) { on ->
                    vm.updateSettings { setNotification(on) }
                    if (on && !notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            if (settings.notification && !notificationsAllowed) {
                item {
                    // Say so rather than failing quietly when the system blocks them.
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.notifications_blocked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }) { Text(stringResource(R.string.action_open_settings)) }
                    }
                }
            }
            item {
                SwitchItem(stringResource(R.string.settings_clear_list), settings.clearList) {
                    vm.updateSettings { setClearList(it) }
                }
            }

            // ---- About ----
            item { SectionHeader(R.string.section_about) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME)) },
                    supportingContent = { Text(stringResource(R.string.about_credits)) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: Int) {
    Column {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun Subheader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun Footer(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

@Composable
private fun RadioItem(title: String, supporting: String?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

@Composable
private fun RuleItem(title: String, formatName: String, selected: ConversionTarget, onSelect: (ConversionTarget) -> Unit) {
    val res = LocalResources.current
    ChoiceItem(title, ConversionTarget.entries, selected, { Formatting.conversionTarget(res, it, formatName) }, onSelect)
}

/** A preference row showing its current value; tapping opens a single-choice dialog. */
@Composable
private fun <T> ChoiceItem(title: String, options: List<T>, selected: T, labelOf: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(labelOf(selected)) },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.selectableGroup()) {
                    for (option in options) {
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = option == selected, role = Role.RadioButton) {
                                    onSelect(option)
                                    open = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Text(labelOf(option), modifier = Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
