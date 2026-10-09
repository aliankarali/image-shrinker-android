package io.github.aliankarali.imageshrinker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aliankarali.imageshrinker.R
import io.github.aliankarali.imageshrinker.core.CropTarget
import io.github.aliankarali.imageshrinker.core.QualityLevel
import io.github.aliankarali.imageshrinker.core.SessionFormat
import io.github.aliankarali.imageshrinker.core.SessionState
import io.github.aliankarali.imageshrinker.data.AppSettings
import kotlinx.coroutines.launch

/**
 * The four session controls. Every value applies the moment it is chosen;
 * closing never discards anything. Closing is refused while the crop has only
 * one side filled in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(vm: MainViewModel, settings: AppSettings) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || !vm.cropIsIncomplete },
    )
    fun close() {
        if (vm.cropIsIncomplete) return
        scope.launch { sheetState.hide() }.invokeOnCompletion { vm.setSessionPanel(false) }
    }

    ModalBottomSheet(onDismissRequest = { vm.setSessionPanel(false) }, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.session_settings_title).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (vm.isSessionModified(settings.quality)) {
                    TextButton(onClick = vm::resetSession) { Text(stringResource(R.string.action_reset)) }
                }
            }

            val appDefault = stringResource(R.string.session_app_default)
            DropdownField(
                label = stringResource(R.string.session_convert_all),
                options = listOf<SessionFormat?>(null) + SessionFormat.entries,
                selected = vm.sessionFormat,
                labelOf = { it?.label ?: appDefault },
                onSelect = { vm.sessionFormat = it },
            )
            if (vm.sessionFormat == SessionFormat.PNG) {
                Text(
                    stringResource(R.string.session_png_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val stored = settings.quality
            DropdownField(
                label = stringResource(R.string.session_quality),
                options = QualityLevel.entries,
                selected = vm.sessionQuality ?: stored,
                labelOf = { level ->
                    val name = Formatting.quality(res, level)
                    if (level == stored) res.getString(R.string.quality_default_suffix, name) else name
                },
                // Picking the stored level clears the override rather than pinning it.
                onSelect = { vm.sessionQuality = if (it == stored) null else it },
            )

            val superseded = SessionState.maxSizeIsSupersededByCrop(vm.cropTarget)
            OutlinedTextField(
                value = vm.maxSizeText,
                onValueChange = vm::updateMaxSize,
                label = { Text(stringResource(R.string.session_max_size)) },
                placeholder = { Text(stringResource(R.string.session_no_limit)) },
                suffix = { Text(stringResource(R.string.px_suffix)) },
                enabled = !superseded,
                singleLine = true,
                supportingText = if (superseded) {
                    { Text(stringResource(R.string.session_max_superseded)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(stringResource(R.string.session_crop_to), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val incomplete = vm.cropIsIncomplete
                OutlinedTextField(
                    value = vm.cropWidthText,
                    onValueChange = vm::updateCropWidth,
                    label = { Text(stringResource(R.string.session_crop_width)) },
                    singleLine = true,
                    isError = incomplete && vm.cropWidthText.isEmpty(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (vm.cropMode == CropTarget.Mode.PIXELS) "×" else ":",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                OutlinedTextField(
                    value = vm.cropHeightText,
                    onValueChange = vm::updateCropHeight,
                    label = { Text(stringResource(R.string.session_crop_height)) },
                    singleLine = true,
                    isError = incomplete && vm.cropHeightText.isEmpty(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val modes = listOf(CropTarget.Mode.RATIO to R.string.crop_mode_ratio, CropTarget.Mode.PIXELS to R.string.crop_mode_px)
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = vm.cropMode == mode,
                        onClick = { vm.cropMode = mode },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }
            Text(
                stringResource(
                    when {
                        vm.cropIsIncomplete -> R.string.session_crop_incomplete
                        vm.cropMode == CropTarget.Mode.PIXELS -> R.string.session_crop_px_help
                        else -> R.string.session_crop_ratio_help
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (vm.cropIsIncomplete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.session_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Button(onClick = ::close, enabled = !vm.cropIsIncomplete) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = labelOf(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
